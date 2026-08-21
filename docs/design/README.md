# 设计文档索引

按**功能域**分组，而不是按文件名字母序——同一功能常常需要多篇（模型一篇、组件/交互一篇、
产品规则一篇），只看目录列表分不清哪些属于同一件事。

新增文档时：**归入已有功能域就沿用该域前缀**，并在本文件加一行；开新域时在下面加一节。

## 命名约定

| 前缀 | 功能域 |
|------|--------|
| `mi-*` | 多实例（MI）子任务分派 |
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

## User Portal 身份与权限

| 文档 | 覆盖 |
|------|------|
| [portal-bu-rbac.md](./portal-bu-rbac.md) | **身份从哪来**：BU + 角色（UBR）模型、工作台上下文、JWT 硬约束 |
| [portal-permission-self-service.md](./portal-permission-self-service.md) | **门户能做什么**：UBR 自助申请/代办/退出、无 UBR（`C` 为空集）时的访问模式 |
| [portal-task-delegation.md](./portal-task-delegation.md) | **任务委托（本期）**：仅站立规则 act-as；单任务 DELEGATE/TRANSFER 本期不理；与 UBR「代办」分域 |

> 身份两篇互补且已互链：前者是硬约束，后者是产品规则。任务委托是第三条线——**不要**与 UBR「代办申请」混称。

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
| [shared-list-components.md](./shared-list-components.md) | **列表共享组件 + 服务端分页接入规范**（状态：**方案已定稿；Views / RT 已在接**）：列头 / 列宽 / 筛选弹窗 / 分页；**§6.5 Relation Tables**（业务 JSON 行 + 内置 User=`sys_users`；类型化筛选；一律不分组）；本期不含 DW |
| [list-file-name-filter.md](./list-file-name-filter.md) | **列表 FILE 列按文件名筛选**（状态：**方案评审中，未实现**）：基线仍是 display-only；下一期用与格子同一套抽名规则筛，禁止当 TEXT 比 URL；推荐查询侧 SQL 抽名（MVP），落库结构化为后续 |

> 列表改造是**增量**的：共享组件纯新增，一个菜单一个提交，未接入的菜单行为不变。
> 行可见范围沿用现有权限语义，子串匹配只能做**候选粗筛**、必须接精确复核（§6.1）。
> SUB 视图的行身份取 JSON 上的 `row_id`（优先级见 §6.1.1），**不走物理表 PK**——业务子表是
> JSON 行存储、没有物理表，走那条路必然取空；没有身份键的行**抛错**不静默合并。
> 分组能力**按字段语义逐列声明**，不是每个列头都挂分组入口（§6.3.1）。
> 筛选 kind 的权威是表 `data_type` / 视图系统列，**不是** Form 组件；`current_step` 是 TEXT。
> SUB 的四列系统字段同样筛 `pi.*`（和 MAIN 同一套 kind），不是 display-only（§6.3.2）。
> `FILE` 列基线只展示；按文件名筛见 [list-file-name-filter.md](./list-file-name-filter.md)，禁止当 TEXT 凑合（§6.3.2）。
> 封闭选项列（Status / Legal Hold / 人员）筛选一律 Equals / Not equals / No data / Has data（§6.3）。
> 排序按 kind：文本字母、数字大小、时间新旧（§6.3.3）。
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
