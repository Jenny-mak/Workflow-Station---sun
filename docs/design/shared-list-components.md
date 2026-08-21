# 跨端列表共享组件与服务端分页接入规范

> **状态：方案已定稿；Views / Relation Tables / 已办任务 / My Requests / To Do 已接入（2026-08-24）。**
> §12 的 6 项待确认**全部关闭**。Delegations（规则+审计）与 Permissions（申请+审批）另分支，
> 未写入本状态行；AD / DW 仍未做。
> To Do 采用引擎 pushdown（Mine 真分页）+ 不可 push 时 fullScan 精确 total；
> **默认不再把 standing-rule Proxy 并进 To Do**（Proxy 仍走 Delegations / assignmentTypes=DELEGATED）。
> **已知未做 → [#1545](../../.kiro/issues/index.yaml)**：默认路径稳 ≤500ms、groupBy/日期去掉 portal fullScan
> （须引擎侧 SQL，另开改造；投影表仍暂缓）。
> 关键决策：§6.1（行可见范围两级过滤）、§6.1.1（SUB 行身份取 `row_id` 优先，写入侧→MAIN→SUB
> 三步走）、§6.2（深分页不设上限，改慢查询日志）、§6.3.1（分组按字段语义声明，不是每列都给）、
> **§6.3.2（筛选 kind 的权威是表 `data_type` / 视图系统列，不是 Form 组件；`FILE` 本期
> display-only，禁止当 TEXT 按文件名凑合筛）**、
> **§6.3.3（排序按 kind：字母 / 数值大小 / 新旧，数字不得按文本排）**、
> **§6.5（Relation Tables：业务表 JSON 行；内置 User=`sys_users`；类型化筛选；RT 一律不分组）**、
> **范围排除 developer-workstation**、**全程零兜底**。
> 可以按 §8 分期 + playbook 逐提交执行。
>
> **给后续 agent 的硬约束（违反即返工）：**
>
> - 共享组件落在 `frontend/shared/src/list/`，经**已存在**的 `@platform-shared` alias 消费。
> **不要**放 `frontend/packages/core`——它是零依赖空壳，且仓库里没有 `pnpm-workspace.yaml`。
> - 行可见范围（involvement）**不得**把子串 / `ILIKE '%uid%'` 当作**最终判定**；它只能作为
> 候选粗筛，后面必须接精确复核，见 §6.1。
> - **本期一行 DW 代码都不改。** `frontend/developer-workstation/`** 与
> `backend/developer-workstation/**` 零 diff（**连 re-export 也不做**）；DW 的 `designer-list`
> 只作**只读参考**。验收 = `git diff --stat frontend/developer-workstation` 输出为空，见 §3、§8。
> - **全程零兜底。** 任何"取不到就给默认值 / 就当没有 / 就换另一条路"的写法都不许进这批代码：
> 缺行身份 → 抛错（§6.1.1）；未知算子 → 400（§6.3）；缺分组计数 → 抛错（§6.4）；
> 共享目录构建失败 → 停下报告，不设退路（§9）。
> - SUB 视图的行身份**只能**由共享的 `ROW_IDENTITY_FIELDS` 优先级取（`row_id` 优先）；**禁止**
> `md5(elem::text)` / `hashCode()` 一类内容兜底，禁止写死 `elem->>'id'`，也**不要**走物理表
> PK 元数据（业务子表没有物理表，那条路必然取空），见 §6.1.1。
> - 分组入口**按字段语义声明**：`groupable = false` 的列不渲染分组项；不许把整类 kind 的默认值
> 改成 true 图省事，见 §6.3.1。
> - 共享组件命名**中立**：不带 `MainTableView` / `Portal` / `Designer` / `mtv-` / `dwl-` 前缀——
> 将来 DW 接入时要能直接复用同一份，见 §5。
> - 共享目录里**不能用** `@/` 导入（`@` 在每个 app 指向自己的 `src`）。组件、util、scss 必须
> 一起搬进 `frontend/shared/src/list/` 并改成相对路径。
> - **一个菜单一个提交**；未接入的菜单一行代码都不改。对未接入的菜单，共享组件是纯新增。
> - **命名保持基线，不继承 PR #107 的改名**：任务菜单叫 **To Do**、代办任务叫 **Proxy Tasks**
> （基线 `en.ts` 既有 key：`tasks: 'To Do'`、`delegation.proxyTasks: 'Proxy Tasks'`）。
> PR #107 系列把它们改成 "Pending Tasks" / "Acting For"（提交 `3722b6589`），重做时**不带回**
> ——从基线开分支天然就是对的，别再动这些 i18n key（用户已确认，2026-08-17）。
> 唯一允许删除的旧实现是 UP 的列宽拖拽（切换到共享件的同一个提交里删，见 §8 提交 2）；
> **DW 侧任何文件都不删、不改**。
> - 组件层**不发请求、不持久化、不认识业务字段**，见 §5 职责边界。
>
> **复盘对象：** PR #107（121 文件 / +14143 −1935，未合并，已弃）。该 PR 内的
> `docs/design/portal-main-table-view-query.md` 随分支一起弃用，其 involvement 章节记录的是
> **被否决**的方案（§6.1 引原文）。

相关文档：

- View 行可见性管控：skill `view-access-control`（本文的 §6.1 不得与之冲突）
- 门户身份来源：[portal-bu-rbac.md](./portal-bu-rbac.md)（BU + Role 决定可见范围）
- 长度 / 分层 / UX / 性能红线：`.cursor/rules/code-quality-standards.mdc`
- FILE 列按文件名筛（未合入基线）：[list-file-name-filter.md](./list-file-name-filter.md)
- Relation Tables / 内置 User：**正文 §6.5**（不再单开文档）
- 错误处理红线（禁静默兜底、禁重复决策）：`.cursor/rules/error-handling-governance.mdc`

---

## 1. 背景：PR #107 为什么整块弃掉

PR #107 想做的事和本文一致（统一列头 + 真分页 + 类型化筛选），方向正确，但交付方式导致无法合并：


| 问题           | 表现                                                                                                                |
| ------------ | ----------------------------------------------------------------------------------------------------------------- |
| **横切而非竖切**   | 一个提交同时改 7 个列表的列头，下一个提交同时改 7 个列表的 SQL → 单提交 62 文件，无法审查                                                             |
| **重新发明已有轮子** | DW 的 `designer-list` 四件套已在 8 个视图使用，PR #107 却在 UP 另写一套 `portal-list`（`portalListGridRuntime` 439 行，其中约 95 行零引用死代码） |
| **越权**       | 行可见范围下推 SQL 时用子串近似（§6.1），非参与人可读到行数据                                                                               |
| **状态与查询不一致** | 关联表切表时用上一张表的筛选查新表，随后静默重置列头 → 用户把子集当全量读                                                                            |
| **分组计数恒 0**  | 后端按原始值分组、前端按显示值取计数并 `?? 0` → lookup 列分组显示 `(0)`                                                                   |
| **长度突破**     | `permissions/index.vue` 1410 → 2406 行（硬上限 600）；另三个视图被推过 600                                                       |
| **无截图**      | 7 个列表视图全重构，`verification-screenshots/` 一张没有                                                                       |


值得记住的一点：**这次不是缺测试**。分支带了 18 个新单测（约 1700 行），其中 115 行专测
SQL 编译器，越权仍从它下面漏过——因为测试断言的是"当前实现的行为"，不是"需求不变式"。
本次重做的测试必须先写不变式（§10 反例）。

## 2. 目标 / 非目标

**目标**

1. 列头、列宽拖拽、筛选弹窗、分页四个组件落在共享层，**本期由 user-portal 与 admin-center 两端消费**，
  命名与实现保持中立，将来 DW 接入无需再改共享层。
2. **按菜单增量接入**：任何时刻停手，未接入的菜单行为完全不变，无需 feature flag。
3. 每个接入点同时满足四条（§6）：不越权、真服务端分页、筛选算子随字段类型、分组标签单边产生。
4. **全程零兜底**：所有"取不到"的情况都显式失败，不给默认值、不静默降级（见头部硬约束）。

**非目标（本期明确不做）**

- **developer-workstation 的任何改动**：DW 不接入共享组件，`designer-list` 保持原样，
连改成 re-export 都不做。因此 DW 那套与共享层会短期并存，这是**明确接受的重复**，
已按 `issue-radar` 备案（§9），等 DW 接入时再合并。
- 虚拟滚动、列固定、列显隐；列状态持久化仍用 `sessionStorage`，不落后端。
- 跨菜单的"保存视图 / 我的筛选方案"功能。
- 重构非列表类巨型文件（UP `tasks/detail.vue` 等）。
- 引入 pnpm workspace——沿用现有 alias，见 §4。

## 3. 现状事实（已探查确认，勿再凭猜测）


| 事实                                                                     | 证据                                                                                                                                                                                                                                                              |
| ---------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `@platform-shared` → `frontend/shared/src` 的 alias **三端都已配好**          | 三个 `vite.config.ts` + 三个 `tsconfig.json` 的 `paths`                                                                                                                                                                                                              |
| 该 alias 已在真实使用                                                         | user-portal 6 处、developer-workstation 4 处、admin-center 3 处                                                                                                                                                                                                      |
| `frontend/packages/core`（`@workflow-station/core`）是**空壳**              | 仅 README + package.json，零 app 声明依赖，且缺 `pnpm-workspace.yaml`                                                                                                                                                                                                     |
| `frontend/shared/src` 目前**只有** `.ts`**，没有** `.vue`                     | 现有内容：`computedField/`、`lookupCascadeCore.ts`、`tableFkRuntime.ts` 等                                                                                                                                                                                              |
| 列宽拖拽**已有实现**（UP）                                                       | `components/mainTableView/MainTableViewColumnResizeHandle.vue` + `utils/mainTableViewColumnResizeCursor.ts` / `.scss`                                                                                                                                           |
| **DW 已有成熟的完整套件**（本期只作**只读参考**，不改不接）                                    | `components/designer-list/`：`DesignerListColumnHeader.vue` 131 行、`DesignerListColumnResizeHandle.vue` 57 行、`DesignerListFilterDialog.vue` 109 行、`DesignerListTable.vue` 140 行；+ `utils/designerListGridRuntime.ts` 164 行、`styles/designerListColumnResize.scss` |
| DW 那套**已在 8 个视图里在用**                                                   | `ActionDesigner` / `ConnectionDesigner` / `DecisionList` / `EmailMonitorDesigner` / `EmailTemplateDesigner` / `TableDesigner` / `FormListSidebar` / `VersionManager`                                                                                            |
| UP 的 `portal-list` 在基点上**不存在**                                         | 它是 PR #107 新造的；基点只有 `mainTableView/` 那两个组件                                                                                                                                                                                                                      |
| 基线分支是 `origin/common_0731_SELF_AP`                                     | 仓库里**没有** `origin/0731`、也没有 `origin/main`（`git rev-parse --verify` 均 fatal）。基线最新提交 `bbc79018d`（2026-08-16，merge PR #106）                                                                                                                                        |
| Main Table View 的 SQL 分页在基线上**不存在**                                    | `MainTableViewJdbcQuery.java` 与 `MainTableViewSqlQueryCompiler.java` 在 `origin/common_0731_SELF_AP` 上都没有（`git cat-file -e` 验证），只在本地 review 分支上（已 merge 两个 `jenny/feat/portal-*-pagination-0731`）。SUB 分页是从零写，无存量要兼容                                              |
| 业务子表**几乎没有物理表**，所以行身份**不能**走物理 PK 元数据                                  | dev 库 `dw_table_definitions` **1228** 行，与之同名的物理表只有 **2** 张。`SubTablePhysicalMetadataCache` 的类注释已写明"Business tables are JSON-row stored (no physical table)…most lookups are stable absent results"——`resolvePkColumnsCached` 对业务子表返回**空列表**。见 §6.1.1            |
| 子表行的身份键实际是 `row_id`                                                    | dev 库 `__subTables__` 共 27 行：`row_id` **26** 行，`id` / `id_idw` / `rowId` / `rowKey` **各 0** 行，完全没有身份键的 **1** 行。既有候选键正源 = `ChangeHistorySubmissionFilter.ROW_IDENTITY_FIELDS`（`row_id`, `rowId`, `rowID`, `id_idw`, `_rowKey`, `rowKey`, `id`，按此优先级）             |
| 存在**无任何身份键**的真实数据行                                                     | `pid=51d70796…` slice `50533` 有完整业务字段（`card_number` / `merchant_name` / `dispute_reason` 等）却没有上述任何键 → 读取侧一旦按 §6.1.1 抛错，这个视图会直接 500。必须先补写入侧，见 §8                                                                                                                 |
| 同一子表被绑进多张表单是常态，且**同一行会跨 slice 重复出现**                                   | `dw_form_table_bindings`：`table_id=5` 有 5 个 binding，另有多张 3–4 个。实测 `pid=875491e6…`、`table=50326`：3 个 slice 共 3 行，但 `distinct row_id = 1` —— 同一逻辑行出现在 3 个 slice 里。因此行身份**不能**含 slice key（§12 第 6 项已由此定案）                                                          |
| 列状态存 `sessionStorage` 是**既成规则**，不是本文档新拍的                                    | UP 基线 `mainTableViewGridRuntime.ts` 的 `loadGridRuntimeFromSession` / `saveGridRuntimeToSession`，key = `portal-mtv-runtime:${viewId}`（该文件**在基线**）；DW 基线 `useDesignerListGrid.ts` 注释 "Persistence key (per Function Unit + list); state is remembered in sessionStorage"。两边独立实现、选择一致。对比：DW `useLaunchpadLayout.ts` 是**落服务端**、localStorage 仅作本机快显缓存 —— 项目里"要跟人跨设备"才落库 |
| `PortalListColumnMeta` 已有 `filterable` / `sortable` / `groupable` 三个开关 | 但 `text()` / `user()` / `datetime()` / `enumOf()` / `enumCodes()` 五个工厂方法**全部硬编码 `groupable = true`**，所以实际效果是"每列都能分组"。该文件**不在基线**（未跟踪，属 PR #107 系列），重做时按 §6.3 重定默认值                                                                                              |
| 共享目录里 `@/` 导入**不可用**                                                   | `@` 在每个 app 指向自身 `src`；UP 那份组件正是 `import ... from '@/utils/...'` + `@import '@/utils/....scss'`                                                                                                                                                                 |
| 表格存量规模                                                                 | 含 `el-table` 的文件：AD 31 / DW 22 / UP 21；含 `el-pagination`：AD 10 / UP 4 / DW 2                                                                                                                                                                                    |


**DW 不在本期范围内，所以「提取 DW 组件」这条路本期不走。** 表里关于 DW 的三条仍然重要，
但用途变了：它们是**只读参考**——DW 的 `designer-list` 已在 8 个视图里跑着、每个文件都在长度限内、
已过 review，所以新写共享组件时照它的结构和交互写，不要另发明一套。读它不改它。

阶段一第 1 个提交因此改用 **UP 的列宽拖拽**做搬迁样本（第五条）：它同样是"已有实现 + 已有调用方"，
能验证「alias 下的 `.vue` 能构建、能过 `vue-tsc`」这条唯一的未知链路，而且搬完 UP 那份就删掉，
**净重复不增加**。DW 那份保持原样。

UP 的 `MainTableViewColumnResizeHandle.vue` 与 DW 的 `DesignerListColumnResizeHandle.vue` 逻辑
**逐行相同**，差异只有四处——import 路径、光标常量名（`MTV_` vs `DWL_`）、body class
（`mtv-column-resizing` vs `dwl-column-resizing`）、以及 DW 模板上多一个 `@click.stop`；
两份**都缺** `onBeforeUnmount` 清理，共享版必须补上。UP 的 scss 头一行还写着
"mirrors DW `designerListColumnResize.scss`"——`error-handling-governance.mdc` 红线 3 点名的正是这种
"靠注释 mirror 对齐 = 重复，不是共享"。本期把 UP 那一侧收进共享层，DW 一侧的重复**留到 DW 接入时再消**，
已备案（§9）。

顺带说明 PR #107 为什么会膨胀到 14k 行：它在 UP 里**重新发明**了 DW 已有的整套东西
（`PortalListColumnHeader` 327 行 / `PortalListFilterDialog` 202 行 / `portalListGridRuntime` 439 行，
其中约 95 行是零引用死代码）。本期新写列头与筛选弹窗时**必须**先读 DW 那两个文件（131 / 109 行）
再动手——它们是尺寸和职责的基准线。

倒数第二条决定了搬迁粒度：`.vue` 不能单独搬，必须与它依赖的 `.ts`（光标 SVG + `clampColumnWidth` +
`COLUMN_WIDTH_MIN/MAX`）和 `.scss` 一起进共享目录。

## 4. 方案：共享组件放哪


| 方案                                                                 | 评价                                                                            |
| ------------------------------------------------------------------ | ----------------------------------------------------------------------------- |
| **A.** `frontend/shared/src/list/`**，经** `@platform-shared` **消费** | **推荐。** 三端 alias 与 tsconfig 已就绪且在用，零工程改造；与 `tableFkRuntime` 等既有跨端共享物同一落点，风格一致 |
| B. `frontend/packages/core`                                        | 拒。需要先补 `pnpm-workspace.yaml` + 三端加依赖声明，把工程改造混进功能改造；且该包至今零消费者，等于同时验证两件没验证过的事   |
| C. 三端各放一份副本                                                        | 拒。直接违反 `error-handling-governance.mdc` 红线 3（一个决策一个位置），也是 ISSUE-095 复制粘贴问题的复发  |


推荐 **A**。唯一新增的未知量是"`.vue` 放共享目录"，由阶段一第 1 个提交单独验证。

## 5. 组件契约（四个组件）

**职责边界（四个组件共同遵守）：** 组件**不发 HTTP 请求**、**不读写 sessionStorage**、
**不认识任何业务字段名**。列状态（顺序 / 宽度 / 排序 / 筛选 / 分组）由**各 app 自己的 composable**
持有并持久化；组件只接收 props、抛出事件。这样同一个组件才能同时服务 UP 的流程列表和 AD 的用户列表。

**命名必须中立。** 本期只有 UP 与 AD 消费，但名字里仍不能出现 `MainTableView` / `Portal` /
`Designer`，CSS 与 body class 也不能沿用 `mtv-` / `dwl-` 前缀（统一成语义化的 `is-column-resizing` 之类）——
将来 DW 接入时要能直接用同一份，不能因为名字带 `Portal` 而被迫再改一轮共享层。
body class 改名只动 **UP 一侧**（DW 的 `dwl-column-resizing` 原样保留），是**全局副作用**，
要同步 UP 的 scss 与调用点，单独一个提交并在 UP 截一张拖拽图——那个自定义深色光标 SVG
是为了避免系统光标在浅色表头上发白，回归了不容易发现。


| 组件                   | 输入（props）                                                                     | 输出（emits）                                                                               | 不负责                                                                             |
| -------------------- | ----------------------------------------------------------------------------- | --------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------- |
| `ListColumnHeader`   | 列定义（字段、标题、类型 kind、`filterable` / `sortable` / `groupable`、可选项）、当前排序、当前筛选、当前分组 | `sort-change`、`filter-change`、`group-change`、`clear-sort`、`clear-filter`、`width-change` | 取数、决定算子有哪些（由 props 传入）、持久化。`**groupable = false` 的列不渲染分组项**（不是灰掉），见 §6.3.1      |
| `ColumnResizeHandle` | 起始宽度、最小宽度                                                                     | `resize`（拖拽中）、`resize-end`（落定）                                                          | 记住宽度。**必须**在 `onBeforeUnmount` 里清理 document 监听与 body 光标样式——UP / DW 现有两份**都缺**这段 |
| `ListFilterDialog`   | 列的 kind、该 kind 允许的算子、可选项、当前筛选值                                                | `confirm(filter)`、`cancel`                                                              | 决定算子有哪些（由 `/columns` 下发经 props 传入）；未识别的算子**不许**静默放行                             |
| `ListPagination`     | `page`、`size`、`total`、`loading`、可选页长选项                                        | `change({ page, size })`                                                                | 自己发请求；页长变化时**必须**同时把 page 归 1 后再抛一次事件。**不画转圈**：`loading` 只禁用翻页；网格中间的 overlay 由宿主 `v-loading` 负责，禁止两只转圈 |


**来源**（DW 文件一律只读参考，不改不搬，见 §3）：


| 共享组件                 | 来源                                                                                                                                   | DW 侧动作 |
| -------------------- | ------------------------------------------------------------------------------------------------------------------------------------ | ------ |
| `ColumnResizeHandle` | **搬 UP 那份**：`MainTableViewColumnResizeHandle.vue` + `mainTableViewColumnResizeCursor.ts` / `.scss`；补 `onBeforeUnmount` 清理。搬完删 UP 原文件 | 不动     |
| `ListColumnHeader`   | **新写**，照 DW `DesignerListColumnHeader.vue`（131 行）的结构与交互写                                                                             | 不动（只读） |
| `ListFilterDialog`   | **新写**，照 DW `DesignerListFilterDialog.vue`（109 行）写                                                                                   | 不动（只读） |
| `ListPagination`     | **新写**——DW 的设计器列表不分页，无参照物                                                                                                            | —      |


**筛选弹窗**由 `ListColumnHeader` 内部使用，对外只暴露 `filter-change`；单独成文件是为了对齐 DW 的
既有结构，将来 DW 接入时两边能对上，也让单个文件都留在长度限内。

**浮层实现约束**（`code-quality-standards.mdc` §6.6 已有明确结论，照抄不要重新发明）：列头下拉与筛选浮层
必须 `Teleport to="body"` + `position:fixed` + 按 `getBoundingClientRect()` 定位，z-index 用
Element Plus 的 `useZIndex().nextZIndex()` 动态取；点外关闭要把 teleport 出去的浮层节点排除在"外部"之外。
理由：父级卡片会建立层叠上下文，`absolute` + 写死 z-index 会被后续卡片和对话框遮住。

## 6. 接入协议：每接一个菜单必答 4 问

**答不上就不要接。** 四个答案写进该菜单接入提交的 commit message 或 PR 描述。

### 6.1 行可见范围：不越权

先回答：**这个列表的行可见范围由什么决定？**（全量 / 本人发起 / 本人参与 / BU+Role / 系统管理员绕过）
把它下推到 SQL 后，**最终判定**必须精确；近似匹配只能用来缩小候选范围，不能直接决定可见性。

PR #107 被否决的写法（`MainTableViewSqlQueryCompiler#compileInvolvement`，原文保留作反面教材）：

```
(pi.start_user_id = ?
 OR EXISTS (SELECT 1 FROM ACT_HI_TASKINST ht WHERE ht.PROC_INST_ID_ = pi.id AND ht.ASSIGNEE_ = ?)
 OR (pi.variables -> '__subTables__')::text ILIKE ? ESCAPE '\')     -- 参数 = '%' + userId + '%'
```

前两个分支（发起人、历史办理人）**是精确的，可以保留**。问题在第三个分支：对 `__subTables__`
整棵 JSON 子树做大小写不敏感的子串匹配，只要 userId 作为**任意子串**出现——出现在别人更长的 id 里、
出现在用户手填的文本字段里、出现在文件路径里——该行就变成"可见"。PR #107 自带的设计文档
把这条记为「pragmatic MI participant hint」并承认"短 user id 可能误命中"，但访问管控不接受误命中。

**本期定稿：沿用现有权限语义，两级过滤。** 不改动 `MainTableViewInvolvementChecker` 的判定规则
（它就是"现有数据权限"的真源：只看键名像参与人的键，值用 `Objects.equals` 全等比较），也不新增表；
只是把它的结果变成 SQL 能用的分页条件。三个判定分支里前两个直接留在 SQL（本来就精确），
只有 MI 参与人这一支需要两级处理。

**第 1 级 — SQL 粗筛**，只用来缩小候选范围（允许多，不允许漏）：

```sql
SELECT pi.id, pi.variables FROM up_process_instance pi
WHERE (pi.variables -> '__subTables__')::text ILIKE '%' || :uid || '%' ESCAPE '\'
```

**第 2 级 — 精确复核**：对这批候选跑 `MainTableViewInvolvementChecker`，剔除假阳性，得到精确的 `miVisibleIds`。

**第 3 级 — 分页查询本体**：

```sql
WHERE (pi.start_user_id = :uid
       OR EXISTS (SELECT 1 FROM ACT_HI_TASKINST ht
                  WHERE ht.PROC_INST_ID_ = pi.id AND ht.ASSIGNEE_ = :uid)
       OR pi.id = ANY(:miVisibleIds))
  AND <列筛选>
ORDER BY <分组字段>, <排序字段>
LIMIT :size OFFSET :offset
```

**为什么这样是精确的**——这是方案成立的关键，也是必须写成测试的不变式：checker 判定"参与"
⟹ 存在某个参与人键的值**全等** userId ⟹ 该字符串必然出现在 `__subTables__` 的 JSON 文本中
⟹ 粗筛的 `ILIKE '%uid%'` 必然命中。所以**粗筛结果是精确结果的超集**，只会多不会少；复核把多出来的
剔掉，最终逐行等于现有 checker 的判定。`ILIKE` 大小写不敏感只让超集更大，不影响成立。
注意这同时意味着 PR #107 那段 SQL 本身没写错——错在把它当**最终判定**用了。

实现要求：

- `= ANY(?)` 传 `text[]` 数组参数，**不要**拼 N 个 `IN (?,?,?…)` 占位符——Postgres 对大数组处理更好，
也不会撞参数数量上限。
- `miVisibleIds` 翻页时不变，按 `(userId, viewId, activeBusinessUnitId)` 做短 TTL 缓存，
只有第一页付计算代价。**缓存 key 必须含 userId 与 BU**——PR #107 的 `FULL_SCAN_CACHE` 漏了这个。
- **只有这一条路径，不设阈值兜底。** 粗筛通常能把候选缩得很小（现有 user id 形如
`user-test-44027893`，MI 参与人字段里存的是 8 位工号，选择性足够）；即使 userId 极短导致粗筛几乎不起作用，
也只是退化成读全部候选——**结果依然精确，只是不快**。不引入"超过阈值就换另一条实现"的分支：
那是第二个决策点（`error-handling-governance.mdc` 红线 3），两条路径迟早语义漂移。

**已否决 / 暂缓的备选**（不要在本期重新提议）：


| 备选                                       | 结论                                                                                                |
| ---------------------------------------- | ------------------------------------------------------------------------------------------------- |
| 落参与人表 `up_process_participant`，由引擎任务事件维护 | **暂缓。** 精确且可索引，是数据量真上去之后唯一同时解决正确性与性能的做法；但需要新表 + 引擎写入路径 + 历史回填，超出本期范围                              |
| 具名键 jsonb 匹配（键名取自 FU 配置）                 | **否决。** SQL 要按配置动态生成，`__subTables__` 是「slice → 行数组」两层结构需 lateral 展开；精确度完全取决于 FU 配置完备性，设计师改了字段名就会漏 |


**门禁：** 该菜单接入提交里必须先有两条**失败测试**再写实现——(1)「非参与用户查询该视图，结果不含某行」；
(2)「粗筛结果 ⊇ 精确结果」的超集不变式。这两条是本次重做最重要的产出物。

系统管理员绕过（`SYS_ADMIN`）沿用 skill `view-access-control` 的既有语义，不在本文重新定义。

#### 6.1.1 MAIN / SUB 视图的行身份，以及为什么必须分三步走

Main Table View 有两种 `table_type`，"一行"的含义完全不同，**不要合成一个提交做**：

- **MAIN**：一个流程实例 = 一行。可见范围过滤完直接 `LIMIT / OFFSET` 打在 `up_process_instance` 上，
排序键就是表上的真实列，索引好建。
- **SUB**：一个子表行 = 一行，行来自 `variables -> '__subTables__' -> <bindingId>` 这个 JSON 数组的
lateral 展开，一个实例会放大成 N 行。分页要作用在**展开后**的行集上，`total` 也必须是展开后的
`COUNT`，因此需要"行身份"这个概念——MAIN 不需要。

**行身份来自 JSON 行上的身份键，不是物理表 PK，也不是字面量 `id` / `id_idw`。** 这一段是上一版方案
被实测推翻后的定稿，改动理由必须留着，避免下次又绕回去：业务子表按 `json-row-storage-no-physical-tables`
规则**只有 JSON 行、没有物理表**（dev 库 1228 张设计表对 2 张物理表，见 §3），所以
`SubTablePhysicalMetadataCache#resolvePkColumnsCached` 对业务子表返回**空列表**，
`SubTableRowKeySupport#rowKeyFromVariableRow(row, pkCols)` 随之返回 `null`。
**若按物理 PK 取身份，等于每个 SUB 视图都取不到身份。** 那条路只适用于 MI overlay
（`MiOverlayComponent` / `SubTableEnrichmentComponent`——它们面对的确实是那少数几张物理表），
MTV 的 SUB 视图**不能**用。

正源是 JSON 行的身份键候选列表，已经存在于 `ChangeHistorySubmissionFilter.ROW_IDENTITY_FIELDS`：

```
row_id, rowId, rowID, id_idw, _rowKey, rowKey, id      ← 按此优先级取第一个存在的键
```

dev 库实测的分布印证了这个顺序：27 行里 26 行用 `row_id`，`id` / `id_idw` **一次都没出现**。
**这直接说明 PR #107 的 `COALESCE(elem->>'id', elem->>'id_idw', md5(elem::text))` 在真实数据上
100% 落到 `md5` 分支**——它的 SUB 分页实际是按行内容去重的，不是按行身份。

实现要求：

- 把 `ROW_IDENTITY_FIELDS` 及"取第一个存在的键"这段逻辑**提升为共享正源**（建议放
`platform-common` 的 `SubTableRowKeySupport`，它已经在处理 `rowKey` 信封），
`ChangeHistorySubmissionFilter` 改为调用它。**不许**在 MTV 侧照抄一份第二实现——
`error-handling-governance.mdc` 红线 3。
- SQL 侧与 Java 侧只共享**这份键清单与优先级**，不要求键的字符串格式逐字节一致；
SQL 只需保证"同一行 ⟹ 同一键"。
- 取键要**大小写不敏感**（`getRowValueIgnoreCase` 的既有语义），但注意 `rowId` 与 `row_id`
差一个下划线、`equalsIgnoreCase` 匹配不到，两个都必须在候选列表里显式列出。

**没有身份键的行：抛错，不静默合并。** PR #107 的做法是 `md5(elem::text)` 兜底——两行内容相同就被
并成一行，`total` 跟着少，用户看不到任何异常。本期**不继承这个兜底**（它是 PR #107 新引入的，
基线上不存在，见 §3），改为在 `mapSubRow` 层检测到行身份为 NULL 时抛 `PortalException`，
由 `GlobalExceptionHandler` 统一成 `ApiResponse` 错误体；message 带 `viewId` + `processInstanceId` +
slice key，**不带行内容**（避免 PII 进日志与响应）。

**这条抛错在 dev 库上会真的触发，所以必须先补写入侧再上读取侧**（§3 已记该行）：
`pid=51d70796…` slice `50533` 有一行带完整业务字段却没有任何身份键。它是写入路径的缺陷——
`ProcessSubTablePrimaryKeyEnricherComponent` 只在子表**声明了 auto PK 字段**时才分配，
没声明 PK 的子表写进 `__subTables__` 时就没有身份。因此 §8 里 SUB 提交之前**必须**先有一个
写入侧提交：保证任何写进 `__subTables__` 的行都带 `row_id`，并对存量数据做一次巡检 / 回填。
**顺序颠倒 = 上线即 500。**

一个必须写进注释的诚实边界：`DISTINCT ON` 把 NULL 键视为相等，所以多条无身份键的行会先被折叠，
抛错时报出的行数可能少于实际缺身份的行数——修数据时要按 `(processInstanceId, sliceKey)` 全量排查，
不能以为报一条就只有一条。

SUB 的分页 SQL 必须分两层：**内层只做展开 + 去重**，外层才做可见范围、列筛选、用户排序与分页。
不能合成一层——`DISTINCT ON` 要求 `ORDER BY` 以去重键开头，与用户选的排序字段互斥。
`<row_identity>` = 按 `ROW_IDENTITY_FIELDS` 优先级取第一个存在的键，即
`COALESCE(elem->>'row_id', elem->>'rowId', elem->>'rowID', elem->>'id_idw', elem->>'id')`
（`rowKey` / `_rowKey` 是嵌套信封，按 `SubTableRowKeySupport` 的既有语义展开后再取）。

```sql
SELECT * FROM (
  SELECT DISTINCT ON (pi.id, <row_identity>)
         pi.*, expanded.elem AS sub_elem, expanded.slice_key, expanded.ord
  FROM up_process_instance pi
  CROSS JOIN LATERAL (
    SELECT '<bindingId>' AS slice_key, e.elem, e.ord
    FROM jsonb_array_elements(pi.variables->'__subTables__'->'<bindingId>')
         WITH ORDINALITY AS e(elem, ord)
    UNION ALL /* 其余 bindingId 同上 */
  ) expanded
  ORDER BY pi.id, <row_identity>, pi.start_time DESC NULLS LAST
) rows
WHERE <§6.1 可见范围> AND <列筛选>
ORDER BY <分组字段>, <排序字段>
LIMIT :size OFFSET :offset
```

**去重键里不含 slice key，这是实测定案的**（§12 第 6 项）：同一子表被绑进多张表单是常态
（`table_id=5` 有 5 个 binding），而实测 `pid=875491e6…` / `table=50326` 的 3 个 slice 里
装的是**同一个 `row_id`**。若把 slice key 放进身份，这一行会在列表里显示 3 次。

`total` 是同一内层加同样 `WHERE` 的 `COUNT(*)`，**不是** `up_process_instance` 的行数。

**注意这里没有 `COALESCE(..., '[]'::jsonb)`，而且不许加回去。** PR #107 写了这个默认值，它是多余的：
`jsonb_array_elements` 是 strict 函数，实测（PostgreSQL 16.5，dev 库）`NULL` 入参返回 **0 行**、
键不存在（`-> 'nope'`）同样返回 **0 行**，与 `'[]'` 完全等价。去掉它还有个好处——slice 存的不是数组
（例如被写成对象）时 Postgres 会直接 `ERROR: cannot extract elements from an object`（已实测），
数据结构坏了会当场炸；套上 `COALESCE` 反而容易让人误以为"取不到就当空"是被允许的。
"某个实例没填这张子表" 本来就该是 0 行，这是**正常的空集，不是缺失**，所以无需任何默认值。

两点实现注意：`WITH ORDINALITY` 取到的 `ord` 只作诊断用（抛错时定位是数组第几行），**不参与**行身份，
否则行序一变身份就变；`elem->>'row_id'` 出来是 text，身份键是数值而又要拿它当分页排序的稳定
tiebreak 时必须显式 cast，否则 `'10' < '9'`。

**分期：写入侧 → MAIN → SUB，三个提交。** 先补写入侧的行身份保证（否则 SUB 的抛错上线即 500，
见上文）；再 MAIN 视图，把 §6.1 的两级过滤、`COUNT`、筛选下推跑通；最后 SUB 视图，只增加
"展开 + 行身份 + 展开后 COUNT"。顺序不能换，每一步都只引入一件没验证过的事。

### 6.2 真分页

- `total` 必须来自 `COUNT(*)` 或聚合查询。**禁止**"拉一批行再 `.size()`"——`code-quality-standards.mdc`
§6.6 后端红线第一条，PR #107 的 `size(1000)` / `size(10_000)` 就是这个反例。
- 筛选 / 排序 / 分组任一变化 → 页码归 1 后再取数。
- 翻页请求必须携带当前全部筛选条件与排序（否则第 2 页是"无筛选的第 2 页"）。
- 每个列表要有**过期响应保护**：请求带单调递增序号，回来时序号过期就丢弃。PR #107 全库没有这层，
一次往返内连点两次列头，慢的先到会覆盖新的。
- `fetchLimit = (page + 1) * size` 这种"每页都从头拉"的写法不允许。
- **深分页不设页数上限，但必须可观测**（决策见 §12 第 1 项）。每个列表查询在 Component 层记耗时，
  **超过 1s 记一条 WARN**，字段固定为 `listKey` / `viewId` / `page` / `size` / `total` / `elapsedMs`，
  **不带行内容、不带筛选值**（避免 PII 进日志，`security-guard.mdc`）。
  这条日志是后续决定要不要加限制的唯一依据——没有它，"深分页到底有没有人用"只能靠猜。
  **看日志时先看 `total` 再看 `page`**：实测耗时与结果集大小绑定、在末页见顶，与页码本身无关
  （§12 第 1 项的五点实测）。实测第 1 页约 61ms、末页约 328ms，所以 1s 阈值只会命中真正大的结果集，
  不会把正常翻页刷成噪声。

### 6.3 筛选算子与分组能力随字段类型

一份声明派生三样东西（可用算子、排序白名单、分组白名单），前端只消费不猜。PR #107 的
`PortalListColumnMeta` 采用的正是这个思路（`field` / `kind` / `filterable` / `sortable` /
`groupable` / `operators` / `options`），**结构可以回收**，但要补类型、并重定分组默认值：


| 字段类型 kind                      | 可用算子                                                                   | 默认可分组 |
| ------------------------------ | ---------------------------------------------------------------------- | ----- |
| `TEXT`                         | contains, eq, ne, startsWith, endsWith, notContains, isNull, isNotNull | **否** |
| `ENUM`                         | eq, ne, isNull, isNotNull（**必须**下发 `options`，弹窗是封闭下拉）                    | **是** |
| `USER`                         | eq, ne, isNull, isNotNull（弹窗是按姓名/工号搜的人员选择器，不是把全公司当 `options` 下发）        | **是** |
| `DATETIME`                     | today…thisYear（先相对窗口、无日期选择器），再 on / before / after / between，以及 isNull / isNotNull（按日历日、`Asia/Shanghai`） | **否** |
| `NUMBER`**（PR #107 缺失，必须新增）**  | eq, ne, gt, gte, lt, lte, between, isNull, isNotNull                   | **否** |
| `BOOLEAN`                      | eq, ne, isNull, isNotNull（True / False 封闭下拉，与 ENUM 同一套）。**没值（格子里的 `-`）≠ False**；Not equals True 会带上空单元格，不等于选 False | **是** |


`NUMBER` 类型缺失就是 PR #107 里"数值列用 `gt`/`lt` 直接 500"的根因：前端敢发，后端的算子解析
对未知算子返回 null，一路走到 SQL 拼装才炸。

**未知算子的处理是硬规定：** 后端返回明确的 400 错误。**禁止**两种静默行为——前端"未知算子就当筛选不存在"
（等于展示全量却显示已筛选）和后端"未知算子返回 null"（等于 500）。

**封闭 kind 的 `options` 必须跟列声明一起下发。** `ENUM` / `BOOLEAN` 没有选项列表时，弹窗**抛错**，
不许退化成文本框。Views 把 `PortalListColumnMeta` 拷到 `MainTableViewFieldColumn` 时漏掉
`options` 就是这个契约的反例（Legal Hold / Status 会变成无法选值）。

每个可筛选 kind 都带 `isNull` / `isNotNull`（No data / Has data）。封闭选项列（ENUM / BOOLEAN / USER）
一律四则：Equals / Not equals / No data / Has data。BOOLEAN 不能只给 Equals：空单元格是 `-`，和
`false` 不是同一回事；Not equals 也不能省，因为它会带上空值，和选另一个选项不是同一筛选。

#### 6.3.1 分组能力按字段含义声明，不是每列都给

**分组不是通用列能力，它只对"取值是有限类别"的字段有意义。** 上表的"默认可分组"就是这条语义规则：

- `ENUM` / `BOOLEAN` / `USER` 默认可分组——取值来自封闭码表、真假、或人员集合，分组后每组有多行，
用户能真正靠它收敛信息。
- `TEXT` / `NUMBER` / `DATETIME` 默认**不可**分组——自由文本与连续值分组后基本"一行一组"，
分组头比数据还多；时间戳要按日 / 月分组是**另一种功能**（值需要先做变换），本期不做，
所以不是"暂时不给"，而是这个 kind 在当前语义下确实不该有分组入口。

**允许按字段显式覆盖，但必须显式。** 用 `PortalListColumnMeta.of(field, kind, filterable, sortable, groupable)` 逐列指定：某个 `TEXT` 列其实是类别码（如 `region_code`）→ 显式 `groupable = true`；
某个 `USER` 列基数极大（如"最后修改人"覆盖全公司）→ 显式 `false`。**禁止**为了省事把整类 kind
的默认值改成 true。

**这正是当前实现的缺陷所在。** `PortalListColumnMeta` 的五个工厂方法 `text()` / `user()` /
`datetime()` / `enumOf()` / `enumCodes()` 全部硬编码 `groupable = true`（§3），所以"每个列头都挂
分组入口"不是 UI 的问题，是列声明的默认值错了。该文件不在基线、属 PR #107 系列，重做时在
**阶段一提交 5** 按上表重定默认值：`text()` / `datetime()` 改为 `groupable = false`，
新增的 `number()` 同样 `false`，`enumOf()` / `enumCodes()` / `boolean()` / `user()` 保持 `true`。

**前端契约：`groupable = false` 的列，列头菜单里不出现分组项**——不是灰掉、不是点了报错，
而是根本不渲染。后端 `groupFields()` 白名单同时拒绝该字段的 `groupBy` 参数（返回 400，
与未知算子同一处理），防止有人绕过 UI 直接调接口。

#### 6.3.2 kind 权威：表存储类型 + 视图系统列，不是 Form 组件

设计师往视图加列，**不改代码、不把业务字段名写进白名单**。每次查询读当前视图的可见列，按下面的
优先级定 `kind`（实现：`MainTableViewColumnSpec` / `RelationTableColumnSpec`）：

1. **视图系统列**（只这四名，值在流程实例上，不在业务表字段里）

   | 字段 | 存哪 | kind | 弹窗 |
   |---|---|---|---|
   | `process_status` | `pi.status` | `ENUM` | Running / Completed / Withdrawn |
   | `start_time` | `pi.start_time` | `DATETIME` | 相对日期 + 日历 |
   | `initiator` | `start_user_name` / `start_user_id` | `USER` | 人员选择器 |
   | `current_step` | `pi.current_node`（当前 BPMN 节点名） | **`TEXT`** | 包含/等于。各 Function Unit 节点名不同，平台没有封闭步骤表，**不做下拉** |

   SUB 视图的行不是流程实例，但仍然**属于**一个实例。这四列在 SUB 上同样筛 `pi.*`（和 MAIN
   同一套 kind / 算子），**不是** `sub_elem` 里的成员，也不是 display-only。格子上的 Status /
   Start Time 来自那条流程实例，筛选必须跟格子一致。

2. **平台审计四名**（精确匹配，与 Form 画布解耦）：`created_at` / `updated_at` → `DATETIME`；
   `created_by` / `updated_by` → `USER`（按姓名或工号搜；单元格里常存显示名，同名两人在存储改成
   user id 之前无法在结果里拆开）。
3. **Table Design `data_type`**（`dw_field_definitions` / 关联表字段定义）。VARCHAR → 文本，
   DATE → 日期，BOOLEAN → True/False，以此类推。**列集合跟设计走。** 绑定表优先；SUB 视图
   若展示本 FU 其它表（通常是 MAIN）同名字段，用那张表的类型补上。名字在本 FU 任何表上都
   没有 → display-only，**禁止**按列名 `date` / `user` 猜 DATETIME / USER。

**禁止**用「该字段在某张表单里用了什么组件」来定筛选类型。同一字段可绑多张表单、控件可以不同，
Views 展示的是表列（可以完全不出现在任何表单上）。

Function Unit 的 Table Design **没有 LOOKUP / CHOICE 类型**。`select` / `radio` / `lookup` /
`user` / `owner` 落库都是 VARCHAR，因此：

- 业务 Choice（表单静态选项）→ 现在是文本筛。要做成封闭下拉，选项必须落到**表字段定义**上，本期不做。
- 业务 Lookup（存对端主键）→ 源字段按文本比 id；设计师在视图上加的 **lookup 显示列** 才按看到的
  名字筛（有存储键映射才能筛，没有就只展示）。关联表字段类型可以是真 `LOOKUP`，列表仍按存的主键当文本。

扫表单 JSON 里的 `type:"lookup"` 只用于显示列 hydrate / 反查存储键，**不是**筛选 kind 的来源。

**`FILE` 列（当前 shared-list 基线）：只展示，不筛选、不排序。** Table Design
`data_type = FILE` → `displayOnly`。禁止把 FILE 当普通 `TEXT` 打开 Contains（会比到 URL，
与格子文件名不一致）。按文件名筛的产品方案见专文
[list-file-name-filter.md](./list-file-name-filter.md)；**未合入前**列头行为维持本段。

#### 6.3.3 排序：按 kind 比大小，不是一律按字母

列头排序和 SQL 必须用**同一套 kind**，禁止前端按字段名猜、也禁止后端把 JSON 文本拿来排数字。

| kind | 菜单文案（ASC / DESC） | SQL |
|---|---|---|
| `TEXT` / `ENUM` / `USER` / `BOOLEAN` | A to Z / Z to A | 存什么比什么（字典序）。BOOLEAN 存 `true`/`false` 文本，ASC 是 False 在 True 前 |
| `NUMBER` | Small to large / Large to small | 有守卫的 `::numeric` 转换：`9` 在 `10` 前。非数字当 null，**不让整页查询失败** |
| `DATETIME` | Older to newer / Newer to older | 按存储的时间字符串比；审计时间与 `start_time` 是 ISO / timestamp text，字典序 ≈ 时间序。筛选按日历日（`left(..., 10)` + `Asia/Shanghai`），排序仍用完整值，同一天内有先后 |

其它约定：

- 用户点的排序 **NULLS LAST**，稳定次序靠 `pi.id`（或 SUB 的行身份）收尾。
- 设计师给视图配的默认排序：升序 **NULLS FIRST**、降序 **NULLS LAST**（对齐搬 SQL 之前内存比较器的空值位置，避免整页被重排）。用户再点列头后走上面的 NULLS LAST。
- lookup / FK **显示列不可排序**（`displayMapped`）：格子上是名字，库里是主键，按名字排会和显示对不上。
- `sortable = false` 的列菜单里不出现排序项。

前端文案由 `sortLabelKeys(kind)` 一处决定（`listHeaderMenu.ts`），列头图标 tooltip 与下拉菜单必须走同一函数。

### 6.4 分组的渲染与计数

前提：该列 `groupable = true`（§6.3.1）。以下只讲已经可分组的列怎么渲染。

- **label 只能由一边产生，且定死是后端。** 后端返回 `{label, count}` 数组，前端**直接渲染后端的 label**，
不再自己按显示值算一遍。不留"或者后端只返回分组键、前端自己配 label"这条备选路径——两条路等于两个
决策点（`error-handling-governance.mdc` 红线 3）。PR #107 的 lookup 列分组显示 `(0)`，正是因为
后端按原始值分组、前端按 hydrate 后的显示值取计数，再 `?? 0` 兜住了对不上的部分。
- `count` 来自 `COUNT(*) ... GROUP BY`，不是前端数组长度。
- 分组字段的排序必须**排在**业务排序之前（`groupBy ASC NULLS LAST` 在前），否则同组行不连续，
会渲染出重复的分组头。
- **计数缺失不允许有"显示形态"。** `label` 与 `count` 出自同一次 `GROUP BY`，成对返回；前端拿到
分组项却没有 `count`，说明后端契约坏了 → 前端**抛错**（开发期立刻暴露），不要渲染 `—`、不要渲染 `0`、
也不要跳过该分组头。渲染替代符号就是兜底，它把后端缺陷变成了用户以为正常的界面。

### 6.5 Relation Tables 接入（含内置 User 虚拟表）

Portal「Relation Tables」是共享列表的第二个消费者（Views 之后）。业务表与内置 User 共用列头 /
筛选 / 分页，但**数据落点不同**，列声明与分组策略也不同。

#### 6.5.1 业务 Relation：JSON 行，无每表物理表

| 用途 | 物理表 |
|------|--------|
| 表 / 字段元数据 | `rt_table_definitions` / `rt_field_definitions` |
| 行数据 | `rt_table_data_rows`（`data` JSONB）；**不为每张业务表再建物理表** |
| 行级 Active/Inactive | `rt_table_data_rows.status`（toggle，**不是**列表数据列） |

列表列声明：`RelationTableColumnSpec` 从 `rt_field_definitions.data_type` → kind（BOOLEAN /
NUMBER / DATETIME / TEXT…；LOOKUP 按存的主键当 TEXT）。**一律 `groupable = false`**——查询端点
没有 GROUP BY / 分组计数，声明不能承诺做不到的能力。VARCHAR 实为码表若要 ENUM，须在字段定义
显式带 `options`；本期不扫全库推断。

筛选 SQL：`ListFilterSql` + `JSON_ROW`（`data->>'field'`）。切表必须重置筛选 / 排序 / 搜索 /
页码（§6.2）。

#### 6.5.2 内置 User = `sys_users` 只读投影

左侧「User」**不是** `rt_*` 业务表：tableId 固定 `-1000000001`，任意登录用户可读、永远
`READONLY`，查询直连 `sys_users`。字段声明在
`PortalRelationTableServiceImpl.systemUserColumns()`（无 `rt_field_definitions`）。

| 列 | kind | 说明 |
|----|------|------|
| `status` | `ENUM` + options | `ACTIVE` / `INACTIVE` / `DISABLED` / `LOCKED` / `PENDING`（对齐 CHECK） |
| `language` | `ENUM` + options | `en` / `zh_CN` / `zh-CN` / `zh_TW` / `zh-TW`（兼容 underscore / hyphen 存值） |
| 其余 | `TEXT` | id / username / display_name / full_name / email / employee_id |

封闭列筛法为 eq / ne / isNull / isNotNull，**禁止**再当 TEXT 用 contains。  
**全部 `groupable = false`**（含 ENUM）：User 用途是检索 / LOOKUP / 导出，不是按状态分析；且 RT
端点尚无 GROUP BY。勿直接调用 `PortalListColumnMeta.withOptions`（其默认 `groupable = true`），
须规范构造器显式传入 `false`。

筛选 SQL：`ListFilterSql` + `PHYSICAL_COLUMN`；WHERE 须以 `WHERE 1=1`（或等价）起头，再拼
`AND …`，避免 `FROM sys_users AND col …`。

**本轮非目标：** 业务表全量 VARCHAR→ENUM；User/RT 分组 UI；洗 `zh_CN`/`zh-CN` 存值。

**验收正例：** User 的 status/language 筛选为下拉；列头无分组项。  
**反例：** status/language 出现 contains；只改 `groupable=true` 未实现 GROUP BY；把 User 行写入
`rt_table_data_rows`。

**实现收尾（与 Views 同范式）：** 列元数据随 `POST …/data` 的 page 返回（不另开 `/columns`）；
请求体非空 `groupBy` → 400；查询耗时 >1s 打 WARN（`listKey` / `tableId` / `page` / `size` /
`total` / `elapsedMs`，不含筛选值与行内容）。

**验证截图（PNG gitignore，PR 描述写绝对路径）：**

- `frontend/user-portal/verification-screenshots/2026-08-20_list-relation-tables-shared.png`
- `frontend/user-portal/verification-screenshots/2026-08-20_list-relation-user-after-enum.png`
- `frontend/user-portal/verification-screenshots/2026-08-20_list-relation-user-status-enum-filter.png`


## 7. 影响面


| 层级           | 变更                                                                                                                    |
| ------------ | --------------------------------------------------------------------------------------------------------------------- |
| 前端共享         | 新增 `frontend/shared/src/list/`（4 组件 + 光标 util + scss + 类型）；UP `mainTableView` 那两个文件搬进共享层后删除                           |
| 前端各 app      | **只有 user-portal 与 admin-center**：每个接入的菜单替换列头/分页、新增该菜单的列状态 composable。`frontend/developer-workstation/`** 零 diff      |
| 后端 API       | 每个接入的列表：新增 `/columns` 列元数据端点；列表端点新增 page/size/sort/filters/groupBy 参数（**只增不改**，旧参数保持兼容）                               |
| 后端 Component | 列声明（kind + 算子）单点定义；查询下推；`COUNT` 与分组计数                                                                                 |
| Entity / SQL | **无 schema 变更**（§6.1 定稿不新增表）。索引按**实际生成的 SQL 表达式**建——PR #107 建的 `jsonb_path_ops` GIN 与它自己生成的 `::text ILIKE` 查询不匹配，等于没建 |
| i18n         | 列头菜单、筛选算子、分页文案：`en` / `zh-CN` / `zh-TW` 三语同步。PR #107 的 22 个 key 三语齐全，**可直接回收**                                        |
| 部署           | 无新环境变量；前端产物进镜像后按 `debug-mode-docker-workflow.mdc` 重建对应 `*-frontend` 服务                                                |


## 8. 分期

### 阶段一：建共享组件，一个业务菜单都不接（5 个提交）

**基本原则：只动 UP 与共享层，DW 一行不改。** 列宽拖拽从 UP 搬（搬完删 UP 原文件，净重复不增加）；
列头与筛选弹窗新写，但**照 DW `designer-list` 的结构写**（只读参考，见 §3）；分页无参照物，纯新增。


| #   | 内容                                                                                                                                                                                                                                                |
| --- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | UP 的 `MainTableViewColumnResizeHandle.vue` + `mainTableViewColumnResizeCursor.ts` / `.scss` 搬进 `frontend/shared/src/list/`，改名 `ColumnResizeHandle`，`@/` 导入改相对路径，补 `onBeforeUnmount` 清理。**验证 `.vue` 能过 UP + AD 的 build + typecheck**（这是本方案唯一的未知链路） |
| 2   | UP 切到共享 `ColumnResizeHandle`，删掉 UP 原文件；body class 改中性名（`is-column-resizing`），只改 UP 一侧。**UP 截一张拖拽图**                                                                                                                                               |
| 3   | 新写共享 `ListColumnHeader` + `ListFilterDialog`（先读 DW 那两个文件定结构），只带单测，不接任何页面                                                                                                                                                                          |
| 4   | 新写 `ListPagination`，只带单测，不接任何页面                                                                                                                                                                                                                   |
| 5   | 算子矩阵 + 分组能力单点声明（后端 kind → 算子 / 默认 `groupable`，前端共享类型）：补 `NUMBER` / `BOOLEAN`，并把 `text()` / `datetime()` / `number()` 的 `groupable` 默认值定为 `false`（§6.3.1）                                                                                          |


提交 1 是"同一份代码换个位置 + 补一段清理"，UP 行为必须零变化，所以适合打头验证共享目录放 `.vue` 这件事；
提交 2 才第一次让 UP 用上共享件并删掉旧文件。提交 3 / 4 是纯新增，不接页面，因此不会影响任何现有视图。
每个提交都要确认 `git diff --stat frontend/developer-workstation` 为空。

### 阶段二：在 user-portal 打磨接入范式（3 个菜单；前两个各一提交，第三个拆三提交）

顺序：`已办任务`（只读、字段简单）→ `关联表数据`（有切表换列的状态问题，正好验证 §6.2 的状态一致性；
内置 User 与 RT 列声明见 **§6.5**）
→ 一个带行可见范围的列表（正式跑通 §6.1 的失败测试 → 实现）。

最后这个带行可见范围的列表如果是 Main Table View，**再拆成三个提交**，顺序不能换（§6.1.1）：

1. **写入侧行身份保证**：任何写进 `__subTables__` 的行都必须带 `row_id`；对存量数据做一次巡检 /
  回填（dev 库已确认存在无身份键的真实数据行）。不先做这步，第 3 步的抛错就是上线即 500。
2. **MAIN 视图**：两级过滤 + `COUNT` + 筛选下推。
3. **SUB 视图**：lateral 展开 + 行身份去重 + 展开后 `COUNT`。

范式定型后再横向铺开——**不要**在范式定型前就铺开，否则等于把 PR #107 的错误按 app 数量放大。

### 阶段三：按菜单铺开（每菜单一提交）

**先把 user-portal 剩余菜单铺完，再转 admin-center**（已定）。理由是阶段二的范式就在 UP 打磨的，
趁上下文还热把同一 app 收口，比切到 AD 再切回来省事；AD 多为简单 CRUD 列表，晚做风险也低。
每个提交回答 §6 的四问。**developer-workstation 不在本期**——它的 `designer-list` 保持原样，
接入与两套实现合并另起任务（ISSUE-1522）。

### 每个提交的固定收尾（四件事，不用每次重想）

1. 改到的模块跑真实构建：`cd frontend/<app> && pnpm run build`；后端 `mvn -pl backend/<module> -am package -DskipTests`
2. 有 UI 变化 → 重建对应 `*-frontend` compose 服务 + 截图存 `verification-screenshots/` 并在提交里写绝对路径
3. 触达 MI 热路径 → `cd frontend && pnpm run regression:mi`
4. 三项都过再 commit；一步一提交，不攒

## 9. 风险与回滚


| 风险                           | 应对                                                                                                                                                                                                                              |
| ---------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 同一批缺陷复发                      | §10 的三条反例先写成失败测试，阶段二第一件事就是让它们红→绿                                                                                                                                                                                                |
| 共享组件被某个 app 的特殊需求带偏          | 组件不认识业务字段（§5）；特殊需求放各 app 的 composable，不进共享层                                                                                                                                                                                     |
| `.vue` 放共享目录踩构建坑             | 阶段一第 1 个提交单独验证（UP + AD build + typecheck）。**失败就停下报告，不设退路**——"退回三端各自薄壳组件"那种 plan B 等于给自己留兜底，会让共享层名存实亡                                                                                                                            |
| 接入过程中列表短暂不可用                 | 每个菜单一个提交且自带截图；回滚粒度 = 单个菜单单个提交                                                                                                                                                                                                   |
| 巨型视图文件越接越大                   | 接入前先拆（`permissions/index.vue` 按 tab 拆子组件），拆与接分两个提交                                                                                                                                                                              |
| 手滑改到 DW                      | 每个提交前跑 `git diff --stat frontend/developer-workstation backend/developer-workstation`，非空即停。DW 的 `designer-list` 在 8 个视图里用，回归面不小，本期完全不碰是最省事的隔离                                                                                   |
| 共享层与 DW `designer-list` 长期并存 | **本期明确接受的重复**，但必须备案而不是忘掉：`.kiro/issues/index.yaml` 记一条 `open`，内容 = DW 接入共享 list 组件并删除 `designer-list`。将来接入时按 `error-handling-governance.mdc` 红线 4 逐项决策差异（DW 多的 `@click.stop`、两边不同的 body class、都缺的 `onBeforeUnmount`），不许随手挑一份当基准 |


## 10. 验收

**反例（当前 / PR #107 的行为，必须消失）**

1. 非参与用户查询启用了「仅参与用户可见」的 Main Table View → 只要其 userId 作为子串出现在
  `__subTables__` 任何位置（含他人更长的 id、用户手填文本），该行仍可见。
2. 在关联表 A 上设筛选 `name contains foo`，切到关联表 B → B 的请求携带 A 的筛选与排序；
  随后列头筛选图标全部消失，但网格显示的是被筛过的子集。
3. 按 lookup 列分组 Main Table View → 分组头显示 `某某 (0)`，其下却有若干行。
4. 数值列使用 `gt` / `lt` 筛选 → 500。
5. SUB 视图里两条内容相同、都没有身份键的子表行 → 被 `md5(elem::text)` 并成一行，`total` 少 1，无任何提示。
  （实测 dev 库 27 行中 26 行的身份键是 `row_id`、`id` / `id_idw` 一次未出现，所以 PR #107 的
   `COALESCE(elem->>'id', elem->>'id_idw', md5(...))` 是 100% 走 md5 分支。）
6. 每一个列头都挂着分组入口，包括自由文本、金额、时间戳列 → 按它们分组等于一行一组。

**正例（期望）**

1. 同上场景，非参与用户查询结果**不含**该行；参与用户（发起人 / 历史办理人 / 真实 MI 参与人）
  结果**包含**该行；`SYS_ADMIN` 按 `view-access-control` 既有语义可见全部。
2. 切表后：新表的请求**不带**旧表筛选；列头状态与实际查询条件一致（要么都是新表的持久化筛选，要么都为空）。
3. 分组头计数等于该组实际行数，且 label 与 count 都来自后端同一次 `GROUP BY`；
  后端漏给 `count` 时前端**报错**，不渲染 `—` 也不渲染 `0`。
4. 数值列可用 `gt` / `lt` / `between`；未知算子返回 400 并有明确 message。
5. 粗筛结果 ⊇ 精确结果（§6.1 的超集不变式）；两级过滤后的可见集**逐行等于**直接跑
  `MainTableViewInvolvementChecker` 的结果。
6. 全期任一提交上 `git diff --stat frontend/developer-workstation backend/developer-workstation`
  输出为空——DW 一行未改，因此那 8 个使用 `designer-list` 的视图**无需回归、无需截图**。
7. SUB 视图的行身份取自共享的 `ROW_IDENTITY_FIELDS` 优先级（`row_id` 优先），不再写死
  `id` / `id_idw`；没有身份键的行**报错**（message 含 `viewId` / `processInstanceId` / slice key，
   不含行内容），不再静默合并。同一视图分页前后 `total` 与逐页行数之和一致。
8. 同一子表被绑进多张表单时，同一 `row_id` 在列表里只出现**一次**（实测 `pid=875491e6…` /
  `table=50326` 的 3 个 slice 装同一行 → 列表显示 1 行，不是 3 行）。
9. 分组入口只出现在 `groupable = true` 的列头上：`ENUM` / `USER` / `BOOLEAN` 有，
   自由文本 / 金额 / 时间戳没有（不是灰掉，是不渲染）；对 `groupable = false` 的字段直接调接口
   传 `groupBy` 返回 400。
10. 深分页可观测：翻到深页（耗时 >1s）时日志有一条 WARN，含 `listKey` / `viewId` / `page` /
    `size` / `total` / `elapsedMs`，**不含行内容与筛选值**；正常翻页（第 1 页约 65ms）不产生该日志。

**taskId / applicationId / FU：** [待确认]——阶段二接入带行可见范围的列表时补上具体验证数据。

## 11. 验证（实现后最低命令）

```bash
# 每个提交都先确认没碰 DW
git diff --stat frontend/developer-workstation backend/developer-workstation   # 期望：空

# 前端（本期只有这两个 app）
cd frontend/user-portal && pnpm run build
cd frontend/admin-center && pnpm run build

# 阶段一第 1 个提交必跑：两端类型检查，验证共享目录里的 .vue 能被解析
cd frontend/user-portal && pnpm run typecheck
cd frontend/admin-center && pnpm run typecheck

# 共享组件单测
cd frontend/user-portal && pnpm run test

# 触达 MI 热路径时
cd frontend && pnpm run regression:mi

# 后端（按接入的模块选）
mvn -pl backend/user-portal -am package -DskipTests
mvn -pl backend/admin-center -am package -DskipTests

# 重建并看日志
cd deploy/environments/dev
docker compose -f docker-compose.dev.yml --env-file .env up -d --build <service>
docker compose -f docker-compose.dev.yml --env-file .env logs --tail=200 <service>
```

UI 截图存 `frontend/<app>/verification-screenshots/`，提交与 PR 描述里写绝对路径。

## 12. 决策记录（原「待确认」6 项，已全部关闭）

> 已定：**行可见范围沿用现有权限语义 + 两级过滤，不新增表、无阈值兜底**（§6.1）；
> **SUB 行身份取自共享 `ROW_IDENTITY_FIELDS`（`row_id` 优先），无身份键抛错不静默合并，
> 写入侧 → MAIN → SUB 三步走**（§6.1.1）；**分组能力按字段语义声明，不是每列都给**（§6.3.1）；
> **本期不改 DW 任何文件**，共享组件只由 UP + AD 消费，列宽拖拽从 UP 搬、列头与筛选弹窗新写（§3、§8）；
> **全程零兜底**，所有"取不到"一律显式失败（头部硬约束）。

**6 项全部关闭（2026-08-17）**：1 定为不设上限 + 慢查询日志、2 被 §6.3.1 取代、3 沿用既有
`sessionStorage` 规则、4 定为先 UP 后 AD、5 转 ISSUE-1522、6 由实测定案。**方案可执行。**
下面保留每一项的决策依据，便于日后回看"为什么这么定"。

1. **深分页策略** → **已定：不设页数上限，改为加慢查询日志（>1s 记 WARN），按生产真实分布再决定。**
   落地要求见 §6.2 最后一条。

   决策依据：**不设上限不会算错，也不违反任何红线，只是深页慢。** 下面是实测（2026-08-17，
   dev 库 PostgreSQL 16.5），查询形状与 §6.1.1 的 SUB SQL 一致（真实 `__subTables__` payload +
   `jsonb` 展开 + `DISTINCT ON` + 外层 offset），用 `generate_series(1,3000)` 把实例放大到
   约 81,000 展开行：

   该集合展开后 81,000 行、去重后 **12,000 行**（即结果集共 600 页 × 20）：

   | offset | 返回行数 | 执行时间 |
   | --- | --- | --- |
   | 0（第 1 页） | 20 | 61 ms |
   | 2000 | 20 | 89 ms |
   | 6000 | 20 | 276 ms |
   | 11000（末页附近） | 20 | 328 ms |
   | 40000（越过结果集尾部） | **0** | 387 ms |

   **每一页都只返回 20 行，但耗时差 5 倍以上**；offset 4 万时返回 0 行仍花 387ms。原因是
   `OFFSET N` 的语义不是"跳到第 N+1 行"，而是"算出前 N 行再丢掉"——数据库无法跳过，
   因为要知道谁排第 N+1 名，必须先把前 N 名排出来。执行计划直接可见：

   ```
   Limit            (actual rows=0)       ← 返回 0 行
     -> Unique      (actual rows=12000)   ← 仍产出了整个结果集
        -> Incremental Sort (actual rows=81000)
   ```

   第 1 页便宜是因为 `LIMIT 20 OFFSET 0` 允许**提前终止**（排够 20 个唯一值就停），
   深 offset 不能提前终止。这才是 5 倍差距的来源，与"传了多少数据"无关。

   **成本在结果集末端见顶，不随页码无限增长**：offset 11000（末页）328ms 与 offset 40000
   （越界）387ms 基本相同，天花板就是"把整个结果集算完"的成本。
   〔**更正**：本文档上一版写"按线性外推 offset 10 万约 1.1–1.3s"是错的——线性只在结果集内成立，
   越过尾部就平了。〕

   **所以耗时与"结果集有多大"绑定，不与"页码有多大"绑定，拦页码等于拦错对象**——这正是本项
   最终定为不设上限的原因。要观测就观测 `total`：慢查询日志里 `total` 比 `page` 更能预测耗时。

   **测量的诚实边界**：实例是同一批真实数据复制 3000 份，dev 库只有 27 行子表数据、payload 小且重复，
   真实 payload 更大会更慢；这是数量级参考，不是 benchmark。另外一次"把实例级筛选下推进内层"的
   对比测试**无效**（用的 `pi.id::text LIKE '8%'` 谓词本身不可走索引，反而更慢 250ms），
   所以本文**不主张**下推有收益，那需要另做实验。

   不选 keyset 的理由：它要求排序键唯一稳定（SUB 的身份键是 JSON 取出的 text，要额外保证），
   换排序字段就要换一套游标，`total` 仍需单独 `COUNT`，而且**不能跳页**——现有 UI 是
   `el-pagination` 页码条，改 keyset 等于同时改交互形态。**若日后日志显示深分页确实是热路径，
   优先补上限，其次才考虑 keyset + 改交互。**
2. ~~AD 是否也需要分组~~ → **已关闭**。这个问题本身问错了：分组不是"某个 app 要不要"，
  而是**每列按字段语义声明**（§6.3.1）。AD 的列表里有 `ENUM` / `USER` 列就自动有分组，
   全是自由文本就自动没有，无需为 AD 单独决策。
3. ~~列状态存储范围~~ → **已定：沿用 `sessionStorage`**，不改现有行为。这不是新拍的规则——
   UP 基线 `mainTableViewGridRuntime.ts`（key `portal-mtv-runtime:${viewId}`）与 DW 基线
   `useDesignerListGrid.ts` 都已在用（§3）。落后端偏好表的先例是 DW `useLaunchpadLayout.ts`，
   适用于"要跟人跨设备"的状态，列宽 / 临时筛选不属于这类。
4. ~~接入顺序~~ → **已定：先 user-portal 剩余菜单，再 admin-center**（§8 阶段三）。
5. ~~DW 何时接入并合并两套实现~~ → **已关闭**，转为 ISSUE-1522（`open`）跟踪，不占本文档的待确认位。
6. ~~同一身份键跨 slice 算一行还是两行~~ → **已关闭，实测定案为一行**。
  `dw_form_table_bindings` 里 `table_id=5` 有 5 个 binding，多 binding 是常态；实测
   `pid=875491e6…` / `table=50326` 的 3 个 slice 装的是同一个 `row_id`，所以去重键**不含**
   slice key，否则该行会显示 3 次（§6.1.1）。

---

请确认以上设计 Plan。确认后若要开始实现，请回复 **按 playbook 执行**（或补充 / 修正项）。