# 跨端列表共享组件与服务端分页接入规范

> **状态：方案已定稿；Views / Relation Tables / 已办任务 / My Requests / To Do / Delegations（规则+审计）/ Permissions（申请+审批）/ Portal Audit / 成员管理已接入。**
> §12 原 6 项待确认**全部关闭**；第 7 项（列宽 fill，§6.6）与第 8 项（侧栏 list 必须接共享套件，§6.7）2026-08-27 追加、2026-08-28 fill 修订。§6.6 **已落地**（共享 `useListColumnLayout` + 宽屏 fill；DW 仍不改）。
> To Do：引擎 `or()` 真分页 + 去重 COUNT；可 push 列（taskName/步骤名、流程名、priority 档、
> createTime/dueDate）走 window；initiatorName / assignmentType 仍 portal fullScan
> （fullScan 已改为引擎真 OFFSET，不再 `(page+1)*size` 前缀重扫）。
> **默认不再把 standing-rule Proxy 并进 To Do**（Proxy 仍走 Delegations / assignmentTypes=DELEGATED）。
> Delegations：My Rules + Audit + 委托任务 Tab 共享列表；委托任务走 `assignmentTypes=DELEGATED`（与 To Do 筛选同一查询）。Tab i18n key 仍为 `delegation.proxyTasks`，文案为「委托任务 / Delegated」。
> 投影表仍暂缓。
> 关键决策：§6.1（行可见范围两级过滤）、§6.1.1（SUB 行身份取 `row_id` 优先，写入侧→MAIN→SUB
> 三步走）、§6.2（深分页不设上限，改慢查询日志）、§6.3.1（共享表头**不再提供 Group 菜单**）、
> **§6.3.2（筛选 kind 的权威是表 `data_type` / 视图系统列，不是 Form 组件；`FILE` 按抽出的文件名筛，禁止当 TEXT 比 URL）**、
> **§6.3.3（排序按 kind：字母 / 数值大小 / 新旧，数字不得按文本排）**、
> **§6.5（Relation Tables：业务表 JSON 行；内置 User=`sys_users`；类型化筛选；列头无 Group）**、
> **§6.6（列宽：默认 = 表头实测与 kind 内容下限取大；chrome 紧凑；宽屏按底宽比例 fill；用户可拖窄到 60px；溢出时 Action `fixed="right"` 钉窗口右沿；2026-08-28 fill 修订）**、
> **§6.7（Portal/Admin 左侧菜单 list view 必须接共享列表套件：表头 + 按 kind 筛选排序 + 可调列宽 + 共享分页；2026-08-27 定稿）**、
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
> 缺行身份 → 抛错（§6.1.1）；未知算子 → 400（§6.3）；
> 共享目录构建失败 → 停下报告，不设退路（§9）。
> - SUB 视图的行身份**只能**由共享的 `ROW_IDENTITY_FIELDS` 优先级取（`row_id` 优先）；**禁止**
> `md5(elem::text)` / `hashCode()` 一类内容兜底，禁止写死 `elem->>'id'`，也**不要**走物理表
> PK 元数据（业务子表没有物理表，那条路必然取空），见 §6.1.1。
> - **共享表头不再提供 Group 菜单。** 列声明没有 `groupable`；请求没有 `groupBy`；
> 响应没有 `groups`；禁止再加分组行 / 私有分组菜单，见 §6.3.1。 Virtual Group、侧栏按表折叠、
> Filter Group、关联表 FU 分组与本能力无关，不要一起删。
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
> - **列宽 / 视口余量只按 §6.6：** 禁止再手调各页 `COL_WIDTHS` 当默认宽；禁止 Action 前
> `list-col-spacer`；禁止只把余量塞进最后一列；禁止靠解开 Action 的 `fixed="right"` 消溢出横滑时的按钮。
> 测宽与 fill 落在 `frontend/shared/src/list/`，宿主 composable 只持有底宽并 persist session。
> - **Portal / Admin 新左侧菜单若是记录列表，必须接 §6.7 全套**，禁止自建 `el-table` 表头 +
> 裸 `el-pagination`。侧栏是 Layout 手写的，加菜单 ≠ 自动用上共享件。DW 仍除外。
>
> **复盘对象：** PR #107（121 文件 / +14143 −1935，未合并，已弃）。该 PR 内的
> `docs/design/portal-main-table-view-query.md` 随分支一起弃用，其 involvement 章节记录的是
> **被否决**的方案（§6.1 引原文）。

相关文档：

- View 行可见性管控：skill `view-access-control`（本文的 §6.1 不得与之冲突）
- 门户身份来源：[portal-bu-rbac.md](./portal-bu-rbac.md)（BU + Role 决定可见范围）
- 长度 / 分层 / UX / 性能红线：`.cursor/rules/code-quality-standards.mdc`
- FILE 列按文件名筛（已实现）：[list-file-name-filter.md](./list-file-name-filter.md)
- Relation Tables / 内置 User：**正文 §6.5**（不再单开文档）
- 列宽默认值与视口余量：**正文 §6.6**（不再单开文档）
- 左侧菜单必须接共享列表：**正文 §6.7**（不再单开文档）；规则 `.cursor/rules/shared-list-portal-admin.mdc`
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
3. 每个接入点同时满足四条（§6）：不越权、真服务端分页、筛选算子随字段类型、列头无 Group 菜单。
4. **全程零兜底**：所有"取不到"的情况都显式失败，不给默认值、不静默降级（见头部硬约束）。

**非目标（本期明确不做）**

- **developer-workstation 的任何改动**：DW 不接入共享组件，`designer-list` 保持原样，
连改成 re-export 都不做。因此 DW 那套与共享层会短期并存，这是**明确接受的重复**，
已按 `issue-radar` 备案（§9），等 DW 接入时再合并。
- 虚拟滚动、用户可配置的列固定 / 列显隐；列状态持久化仍用 `sessionStorage`，不落后端。
  Action 列维持现有 `fixed="right"`（§6.6），不是新做「钉列」功能。
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
| `PortalListColumnMeta` 曾有 `filterable` / `sortable` / `groupable` 三个开关 | PR #107 五个工厂方法全部硬编码 `groupable = true`，效果是每列都能分组。**现契约已删 `groupable`**，共享表头不再提供 Group 菜单（§6.3.1）                                                                                              |
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
**不认识任何业务字段名**。列状态（顺序 / 宽度 / 排序 / 筛选）由**各 app 自己的 composable**
持有并持久化；组件只接收 props、抛出事件。这样同一个组件才能同时服务 UP 的流程列表和 AD 的用户列表。

**命名必须中立。** 本期只有 UP 与 AD 消费，但名字里仍不能出现 `MainTableView` / `Portal` /
`Designer`，CSS 与 body class 也不能沿用 `mtv-` / `dwl-` 前缀（统一成语义化的 `is-column-resizing` 之类）——
将来 DW 接入时要能直接用同一份，不能因为名字带 `Portal` 而被迫再改一轮共享层。
body class 改名只动 **UP 一侧**（DW 的 `dwl-column-resizing` 原样保留），是**全局副作用**，
要同步 UP 的 scss 与调用点，单独一个提交并在 UP 截一张拖拽图——那个自定义深色光标 SVG
是为了避免系统光标在浅色表头上发白，回归了不容易发现。


| 组件                   | 输入（props）                                                                     | 输出（emits）                                                                               | 不负责                                                                             |
| -------------------- | ----------------------------------------------------------------------------- | --------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------- |
| `ListColumnHeader`   | 列定义（字段、标题、类型 kind、`filterable` / `sortable`、可选项）、当前排序、当前筛选 | `sort-change`、`filter-change`、`clear-sort`、`clear-filter`、`width-change` | 取数、决定算子有哪些（由 props 传入）、持久化。**菜单没有 Group 项**（§6.3.1）      |
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
ORDER BY <排序字段>
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
ORDER BY <排序字段>
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
- 筛选 / 排序任一变化 → 页码归 1 后再取数。
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

### 6.3 筛选算子随字段类型

一份声明派生两样东西（可用算子、排序白名单），前端只消费不猜。PR #107 的
`PortalListColumnMeta` 采用的正是这个思路（`field` / `kind` / `filterable` / `sortable` /
`operators` / `options`），**结构可以回收**，但要补类型；**不要**回收 `groupable` / `groupBy`：


| 字段类型 kind                      | 可用算子                                                                   |
| ------------------------------ | ---------------------------------------------------------------------- |
| `TEXT`                         | contains, eq, ne, startsWith, endsWith, notContains, isNull, isNotNull |
| `ENUM`                         | eq, ne, isNull, isNotNull（**必须**下发 `options`，弹窗是封闭下拉）                    |
| `USER`                         | eq, ne, contains, notContains, isNotNull, isNull（弹窗仍是人员选择器；contains = 选中的人出现在单元格的逗号分隔身份里，**不是**按姓名片段自由输入。Current Assignee 还要打 `current_assignee` **和** `candidate_users`；存的是 `buCode:roleCode` 拼接时，按该用户是否持有这对 BU+Role 命中） |
| `DATETIME`                     | today…thisYear（先相对窗口、无日期选择器），再 on / before / after / between，以及 isNull / isNotNull（按日历日、`Asia/Shanghai`） |
| `NUMBER`**（PR #107 缺失，必须新增）**  | eq, ne, gt, gte, lt, lte, between, isNull, isNotNull                   |
| `BOOLEAN`                      | eq, ne, isNull, isNotNull（True / False 封闭下拉，与 ENUM 同一套）。**没值（格子里的 `-`）≠ False**；Not equals True 会带上空单元格，不等于选 False |


`NUMBER` 类型缺失就是 PR #107 里"数值列用 `gt`/`lt` 直接 500"的根因：前端敢发，后端的算子解析
对未知算子返回 null，一路走到 SQL 拼装才炸。

**未知算子的处理是硬规定：** 后端返回明确的 400 错误。**禁止**两种静默行为——前端"未知算子就当筛选不存在"
（等于展示全量却显示已筛选）和后端"未知算子返回 null"（等于 500）。

**封闭 kind 的 `options` 必须跟列声明一起下发。** `ENUM` / `BOOLEAN` 没有选项列表时，弹窗**抛错**，
不许退化成文本框。Views 把 `PortalListColumnMeta` 拷到 `MainTableViewFieldColumn` 时漏掉
`options` 就是这个契约的反例（Legal Hold / Status 会变成无法选值）。

每个可筛选 kind 都带 `isNull` / `isNotNull`（有值 / 没值）。封闭选项列（ENUM / BOOLEAN）
一律四则：Equals / Not equals / 没值 / 有值。USER 在此之上加 contains / notContains（人员选择器
仍必选一个人；逗号分隔的多人单元格「包含张三」能命中，「等于张三」不能）。BOOLEAN 不能只给
Equals：空单元格是 `-`，和 `false` 不是同一回事；Not equals 也不能省，因为它会带上空值，和选另一个选项不是同一筛选。

#### 6.3.1 共享表头不再提供 Group 菜单

共享列表的列头菜单只有排序、筛选、清排序/清筛选、以及可选的列左右移动。**没有 Group / Ungroup。**

这不是「部分列暂时不给」，而是产品决定：共享列表不做按列分组。实现上必须同时拿掉整条链路，禁止只藏菜单：

- 列声明没有 `groupable`（`ListColumnMeta` 只有 `filterable` / `sortable`）。
- 请求没有 `groupBy`；响应没有 `groups` / 分组计数。
- 前端没有分组行、没有 `insertGroupHeaders`、没有 `@group-change`。
- 列头菜单生成器 `listHeaderMenuItems` 对任何 kind（含 ENUM / USER / BOOLEAN）都不产出 `group` 项。

**不要删其它叫 Group 的能力。** Virtual Group、Views 侧栏按表折叠、Automation / DW Filter Group、
关联表左侧 Function Unit 分组，都不是共享列表列头 Group，不在本条范围内。

PR #107 系列曾经默认「每列都能分组」，lookup 列还会出现分组头 `(0)`。那是要消掉的缺陷，
不是要修成「只对 ENUM 分组」。若将来单独做分组产品，另开设计，不要把菜单加回共享表头。

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
   DATE → 日期，BOOLEAN → True/False，TIME → DATETIME（时分秒），JSON / 未声明类型 → TEXT。
   **列集合跟设计走。** 绑定表优先；SUB 视图若展示本 FU 其它表（通常是 MAIN）同名字段，用那张
   表的类型补上。名字在本 FU 任何表上都没有 → 仍按 TEXT 筛存值，**禁止**按列名 `date` / `user`
   猜 DATETIME / USER。BYTEA 仍 display-only。FILE 按抽出的文件名筛（§6.3.2 下段）。

**禁止**用「该字段在某张表单里用了什么组件」来定筛选类型。同一字段可绑多张表单、控件可以不同，
Views 展示的是表列（可以完全不出现在任何表单上）。

Function Unit 的 Table Design **没有 LOOKUP / CHOICE 类型**。`select` / `radio` / `lookup` /
`user` / `owner` 落库都是 VARCHAR，因此：

- 业务 Choice（表单静态选项）→ 现在是文本筛。要做成封闭下拉，选项必须落到**表字段定义**上，本期不做。
- 业务 Lookup（存对端主键）→ 源字段按文本比 id；设计师在视图上加的 **lookup 显示列** 才按看到的
  名字筛（有存储键映射才能筛，没有就只展示）。关联表字段类型可以是真 `LOOKUP`，列表仍按存的主键当文本。

扫表单 JSON 里的 `type:"lookup"` 只用于显示列 hydrate / 反查存储键，**不是**筛选 kind 的来源。

**`FILE` 列：按格子上看到的文件名筛选、排序（A–Z），不算子。** Table Design
`data_type = FILE` → `Kind.FILE`。禁止把 FILE 当普通 `TEXT` 打开 Contains（会比到 URL，
与格子文件名不一致）。抽名规则与实现见
[list-file-name-filter.md](./list-file-name-filter.md)。BYTEA 仍 display-only。

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

### 6.4 共享列表不做分组

共享列表**没有**分组渲染、分组计数、分组行。`displayRows` 就是本页 `content`。

历史上 PR #107 的 lookup 分组头 `(0)`、前后端各算一遍 label，是分组产品自己的缺陷。
下线 Group 之后这条缺陷不再出现，也不要为了「修计数」把分组加回来。

Virtual Group、侧栏按表折叠、Filter Group、关联表 FU 分组仍按各自产品存在，与本节省口无关。

### 6.5 Relation Tables 接入（含内置 User 虚拟表）

Portal「Relation Tables」是共享列表的第二个消费者（Views 之后）。业务表与内置 User 共用列头 /
筛选 / 分页，但**数据落点不同**，列声明也不同。

#### 6.5.1 业务 Relation：JSON 行，无每表物理表

| 用途 | 物理表 |
|------|--------|
| 表 / 字段元数据 | `rt_table_definitions` / `rt_field_definitions` |
| 行数据 | `rt_table_data_rows`（`data` JSONB）；**不为每张业务表再建物理表** |
| 行级 Active/Inactive | `rt_table_data_rows.status`（toggle，**不是**列表数据列） |

列表列声明：`RelationTableColumnSpec` 从 `rt_field_definitions.data_type` → kind（BOOLEAN /
NUMBER / DATETIME / TEXT…；LOOKUP 按存的主键当 TEXT）。VARCHAR 实为码表若要 ENUM，须在字段定义
显式带 `options`；本期不扫全库推断。

筛选 SQL：`platform-common` 的 `ListFilterSql` + `JSON_ROW`（`data->>'field'`）。切表必须重置筛选 / 排序 / 搜索 /
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
共享表头没有 Group 项（§6.3.1），User 列表也不另做分组。

筛选 SQL：`platform-common` 的 `ListFilterSql` + `PHYSICAL_COLUMN`；WHERE 须以 `WHERE 1=1`（或等价）起头，再拼
`AND …`，避免 `FROM sys_users AND col …`。

**本轮非目标：** 业务表全量 VARCHAR→ENUM；洗 `zh_CN`/`zh-CN` 存值。

**验收正例：** User 的 status/language 筛选为下拉；列头无 Group 项。  
**反例：** status/language 出现 contains；把 User 行写入
`rt_table_data_rows`。

**实现收尾（与 Views 同范式）：** 列元数据随 `POST …/data` 的 page 返回（不另开 `/columns`）；
查询耗时 >1s 打 WARN（`listKey` / `tableId` / `page` / `size` /
`total` / `elapsedMs`，不含筛选值与行内容）。

**验证截图（PNG gitignore，PR 描述写绝对路径）：**

- `frontend/user-portal/verification-screenshots/2026-08-20_list-relation-tables-shared.png`
- `frontend/user-portal/verification-screenshots/2026-08-20_list-relation-user-after-enum.png`
- `frontend/user-portal/verification-screenshots/2026-08-20_list-relation-user-status-enum-filter.png`


## 6.6 列宽默认值与视口余量（2026-08-27 定稿；2026-08-28 fill 修订）

> **状态：已落地（2026-08-28 fill）。** 范围 = 已接入共享列表的 **user-portal + admin-center**；
> **一行 DW 都不改**（与本文头部硬约束一致）。底宽仍按表头实测与 kind 下限；**显示宽**在列合计
> 小于视口时按底宽比例摊进未拖过的数据列，表格铺满卡片。拖过的列锁定底宽。列合计超出视口时
> 横向滚动，Action `fixed="right"` 钉窗口右沿。2026-08-27 的 hug（空白留在表右侧）已由产品确认改为 fill。

接入共享列表之后，宽屏上 Action 前曾出现 spacer 空列，表头标题（尤其英文）经常被裁。
默认宽按标题实测抬高之后，2026-08-27 曾用 hug 避免短列被撑空；2026-08-28 产品确认宽屏
整条留白更差，改为 fill（拖过的列不参与分摊）。

### 6.6.1 现状（As-Is）

| | Portal / Admin 共享列表 | DW `designer-list`（只读参考，不改） |
|---|---|---|
| 数据列绑定 | 固定 `:width`（像素） | `:min-width` |
| 默认宽 | 各页手写 `COL_WIDTHS`，多数 120 | 全局 160 + 各设计器 `defaultWidth` |
| 视口比列总和更宽时 | `leftoverColumnWidth` 算出 spacer，插在数据列和 Action 之间 | 表格 `table-layout:auto` 把余量吃进各列，**无 spacer** |
| Action | 多数 `fixed="right"` | 同样 `fixed="right"` |
| 拖拽 | 1:1（鼠标移动多少，列就变多少） | 不是严格 1:1（列还会被表格再分配） |
| 记住宽度 | `sessionStorage`，关标签即丢 | 同样 session |

Spacer 不是某一页写错。`leftoverColumnWidth` 的注释写明：空宽停在尾部，**好让其它列保持 1:1 拖拽**。
有 Action 时列顺序是「数据列 → `list-col-spacer` → Action(`fixed="right"`)」；To Do / 已办没有
Action，空白贴在表最右边。空地就是 1:1 策略的视觉成本。

§2 非目标里的「列固定」指**用户可配置的钉列 / 列显隐**，不是说要把现有 Action 解钉。

### 6.6.2 目标 / 非目标

**目标**

1. **没拖过时表头完整可见，且按 kind 给够「大部分单元格」的底宽**（当前 locale 的标题文字 + 下拉箭头 + 紧凑拖拽槽 + 内边距，与 DATETIME/TEXT/USER 等内容下限取大）。特别长的单元格仍 ellipsis + tooltip；**不按当前页行数据实测**。列头 caret 贴着拖拽线，两列之间的 cell padding 收紧；**不**靠砍默认列宽来消空 gutter。
2. **Action 前不再有 spacer。** 列宽总和小于视口时，余量按底宽比例摊进未拖过的数据列，表格铺满卡片；勾选列与 Action 不参与分摊。列合计超出视口时 Action 继续 `fixed="right"` 钉窗口右沿（固定像素宽）。
3. 用户拖过的宽度写入 session，**可以窄于标题自动宽 / kind 下限**（表头允许 ellipsis）。下限仍是 `COLUMN_WIDTH_MIN=60`。Views 设计器 `columnWidth` 仍优先于自动宽。
4. 拖拽时当前列 1:1 跟手；松手后写入该列底宽（`clamp(拖到的宽, 60, 600)`），**不再弹回自动宽**。
5. **一份** `useListColumnLayout`：测宽、fill 显示宽、session 底宽与这份 composable 一起进
   `frontend/shared/src/list/`。两端 `import { useListColumnLayout } from '@platform-shared/list/useListColumnLayout'`，
   删除 app 内拷贝。分摊只走 `allocateFilledDisplayWidths`。

**非目标（本项明确不做）**

- 不改 `frontend/developer-workstation/`（含 `designer-list`）。
- 不改行操作模型（不上 MDA 的 Command Bar / hover Quick Actions）。
- 不按**当前页单元格**实测抬默认宽（翻页会跳列宽、一条超长备注会撑飞整列）。按列 `kind` 给稳定的内容下限。
- 不改后端、schema、i18n key / 文案本身（测宽吃**当前 locale 已经渲染出来的 label**）。
- 不抽 `usePortalListGrid` / `useAdminListGrid`（列声明、筛选仍是各 app 的事）。
- **列头 Group 已在本工作区下线**（菜单无 group 项，`insertGroupHeaders` / `groupBy` 请求已拆）。
  §6.6 **不再包含**分组删除；也不要把分组改动和列宽 fill 揉成同一套实现叙事。

### 6.6.3 定稿：底宽记住拖拽；显示宽在宽屏 fill

**底宽**（写入 session 的值，也是拖拽在改的值）按优先级：

1. 本列表 session 里该字段已被用户拖过 → `clamp(remembered, COLUMN_WIDTH_MIN=60, COLUMN_WIDTH_MAX=600)`。
   过窄时表头可以 ellipsis；用户仍可再加宽。不写迁移脚本。
   session JSON 带 `v`（当前 `LAYOUT_STORE_VERSION = 4`）；版本对不上则丢弃该 key，不当坏 JSON 抛错。
2. 否则，Views：设计器存了 `columnWidth` → 用设计值当底宽（**不被**标题自动宽盖掉）。
3. 否则 → `clampColumnWidth(max(HEADER_FIT_MIN=112, 标题实测 + chrome, kind 内容下限))`。

**标题实测**必须用表头**实际渲染面**：Portal/Admin `thead .cell` 是 **11px / 600 / uppercase /
letter-spacing 0.08em**（`ws-theme.scss`），用离屏 DOM 按该 CSS 测当前语言的 `column.label`
（`text-transform: uppercase`，不要在 canvas 宽度上再叠一层字距）。
禁止按 14px 混排测，否则英文 `Process Title` / `Current Assignee` / `Entity Manager` 会被裁。
禁止再为中文手调一版 `COL_WIDTHS`。chrome = caret（按 el-icon 盒）+ trigger gap + 拖拽 gutter（4px，贴着 8px 命中条）+
**两侧 cell padding**（`CELL_PADDING_X_PX`，list grid 为 6px）+ `HEADER_FIT_PAD_PX=6`，以共享层常量收口。
`HEADER_FIT_MIN = 112` 避免「状态」这类短标题列只够字、不够 caret + handle。

**kind 内容下限**（与表头取 `max`，仍走同一 `clamp`；数字收口在 `KIND_CONTENT_FLOOR`）：

| kind | 下限 | 覆盖的「大部分」内容 |
|------|------|----------------------|
| TEXT | 168 | `ATM-DC-PW-000013`、短标题；很长的流程名仍 ellipsis |
| DATETIME | 180 | `2026-08-27 15:04:00` |
| USER | 120 | 中文名、常见英文显示名 |
| ENUM / BOOLEAN / NUMBER | 112 | 与 `HEADER_FIT_MIN` 相同；更长的枚举 label 仍由表头实测抬高 |

**显示宽**：列合计小于视口时按底宽比例摊进未锁定列（`allocateFilledDisplayWidths`）；已拖过的列锁定底宽。禁止再引入 `distributeDisplayWidths` / `invertBaseWidth`。

```
fixed = 勾选列宽 + Action 列宽（没有就不计）
slack  = viewport − fixed − Σ(数据列底宽)
slack ≤ 0 → 显示宽 = 底宽；内层 100%；表格横向滚动；Action `fixed="right"` 钉窗口右沿
slack > 0 → 未拖过的数据列按底宽比例吃掉 slack；内层 100%；表格铺满卡片
```

勾选列、Action **不吃**数据列宽。去掉所有 `list-col-spacer` / `leftoverWidth` 空列。
分页条仍铺满卡片、靠右。

**拖拽：** mousemove **只改当前列的显示宽**（1:1 跟手，其它列保持按下时的宽度）。
`mouseup` / `width-commit` persist `clamp(拖到的宽, 60, 600)`。
拖到比自动宽更窄时**记住这个窄宽**，表头可以 ellipsis。
红线跟手，高度裁在表格与滚动容器的可见相交区域（不穿分页）。

**职责边界（对齐 §5）：** `ListColumnHeader` / `ColumnResizeHandle` 仍然不测宽、不 persist。
测宽以及 **`useListColumnLayout` 本身**都放 `frontend/shared/src/list/`。
组件仍不碰 session。Views 的 `mainTableViewGridRuntime` 继续管自己的底宽优先级
（session → 设计器 `columnWidth` → 表头实测与 kind 下限），显示宽与 Portal/Admin 同一套 fill。

### 6.6.4 Views 特例

运行时底宽：`session.columnWidths[field]` → `col.columnWidth`（设计器）→ 表头实测与 kind 下限取大。
设计器宽度是 maker 意图，对应 MDA 的 `visualSizeFactor`，**不要**用标题自动宽覆盖它。
无论底宽从哪来，显示宽走同一套 fill（与 Portal/Admin 相同），Views 也不能再出现 spacer。
无设计器宽度时，自动宽 = 表头实测与 `col.kind` 内容下限取大（与 Portal/Admin 同一函数）。
记住的宽可以窄于标题自动宽。

旧 session 里存的是「当时的显示宽」。第一次打开可能略宽，用户再拖一次即按新规则。
**不迁 session、不写迁移脚本。**

### 6.6.5 已否决

| 备选 | 结论 |
|------|------|
| 只改各页 `COL_WIDTHS` 手调默认宽 | **否决。** 中文调完英文仍裁；与余量无关，空白还在。 |
| 数据列改 `:min-width`，交给 EP `table-layout:auto`（纯 DW） | **否决。** 空白能消，拖拽不再 1:1，和现网 Portal 手感差一截。 |
| 余量全给 Action 前最后一列数据列 | **否决。** 空白变成「带表头的大空格」，那一列被撑空。 |
| Action 不 `fixed`，spacer 放到 Action 后面 | **否决。** 横滑时按钮离开视口。hug 宽屏留白是「表收拢后卡片右侧空着」，溢出时 Action 仍 `fixed="right"`。 |
| 按当前页单元格实测默认宽 | **否决。** 翻页最长值会变，列宽跟着跳；一条超长备注会撑飞整列。kind 下限覆盖「大部分」即可。 |
| 学 MDA 拆掉行内 Action，改 Command Bar | **否决（本项）。** 那是交互改版，不是列宽问题。 |
| 视口余量按底宽比例摊进数据列 | **2026-08-28 采纳（fill）。** hug 在宽屏留下整条卡片空白；短列略空可接受。拖过的列不参与分摊。 |

没有「既严格把显示宽钉死成底宽、宽屏 Action 又永远贴窗口右沿、又永远没有表右侧空地」的免费方案。
现网 spacer 把空地单独占一格来换 1:1。本项改为：**显示宽 = 底宽；空地在表外面。溢出时才钉 Action。**

### 6.6.6 验收 / 验证

**反例（必须消失）**

1. 英文 locale、未拖过 → 表头仍 ellipsis。
2. 有 Action 的列表在宽屏下，Action 前仍有 `list-col-spacer`。
3. 用户拖过的列，下次打开回到标题自动宽（session 被自动宽盖掉）。
4. Views 里设计器存了 `columnWidth` 的列，被标题自动宽覆盖。
5. 拖 Request ID 时其它列被挤扁、红线穿过分页。
6. 用户把列拖窄后松手，宽度弹回标题自动宽。
7. 从未拖过的 TEXT 列（如 Request ID）按表头收成比 `ATM-DC-PW-000013` 还窄。

**正例**

1. zh-CN / en，未拖过 → 表头完整可见；单元格过长仍 ellipsis + tooltip。
2. 宽屏无 Action **前** spacer；列合计小于视口时表格铺满卡片；Action 在表尾。
3. 拖一列的过程中其它列宽度不变、红线贴在当前列右缘且不超过可见表体；松手后该列就是拖到的宽度（可窄于表头，下限 60）。默认宽仍按 kind 内容下限；下拉箭头贴着拖拽线，两列之间没有大段空 padding。
4. 列总和超出视口 → 横向滚动，Action 钉窗口右沿。
5. To Do（无 Action）同样 fill，右侧不再留卡片空带。
6. `frontend/user-portal/src/composables/list/useListColumnLayout.ts` 与
   `frontend/admin-center/src/composables/list/useListColumnLayout.ts` **不再存在**；
   测宽 / fill / 布局只在 `@platform-shared/list` 有一份实现。分摊只走 `allocateFilledDisplayWidths`。

**落地范围（一次做完，不再拆「后续去重」）：** shared 测宽 + fill `useListColumnLayout` +
Portal/Admin 改 import 并删拷贝 + 去掉 spacer + Views 同一 fill + 单测 + 截图。

**验证（落地时）：** 共享布局单测（测宽可走 DOM / canvas fallback；Views 底宽优先级有纯函数测试）+
`frontend/scripts/verify-shared-list-column-layout.mjs` +
`frontend/scripts/verify-user-filter-operators.mjs`（Current Assignee 六则）+
两端 `pnpm run build` + 截图：To Do、My Request（有 Action）、一张 Admin 列表、Views；中英各一。
脚本**必须**打开 Permissions（混合页：`.portal-content` 不得 `overflow: hidden`，**同时**
嵌套 list 有封顶高度、表体自己可竖向滚）和 Delegations
（Action 列写死 width，窄屏下 Suspend/Delete 按钮可见）。只测 To Do / My Requests / Admin users
会漏掉这两处。
`git diff --stat frontend/developer-workstation` 必须为空。


## 6.7 左侧菜单 list view 必须接共享列表（2026-08-27 定稿）

> **状态：策略已定稿。** 约束 **user-portal + admin-center** 的左侧菜单（及页内承担该菜单主内容的 Tab）。
> **DW 不在范围。** 对照规则：`.cursor/rules/shared-list-portal-admin.mdc`。
>
> 两侧栏都是 Layout **手写**的（`PortalLayout.vue` / `AdminLayout.vue`），不是从 `router` 生成。
> 因此「加了一个菜单项」不会自动带上共享表头 / 分页；不加门禁就会再出现一张自建表。

### 6.7.1 什么叫必须接的 list view

**必须接：** 左侧菜单点进去后，**主内容**是「多行业务记录 + 列 + 筛选/排序 + 分页」的页面，
或该页里承担主内容的 Tab（例如 Delegations 的「我的规则 / 审计」）。

**不要接：** 卡片目录、仪表盘、树、消息流、表单、落地/外链页、弹窗里的小表、Lookup 下拉、
任务/申请详情里的表单子表。这些不是共享列表的消费者。

判断口诀：用户是在「翻一张有列的业务表」还是在「干别的事」。是前者 → §6.7 全套；是后者 → 登记到
`exempt` 并写原因，禁止第三种「没登记」。

### 6.7.2 必接套件（缺一不可）

新菜单或把现有页改成记录列表时，**一次接齐**，禁止「先裸表、以后再接共享件」。

| 能力 | 必须用的真源 | 禁止 |
|------|----------------|------|
| **共享表头** | `@platform-shared/list/ListColumnHeader.vue`（菜单、筛选入口、拖拽柄都在这里） | 只用 `el-table-column` 的 `label` 当表头；再写一套 Portal/Admin 私有列头 |
| **按字段类型筛选** | 列 `kind` + 算子矩阵 §6.3 / §6.3.2；弹窗 `ListFilterDialog`（由列头打开） | 视图里猜算子；ENUM/BOOLEAN 退化成文本框；FILE 当 TEXT 用 contains |
| **按字段类型排序** | 同一套 `kind` + §6.3.3；菜单文案走 `sortLabelKeys(kind)` | 数字按字符串排；DATETIME 菜单写 A→Z |
| **列宽可调** | 共享 `ColumnResizeHandle` + 列布局（§6.6：宽屏 fill） | 不可拖的死宽表；再手写一页 `COL_WIDTHS` 当默认宽；Action 前 spacer |
| **共享分页** | `@platform-shared/list/ListPagination.vue` | 主列表用裸 `el-pagination`；页长变化不把 page 归 1 |
| **真分页查询** | §6.2：`COUNT` + `page`/`size`/`sort`/`filters` 下推 | 拉全量再 `.length`；筛选只在前端切当前页 |

接入时仍须回答 §6 四问（可见范围、真分页、kind 算子、分组——本分支列头已无 Group 项，
**禁止**在新列表上再做一套私有分组菜单）。

列左右移动是表头已有能力，按该列表是否允许用户改列序选用，**不是**第四件必接之外的例外。
Action 列可继续 `fixed="right"`（§6.6），不代替共享表头。

### 6.7.3 分类表（2026-08-27 盘点）

路径以 Layout 的 `el-menu-item index` 为准（动态 `:index` 写模式）。同一页面多 FU 子项算一条。

**Portal — required（已接共享列表）**

| 侧栏 | 页面 |
|------|------|
| `/tasks` | `views/tasks/index.vue` |
| `/tasks/completed` | `views/tasks/completed.vue` |
| `/my-applications` | `views/applications/index.vue` |
| `/delegations`（规则 / 审计 Tab） | `DelegationRulesList` / `DelegationAuditList` |
| `/permissions`（申请 / 审批表） | `PermissionRequestSharedList` |
| `/relation-tables`、`/relation-tables/:fu` | `views/relation-tables/index.vue` |
| `/views`、`/views/:fu` | `views/main-table-views/index.vue` |
| `/audit/:fu` | `views/audit/index.vue` |
| `/member-management` | `views/permissions/member-management.vue`（不在侧栏，已接全套；成员在选组/BU 后客户端筛选排序分页） |

**Portal — exempt**

| 入口 | 原因 |
|------|------|
| `/dashboard` | 统计卡片，不是列网格 |
| `/bi-dashboard` | BI 落地 |
| `/processes` | 流程卡片目录 |
| 顶栏通知 `/notifications` | 消息流，且不在左侧菜单 |
| 详情 `/tasks/:id`、`/applications/:id`、`/views/:fu/detail` | 表单/详情，不是列表 |
| 登录 / 403 / 404 / profile | 非业务列表 |

**Portal — gap（是记录列表，尚未接齐 §6.7.2；新菜单不得再增加此类）**

| 入口 | 现状 | 处理 |
|------|------|------|
| `/delegations` 的委托任务 Tab | 共享列表（`queryTodoTasks` + `assignmentTypes=DELEGATED`） | 与 To Do 同一套表头 / 筛选 / 列宽 / 分页 |

**Admin — required（已接）**

| 侧栏 | 页面 |
|------|------|
| `/user/list` | `UserList.vue` |
| `/virtual-group` | `virtual-group/index.vue` |
| `/role` | `RoleList.vue` |
| `/function-unit` 各 Tab | `FunctionUnitListTab` / `ArchiveTab` / `DeploymentsTab` |
| `/bi-management/dashboard-registry` | `DashboardRegistry.vue` |
| `/bi-management/dashboard-assignment` | `DashboardAssignment.vue` |
| `/bi-management/rbac-mapping` | `RbacMapping.vue` |
| `/audit/admin-center`、`/audit/user-portal` | 两页审计列表 |
| `/relation-tables/structure` | `structure/index.vue` |
| `/relation-tables/data`、`/relation-tables/data/:fu` | `RelationTableDataGrid.vue` |
| `/automation-pieces` | `automation-piece/index.vue` |
| `/automation-flows` | `automation-flow/index.vue` |
| `/automation-runs` | `automation-run/index.vue` |

**Admin — exempt**

| 入口 | 原因 |
|------|------|
| `/dashboard` | 概览 |
| `/organization` | 业务单位树 |
| 结构创建/编辑、ER 图、导入页、profile、各 *Dialog | 表单 / 图 / 弹窗小表 |

### 6.7.4 加 / 改左侧菜单时的硬步骤

1. 在 Layout 增加或修改 `el-menu-item` **之前**，先把该 `index` 写入 §6.7.3 的 `required` 或 `exempt`（本文件与规则一起改）。未分类 = 不允许合并。
2. `required` → 同一提交接齐 §6.7.2，并回答 §6 四问；截图进 `verification-screenshots/`。
3. 禁止：先上裸表、ticket 里写「稍后换共享组件」。
4. 不要给所有 `el-table` 一刀切（子表 / 弹窗会误伤）。只对「侧栏 list view」强制。
5. `git diff --stat frontend/developer-workstation backend/developer-workstation` 仍须为空（与本文头部一致），除非另开 DW 接入任务。

### 6.7.5 怎么保证「每次」

| 层 | 做什么 |
|----|--------|
| 文档 | 本节省分类表；新菜单必须改表 |
| Agent | `.cursor/rules/shared-list-portal-admin.mdc`（改 Layout / 两端 `views` / `router` 时加载） |
| Review | code-review 命中 Portal/Admin 侧栏或新 `el-table` 主列表 → 对照本节；漏接 = **Major**（用户可见列表交互分裂）；完全自建列头+分页冒充已接入 = **Blocker** |
| CI（待挂） | 解析两个 Layout 的 `index`：不在 required/exempt/gap → 失败；required 页未 import `ListColumnHeader` 与 `ListPagination` → 失败；主列表仍用裸 `el-pagination` → 失败。gap 仅允许表中已列路径，**禁止新增 gap** |

未挂 CI 之前，靠分类表 + 规则 + review。**不能**指望「看起来像列表就会有人想起共享件」。


## 7. 影响面


| 层级           | 变更                                                                                                                    |
| ------------ | --------------------------------------------------------------------------------------------------------------------- |
| 前端共享         | 新增 `frontend/shared/src/list/`（4 组件 + 光标 util + scss + 类型）；UP `mainTableView` 那两个文件搬进共享层后删除                           |
| 前端各 app      | **只有 user-portal 与 admin-center**：每个接入的菜单替换列头/分页、新增该菜单的列状态 composable。`frontend/developer-workstation/`** 零 diff      |
| 后端 API       | 每个接入的列表：新增 `/columns` 列元数据端点；列表端点新增 page/size/sort/filters 参数（**只增不改**，旧参数保持兼容）。**不要**再加 `groupBy` |
| 后端 Component | 列声明（kind + 算子）单点定义；查询下推；`COUNT` 真分页。**不要**做分组计数                                                                                 |
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
| 5   | 算子矩阵单点声明（后端 kind → 算子，前端共享类型）：补 `NUMBER` / `BOOLEAN`。**不要**声明 `groupable`（§6.3.1）                                                                                          |


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
3. 共享列头菜单出现 Group / Ungroup，或列表插入分组行。
4. 数值列使用 `gt` / `lt` 筛选 → 500。
5. SUB 视图里两条内容相同、都没有身份键的子表行 → 被 `md5(elem::text)` 并成一行，`total` 少 1，无任何提示。
  （实测 dev 库 27 行中 26 行的身份键是 `row_id`、`id` / `id_idw` 一次未出现，所以 PR #107 的
   `COALESCE(elem->>'id', elem->>'id_idw', md5(...))` 是 100% 走 md5 分支。）
6. 请求或响应仍携带 `groupBy` / `groups` / `groupable`（Jackson 可忽略未知字段，但新代码禁止再写这些字段）。

**正例（期望）**

1. 同上场景，非参与用户查询结果**不含**该行；参与用户（发起人 / 历史办理人 / 真实 MI 参与人）
  结果**包含**该行；`SYS_ADMIN` 按 `view-access-control` 既有语义可见全部。
2. 切表后：新表的请求**不带**旧表筛选；列头状态与实际查询条件一致（要么都是新表的持久化筛选，要么都为空）。
3. 共享列头菜单对 TEXT / ENUM / USER / BOOLEAN / NUMBER / DATETIME **都没有** Group 项；
  网格没有分组行；查询请求没有 `groupBy`。
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
9. Virtual Group、Views 侧栏按表折叠、Filter Group、关联表 FU 分组**仍然可用**——
   下线的只是共享列表列头 Group，不是所有名叫 Group 的功能。
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

## 12. 决策记录（原「待确认」6 项已关闭；第 7、8 项 2026-08-27 追加）

> 已定：**行可见范围沿用现有权限语义 + 两级过滤，不新增表、无阈值兜底**（§6.1）；
> **SUB 行身份取自共享 `ROW_IDENTITY_FIELDS`（`row_id` 优先），无身份键抛错不静默合并，
> 写入侧 → MAIN → SUB 三步走**（§6.1.1）；**共享表头不再提供 Group 菜单**（§6.3.1）；
> **本期不改 DW 任何文件**，共享组件只由 UP + AD 消费，列宽拖拽从 UP 搬、列头与筛选弹窗新写（§3、§8）；
> **全程零兜底**，所有"取不到"一律显式失败（头部硬约束）；
> **列宽 fill 按 §6.6**（2026-08-28）：默认宽 = 表头实测与 kind 内容下限取大；chrome 紧凑（caret 贴拖拽线）；宽屏按底宽比例摊余量；用户可拖窄到 60px；溢出时 Action `fixed="right"` 钉窗口右沿；
> **侧栏 list view 按 §6.7**：共享表头 + 按 kind 筛选排序 + 可调列宽 + 共享分页，路径必须分类。

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
2. ~~AD 是否也需要分组~~ → **已关闭，并被 §6.3.1 取代。** 共享表头两端都不提供 Group 菜单；
  不是「AD 的 ENUM 列自动有分组」。AD 若有 Virtual Group 等独立产品，与列头 Group 无关。
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
7. **默认列宽与视口余量** → **已定（2026-08-28 fill）：底宽 = 表头实测与 kind 内容下限取大（Views 设计器 `columnWidth` 优先）；宽屏按底宽比例摊余量，拖过的列锁定；用户拖窄后记住，不再弹回；caret 贴拖拽线；去掉 spacer；溢出时 Action 维持 `fixed="right"` 钉窗口右沿。** 落地要求见 §6.6。

   决策依据：Power Apps model-driven 网格在列总和小于视口时按设计宽比例把余量分给各数据列、
   铺满 100%。2026-08-27 hug 避免短列被撑空，但宽屏整条卡片留白观感更差，2026-08-28 改为 fill。
   仍否决「只撑最后一列」「解开 Action 换 spacer」「照抄 DW `:min-width`」。
   单元格不按当前页实测（翻页会跳）；TEXT/DATETIME/USER 用稳定 kind 下限。
   Portal/Admin 两份 `useListColumnLayout` 已抽进 shared。已落地：
   `frontend/shared/src/list/useListColumnLayout.ts` + `columnWidthLayout.ts`（`allocateFilledDisplayWidths`）。
8. **Portal / Admin 左侧菜单 list view** → **已定（2026-08-27）：必须一次接齐共享表头、按 kind
   筛选与排序、可调列宽、共享分页；侧栏路径必须登记 required / exempt / gap。** 见 §6.7。

   决策依据：两侧栏是 Layout 手写，加菜单不会自动用上 `@platform-shared/list`。只写「请记得接」
   挡不住下一张裸表。分类表强迫每个新 `index` 做决定；规则 + review 管 agent；CI 挂上之后才
   能卡住人。gap 仅允许已列路径，禁止再开新 gap。Delegations 委托任务 Tab 已接共享列表。
   DW 仍除外。

---

请确认以上设计 Plan。确认后若要开始实现，请回复 **按 playbook 执行**（或补充 / 修正项）。