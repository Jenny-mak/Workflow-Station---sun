# Source map — extract-help-guideline

Where to **read** product facts. Do not copy agent-only files into `/help/`.

Deep-documentation quality applies **inside** `frontend/help` only: layered
articles, exact UI surfaces, diagrams/flow, cross-links, `llms.txt`, same-task
sync. Canonical human site remains `/help/`. Do not add a second docs tree.

## Portal files (write target)

| File | Role |
|------|------|
| `frontend/help/src/guidelines.ts` | Catalog + `NAV_TREE` (mirrors DW / Admin / Portal menus) |
| `frontend/help/src/views/*Guide.vue` | Article (use `GuideArticle.vue`) |
| `frontend/help/src/components/GuideLinkedText.vue` | Inline `[[/path]]` wiki tokens in locale strings → `router-link` |
| `frontend/help/public/guides/` | UI screenshots referenced by `figure.src` |
| `frontend/help/public/llms.txt` | LLM index of this portal (keep in sync with `GUIDELINES`) |
| `frontend/help/public/llms-full.txt` | Compact English bundle of help articles (not the whole repo) |
| `frontend/help/src/i18n/locales/en.ts` | Canonical English |
| `frontend/help/src/i18n/locales/zh-CN.ts` | Simplified |
| `frontend/help/src/i18n/locales/zh-TW.ts` | Traditional |
| `frontend/help/src/App.vue` | Shell matches the three portals. Nav is `NAV_TREE`. |

Canonical live URL: `http://localhost:3000/help/<id>` (edge). Old DW path
`/dev/help/computed-fields` redirects only for computed-fields.

After replacing PNGs, bump `GUIDE_FIGURE_REV` in `GuideArticle.vue`. Capture
script: `frontend/scripts/capture-help-guide-images.mjs` (requires `HELP_GUIDE_FU_ID`).
Redact in `redact-help-guide-pii.mjs`.

## Article layers (required)

Each article should let a reader answer without opening source:

1. **Overview** — what this job / control **is** (appearance, stored value,
   nearest sibling). Palette `whatBody` must not open with drag-from-Basic.
2. **Order of work** — `flow-keys` (visible steps)
3. **How-to** — real screenshots from the demo Function Unit. Script **effect**
   methods use `intentKey` / `beforeKey` / `afterKey` / `noteKey` on
   `GuideArticle` (see SKILL.md Readability gates): one card with user labels
   **Default / After you run the sample / Note**, not writer taxonomy. Visual
   effects use Form Preview figures.
4. **Field catalog** — one sample row per visible control: on-screen label
   (`code`), meaning + required/blank (`hintKey`). List dropdown choices. If a
   value is filled by Admin Center, say so on that row.
5. **Exact samples** — field/table names that appear on those screenshots
6. **Failures** — save blockers the designer can see
7. **Related** — other `/help/` ids, not a paste of their full text

Demo Function Unit for figures (when present): Purchase Request. Main table
`help_pr`, sub-table `help_pr_line`. Formula samples must use those names
(`quantity * unit_price`, `SUM(help_pr_line.line_total)`, `end_date - start_date`,
`IF(grand_total > 5000, "Y", "N")`). Email samples: PR Approved Notice, Vendor
quote to PR.

## Candidate topics (scan if user asks)

| Topic | Start here |
|-------|------------|
| Computed fields | Existing `/help/computed-fields`; `ComputedFieldEditor.vue`; `table.computedField` i18n; `backend/platform-common/.../computedfield/` only for **user-visible** error meanings |
| View access | `.cursor/skills/view-access-control/SKILL.md` (rewrite); View Design panel + view i18n |
| Email send | `EmailTemplateDesigner.vue`, `EmailBodySplitEditor.vue`, `SendTaskProperties.vue`, `emailTemplate` / `connection` i18n |
| Email monitor | `EmailMonitorDesigner.vue`, `StartEventEmailMonitorSection.vue`, `emailMonitor` i18n |
| Form / sub-table | Form designer i18n — extract **how to bind**, not MI invariant I1–I7 |
| Form events | Hub `/help/form-events`; `HermesEventConfig.vue` / `HermesFnConfig.vue`. Effect methods = how-to skeleton. Per palette type: own article (SKILL.md Control article skeleton), not a group dump. |
| Form controls (Basic / Extend / Layout) | Designer palette + that type’s properties panel; `formCreateDefaultEvents/constants.ts` for Create events; `main.ts` `addDragRule` for custom types |
| Relation tables | Admin relation computed-field i18n (no MAIN/SUB) |

When scanning: grep designer `*Guide*`, `dialogHint`, `placeholder`, and
`ElMessage` / i18n error keys the **user** can see.

## Rewrite rules from agent skills

Allowed: `view-access-control`, `function-unit-portability` **user consequences**.

Forbidden to paste: `code-review`, `secure-coding-sast`, `fallback-audit`,
playbook 任务整理, Docker compose rebuild steps.

## i18n key shape

```ts
guides.myTopic: { title, summary }
myTopicGuide: { pageTitle, intro, flowTitle, flow1…, relatedTitle, sectionTitle, sectionBody, sampleHint? }
```

`vue-i18n` must not contain a raw `${name}` or `{ }` in the locale file
(named interpolation). Literal `{`: `{'{'}`. Literal `}`: `{'}'}`. A JUEL
token in help copy is `${'{'}fieldName{'}'}`, never `${fieldName}`.

Field-catalog `hintKey`s: meaning + required vs optional + what blank does.
One `samples` row per visible control. Name every dropdown choice. If Host /
Port / TLS are filled in Admin Center, still list that row.

## One article = one designer job

A **palette control** (Input, Checkbox, Lookup) is one job → one `GUIDELINES`
id (see SKILL.md **Subpages**). A folder (Basic) may have a short index plus
child articles.

If any **other** topic would exceed ~12 sections, split into two `GUIDELINES`
ids and say so in the 提取清单. More than ~12 **product objects** on one page
(21 Basic types behind `#hash`) is the same split trigger.

## Subpages (NAV)

Distilled from [La Suite Docs](https://github.com/suitenumerique/docs) knowledge
tree. Do not import that editor.

| Allowed hash | Forbidden |
|---|---|
| `/email-send#connection` — Connection is a chapter of Send email | `/form-events-basic#checkbox` — Checkbox is its own palette object |
| `/form-events#required` — one method on the events hub | Pointing every Basic leaf at the same events dump |

`NAV_TREE` leaf `titleKey` should match the article `pageTitle` when the leaf
names a control. `llms.txt`: one URL per control, not one line for the whole
palette.

Form-events candidate: hub `/form-events`; per-control `/form-ctl-<type>`;
group indexes `/form-events-basic` etc. after split. Read
`formCreateDefaultEvents/constants.ts` + **the selected control’s properties
panel** for catalogs.

## Human copy (anti-AI flavor)

Use from SKILL.md **Human copy**. This list is the shipping scan for locale
strings. It is **not** a license to run a general AI-humanizer: no humor, no
first-person feelings, no rhetorical questions, no padded word count.

### Keep vs cut

| Keep | Cut |
|---|---|
| Exact on-screen labels, required/blank, visible failures | Template sentence shells that work for any product |
| Click-the-UI / script how-to / reference genres | “In this article we will cover…” / 下面分为三点 |
| Short facts; one job per section | Claim + explanation + recap in every paragraph |
| Cross-links to other `/help/` ids | Closing “hope this helps” / 希望这能帮到你 |

### Banned shells (scan zh-CN / zh-TW / en)

| Family | zh | en |
|---|---|---|
| Contrast | 不是…而是；不在于…而在于；与其说…不如说 | not X, but Y (as a thesis opener) |
| Essence | 真正重要的是；真正决定…的是；本质上；核心在于 | The key is…; At its core; What really matters |
| Sequence lecture | 首先 / 其次 / 最后；第一步…第二步（when not a real `flow-keys` step） | First,… Second,… Finally,… (in prose; `flow-keys` may still number real UI steps) |
| Signpost | 下面我们来；接下来我会；我们可以看到 | Let’s dive in; Let’s walk through; We’ll explore |
| Lecture wrap | 总的来说；值得注意的是；由此可见；不难看出；基于以上 | It’s worth noting; As we can see; Based on the above |
| Fake Q&A | 你觉得呢？你有没有类似经历？你现在卡在哪一步？ | What do you think?; Have you ever…?; Sound familiar? |
| Inflated open | 这次只看；这个问题很简单：；随着…的发展 | In today’s fast-paced…; Whether you’re a beginner or… |
| Palette opener | 从 Basic 拖入 X。填 Options | Drag X from Basic. Fill Options |
| Closing filler | 希望这能帮到你；如有疑问欢迎… | Hope this helps; Don’t hesitate to reach out |

Designer **order of work** is allowed as `flow-keys` (Step 1 on the real screen).
Do not repeat those steps again as 首先/其次 in the intro.

### Rewrite examples (help voice)

Bad: 从 Basic 拖入 Radio。填 Options（label/value）。value 是选中项的值。
Good: 选项全部摊在表单上，一次只能选一项。Field 存 Options 的 value 键。

Bad: 真正重要的不是把公式写得很长，而是先勾选 Computed，再打开 Formula。
Good: Tick **Computed**, open **Formula**, then save the table.

Bad: 值得注意的是，BU 和 Role 都空时，只有系统管理员看得到该视图。
Good: If BU and Role are both empty, only System Administrator sees the view.

Bad: 接下来我们来看失败情况。你现在卡在哪一步？
Good: (failures `failKeys` only) Save is blocked until both BU and Role are set.

If a paragraph is already operational and names real controls, leave it. Do not
“polish” it into warmer prose.
