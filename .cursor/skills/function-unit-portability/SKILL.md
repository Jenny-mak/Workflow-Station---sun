---
name: function-unit-portability
description: >-
  Function Unit export/import/clone/version snapshot must carry ALL designer configuration
  so re-import, rollback, and clone do not fail or silently drop settings. MUST also update
  every consumer: Developer Workstation, Admin Center catalog import (both HTTP paths),
  Deploy ZIP upload, and runtime sync tables. Use when editing FunctionUnitExporter,
  FunctionUnitImporter, FunctionUnitCloner, VersionComponentImpl, *Portability,
  ExportManifest, FunctionUnitImportComponent, FunctionUnitPackageParser, EmailMonitorSync,
  EmailConnectionSync, or when the user mentions FU 导入导出 / clone / 打包缺失 / Admin 导入.
---

# Function Unit 可移植性 — 导入 / 导出 / Clone / 版本快照

**原则：** ZIP 导出、同名覆盖导入、Clone、版本快照/回滚 MUST 在目标环境还原 **与源 FU 等价的全部设计配置**；禁止「能导入但丢字段/丢规则/静默跳过」。

**改 ZIP 结构或任一导入路径时，MUST 扫完整消费者矩阵（DW + Admin + Deploy），禁止只改 Developer Workstation。**

与 View 访问管控规则配套：`.cursor/skills/view-access-control/SKILL.md`。

---

## 消费者矩阵（改 Export/Import/Clone 必扫）

同一份 DW ZIP 会被多条链路消费。**只改 DW 导入、不管 Admin**（或反过来）视为漏改。

| 消费者 | 入口 | 落库 / 效果 | 不是什么 |
|--------|------|-------------|---------|
| **DW Export** | `FunctionUnitExporter` | ZIP + version snapshot | — |
| **DW Import** | `FunctionUnitImporter` + `ExportImportPackageParser` | `dw_*` 设计器（表/表单/BPMN/email 模板与 monitor **含未绑定模板**） | 不写 `sys_*` |
| **DW Clone** | `FunctionUnitCloner` | 同库新 FU，id/uid remap | — |
| **DW Version** | `VersionComponentImpl` + `FunctionUnitSnapshotRestorer` | 快照 ↔ `dw_*` | — |
| **DW Deploy** | `DeploymentComponentImpl`：先 `exportFunctionUnit` 再 POST Admin | 把 **同一份 ZIP** 交给 Admin 导入 | DW 自己不写 `sys_*` |
| **Admin UI 导入** | `POST /function-units/import` → `FunctionUnitController` | catalog + 运行时 sync | **不会**回写 `dw_*` |
| **Admin Deploy 导入** | `POST /function-units-import/import` → `FunctionUnitImportController` | **同一** `FunctionUnitImportComponent` | 不得做成第二条解析逻辑 |
| **Admin 解析** | `FunctionUnitPackageParser` | ZIP 目录 → contents / connections / emailMonitors | 漏解析某个目录 = 静默丢文件 |
| **Admin 运行时 sync** | `EmailConnectionSyncComponent`、`EmailMonitorSyncComponent` | `sys_email_connections` / `sys_email_monitor_rules` 供引擎 | monitor **仅同步有 `startEventId` 的绑定**；模板留给 DW |
| **Engine** | `sys_email_monitor_rules` 等 | 运行时 poll / 发信凭证 | 不读 DW 设计器表 |

两条 Admin HTTP 路径 MUST 走同一套 parser + import + sync。禁止只改其中一条。

**Admin catalog ≠ DW 设计器：** Admin 导入保证包不丢进 catalog、运行时表可供引擎使用；**不会**把 Email Monitor 模板写回 `dw_email_monitor_rules`。跨环境要设计器完整，须再做 **DW Import**。

---

## 三条 DW 路径（必须行为一致）

| 路径 | 入口 | View Design 实现 |
|------|------|------------------|
| **Export → Import** | `FunctionUnitExporter` / `FunctionUnitImporter` | `views/main_table_views.json` ← `MainTableViewPortability` |
| **Clone** | `FunctionUnitCloner` | `MainTableViewService.cloneViewsForFunctionUnit` |
| **Version snapshot / rollback** | `VersionComponentImpl` | snapshot `mainTableViews` ← `MainTableViewService.snapshotViewsForFunctionUnit` + `MainTableViewPortability.importAll` |

**Rollback 完整还原契约（含缺口矩阵、测试）：** `.cursor/skills/function-unit-version-rollback/SKILL.md`

新增 FU 级配置时 **DW 三条路径 + Admin parser/sync + Deploy ZIP** 都要补，并在下方清单打勾。

---

## ZIP 包结构（ExportManifest.components）

| 文件 / 目录 | 内容 |
|-------------|------|
| `manifest.json` | 元数据 + `components`（含 `mainTableViews` 路径） |
| `process/process.bpmn` | 流程 BPMN |
| `tables/table_*.json` | 表结构 + FK 元数据 |
| `relations/table_relations.json` | 表关系 |
| `forms/form_*.json` | 表单 + binding + subTableViewConfig |
| `relation-tables/relation_tables.json` | 引用的 rt_ 结构 |
| **`views/main_table_views.json`** | **View Design：字段、排序/筛选、restrictToInvolvedUsers、accessRules** |
| `actions/action_*.json` | 动作 |
| `decisions/decision_*.dmn` | 决策 |
| `connections/`、`email-monitors/`、**`email-templates/`** | 邮件连接、监听规则、**Send Task HTML 模板** |

---

## Email Templates 可移植（MUST）

| 路径 | 行为 |
|------|------|
| **Export / Snapshot** | 始终写入 `emailTemplates`（无模板时写 `[]`）；ZIP 为 `email-templates/template_*.json` |
| **Import (DW)** | 解析并 `importEmailTemplate`；BPMN Send Task `emailTemplateId` 经 `BpmnIdRewriter` remap |
| **Clone** | 深拷贝模板并建立 id mapping；monitor 的 `connectionUid` / `targetFormId` / 数值 `targetBindingId` **映射失败必须抛业务异常**（禁止 `getOrDefault` 回退源 ID） |
| **Version rollback** | `clearChildCollectionsAndFlush` 删除模板后按快照重建；**旧快照缺 `emailTemplates` key** → 清空且不恢复（接受：回到无模板功能时代） |
| **Admin import** | 解析 `email-templates/`，以 `ContentType.EMAIL_TEMPLATE` 写入 **catalog content**（禁止静默丢弃；坏 JSON → 整次导入失败） |

**Admin catalog ≠ Send Email 运行时：** workflow-engine 仍从 **DW** `dw_email_templates`（Internal API）取模板。Admin 导入只保证包内模板进入 catalog、不丢文件；**不会**自动写入 DW 运行时表。跨环境要能发信，须另有 DW 导入/同步（本 skill 不把「Admin→DW 回写」算作当前 MUST）。

---

## Email Monitors 可移植（MUST）

设计器「Email Monitors」列表只展示 **模板**（`sourceRuleId IS NULL` 且 `startEventId IS NULL`）。Start Event 上的规则是运行时绑定，通过 `sourceRuleId` 指向模板。

| 路径 | 行为 |
|------|------|
| **Export / Snapshot** | `findByFunctionUnitIdOrderByNameAsc` 导出 **全部** monitor（模板 + 绑定）；始终写入 `emailMonitors`（无则 `[]`）；JSON 含 `ruleId` / `sourceRuleId` / `startEventId` |
| **Import (DW)** | `EmailMonitorRulePortability.importAll` 两遍：先模板、再绑定；remap `sourceRuleId`、`connectionUid`、`targetFormId`、数值 `targetBindingId`；失败抛业务异常。旧 ZIP 仅有绑定、无模板映射 → `FALLBACK(migration)` 允许 `sourceRuleId` 为空，不得静默丢整条绑定 |
| **Clone** | 同一套两遍 + remap；禁止把 `sourceRuleId` 置空 |
| **Version rollback** | 先删 `dw_email_monitor_rules` 再按快照 `importAll` |
| **Admin import** | `FunctionUnitPackageParser` 解析 `email-monitors/`；`EmailMonitorSyncComponent` **只同步有 `startEventId` 的绑定** 到 `sys_email_monitor_rules`（引擎 poll）。**无 startEvent 的模板不得写入 sys 表**（否则引擎会当运行时规则轮询）。catalog `ContentType` **没有** `EMAIL_MONITOR` |

**Admin catalog ≠ DW Email Monitors 页：** Admin 导入不会把模板写回 `dw_email_monitor_rules`。跨环境要设计器列表完整，必须 **DW Import**。旧 ZIP 从未包含模板 → 必须用修好后的 DW 重新 Export。

---

## View Design 可移植字段（MUST）

每个 view 条目：

| 字段 | 说明 |
|------|------|
| `mainTableName` | 按表名 remap（非 id） |
| `viewName` / `isDefault` / `status` | 基本元数据 |
| `sortConfig` / `filterConfig` | 含 toolbar |
| **`restrictToInvolvedUsers`** | 仅参与用户可见数据 |
| **`accessRules`** | BU + Role；见下节 |
| `fields` | 列定义 |

### accessRules 序列化

- **Export：** 从 `dw_main_table_view_access` **JDBC 读取**（禁止依赖 JPA lazy `view.getAccessRules()` — `findByFunctionUnitIdWithFields` 不 fetch access）。
- 每条规则：`targetType` + `targetId` + **`targetCode`**（BU/Role 的 `sys_*`.code，跨环境 remap）。
- **Import：** 优先 `targetCode` → 目标环境 id；fallback `targetId`（同库版本回滚）。
- **校验：** `MainTableViewAccessRulesValidator` — 空或 BU+Role 成对；禁止半配；code 无法解析 → `BIZ_VIEW_ACCESS_IMPORT_UNRESOLVED`。
- **Publish / Save / Clone：** 同样成对校验。

---

## Clone 专用

- `cloneViewsForFunctionUnit`：复制 fields、`restrictToInvolvedUsers`、access rules（`cloneAccessRules` via JDBC DTO）。
- 保存前 `MainTableViewAccessRulesValidator.validatePairedOrEmptyEntities`。
- 新 FU 上 view 状态为 **DRAFT**（与表 clone 一致）。

---

## 新增 FU 配置时的检查清单

改 Export/Import/Clone/Version **或 ZIP 目录** 时逐项确认（DW **和** Admin 都要勾）：

**Developer Workstation**

- [ ] **Exporter** 是否写入 ZIP / snapshot？（空集合写 `[]`，禁止缺 key 导致旧包无法区分「没有」与「漏导出」）
- [ ] **Importer** 是否在约定顺序还原？是否 remap 名称/id（表名、rt 名、BU/Role code、`sourceRuleId`）？
- [ ] **Clone** 是否深拷贝并重写 BPMN/表单/email 引用（禁止丢 `sourceRuleId`）？
- [ ] **Version rollback** 是否走同一 `*Portability.importAll`？`clearChildCollectionsAndFlush` 是否先删对应 `dw_*`？
- [ ] `ExportManifest.components` 是否登记新组件路径？

**Admin Center + Deploy + Engine**

- [ ] **`FunctionUnitPackageParser`** 是否解析对应 ZIP 目录（漏目录 = 静默丢文件）？
- [ ] 若进 catalog：是否有 `ContentType`？没有则文档写明「仅 runtime sync、不进 catalog」（如 Email Monitor）
- [ ] **`FunctionUnitImportComponent`** 是否持久化/调用 sync？两条 HTTP（`/function-units/import` 与 `/function-units-import/import`）是否仍走同一套？
- [ ] 对应 **`*SyncComponent`** 是否写入 `sys_*`？设计器专用数据是否被正确 **跳过**（避免引擎误消费模板）？
- [ ] **DW Deploy** 导出的 ZIP 与 Admin 解析字段是否对齐？
- [ ] **Engine / Portal** 是否需要读新字段？需要则补运行时，不需要则写清「只 DW」

**共性**

- [ ] 失败时是 **显式业务异常** 而非 `log.warn` 静默跳过？
- [ ] 测试是否覆盖 **实际落库的每一侧**（`developer-workstation` **和** `admin-center`，按消费者矩阵）？
- [ ] View access：是否 JDBC 导出 + 成对校验 + `restrictToInvolvedUsers`？

---

## 代码 touchpoints

| 类 | 职责 |
|----|------|
| `FunctionUnitExporter` | 组装 ZIP + manifest |
| `FunctionUnitImporter` | 解析包 + 编排导入 |
| `FunctionUnitCloner` | 同库 clone + BPMN rewrite |
| `FunctionUnitSnapshotRestorer` | 快照还原 |
| `VersionComponentImpl` | snapshot/restore + clear/flush |
| `DeploymentComponentImpl` | DW Deploy：export ZIP → Admin import |
| `EmailMonitorRulePortability` | monitor 两遍 import/clone + remap |
| `MainTableViewPortability` | View Design export/import |
| `RelationTableStructurePortability` | rt_ 结构 |
| `MainTableViewAccessRulesValidator` | BU/Role 成对校验（Save/publish/import/clone） |
| `MainTableViewServiceImpl` | Save、publish、clone、snapshot DTO |
| `FunctionUnitPackageParser` | Admin 解析 ZIP 目录 |
| `FunctionUnitImportComponent` | Admin catalog 导入编排 |
| `EmailConnectionSyncComponent` / `EmailMonitorSyncComponent` | `sys_email_*` 供引擎 |

---

## 测试

| 测试 | 覆盖 |
|------|------|
| `MainTableViewPortabilityTest` | JDBC 导出 access；import 未解析 code；半配拒绝 |
| `MainTableViewAccessRulesValidatorTest` | 成对/空规则 |
| `MainTableViewServiceImplTest` | updateView 成对 Save |
| `EmailPortabilityTest` | 模板 import；BPMN templateId remap；monitor 模板+绑定 round-trip；未映射 form/connection 抛错 |
| `VersionRollbackParityTest` | snapshot 含模板 + 绑定 |
| `EmailMonitorSyncComponentImplTest` | Admin：无 `startEventId` 的模板不同步 sys 表 |
| `ExportImportPropertyTest` / 手测 | 整包 round-trip |

手测：Export FU → 删/改 View access → Import 同名覆盖 → Portal 验证 View 菜单与 access 与导出前一致。Email Monitor：Export → DW Import 后设计器列表仍有模板；Deploy/Admin 导入后引擎只 poll 绑定。

---

## 禁止

- 仅 UI Save 校验、import/clone 不校验 access 成对。
- Export 用 JPA 读 access rules（未 fetch → **空数组**）。
- Import 对无法 remap 的 access code **warn 跳过**导致半配或 admin-only 静默降级。
- 新配置只加 DW Exporter，不加 Importer / Clone / Version / **Admin parser / sync**。
- 只改 Admin 两条导入 HTTP 中的一条。
- 把 Admin catalog 导入当成 DW 设计器还原（或反过来假定 Admin 会写 `dw_*`）。
- 把设计器模板同步进 `sys_email_monitor_rules`（引擎会当运行时规则）。
- 同名 re-import / rollback 时 **`clearChildCollectionsAndFlush` 未删** `dw_email_connections` / `dw_email_monitor_rules`（`connection_uid` / `rule_uid` 全局唯一 → 重复 INSERT 失败）。

---

## Re-import 清理（email）

`VersionComponentImpl.clearChildCollectionsAndFlush` MUST 在重建内容前：

1. `emailMonitorRuleRepository.deleteByFunctionUnitId`
2. `emailConnectionRepository.deleteByFunctionUnitId`
3. `emailTemplateRepository.deleteByFunctionUnitId`
4. `tableRelationRepository.deleteByFunctionUnitId`（与 tables 一并重建）

不能依赖 `functionUnit.getEmailConnections().clear()` alone — 集合常为 lazy 未加载，旧行会留在库中。

---

## 参考

- View 业务规则：`.cursor/skills/view-access-control/SKILL.md`
- 手测：`docs/view-access-control-test-guide.md`
- 编辑相关 Java 时自动加载：`.cursor/rules/function-unit-portability-consumers.mdc`
