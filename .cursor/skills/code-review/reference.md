# Code Review — 详细门禁与协作流程

由 [SKILL.md](SKILL.md) 在命中对应风险时按需加载。N/A 必须有依据。

## 通用审查门禁

### 需求与业务正确性

- 每项需求是否有对应代码和验证；每个改动是否能追溯到需求。
- 正例、反例、异常路径、权限不足、空值、边界值和重复请求。
- 状态迁移、事务边界、并发、幂等、资源归属和错误传播。
- 是否新增静默 fallback、catch 后只记录日志、默认空集合/null 掩盖数据错误。
- 同一业务判断是否出现第二个决策点；需求变化是否要改多处。
- 修复是否处理根因，而不是仅隐藏症状。

### 最小修改

逐文件回答「删除这个改动后，验收是否仍能通过？」

拒绝：

- 无关重构、格式化、重命名或依赖升级。
- fix/feat/perf/refactor 混在同一 PR。
- 修改生成镜像（如 `.claude/skills`）而非 `.cursor` 真源。
- 仅为假想未来需求新增抽象、开关、接口或依赖。
- 注释掉的旧代码、调试输出、临时脚本或构建产物。
- 范围外存量问题单独报告，不在本 diff 顺手修复。

### 影响链完整性

- 接口 → 实现 → 所有调用方。
- Entity → 追加式 SQL → Repository/Mapper → DTO → 前端类型。
- Controller URL/响应 → 前端 API → store/composable/view。
- i18n key → en、zh-CN、zh-TW。
- 配置/环境变量 → application 配置、Docker、K8s、Kong、healthcheck、文档。
- `platform-*` / shared → 所有实际下游模块。
- 删除或重命名 → 搜索残余引用、配置、测试、文档和序列化字段。

### 编译与静态质量

- 后端至少编译/打包对应 Maven 模块；公共模块或接口变化要编译直接下游。
- 前端执行对应应用 build，包含 TypeScript 类型检查。
- 执行项目已存在且适用的 lint、Checkstyle、SpotBugs、SAST 等命令。
- 新增 warning 后用 `@SuppressWarnings`、`eslint-disable`、`ts-ignore` 掩盖 → Blocker。
- 降低 strict、lint、安全规则或测试标准来通过门禁 → Blocker。

### 测试质量与证据

- 新增/修改行为覆盖正常路径和至少一个异常/边界/权限路径。
- Bug 修复应先有能复现失败的测试。
- 测试验证外部可观察行为，不复制实现、不 mock 被测对象内部逻辑。
- 禁止 skip/only、无断言、固定 sleep、依赖执行顺序、共享脏数据或放宽断言。
- 测试可重复执行，并正确清理数据库、timer、线程、mock 和外部资源。
- 核心不变量适合时增加属性测试。
- 可见 UI 必须 build、部署并保留 Playwright 截图。
- Portal MI 热路径必须执行完整 `pnpm run regression:mi`；unit-only 不算完整通过。

### 数据库与迁移

- `deploy/init-scripts` 只新增、不改旧 SQL，且脚本幂等。
- 约束、索引、执行顺序、重复执行、已有数据和失败恢复。
- 禁止破坏性删列/改列、双轨 schema 来源或为 Relation Table 创建业务物理表。
- 高风险迁移有备份、验证、前滚/回退方案。

### 分布式契约与兼容

- 新旧服务混跑、部署顺序和回滚旧版本。
- 字段只增不破坏，未知枚举值可控。
- 消息重复、乱序、重试和幂等。
- 新版本写入的数据是否仍可被回退版本读取；不能隐含「所有服务同时升级」。

### 可靠性与可观测性

- timeout、有限 retry、退避、熔断/降级依据。
- 连接、流、线程、timer、listener 和事务正确释放/回滚。
- 写操作防重复提交，跨服务权威数据失败与空结果可区分。
- 关键日志包含业务上下文 ID，日志级别与结果一致。
- 禁止无限重试、吞异常或记录成功但实际失败。

### 前端 UX、可访问性与 i18n

- loading、error、empty、success、重复提交和危险操作确认。
- 未保存数据保护、权限态、键盘/焦点、label/aria。
- 1366×768 不产生不合理横向滚动。
- 三语言同步，无硬编码用户文案。
- 验证真实交互，不只断言 DOM 存在。

### 值语义边界

- 金额使用 decimal 语义和明确舍入，禁止浮点金额。
- 时间使用明确时区/ISO 8601，并覆盖 DST/跨日。
- null、空字符串、空数组语义不混淆。
- 排序稳定、分页边界正确、未知枚举有兼容策略。
- 不依赖数据库、JVM 或浏览器默认 locale/时区/排序。

### 依赖与供应链

- 新依赖确有必要，仓库内无现成功能可复用。
- 版本在根依赖管理对齐，lockfile 同步。
- 检查许可证、已知漏洞、维护状态、包体积和运行影响。
- Action/脚本/镜像使用不可变 commit SHA/digest；禁止执行来源不明的下载内容。

### Diff 与提交卫生

- 无 conflict marker、调试代码、死代码、生成物、临时文件、快照噪声和意外大文件。
- commit 原子化，message 描述意图且不含敏感/内部值。
- 文档、示例、API、环境变量和运维 runbook 与代码同步。

## Function Unit 强制完整性矩阵

### 通用性门禁

实现必须由 FU 元数据、schema、binding、稳定 code/name 或运行时上下文驱动。

禁止硬编码：

- FU id/code/name。
- form/table/field/binding/view id。
- process key、示例名称、固定 seed 行或单一 package 结构。
- 通过某个具体 FU 标识决定业务语义。

必须支持：

- 修改后新建的 FU。
- 升级前已存在的 FU。
- Export/Import、Clone、Snapshot/Rollback 后的 FU。
- 有新配置和没有新配置的旧 FU。

若能力确实只适用于某类 FU，使用可配置、可验证的能力条件，并提供需求依据。「通用」仅指需求范围内行为一致，不允许为假想未来场景过度抽象。

### 生命周期矩阵

| 阶段 | Review 必查 |
|---|---|
| DW Save/Load | 设计器配置、DTO、持久化、重新打开一致 |
| Export | FunctionUnitExporter ZIP、manifest/components、v2 snapshot 完整 |
| Import | FunctionUnitImporter 新导入/同名覆盖、顺序、稳定键与 ID remap、显式失败 |
| Clone | FunctionUnitCloner 深拷贝、引用重写、状态和访问规则 |
| Version | VersionComponentImpl / FunctionUnitSnapshotRestorer v2 + legacy、clear/flush/session 安全 |
| Admin JSON import | `/function-units/import` → FunctionUnitPackageParser + FunctionUnitImportComponent 包含同一 ZIP 能力 |
| Admin deploy import | `/function-units-import/import` → 同一 ImportComponent（禁止第二条解析逻辑） |
| Deploy/Activate | DW `DeploymentComponentImpl` 导出的 ZIP 与 Admin 解析字段对齐；catalog / `sys_*` / engine payload 正确 |
| Portal backend | FU 内容、Form、View、Access 和数据语义同步 |
| Portal frontend | renderer、task detail、application detail 和 Designer parity 同步 |

不得只更新 Admin 两条导入链中的一条。不得只改 DW export/import/clone 而不扫 Admin parser/`*SyncComponent`/Engine（或反过来）。Admin catalog ≠ DW `dw_*` 设计器：runtime sync 与设计器还原是两条契约，见 skill `function-unit-portability` 消费者矩阵。

### Round-trip 证据

至少按风险覆盖：

- Export → Import。
- Clone。
- Snapshot → Rollback。
- DW Deploy → Admin Import → Activate → Portal runtime。
- 同名 re-import、缺失字段、无法 remap、旧 snapshot/package 等负例。

## 安全、隐私与增量敏感信息

### 安全门禁

- 服务端认证、资源归属、BU/Role、横向/纵向越权。
- SYS_ADMIN、普通用户、发起人、办理人、无权限用户的权限矩阵。
- 输入校验、SQL 注入、SSRF、XXE、LDAP、XSS、路径穿越。
- 缓存 key 包含身份、BU、viewContext 等隔离上下文。
- 日志和响应不暴露 PII、token、堆栈或内部实现。

### 增量 secret 检查对象

检查代码、配置、SQL、脚本、fixture、文档、截图/二进制、日志样例、文件名和新增 commit message 中的：

- password、API key、token、Cookie、JWT。
- private key、证书私钥、keystore/truststore 密码。
- 数据库/LDAP/外部系统连接凭证。
- 加密密钥、client secret。
- 真实姓名、邮箱、电话、证件号等 PII。

不扫描、不报告、不要求整改「用户显式基线之前且本 diff 未改动」的历史旧值。  
`.gitignore` 不能保护已跟踪或强制添加的文件。

## 性能专项

### 后端

- N+1、循环内 DB/HTTP、逐行写入。
- 拉全量后 count/aggregate、无分页或无界查询。
- 可并行 I/O 串行化、阻塞 I/O 使用公共 ForkJoinPool。
- 缓存 TTL/容量/穿透/失效/身份隔离和 key 完整性。
- 大 payload、重复序列化、慢 SQL、缺索引。
- timeout、retry storm、线程池/队列无界。

### 前端

- 重复 API 和缺少 in-flight Promise 去重。
- 可并行请求串行 await。
- 远程搜索无至少 300ms debounce。
- deep watch、大对象深克隆、模板循环内 filter/find/format。
- 大列表无分页/虚拟滚动，重型库未动态导入。
- timer/WebSocket/listener 未清理。

### 性能变更证据

- 性能改动不得与 fix/feat 语义修改混 PR。
- 提供同环境、同数据、同口径的优化前后结果；没有测量不得声称提升。
- 不得以缓存、并行、少 clone 改变 FU、权限、MI merge/filter 或错误语义。
- MI 热路径执行完整 `pnpm run regression:mi`。

## 发布、回退与豁免

高风险改动检查 feature flag/default、灰度/canary、混合版本兼容、紧急关闭和可执行回退步骤。低风险内部改动可有依据地标 N/A。

所有 N/A、风险接受、规则例外或未执行验证必须记录：

- 原因与外部依据。
- 影响范围。
- 责任人/Issue。
- 后续动作和临时逻辑删除条件。

「时间不足」或「现有代码如此」不是豁免依据。

## 推荐协作流程

1. 开发前明确验收、范围、变更类型；FU 改动先列生命周期矩阵。
2. 一个 concern 一组最小改动，不混 fix/feat/perf/refactor。
3. 只 stage 目标 hunks，运行 staged review，解决 Blocker/Major。
4. 编译所有受影响模块，运行相关测试和本地增量 secret scan。
5. 使用不含敏感信息的 Conventional Commit。
6. PR 前更新基线，对 `base...HEAD` 运行完整 review。
7. 作者提供实际编译、测试、Docker logs 和截图证据。
8. Reviewer 独立复核需求、完整 diff、高风险路径和至少一个关键命令。
9. `platform-common`、安全鉴权、schema、FU portability/rollback 或跨服务契约需要领域 owner/第二 reviewer。
10. 修复后先审增量，再审完整分支；scope 变化先更新验收。
11. 满足放行规则后合并。
