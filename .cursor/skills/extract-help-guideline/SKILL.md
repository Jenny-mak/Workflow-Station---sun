---
name: extract-help-guideline
description: >-
  Extracts and maintains designer-facing articles for the /help/ Guidelines
  portal from product UI copy, validators, and business rules. Merges
  deep-documentation quality (layered pages, exact surfaces, diagrams,
  cross-links, llms.txt, same-task doc sync) without creating a second docs
  site. Enforces a subpage tree: each palette/control NAV leaf is its own
  how-to article, not a hash on a group dump. Enforces operational human copy:
  strip AI template shells without turning articles into essays. Use when
  the user asks to 提取 guideline, generate /help/ articles,
  extract-help-guideline, 给人看的指南, 重构 guideline, refresh help docs,
  去 AI 味, 控件怎么用, Basic 控件, or turn designer behavior into a help page.
  Does not generate Cursor rules or SKILL.md (except this skill's own
  maintenance).
disable-model-invocation: true
---

# Extract /help/ guideline articles

Turn **what a designer sees and must follow** into a page on `frontend/help`.
This is not an agent rule dump. Audience: people using Developer Workstation /
Admin Center, unauthenticated, environment-independent (`/help/`).

Quality bar comes from **deep-documentation** (canonical:
[adamdroberts/agent-skills deep-documentation](https://github.com/adamdroberts/agent-skills/blob/main/deep-documentation/SKILL.md)),
adapted to this portal only. Do **not** spin up Mintlify, a second markdown
site, repo-wide `llms-full.txt`, or a parallel README stack.

**New article:** do not write files until the user confirms the 提取清单.
**User already named pages and asked to rewrite / 重构:** skip the清单 and edit
those articles.

## When to use

User names a topic (View 访问、计算字段、邮件模板、某个 Basic 控件) or says
提取 guideline / 生成 help 文章 / 重构 guideline / refresh `/help/` /
去 AI 味（help 文案）/ 控件怎么用.

## When not to use

- Authoring `.cursor/rules` or new `.cursor/skills` → `create-skill` / `/create-rule`
- Code review, SAST, playbook, performance N+1
- Inventing product behavior not present in UI or validators
- Dumping Java class names, Docker, Kong, or playbooks into `/help/`
- Pasting a third-party “AI humanizer” Role prompt, or rewriting `/help/` into essays

## Core rules (deep-documentation, scoped to /help/)

- Ground every sentence in the real UI: i18n labels, dialogs, save blockers, `#ERR`.
- Write operational copy: what to tick / type / save, and what fails. No marketing.
  Strip model-organizing shells (see Human copy). Do not rewrite into essays.
- Script **effect** methods: Intent → Before → Code → After (see Voice). Parameter
  lists stay reference. Do not write a JavaScript tutorial.
- Layer each article: **overview → order of work → how-to with figures → field
  catalog (every control) → exact samples → failures → related articles**.
- **Subpages:** a product object in `NAV_TREE` (palette type, named menu item)
  gets its **own** `GUIDELINES` path. The page title matches the leaf. Hash is
  only a section inside that same job (see Subpages). Group dumps with
  `#checkbox` while the title stays “Basic 控件 — 事件” are a defect.
- Cross-link related guidelines with `router-link` in the Related footer **and**
  with inline wiki tokens `[[/path]]` or `[[/path#hash]]` whenever body copy
  names another article (for example 「怎么写事件」, Input, Owner, Advanced
  Upload). `GuideLinkedText` turns those into links. Do not leave a quoted
  article title as plain text. Never put `[[…]]` in titles, crumbs, or home-card
  summaries (`HelpHome` is not linked-text).
- Do not duplicate the other article.
- Use a **flow** (`GuideArticle` `flow-keys`) for multi-step designer jobs.
  Prefer that over a decorative paragraph. Mermaid in i18n is optional; the
  rendered flow list is the human diagram.
- Screenshots and formula/email **samples must match the same demo Function Unit**
  when figures exist (Purchase Request: main `help_pr`, sub `help_pr_line`).
  Do not keep leftover sample names (`leave_request`, `date_info`) next to those
  figures.
- Same task as the UI change: if Connections / Templates / Formula / Send Task
  behavior changes, update the matching `/help/` article, figures, `llms.txt`,
  and `DesignerHelpLink` in one change.
- Treat `frontend/help/public/llms.txt` as the LLM index for this portal.
  Keep it in sync with `GUIDELINES`. Optional `llms-full.txt` is a compact
  English bundle of the **help articles**, not the whole Git repo.

## Workflow

### 1. Topic

If missing, ask **one** question: which topic (or “scan candidates”).
Do not extract the whole product in one pass.

### 2. Read sources (only user-facing)

Read [reference.md](reference.md). Prefer:

1. Designer / Admin **i18n** strings and dialogs (labels, hints, errors)
2. Validators that **block save** or show `#ERR` / form messages
3. Existing `/help/` article on the same id (update, do not fork)
4. Business-rule skills (e.g. `view-access-control`) **rewritten for humans**
5. Current figures under `frontend/help/public/guides/` and the capture script

Skip: Java class names, Docker, Kong, Checkmarx, agent playbooks, internal IDs
unless the UI shows them.

### 3. 提取清单 (new articles only)

Output this block, then **stop**. No Edit/Write until 确认 / 可以 / 执行.

```markdown
## 提取清单（help）

【主题】…
【读者】设计器用户（不登录 /help）
【来源】
- path — 抽了哪条可见规则
【将写入】
- `frontend/help/src/guidelines.ts` id: `…` path: `/…`
- `NAV_TREE` 挂到哪个门户菜单
- 每个产品对象一个 path（调色板控件禁止共用一篇 + hash）
- 源界面 `DesignerHelpLink`
- view + i18n keys（en / zh-CN / zh-TW）
- `frontend/help/public/llms.txt`（及可选 `llms-full.txt`）
- 需要截图时：`frontend/help/public/guides/…png` + bump `GUIDE_FIGURE_REV`
【章节】overview / flow / how-to / field-catalog / samples / failures / related
【字段目录】将写入的每个 UI 标签（必填/选填、空白时怎样）— 禁止写「等其他选项」
【不写】agent-only / 实现细节
【已有页面】无 | 将更新 `computed-fields` 等

回复 **确认** 后我再改 `frontend/help`。
```

Every 【来源】 line must be a real file you opened. If a rule is only in an
agent skill, say so and rewrite; do not paste the skill.

### 4. After confirm (or explicit 重构) — write like the live articles

Copy the pattern in:

- `frontend/help/src/views/FormEventsGuide.vue` — script how-to hub (intent blocks,
  jump nav, block code, fail list)
- `frontend/help/src/views/ComputedFieldGuide.vue` / `EmailSendGuide.vue` — click-the-UI
- `frontend/help/src/components/GuideArticle.vue`
- `frontend/help/src/guidelines.ts` (`GUIDELINES` + `NAV_TREE`)
- `frontend/help/src/i18n/locales/{en,zh-CN,zh-TW}.ts`
- `frontend/help/public/llms.txt`

Checklist:

- [ ] New `id` kebab-case; `path` `/that-id`
- [ ] Register in `GUIDELINES` (titleKey + summaryKey + lazy view)
- [ ] `NAV_TREE` leaf under the **same menu** as the product. Empty menus stay grey.
      Palette/control names get their **own** path (see Subpages). Hash only for
      a section of that same article.
- [ ] `DesignerHelpLink` on the source screen → `/help/<id>` (rule `help-guideline-link`)
- [ ] `GuideArticle`: sections may set `anchor`, `figure`, `samples`, `intentKey` /
      `beforeKey` / `afterKey` / `noteKey`, `failKeys`, `jumpLinks`, `figureBeside`,
      `sampleLayout: 'block'`; page may set `flow-keys` and `related`
- [ ] Figures: capture real UI (`frontend/scripts/capture-help-guide-images.mjs`),
      store under `public/guides/`, redact email/name/process id, bump
      `GUIDE_FIGURE_REV` in `GuideArticle.vue` so browsers drop the old PNG
- [ ] Samples use the **same table/field names as the figures**
- [ ] `data-testid="…-guide-page"`
- [ ] i18n **three locales in the same change**; no raw `${token}` or `{ }` in locale
      strings (vue-i18n interpolates `{name}`). Literal braces: `{'{'}` / `{'}'}`.
      Tokens: `${'{'}fieldName{'}'}`
- [ ] Voice: ticks / types / saves; **script methods** use Intent → Before → Code → After (see Voice)
- [ ] Human copy: no lecture connectives / contrast shells / fake Q&A (see Human copy)
- [ ] **Field catalog:** every visible control on the documented screens (see Field catalog)
- [ ] Update `public/llms.txt` (and `llms-full.txt` if present)
- [ ] Do not add Element Plus; styles already in `help.css`

### 5. Verify (after write)

- `cd frontend/help && pnpm run build`
- Rebuild `platform-help-frontend` (compose) when shipping to `localhost:3000/help/`
- Playwright `frontend/scripts/verify-help-portal.mjs`
- Quote screenshot paths under `frontend/developer-workstation/verification-screenshots/`

## Field catalog (required)

Every article that documents a form, properties panel, or dialog **must** list
**each visible control** the person can tick, type, or save. Do not write “and
other options”. Optional and advanced fields count. Dropdown / radio **choices**
the designer can pick must be named.

For each control, the catalog row must say:

1. **Label** — exact on-screen text (the `code` on a `GuideArticle` sample).
2. **Meaning** — what it is for (`hintKey`).
3. **Required vs optional** — and what happens if it is left blank.
4. **Filled elsewhere** — if Host / Port / TLS come from Admin Center (or another
   tab), say so on that row; do not omit the control because it is disabled.

Place the catalog in the **reference** layer: `samples` with
`sampleLayout: 'block'` on the matching how-to section, or a dedicated
`anchor` section whose samples are one row per field. How-to prose stays
click-the-UI; the catalog stays reference. Do not mix a paragraph that both
narrates the flow and defines every field.

Email send / Email Monitor articles are the calibration: Connections, Email
Templates, Send Task Config, Email Monitors, extraction wizard, and Start Event
Inbound Email Trigger each have a complete catalog.

Locale strings that mention the Insert field button or `${'{'}fieldName{'}'}`
must use vue-i18n escapes. A raw `{ }` in `en.ts` / `zh-CN.ts` / `zh-TW.ts`
throws at render and blanks the page.

## Voice

Three genres. Do not mix them in one paragraph (Diátaxis: how-to vs reference).
This portal is **not a tutorial** — the reader already uses Form Design / Table Design.

| Genre | Use for | Do | Don't |
|---|---|---|---|
| Click-the-UI | Computed, email, View access, opening Edit | “Tick Computed, open Formula, then save the table.” | “`ComputedFieldEvaluator` then persists…” |
| Script how-to | Form event **effect** methods (`setValue`, `disabled`, `setOptions`, …) | Intent → Before → Code → After (including does-not) | “`disabled(true, field)` locks the control…” as the first sentence |
| Reference | Parameter names, Create event lists, signatures, who-wins | Short facts; method name as a tool in the sentence | Teaching JavaScript; overlay/implementation |

| Do | Don't |
|---|---|
| “If BU and Role are both empty, only System Administrator sees the view.” | Copy mermaid of Portal services |
| Formula samples: `quantity * unit_price` on `help_pr_line` | Leftover names that contradict the screenshot (`leave_request`) |
| Task heading: “Lock a field” / “Block Save” | Heading that is only the method name (`disabled()`) |
| Playwright asserts **code** (method + `help_pr` field names) | Asserting “If you want” prose (it changes per locale) |

### Script / API methods (how-to skeleton)

One **job** per sample. Same field names in the sentence, the code, and the After
(Stripe request/response: they must match). Demo FU: `help_pr` / `help_pr_line`.

1. **Intent** — If you want …, use `api.method` on this event (change / Form event).
2. **Before** — designer default / no script.
3. **Code** — shortest paste-into-Edit snippet; introduce it with one sentence.
4. **After (Result)** — what the person **sees or can click** (grey, gone, banner, Save blocked).
5. **Does-not / side effect** — what stays the same or changes as a side effect
   (Microsoft `setDisabled` note pattern). Examples: `clearOptions` does not clear
   the stored value; an ERROR banner does not block Save; `hidden` does not submit.

Pair get/set or set/clear as Related one-liners, not a second tutorial.
Palette **control** articles use the Control article skeleton below; do not
repeat hub signatures from `/form-events`.

Failures = how the designer **sees** that it did not work (script error, missing
Save then Ok, Preview has no Portal `user`, method on the wrong control type).
No class names, Docker, or overlay internals.

`llms-full.txt` may keep method names for retrieval. Human article stays intent-first.

## Subpages (NAV leaf = document)

Hierarchy model distilled from [La Suite Docs](https://github.com/suitenumerique/docs)
(subpages + topic TOC). Do **not** copy that product (collab editor, AI rewrite,
slides). Apply inside `frontend/help` only.

| NAV leaf is… | `to` | Page |
|---|---|---|
| A product object (Input, Checkbox, Lookup, a named screen) | Own `GUIDELINES` path, e.g. `/form-ctl-checkbox` | Title = that object. How to **use** it. |
| A chapter of one job (Connection inside Send email) | Hash on that job’s article, e.g. `/email-send#connection` | Parent title stays the job; section `anchor` matches the hash. |
| A folder (Basic / Extend) | Group with **child leaves**; optional short **index** article | Index lists children. It is not a dump of every child’s body. |

**Defect (do not repeat):** 21 Basic names → `/form-events-basic#input` … `#checkbox` while `pageTitle` is “Basic 控件 — 事件”. The reader clicks Checkbox and still reads a group events list.

Many similar controls: **one shared Vue** + registry keyed by guideline id is allowed. Still **one GUIDELINES row and one `llms.txt` URL per control**. Do not invent 21 copy-paste SFCs.

Old hashes (`/form-events-basic#checkbox`) must **redirect** to the new path when you split.

### Control article skeleton

One palette type = one designer job. Layers:

1. **Overview** (`whatTitle` / `whatBody`) — the reader who has never used this
   control must learn, in ≤ 4 sentences: what it **looks like** on the form and
   how the person interacts; what the **Field stores** (string / number / one
   key / array / `[start,end]` / HTML); the **nearest sibling** with
   `[[/form-ctl-…]]`; the **demo field** once (Purchase Request / help_pr) when
   the article uses one. **Forbidden in `whatBody`:** opening with
   “Drag X from Basic/Extend” / “从 Basic 拖入” / “從 Extend 拖入” (that is
   `flow-keys`); “Fill Options. value is the selected option.” with no
   appearance; repeating `howBody`.
2. **Order of work** — `flow-keys`: drag from that palette group → bind field → fill this type’s properties → save → Preview.
3. **How-to** — ticks / types / saves on **this** control.
4. **Field catalog** — every label on **this** control’s properties panel (read the panel; do not guess).
5. **Events** — at most **one** primary sample (`intentKey` / Before / After). Full `api.*` stays on `/form-events`.
6. **Failures** — what the designer sees when this control is misused.
7. **Related** — `/form-events#…` plus sibling controls, not a paste of their pages.

`form-events-basic` / `-extend` / `-layout` after a split: **index only** (what that palette group is + links). Event how-to lives on `/form-events` and on each control’s Events section.

## Readability gates (human articles)

Apply before shipping. Script how-to pages **must** use visible structure — do not
flatten Intent / Before / After into one `bodyKey` paragraph.

| Gate | Rule |
|---|---|
| Intro | ≤ 3 sentences; no “how to read this page” tutorial |
| Control overview | `whatBody` = appearance + stored value + nearest sibling. Never open with drag-from-palette. |
| Script how-to | One compact card: `intentKey` lead sentence + `beforeKey` / `afterKey` / optional `noteKey`. Labels are **Purpose** (in lead), **Default**, **After you run the sample**, **Note** — never writer taxonomy (“If you want”, “Before the script”, “What does not change”) |
| After | `afterKey` states the **visible** result once; sample `hintKey` must not repeat it |
| Demo fields | In prose, name the **control type + label** (e.g. “Scenario dropdown”, not bare “Scenario is A”). Define demo fields once in `whatBody` (Purchase Request / help_pr). Code samples may keep field names (`scenario`) |
| Visual tasks | Banner, field error, lock, hide/show → pair with **Form Preview** effect figure (`figureBeside`), not designer canvas alone |
| Code | Primary sample ≤ 2 lines when possible; secondary samples one-line **Related:**; use `sampleLayout: 'block'` |
| Failures | `failKeys[]` list, not one `failBody` wall |
| Task nav | Hub pages with > 8 task sections add `jumpLinks` before the first how-to |
| Figures | One figure per distinct surface (Edit dialog, canvas, Form tab, Preview effect). Do **not** reuse the same PNG nine times with different captions |
| Palette control | Own article (Subpages). Title = control name. How-to + catalog first; one event sample; link `/form-events#…`. Never a group dump. |
| Params / hooks | Reference only — short body + samples; not a second how-to tutorial |
| Human copy | Operational facts stay; strip AI shells. Full pattern list: [reference.md](reference.md#human-copy-anti-ai-flavor) |

Diátaxis genre names (how-to vs reference) stay in [reference.md](reference.md); do
not paste them into `/help/` copy.

## Human copy (anti-AI flavor)

Locale strings in `en` / `zh-CN` / `zh-TW` must read as a person documenting this
product, not as a model assembling an answer. Distill **pattern bans** from
public AI-flavor writing notes; **do not** paste a third-party Role prompt, and
**do not** run a “humanizer” that adds humor, first-person feelings, or fake
reader Q&A. Help copy already has a Voice: ticks / types / saves.

Keep: facts, exact UI labels, required/blank behavior, this portal’s three genres.
Change only the **organizing shell** that makes the reader notice the writer is a
model.

| Ban | Examples | Write instead |
|---|---|---|
| Lecture connectives | 总的来说 / 值得注意的是 / 由此可见 / 不难看出；It’s important to note; In summary | State the rule. No wrap-up sentence. |
| Contrast shells | 不是 A，而是 B；真正重要的是；本质上；核心在于 | Name the condition and the result. |
| Assistant signposts | 下面我们来；接下来我会；Let’s walk through; In this article | Start at the control or the failure. |
| Fake engagement | 你觉得呢？你现在卡在哪一步？Have you ever wondered | Failures list + related links. No questions to the reader. |
| Uniform paragraphs | Every section = claim + explanation + recap | How-to narrates clicks; catalog is one row per control. |
| Inflated openings | 这次只看；这个问题很简单；In today’s…; Whether you’re a beginner | One-sentence job: what this page is for. |

Self-check: if you could swap the product name and the paragraph still works,
rewrite it against the real labels. If a rewrite would add personality, jokes,
or “show don’t tell” color, **stop** — that is the wrong kind of humanization
for `/help/`.

## Invoke

```text
/extract-help-guideline
主题：View 访问管控
```

or: `按 extract-help-guideline 提取邮件模板 guideline`

or: `按 extract-help-guideline 重构当前 guideline`

or: `按 extract-help-guideline 提取 Checkbox 控件怎么用`
