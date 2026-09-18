/**
 * 前端功能开关（编译期常量，改完需重新构建前端）。
 */

/**
 * AI Generate（功能单元 AI 生成）。
 *
 * 模型调用直连集团 AI gateway（OpenAI 兼容 `chat/completions`），凭证是每用户的 DSP AMToken，
 * 由 `useAiChat` 经 `X-AM-Token` 头透传给后端。2026-07-28～07-29 之间曾因旧的 Activepieces
 * 链路（`<ap>/api/v1/webhooks/<flowId>/sync` → `piece-ai` run_agent → deepseek）废弃而整体关闭。
 *
 * 这个开关控制的是**入口**：为 false 时按钮与 `<AiPanel>` 都不渲染，因此
 * `src/api/aiGeneration.ts` 里的任何请求都不会发出（AiPanel 的数据拉取挂在 `props.visible`
 * 的 watch 上，组件不创建就不会触发）。
 *
 * 开关须**两侧一致**，只改一侧会得到 404 或空入口：
 *   1. 这里为 `true` 并重新构建 developer-workstation 前端；
 *   2. 后端 `ai-generation.enabled`（环境变量 `AI_GENERATION_ENABLED`）为 `true`——
 *      `AiGenerationController` 带 `@ConditionalOnProperty`，为 false 时整个控制器不注册。
 *
 * 另外后端还须配 `GROUP_AI_GATEWAY_URL`，否则每轮对话以 `AI_GATEWAY_NOT_CONFIGURED` 失败。
 */
export const AI_GENERATION_ENABLED = true

/**
 * AI Studio（分阶段引导式 AI 设计，五阶段：Description & Roles → Workflow → Data Model →
 * Forms & Actions → Review）。
 *
 * 当前增量只落地入口：FunctionUnitEdit 头部的 "AI Studio" 按钮 + "Build with AI" 弹窗
 * （`components/ai/AiStudioEntryDialog.vue`）。工作台页面尚未实现，点 "Open AI Studio"
 * 会给出显式的开发中提示。为 false 时按钮与弹窗都不渲染。
 */
export const AI_STUDIO_ENABLED = true

/**
 * Hermes Master（HM）：常驻 DW 每个页面的助手机器人（`components/hermes-master/`）。
 * 为 false 时 App.vue 不挂载它，`src/api/hermesMaster.ts` 的请求也就不会发出。
 *
 * 对话走后端 `/ai-generation/hermes-master/chat`，与 AI Generate 同受 `ai-generation.enabled` 控制：
 * 后端关着时机器人照常活动，只是对话会在气泡里显示失败提示。
 */
export const HERMES_MASTER_ENABLED = true
