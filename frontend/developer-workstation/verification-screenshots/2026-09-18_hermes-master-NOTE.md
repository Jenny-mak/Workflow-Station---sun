# Hermes Master（DW 全局助手机器人）端到端验证

- Script: `frontend/scripts/verify-hermes-master.mjs`
- Screenshots: `2026-09-18_hermes-master_01-asleep.png` … `_10-dizzy.png`（PNG 按 .gitignore 约定不入库）
- 跑法：`cd frontend && node scripts/verify-hermes-master.mjs`
  （`HM_ORIGIN` 默认 `http://localhost:3000`；`HM_SKIP_CHAT=1` 跳过真实模型对话）

## 2026-09-18 结果：17/17 PASS

姿态序列由脚本里的 MutationObserver 记录 `data-pose` 得到，不是看截图猜的：

- 打开 DW：`sleep` 起步、位于右下角 → `wake` → 自主动作
- 鼠标不点击：停留 → `wave`；快速划过 → `startled`；来回蹭 → `giggle`
- 点击 → 聊天气泡；等回复时 `think`；真实模型回答了一条 DW 问题
- 侧栏切到 Automation：机器人 DOM 节点是同一个，气泡里的消息数不变；焦点在气泡内时 Esc 关闭
- 拎到离地约 660px 松手：`fall > dizzy > jump`，落在松手处的地面上
- 离地约 60px 松手：`fall > land`，不晕
- 全程无 pageerror

## 本地快速看 UI 的坑

DW 的 `vite`（dev 模式）起得来但页面不挂载（既有的 `js-beautify` default export 报错，与 HM 无关）。
要在不重建 Docker 的情况下看效果：`pnpm run build && pnpm exec vite preview --port 3002`，
脚本用 `HM_ORIGIN=http://localhost:3002` 指过去（preview 复用 `server.proxy`，接口打到 :8083）。
