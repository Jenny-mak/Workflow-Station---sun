# 设计文档索引

按**功能域**分组，而不是按文件名字母序——同一功能常常需要多篇（模型一篇、组件/交互一篇、
产品规则一篇），只看目录列表分不清哪些属于同一件事。

新增文档时：**归入已有功能域就沿用该域前缀**，并在本文件加一行；开新域时在下面加一节。

## 命名约定

| 前缀 | 功能域 |
|------|--------|
| `mi-*` | 多实例（MI）子任务分派 |
| `owner-*` | Owner 人员组件（表单 + View） |
| `portal-*` | User Portal 终端用户身份与权限 |
| `developer-workstation-*` | Developer Workstation 设计器侧 |
| `table-design-*` | Table Design 建模规则 |
| 无前缀 | 跨端/全局主题（如信息架构、总蓝图） |

---

## MI 多实例子任务分派

一个 MI 子流程按子表逐行展开，每行可指派给**某个人**或**某个 BU + Role 的共享认领池**。

| 文档 | 覆盖 |
|------|------|
| [mi-subtask-bu-role-assignment.md](./mi-subtask-bu-role-assignment.md) | **分派模型**：BPMN 契约（`assigneeMode`/`assigneeField`/`roleField`/`buField`）、后端逐行解析、认领池、孤儿修复、To Do 可见性 |
| [mi-assignment-mode-component.md](./mi-assignment-mode-component.md) | **Assignment Mode 表单组件**：容器化与拖拽、DW 设计器/DW Preview/Portal 三端渲染、form-create 框架约束、排查手册 |

> 改这两处任一侧前，两篇都值得扫一眼：契约字段名由前者定义，后者依赖它做门控。

## Owner 组件（Creator / Case Handler）

Table Design 先建 VARCHAR 列，表单上把控件改成 Owner（**主表、子表都可以**）。属性面板 **下拉框**选 **Creator** 或 **Case Handler**（两值，不是四值）。Create = 行创建预填当前 user、Save 落库。主表 Case Handler：进行中写任务 assignee，**该节点 Complete 写实际操作人**（委托则记代办人），MI 写外层框名（`step:`），终态清空；子表 Case Handler **仅 MI 行**写人，非 MI 不自动写。一表、一表单可以多个。View 勾选该列即可。不是每表一个，也不自动建列。

| 文档 | 覆盖 |
|------|------|
| [owner-field-component.md](./owner-field-component.md) | **Owner 组件**（状态：2026-09-07 修订）：Source 下拉、四场景、主表 Case Handler=`user:`/`step:`、Complete 记实际操作人、子表仅 MI 行 |

> 主表 Owner Case Handler 与申请详情 Basic Info / My Request 列表 Current Assignee **同一套展示逻辑**。To Do 任务头仍是本任务办理人。存储不与实例列合成。分派仍看 BPMN；改 Owner 不转办。Source 是下拉两项。
> 08-17「拖组件建列 / 每表一个 / 可改派 / 禁止跟办理人」已作废，且 **未合入 origin**。实现以该文档为准，样式跟 Lookup / `ws-theme`。
> 「owned by me」筛选、手改派、行级可见性均另开设计。

## User Portal 身份与权限

| 文档 | 覆盖 |
|------|------|
| [portal-bu-rbac.md](./portal-bu-rbac.md) | **身份从哪来**：BU + 角色（UBR）模型、工作台上下文、JWT 硬约束 |
| [portal-permission-self-service.md](./portal-permission-self-service.md) | **门户能做什么**：UBR 自助申请/代办/退出、无 UBR（`C` 为空集）时的访问模式 |
| [portal-task-delegation.md](./portal-task-delegation.md) | **站立任务委托**：时间窗规则；目标为指定用户 **或** BU+Role；不改 `assignee`；须切工作台；与 UBR「代办申请」分域 |
| [portal-task-single-delegate.md](./portal-task-single-delegate.md) | **单任务委托按钮**（已定稿）：不改 `assignee`；目标为指定用户 **或** BU+Role；不认领；须切到该工作台才可见 |

> 身份两篇互补且已互链：前者是硬约束，后者是产品规则。任务委托有两条线（站立规则 / 单任务按钮），都是不改办理人、别人代 A 办——**不要**与 UBR「代办申请」混称，也**不要**把委托做成转办。用户可见用「委托 / 委托任务」；不用 Acting For。

## Developer Workstation

| 文档 | 覆盖 |
|------|------|
| [developer-workstation-workspace-rbac.md](./developer-workstation-workspace-rbac.md) | 功能单元工作区隔离：Technical Lead / Team Lead / Developer 可见性与操作权限 |

> 与 [portal-bu-rbac.md](./portal-bu-rbac.md) 互补：这里是**设计器**工作区，那里是**终端用户**门户。

## Table Design 建模规则

| 文档 | 覆盖 |
|------|------|
| [table-design-fk-pk-requirements.md](./table-design-fk-pk-requirements.md) | 外键 / 主键规则 PRD（状态：已定稿待开发） |

## 邮件与提醒

| 文档 | 覆盖 |
|------|------|
| [mail-monitor-and-task-due-reminder-design.md](./mail-monitor-and-task-due-reminder-design.md) | 邮箱监控入子表 + 任务即将过期通知（状态：**方案评审中，未实现**） |

## 跨端主题

| 文档 | 覆盖 |
|------|------|
| [feature-blueprint.md](./feature-blueprint.md) | 1.0 功能总蓝图（三应用 = 三层楼的整体视图，2.0 规划树） |
| [user-profile-information-architecture.md](./user-profile-information-architecture.md) | 三端「个人中心 / 顶栏用户菜单」的信息边界与术语 |
| [shared-list-components.md](./shared-list-components.md) | **列表共享组件 + 服务端分页接入规范**（状态：**方案已定稿**）：列头 / 按 kind 筛选排序 / 可调列宽 / 分页；**§6.5 RT**；**§6.6 列宽 fill（宽屏按底宽比例摊余量）**；**§6.7 Portal/Admin 侧栏 list view 必须接全套**；本期不含 DW |
| [list-file-name-filter.md](./list-file-name-filter.md) | **列表 FILE 列按文件名筛选**（状态：**已实现**）：`Kind.FILE`；与格子同一套抽名；禁止当 TEXT 比 URL |

> 列表改造是**增量**的：共享组件纯新增，一个菜单一个提交，未接入的菜单行为不变。
> 行可见范围沿用现有权限语义，子串匹配只能做**候选粗筛**、必须接精确复核（§6.1）。
> SUB 视图的行身份取 JSON 上的 `row_id`（优先级见 §6.1.1），**不走物理表 PK**——业务子表是
> JSON 行存储、没有物理表，走那条路必然取空；没有身份键的行**抛错**不静默合并。
> 分组能力**按字段语义逐列声明**，不是每个列头都挂分组入口（§6.3.1）。
> 筛选 kind 的权威是表 `data_type` / 视图系统列，**不是** Form 组件；`current_step` 是 TEXT。
> SUB 的四列系统字段同样筛 `pi.*`（和 MAIN 同一套 kind），不是 display-only（§6.3.2）。
> `FILE` 列按抽出的文件名筛；禁止当 TEXT 凑合（§6.3.2）。
> 封闭选项列（Status / Legal Hold）筛选一律 Equals / Not equals / 没值 / 有值；人员列另加 Contains / Does not contain（§6.3）。
> 排序按 kind：文本字母、数字大小、时间新旧（§6.3.3）。
> Portal / Admin **左侧菜单记录列表**必须接共享表头 + 按 kind 筛选排序 + 可调列宽 + 共享分页（§6.7）；
> 侧栏是手写的，加菜单必须先登记 required / exempt。
> 翻页 loading：网格 `v-loading` 一只转圈；`ListPagination` 只禁用，不在页码左边再画一只。
> 深分页**不设页数上限**，改为 >1s 记 WARN 慢查询日志，按生产真实分布再决定（§6.2）。

---

## 相关位置

- **规则与技能**：`.cursor/rules/`（唯一真源）、`.cursor/skills/`；镜像见 `.claude/` `.github/` `.kiro/`
- **功能规格（立项快照）**：[../specs/README.md](../specs/README.md) —— 需求 / 设计 / 任务三段式的历史立项记录。
  分工：本目录是**现行契约**（改功能时以此为准），`specs/` 是**当初为什么这么做**（追溯设计意图）。
- **架构**：[../architecture/](../architecture/)
- **排障**：[../troubleshooting/](../troubleshooting/)
- **数据库**：[../database/](../database/)、schema 真源为 `deploy/init-scripts/00-schema`
