# 2026-09-20 · FU 文档导入/下载 + Build with AI 一键生成

脚本：`frontend/scripts/_fu-docs-import-one-click-check.mjs`（`RUN_MODEL=1` 跑真实模型）。环境：本地 dev compose，账号 developer。

## A. Settings → Requirements / Function Unit Design 导入与下载
- `_A1-empty-toolbar`：工具栏新增 Import / Download，空文档时 Download 禁用。
- `_A2-imported-unsaved`：导入 `.md`（带 BOM、CRLF）→ 进编辑器草稿并标 Unsaved，此时库里没有新版本；Save 后才生成 v1.1。
- 下载：文件名 `Doc_IO_<n>-requirements-v1.1.md`，内容与文档一致。
- `_A3-rejected-docx`：`.docx` 被拒绝，编辑器内容不变。
- `_A4-replace-confirm`：有未保存修改时再导入先确认，取消则保留修改。

## B. Build with AI → One-click generate
- `_B1-entry-dialog` / `_B2-one-click-input`：第三张模式卡 + 需求输入框；没有描述也没有 Requirements 文档时 Generate 禁用；后端同样以 `AI_STUDIO_ONE_CLICK_NO_INPUT`(400) 拒绝。
- `_B3-replace-confirm`：每次生成前确认"整套设计会被替换"；取消则不提交作业。
- `_B4-workspace-waiting`：提交后进工作台等待（可 Stop），URL 由 `mode=generate` 换成 `mode=continue`。
- `_B5-result-card` / `_B6-process-loaded`：真实模型（deepseek）300 秒完成；结果卡标记 Applied，写入 1 表 / 4 表单 / 5 动作 / 流程（含 HR 分支），流程画布已重载；需求描述记为 Requirements 文档（来源 `AI_ONE_CLICK`）。

## 过程中发现并修复
第一轮真实模型失败：`AI_TASK_ASSIGNEE_INVALID`（userTask 没有 assigneeType），修复重试后仍失败。
根因是 `ai-prompts/generation.txt` 只要求"必须带 assigneeType 扩展属性"，从未给出 XML 写法；空功能单元里没有现成流程可抄。
已在提示词里补上确切写法（`custom:properties > custom:values`，命名空间 `http://workflow.platform/schema/custom`），第二轮一次通过。

## C. 帮助文档（/help/fu-documents、/help/ai-studio）
- 新文章两篇，挂在 开发工作站 → 功能单元 下（AI Studio、Function Unit Settings）；三语；`llms.txt` / `llms-full.txt` 已同步；`GUIDE_FIGURE_REV` = 20260920-1。
- 界面 `?`：Settings 文档页签工具栏（`fu-documents-guide-link`）、Build with AI 标题旁（`ai-studio-guide-link`）。
  `node scripts/verify-fu-docs-ai-studio-help-links.mjs`（HELP_GUIDE_FU_ID=50144）→ 4/4 PASS，截图 `_help-link-fu-documents.png`、`_help-link-ai-studio.png`。
- `node scripts/verify-help-portal.mjs` → 128/128 PASS，截图 `_help-portal-fu-documents.png`、`_help-portal-ai-studio.png`；zh-CN / zh-TW 渲染无 i18n 报错（`_help-portal-*-zh-CN.png`）。
- 配图由 `scripts/capture-fu-docs-ai-studio-help-images.mjs` 在演示功能单元 Purchase Request（50144，help_pr / help_pr_line）上截取；
  结果卡那张来自一次真实一键生成（345 秒，表名仍是 help_pr / help_pr_line）。注意该脚本带 RUN_MODEL=1 时会替换所指功能单元的整套设计。
