---
name: config-over-heuristics
description: >-
  把「靠列名/表名/字段名猜」的运行时判据换成「读设计器配置」，并安全地删掉猜测兜底；
  §10 另记录本仓库子表「身份 / 外键 / MI 判定」三条契约的正确答案与历史写错点清单。
  适用于症状"只在某些 Function Unit 复现""改个字段名就坏""同一个 bug 反复回来"，
  任何 `xxx === 'some_column'` / 名字白名单 / 正则匹配字段名决定业务语义的代码，
  以及改 row identity / primaryKeyFields / foreignKeyField / bindingLinkMode /
  Clone / Copy Form / Import / Rollback 等任何"重建 binding"的路径。
  触发词：启发式、猜列名、硬编码列名、改名就失效、只在某个 FU 复现、读配置不要写死、
  子表主键、行身份、platformRowUuid、外键没标记、Structural FK Fields、bindingLinkMode、
  miParticipantRow、克隆丢配置、复制表单丢配置、
  heuristic、guess column name、config-driven、sub-table identity、foreign key not declared。
---

# 用配置取代猜测（Config over Heuristics）

本 skill 记录一次真实重构的**方法与踩坑**：把 MI 子表 binding 分类从「猜列名/表名/FK 名」
改为「读设计器配置」，并物理删除全部兜底。方法本身与 MI 无关，适用于任何"靠名字猜语义"的代码。

- **§0–§9 讲怎么做**这类改造（找配置、验判据、删兜底、验证顺序）。
- **§10 讲本仓库的正确答案**：子表身份 / 外键 / MI 判定三条契约 + 历史写错点清单。
  动子表相关代码前直接跳 §10，省得从症状重新推导一遍。

具体到 MI 的判据表见规则 `portal-mi-subtable-my-request.mdc`。

## 0. 先判断是不是这类问题

命中任一即适用：

- 同一个 bug **只在某些 FU / 某些表上复现**，别的地方好好的
- 用户**改了字段名或表名**之后功能坏掉
- 代码里有 `field === 'task_status'`、`tableName.endsWith('participants')`、
  `fk === 'id'`、`['assignee_user_id','assignee_id'].includes(k)`、`/assignee/i.test(f)` 这类判断
- 注释里写着 "often" / "usually" / "convention" / "guess"，而代码把它当成 always

> 名字猜测的危害不是"偶尔显示错"，而是**静默走错分支**：命名恰好撞对的场景蒙对了，
> 不撞的静默坏掉，于是永远只在部分数据上复现，反复回归。

## 1. 找到权威配置，先证明它存在

**不要急着设计新配置字段。**本次改造最大的教训：我曾断言"child 侧缺权威来源、需要后端新增字段"，
实际上 `binding_link_mode` + `dw_field_definitions.is_foreign_key/ref_table_id` **早就存在**，
只是我只看了 binding 层的 `foreignKeyField`，没往下看字段级元数据。

做法：

```bash
# 1) 查库看这个语义有没有已存的配置列
docker exec -i platform-postgres-dev psql -U platform_dev -d workflow_platform_dev -c "\d <表>"
# 2) 查后端 DTO/enum 是否已经透传
grep -rn "<字段名>" backend/ --include=*.java
# 3) 查前端类型里有没有（有类型 = 大概率已经到前端了）
grep -rn "<字段名>" frontend/<app>/src --include=*.ts
```

**gate**：能用现有配置回答，就绝不新增配置字段（新增会连累导入导出/clone/版本快照/存量迁移）。

## 2. 判据必须逐条对着真实数据验证

写完判据**先拿现场配置核对**，不要只在脑子里推演。本次差点犯的错：

> 判据写成「collection 用 `bindingLinkMode`，child = FK 指向 collection，**其余 = shared**」——
> 用户当场指出：attachment 也是 `structuralFk`，凭什么它是 shared？

正确判据是**三条都要正面成立**：

| 类别 | 判据 |
|---|---|
| A | 配置显式声明（`bindingLinkMode === 'miParticipantRow'`） |
| B | 结构关系指向 A（字段级 FK 的 `refTableId` 指向 A 的 tableId） |
| C | 结构关系指向别处（FK 指向主表） |

`bindingLinkMode` 区分不了 B 和 C（两者都是 `structuralFk`），**只有 `refTableId` 指向谁能区分**。

**规则：`shared` / 默认类 绝不能写成 `else` 兜底。** 判不出来要返回 `null`（"我不知道"），
让调用方决定；静默归到某一类 = 把"猜错列名"换成"猜错类别"，更难查。

```ts
// ❌ 错：信息不足时被静默归类
return 'shared'

// ✅ 对：判不了就说判不了
return null
```

**易错变体：空结果 ≠ 无信息。** 兜底的触发条件常写成「结果为空」，但结果为空有两种含义：

| 情况 | 含义 | 该不该兜底 |
|---|---|---|
| 压根没拿到配置 | 信息不足 | 可以兜底（或返回 null） |
| 配置拿到了，**按判据过滤后本来就是空** | 这是**确定的答案** | **绝不能兜底** |

实测踩过：`resolveMiChildStructuralFkColumns` 按 `refTableId` 正确排除了全部外键，
`out` 为空，末尾一句 `if (out.length === 0) return [bindingFk]` 又把刚排除的那一列放回来 ——
**过滤等于白做**，共享附件表指向主表的外键被当成"指向参与者"，整行被丢弃。

```ts
// ❌ 只看结果空不空
if (out.length === 0 && bindingFk) return [bindingFk]

// ✅ 先区分"没有配置"和"配置说答案是空"
const hasConfig = (config?.fieldDefinitions ?? []).some(f => f?.isForeignKey)
if (out.length === 0 && !hasConfig && bindingFk) return [bindingFk]
```

### 2.1 判据之间有优先级 —— 顺序错了，正面判据一样出事

三条判据都"正面成立"还不够：**它们可能同时成立**，此时顺序决定对错。

本次 review 抓出的 Blocker：A 类（MI collection）**自己也满足 C 的条件**——
collection 表除了被显式声明，还有一个指向主表的外键。先判 C 就会把 A 判成 C，
于是整片跳过行隔离，**把别人已保存的数据覆盖成空**（比原 bug 更严重）。

```java
// ❌ 错：A 被 C 吃掉
if (fkTargetsMainTable(key)) return SHARED;      // collection 也 FK 到主表！
if (declaredAsCollection(key)) return COLLECTION; // 永远走不到

// ✅ 对：显式声明 > 结构推断
if (declaredAsCollection(key)) return COLLECTION;
if (fkTargetsMainTable(key)) return SHARED;
```

**通用规则：显式声明（用户在设计器里明确选的）永远排在结构推断（从关系反推的）前面。**
写判据时逐对问一句「这两条会不会同时成立？」——会，就必须定序并写进注释。

### 2.2 同一份判据在前后端各有一份 —— 必须同序

判据往往前端一份、后端一份。本次前端 `resolveMiBindingKindFromConfig` 第 1 条就是显式声明，
**后端漏了这一条**，于是同一个 FU 在两侧被判成不同的类。

改判据时 `grep` 另一侧的等价实现，**两边同改同序**；只改一侧 = 前端显示对、后端存错，
或反过来——这类不一致极难从症状倒推。

## 3. 删兜底之前，先解决"上下文可达性"

这是**能不能删干净的决定性前提**。判据往往需要额外上下文（本次是 collection / 主表的 tableId），
而调用点常常是深层纯函数拿不到。本次实测 **39 个调用点分布在 20+ 文件**，逐个加参数不现实。

解法：**模块级注册表**（与既有 `setActiveMiConfig` 同套路）

```ts
let activeCtx = { ... }
export function registerCtx(...) { activeCtx = ... }      // 详情页解析完配置后调
export function resolveCtx(explicit?) {                   // 显式传参永远优先
  return { ...activeCtx, ...pickDefined(explicit) }
}
```

配套三条纪律：

1. **每条入口链路都要注册** —— To Do 与 My Request 是两条独立解析链，漏一条那条就判不出来
2. **注销时一并清空** —— 本次 code review 抓到：`setActiveMiConfig(null)` 没清表 id，
   下一个 FU 若 `forms` 为空或解析抛错就会**沿用上一个 FU 的 tableId**（跨 FU 串配置）
3. **注册点要在"配置一定已就绪"处** —— 本次注册点被包在 `if (content.forms?.length > 0) { try {`
   里面，forms 缺失即跳过，正是上面那条泄漏的成因

## 4. 删兜底 = 同时删掉"数据供给"的漏点

删掉猜测后，判据**只认配置**，于是"配置没送到"从"降级猜一下"变成"直接判错"。必须同步检查：

```bash
# 所有构建该对象的地方，是否都透传了判据需要的字段
grep -rln "bindingId: b.bindingId" src/ | while read f; do
  echo "$f: linkMode=$(grep -c bindingLinkMode $f) fieldDefs=$(grep -c fieldDefinitions $f)"
done
```

本次靠这条命令查出 **5 个 binding 构建点漏传** `bindingLinkMode`（To Do 历史表单、节点表单图、
My Request 三处），删兜底后这些路径的 collection 会一律判成非 MI。**单测全绿也抓不到**。

## 5. 返回值可能兼任控制流开关 —— 改它前先确认

本次最痛的回归：`resolveAssigneeFieldForBinding` 的返回值有**第二个语义**——

```ts
const af = config?.assigneeField ?? resolveAssigneeFieldForBinding(...)
if (!af && !config) continue     // ← undefined 意味着"这张表不参与分派，跳过校验"
```

我把它改成读 FU 级配置后，它对**任何** binding 都返回同一个列名 → 逃生口失效 →
同表单的附件子表被当成"需要分派" → Approve 恒被拦下。

**改造前必查**：函数的返回值除了"值"以外，有没有被当作 flag 用？
`grep` 一下每个调用点对返回值的 **falsy 分支**（`if (!x)` / `?? ` / `|| `）。

若判据从 per-item 变成全局配置，**必须把 item 传进去**，否则你回答的就不再是同一个问题：

```ts
// ❌ 只有全局配置，回答不了"这一个 binding 该不该参与"
export function resolveAssigneeField(): string | undefined

// ✅ 判据留在函数内部一处，item 维度不丢
export function resolveAssigneeFieldForBinding(binding): string | undefined {
  if (!bindingDeclaresMiParticipantRow(binding)) return undefined
  return getActiveMiFieldNames().assigneeField ?? undefined
}
```

顺带：改完若参数变成死参数（如原来的 `columns` / `tableName`），**要删**——
签名留着不用的参数会误导后来者以为列名仍参与判定。

## 6. 验证顺序（单测证明不了这类改造）

本次实测：**typecheck + 1594 个单测全绿，仍有 Blocker 被截图验证抓出来**
（`miChildFkConfigOfBinding` 未导入，调用点写成 `as any` 让 TS 放行，该路径无单测覆盖）。

必须按这个顺序，缺一不可：

| 顺序 | 手段 | 能抓什么 |
|---|---|---|
| 1 | 影子探针（见 §7） | 判据本身对不对、翻转面多大 |
| 2 | 单测 + 全量套件 | 判据逻辑、既有行为不回归 |
| 3 | `pnpm run regression:mi` | 热路径专项 |
| 4 | typecheck + build | 类型与打包 |
| 5 | **真实 UI 截图 + 控制台 0 错误** | 导入缺失、配置没送到、`as any` 掩盖的一切 |
| 6 | **端到端状态变更** | 不能只看"报错没了"——要查数据库确认业务真的推进了 |

第 6 条本次的具体做法：点 Approve → 弹窗打开 → `POST /complete` 返回 200 →
**查 `act_ru_task` 确认任务真的完成并拆分出 2 个 MI 子任务**。只看 toast 消失会漏掉"静默失败"。

另外：**改动前先跑一次全量测试记基线**（`git stash` 对照），否则分不清"既有红"和"我弄红的"。

### 6.1 只验"自己那份数据"必然漏掉覆盖别人的 bug

上面 6 条**全绿，仍然漏掉一个 Blocker**（1390 后端 + 1610 前端单测 + 真实 UI 截图 + 查库确认业务推进，
全部通过；漏掉的是"当前用户保存时把别的用户已存的数据抹成空"）。

原因很朴素：**我全程只用一个参与者、只改自己那几行**。而这类判据一旦归错类，
受害的是**另一份数据**——它不在我的测试路径上，自然全绿。

补一条验证维度：

| 维度 | 做法 |
|---|---|
| **多主体** | 用 B 身份保存一次，**查库确认 A 的字段没变**。凡是"隔离 / 权限 / 归属"语义的改动都必须做 |
| **前后快照对比** | 保存前先把相关行 dump 一份（`psql` 一条 SQL 即可），保存后逐字段比，而不是只看自己新增的那行在不在 |
| **多形态入口** | 同一段代码常被**几类不同的页面/流程**调用。只验其中一类，另一类的回归会原样发布出去 |

最后一条实测踩过两次：改共享附件判据只验了 **MI 子任务页**，结果 **assignment（非 MI 任务）**
页的附件存进了库却渲染 0 行 —— 同一个过滤函数，两类页面走的分支不同。
**动共享逻辑前，先 grep 出它的调用点属于哪几类入口，每类各验一次。**

同理适用于：租户隔离、行级权限、并发写合并、软删除——凡是"我的操作不该影响别人"的地方。

### 6.2 新写的回归测试，必须先证明它会失败

补测试时**先把修复临时关掉**（`if (false && ...)` 或删掉新传的参数），确认测试真的红，再恢复。

本次两条新测试都这样验过：
- 后端 `...collectionTableWhoseFkAlsoPointsAtMainStillRowIsolates` → 关掉修复即 FAIL
- 前端 `miRenamedPkNestedEnrich` → 去掉新传的两个参数即 `expected '' to be 'FROM-NESTED'`

没做这一步的"回归测试"经常是**恒绿的摆设**——尤其在这类改造里，因为容易写出一个
"无论判据对错都成立"的断言。

## 7. 影子探针 —— 大范围切换判据前的必做步骤

判据要切换的调用点很多时（本次 162 处引用 / 32 文件），**先只观测不改行为**：

```ts
export function probe(where, items, ctx) {
  const authoritative = computeFromConfig(items, ctx)
  const heuristic = legacyGuess(items)
  for (const d of diff(authoritative, heuristic)) console.warn('[probe]', where, d)
}
```

挂到所有入口，跑真实数据，拿到**翻转清单**。

**gate：清单里每一条翻转都能解释为"这是修好了"，才允许进入真正切换。**

本次正是靠它避免了一次事故：探针唯一报出的分歧（People binding）**证伪了我的 child 判据**——
照原设计切换会让 People 从 participant-child 变成 shared，丢失参与者隔离、跨参与者串数据。
成本只有半天，而单测/typecheck/MI 回归**三道关都拦不住**这个错误。

## 8. 自检清单

- [ ] 权威配置**已存在**（查过库 + 后端 DTO + 前端类型），没有为此新增配置字段
- [ ] 权威配置**已存在**（查过库 + 后端 DTO + 前端类型），没有为此新增配置字段
- [ ] 每一类都有**正面判据**，没有 `else` 兜底；判不出返回 `null`
- [ ] 判据拿**现场真实配置**逐条核对过（尤其是"看起来同类"的两张表怎么区分）
- [ ] **判据之间定过序**：逐对问过"会不会同时成立"；显式声明排在结构推断前面（§2.1）
- [ ] **前后端两份判据同序**：grep 过另一侧的等价实现，两边同改（§2.2）
- [ ] 上下文对所有调用点可达（注册表 + 每条入口链路都注册 + 注销时清空）
- [ ] 所有构建该对象的地方都透传了判据字段（grep 过，不是靠记忆）
- [ ] 确认过返回值有没有兼任控制流开关；死参数已删
- [ ] 大范围切换前跑过影子探针，翻转清单逐条可解释
- [ ] 改动前记了测试基线；截图验证 + 控制台 0 错误 + **端到端状态变更已确认**
- [ ] **验过"不影响别人"**：用第二个主体保存一次，查库确认第一个主体的数据没变（§6.1）
- [ ] **新回归测试先证明会失败**（临时关掉修复跑一次）（§6.2）
- [ ] 判据写进对应 rule 文档（含**已删除清单**与 grep 校验命令），防止被重新引入

## 9. 反模式速查

| 反模式 | 为什么坏 | 正确做法 |
|---|---|---|
| `field === 'task_status'` | 改名即失效，只在部分 FU 复现 | 读 Sub-Task Config 配置的列名 |
| `tableName.endsWith('participants')` | 改表名即失效；同名普通表被误判 | 读 `bindingLinkMode` 声明 |
| `fk === 'id'` | `id` 是最通用的名字，谁都可能叫 | 看字段级 FK 的 `refTableId` 指向谁 |
| `['assignee_user_id','assignee_id']` | 名单穷举不完 | 配置回答；回答不了=不参与 |
| 判不出来 → 归到某个默认类 | 把猜错列名换成猜错类别，更难查 | 返回 `null`，调用方决定 |
| 全局配置替代 per-item 判断 | 丢失 item 维度，对谁都返回同一答案 | 把 item 传进函数，判据留一处 |
| 只看"报错消失"就收工 | 静默失败照样没报错 | 查数据库确认业务状态真的变了 |
| 结构判据排在显式声明前面 | 两条同时成立时，显式声明被吃掉 | 显式声明先判（§2.1） |
| 只改前端或只改后端的判据 | 同一 FU 两侧判成不同类，症状无法倒推 | grep 另一侧等价实现，同改同序（§2.2） |
| 只用自己的数据验证隔离改动 | 受害的是**别人**那份数据，不在测试路径上 | 换个主体保存，查库比对前后快照（§6.1） |
| 只验一类入口就收工 | 同一函数被多类页面调用，走的分支不同 | grep 出所有入口类型，每类各验一次（§6.1） |
| `if (结果为空) 兜底` | "没配置"和"配置说答案就是空"被混为一谈 | 先判有没有配置，再决定要不要兜底（§2） |
| 补的回归测试从没红过 | 大概率写成了恒真断言，是摆设 | 临时关掉修复确认它会失败（§6.2） |
| 用**值的形状**判语义（UUID 正则） | 同一语义有多种生成策略，恒假于其中几种 | 读配置说这一列是不是主键（§10.1） |
| 逐字段拷贝实体时漏一个 | `@Builder.Default` 把"漏写"变成"替换成默认值" | 对着实体字段清单核 builder（§10.3） |
| 修完第一处就收工 | 同一错法通常散布多个调用点 | grep 同一调用形态的全部位置（§10.3） |

---

## 10. 已固化的领域契约：子表身份与绑定

上面讲方法。这一节讲**这个仓库里的正确答案**，避免下次从症状重新推导。
2026-09 一轮排查修掉 15 处同类错误，全部来自下面三个问题被"猜"而不是"读"。

### 10.1 三条契约

**A. 一行的身份** = 配置主键 → 平台 UUID → `null`

- 平台键常量：后端 `SubTableRowIdentity.CANONICAL_FIELD`、前端 `PLATFORM_ROW_UUID_FIELD`，
  值都是 `platformRowUuid`。**两端必须同名**，改一边就是静默分叉。
- 主键列名可以是任何东西：实测有 `correspondence_id`、`case_number`、`id_idwxwcxmw`、
  `idqcxma`、`row_id`。任何「像主键的名字」白名单都是错的。
- **主键值也可以是任何形状**：`pk_generation_json.strategy` 有 `uuid` 和 `prefixedSequence`
  （`Corr-000004`、`Test-000017`）。用 UUID 正则判「有没有分配主键」，
  在 prefixedSequence 的表上**恒假**——MI 参与者表 `subtable` 正是这一类，
  所以判据恰好在最关键的表上失效。

**B. 子表必须有外键，可以没有主键**

设计器已在强制（`useTableBindingForm.ts` 的 `structuralFkRequired`，
提示语 "Sub-table binding requires a foreign key field"）；后端补了
`SUB_FK_NOT_DECLARED`，判据是**「这次请求是否改变 FK 值」**而不是「create 还是 update」——
因为 `updateBinding` 也能改 `foreignKeyField`，按 create/update 区分会留下一个洞：
干净 binding 被重指到未声明的列仍然放行（实测验证过）。

推论：**没有 FK 标记的子表 = 违反契约的脏数据**，不是"另一种合法配置"。

**C. 没有 FK 标记 ⇒ 一定不是子任务表**

```
有 FK 标记  →  可能是 MI 参与者表，需要判定
无 FK 标记  →  structuralFk（挂主表），确定
```

2026-09 实测双向成立。**注意这不是 §2 禁止的 else 兜底**：
「配置的缺失本身就是答案」，与"判不出随便给一个"性质不同。

**D. 设计器的 `Structural FK Fields` 是派生显示**

```ts
const structuralFkFieldNames = computed(() =>
  selectedTableFields.value.filter(f => f.isForeignKey).map(f => f.fieldName)
)
```

所以 `binding.foreign_key_field` 只是它的缓存。标记没打过，存的值就成了孤儿
（实测 33 个 SUB binding 存着一个未被标记为 FK 的列名，弹窗里那栏是空的）。
→ 真源永远是 Table Design 的 `isForeignKey`。

### 10.2 判不出来时怎么办 —— 按"错了会怎样"分别处理

| 场景 | 做法 | 理由 |
|---|---|---|
| 行身份判不出 | 返回 `null` / 空集 | 下游按位置配对，最差配错一行；猜列名会把两行**不同的行合并** |
| 子表没有 FK 标记 | **跳过重建** + 可操作日志 | 建出来的本来就是坏 binding；stale 占位符看得见，用户能修 |
| link mode 判不出 | 读同表其它 binding；再不行 `structuralFk` | 契约 C：能推导 |
| 授予访问权的判据 | 保持 fail-closed | 看不见自己的数据 ≪ 别人看见你的数据 |

日志要写**用户能执行的下一步**，不是 "resolve failed"：

> `sub-table 'X' (id N) has no field marked as a foreign key; mark the parent-referencing column in Table Design`

### 10.3 `@Builder.Default` 陷阱

```java
@Builder.Default
private BindingLinkMode bindingLinkMode = BindingLinkMode.structuralFk;
```

**从 source 逐字段拷贝时漏写一个字段 ≠ 留空，而是静默替换成默认值。**
实测后果：克隆一个有 6 个 MI binding 的 FU → 产出 0 个 MI，参与者隔离全失效。

核对方法：列出实体全部 `private` 字段，逐个在 builder 里 grep。
全仓有 **10 个** `FormTableBinding.builder()` 调用点，第一轮只审了 3 个就以为修完了，
`copyTaskForm` / `copyProcessToTaskForm` 两处同样的漏拷是被追问后才发现的。

合法的例外要写注释说明（如 `subListViewId` 故意不拷，要重新指向克隆出的 config）。

### 10.4 仍然合法的「像硬编码」的东西

别把这些也删了：

- **平台枚举**：`PRIMARY`/`SUB`/`RELATED`、`structuralFk`/`miParticipantRow`、`EDITABLE`
- **平台写入的值**：`IN_PROGRESS`/`COMPLETED` —— 列**名**是配置（已治理），列**值**是契约
- **平台 envelope 结构**：lookup 值对象的 `{id, …}`、`rowKey`、`rowId`
- **`sys_users`** / `-1000000001`：平台虚拟表常量
- **"这列算不算业务数据"类名单**（`SUB_TABLE_ROW_META_KEYS` 等）：无单一配置源，
  判宽判窄只影响"算不算空行"

判据：**猜错会不会让两行不同的数据被当成同一行 / 走错业务分支？**
会 → 必须读配置；不会（只影响显隐、排序、算不算空）→ 可以留名单。

### 10.5 历史写错点速查

新增同类代码前扫一遍。每条都是真实修过的 bug。

| 位置 | 错法 | 实测后果 |
|---|---|---|
| `miLinkChildRows` / `miLinkChildIdentity`(×13) / `miLinkChildScrub` | UUID 正则判「有没有分配主键」 | prefixedSequence 主键的表恒假 → 同一参与者多行被合并 |
| `miLinkChildIdentity.scoreMiLinkChildRowQuality` | **内联**一份同样的正则 | grep 函数名漏掉；真主键行只得 40 分 |
| `SubTableRowIdentity.IDENTITY_FIELDS` | `['row_id','rowId','id_idw','id',…]` | `id` 在 13 张表是业务列且无一是主键 |
| 前端 `subTableRowIdentity.ts` | 同名单 + 写 `row_id` | 后端改名后两端静默分叉，编辑被读成删+增 |
| `FormTableBindingRestorer.inferForeignKeyField` | 猜 `row_id`→`case_id`→兜底 | 15 张子表猜对 2 张，其余 FK 指向不存在的列 |
| `FormTableBindingRestorer` link mode | `"row_id".equals(fk) ? MI : structuralFk` | 14 个 MI binding 里 7 个被还原成 structuralFk |
| `FunctionUnitCloner` / `copyTaskForm` / `copyProcessToTaskForm` | 漏写 `.bindingLinkMode(...)` | 见 §10.3 |
| `FormConfigJsonTableProvisioner` 自动建表 | 建了 FK 列但不打 `isForeignKey` | **8 张无标记表的出生方式**（曾在持续生产） |
| 同上，匹配到无 FK 的已有表 | 落进 createSubTable | 静默新建一张近似重复的表 |
| `SubTableRowKeySupport`(×4) | `id` ⇄ `id_idw` 互顶 | 只对主键正好叫这两个名字的表有效 |
| `SUB_TABLE_STRUCTURAL_FK_KEYS` | 两份手抄副本**已漂移** | 同一行在 To Do 与 My Request 对「算不算空行」答案相反 |

### 10.6 子表专用自检

除 §8 外，改子表身份/绑定时再过一遍：

- [ ] 判断"这一行是谁"用的是配置主键，不是列名白名单、**不是值的形状**
- [ ] 判断"哪列指向父表"读 `isForeignKey`，不是 `row_id`/`main_id`/`case_id`
- [ ] 判断"是不是 MI"读 `bindingLinkMode` 或契约 C，不是 FK 叫什么名字
- [ ] 从实体拷贝时**逐字段**核对过 builder（§10.3）
- [ ] grep 过同一调用形态的**全部**位置，不是只改先看到的那个
- [ ] 判不出时返回 null / 跳过，并给了可执行的日志——没有发明默认值
- [ ] 前后端各有一份的常量（如 `platformRowUuid`）两边都改了
- [ ] 日志只打 id/name **不打实体**（`@Data` 双向引用 `toString` 会无限递归）
- [ ] FU 生命周期改动覆盖 **Clone · Export · Import · Rollback · Copy Form**
      （见 `function-unit-portability`），且做过「修复前复现 + 修复后保真」两次真机
