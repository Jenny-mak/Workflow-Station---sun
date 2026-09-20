# Hermes Master（DW 全局助手机器人）端到端验证

- Script: `frontend/scripts/verify-hermes-master.mjs`
- Screenshots: `2026-09-18_hermes-master_01-dormant-logo.png` … `_11-dizzy.png`（PNG 按 .gitignore 约定不入库）
- 跑法：`cd frontend && node scripts/verify-hermes-master.mjs`
  （`HM_ORIGIN` 默认 `http://localhost:3000`；`HM_SKIP_CHAT=1` 跳过真实模型对话）

## 2026-09-18 结果：31/31 PASS

姿态序列由脚本里的 MutationObserver 记录 `data-pose` 得到，不是看截图猜的：

- 打开 DW：`dormant` 起步——右下角一块未激活的 logo。干等 6 秒、鼠标停留 / 划过、按住想拖走
  （位置不变，且这一下不算点击）之后姿态序列仍只有 `dormant`；干净的一次点击 → `dormant>emerge`，
  且这次点击不打开聊天气泡
- 出场三段用肢体的 bounding box 断言，不靠截图：
  纯 logo（头顶不高于身体上沿、手臂宽 0、腿高 ≤2px）→ 探头（头露出 5–18px，四肢仍收着）→
  头全出且手臂先于腿伸出 → 站起后进入自主动作
- 鼠标不点击：停留 → `wave`；快速划过 → `startled`；来回蹭 → `giggle`
- 点击 → 聊天气泡；等回复时 `think`；真实模型回答了一条 DW 问题
- 侧栏切到 Automation：机器人 DOM 节点是同一个，气泡里的消息数不变；焦点在气泡内时 Esc 关闭
- 拎到离地约 660px 松手：`fall > dizzy > jump`，落在松手处的地面上
- 离地约 60px 松手：`fall > land`，不晕
- 双手举双筒望远镜盯鼠标（`spy`，常规随机动作，权重 3）：脚本把 `Math.random` 钉成 0.999 选中动作表最后一项。
  断言读的是每只镜筒 `<g>` 上的 `rotate(θ)`，和"从两眼中点指向鼠标"的角度比，误差 <1.5°：
  左上 -153.5° vs -153.5°，移到右上 -67.5° vs -67.5°；同时核对两只手、两条直画的手臂都在，常规手臂 opacity=0。
  效果图 `2026-09-18_hermes-master_binoculars-effect.png`
- 彩蛋（每次挑动作 1/1000 概率）：脚本把 `Math.random` 钉成 0 强制命中（不改产品代码）→ `throw`，
  石头落点的 transform 正好是鼠标坐标 `translate3d(400px, 300px, 0)`，烟尘结束后 `.hm-rock` 节点被清掉。
  效果图 `2026-09-18_hermes-master_rock-throw-effect.png`：上排 6 格动作分解，下方是飞行各帧叠加出的抛物线
  （由连拍帧用 PIL 合成，截图里的鼠标指针是脚本注入的假光标——Playwright 截不到真指针）
- 全程无 pageerror

## 本地快速看 UI 的坑

DW 的 `vite`（dev 模式）起得来但页面不挂载（既有的 `js-beautify` default export 报错，与 HM 无关）。
要在不重建 Docker 的情况下看效果：`pnpm run build && pnpm exec vite preview --port 3002`，
脚本用 `HM_ORIGIN=http://localhost:3002` 指过去（preview 复用 `server.proxy`，接口打到 :8083）。
