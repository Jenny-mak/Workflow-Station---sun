# Owner 组件：Table 字段 + 取值来源（Creator / Case Handler）

> **状态：方案修订中（2026-09-07）。** 相对 08-21：Source **改为下拉框**；写入规则按 **主表/子表 × Creator/Case Handler** 四场景写清，并纳入 **MI**。改代码前先确认本文；确认后按 playbook 执行。
>
> **给后续 agent：** Owner 是已有 Table 列上的 **控件类型**，不是自动建列、也不是每表只能一列。
> - 设计师先在 Table Design 建 `VARCHAR` 列，拖进表单后把控件改成 `type:"owner"`。**主表、子表都可以。**
> - 每个 Owner 控件用属性面板 **下拉框**选一个 **source**：`CREATOR` 或 `CASE_HANDLER`（下拉文案 **Case Handler**；**不是**四个枚举；主表/子表由字段所在表决定）。流转中按 §3.3 四场景 **自动赋值**，控件只读。
> - **一表 / 一表单可以有多个** Owner（典型：一列 Creator，一列 Case Handler）。
> - 值落在**该 Owner 列自己的行 JSON**：一个人 `user:<userId>`；未领的一堆人 `user:<id1>,user:<id2>`；另有 `<field>__display` 姓名缓存。
> - Owner 的第二个 source 叫 **Case Handler**（`CASE_HANDLER`；旧 config `CURRENT_ASSIGNEE` 当同义）。**只表示取值规则**。**My Request / 从 Data 点进的申请详情 Basic Info「Current Assignee」与主表 Owner Case Handler 同一套逻辑**（§3.3.3 / §6.7）。To Do 详情 Basic Info 仍是**当前这条任务**的办理人，不要改成主表那套。不要用实例列在读路径盖掉 Owner JSON。
> - **不**改 BPMN 分派、**不**改 MI `AssignmentConfig` / `assigneeField`；改 Owner **不**转办任务。Owner **不**控制行可见性。

## 相对旧方案（必读）

| 版本 | 结论 | 本文 |
|------|------|------|
| 2026-08-17 Dataverse 式 | 拖组件自动建列；每表一个；空才填创建人后可改派；**禁止**跟办理人同步 | **作废** |
| 更早 `valueSource=ASSIGNEE` + 全局 `OwnerAssigneeSync` | 所有 Owner 都跟办理人变 | **作废**（同步只发生在 `source=CASE_HANDLER` 的那些列） |
| 2026-08-19 | 先建列再改类型；source 二选一；可多个；自动赋值；系统风格只读控件 | 骨架仍有效 |
| 2026-08-21 | Owner 的 Current Assignee **≠** 系统 Current Assignee；主表+子表都可用；未领把一堆 `user:` 写进 **Owner 列** | 值模型仍有效 |
| 2026-09-07（本文） | Source **下拉框**：Creator / **Case Handler**（`CASE_HANDLER`）。主表 Handler：普通节点 `user:`，MI `step:`，终态清空。委托期间先写 assignee，**该节点 Complete 时改写实际操作人**。子表 Handler 仅 MI 行 | **现行** |

**相对 origin：Owner 尚未合入。** `origin` 上没有 `OwnerField.vue` / `OwnerFieldComponent` / `OwnerFieldFormReconciler`。08-17 的实现只存在于未合并的 fork / 本地分支，**不要当 origin 基线去「打补丁」**。

实现策略：

- **从 origin 开工：** 按本文 **新增** 全套（对账、提交、同步、只读控件）。不要先落地 08-17 再改。
- **若在仍含 08-17 文件的 fork 上继续：** 那些文件必须 **按本文改语义**（去掉每表一个、自动建列、空才填当前用户、可改派选组），不是在旧约束上加 source。合入 origin 时以本文为准，不要把 08-17 行为带进主干。

相关文档：

- Owner `CASE_HANDLER` **进行中只借用**「当前任务 assignee 怎么算」：已领 = 那一个人，未领 = 候选人展开后的 user 列表。**该节点 Complete 时改写实际操作人**（委托时即代办人）。赋值结果写进 **Owner 字段**。
- 申请详情 Basic Info / My Request 列表的 Current Assignee：**展示**与主表 Owner Case Handler 同一套规则（§6.7）。存储可以仍是实例列或算出来的展示值，但**禁止第二套算法**。To Do 任务头仍是本任务办理人。
- 任务分派模型（不要复用到本组件）：BPMN `assigneeType`、`UserTaskAssigneeConfigSection.vue`
- MI 行内分派（不要改）：[mi-subtask-bu-role-assignment.md](./mi-subtask-bu-role-assignment.md)
- View 访问管控：skill `view-access-control`（Owner **不**控制行可见性）
- 关联工单：HSWORKFLOW-815 可用主表 Owner 列在 View 里**展示同类信息**；那仍是 Owner 字段，不是新增系统列 `current_assignee`

---

## 1. 背景与目标

平台里「这条记录是谁创建的」和「这单现在谁在办」是两个问题。设计师要能在 **同一张表、同一张表单** 上用两个（或多个）字段分别回答，并在 Main Table View 里勾出来。

设计师下拉只选 **source**（两项）。主表还是子表看字段绑在哪张表，不要做成四个 source 值。

| 场景 | source | 字段所在表 | 字段回答的问题 | 流转时（含 MI） |
|---|---|---|---|---|
| **主表 Create** | `CREATOR` | MAIN | 谁建了这张主表行 | 行创建时预填当前登录用户（只读）；首次 Save 写**当时操作人**。之后不覆盖、不可手改 |
| **子表 Create** | `CREATOR` | SUB | 谁建了这一行 | 同上：+Add 预填当前 user（只读）；首次 Save 写当时操作人。之后不覆盖、不可手改 |
| **主表 Case Handler** | `CASE_HANDLER` | MAIN | 主流程当前谁在办 / 这一步谁办完的 | 进行中 = 该任务 **assignee**（`user:`）。**MI = 外层框名** `step:`。委托期间仍是 A；**该节点 Complete 时改成实际点完成的人**（B 代办则记 B）。下一段普通节点再换成新人；终态清空 |
| **子表 Case Handler** | `CASE_HANDLER` | SUB | 这一行谁在办 / 谁办完的 | **仅 MI 行**。进行中 = 该行任务 assignee；委托 Complete 时记实际操作人。非 MI 不自动写 |

操作步骤：

1. Table Design：在已有 MAIN / SUB 表上新增（或使用已有）`VARCHAR` 列，例如 `case_owner`、`current_handler`。
2. 打开已绑定该表的表单，把该字段拖进画布（默认是 `input`）。
3. 把该控件类型改成 Owner，属性里用 **下拉框**选 source（Creator / Case Handler）。
4. 保存表单。运行时按 §3.3 四场景自动写该列；View catalog 里勾该列即可展示。

**成功标准：**

- 同一表单可以同时有 Creator 列和 Case Handler 列（主表或子表）。属性面板 Source 是下拉框。
- **Create（主表/子表）**：行创建预填当前 user（只读）；首次 Save 写当时操作人；之后不覆盖。全程不可手改。
- **主表 Case Handler**：进行中写任务 assignee；**Complete 写实际操作人**（委托则记代办人）；MI 写外层框名（`step:`，不当成人）；再遇 MI 换框名，再遇普通节点换成人；终态清空。**子表 Case Handler**：仅 MI 行；进行中写该行 assignee，Complete 写实际操作人；非 MI 不自动写（方案 B）。
- 未领 = 一堆 `user:`；领了 / 转办后 = 那一个人。Creator 列不变。
- 两列都能在 View 显示，保存 / 刷新后与表单一致。系统 Current Assignee UI 不被这个字段替换。

---

## 2. 非目标

- **不**把 Owner JSON 和实例列 `current_assignee` 合成一个存储。展示上：申请详情 Basic Info / My Request 列表的 Current Assignee **必须**与主表 Owner Case Handler 同一套逻辑（§6.7）。To Do 任务头除外。
- **不改** `AssigneeType`、`TaskAssigneeResolver`、`TaskAssignmentListener`、BPMN 扩展、MI `AssignmentConfig`。
- **不**用 Owner 控制 View 行可见性（「owned by me」、行级 ACL 另开）。
- **不做**改 Owner 就转办 / 认领（单向：按办理规则取值 → 写入本字段，永不反向）。
- **不**在每张表默认追加 Owner 列；**不**在保存表单时自动 `INSERT dw_field_definitions`。
- **本期不做**表单内手改派、不做 BU+Role 组选择器（08-17 的 `allowGroup` / 选组 UI 退出 MVP）。不读、不写、不展示 `group:`。
- **不**在读路径用系统 Current Assignee 覆盖 Owner 列已写入的值。

### 2.1 与现有人员概念

| 概念 | 真源 | Owner 怎么用它 |
|---|---|---|
| 当前登录用户 | 会话身份 | **仅** `CREATOR`：行创建时只读预填；首次 Save 由服务端写**当时操作人**（忽略客户端改值） |
| 发起人 `start_user_id` | 实例列 | **不是** Owner Creator 的写入真源（发起页操作人通常等于发起人，以 Save 时操作人为准） |
| Current Step（`currentStepName`） | 普通节点 = 任务名；MI 内 = **外层 multiInstance subProcess 名** | 主表 Case Handler 在 MI 时**写入同一串名字**，前缀 `step:`，展示为普通文本（不当人头） |
| 主流程用户任务办理人 | 非 MI 用户任务的 assignee / 候选人 | **仅**主表 `CASE_HANDLER` **进行中**默认值；Complete 时改成实际操作人 |
| 某行 MI 子任务办理人 | 该行对应的内层 MI 任务 | **仅**子表 `CASE_HANDLER`（MI collection）进行中默认；Complete 记实际操作人。**禁止**写进主表 Owner |
| 申请详情 Basic Info / My Request 列表 Current Assignee | 与 §3.3.3 **同一套计算结果** | 进行中 = assignee（委托仍是 A）；该步办完后活数据跟下一节点走。**不是** To Do 当前任务办理人 |
| 审计 `created_by` | 行 JSON，系统填写 | 无关；不要复用审计列当 Owner |

Create **不是**「钉死 `start_user_id`」，也**不是**可手改。行创建只读预填当前登录用户；首次 Save 服务端写当时操作人。审批节点再保存不得把已有 Creator 改成当前审批人。客户端改值一律忽略。

显示名：**ID 不变，名字跟用户档案走。** Creator / Case Handler 读表单和 View 时都按 user ID **现查**（`UserDisplayNameResolver`）。`__display` 只是缓存，投影以现查为准。人改名后，Owner 两列都显示新名。

系统列 Initiator 仍读冻结的 `start_user_name`（平台现状）。Owner **不**为了迁就它而保存旧名。若产品要求 Initiator 也现查，另开，不在本期 Owner 范围。

---

## 3. 概念模型

`type: "owner"`，`input: true`，`field` **必须**等于该表上已存在的 `dw_field_definitions.field_name`（`VARCHAR`）。

Table Design **没有** `OWNER` 数据类型。Owner 只存在于 form-create rule。

### 3.1 值模型

主值是 **VARCHAR 标量字符串**，禁止 JSON 对象、禁止 JSON 数组。前缀区分「人」和「步骤」，**禁止**把步骤名写成 `user:`（会变成假人名）。

| 形态 | 主值 | `__display` / 展示 |
|---|---|---|
| 一个人 | `user:<userId>` | 按 ID 现查姓名；灰底 tag + 头像 |
| 一堆人（未领） | `user:<id1>,user:<id2>,user:<id3>` | 现查姓名，`", "` 拼接；多个 tag |
| **MI 外层步骤**（仅主表 Case Handler） | `step:<currentStepName>` | **普通文本**（与 Current Step 同文案），**不要**当作用户、不要查用户、不要画头像 |
| 空 | 缺省 / `""` | 表单 / View 显示 `-` |

- 读路径：`user:` 段才走 `parseStoredUserIds` + 现查；`step:` **整段当步骤名**，不要拆成 user。
- `__display` 对 `user:` 只是缓存；对 `step:` 可原样存步骤名。展示以主值前缀为准。
- 不存「待认领」等 UI 措辞。

### 3.2 source 下拉：两值，不是四值

属性面板 Source 用 Element Plus **`el-select` 下拉框**（不要 `el-radio-group`）。

```jsonc
{ "source": "CREATOR" }           // 缺省
{ "source": "CASE_HANDLER" }      // 下拉文案：Case Handler
```

| 不要 | 原因 |
|---|---|
| 做成 `MAIN_CREATOR` / `SUB_CREATOR` / `MAIN_ASSIGNEE` / `SUB_ASSIGNEE` 四个枚举 | 主表/子表已由 `field` 所在表（MAIN / SUB）决定；四值会让设计师在主表上误选「子表 Create」 |
| 按「当前打开的是不是 MI 表单」隐藏选项 | 同一列可出现在发起表单、普通审批表单、MI 待办表单；source 是列级契约，不跟节点走 |

下拉文案：**Creator** / **Case Handler**。属性面板可加一行只读说明，例如主表：「Creator = 行创建预填当前用户；Case Handler = 进行中写办理人，委托办完写实际操作人，MI 写外层框名」。读旧 config `CURRENT_ASSIGNEE` 当作 `CASE_HANDLER`。

### 3.3 四场景写入规则

两种 source 在 Portal **全程只读**，不允许手改。`CREATOR` 行创建时预填当前 user；首次 Save 由服务端写当时操作人，之后不覆盖。`CASE_HANDLER` 按 §3.3.3 / §3.3.4 自动回写。客户端改值一律忽略。`__display` 按主值现查。

人员展开（只用于「写人」的场景：普通节点主表 Case Handler、MI 子表 Case Handler）：

| 任务状态 | 写入 |
|---|---|
| 还没领（候选人池，含 MI `assigneeMode=role` 的 BU+Role 池） | `user:<id1>,user:<id2>,…` |
| 已领 / 已分派到一个人 | `user:<id>` |

主表 Case Handler 的终态见 §3.3.3（**清空**）。子表 MI Handler、Creator **不清空**。

禁止：主值留空只写 `__display`；写成 JSON 数组；写成 `group:`；用系统实例列盖 Owner JSON。

#### 3.3.1 主表 Create / 3.3.2 子表 Create（同一条规则）

主表行、子表行（含 MI collection、后来 +Add 的行）共用：

| 时机 | 行为 |
|---|---|
| 行创建（发起页打开主表 / 子表 +Add 开对话框） | **只读预填**当前登录用户 `user:<currentUserId>` |
| 首次点 Save | 服务端写**当时操作人**（与预填相同，除非会话在打开后换人；**忽略**客户端改值） |
| 该行已落库之后的任何保存 / 流转 / MI | **不覆盖主值** |

- 不要改用实例 `start_user_id` 覆盖 Save 时的字段值。
- 不要用该行 MI 任务 assignee / `assigneeField` 填 Creator。
- 禁止：行已有 Creator 后，审批 / MI 待办发现「想填当前操作人」就改写。

#### 3.3.3 主表 Case Handler（`CASE_HANDLER` × MAIN）

进行中默认 = 主流程任务 **assignee**。委托不改任务 `assignee`，所以委托期间格子里仍是 A。**该节点 Complete 时**改成**实际点完成的人**（B 代 A 办完 → 记 B；A 自己办完 → 仍是 A）。活动定位与 **Current Step** 同一套算法。

| 当前停在哪 | 写入主表 Owner |
|---|---|
| 普通用户任务（进行中） | 该任务 **assignee**：`user:…`（已领一人 / 未领一堆）。委托中仍是 A |
| 普通用户任务 **Complete** | **实际操作人** `user:<actorId>`（委托时即 B 或 A） |
| **任一段 MI**（内层进行中） | **外层框名** `step:<currentStepName>`。不写内层办理人 |
| 这段 MI 结束，下一段还是 MI | 换成新外层框名 |
| 这段 MI 结束，下一段是普通用户任务 | 换成该任务 assignee（进行中）；该步 Complete 后再写成实际操作人 |
| 后面没有普通用户任务，或流程终态 | **清空**（其它规则不变） |

`step:` 展示为普通文本。不要画头像、不要当 `user:` 去查人。

| 时机 | 写入 |
|---|---|
| 进入 / 停留在普通用户任务 | 覆盖为任务 assignee（`user:`） |
| 该普通任务 Complete | 覆盖为**实际操作人** |
| 进入 / 停留在 MI | 覆盖为 `step:<外层框名>` |
| 认领 / 转办 / 领导改派 | 覆盖为新 assignee |
| 委托（任务未 Complete） | **不改**（仍是 A） |
| 流程终态 | 清空 |
| 表单 Save（未 Complete） | 不因委托改成 B |

- 禁止：把内层 MI assignee 写进主表；步骤名写成 `user:multi`。
- 不要改 `TaskAssignmentListener` / MI `AssignmentConfig`。
- **只清空主表 Case Handler。** Creator、子表 MI Handler 终态不清空。

#### 3.3.4 子表 Case Handler（`CASE_HANDLER` × SUB）

**MI collection 行（已确认）：** 表是当前 MI 节点 Sub-Task Config 的 `subTableName`，且行能按 `rowIdVariable` / `currentItem` 对上任务。对不上不要猜列名。

| 行状态 | 写入该行 Owner 列 |
|---|---|
| 有对应未完成 MI 任务 | 该行任务 **assignee**（委托中仍是 A） |
| 该行 MI 任务 Complete | **实际操作人**（B 代办则记 B） |
| 该行已完成、同节点其它行未完成 | 保留该行最后一次写入（含上面的实际操作人），不抄兄弟行 |
| 流程结束 | 保留最后一次写入 |

**非 MI 子表（已确认：方案 B）：** 普通子表没有行级任务，**不自动写** Case Handler，不抄主表。列保持空 / `-`。该表若后来成为某次 MI 的 collection，再按上表写该行任务。离开 MI 之后不抄下一节点主流程办理人、不清空，**保留该行最后一次写入**。

认领 / 取消认领 / 转办 / **领导改派**写该行时必须带任务自己的 `_currentItem`；对不上的行不要改。

不要回写 MI `assigneeField`，不要改 `AssignmentConfig` / `shared.ts` merge。

### 3.4 唯一性（允许多个）

| 范围 | 规则 |
|---|---|
| 一张 MAIN / SUB 表 | **可以有多个** Owner 控件；每个绑不同的 `field` |
| 同一表单 | 同一 `field` 不能出现两次 |
| 同表多张表单 | 同一 `field` 若都是 Owner，**source 必须相同**（禁止一张 CREATOR、另一张 CASE_HANDLER） |
| 列不存在 / 非 VARCHAR / PK / 审计 / 公式列 | 保存失败 |
| 画布 | `only: false`（取消每表一个的画布限制） |

不再禁止「绑到已有业务列」——那是本方案的主路径。
不再保存时自动建列。Table Design 删列后表单仍挂 Owner → 保存失败。

---

## 4. 数据契约

### 4.1 `props.ownerConfig`

```jsonc
{ "source": "CREATOR" }           // 缺省
{ "source": "CASE_HANDLER" }
```

JSON 字符串，对齐 `lookupConfig`。非法 JSON / 未知 source → 保存失败。运行时禁止 fallback 成 `input`。读旧值 `CURRENT_ASSIGNEE` 当作 `CASE_HANDLER`。

忽略（本期不读、不写进新保存的 config）：`allowGroup`。读旧 config 时若只有 `allowGroup`、没有 `source`，视为 `CREATOR`。

### 4.2 form-create rule

```jsonc
{
  "type": "owner",
  "field": "current_handler",   // 必须已是该表 VARCHAR 列
  "title": "Case Handler",
  "props": { "ownerConfig": "{\"source\":\"CASE_HANDLER\"}" }
}
```

- `mapDataType("owner")` 仍为 `VARCHAR`（粘贴补表等路径）；**日常保存不再 provision 新列**。
- 控件类型存在于 rule，不存在于 `dw_field_definitions.data_type`。

### 4.3 运行态（字段 JSON，主表 variables / 子表行同形）

一个人（已分派 / 已认领）：

```jsonc
{
  "case_owner": "user:u-initiator",
  "case_owner__display": "Alice",
  "current_handler": "user:u-bob",
  "current_handler__display": "Bob"
}
```

source = Case Handler 且任务未领（一堆人）——ID **写在 Owner 列主值**，不是空字符串：

```jsonc
{
  "current_handler": "user:u-lina,user:u-zhang",
  "current_handler__display": "李娜, 张三"
}
```

主值不是 JSON 数组。界面按逗号拆 `user:` 段成多个灰底 tag。姓名缓存 `__display` 用 `", "` 拼接；展示以按 ID 现查为准。

### 4.4 View

列本来就在 Table catalog 里（设计师建过字段）。无新 `columnType`。
设计师要在 View Design **勾上**这些列；存量 View 不会自动追加。

**展示：** 读 **本列主值** 里每个 `user:<id>`，按 ID 现查姓名（页内 batch）。不要用过期 `__display`，也不要用系统 Current Assignee / 冻结 `start_user_name` 盖掉本列。单元格是普通列表文字（可带与 Lookup 只读态同款的灰底姓名 tag + 头像），不是独立系统列。

**筛选 / 排序（本期）：** SQL 走 `variables->>'field'`。一个人可等值 `user:<id>`；一堆人是逗号拼接，等值筛会漏，MVP 以展示为准。按「是否包含某 user」另开，禁止 silently 滤错。

HSWORKFLOW-815：主表 VARCHAR 做成 Owner + `CASE_HANDLER` 再勾进 View，可以在 View **看到同类办理人信息**。那是 Owner 字段，**不是**系统 Current Assignee 列。

---

## 5. 设计器行为

### 5.1 产品步骤

```text
Table Design 建 VARCHAR 列
  → 表单把该字段拖上画布（input）
  → 属性面板将控件类型改为 Owner，**下拉框**选择 Creator 或 Case Handler
  → 保存（对账：列存在、类型合法、同列 source 一致；主表/子表都允许 Creator 或 Case Handler）
  → View 勾选该列
```

属性面板需要「控件类型」：至少 Input ↔ Owner（仅 VARCHAR）。选 Owner 后出现 Source **下拉框**（`el-select`，不要 radio）。
`useTableFieldRules` 按 dataType 生成 `input` 时，**不得覆盖**已保存的 `type:"owner"` / `ownerConfig`。

Palette 仍可保留 Owner 作为快捷方式，但 `field` 必须改成已有列名才能保存；`only: false`；不自动建列。

### 5.2 落点

| 步骤 | 文件 |
|------|------|
| 注册 | `frontend/developer-workstation/src/main.ts`（`only: false`；`ownerConfig` 含 source） |
| 属性 | `OwnerConfigEditor.vue`：Source 用 **`el-select` 下拉**（`CREATOR` / `CASE_HANDLER`，文案 Case Handler）；可按 MAIN/SUB 显示一行说明；去掉「每表一个 / 允许选组」作为主交互 |
| 改类型 | FormDesigner 属性面板 Control type（VARCHAR → Owner） |
| Hydration | `useTableFieldRules.ts`：保留已有 owner rule |
| Preview | `useFormPreviewColumns.ts` |
| 子表类型 | `designerSubTableField/types.ts` |
| 对账 | DW 新增 `OwnerFieldFormReconciler`（fork 上若已有则按 §3.4 改语义，禁止保留每表一个 / 自动建列） |

### 5.3 View 设计器

无新系统列。普通字段目录勾选即可。

---

## 6. Portal 运行时

### 6.1 渲染与风格：`OwnerField.vue`

两种 source **全程只读**，无选人下拉。非法 `ownerConfig` 报错，禁止 fallback 成输入框。`user:` 现查姓名（灰底 tag）；`step:` 普通文本；空显示 `-`。

**样式必须跟现有系统，禁止另做一套皮肤。** 对齐 Portal `LookupField.vue` 只读态 + `ws-theme.scss` 令牌；DW Preview 与 Portal 同结构（`portal-design-parity`）。

| 元素 | 跟谁抄 |
|---|---|
| 外框 | Lookup 输入框：高 32px、边框 `#dcdfe6` / `--el-border-color`、圆角 `var(--ws-radius-input, 8px)` |
| 只读 | Lookup `.is-readonly`：背景 `--el-disabled-bg-color` `#f5f7fa`，禁点击 |
| 已选姓名 | Lookup `.lookup-selected-tag`：高 24px、灰底 `#f0f2f5`、圆角 4px、字 13px `#909399` |
| 头像 | 可保留圆形字头（22px），主色 `--primary-color` `#db0011`；不要大面积自定义 pill / 自绘 Tab |
| View 单元格 | 普通表文字，或同一灰底 tag；不要在列表里用弹层/下拉 |
| DW 属性 Source | Element Plus **`el-select` 下拉框**（不要 radio、不要自绘选项条） |

不要：独立配色圆角、可点开的选人面板、User/Group radio 作为主界面。Creator / Case Handler 合入前都必须是只读 Lookup 态。

池任务多名：一个字段内多个灰底 tag，或一条 `name1, name2, name3` 文本（与 My Requests 同文案）；不要用组建筑图标冒充 BU+Role。

### 6.2 提交：`OwnerFieldComponent.applyOnSubmit`

按该字段的 `source` 分支，**禁止**再对所有 Owner 统一「空才填当前 userId」：

- `CREATOR`：按 §3.3.1 / §3.3.2。该行尚未有主值 → 服务端写当时操作人。已有主值 → 保留。客户端改人一律忽略。
- `CASE_HANDLER`：按 §3.3.3 / §3.3.4。提交不是唯一写点，见 §6.3。

客户端提交的 `__display` 仍不信任，以服务端按 ID 现查为准。

Creator **不要**用 `startUserId` 覆盖 Save 时的字段值。

### 6.3 按办理规则回写 Owner 列（仅 `CASE_HANDLER`）

任务认领 / 取消认领 / 转办 / **领导改派（BU Role 池）** / 节点切换 / **普通任务或某条 MI 子任务 Complete**时，按 §3.3.3 / §3.3.4 重算并写进该实例上所有 `source=CASE_HANDLER` 的 Owner 列（主表 variables + 已有子表行）。MI 内层任务必须带上该任务的 `_currentItem`，只改对上的 Participants 行。可以挂在现有 `ProcessInstanceSyncComponent` 同类写点之后，但写入目标是 **Owner JSON**，不要改系统实例列的语义，也不要把系统列和 Owner 当成同一字段。

**委托（Delegate）与转办（Transfer）分开写：** 平台委托**不改**任务 `assignee`（单仍挂 A，B 代 A 办）。见 [portal-task-single-delegate.md](./portal-task-single-delegate.md)。

| 事件 | Case Handler 写什么 |
|---|---|
| 委托发生、任务尚未 Complete | **不改**（仍是 assignee A） |
| **该节点 Complete** | 写**实际操作人**（B 点完成 → `user:B`；A 自己完成 → `user:A`）。这一步必须进 `_snapshot_{taskId}` |
| 转办 | 覆盖为新 assignee |
| Complete 之后流程已走到下一节点 | 主表按 §3.3.3 换成下一节点的人 / `step:` / 清空。上一节点「谁办完的」只在快照里 |

**实际操作人** = Complete 请求的服务端操作人（会话身份），不是客户端自报、也不是任务 `assignee`。未领池任务被某人办完 → 记该操作人。

MI 期间：主表列写 `step:<外层框名>`，不写内层办理人；下一段普通节点再换成 `user:`；终态清空。MI collection 各行写**该行任务**；已完成行保留上次写入。

不要在 `TaskAssignmentListener` 里写 Owner。
不要改非 Owner 列。
不要改 `source=CREATOR` 列。

**不要**在 My Requests 列表 enrich 里回写 Owner JSON。View / 表单读 Owner 列自己的主值。

### 6.4 子表与 MI

- `CREATOR`：§3.3.1 / §3.3.2。行创建预填当前 user，首次 Save 落库，之后不覆盖。
- `CASE_HANDLER`：**允许。** 仅 MI collection 行写该行任务（进行中 assignee，Complete 实际操作人）。非 MI 子表不自动写（方案 B）。
- 行↔任务对齐只读 Sub-Task Config（`subTableName` / `rowIdVariable` / `currentItem`），禁止硬编码列名。
- 触达 `SubTableField.vue` / 对话框只加只读展示。`regression:mi` 全套。不改 `AssignmentConfig` / `shared.ts` merge。

### 6.5 View

- 两种 source 都读 **本列主值**（`user:` 现查姓名；`step:` 当文本）。页内 batch，禁止逐行 HTTP。不要用过期 `__display`，也不要用系统 Current Assignee 盖本列。
- 空主值显示 `-`。
- View / My Request 主数据仍是**当前活数据**（主表 Case Handler 会随节点变）。历史值只在 §6.6 的已完成节点回看。

验收必须刷 View，不能只看详情。改名后活数据里的 `user:` 应显示新名（ID 仍是原列表）。`step:` 不查人。

### 6.7 申请详情 Basic Info / My Request 列表 = 主表 Case Handler

**已确认：** 下列「系统 Current Assignee」**展示**与主表 Owner `CASE_HANDLER`（§3.3.3）同一套逻辑，一直显示「主表现在该显示的东西」（进行中人 / `step:` 外层框名 / 空）：

| 入口 | 页面 |
|---|---|
| My Request 打开申请 | `applications/detail.vue` Basic Info → Current Assignee |
| Data / View 点主表行且打开的是申请详情（`/applications/:id?from=views`） | **同一页** Basic Info → Current Assignee |
| My Request 列表「当前办理人」列 | 同一套展示（避免列表还是旧的内层人、头上已是步骤名） |

**同一套**的含义：一个计算结果，两处消费。有主表 Owner Case Handler 列就和它一致；没有配 Owner 列的 FU，头上/列表仍按 §3.3.3 **现算**，不要求先放控件。禁止再写一套「读实例 `current_assignee` / 当前查看者任务」的展示。进行中委托时两边都仍是 A；点已完成节点看快照才是实际操作人。MI「内层任务名 → 外层框名」只解析一次（`MiOuterStepResolver`），列表展示和 Owner 写入共用这张表；列表显示 `multi`，Owner 列存 `step:multi`，不要把列表文案原样写入列。

**不要改：** To Do / Completed **任务**详情 `TaskBasicInfo` 的 Current Assignee —— 那是**当前这条任务**的办理人（MI 待办里就是这一行的人）。

Data 点进若落到没有 Basic Info 的表单详情页（`/views/.../detail`），不另做一头；表单上的 Owner 字段按 §6.1 / §6.6 显示。

### 6.8 边角（已认可）

1. **退回 / 撤回：** 主表 Case Handler 按「现在停在哪」重算（回到普通节点 → 该任务 assignee；仍在 MI → `step:`；没了 → 空）。Creator 不改。已完成节点快照不因退回改写。
2. **草稿未正式发起：** Creator 仍是该行**首次 Save** 的操作人；主表 Case Handler 在尚未进入用户任务前为空。
3. **View 筛选 `step:`：** 本期只保证能看见步骤名；按步骤名筛另开，禁止假装能按人筛到 `step:` 值。

### 6.6 已完成节点回看：必须用该节点快照里的 Owner

产品要求：在 **To Do** 和 **Completed Task** 里，流程图点开**已经完成的节点**，Owner 必须显示**该节点办完当时**冻住的值，不能显示现在的活数据。

否则主表 Case Handler 进了 MI 变成 `step:multi`、终态被清空后，点回「经理审批」会看到错的（或空的），看不到当时办完的人（委托时应是实际操作人，不是原 assignee）。

| 你点的是 | Owner 从哪读 |
|---|---|
| **当前未完成待办**（To Do 当前任务） | **活数据**（§3.3 自动回写后的当前值） |
| **已完成节点**（To Do 流程图点旧节点；Completed 打开该任务或再点其它已完成节点） | 该任务的 `_snapshot_{taskId}.fieldValues`（及快照里的 `__subTables__` 同行 Owner 列） |
| 节点已完成但没有 `_snapshot_{taskId}`（老实例 / 当时表单没冻到） | Owner 显示 `-`。**禁止**用活数据顶上（否则又变成当前步骤的人/框名） |

**快照必须带上 Owner：** 任务 Complete 时写入 `_snapshot_{taskId}` 的字段子集，只要该节点表单上有 `type:"owner"`，对应主表列和子表行列都要进 `fieldValues` / `__subTables__`。不要因为只读 / 自动赋值就漏掉。

**点节点怎么对上快照：** 用流程图节点的 `taskDefinitionKey` / `activityId` 对流程历史里**已完成**任务的 `taskId`（同一节点循环多次 → 取该节点最后一次完成）。读 `variables['_snapshot_' + taskId]`。不要改非 Owner 字段的现有取数（To Do 点旧节点其它字段仍可走活数据）；**只把 Owner 键用快照盖上去**。

**展示：** 快照里的 `user:` 仍按 ID **现查**姓名（人改名后历史节点也显示新名，ID 不变）。`step:` 显示冻住的步骤名文本。

不要为了回看去改 `shared.ts` merge / MI `AssignmentConfig`。

---

## 7. 后端

- 无新 HTTP 端点、无新表 / 新物理列 / 新 env。业务表仍是 JSON 行存储。
- DW：`OwnerFieldFormReconciler` 按 §3.4；`FormCreateRuleToFieldMapper` 保留 `owner → VARCHAR` 供粘贴路径。
- Portal：`OwnerFieldComponent` 按 source 分支；同步写点见 §6.3。
- **禁止**再实现「所有 Owner 都不碰 assignee 写点」或「所有 Owner 都跟 assignee」。

---

## 8. 影响面

| 层级 | 变更 |
|------|------|
| 文档 | 本文 + `docs/design/README.md` Owner 节 |
| DW 前端 | palette `only`；`OwnerConfigEditor` **el-select** source；控件类型切换；hydration 保 type |
| DW 后端 | **新增** `OwnerFieldFormReconciler`（按 §3.4；fork 旧文件则改语义） |
| Portal 前端 | `OwnerField.vue` 只读；To Do / Completed **已完成节点**用 `_snapshot_{taskId}` 盖 Owner（§6.6） |
| Portal 后端 | `OwnerFieldComponent`；同步写点；Complete 快照子集必须包含 Owner 列 |
| i18n | Source 选项、控件类型、校验错误（en / zh-CN / zh-TW） |
| 测试 | 对账多列 / 必须已有列 / 跨表单 source；Creator 不被审批人覆盖；转办更新 Case Handler、不动 Creator；委托期间仍是 A，Complete 后快照是实际操作人 |

**禁止改：** `platform-common` 语义、`AssigneeType` / `TaskAssignmentListener`、MI `AssignmentConfig`、`shared.ts` merge（除非渲染强制，先报告）。

---

## 9. 分期

**本期（本文 MVP）：** 先建列、改类型、Source **下拉**二选一、主表+子表四场景、可多个。Create = 创建预填当前 user、Save 落库。主表 Case Handler：进行中 assignee，Complete 实际操作人，MI `step:<外层框名>`，终态清空。子表 Case Handler 仅 MI 行。不与系统 Current Assignee 合成。

**后续（另开）：** 手改派 / BU+Role 组；「owned by me」；独立 Assign；系统列 Current Assignee 自己的增强。

---

## 10. 风险与回滚

| 风险 | 处理 |
|------|------|
| 审批保存把 Creator 改成当前操作人 | 仅行创建预填 + 首次 Save 落库；之后不覆盖。单测：审批保存不得改已有 Creator |
| 改名后 Owner 仍显示旧名 | 表单 / View 读路径按 ID 现查；`__display` 只是缓存。单测：改 displayName 后 Owner 显示新名、主值 ID 不变 |
| Case Handler 与系统 Current Assignee 读路径混成一套存储 | 禁止合成存储。展示可共用 §3.3.3 算法 |
| 转办不经表单，Owner Case Handler 列不更新 | 同步挂在任务办理写点，回写 Owner JSON，不挂在表单提交 |
| 委托办完后快照仍是 A | Complete 必须写实际操作人进 Owner 列并进 `_snapshot_{taskId}` |
| To Do 点旧节点看到当前 Case Handler | 已完成节点 Owner 只读 `_snapshot_{taskId}`，禁止用 live `formData` 盖 |
| 池任务主值留空 / 只写 display | 主值写 `user:id1,user:id2` |
| 子表不许选 Case Handler | 约束已作废；对账允许 SUB |
| MI 内层办理人 / 步骤名写成 `user:` | 主表 MI 只写 `step:<currentStepName>`，展示当文本 |
| MI 已完成行被兄弟办理人覆盖 | 该行保留最后一次写入 |
| 子表 Creator 写成 MI assignee | Creator 只走「创建预填 + Save 落库」 |
| 用 `startUserId` 覆盖 Save 时的 Creator | 禁止；以首次 Save 字段值为准 |
| hydration 把 owner 冲回 input | 已保存 type 优先 |
| 按姓名筛 View | MVP 不保证；SQL 滤的是 `user:<id>`。勿假装滤 display |
| 视觉自成一套 | 对照 Lookup 只读态 + `ws-theme` 截图验收 |
| fork 把 08-17 合进 origin | 禁止。合入必须以本文为准 |
| 同列两张表单 source 不一致 | 保存失败 |

回滚：表单把控件改回 input 即停止自动赋值；JSON 键保留，View 仍可读。

---

## 11. 验收

**反例：** 只能拖一个自动建列的 Owner；无法在同一表单同时展示发起人列和当前办理人列；转办后「归属」和「办理人」无法分开；Creator 在审批保存后变成审批人。

**正例：**

1. Table Design 建 `case_owner`、`current_handler`（VARCHAR）→ 主表或子表表单改成 Owner，属性 **下拉**选 Creator / Case Handler → 保存成功。
2. **Create：** 打开发起页 / 子表 +Add 时只读预填当前 user；点 Save 后表里是当时操作人；控件不可手改；之后审批再保存不改。
3. **主表 Case Handler：** 普通节点进行中 = 任务 assignee。进入 MI = `step:<外层框名>`（与 Current Step 同文案，不是人头）。下一段还是 MI → 换新框名；下一段普通节点 → 换成人；流程结束或后面没有普通节点 → 清空。
4. **子表 Case Handler：** 仅 MI collection 各行：进行中写该行 assignee；Complete 写实际操作人；一行完成后不被兄弟行覆盖。普通子表（非 MI）该列保持空 / `-`，不抄主表。离开 MI 后保留该行最后一次写入。
5. 转办（普通用户任务）→ 主表 Case Handler 变、Creator 不变。**委托未 Complete → 仍是 A**；**该节点 Complete → 记实际操作人 B**（进列并进快照）。MI 内转办内层任务 → 只改对应子表行，主表 Case Handler 不变。
6. To Do / Completed 点已完成节点：Owner 是该节点 Complete 时快照（委托办完应是 B，不是 A；无委托则是办完的人），不是当前活数据（例如现在的 `step:multi` 或空）。当前待办仍看活数据。无快照则 Owner 为 `-`，不拿活数据顶。
6b. My Request / 从 Data 点进的申请详情 Basic Info Current Assignee，与主表 Owner Case Handler 一致（进行中委托仍是 A；MI 时是外层框名）。To Do 任务头 Current Assignee 仍是本任务的人。
7. 未在 Table Design 建列就放 Owner → 保存失败。
8. 同表第二张表单把 `current_handler` 设成 Creator → 保存失败（source 冲突）。
9. 同一表单可以有两个 Creator 列（绑不同 field）或两个 Case Handler 列；不报「每表只能一个」。

---

## 12. 验证

```bash
cd frontend/developer-workstation && pnpm run build
cd frontend/user-portal && pnpm run build
mvn -pl backend/developer-workstation,backend/user-portal -am test
cd frontend && pnpm run regression:mi
cd deploy/environments/dev && docker compose -f docker-compose.dev.yml --env-file .env \
  up -d --build user-portal user-portal-frontend developer-workstation developer-workstation-frontend
```

单测至少覆盖：§3.4 对账；Create 预填当前 user、首次 Save 落库、再次保存不覆盖；§3.3.3 主表 Case Handler 进行中 `user:`、MI 为 `step:`、终态清空、Complete 写实际操作人；§3.3.4 MI 子表行级任务互不抄写；§6.6 已完成节点 Owner 读快照不读活数据、无快照不兜底；委托未 Complete 仍是 A、Complete 快照是 B；未领写入多 `user:`；子表允许 `CASE_HANDLER`；读旧 `CURRENT_ASSIGNEE` 当 `CASE_HANDLER`；`mapDataType("owner") == VARCHAR`；非法 source。

UI：DW 属性面板 Source **下拉框**；Portal **只读 Lookup 风格**（灰底 tag + 头像，32px 输入框）；View 两列姓名。截图进 `verification-screenshots/`，对照同页 Lookup / 普通只读输入。

---

## 13. 代码落点（实现时）

**DW：** `OwnerConfigEditor.vue`（**`el-select`**）；`main.ts`；`useTableFieldRules.ts`；**新增** `OwnerFieldFormReconciler.java`（及测试）；FormDesigner 控件类型。

**Portal：** **新增** `OwnerFieldComponent`；`ProcessInstanceSyncComponent` + 发起 assignee 更新；`OwnerField.vue` / `OwnerChip.vue`（只读、抄 Lookup + `ws-theme`）；View 投影现查。

**不要改：** `TaskAssignmentListener`、`AssigneeType`、MI `AssignmentConfig`、`shared.ts` merge。

**不要把 fork 上未合并的 08-17 Owner 行为带进 origin。**

---

## 14. 已确认（2026-08-19，2026-08-21 修订，2026-09-07 补只读 / Case Handler 规则）

1. 先 Table Design 建字段，再在表单把控件改成 Owner（不是拖组件自动建列）。
2. 组件上两个选项：Creator / **Case Handler**（`CASE_HANDLER`）；**属性面板用下拉框**。两种 source **全程只读**，不允许手改。旧 config `CURRENT_ASSIGNEE` 当 `CASE_HANDLER`。
3. Creator：行创建只读预填当前 user，首次 Save 服务端写当时操作人；之后不覆盖。不是 `start_user_id`，也不是办理人。显示名按 ID **现查**。
4. 主表 Case Handler：进行中 = 任务 assignee `user:`；**该节点 Complete = 实际操作人**；MI = `step:<外层框名>`；终态清空。子表仅 MI 行。**申请详情 Basic Info / My Request 列表 Current Assignee 与主表 Case Handler 同一套逻辑**（含从 Data 点进 `/applications/:id`）。To Do 任务头仍是本任务办理人。
5. **不限制**一个表单 / 一张表只能一个 Owner。
6. 字段进 View（勾选已有列 + `__display`），不为 815 单独加系统列。
7. 不合成一列、不反写任务分派、不改 MI `AssignmentConfig`、不控制行可见性。
8. 手改派 / `allowGroup` 退出本期 MVP。
9. **origin 无 Owner 代码**；08-17 实现未合入。从 origin 新增，或把 fork 文件改成本文后再合。
10. **控件样式抄现有系统**（Lookup 只读态 + `ws-theme`），不要新皮肤。
11. **2026-08-21：** 未领时 Owner 主值是 `user:id1,user:id2`，不是空主值 + 仅 display。子表允许 Case Handler。`OWNER_ASSIGNEE_ON_SUB` 作废。
12. **2026-09-07：** Source **不**拆成四个枚举。两种 source **全程只读**。Create = 只读预填当前 user，首次 Save 写当时操作人。主表 Case Handler：进行中 `user:`，MI `step:<Current Step 名>`，终态清空。子表 Case Handler **仅 MI 行**；非 MI 不自动写（方案 B）。
13. **2026-09-07：** To Do / Completed 点**已完成节点**，Owner 读该任务 `_snapshot_{taskId}`，不读活数据。Complete 快照必须包含表单上的 Owner 列。无快照显示 `-`，禁止活数据兜底。
14. **委托未 Complete：Case Handler 仍是 assignee A。该节点 Complete：改写成实际操作人**（B 代办则记 B），并进快照。转办才改进行中的默认值。
15. 申请详情 Basic Info / My Request 列表 Current Assignee = 主表 Case Handler 同一套逻辑。退回/撤回按当前节点重算；草稿 Handler 为空；View 对 `step:` 本期只展示。

---

确认本文后若要开工，回复：

```text
按 playbook 执行（先输出任务整理，等我确认）。
```
