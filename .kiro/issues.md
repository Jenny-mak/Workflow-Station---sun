# Issue 跟踪仪表盘

> **活跃条目**：`.kiro/issues/index.yaml`（Open / Wontfix）  
> 本文件仅保留统计摘要和当前待处理清单。

## 统计 (截至 2026-09-04)

| 状态 | 数量 | 位置 |
|------|------|------|
| 🔓 Open | **15** | `index.yaml` |
| ⏸️ Wontfix | **2** | `index.yaml` |
| ✅ Fixed | **见 index.yaml** | `index.yaml` 内 `status: fixed` 条目（含 #1403–#1404、#1409+、#1461–#1495、#1497–#1500、#1504、#1512–#1519、#1521、#1523–#1530、#1532–#1539、#1541–#1597 等） |


按严重度的分布见 `index.yaml` 各条目的 `severity` 字段（`status: open` / `fixed` / `wontfix`）。

---

## 当前待处理 (Open)

| ID | 严重度 | 分类 | 描述 |
|----|--------|------|------|
| 1402 | major | architecture | VirtualGroupTaskServiceImpl 与工作流引擎未集成 |
| 1405 | major | bug | ACTION 表单弹窗提交无服务端 API |
| 1406 | minor | quality | 多实例状态接口用户名为 User-{id} 占位 |
| 1407 | minor | quality | 动作测试仍为占位实现（流程 simulate 已接入 BpmnProcessSimulator） |
| 1408 | minor | quality | 表单 boundTable 深绑定校验缺失 |
| 1501 | major | bug | Main Table View / Relation Tables 列表未应用 Input 敏感打码 |
| 1522 | major | deploy | dev 库流程实例数据全空，regression:mi 截图门禁全部无法执行（环境 fixture 缺口） |
| 1531 | minor | quality | Form Business Rules 未交付脚手架（DW 无入口 / Portal 无传参 / 库内 0 数据）— 技术债，非线上缺陷 |
| 1540 | minor | quality | Views CSV export：FE 注释称不分页但 body 仍带 size；BE 用 maxRows 覆盖 — 契约需澄清 |

### Wontfix

| ID | 分类 | 描述 |
|----|------|------|
| 095 | quality | auth.ts 三端重复 — 无共享包，维持各应用内模块 |
| 1520 | architecture | 并入 #1531 — mathjs 属未交付脚手架，非双引擎并存 |

---

## 复发问题模式（排查索引）

| 主记录 | 场景 | 相关 |
|--------|------|------|
| **#179** | MI 待办/申请：Link Form、表格下内联 **subtable2** 跨节点不带数、空白、id 串人、Current node 串行 | #177、#178、#1383 |
| **#1392** | MI 子任务主表字段透传：BPMN previous-form BFS 误从 MI 子流程 startEvent 起跑 / 缺父子流程感知 / 同名约定无诊断；同时存在 SubTaskForm.vue 死代码副管线违反 portal-design-parity | #1396 |
| **#1396** | MI 字段透传 Path 2 readonly 快照剩余两条 BFS 缺口：`MI 子任务 → 后续主流程`（subProcess 内部 user task 不可达）+ `intra-MI`（同 MI 内 sub form1 → sub form2 兄弟不可达）；引入 subProcess 内部递归 + 内部 BFS 一并补齐 | #1392 |

| **#1435** | MI People 内联表单：Save 后 id 空 / hydrate 被跨参与者 PK 覆盖 / UUID 被 scrub 覆写 | #1437、#1438 |
| **#1438** | MI sub form1 **Shared Attachment**：新增 1 条后旧附件 UI 消失；foreign-id 误删合法 file 行；Sub Task 泄漏纯 id+file | #1435、`portal-mi-subtable-my-request.mdc` §Shared Attachment |
| **#1439** | MI **sub form2** People 未继承 sub form1 的 age/sex/name；stale `sub_task_id` + collapse pick-one | #1437、`portal-mi-subtable-my-request.mdc` §sub form1→2 |
| **#1440** | MI People **内联表单**编辑（如开 sex）串入**上一参与者** age/id；nested 写入全 slice + inline merge 未 scope | #1437、#1439、`portal-mi-subtable-my-request.mdc` §nested scope |
| **#1441** | **My Request** Details / Link Form 弹窗：IN_PROGRESS 子任务 People 空白（parentChildTaskStatusesMatch 误杀无 task_status 子行） | SubTableField.vue `handleLinkFormClick` |
| **#1442** | 待办 **Sub Task Edit** 改 assignee：UI Assignee 列 stale；**MI 子任务仍分给旧人**（__subTables__ slice 64 stale vs 66 编辑，collection 先写入 64） | SubTableField.vue、useTaskForm.ts、TaskProcessComponent.selectRowsForMiCollection |
| **#1443** | MI 办理人 **sub form2**：Sub Task 子表空 + People 未带入 sub form1 age/sex；link-child scrub 把 collection 行 `id_idw` 误删 | #1435、scrub `skipSliceKeys`/`buildMiCollectionSliceKeySet`、`miCollectionIdIdwScrub.test.ts` |
| **#1444** | MI 办理人 People **Save 后表单回显旧值**：persisted 已更新但 inline form 仍显示 stale age；foreign 占位行 seed/collapse 污染 + flatten/hydrate  sibling slice 63 覆盖 30 | #1435、#1443、`flattenNestedSubTableRowsIntoPayload`、`hydrateMiLinkChildBindingsFromFullSnapshot`、task e921c696 Test-000071 |
| **#1466** | **Email Monitor** 启动流程后 Portal My Request / 待办不可见 | EmailMonitorPortalSyncComponent + internal hydrate API（已 fixed 2026-06-30） |
| **#1512** | **Email Monitor** hydrate 因引擎 GET 403 失败（无 JWT） | engineSnapshot 随 hydrate 下发，跳过引擎 GET（已 fixed 2026-08-04） |
| **#1513** | **Main Table View** 列表可见但 View Record 403（非参与者） | canAccessProcessDetail 对齐列表视图规则（已 fixed 2026-08-04） |
| **#1514** | **My Requests** manualTask 流程图节点不着色 | bpmnHumanWorkflowTasks 解析 manualTask 等（已 fixed 2026-08-04） |
| **#1515** | **Email Monitor** 收信后建案失败 `missing_function_unit_id` | 启动变量补 `functionUnitCode`（已 fixed 2026-08-04） |
| **#1467** | **Email Monitor** 转发邮件内层 HTML 未抽取 | ImapInboundMailClient nested message/rfc822（已 fixed 2026-06-30） |

详情与根因清单见 `index.yaml` 中 `id: "179"`（`recurrence: pattern`）。改 `shared.ts` / `tasks/detail.vue` / `FormRenderer.vue` / `SubTableField.vue` 时优先跑上述单测并做三参与者手测；最新一例（#1383）是当前参与者打开 sub form1 时 inline subtable2 被预填上一参与者的输入，根因在 `isolateMiSubTaskData` 重建 myRow.__subTables__ 时遗漏 MI 过滤、且 `syncMiLinkChildRowsIntoParentNested` 在空数据时不回写残留。#1438：Attachment UUID 不得进入 `foreignSubTableRowIds`；有 `file` 的行在 `isLeakedForeignRowOnSharedAttachment` 中 MUST 先判定保留；`patchFormDataSubTablesFromCurrentBindings` 对 shared attachment MUST merge 全量快照。手测 task 093962c4 Attachment 3 行。#1440：`patchMiParentRowsWithNestedChildSlice` MUST `scopeMiLinkChildRowsForParentRow`；`syncMainSubTableRows` binding.data 用 merge 后 `out`；inline `mergeRowsForInlineFormTarget` nested 须 `pickMiLinkChildRowsForParent`。手测 `verify-sex-toggle-isolation.mjs` task 6c6c5cc6。#1444：Save 后 variables 正确但 People inline 仍 stale → flatten 须让顶层 slice 覆盖 nested；hydrate 须让当前 binding slice 覆盖 stale sibling（如 63 vs 30）；seed/collapse 不得处理 foreign 占位行。手测 `frontend/scripts/_cap-e921.mjs` task e921c696 Test-000071。改 `useBpmnParser.ts` 时优先跑 `useBpmnParserPreviousForms.test.ts`（8 case 含场景 1/2/2-regression/3/4 + boundary + dedup + missing-current）。

---

## 跟踪系统说明

issue 详情为机器可读 YAML：

- **待处理 / Wontfix**：`.kiro/issues/index.yaml`
- **已修复**：`index.yaml` 中 `status: fixed` 条目

查看所有未修复问题：
```bash
grep "status: open" .kiro/issues/index.yaml
```

查看特定分类（活跃）：
```bash
grep -A2 "category: security" .kiro/issues/index.yaml | grep "status: open"
```

按 ID 检索已修复条目：
```bash
grep -A20 'id: "1404"' .kiro/issues/index.yaml
```
