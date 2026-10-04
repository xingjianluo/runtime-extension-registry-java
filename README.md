# 运行时可热更新的扩展注册与执行机制

在 Spring Boot 工程上实现的一套**运行时扩展注册中心**：扩展可在不重启进程的前提下注册（编程式）、
启停、热更新配置与移除；一次变更**要么整体生效、要么整体回滚**；并发读写始终看到一致、可解释的状态；
单个扩展失败被故障隔离并归一化为可区分的对外错误。在此之上还提供**带递增编号的历史版本留存
（含相对上一版的启停/配置差异、可配保留上限与最旧先淘汰）与一键原子回退**，回退成功即产生更新编号、
原历史不被覆盖，目标含已不存在扩展或当前依赖下无法就绪时整体拒绝。

- Java 21 / Spring Boot 3.4.x，无任何额外依赖（仅 web + test）。
- 入口包：`com.github.xingjianluo.runtimeextensionregistryjava.extension`。

---

## 1. 快速开始与本地验证

### 1.1 运行测试（推荐，覆盖并发热更新与回滚）

```bash
mvn test
```

共 71 个用例（其中并发回退用例以 `@RepeatedTest(30)` 反复执行 30 轮），包含：

| 测试类 | 覆盖内容 |
| --- | --- |
| `VersionRangeTest` | 语义化版本解析、精确版本与区间兼容判定、非法表达式拒绝 |
| `DependencyResolutionTest` | 重复标识、依赖缺失、版本不兼容、非法范围、直接/传递循环依赖的可区分拒绝 |
| `HotReloadRollbackTest` | 配置热更新原子替换、未受影响扩展不重复初始化、无效配置/初始化失败回滚、启停、批次 all-or-nothing、依赖未就绪拒绝执行 |
| `ConcurrentUpdateTest` | 6 写 4 读并发：无撕裂状态、初始化次数精确、被拒变更期间持续可用、读者观察到多个已提交世代 |
| `FailureIsolationTest` | 单扩展执行失败不影响其他扩展、错误码与上下文、初始化失败整批回滚 |
| `HistoryAndEvictionTest` | 递增版本与精确差异、历史列表从新到旧、被拒不入历史、保留上限最旧先淘汰、已淘汰/不存在编号报"查不到" |
| `RollbackBehaviorTest` | 回退成功状态一致并产生新编号、原历史不覆盖、未受影响不重复初始化、回退到相同状态不初始化、目标含已移除/事后新增扩展被拒、当前无法就绪被拒且不半回退、反复回退再改回稳定收敛 |
| `RollbackConcurrencyTest` | 受控交错（初始化闸门+发生序）+ 逐轮屏障：读/热更新/回退真实同屏、无撕裂、初始化次数按历史 diff 精确对账、30 轮稳定 |
| `WebApiIntegrationTest` | 端到端 HTTP：热更新、拒绝回滚、错误归一化响应不含堆栈 |
| `HistoryRollbackWebApiTest` | 端到端 HTTP：历史列表/详情、淘汰与未知版本 404、回退成功、集合不一致 409 |

单测日志会打印**每轮输入配置与判定依据**，例如：
`change request received: action=update-config, extensionId=transform, enabled=null, configKeys=[mode]`、
`scenario=missing-dependency expectedRejection=MISSING_DEPENDENCY ...`、
`scenario=reconcile rounds=150 accepted=... rejected=... rollbacksOk=... checks=... rebuilt=... totalInits=...`。

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

# 浏览历史版本（从新到旧，含保留上限与当前保留条数）
curl -s localhost:8080/api/extensions/history

# 查某个历史版本（含相对上一版本的启停/配置差异与完整快照）
curl -s localhost:8080/api/extensions/history/2

# 一键回退到历史版本 2：成功返回新编号（比当前更新），对外状态与版本 2 一致
curl -s -X POST localhost:8080/api/extensions/history/2/rollback

# 回退/查询一个已淘汰或不存在的版本 -> 404 HISTORY_VERSION_NOT_FOUND
curl -s -i localhost:8080/api/extensions/history/99999
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

## 5. 历史版本与一键回退

每次**成功**提交（既包括普通热更新 `apply(ChangeSet)`，也包括回退）都会把当时的完整运行状态
留存为一个带**严格递增编号**的历史版本，版本号与提交后的世代号一致。

### 5.1 版本留存内容与差异

每个历史版本（`HistoryVersion`）包含：

| 字段 | 含义 |
| --- | --- |
| `version` | 版本编号（世代号），严格递增 |
| `kind` | 产生方式：`CHANGE`（普通变更）/ `ROLLBACK`（回退提交） |
| `revertedFrom` | 仅回退版本有：本次回退的目标版本编号 |
| `diff` | 相对**上一个已提交版本**的差异（`VersionDiff`） |
| `snapshot` | 该版本提交时刻的**完整不可变快照**（全部扩展的启停/就绪/依赖/配置） |

`diff.changes` 按扩展标识列出本次受影响的条目（`EntryChange`），可一眼看出"这一回动了谁"：

- `ADDED` / `REMOVED`：扩展新出现 / 被移除（携带前后启停与配置，缺失侧为 `null`）；
- `ENABLED_TOGGLED`：仅启停变化；
- `CONFIG_CHANGED`：仅配置变化；
- `TOGGLED_AND_CONFIG_CHANGED`：启停与配置同时变化。

被拒绝的变更不产生历史、不推进编号；编号只在成功提交后单调增加。

### 5.2 保留上限与淘汰

历史不会无限增长。保留条数由配置项控制，**超出上限时最旧的版本先淘汰**（oldest-first）：

```properties
# application.properties；也可用环境变量 EXTENSION_HISTORY_RETENTION_LIMIT 覆盖，默认 20
extension.history.retention-limit=20
```

编程式构造时也可显式指定：`new DefaultExtensionRegistry(50)`（取值必须 ≥ 1）。

查询一个**已经被淘汰**或**压根不存在**的版本号时，明确返回"查不到"：

- 编程式 API `historyVersion(n)` 返回 `Optional.empty()`；
- HTTP `GET /api/extensions/history/{n}` 返回 **404 `HISTORY_VERSION_NOT_FOUND`**
  （上下文携带 `version`、`retentionLimit`、`oldestRetained`、`latestRetained`，便于区分"太旧被淘汰"
  与"从未存在"），**不会**返回空结果，也不会报 500。

### 5.3 一键回退的语义

`rollback(n)`（HTTP `POST /api/extensions/history/{n}/rollback`）把运行状态恢复成历史版本 `n`：

1. **回退本身也是一次原子变更**，复用第 4 节的隔离提交流程：先在隔离区完成全部校验与初始化，
   全部成功后才单次引用切换；任何一步失败都整体作废，当前对外状态原封不动、持续可用。
2. 成功后：
   - 对外可见的**启用状态与配置与目标版本逐字段一致**；
   - 产生一个**比当前更新的新编号**（当前世代 +1），`kind=ROLLBACK`、`revertedFrom=n`；
   - **原有历史一律不被覆盖、不被改写**，目标版本仍可按原编号反复回退；
   - 新版本的 `diff` 是相对"回退前一刻"的差异，可看出这次回退实际拨动了哪些扩展。
3. 回退到与当前完全相同的状态也允许提交（产生新编号、`diff` 为空），但**不初始化任何实例**。
4. **未受影响的扩展不重复初始化**：只有配置或启停真正变化、或依赖实例被重建的扩展才重新初始化，
   其余实例原样复用；一次成功（重新）初始化对应旧实例一次 `shutdown`。

### 5.4 回退被整体拒绝的边界

回退遵循"按**现在**的依赖与兼容关系重新就绪"的原则。以下情况**整体拒绝**，返回
**409 `ROLLBACK_NOT_POSSIBLE`**（可与"版本查不到"`HISTORY_VERSION_NOT_FOUND` 及其它失败区分），
绝不只回退一半，当前对外状态保持原样可用：

- **扩展集合不一致**：目标版本里包含**现在已经不存在**的扩展（之后被 `remove` 移除），
  或当前存在**目标版本之后才注册**的扩展。上下文分别给出 `missingInCurrent` / `addedAfterTarget`。
- **当前依赖/兼容关系下无法就绪**：例如恢复出的启用状态形成当前不满足的依赖链，
  或恢复出的配置在当前工厂的 `initialize` 校验下非法、初始化抛出异常。
  上下文给出 `reason`（底层拒绝码，如 `MISSING_DEPENDENCY` / `INVALID_CONFIG` /
  `INITIALIZATION_FAILED`）与 `reasonContext`，原始内部细节不随 HTTP 透出。

被拒绝时：不推进编号、不产生历史、本次新建实例全部被丢弃并 `shutdown`、旧实例完全不动。

> 关于版本/依赖声明：回退恢复的是目标版本的**配置与启用状态**；版本号与依赖声明以**当前已注册
> 定义**为准（这两者本就无法通过运行时命令修改）。因此"版本不兼容/依赖图成环"这类问题，本质会在
> 当前依赖关系校验阶段暴露为 `ROLLBACK_NOT_POSSIBLE`，而不会恢复出一个无法自洽的状态。

### 5.5 与现有启停 / 配置更新的兼容关系

- 回退与普通 `apply`、读取、执行共用同一把写锁与同一条 immutable-generation 发布路径；
  回退与其它热更新**写-写串行**，与读取**读-写并发**。
- 任何时刻读者要么看到完整旧状态、要么看到完整新状态，看不到回退处理到一半的中间状态；
  被拒绝的回退期间服务持续可用。
- 反复在不同历史版本间回退、再热改回去，状态始终稳定收敛；回退锚点若已被保留上限淘汰，
  则按"查不到"处理（`HISTORY_VERSION_NOT_FOUND`），需要时可调大保留上限。

---

## 6. 错误归一化与故障隔离

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
| `HISTORY_VERSION_NOT_FOUND` | 404 | 历史版本不存在或已被保留上限淘汰（区别于回退被拒） |
| `ROLLBACK_NOT_POSSIBLE` | 409 | 回退目标扩展集合与当前不一致，或当前依赖/兼容关系下无法就绪（整体拒绝） |
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

## 7. HTTP API

| 方法与路径 | 说明 |
| --- | --- |
| `GET /api/extensions` | 当前世代快照：`generation` 与每个扩展的 `enabled/ready/dependencies/blockedBy/config` |
| `GET /api/extensions/{id}` | 单个扩展状态视图，不存在返回 404 |
| `POST /api/extensions/changes` | 提交一批原子变更（数组，all-or-nothing），返回新生效世代与快照 |
| `POST /api/extensions/{id}/invoke` | 在当前世代执行扩展，返回 `{generation, extensionId, data}` |
| `GET /api/extensions/history` | 历史版本列表：`retentionLimit/retainedCount` + 从新到旧的轻量条目（编号/类型/差异扩展） |
| `GET /api/extensions/history/{n}` | 取第 n 个历史版本（含 `diff` 与完整 `snapshot`）；不存在或已淘汰返回 404 |
| `POST /api/extensions/history/{n}/rollback` | 原子回退到第 n 个历史版本，成功返回新编号与快照；集合不一致/无法就绪返回 409，版本查不到返回 404 |

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

## 8. 代码地图

```
extension/
├── model/        Version, VersionRange, ExtensionDefinition, DependencyRequirement,
│                 ChangeCommand(sealed), ChangeSet, RegistrySnapshot, ExtensionStatusView, ChangeOutcome,
│                 VersionKind, EntryChange, VersionDiff, HistoryVersion, HistorySummary, HistoryPage
├── spi/          Extension, ExtensionFactory, ExtensionContext, ExtensionInvocation, ExtensionResult
├── error/        ErrorCode（含 HISTORY_VERSION_NOT_FOUND / ROLLBACK_NOT_POSSIBLE）,
│                 ExtensionException（唯一对外异常形态）
├── registry/     ExtensionRegistry（接口）, DefaultExtensionRegistry（世代+COW+写锁+历史留存+回退）,
│                 DependencyResolver（范围/缺失/兼容/环校验 + 拓扑序）
├── web/          ExtensionController（含 history/history/{n}/history/{n}/rollback 端点）,
│                 ApiExceptionHandler, ApiError, ChangeRequest
└── sample/       EchoExtension, TransformExtension, StrictConfigExtension, SampleRegistrySeeder
```
