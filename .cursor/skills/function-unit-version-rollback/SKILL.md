---
name: function-unit-version-rollback
description: >-
  Developer Workstation Version Management rollback must restore the target version's
  full Function Unit design (tables, fields, FK, relations, forms/bindings, View Design
  with BU+Role access, BPMN, actions, decisions, email, form control attrs, etc.).
  Use when editing VersionComponentImpl, dw_versions snapshots, Version Management UI
  Rollback, or when the user mentions 版本回滚 / rollback 丢数据 / subTablePlaceholderStale /
  SubTableViewConfig session / View Design 500 / idx_mtv_access_view_target.
---

# Function Unit 版本回滚 — 完整还原契约

**产品目标：** 用户在 **Version Management** 对某一历史版本点 **Rollback** 后，当前 FU 的设计内容 MUST 与**该目标版本快照时刻**一致（表关系、Form 画布与 binding、View 与 BU/Role、Table/字段、控件属性、BPMN、Action、Decision、邮件等 **Function Unit 内全部设计器产物**）。

与可移植性总纲配套：`.cursor/skills/function-unit-portability/SKILL.md`  
View 访问规则语义：`.cursor/skills/view-access-control/SKILL.md`

---

## 与 Publish / Deploy / Import 的边界

| 操作 | 存储 | 影响 Portal 运行时 |
|------|------|-------------------|
| **Publish**（DW） | 写 `dw_versions.snapshot_data` + 设计侧 PUBLISHED | 不直接改 Portal 目录 |
| **Rollback**（DW Version Management） | 从 `dw_versions` 还原 **DW 设计库** | **不**自动改 `sys_function_units` / Flowable；需再 **Deploy** 才进 Portal |
| **Deploy**（DW 或 Admin） | `sys_function_units` + 引擎 | Portal New Requests / 已部署流程 |
| **Import 同名覆盖** | 先 snapshot 再 clear（见 portability Skill） | 同 Rollback 后需 Deploy |

Rollback 只保证 **Developer Workstation 设计态** 与目标版本一致；**Admin Center FU Access（Portal 门禁）** 在 `sys_function_unit_access`，不在 `dw_versions` 快照内。

若改动的是 **ZIP / snapshot 字段**（会随 Export 或 Deploy 离开 DW），MUST 按 skill `function-unit-portability` **消费者矩阵**同时核对 Admin parser、两条导入 HTTP、`*SyncComponent` 与 Engine。Rollback 本身不写 `sys_*`，但同一套导出物会被 Admin 消费。

**Rollback 前自动备份：** 每次 Rollback 会先写一条 `Auto backup before rollback` 版本；若目标版本不含某属性，可 Rollback 到该备份版本恢复。

---

## 回滚管线（当前实现）

**入口：** `POST /api/v1/function-units/{id}/versions/{versionId}/rollback`  
**实现：** `VersionComponentImpl.rollback` → `FunctionUnitSnapshotRestorer.restore`

```
1. 校验目标 version 属于该 FU，且 ≠ 当前 currentVersion（BIZ_ROLLBACK_TO_CURRENT）
2. sequenceSynchronizer.synchronizeAll()
3. 把「回滚前现状」再 snapshot 一条 dw_versions（Auto backup before rollback）
4. clearChildCollectionsAndFlush(fu)
   - native DELETE：subTableViewConfig（fields 先于 configs）/ linkForm / FK / email / tableRelation …
   - **必须** refreshFunctionUnitAfterNativeClear：
     entityManager.flush() + clear() + 从 DB 重载 FunctionUnit
     （见 §回归缺陷 #1473）
5. sequenceSynchronizer.synchronizeAllInTransaction()
6. FunctionUnitSnapshotRestorer.restore(fu, target.snapshot)
   - v2（export 格式）：FunctionUnitImportWriter + BPMN rewrite/fixStaleIds（同 Import）
   - legacy（pre-v2）：内存还原 tableDefinitions/formDefinitions/… + fixStaleIds
   - **收尾：** FormTableBindingRestorer.repairFunctionUnitForms（见 §回归缺陷 #1472）
7. 写 rollback 版本记录；currentVersion 递增；status ← snapshot.status（legacy 缺省 DRAFT）
8. 若 status=PUBLISHED → mainTableViewService.publishViewsForFunctionUnit
```

**快照写入：** `FunctionUnitExporter.buildVersionSnapshotPayload`（schema v2，与 Export ZIP 同序列化器）在 **Publish**、**Version createSnapshot**、**Import 前 snapshot**、**Rollback 前 backup** 时调用。旧 `dw_versions` 行仍为 legacy 键，回滚时自动走 legacy 分支。

---

## 快照 JSON 顶层键（v2 / `snapshotSchemaVersion: 2`）

| 键 | 内容 | Rollback 还原路径 |
|----|------|-------------------|
| `name` / `code` / `description` / `status` | FU 元数据 | description → displayName |
| `process` | BPMN 明文 XML | `BpmnIdRewriter` + `fixStaleIds` + 创建/更新 ProcessDefinition |
| `tables[]` | 表 + fields + foreignKeys + requestIdConfig | `importTable` + `importForeignKeys` + `importFieldRefMetadata` |
| `tableRelations[]` | Data Model Relation | `importTableRelations` |
| `relationTables[]` | rt_ 结构（RELATED binding） | `RelationTableStructurePortability.importAll` |
| `forms[]` | configJson + fieldPermissions + tableBindings + stageBindings + inline subTableViewConfig | `importFormShell` + `finalizeFormImport` |
| `linkFormComponents[]` | Link Form 列组件 | `importLinkFormComponent` |
| `actions[]` | 动作 + configJson | `importAction` |
| `decisions[]` | DMN XML 字符串列表 | `importDecision` |
| `connections[]` / `emailMonitors[]` | 邮件连接 / 监听规则 | `importEmailConnection` + `importEmailMonitorRule`（remap form/binding id） |
| `mainTableViews[]` | View + fields + accessRules（targetCode） | `MainTableViewPortability.importAll` + `seedDefaultViews` |

Legacy 键（`tableDefinitions` / `formDefinitions` / `processXml` / …）仍支持，见 `FunctionUnitSnapshotRestorer.restoreLegacy`。

---

## 完整还原矩阵（v2 快照 vs Export）

| FU 设计产物 | Export ZIP | Rollback v2 | 说明 |
|-------------|------------|-------------|------|
| 主/子 **Table** + **字段** | ✅ | ✅ | 新 id；BPMN `BpmnIdRewriter` + `fixStaleIds` |
| **Foreign Key** | ✅ | ✅ | `tables[].foreignKeys` |
| **Table Relations** | ✅ | ✅ | `tableRelations[]` |
| **Process BPMN** | ✅ | ✅ | 无 ProcessDefinition 时 **创建** |
| **Form configJson**（整包 rule/options） | ✅ | ✅ | 见 §表单控件属性 |
| **Form fieldPermissions**（TASK） | ✅ | ✅ | `forms[].fieldPermissions` |
| **Form bindings** + **SubTable View Config** | ✅ | ✅ / ⚠️ | 快照含 `tableBindings` 则直接 restore；空 bindings 见 #1472 |
| **Link Form components** | ✅ | ✅ | `linkFormComponents[]` |
| **View Design** + **accessRules** | ✅ | ✅ | `MainTableViewPortability`；保存 access 见 #1471 |
| **Actions / Decisions** | ✅ | ✅ | |
| **Email connections / monitors** | ✅ | ✅ | `connections[]` / `emailMonitors[]` |
| **Relation Table (rt_)** | ✅ | ✅ | `relationTables[]`（有 RELATED binding 时） |
| **dw_function_unit_access**（DW 门禁） | — | ❌ | 与 Admin `sys_function_unit_access` 分离 |
| **Dev virtual groups** | — | ❌ | `dw_function_unit_dev_groups` |

**旧版 snapshot（无 schema v2）：** 仍仅还原 legacy 子集（无 FK/bindings/email/view access 等）；须 **重新 Publish** 生成 v2 快照后 Rollback 才能全量还原。

---

## 表单控件属性（configJson.rule[]）

Rollback **不会 strip** 控件属性；**能还原什么取决于目标快照里当时存了什么**（非设计器当前未 Save 状态）。

| 属性 | 存储位置 | v2 Rollback | Legacy |
|------|----------|-------------|--------|
| **校验** `validate[]`（required/trigger/message） | `configJson.rule[]` | ✅ 整包写入 | ✅ 有 configJson 则还原 |
| **只读** `readonly` / `props.readonly` | `configJson.rule[]` | ✅ | ✅ |
| **组件事件** `on`/`hook` 或 `_on`/`_hook`（`$FNX:`） | `configJson.rule[]` | ✅ | ✅ |
| **表单 options 事件** | `configJson.options` | ✅ | ✅ |
| **TASK 字段权限** EDITABLE/READONLY/HIDDEN | `fieldPermissions` + 快照 | ✅ | ❌ |
| **Stage 只读** | `stageBindings` | ✅ v2 | ❌ |

**机制：** `FunctionUnitExporter.serializeForm` 原样写入 `configJson`；`importFormShell` 整包读入；`finalizeFormImport` 仅 remap bindingId，不删 validate/事件/readonly。

**常见误解：**

- 回滚到 **较早版本** 后 readonly/事件消失 → 目标快照 **本来就没有**（例如 MCY 1.0.141 无 `_on`，1.0.142 才有）；不是 rollback 丢数据。
- 设计器 **「Is it required」开关 OFF** 但 Validation 面板有规则 → `validate[]` 与 `$required` 两套机制；快照存的是 `validate[]`（设计器 UI 展示不同步，非 rollback 问题）。
- Save 路径：`prepareFormCreateRulesForPersist` 将 `_on`→`on` 并序列化为 `$FNX:`；加载时 `inflateComponentEventsForDesigner` 还原 Event 面板。

---

## 回归缺陷与修复（2026-07 MCY 手测）

改 Rollback / Restore / 相关 Save 路径时 **MUST NOT 回退** 下列修复。

### #1472 — Rollback 后 Form binding 丢失（subTablePlaceholderStale）

| 项 | 内容 |
|----|------|
| **症状** | Form Design 子表占位符 `designer.subTablePlaceholderStale`；Sub Table Binding 仍显示旧 id（如 271）；`dw_form_table_bindings` = 0 |
| **根因** | 回滚到 legacy 或 **bindings 已空的 v2 快照** 只恢复 `configJson`，未恢复 binding 行；后续 Publish 写入空 `tableBindings`，rollback 循环放大 |
| **修复** | `FormTableBindingRestorer.repairFunctionUnitForms` — 当 form **0 bindings** 且 configJson 有 stale `_bindingId` 时，从 `subListViews` / `relationViews` / lookup `bindingId` 推断表并重建 PRIMARY/SUB/RELATED + remap configJson + 子表 list view |
| **触发** | `FunctionUnitSnapshotRestorer` restore 收尾；`FormDesignComponentImpl.getByFunctionUnitId/getById`（REQUIRES_NEW 懒修复） |
| **验证** | `FormTableBindingRestorerTest`；GET `/function-units/{id}/forms` 后 bindings 行数 > 0 |
| **UX** | Form Design 主导航 Sub/Relation  tab 显示真实表名（`formatBindingGroupNavLabel`），非 `2 tables` — `FormDesigner.vue` |

**注意：** `dw_table_relations`（Data Model Relation）与 `dw_form_table_bindings` 是不同概念；MCY seed 可能只有 field FK 无 relation 行，勿与 binding 修复混谈。

### #1473 — Rollback 失败 SubTableViewConfig Hibernate 冲突

| 项 | 内容 |
|----|------|
| **症状** | `Rollback failed: A different object with the same identifier value was already associated with the session : [SubTableViewConfig#50062]` |
| **根因链** | ① `createSnapshot`（backup）经 `findByBindingId` 把 `SubTableViewConfig` 载入 persistence context → ② `clearChildCollections` native DELETE 未驱逐 session 实体 → ③ `synchronizeAllInTransaction` 序列对齐后 restore INSERT **复用同 id** → NonUniqueObjectException |
| **修复** | `VersionComponentImpl.refreshFunctionUnitAfterNativeClear`：`flush()` + `entityManager.clear()` + 重载 FU；`SubTableViewConfigRepository` DELETE 加 `flushAutomatically = true` |
| **适用** | `rollback` 与 `snapshotAndClearForReimport` 均在 clear 后调用 |
| **验证** | MCY FU48 Rollback 至含子表 list view 的版本成功 |

**通用规则（native DELETE + Hibernate）：** 凡 rollback/import 在 **同一事务内** 先 `createSnapshot`（加载实体）再 native DELETE 再 INSERT，**MUST** clear persistence context 或 evict 被删实体类型，否则任意 `@GeneratedValue` 子表都可能复现。

### #1471 — Rollback 后 View Design 保存 500（access 重复）

| 项 | 内容 |
|----|------|
| **症状** | View Design 保存 / 自动保存 → HTTP 500；`duplicate key … idx_mtv_access_view_target`；`(view_config_id, BUSINESS_UNIT, bu-e2e-finance)` |
| **根因链** | Rollback 从快照还原 View + BU/Role access → 用户保存时 `replaceAccessRules` 先 `deleteByViewConfigId` **未 flush** → `config.getAccessRules().clear()` **lazy 加载**尚未删除的旧行 → cascade INSERT 与 DB 已有行冲突 |
| **修复** | `MainTableViewAccessRepository`：显式 `@Query DELETE` + `flushAutomatically = true`；`MainTableViewServiceImpl.replaceAccessRules`：`deleteByViewConfigId` 后 **`flush()`** 再 `clear()` 重建 |
| **验证** | `MainTableViewServiceImplTest.updateView_*`；PUT `/function-units/{id}/main-table-views/{viewId}` 含 accessRules |

**与 Rollback 关系：** Rollback 正确还原 access；问题出在 **还原后的第一次 Save**。改 access 保存逻辑时须保留 flush。

---

## 实现规则（扩展 Snapshot / Restore 时）

### 1. 与 Export 共用 Portability

新增 FU 级配置时：

1. 先在 `FunctionUnitExporter` / `FunctionUnitImporter` 打通（见 portability Skill）。
2. **Rollback MUST 复用同一 `*Portability` 或同一 DTO**，禁止在 `VersionComponentImpl` 写第二套简化逻辑。
3. `createSnapshot` 增加键；restore 在 **tables 还原之后** 按 Import 相同顺序调用。

### 2. ID 重映射

Rollback 总是 **删旧行 + INSERT 新 id**。凡快照里存了 `tableId` / `formId` / `bindingId` 的：

- 快照 MUST 同时存 **稳定键**（`tableName`、`formName`、`mainTableName`），与 Export 一致。
- Restore MUST 用 `name → newId` 映射，禁止写死旧 id。

### 3. View 与 BU/Role

- **Restore 路径：** `MainTableViewPortability.importAll`（含 access targetCode 校验）。
- **Save 路径（Rollback 后）：** `replaceAccessRules` 必须 delete + **flush** + clear + 重建（#1471）。
- 半配 access → import 抛 `BIZ_VIEW_ACCESS_IMPORT_UNRESOLVED` / validator 错误，**禁止** silent skip。

### 4. clearChildCollectionsAndFlush 与 Rollback 对齐

Rollback 与 re-import 共用 clear。凡 Export 会重建的集合，clear MUST 先删：

- `dw_sub_table_view_fields` → `dw_sub_table_view_configs`（先于 forms）
- `foreignKey`（先于 tables）
- `emailMonitorRule` / `emailConnection` / `tableRelation`

clear 后 **MUST** `refreshFunctionUnitAfterNativeClear`（#1473）。

### 5. 回滚后状态

- Rollback 从目标快照 **`status`** 还原 FU（v2：`PUBLISHED`/`DRAFT`；legacy 默认 DRAFT）。
- 若 **PUBLISHED** → `publishViewsForFunctionUnit`。
- 选 Rollback 目标时：要控件事件/readonly → 选 **含这些字段的快照版本**，不是更早的无事件版本。

---

## 禁止

- Rollback 只还原 `configJson` 却忽略 **binding / subTableViewConfig** → 表单空白、子表 Portal Display 丢失。
- 快照 `tableBindings` 为空时不跑 **FormTableBindingRestorer** → stale 占位符永久化。
- clear 后用 native DELETE 但 **不清 Hibernate session** → SubTableViewConfig / 同类实体 NonUniqueObjectException。
- View access 保存 **delete 后不 flush** → idx_mtv_access_view_target 500。
- 快照不含 **mainTableViews** 的旧版本 rollback 后只靠 `seedDefaultViews` → View/access 与目标版本不符。
- Restore 后不做 **BPMN fixStaleIds** → 流程引用旧 form/table id。
- 把 **Admin Deploy 回滚** 与 **DW Version Rollback** 混为一谈。
- 未更新本 Skill 就声称「Rollback 已全量还原」。

---

## 测试

| 测试 / 手测 | 覆盖 |
|-------------|------|
| `FormTableBindingRestorerTest` | #1472 binding 推断 + remap |
| `MainTableViewServiceImplTest` | #1471 access delete+flush |
| `MainTableViewPortabilityTest` | View + access import |
| `VersionRollbackParityTest` | v2 格式检测 + email monitor id remap |
| `DecisionDesignVersionSnapshotPropertyTest` | legacy 决策 snapshot/restore |

**手测脚本（MCY FU48 推荐）：**

1. Publish 含子表 + View access + 控件 validate/事件/readonly 的版本 **A**。
2. 再改并 Publish 版本 **B**。
3. Version Management → **Rollback 到 A**（应成功，无 SubTableViewConfig 错误）。
4. Form Design：binding 非 stale，Sub/Relation 导航为真实表名；Case Number 等属性与 **A 快照** 一致。
5. View Design：HMDC Case 保存 BU+Role **无 500**。
6. 可选：Rollback 到 `Auto backup before rollback` 行恢复 rollback 前状态。
7. 与 **Export ZIP of A** Import 到空 FU diff（目标：设计态一致）。

**DB 快查（MCY）：**

```sql
-- binding 是否丢失
SELECT count(*) FROM dw_form_table_bindings b
JOIN dw_form_definitions f ON f.id = b.form_id WHERE f.function_unit_id = 48;

-- 快照是否空 bindings（v2）
SELECT version_number,
  jsonb_array_length(convert_from(snapshot_data,'UTF8')::jsonb->'forms'->0->'tableBindings') AS bindings
FROM dw_versions WHERE function_unit_id = 48 ORDER BY published_at DESC LIMIT 5;
```

---

## 代码 touchpoints

| 类 | 职责 |
|----|------|
| `VersionComponentImpl` | `rollback` / `clearChildCollectionsAndFlush` / `refreshFunctionUnitAfterNativeClear` |
| `FunctionUnitSnapshotRestorer` | v2 restore + legacy + 收尾 `FormTableBindingRestorer` |
| `FormTableBindingRestorer` | #1472 缺 binding 时从 configJson 重建 |
| `FunctionUnitExporter` | `buildVersionSnapshotPayload`（schema v2，整包 configJson） |
| `FunctionUnitImportWriter` | `importFormShell` / `finalizeFormImport` / `importSubTableViewConfigIfPresent` |
| `MainTableViewPortability` | View export/import（含 targetCode access） |
| `MainTableViewServiceImpl` | #1471 `replaceAccessRules` delete+flush |
| `SubTableViewConfigRepository` | native DELETE + `flushAutomatically` |
| `FormDesigner.vue` | 主导航 Sub/Relation 真实表名标签 |

---

## Agent 检查清单（改 Rollback 或用户报丢数据 / 500）

- [ ] 丢的是哪一类？对照 **完整还原矩阵** 与 **§回归缺陷**。
- [ ] 目标 `snapshot_data` 是否 **本来就没有** 该键/属性？（旧快照无法魔法恢复）
- [ ] Form binding stale → #1472 `FormTableBindingRestorer` 是否仍 wired？
- [ ] Rollback 报 SubTableViewConfig session → #1473 clear 后是否仍 refresh FU？
- [ ] View 保存 500 / idx_mtv_access → #1471 delete 后是否 **flush**？
- [ ] 控件属性「丢失」→ 对比 **目标版本快照** configJson，非当前未 Save 状态。
- [ ] clear 路径是否删将被重建的 child 行（含 subTableViewConfig、email）？
- [ ] 新 fix 是否 snapshot + restore **成对** 且复用 Portability？
- [ ] 手测后 Portal 是否需 **Deploy** + Admin Access？
- [ ] 是否更新本 Skill + `.kiro/issues`（#1471–#1473）？

---

## 参考

- 可移植性总纲：`.cursor/skills/function-unit-portability/SKILL.md`
- View 规则：`.cursor/skills/view-access-control/SKILL.md`
- Issue：`.kiro/issues/index.yaml` #1471 / #1472 / #1473
- API：`FunctionUnitController` rollback 端点
