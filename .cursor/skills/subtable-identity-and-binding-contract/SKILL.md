---
name: subtable-identity-and-binding-contract
description: >-
  子表的「身份」与「绑定」两条契约：哪一列标识一行、哪一列指向父表、这张表是不是 MI 参与者表——
  三者都只能读设计器配置，不能猜列名、不能看值长什么样、不能靠 @Builder.Default 顺带。
  适用于改 row identity / primaryKeyFields / foreignKeyField / bindingLinkMode / __subTables__ 行匹配，
  以及 Clone / Copy Form / Import / Rollback / 粘贴表单配置等任何"重建 binding"的路径。
  触发词：子表主键、行身份、platformRowUuid、row_id、外键没标记、Structural FK Fields、
  bindingLinkMode、miParticipantRow、克隆丢配置、复制表单丢配置、子表行匹配不上、
  sub-table identity、foreign key not declared。
---

# 子表身份与绑定契约

本 skill 记录 2026-09 一轮排查的**业务契约**与**全部 hardcode 清单**。

与 `config-over-heuristics` 的分工：那个讲**怎么做**这类改造（方法、踩坑、验证顺序）；
本文讲**正确答案是什么**（契约）+ **哪里曾经写错**（清单），避免重新推导一遍。

---

## 1. 三条业务契约（背下来）

### 契约 A：一行的身份 = 配置主键，其次平台 UUID

```
设计器配置了主键 (dw_field_definitions.is_primary_key)  → 用它，列名是设计师起的
没配主键                                                  → 用平台生成的 UUID
两者都没有                                                → 判不出，返回 null；不许猜
```

- 平台键常量：后端 `SubTableRowIdentity.CANONICAL_FIELD`、前端 `PLATFORM_ROW_UUID_FIELD`，
  值都是 `platformRowUuid`。**两端必须同名**，改一边就是静默分叉。
- **主键列名可以是任何东西**：实测有 `correspondence_id`、`case_number`、`id_idwxwcxmw`、
  `idqcxma`、`row_id`。任何「像主键的名字」白名单都是错的。
- 主键值也可以是任何形状：`pk_generation_json.strategy` 有 `uuid` 和 `prefixedSequence`
  （`Corr-000004`、`Test-000017`）。**用 UUID 正则判「有没有分配主键」必然在
  prefixedSequence 的表上恒假**——这是最初 ACQ Correspondence 新增行被折叠的根因。

### 契约 B：子表必须有外键，可以没有主键

```
所有 SUB 表  →  必须有一列标记 isForeignKey（指向父表）
             →  主键可选；不配就用平台 UUID 区分行
```

- 设计器**已经在强制**：`useTableBindingForm.ts` 的 `structuralFkRequired`
  （提示语 "Sub-table binding requires a foreign key field"）。
- 后端补了 `SUB_FK_NOT_DECLARED` 校验（`FormDesignComponentImpl`），判据是
  **「这次请求是否改变 FK 值」**，不是「create 还是 update」——
  见下面「曾经写错 #8」。
- 推论：**没有 FK 标记的子表 = 违反契约的脏数据**，不是"另一种合法配置"。
  遇到它应当跳过 + 给出可操作日志，不要发明一个列名糊过去。

### 契约 C：没有 FK 标记 ⇒ 一定不是子任务表

```
有 FK 标记  →  可能是 MI 参与者表，需要判定
无 FK 标记  →  structuralFk（挂主表），确定
```

这是用户给的领域规则，2026-09 实测双向成立（当时 dev：8 张无标记表全是 structuralFk；
4 张 MI 表全都有 FK 标记）。

**注意这不是 else 兜底**：「配置的缺失本身就是答案」，与"判不出随便给一个"性质不同。

### 契约 D：`Structural FK Fields` 这一栏是派生显示，不是独立配置

设计器弹窗里那一栏完全由 `isForeignKey` 算出来：

```ts
const structuralFkFieldNames = computed(() =>
  selectedTableFields.value.filter(f => f.isForeignKey).map(f => f.fieldName)
)
```

所以 `binding.foreign_key_field` 只是它的缓存。**标记没了/没打过，存的值就成了孤儿**
（2026-09 实测 33 个 SUB binding 存着一个未被标记为 FK 的列名，弹窗里那栏是空的）。
→ 真源永远是 Table Design 的 `isForeignKey`。

---

## 2. 曾经写错的地方（全清单）

每条都是真实修过的 bug。**新增同类代码前先扫一遍这张表。**

| # | 位置 | 错法 | 实测后果 |
|---|---|---|---|
| 1 | `miLinkChildRows.ts` | UUID 正则判「有没有分配主键」 | `prefixedSequence` 主键的表恒假 → 同一参与者的多行被合并成一行 |
| 2 | `miLinkChildIdentity.ts` ×13 + `miLinkChildScrub.ts` | 同上（`isAllocatedUuidPrimaryKey`） | MI 参与者表 `subtable` 正是 prefixedSequence，判据在最关键的表上恒假 |
| 3 | `miLinkChildIdentity.scoreMiLinkChildRowQuality` | **内联**一份同样的 UUID 正则 | grep 函数名漏掉；真主键行只得 40 分，可能输给垃圾行 |
| 4 | `SubTableRowIdentity.IDENTITY_FIELDS` | `['row_id','rowId','id_idw','id',…]` 猜名单 | `id` 在 13 张表是业务列且无一是主键 → 两行业务 id 相同被判同一行 |
| 5 | 前端 `subTableRowIdentity.ts` | 同名单 + 写 `row_id` | 后端改名 `platformRowUuid` 后两端静默分叉，Change History 把编辑读成删+增 |
| 6 | `FormTableBindingRestorer.inferForeignKeyField` | 猜 `row_id`→`case_id`→`row_id` 兜底 | 15 张子表猜对 2 张；其余建出的 binding 的 FK 指向不存在的列 |
| 7 | `FormTableBindingRestorer` link mode | `"row_id".equals(fkField) ? MI : structuralFk` | 14 个 MI binding 里 7 个 FK 不叫 row_id → 还原成 structuralFk，参与者隔离失效 |
| 8 | `FormDesignComponentImpl` 新校验（我自己的第一版） | 按 `creating` 区分 | `updateBinding` 能改 `foreignKeyField` → 干净 binding 被重指到未声明列仍放行 |
| 9 | `FunctionUnitCloner` | 漏写 `.bindingLinkMode(...)` | `@Builder.Default` 把"漏写"变成"替换成默认值"；克隆 6 MI binding → 0 |
| 10 | `copyTaskForm` / `copyProcessToTaskForm` | 同 #9 | 点一次「复制表单」MI 就退化 |
| 11 | `FormConfigJsonTableProvisioner` 自动建表 | 建了 FK 列但不打 `isForeignKey` | **这就是 8 张无标记表的出生方式**（还在持续生产） |
| 12 | 同上，匹配到无 FK 的已有表 | 落进 createSubTable | 静默新建一张近似重复的表 |
| 13 | `SubTableRowKeySupport` ×4 处 | `id` ⇄ `id_idw` 互相顶替 | 只对主键正好叫这两个名字的表有效；字段名是按表生成的 |
| 14 | `subTableRowMerge.ts` | `['id','row_id','rowId']` 兜底 | 同 #4 |
| 15 | `SUB_TABLE_STRUCTURAL_FK_KEYS` | 两份手抄副本**已漂移** | 一份少 `id`/`main_id` → 同一行在 To Do 与 My Request 对「算不算空行」答案相反 |

### `@Builder.Default` 陷阱（#9 #10，单独强调）

```java
@Builder.Default
private BindingLinkMode bindingLinkMode = BindingLinkMode.structuralFk;
```

**从 source 逐字段拷贝时漏写一个字段 ≠ 留空，而是静默替换成默认值。**
所以「复制一个实体」必须逐字段核对，不能凭"看起来都拷了"。

核对方法：列出实体全部 `private` 字段，逐个在 builder 里 grep。
2026-09 全仓有 **10 个** `FormTableBinding.builder()` 调用点，我第一次只审了 3 个。

合法的例外要写注释说明，例如：
- `subListViewId` 故意不拷（要重新指向克隆出的 config，拷源 id 会悬空）
- AI 生成的 binding 没有 link mode 概念，取默认是对的

---

## 3. 判不出来时怎么办

按「错了会怎样」决定，**不要统一用一个策略**：

| 场景 | 正确做法 | 理由 |
|---|---|---|
| 行身份判不出 | 返回 `null` / 空集 | 下游按位置配对，最差是配错一行；猜列名会把两行不同的行合并 |
| 子表没有 FK 标记 | **跳过重建** + 可操作日志 | 建出来的本来就是坏 binding；stale 占位符看得见，用户能修 |
| link mode 判不出 | 读同表其它 binding；再不行 `structuralFk` | 契约 C：能推导，不是兜底 |
| 授予访问权的判据 | 保持 fail-closed | 看不见自己的数据 ≪ 别人看见你的数据 |

日志要写**用户能执行的下一步**，不是"resolve failed"：

> `sub-table 'X' (id N) has no field marked as a foreign key; mark the parent-referencing column in Table Design`

---

## 4. 仍然合法的「像硬编码」的东西

别把这些也删了：

- **平台枚举**：`PRIMARY`/`SUB`/`RELATED`、`structuralFk`/`miParticipantRow`、`EDITABLE`——
  后端 enum 定义的闭集，不是逐 FU 配置。
- **平台写入的值**：`IN_PROGRESS`/`COMPLETED` 等状态**值**由后端自己 put。
  列**名**是配置（已治理），列**值**是契约。
- **平台 envelope 结构**：lookup 值对象的 `{id, …}`、`rowKey`、`rowId`——平台自己的信封格式。
- **`sys_users`** / `-1000000001`：平台虚拟表常量。
- **`SubTableRowKeySupport` 里由 `pkCols` 驱动的分支**：`col` 来自配置，只是同一个键的
  两种表示映射。删它会被 MI 门禁挡下（实测）。
- **"这列算不算业务数据"类名单**（`SUB_TABLE_ROW_META_KEYS` 等）：没有单一配置源，
  判宽判窄只影响"算不算空行"，不会错配行。

判据：**猜错会不会让两行不同的数据被当成同一行 / 走错业务分支？**
会 → 必须读配置；不会（只影响显隐、排序、算不算空）→ 可以留名单。

---

## 5. 验证（这类改动单测证明不了）

单测绿 ≠ 修好。2026-09 这轮踩了三次：

1. **测试可能是空的**。第一版 restorer 测试用**被我改坏的旧逻辑**跑也能过——
   fixture 的 columns 放错位置（应在顶层 `subListViews` 按 bindingId 索引），
   在更早的分支就退出了。
   → **新写的回归测试，必须先临时改坏产品代码证明它会失败。**

2. **fixture 可能模拟了产品不允许的状态**。子表不标 FK 的 fixture 违反契约 B，
   改完逻辑后它触发的是 `@Data` 双向引用的 `toString` 无限递归，不是业务失败。
   → 日志里**只打 id/name，绝不打实体**。

3. **同类 bug 往往不止一处**。修完 `FunctionUnitCloner` 以为完事，
   用户追问后才发现 `copyTaskForm`/`copyProcessToTaskForm` 一模一样。
   → 修完一处，**grep 同一个调用形态的全部位置**。

真机验证要做「修复前复现 + 修复后保真」两次：

```
Clone FU(6 MI binding) 旧镜像 → 16 binding / 0 MI   ← bug 复现
Clone FU 新镜像              → 6 MI / 10 structural ← 与源一致
```

FU 生命周期改动必须覆盖：**Clone · Export · Import · Version/Rollback · Copy Form**
（见 `function-unit-portability`）。

---

## 6. 自检清单

改任何涉及子表身份/绑定的代码前：

- [ ] 我判断"这一行是谁"用的是**配置主键**，不是列名白名单、不是值的形状
- [ ] 我判断"哪列指向父表"读的是 `isForeignKey`，不是 `row_id`/`main_id`/`case_id`
- [ ] 我判断"是不是 MI"读的是 `bindingLinkMode` 或契约 C，不是 FK 叫什么名字
- [ ] 从实体拷贝时，我**逐字段**核对过 builder（特别是有 `@Builder.Default` 的）
- [ ] 我 grep 过同一调用形态的**全部**位置，不是只改我先看到的那个
- [ ] 判不出时我返回 null / 跳过，并给了用户能执行的日志——没有发明一个默认值
- [ ] 新写的测试，我临时改坏产品代码验证过它真的会失败
- [ ] 前后端各有一份的常量（如 `platformRowUuid`），我两边都改了
