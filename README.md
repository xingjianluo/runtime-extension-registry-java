# 运行时可热更新的扩展注册与执行机制

在 Spring Boot 工程上实现的一套**运行时扩展注册中心**：扩展可在不重启进程的前提下注册（编程式）、
启停、热更新配置与移除；一次变更**要么整体生效、要么整体回滚**；并发读写始终看到一致、可解释的状态；
单个扩展失败被故障隔离并归一化为可区分的对外错误。

- Java 21 / Spring Boot 3.4.x，无任何额外依赖（仅 web + test）。
- 入口包：`com.github.xingjianluo.runtimeextensionregistryjava.extension`。

---

## 1. 快速开始与本地验证

### 1.1 运行测试（推荐，覆盖并发热更新与回滚）

```bash
mvn test
```

共 32 个用例，包含：

| 测试类 | 覆盖内容 |
| --- | --- |
| `VersionRangeTest` | 语义化版本解析、精确版本与区间兼容判定、非法表达式拒绝 |
| `DependencyResolutionTest` | 重复标识、依赖缺失、版本不兼容、非法范围、直接/传递循环依赖的可区分拒绝 |
| `HotReloadRollbackTest` | 配置热更新原子替换、未受影响扩展不重复初始化、无效配置/初始化失败回滚、启停、批次 all-or-nothing、依赖未就绪拒绝执行 |
| `ConcurrentUpdateTest` | 6 写 4 读并发：无撕裂状态、初始化次数精确、被拒变更期间持续可用、读者观察到多个已提交世代 |
| `FailureIsolationTest` | 单扩展执行失败不影响其他扩展、错误码与上下文、初始化失败整批回滚 |
| `WebApiIntegrationTest` | 端到端 HTTP：热更新、拒绝回滚、错误归一化响应不含堆栈 |

单测日志会打印**输入配置与判定依据**，例如：
`change request received: action=update-config, extensionId=transform, enabled=null, configKeys=[mode]`、
`scenario=missing-dependency expectedRejection=MISSING_DEPENDENCY ...`。

### 1.2 启动应用并用 HTTP 验证

```bash
mvn spring-boot:run                       # 默认 8080；端口被占用时：SERVER_PORT=8081 mvn spring-boot:run
curl -s http://localhost:8080/api/extensions
```

启动时播种器（`SampleRegistrySeeder`）以**一批原子变更**注册三个示例扩展：

| 标识 | 版本 | 依赖 | 默认 | 配置 |
| --- | --- | --- | --- | --- |
| `echo` | 1.0.0 | 无 | 启用 | `{"prefix":"echo"}` |
| `transform` | 1.0.0 | `echo [1.0.0,2.0.0)` | 启用 | `{"mode":"UPPER"}` |
| `strict` | 1.0.0 | 无 | 停用 | `{"required":"seed-value"}` |

常用验证命令：

```bash
# 执行扩展
curl -s -X POST localhost:8080/api/extensions/echo/invoke \
  -H 'Content-Type: application/json' -d '{"text":"hello"}'

# 热更新配置（合法 -> 新生效世代）
curl -s -X POST localhost:8080/api/extensions/changes \
  -H 'Content-Type: application/json' \
  -d '[{"action":"update-config","extensionId":"transform","config":{"mode":"LOWER"}}]'

# 无效配置 -> 409 INVALID_CONFIG，整体回滚，transform 维持上一个合法配置
curl -s -X POST localhost:8080/api/extensions/changes \
  -H 'Content-Type: application/json' \
  -d '[{"action":"update-config","extensionId":"transform","config":{"mode":"SIDEWAYS"}}]'

# 停用依赖：transform 立即 blockedBy=[echo]，执行返回 409 DEPENDENCY_NOT_READY
curl -s -X POST localhost:8080/api/extensions/changes \
  -H 'Content-Type: application/json' -d '[{"action":"disable","extensionId":"echo"}]'

# 同一批次"坏配置 + 启用"整体拒绝，strict 保持停用
curl -s -X POST localhost:8080/api/extensions/changes -H 'Content-Type: application/json' \
  -d '[{"action":"update-config","extensionId":"strict","config":{"required":""}},
       {"action":"enable","extensionId":"strict"}]'
```

---

## 2. 扩展契约（SPI）

扩展实现 `extension.spi.Extension`（三个方法均有默认空实现，按需覆写）：

```java
public interface Extension {
    /** 每次"配置生效"时调用。配置非法请抛 IllegalArgumentException。 */
    void initialize(ExtensionContext context) throws Exception;
    /** 处理一次请求。抛任意异常都会被注册中心归一化为 EXECUTION_FAILED。 */
    Object execute(Map<String, Object> input) throws Exception;
    /** 停用/移除/被新实例替换（成功切换之后）调用，用于释放资源。 */
    void shutdown();
}
```

配套类型：

- `ExtensionFactory`（函数式接口）：`create()` 产生新实例，绑定在 `ExtensionDefinition` 上。
- `ExtensionContext`：`config()` 取本次生效的不可变配置；`dependency(id)` 取**已就绪**依赖实例，
  依赖不存在或未就绪时返回 `Optional.empty()`。依赖实例随配置重建时，依赖方也会被重建，因此扩展可安全持有依赖引用。

注册一个扩展（编程式 API，携带工厂）：

```java
ExtensionDefinition def = new ExtensionDefinition(
        "transform",
        Version.parse("1.0.0"),
        List.of(new DependencyRequirement("echo", "[1.0.0,2.0.0)")),
        true,                                  // 初始是否启用
        Map.of("mode", "UPPER"),               // 初始配置（构造后不可变）
        TransformExtension::new);              // 工厂
registry.apply(new ChangeSet(List.of(new ChangeCommand.RegisterDefinition(def))));
```

契约要点：

1. **标识唯一**：`id` 重复（已存在或同一批次内）→ `DUPLICATE_EXTENSION_ID`，绝不静默覆盖。
2. **初始化即校验**：`initialize` 抛 `IllegalArgumentException` 归一化为 `INVALID_CONFIG`；
   抛其他异常归一化为 `INITIALIZATION_FAILED`，二者可区分。
3. **执行无副作用于注册表**：`execute` 抛错不摘除、不停用扩展，其他扩展与整体请求处理不受影响。
4. **生命周期严格成对**：一次成功的（重新）初始化，对应旧实例一次 `shutdown()`；
   未受变更影响的实例原样复用，不重复初始化。

---

## 3. 配置与兼容规则

### 3.1 版本与范围

- 版本：语义化三段 `major.minor.patch`（如 `1.2.3`），按数值比较。
- 兼容范围表达式：
  - 精确版本：`1.2.3`，仅该版本满足；
  - 区间：`[1.0.0,2.0.0)`——`[`/`]` 闭端，`(`/`)` 开端；端点留空表示无界，
    如 `[1.0.0,)`、`(,2.0.0]`。
- 范围表达式无法解析 → `INVALID_VERSION_RANGE`（上下文含扩展标识、依赖标识与原始表达式）。
- 依赖版本不在范围内 → `INCOMPATIBLE_DEPENDENCY_VERSION`（上下文同时给出要求范围与实际版本）。

### 3.2 依赖与就绪（ready）判定

校验在每次变更的**候选定义全集**（应用完该批次所有命令后的结果）上进行：

1. 每个依赖必须能在候选集合中找到，否则 `MISSING_DEPENDENCY`；
2. 版本范围必须满足；
3. 依赖图必须无环（DFS 三色检测），有环则 `CYCLIC_DEPENDENCY`，上下文给出可读环链（如 `a -> b -> a`）。

就绪规则：扩展 `enabled=true` 且其依赖链上所有扩展都就绪时 `ready=true`。

- 主动停用：`enabled=false`，执行返回 `EXTENSION_DISABLED`；
- 启用但被依赖阻断：`enabled=true, ready=false`，视图中 `blockedBy` 列出未就绪依赖，
  执行返回 `DEPENDENCY_NOT_READY`——**依赖尚未就绪的请求不会被执行**。

### 3.3 变更命令（一个 ChangeSet 是一次原子变更）

| 命令 | 含义 | 关键拒绝 |
| --- | --- | --- |
| `RegisterDefinition` | 注册新扩展（含工厂） | 标识重复、版本/范围/依赖/环非法 |
| `UpdateConfig` | 热更新配置 | 扩展不存在；扩展初始化校验失败 |
| `SetEnabled` | 启用/停用 | 扩展不存在 |
| `RemoveExtension` | 移除 | 扩展不存在；被依赖时属于调用方选择（见下注） |

> 注：依赖校验针对最终候选集合。移除一个仍被他人依赖的扩展，会令依赖方因
> `MISSING_DEPENDENCY` 而使整批变更被拒绝，避免产生悬空依赖。

---

## 4. 原子生效与回滚语义

`DefaultExtensionRegistry` 采用 **immutable generation + copy-on-write** 模型：

1. 当前生效状态是一个 `volatile` 引用，指向不可变的 `Generation`（世代号 + 全部条目）。
2. `apply(ChangeSet)` 在写锁内的**隔离区域**工作：
   1. 在副本上按序应用全部命令，得到候选定义表；
   2. 对候选表做依赖校验（范围解析 → 缺失 → 版本不兼容 → 环）；
   3. 按拓扑序（被依赖者在前）初始化：定义与依赖均未变化的实例**原样复用**，
      其余通过工厂新建并调用 `initialize`。
3. 任何一步失败：**丢弃本次新建实例（逐个 shutdown），已发布世代与旧实例完全不动**，
   对外仍可正常服务；异常带可区分错误码与定位上下文抛出。
4. 全部成功后，执行**唯一一次引用赋值**发布新世代——其他线程要么看到完整旧状态，
   要么看到完整新状态，不存在部分生效/撕裂状态；随后才关闭不再被引用的旧实例。
5. 世代号在每次成功提交后 +1；执行结果（`ExtensionResult`）回带它所基于的世代号。

并发性质（由 `ConcurrentUpdateTest` 验证）：

- 写-写串行化，不会出现同一扩展被并发重复初始化；
- 读路径无锁，单次请求内固定读取一个世代（快照与执行结果世代一致）；
- 被拒绝的变更不推进世代，读/执行在此期间持续可用。

---

## 5. 错误归一化与故障隔离

对外只有 `ApiError` 一种错误体：`{code, message, context}`，**不含堆栈、不含底层异常原文**。
错误原文与 cause 链只写入服务端日志。

| code | HTTP | 触发场景 |
| --- | --- | --- |
| `DUPLICATE_EXTENSION_ID` | 409 | 注册标识重复 |
| `INVALID_VERSION_RANGE` | 409 | 依赖版本范围表达式非法 |
| `MISSING_DEPENDENCY` | 409 | 依赖在候选集合中不存在 |
| `INCOMPATIBLE_DEPENDENCY_VERSION` | 409 | 依赖版本不兼容 |
| `CYCLIC_DEPENDENCY` | 409 | 依赖成环（context 含 cycle） |
| `EXTENSION_NOT_FOUND` | 404 | 操作/执行未知扩展 |
| `EXTENSION_DISABLED` | 409 | 扩展被主动停用 |
| `DEPENDENCY_NOT_READY` | 409 | 依赖链未就绪（context 含 blockedBy） |
| `INVALID_CONFIG` | 409 | 扩展初始化拒绝配置（响应剥离内部 detail） |
| `INITIALIZATION_FAILED` | 409 | 初始化抛非校验类异常（响应剥离内部 detail） |
| `EXECUTION_FAILED` | 502 | 执行抛异常（context 含 extensionId/generation，隔离于其他扩展） |
| `BAD_REQUEST` | 400 | 请求体/action/入参非法 |
| `INTERNAL_ERROR` | 500 | 兜底未归类错误，不透出任何内部信息 |

故障隔离边界：扩展执行在注册表线程内同步进行，异常被捕获并归一化；
注册表状态（含失败扩展自身的可调用性）不被改变。

---

## 6. HTTP API

| 方法与路径 | 说明 |
| --- | --- |
| `GET /api/extensions` | 当前世代快照：`generation` 与每个扩展的 `enabled/ready/dependencies/blockedBy/config` |
| `GET /api/extensions/{id}` | 单个扩展状态视图，不存在返回 404 |
| `POST /api/extensions/changes` | 提交一批原子变更（数组，all-or-nothing），返回新生效世代与快照 |
| `POST /api/extensions/{id}/invoke` | 在当前世代执行扩展，返回 `{generation, extensionId, data}` |

变更请求体字段：

```json
[
  {"action": "enable",        "extensionId": "strict"},
  {"action": "disable",       "extensionId": "echo"},
  {"action": "update-config", "extensionId": "transform", "config": {"mode": "LOWER"}},
  {"action": "remove",        "extensionId": "strict"}
]
```

> 新扩展注册需要提供实例工厂，无法通过 JSON 表达，因此仅开放编程式 API
> （`ChangeCommand.RegisterDefinition`）；HTTP 覆盖全部运行时热更新操作。

---

## 7. 代码地图

```
extension/
├── model/        Version, VersionRange, ExtensionDefinition, DependencyRequirement,
│                 ChangeCommand(sealed), ChangeSet, RegistrySnapshot, ExtensionStatusView, ChangeOutcome
├── spi/          Extension, ExtensionFactory, ExtensionContext, ExtensionInvocation, ExtensionResult
├── error/        ErrorCode, ExtensionException（唯一对外异常形态）
├── registry/     ExtensionRegistry（接口）, DefaultExtensionRegistry（世代+COW+写锁）,
│                 DependencyResolver（范围/缺失/兼容/环校验 + 拓扑序）
├── web/          ExtensionController, ApiExceptionHandler, ApiError, ChangeRequest
└── sample/       EchoExtension, TransformExtension, StrictConfigExtension, SampleRegistrySeeder
```
