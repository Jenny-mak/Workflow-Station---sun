---
name: code-review
description: >-
  Read-only, evidence-based code review for staged changes or a PR branch. Enforces
  repository rules and skills, minimal diffs, compilation and test gates, security/privacy,
  performance, Function Unit portability and runtime completeness, generic behavior across
  new and existing Function Units, and incremental secret checks. Use when the user asks for
  code review, review code, PR review, review diff, pre-commit review, 代码审查, 提交前检查,
  审查改动, FU review, or sensitive-information review.
---

# Code Review — 证据驱动的提交与 PR 审查

本 skill 是现有规则与专项 skill 的**编排层**，不替代它们，也不复制第二套业务规则。
详细门禁清单与协作流程见 [reference.md](reference.md)。

## Quick Start

1. **定模式**：staged（提交前）或 PR `base...HEAD`（基线不明则先问，禁止默认当前分支）。
2. **拉证据输入**：status / name-status / stat / 完整 diff / 未跟踪列表；PR 另加 commit 列表与 message。
3. **补齐前置**：需求与正反例、模块与变更类型、允许/禁止范围、已执行验证。缺关键信息 → 最多 `CONDITIONAL`。
4. **建触发矩阵**：每次必跑通用项；只加载命中路径对应的 rule/skill（见 §2）。
5. **只读审查 + 固定格式输出**（§10）。不得只回复 LGTM。

## 审查边界

- 默认**不得修改**被审查的产品代码、测试或配置；用户明确要求修复后，另起修复步骤。
- 不把 review 与「顺手重构」混在一起。
- 只报告有具体证据的问题；引用符号前必须搜索确认真实存在及签名。
- **`.kiro` 台账**：默认只在报告「范围外问题」列出；仅当用户明确要求时才改 `.kiro`。台账更新 ≠ 可改被审查实现。
- **生成物**：审查 `.cursor/skills` / `.cursor/rules` 真源；单独改 `.claude/skills` 等生成镜像而未改真源 → Major/Blocker（视是否造成漂移）。

## 验证证据归属（谁来跑命令）

| 情况 | 做法 |
|---|---|
| 本地可执行且只读（`git`、`mvn … -DskipTests`、`pnpm run build`、已有 test/lint、secret 扫描） | **Reviewer 应实际执行**，把命令与结果写入报告 |
| 作者已在对话/PR 中提供完整命令输出 | 可核验引用；抽查至少一条高风险命令，不得盲信 |
| 环境/权限/依赖不可用（无 Docker、无 DB、无截图环境等） | 对应项标 **未执行**；结论最多 `CONDITIONAL`，不得 `PASS` |
| 验证需要改产品代码、装未知依赖、或写生产配置 | **禁止**；标未执行并说明 |

IDE 无红线或「理论上能编译」不能代替命令输出。

## 1. 审查模式

### A. 提交前 — staged diff

至少：`git status --short`、`git diff --cached --name-status|--stat`、`git diff --cached`、未跟踪文件列表。

- 只审未 staged 工作区会漏提交内容；只审 staged 而不看未跟踪会漏应提交关联文件。
- **相关未跟踪文件**（同功能的源码/测试/i18n/SQL/配置，非 `dist`/`node_modules`/日志噪声）→ **Major**（漏提交）；若会导致运行时缺失/安全问题 → **Blocker**。无关噪声在「范围外」或忽略并简述依据。

### B. PR 前 — branch diff

- 明确基线分支；用 merge-base 语义审完整 `base...HEAD`，不是只看最后一 commit。
- 检查 name-status、stat、完整 diff、commits、messages。
- 修 finding 后：先审增量，再重审完整 `base...HEAD`。

## 2. 变更触发矩阵

路径约定：规则 = `.cursor/rules/<file>.mdc`；skill = `.cursor/skills/<name>/SKILL.md`（下文写短名）。

### 每次都执行

需求与验收映射 · 业务正确性 · 最小 diff · 影响链 · 编译/build · 测试证据 · 安全/隐私/增量 secret · Diff 卫生  
（细则：[reference.md §通用门禁](reference.md#通用审查门禁)）

### 按路径/语义触发（只展开命中项；N/A 须有依据）

| 触发 | 必须加载/对照 |
|---|---|
| 任意代码 | `code-quality-standards` · `change-playbook` · `cross-cutting` · `ai-guardrails` |
| 异常/空值/降级 | `error-handling-governance`；量化用 skill `fallback-audit` |
| 按名字判断业务语义（列名/表名/字段名白名单、`x === 'some_column'`、正则匹字段名），或把此类判据改为读配置 | skill `config-over-heuristics`（重点查：默认类是否写成 else 兜底、配置是否对所有调用点可达、构建点是否透传判据字段、返回值是否兼任控制流开关） |
| 鉴权/输入/URL/SQL/XML/LDAP/凭证 | `security-guard` + skill `secure-coding-sast` |
| Entity / `deploy/init-scripts` / schema | `init-scripts-append-only` · `json-row-storage-no-physical-tables` · `deployment-infra`（若触 deploy） |
| 性能/缓存/并发/批量/前端热路径 | `performance-guardrails`；Portal 热路径再加 `performance-change-safety` |
| 测试文件 | `testing` |
| 可部署代码/配置 | `debug-mode-docker-workflow`；配置同步相关再加 `docker-k8s-config-sync` |
| 可见 UI | skill `verify-ui-fix-with-screenshot` + `frontend-screenshot-verification` · `portal-design-parity`（Portal↔DW） · `form-preview-fk-pk-runtime`（FK/PK） · skill `portal-dialog-form-labels`（弹窗表单） · `i18n-rules` |
| i18n key 增改 | `i18n-rules`（en / zh-CN / zh-TW 同更） |
| FU export/import/clone/snapshot（含 Admin parser/sync、Deploy ZIP） | skill `function-unit-portability`（消费者矩阵：DW **和** Admin，禁止只改一侧） |
| FU version/rollback | skill `function-unit-version-rollback` |
| View access | skill `view-access-control` |
| Portal MI | `portal-mi-subtable-my-request` + `performance-change-safety` |
| Portal/Admin 侧栏或记录列表（`el-table` 主列表、Layout 菜单、`ListColumnHeader` / `ListPagination`） | `shared-list-portal-admin` + [shared-list-components.md](../../../docs/design/shared-list-components.md) §6.7（表头 / kind 筛选排序 / 列宽 / 分页）；列宽 fill 再加 §6.6 |

## 3. 通用门禁（摘要）

完整清单见 [reference.md](reference.md)。审查时至少覆盖：

1. **需求/正确性**：改动可追溯；正反例与边界；无静默 fallback；单一决策点；修根因。
2. **最小 diff**：逐文件问「删掉后验收是否仍过？」；拒无关重构、类型混 PR、假想抽象、调试残留、改生成镜像。
3. **影响链**：接口↔实现↔调用方；Entity→SQL→DTO→前端；i18n 三语；配置→Docker/K8s/Kong；`platform-*` 下游；删除/重命名搜残引用。
4. **编译**：受影响模块有真实命令结果；掩盖 warning / 降严格度 = Blocker；未跑 = 未验证。
5. **测试**：正常 + ≥1 异常/边界；bugfix 宜先有失败测试；UI 要截图；MI 热路径要完整 `pnpm run regression:mi`。
6. **DB**：init-scripts 只增不改、幂等；禁破坏性删改列与业务物理表。
7. **安全与增量 secret**：见 §5。
8. **性能**：见 §6；perf 不与 fix/feat 语义混 PR。

## 4. Function Unit 强制完整性

**触发**：改 FU 设计产物/属性/snapshot；Form/Table/View/Action/Decision/Email/BPMN/Access；Deploy/Import/Clone/Rollback/Portal runtime；或问题仅在单一 FU 复现。  
未触发 → 输出模板中整节标 `N/A`（依据：未触及 FU 能力）。

命中后细则与生命周期表见 [reference.md §FU](reference.md#function-unit-强制完整性矩阵)。硬性要求：

- **通用性**：由元数据/schema/binding/稳定 code 或运行时上下文驱动；禁止硬编码具体 FU/form/table/field/process key 决定业务语义。
- **双样本**：运行验证至少覆盖「新建 FU」+「已有或导入 FU」。若环境无法跑双样本，但静态审查能证明无硬编码且生命周期点已同步 → 通用性可写「静态 PASS」；运行证据标未验证，结论最多 `CONDITIONAL`。仅单一报告 FU / 固定 seed 通过 → 不得判运行 PASS。
- **生命周期**：Save/Load · Export · Import · Clone · Version/Rollback · Admin JSON import · Admin deploy import · Deploy/Activate · Portal backend · Portal frontend — 每项「已同步+证据」或「有依据 N/A」；不得只改 Admin 两条导入链之一；**不得只改 DW 而不扫 Admin parser/`*SyncComponent`/Engine**（见 skill `function-unit-portability` 消费者矩阵）。

## 5. 安全、隐私与增量敏感信息

安全门禁见 [reference.md §安全](reference.md#安全隐私与增量敏感信息)。

### 增量 secret — 默认扫描范围

**默认**：扫描本次审查 diff 中所有新增/修改的行与新增文件（含文件名），以及本次范围内新增 commit 的 message。不要求用户再「决定启用日」。

| 模式 | 范围 |
|---|---|
| staged | `git diff --cached` 的新增/修改内容 + 拟纳入本次提交的相关未跟踪文件 |
| PR | `git diff --merge-base base HEAD`（或等价 `base...HEAD`）新增/修改内容 + 该范围内 commit messages |
| 用户显式收窄 | 仅当用户给出基线 commit/日期时，才排除「基线之前已存在且本 diff 未改动」的旧值 |

历史值被复制、修改或重新提交 → 视为新引入。  
发现真实凭证/PII → **Blocker**；报告只写「类型 + 文件位置 + 处置」，**绝不复述敏感值**。已 push 的新凭证须吊销/轮换并从新增提交移除后重扫。

## 6. 性能专项

命中性能/热路径时加载对应规则；检查项与证据要求见 [reference.md §性能](reference.md#性能专项)。  
明显回退 → Major；可能导致不可用/耗尽/越权/数据错误 → Blocker。

## 7. 豁免

所有 N/A、风险接受、规则例外、未执行验证须记录：原因与外部依据、影响范围、责任人/Issue、后续动作与删除条件。  
「时间不足」或「现有代码如此」不是豁免依据。发布/回退检查见 [reference.md](reference.md#发布回退与豁免)。

## 8. Finding 严重级别

| 级别 | 含义 |
|---|---|
| **Blocker** | 编译/类型失败、敏感信息、可利用安全缺陷、越权、数据损坏/丢失、破坏性不兼容、核心业务错误、FU 生命周期缺失。必须修复后合并 |
| **Major** | 高概率正确性/兼容性/通用性问题、关键测试或运行证据缺失、明显性能/可靠性回退。原则上修复；接受须有责任人+依据 |
| **Minor** | 不影响正确性与安全的局部维护性。禁止风格偏好噪声 |
| **Question** | 需作者澄清、尚无反例。不得伪装成 finding |

每条 finding：**文件:行** · 反例/触发条件 · 影响 · 最小修复 · 依据 rule/skill · 验证缺口。

## 9. 放行规则

仅同时满足才输出 **PASS**：

- Blocker = 0，且无「未接受」的 Major
- 受影响模块编译/build 已实际通过（或作者输出经核验）
- 必需测试/运行/UI 截图已通过
- 增量敏感信息扫描通过
- FU 变更时通用性与生命周期矩阵完整（或有依据 N/A）

否则：有 Blocker/未接受 Major → **FAIL**；关键验证不可用但无 Blocker → **CONDITIONAL**。

## 10. 固定输出格式

```markdown
# Code Review 结果

## 结论
PASS | CONDITIONAL | FAIL

## Findings
### Blocker
- [文件:行] 标题
  - 反例：
  - 影响：
  - 最小修复：
  - 依据：
  - 验证缺口：

### Major
...

### Minor
...

### Question
...

## 范围 / 验收映射
- ...

## 触发矩阵（命中项）
- ...

## 编译与静态检查
- 命令：... → PASS / FAIL / 未执行

## 测试与运行证据
- 命令/截图/logs：... → PASS / FAIL / 未执行

## Function Unit 通用性与生命周期
- 通用性：PASS / 静态 PASS / N/A / 未验证
- Save/Load：...
- Export/Import：...
- Clone：...
- Version/Rollback：...
- Admin 两条 Import：...
- Portal runtime/parity：...

## 增量敏感信息
- 扫描范围：...
- 结果：PASS / FAIL / 未执行

## 性能
- 结果与证据：PASS / N/A / 未验证

## 残余风险 / 豁免
- ...

## 范围外问题
- 无 / ...
```

### 结论样例（摘要）

- **PASS**：无 Blocker/未接受 Major；`mvn … package` / `pnpm run build` 与必要测试已跑通；secret 扫描干净；非 FU 或 FU 矩阵齐全。
- **CONDITIONAL**：静态无 Blocker，但 Docker/UI 截图/双 FU 运行未执行，或需求正反例缺失。
- **FAIL**：存在 Blocker（如编译失败、硬编码 FU id、staged 漏掉必要 SQL、diff 含明文 token）。

## 11. 更多

- 通用门禁全文、FU 生命周期表、安全/性能细则、推荐协作流程 → [reference.md](reference.md)
