#!/usr/bin/env node
/**
 * Hermes Master（DW 全局助手机器人）端到端验证：
 * 未激活的 logo（只有点击能激活）→ 出场 → 划过反应 → 点击聊天 → 跨页面常驻 → 高处拖拽摔晕再跳起 → 低处轻放 → 双手举双筒望远镜盯鼠标 → 1/1000 扔石头彩蛋（后两项钉住 Math.random 强制触发）。
 *
 *   cd frontend && node scripts/verify-hermes-master.mjs
 *
 * 环境变量：HM_ORIGIN（默认 http://localhost:3000）、HM_SKIP_CHAT=1（不调模型）、LOGIN_USER / LOGIN_PASS。
 * 截图落在 frontend/developer-workstation/verification-screenshots/。
 */
import { chromium } from 'playwright'
import { mkdirSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { loginViaDwPassword } from './playwright-login.mjs'

const ORIGIN = (process.env.HM_ORIGIN ?? 'http://localhost:3000').replace(/\/$/, '')
const OUT = resolve(dirname(fileURLToPath(import.meta.url)), '../developer-workstation/verification-screenshots')
const DATE = new Date().toISOString().slice(0, 10)
mkdirSync(OUT, { recursive: true })

const failures = []
function check(name, ok, detail = '') {
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? `  (${detail})` : ''}`)
  if (!ok) failures.push(name)
}

const browser = await chromium.launch()
const page = await browser.newPage({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 2 })
const pageErrors = []
page.on('pageerror', e => pageErrors.push(e.message))

const shot = (slug, clip) => page.screenshot({ path: `${OUT}/${DATE}_hermes-master_${slug}.png`, clip })
const pose = () => page.getAttribute('.hm-robot svg', 'data-pose')
const box = () => page.locator('.hm-robot').boundingBox()
const poses = () => page.evaluate(() => window.__hmPoses.slice())
async function waitForPose(wanted, timeout = 8000) {
  const list = Array.isArray(wanted) ? wanted : [wanted]
  await page.waitForFunction(w => w.includes(document.querySelector('.hm-robot svg')?.getAttribute('data-pose')), list, { timeout })
}
/** 等它回到可被打断的自主状态（站着 / 走着），避免在倒立、缩回时测交互 */
async function waitUntilCalm() {
  await waitForPose(['idle', 'walk', 'sit', 'think'], 40000)
}
async function robotClip(pad = 90) {
  const b = await box()
  const x = Math.max(0, b.x - pad)
  const y = Math.max(0, b.y - pad)
  return { x, y, width: Math.min(1440 - x, b.width + pad * 2), height: Math.min(900 - y, b.height + pad * 1.2) }
}

await loginViaDwPassword(page, { loginOrigin: ORIGIN })
// 在页面脚本运行前装好姿态记录器：要抓到"一打开就在睡觉"
await page.addInitScript(() => {
  window.__hmPoses = []
  new MutationObserver(() => {
    const p = document.querySelector('.hm-robot svg')?.getAttribute('data-pose')
    if (p && window.__hmPoses[window.__hmPoses.length - 1] !== p) window.__hmPoses.push(p)
  }).observe(document, { subtree: true, childList: true, attributes: true, attributeFilter: ['data-pose'] })
})
await page.goto(`${ORIGIN}/dev/function-units`, { waitUntil: 'domcontentloaded' })
await page.waitForSelector('.hm-robot')

// 1. 右下角一块未激活的 logo：放着不动、鼠标划过、想拖走都不会激活它，只有点击会
/** 头顶比 logo 身体上沿高出多少 px（≤0 = 头完全缩在 logo 背后），以及手臂 / 腿当前的可见尺寸 */
const rig = () => page.evaluate(() => {
  const r = sel => document.querySelector(`.hm-robot ${sel}`).getBoundingClientRect()
  return {
    headAbove: Math.round(r('.hm-body').top - r('.hm-head').top),
    armWidth: Math.round(r('.hm-arm--l').width),
    legHeight: Math.round(r('.hm-leg--l').height)
  }
})
const first = await box()
check('starts as the dormant logo', (await poses())[0] === 'dormant', `poses=${(await poses()).join('>')}`)
check('starts in the bottom-right corner', first.x > 1440 * 0.75 && first.y + first.height >= 899, `x=${first.x} bottom=${first.y + first.height}`)
const asLogo = await rig()
check('dormant = the bare logo', asLogo.headAbove <= 1 && asLogo.armWidth <= 1 && asLogo.legHeight <= 2, JSON.stringify(asLogo))
await shot('01-dormant-logo', await robotClip())
await page.waitForTimeout(6000)
await page.mouse.move(first.x + first.width / 2, first.y + first.height - 20, { steps: 5 })
await page.waitForTimeout(800)
await page.mouse.move(first.x - 80, first.y + first.height - 20, { steps: 2 })
await page.mouse.move(first.x + first.width / 2, first.y + first.height - 20)
await page.mouse.down()
await page.mouse.move(first.x - 300, first.y - 300, { steps: 6 })
const draggedTo = await box()
await page.mouse.up()
await page.waitForTimeout(300)
check('dormant logo cannot be dragged away', Math.abs(draggedTo.x - first.x) < 1 && Math.abs(draggedTo.y - first.y) < 1, `x=${draggedTo.x} y=${draggedTo.y}`)
check('waiting, hovering and drag attempts never activate it', (await poses()).join('>') === 'dormant', (await poses()).join('>'))
await page.locator('.hm-robot').click()
check('a click activates it', (await poses()).join('>') === 'dormant>emerge', (await poses()).join('>'))
check('the activating click does not open the chat', (await page.locator('.hm-chat').count()) === 0)
await page.mouse.move(10, 10)
await page.waitForTimeout(1500)
const peeking = await rig()
check('then peeks its head out while the limbs stay in', peeking.headAbove > 5 && peeking.headAbove < 18 && peeking.armWidth <= 1 && peeking.legHeight <= 2, JSON.stringify(peeking))
await shot('02-peeking', await robotClip())
// 取样点落在「左臂弹出、腿（85% 起）还没伸」的窗口里；弹出过程中取样，所以只要求手臂已明显可见
await page.waitForTimeout(1900)
const armsOut = await rig()
check('head fully out, arms come out before the legs', armsOut.headAbove >= 18 && armsOut.armWidth > 3 && armsOut.legHeight <= 2, JSON.stringify(armsOut))
await shot('03-arms-out', await robotClip())
await waitUntilCalm()
const standing = await rig()
check('ends standing on its legs and starts acting on its own', standing.legHeight > 15 && /^dormant>emerge>(idle|walk|sit|think|wave|handstand|hide|sleep)/.test((await poses()).join('>')), `${JSON.stringify(standing)} ${(await poses()).join('>')}`)

// 2. 鼠标停在身上（不点击）→ 挥手
await page.mouse.move(10, 10)
await waitUntilCalm()
let b = await box()
await page.mouse.move(b.x + b.width / 2, b.y + b.height / 2, { steps: 4 })
await waitForPose('wave', 3000).then(() => check('hover → waves', true)).catch(async () => check('hover → waves', false, await pose()))
await shot('04-hover-wave', await robotClip())
await page.mouse.move(10, 10)
await page.waitForTimeout(2600)

// 3. 快速划过 → 吓一跳
await waitUntilCalm()
b = await box()
await page.mouse.move(b.x - 60, b.y + 40)
await page.mouse.move(b.x + b.width / 2, b.y + 40, { steps: 2 })
await page.mouse.move(b.x + b.width + 80, b.y + 40, { steps: 2 })
await waitForPose('startled', 3000).then(() => check('quick swipe → startled', true)).catch(async () => check('quick swipe → startled', false, await pose()))
await shot('05-swipe-startled', await robotClip())
await page.waitForTimeout(2200)

// 4. 在身上来回蹭 → 痒得发抖
await waitUntilCalm()
b = await box()
for (let i = 0; i < 10; i++) {
  await page.mouse.move(b.x + (i % 2 ? 20 : b.width - 20), b.y + 30 + (i % 3) * 8, { steps: 3 })
}
await waitForPose('giggle', 3000).then(() => check('rubbing → giggles', true)).catch(async () => check('rubbing → giggles', false, await pose()))
await shot('06-rub-giggle', await robotClip())
await page.mouse.move(10, 10)
await page.waitForTimeout(2200)

// 5. 点击 → 聊天气泡
await waitUntilCalm()
await page.locator('.hm-robot').click()
await page.waitForSelector('.hm-chat')
check('click → chat bubble opens', true)
await page.waitForTimeout(400)
await shot('07-chat-open')
if (!process.env.HM_SKIP_CHAT) {
  await page.locator('.hm-chat__suggestions button').first().click()
  await waitForPose('think', 3000).then(() => check('waiting for a reply → thinking pose', true)).catch(async () => check('waiting for a reply → thinking pose', false, await pose()))
  await page.waitForSelector('.hm-chat__msg.is-thinking', { state: 'detached', timeout: 130000 })
  const reply = page.locator('.hm-chat__msg.is-assistant').last()
  const isError = await reply.evaluate(el => el.classList.contains('is-error'))
  const text = (await reply.innerText()).trim()
  check('HM answers a DW question', !isError && text.length > 20, `${isError ? 'ERROR: ' : ''}${text.slice(0, 120).replace(/\s+/g, ' ')}`)
  await page.waitForTimeout(500)
  await shot('08-chat-reply')
}

// 6. 切到别的 DW 页面：机器人与对话都还在
const messagesBefore = await page.locator('.hm-chat__msg').count()
await page.evaluate(() => {
  window.__hmNode = document.querySelector('.hm-robot')
})
await page.locator('a[href$="/automation"], .el-menu-item:has-text("Automation")').first().click()
await page.waitForURL(/\/automation/)
await page.waitForTimeout(1500)
check('stays mounted across DW pages', await page.evaluate(() => window.__hmNode === document.querySelector('.hm-robot')))
check('conversation survives navigation', (await page.locator('.hm-chat__msg').count()) === messagesBefore)
await shot('09-other-page')
// Esc 只在焦点位于气泡内时关闭它，不抢页面上其它弹层的 Esc
await page.locator('.hm-chat textarea').focus()
await page.keyboard.press('Escape')
await page.waitForSelector('.hm-chat', { state: 'detached' })
check('Esc closes the chat bubble', true)

// 7. 拎到高处松手 → 下落 → 摔晕 → 跳起来
await waitUntilCalm()
b = await box()
await page.mouse.move(b.x + b.width / 2, b.y + 30)
await page.mouse.down()
await page.mouse.move(700, 300, { steps: 12 })
await page.mouse.move(520, 180, { steps: 8 })
check('drag → dangling pose', (await pose()) === 'dragged', await pose())
await shot('10-dragged')
const before = (await poses()).length
await page.mouse.up()
await waitForPose('dizzy', 5000).catch(() => {})
await page.waitForTimeout(1200)
await shot('11-dizzy', await robotClip())
await waitForPose(['idle', 'walk', 'sit', 'think', 'wave', 'handstand', 'hide', 'sleep'], 8000).catch(() => {})
const tail = (await poses()).slice(before).join('>')
check('dropped from high up → fall > dizzy > jump', tail.startsWith('fall>dizzy>jump'), tail)
b = await box()
check('lands on the ground where it was dropped', Math.abs(b.y + b.height - 900) < 1 && Math.abs(b.x + b.width / 2 - 520) < 60, `x=${Math.round(b.x)} bottom=${b.y + b.height}`)

// 8. 低处放下 → 只是轻轻落地
await waitUntilCalm()
b = await box()
await page.mouse.move(b.x + b.width / 2, b.y + 30)
await page.mouse.down()
await page.mouse.move(900, 780, { steps: 10 })
const beforeLow = (await poses()).length
await page.mouse.up()
await page.waitForTimeout(1500)
const lowTail = (await poses()).slice(beforeLow).join('>')
check('put down near the ground → soft landing, no fainting', lowTail.startsWith('fall>land') && !lowTail.includes('dizzy'), lowTail)

// 9. 双手举双筒望远镜盯着鼠标：钉住 Math.random 选中动作表最后一项（spy），再核对两只镜筒确实指向鼠标
await waitUntilCalm()
await page.mouse.move(300, 200)
await page.evaluate(() => {
  window.__realRandom = Math.random
  Math.random = () => 0.999
})
await waitForPose('spy', 45000).catch(() => {})
await page.evaluate(() => {
  Math.random = window.__realRandom
})
check('picks up the binoculars', (await pose()) === 'spy', await pose())
/** 镜筒实际转角 vs 从两眼中点指向鼠标的角度（deg），以及双手 / 常规手臂的状态 */
async function spyState(mouse) {
  await page.mouse.move(mouse.x, mouse.y, { steps: 4 })
  await page.waitForTimeout(200)
  return page.evaluate(m => {
    const svg = document.querySelector('.hm-robot svg')
    const barrels = [...svg.querySelectorAll('.hm-spy__barrel')]
    if (!barrels.length) return null
    const angles = barrels.map(g => Number(/rotate\(([-\d.]+)\)/.exec(g.getAttribute('transform'))[1]))
    const box = svg.getBoundingClientRect()
    const unit = box.width / 120
    const expected = (Math.atan2(m.y - (box.top + 18 * unit), m.x - (box.left + 59.5 * unit)) * 180) / Math.PI
    return {
      angles,
      expected: Math.round(expected * 10) / 10,
      hands: svg.querySelectorAll('.hm-spy > circle').length,
      arms: svg.querySelectorAll('.hm-spy > path.hm-limb').length,
      restArmsHidden: [...svg.querySelectorAll('.hm-arm')].every(a => getComputedStyle(a).opacity === '0')
    }
  }, mouse)
}
const aimed = st => st && st.angles.every(a => Math.abs(a - st.expected) < 1.5)
b = await box()
const spyLeft = await spyState({ x: b.x - 300, y: b.y - 160 })
check('both barrels point at a mouse on the upper left', aimed(spyLeft), JSON.stringify(spyLeft))
check('held with both hands; the resting arms are hidden', spyLeft?.hands === 2 && spyLeft?.arms === 2 && spyLeft?.restArmsHidden, JSON.stringify(spyLeft))
await shot('13-binoculars-left', await robotClip(140))
const spyRight = await spyState({ x: b.x + b.width + 60, y: b.y - 260 })
check('and follow it to the upper right', aimed(spyRight) && spyRight.expected > -90, JSON.stringify(spyRight))
await shot('14-binoculars-right', await robotClip(140))
await page.mouse.move(10, 10)

// 10. 彩蛋：每次挑动作有 1/1000 的概率捡石头扔向鼠标。钉住 Math.random 强制命中，不改产品代码
await waitUntilCalm()
await page.mouse.move(400, 300)
await page.evaluate(() => {
  window.__realRandom = Math.random
  Math.random = () => 0
  // 烟尘只停留 0.4s，截图一慢就错过：落点在页面里记下来
  window.__hmRockHit = ''
  new MutationObserver(() => {
    const hit = document.querySelector('.hm-rock.is-hit')
    if (hit) window.__hmRockHit = hit.style.transform
  }).observe(document.body, { subtree: true, childList: true, attributes: true, attributeFilter: ['class'] })
})
await waitForPose('throw', 45000).catch(() => {})
await page.evaluate(() => {
  Math.random = window.__realRandom
})
check('a 1-in-1000 roll → picks up a rock and throws it', (await pose()) === 'throw', await pose())
await page.waitForSelector('.hm-rock:not(.is-hit)', { timeout: 4000 }).catch(() => {})
await shot('12-rock-in-flight')
await page.waitForFunction(() => window.__hmRockHit, null, { timeout: 5000 }).catch(() => {})
const landed = await page.evaluate(() => window.__hmRockHit)
check('the rock lands exactly on the mouse position', landed.startsWith('translate3d(400px, 300px'), landed)
await page.waitForSelector('.hm-rock', { state: 'detached', timeout: 3000 }).catch(() => {})
check('the rock is cleaned up afterwards', (await page.locator('.hm-rock').count()) === 0)

check('no page errors', pageErrors.length === 0, pageErrors.slice(0, 3).join(' | '))
await browser.close()
console.log(failures.length ? `\n${failures.length} check(s) failed` : '\nAll checks passed')
console.log(`Screenshots: ${OUT}/${DATE}_hermes-master_*.png`)
process.exit(failures.length ? 1 : 0)
