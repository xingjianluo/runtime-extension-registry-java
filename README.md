# 运行时可热更新扩展注册与执行机制

在 Spring Boot 骨架之上实现的一套**运行时可热更新**的扩展注册、依赖解析、配置变更与执行框架。
扩展的启停、依赖解析与配置变更在并发访问下具有一致且可解释的结论；单个扩展故障被隔离并归一化为
稳定的对外错误码；所有验证均可通过工程已有依赖（Spring Boot Test / JUnit 5）在本地完成。

## 1. 扩展契约（Extension Contract）

每个扩展以**唯一标识 `id`** 注册，并声明自身版本、实现类型 `kind`、依赖列表与初始配置。

| 项 | 说明 |
| --- | --- |
| `id` | 全局唯一，非空；重复注册被 `DUPLICATE_EXTENSION_ID` 拒绝，**绝不静默覆盖** |
| `version` | 数字语义化版本 `major.minor.patch`（缺省部分补 0，如 `1` → `1.0.0`），非法为 `INVALID_VERSION` |
| `kind` | 实现类型，对应一个已注册的 `ExtensionFactory`；未知类型为 `UNKNOWN_EXTENSION_KIND` |
| `dependencies` | 依赖声明列表：`{ extensionId, versionRange }` |
| `config` | 字符串键值配置；由对应工厂的 `validateConfig` 校验，非法为 `INVALID_CONFIG` |

### SPI

```java
public interface Extension {
    void initialize(ExtensionRuntimeContext context) throws Exception; // 构造后仅一次
    Map<String, Object> execute(Map<String, Object> input, ExtensionRuntimeContext context) throws Exception;
    default void destroy() { }                                          // 退役时仅一次
}

public interface ExtensionFactory {
    String kind();
    void validateConfig(ExtensionDefinition definition) throws Exception; // 变更预检，不产生实例
    Extension create(ExtensionDefinition definition);
}
```

`ExtensionRuntimeContext` 提供本次生效配置与依赖访问：`dependency(id)` 只返回在当前状态下
**已启用且就绪**的依赖，否则为空。扩展无法绕过注册中心调用到未就绪依赖。

### 生命周期与就绪语义

- 新注册的扩展初始为 `DISABLED`。
- `ENABLED` 快照一定持有一个已成功 `initialize` 的存活实例；`DISABLED` 快照不持有实例。
- **依赖未就绪时，针对扩展的请求不会被执行**：执行前再次校验全部依赖在同一状态快照内就绪，
  否则返回 `DEPENDENCY_NOT_READY`；扩展自身停用返回 `EXTENSION_NOT_READY`。

## 2. 兼容范围规则（Version Range）

采用显式、可解释的范围语法：

| 写法 | 含义 |
| --- | --- |
| `1.2.0` | 精确版本（无括号 token） |
| `[1.0.0,2.0.0)` | 下界包含、上界排除 |
| `(1.0.0,1.5.0]` | 下界排除、上界包含 |
| `[1.0.0,)` / `(,1.5.0]` | 单侧无界 |

方括号 `[ ]` 表示包含，圆括号 `( )` 表示排除；边界必须是数字版本；下界不得大于上界。
任何解析失败归一为 `INVALID_VERSION_RANGE`。依赖版本不在范围内为 `VERSION_INCOMPATIBLE`，
错误上下文带 `requiredRange` 与 `actualVersion`。

### 依赖解析失败原因（可区分，互不混淆）

| 错误码 | 触发条件 |
| --- | --- |
| `DEPENDENCY_NOT_FOUND` | 依赖标识在注册表中不存在 |
| `VERSION_INCOMPATIBLE` | 依赖存在但版本不满足声明范围 |
| `DEPENDENCY_NOT_READY` | 依赖存在且版本兼容，但在目标状态下未启用 |
| `CYCLIC_DEPENDENCY` | 依赖图存在环（DFS 与拓扑排序双重检测，错误上下文带环路径） |

注意：注册阶段只校验 `id` 唯一、`kind` 已知与**基础配置合法性**；依赖的存在性、兼容性与就绪性
在"启用 / 变更"阶段针对**变更后的假设世界**整体校验，因此"先注册依赖再注册被依赖者"顺序无关。

## 3. 配置热更新与原子生效（回滚语义）

`POST /api/extensions/changes` 提交一个**变更集**（一次可含多个条目）：

```json
{ "items": [ { "extensionId": "a", "enabled": true, "config": { "prefix": "p-" } } ] }
```

- `enabled` 为 `null`：维持当前启停状态；`config` 为 `null`：维持当前配置。
- 同一变更集内同一扩展出现多次 → `CHANGE_SET_REJECTED`。
- 变更集是**原子**的：要么整体生效，要么整体回滚，绝不部分生效。

提交后，注册中心在单写锁内依次完成：

1. 解析全部目标条目，拒绝未知扩展、重复条目；
2. 构造变更后的完整假设世界（含未被条目直接触及的扩展，防止"禁用 base 却让依赖它的 upper
   仍启用"这类**间接破坏**）；
3. 对每个更新配置的扩展执行 `validateConfig`；
4. 对目标世界里**所有**启用扩展校验依赖（缺失 / 不兼容 / 未就绪 / 环），并按拓扑序计算初始化顺序；
5. 按依赖优先顺序 `create` + `initialize` 所需新实例；
6. 全部成功后，用一个新的**不可变状态**通过 `volatile` 引用一次性发布；
7. 发布后再逆序 `destroy` 被替换 / 被停用的旧实例。

回滚保证：

- 任一实例 `create` 或 `initialize` 失败 → 已创建的临时实例全部 `destroy`，抛出
  `INITIALIZATION_FAILED`，**旧状态引用完全不动，对外持续可用**。
- 无效配置或任何依赖校验失败 → 在产生任何实例之前即被拒绝，旧状态不受影响。
- 配置未变化的已启用扩展**复用原实例，不重复初始化**；仅配置确实改变时才重建实例。

## 4. 并发一致性模型

- **状态不可变 + volatile 发布**：所有读取（查看 / 执行）固定读取一个不可变状态快照，
  只能看到完整的旧状态或完整的新状态，**不会读到撕裂状态**。
- **单写锁串行化变更**：所有启停 / 配置变更串行，同一有效配置的初始化不会并发执行两次。
- 执行方法把"就绪检查、配置读取、实际执行"绑定在同一个状态代际（generation，单调递增）上；
  返回结果携带其实际运行的代际与观测配置，可严格校验行为与状态同代。

## 5. 故障隔离与错误归一化

- 单个扩展 `initialize` / `execute` 抛错只影响它自己：初始化失败回滚整批；执行失败归一为
  `EXECUTION_FAILED`，注册表状态不变，其他扩展继续可用。
- 所有对外错误形如 `{ "errorCode", "message", "context" }`，错误码稳定、可区分，
  `context` 仅保留便于定位的非敏感信息（如 `extensionId`、`dependencyId`、`requiredRange`、
  `actualVersion`、环路径）。
- **不向客户端透出底层异常消息或堆栈**；原始异常仅在服务端日志记录。

### HTTP 状态映射

| 错误码 | HTTP |
| --- | --- |
| `UNKNOWN_EXTENSION` | 404 |
| `DUPLICATE_EXTENSION_ID` / `CYCLIC_DEPENDENCY` / `CHANGE_SET_REJECTED` | 409 |
| `UNKNOWN_EXTENSION_KIND` / `INVALID_VERSION` / `INVALID_VERSION_RANGE` / `VERSION_INCOMPATIBLE` / `DEPENDENCY_NOT_FOUND` / `DEPENDENCY_NOT_READY` / `EXTENSION_NOT_READY` / `INVALID_CONFIG` / `INITIALIZATION_FAILED` / `INVALID_REQUEST` | 422 |
| `EXECUTION_FAILED` | 500 |

## 6. HTTP 接口

| 方法与路径 | 作用 |
| --- | --- |
| `POST /api/extensions` | 注册扩展（201） |
| `GET  /api/extensions` | 列出全部扩展及其状态与生效配置 |
| `GET  /api/extensions/{id}` | 查看单个扩展 |
| `POST /api/extensions/changes` | 原子提交启停 / 配置变更集 |
| `POST /api/extensions/{id}/execute` | 执行一个已启用扩展 |

内置扩展类型（开箱即可本地验证）：

- `echo`：配置必须含 `prefix`；执行返回 `{ "echo": prefix + message }`。
- `relay`：必须恰好声明一个依赖；执行时经运行时上下文转发到该就绪依赖。
- `faulty`：配置 `failOnInit` / `failOnExecute`（`true|false`），用于验证初始化失败回滚、
  执行失败归一化与故障隔离。

### curl 本地手测

```bash
mvn -o spring-boot:run &

# 注册一个 echo（注册后默认 DISABLED）
curl -s -X POST localhost:8080/api/extensions -H 'Content-Type: application/json' \
  -d '{"id":"hello","version":"1.0.0","kind":"echo","config":{"prefix":"hi-"}}'

# 启用
curl -s -X POST localhost:8080/api/extensions/changes -H 'Content-Type: application/json' \
  -d '{"items":[{"extensionId":"hello","enabled":true}]}'

# 执行
curl -s -X POST localhost:8080/api/extensions/hello/execute -H 'Content-Type: application/json' \
  -d '{"input":{"message":"there"}}'      # => {"extensionId":"hello","status":"OK","result":{"echo":"hi-there"}}

# 热更新配置（原子生效）
curl -s -X POST localhost:8080/api/extensions/changes -H 'Content-Type: application/json' \
  -d '{"items":[{"extensionId":"hello","enabled":true,"config":{"prefix":"bye-"}}]}'

# 停用后执行 -> 422 EXTENSION_NOT_READY
curl -s -X POST localhost:8080/api/extensions/changes -H 'Content-Type: application/json' \
  -d '{"items":[{"extensionId":"hello","enabled":false}]}'
curl -s -X POST localhost:8080/api/extensions/hello/execute -H 'Content-Type: application/json' -d '{}'
```

## 7. 本地验证方法

仅需 JDK 21+ 与 Maven，依赖已包含在工程的 Spring Boot 起步依赖中（离线可用 `-o`）：

```bash
mvn -o test          # 运行全部测试
```

测试覆盖（共 33 个用例）：

- `VersionRangeTest`：精确 / 开闭区间 / 单侧无界 / 非法版本与范围的可区分拒绝。
- `DependencyResolverTest`：依赖缺失、版本不兼容、依赖未就绪、循环依赖，以及拓扑序依赖优先。
- `ExtensionRegistryTest`（13 个）：重复 id 不覆盖、未知 kind、非法配置、依赖缺失 / 不兼容 /
  未就绪、间接破坏被整体拒绝且旧状态可用、初始化失败整批回滚、非法配置变更被拒且服务仍可用、
  合法热更新原子重建、执行失败归一化且隔离兄弟扩展、停用 / 未知扩展执行被拒、变更集重复条目。
- `ConcurrentHotUpdateTest`（4 个）：
  - 8 更新者 + 8 读取者并发热更新下**零撕裂读取**且经历多个生效代际；
  - 合法 / 非法配置交替并发：非法变更全部被拒、合法变更生效、期间读取始终成功；
  - 并发"成对启用"与"禁用底座"：危险禁用始终被拒，永不可观测"上层启用而底座停用"；
  - 并发启用 / 无意义变更 / 配置更新：每个实例**恰好初始化一次**，被替换实例恰好销毁一次（不重复初始化、不泄漏）。
- `ExtensionControllerTest`（MockMvc 端到端）：注册→启用→执行→热更新→停用全链路、重复注册 409、
  未知 kind / 非法版本可区分、relay 依赖流程的拒绝与原子批次接受、初始化失败回滚且原始故障信息不外泄。

按要求，每个单测都通过 SLF4J **打印输入配置与判定依据**（形如
`[input ...] decision: rejected code=... context=...`），可用如下命令查看：

```bash
mvn -o test -Dtest=DependencyResolverTest    # 其它测试类同理
cat target/surefire-reports/*.txt            # 汇总报告
```

## 8. 主要代码结构

```
model/      Version, VersionRange, DependencySpec, ExtensionDefinition,
            ExtensionStatus, ExtensionSnapshot（不可变快照，强类型实例）
spi/        Extension, ExtensionFactory, ExtensionRuntimeContext
error/      ExtensionErrorCode（稳定错误码）, ExtensionException（码+安全消息+定位上下文）
core/       ExtensionKindCatalog, DependencyResolver（存在/兼容/就绪/环/拓扑序）,
            StateChangeRequest/StateItem, ExtensionRegistry,
            DefaultExtensionRegistry（不可变状态+volatile发布+单写锁+回滚+执行隔离）,
            RegistryConfiguration
builtin/    Echo / Relay / Faulty 三种内置扩展 + 启动注册器
web/        ExtensionController（REST 门面）, GlobalExceptionHandler（错误归一化）, dto/
```
