import { onBeforeUnmount, onMounted, ref, type Ref } from 'vue'
import {
  HM_HEIGHT,
  HM_WALK_SPEED,
  HM_WIDTH,
  clamp,
  isInterruptible,
  landingPose,
  pickNextAction,
  pickWalkTarget,
  rockFlightMs,
  rockLaunchPoint,
  rockPosition,
  rollsRockThrow,
  toFigurePoint,
  stepFall,
  xBounds,
  type HmPose,
  type Point
} from '@/utils/hermesMasterBehavior'

/** 这些时长必须与 HermesMasterFigure.vue 里同名姿态的 keyframes 时长一致 */
/** 出场：logo → 探头 → 伸出四肢 */
const EMERGE_MS = 4600
const WAKE_MS = 1700
/** 捡石头 → 抡臂 → 出手 → 收势；石头在 RELEASE 时刻离手（对应 hm-throw-arm 的 56%） */
const THROW_MS = 2600
const THROW_RELEASE_MS = 1450
/** 石头砸到目标后的小烟尘停留多久 */
const ROCK_HIT_MS = 420
const JUMP_MS = 900
const LAND_MS = 450
const DIZZY_MS = 2600
const STARTLED_MS = 900
const GIGGLE_MS = 1400
const WAVE_MS = 1800
const PEEK_MS = 2200
/** 按下后移动超过这个距离才算拖拽，否则算点击 */
const DRAG_THRESHOLD = 5
/** 在身上来回蹭的累计路程超过它 → 挠痒反应 */
const TICKLE_DISTANCE = 260
/** 停留超过它 → 挥手；不到它就划走 → 被吓一跳 */
const HOVER_DWELL_MS = 320
const REACTION_COOLDOWN_MS = 1600

/** 飞行中的石头（页面坐标）；hit = 已砸到目标，正在冒烟尘 */
export interface HmRock extends Point {
  hit: boolean
  /** 自转角度（deg） */
  spin: number
}

export interface HermesMasterBehaviorOptions {
  /** 聊天气泡打开时原地待命、不再随机走动：返回此刻该摆的姿态；气泡关着返回 null */
  chatPose: () => 'idle' | 'think' | null
  /** 单击（未发生拖拽） */
  onClick: () => void
}

export function useHermesMasterBehavior(el: Ref<HTMLElement | null>, options: HermesMasterBehaviorOptions) {
  /** 打开 DW 时是一块未激活的 logo，被点击后才出场并开始活动 */
  const pose = ref<HmPose>('dormant')
  const x = ref(0)
  /** 离地高度（px） */
  const y = ref(0)
  /** 用户让它去休息：睡到被点醒为止 */
  const resting = ref(false)
  const rock = ref<HmRock | null>(null)
  /** 望远镜要对准的点（机器人 viewBox 坐标）；只在 spy 姿态期间更新，免得平时每次鼠标移动都触发渲染 */
  const aim = ref<Point | null>(null)

  const reducedMotion = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false

  let poseTimer: ReturnType<typeof setTimeout> | null = null
  let frame = 0
  let lastFrameAt = 0
  let walkTarget: number | null = null
  let vy = 0
  let releaseHeight = 0
  let swing = 0
  let swingTarget = 0

  // 拖拽
  let pointerId: number | null = null
  let pressX = 0
  let pressY = 0
  let grabDx = 0
  let grabDy = 0
  let dragging = false
  let pressCancelled = false
  let lastMoveX = 0
  let lastMoveAt = 0

  // 悬停
  let hoverStartedAt = 0
  let hoverDistance = 0
  let hoverLastX = 0
  let hoverLastY = 0
  let hoverTimer: ReturnType<typeof setTimeout> | null = null
  let peekTimer: ReturnType<typeof setTimeout> | null = null
  let throwTimer: ReturnType<typeof setTimeout> | null = null
  let rockTimer: ReturnType<typeof setTimeout> | null = null
  let rockFrame = 0
  /** 鼠标最后出现的位置：扔石头的目标 */
  let lastPointer: Point | null = null
  let lastReactionAt = 0

  function clearPoseTimer() {
    if (poseTimer) clearTimeout(poseTimer)
    poseTimer = null
  }

  function setVar(name: string, value: string) {
    el.value?.style.setProperty(name, value)
  }

  /** 进入一个定时姿态，到点后执行 then（默认回到自主调度） */
  function hold(next: HmPose, ms: number, then: () => void = scheduleNext) {
    clearPoseTimer()
    walkTarget = null
    pose.value = next
    poseTimer = setTimeout(then, ms)
  }

  function scheduleNext() {
    clearPoseTimer()
    walkTarget = null
    if (resting.value) {
      pose.value = 'sleep'
      return
    }
    const chatPose = options.chatPose()
    if (chatPose) {
      pose.value = chatPose
      return
    }
    if (rollsRockThrow(Math.random, reducedMotion)) {
      throwRock()
      return
    }
    const action = pickNextAction(pose.value, Math.random, reducedMotion)
    if (action.pose === 'walk') {
      walkTarget = pickWalkTarget(x.value, window.innerWidth)
      setVar('--hm-dir', walkTarget > x.value ? '1' : '-1')
      pose.value = 'walk'
      ensureLoop()
      return
    }
    if (action.pose === 'spy') updateAim()
    hold(action.pose, action.durationMs, action.pose === 'sleep' ? wakeUp : scheduleNext)
  }

  function updateAim() {
    // 鼠标还没在页面上动过：先朝页面上方正中张望
    const target = lastPointer ?? { x: window.innerWidth / 2, y: window.innerHeight * 0.3 }
    aim.value = toFigurePoint(target, { x: x.value, y: y.value }, window.innerHeight)
  }

  // ---------- 彩蛋：捡起地上的石头扔向鼠标 ----------
  function throwRock() {
    hold('throw', THROW_MS)
    if (throwTimer) clearTimeout(throwTimer)
    throwTimer = setTimeout(launchRock, THROW_RELEASE_MS)
  }

  function launchRock() {
    throwTimer = null
    // 出手前被拎走了：这一下就不扔了
    if (pose.value !== 'throw') return
    const from = rockLaunchPoint({ x: x.value, y: y.value }, window.innerHeight)
    // 鼠标还没在页面上动过：朝页面上方正中扔
    const to = lastPointer ?? { x: window.innerWidth / 2, y: window.innerHeight * 0.3 }
    const duration = rockFlightMs(from, to)
    const startedAt = performance.now()
    if (rockTimer) clearTimeout(rockTimer)
    cancelAnimationFrame(rockFrame)
    const fly = (now: number) => {
      const t = (now - startedAt) / duration
      if (t < 1) {
        rock.value = { ...rockPosition(from, to, t), hit: false, spin: t * 540 }
        rockFrame = requestAnimationFrame(fly)
        return
      }
      rockFrame = 0
      rock.value = { ...to, hit: true, spin: 0 }
      rockTimer = setTimeout(() => (rock.value = null), ROCK_HIT_MS)
    }
    rockFrame = requestAnimationFrame(fly)
  }

  function wakeUp(then: () => void = scheduleNext) {
    resting.value = false
    hold('wake', WAKE_MS, then)
  }

  // ---------- 帧循环：只在踱步 / 下落 / 被拎着时运行 ----------
  function ensureLoop() {
    if (frame) return
    lastFrameAt = performance.now()
    frame = requestAnimationFrame(tick)
  }

  function tick(now: number) {
    frame = 0
    // 切到后台标签页再回来时 dt 会很大，封顶避免瞬移 / 穿地
    const dt = Math.min((now - lastFrameAt) / 1000, 0.05)
    lastFrameAt = now

    if (pose.value === 'walk' && walkTarget !== null) {
      const step = HM_WALK_SPEED * dt
      const remaining = walkTarget - x.value
      if (Math.abs(remaining) <= step) {
        x.value = walkTarget
        scheduleNext()
      } else {
        x.value += Math.sign(remaining) * step
      }
    } else if (pose.value === 'fall') {
      const next = stepFall({ y: y.value, vy }, dt)
      y.value = next.y
      vy = next.vy
      if (next.landed) onLanded()
    } else if (pose.value === 'dragged') {
      swing += (swingTarget - swing) * 0.18
      swingTarget *= 0.9
      setVar('--hm-swing', `${swing.toFixed(1)}deg`)
    }

    if (pose.value === 'walk' || pose.value === 'fall' || pose.value === 'dragged') {
      frame = requestAnimationFrame(tick)
    }
  }

  function onLanded() {
    if (landingPose(releaseHeight) === 'dizzy') {
      hold('dizzy', DIZZY_MS, () => hold('jump', JUMP_MS))
    } else {
      hold('land', LAND_MS)
    }
  }

  // ---------- 拖拽 ----------
  function onPointerDown(e: PointerEvent) {
    if (e.button !== 0 || !el.value) return
    pointerId = e.pointerId
    pressX = e.clientX
    pressY = e.clientY
    const rect = el.value.getBoundingClientRect()
    grabDx = e.clientX - rect.left
    grabDy = e.clientY - rect.top
    dragging = false
    pressCancelled = false
    // 按下即不再算"划过"：别在点开聊天的同时挥手 / 被吓到
    cancelHoverTimer()
    hoverStartedAt = 0
    el.value.setPointerCapture(e.pointerId)
  }

  function onPointerMove(e: PointerEvent) {
    if (pointerId !== e.pointerId) {
      trackHover(e)
      return
    }
    if (!dragging) {
      if (Math.hypot(e.clientX - pressX, e.clientY - pressY) < DRAG_THRESHOLD) return
      // 未激活时拎不起来，拖过的这一下也不算点击：只有干净的点击能激活它
      if (pose.value === 'dormant') {
        pressCancelled = true
        return
      }
      dragging = true
      clearPoseTimer()
      cancelHoverTimer()
      walkTarget = null
      resting.value = false
      swing = 0
      swingTarget = 0
      lastMoveX = e.clientX
      lastMoveAt = performance.now()
      pose.value = 'dragged'
      ensureLoop()
    }
    const [minX, maxX] = xBounds(window.innerWidth)
    x.value = clamp(e.clientX - grabDx, minX, maxX)
    y.value = clamp(window.innerHeight - (e.clientY - grabDy) - HM_HEIGHT, 0, window.innerHeight - HM_HEIGHT)

    const now = performance.now()
    const elapsed = Math.max(now - lastMoveAt, 1)
    // 往右拖 → 身体向左后方荡
    swingTarget = clamp(((e.clientX - lastMoveX) / elapsed) * -22, -32, 32)
    lastMoveX = e.clientX
    lastMoveAt = now
  }

  function onPointerUp(e: PointerEvent) {
    if (pointerId !== e.pointerId) return
    el.value?.releasePointerCapture(e.pointerId)
    pointerId = null
    if (!dragging) {
      if (!pressCancelled) press()
      return
    }
    dragging = false
    setVar('--hm-swing', '0deg')
    releaseHeight = y.value
    if (y.value <= 0) {
      scheduleNext()
      return
    }
    vy = 0
    pose.value = 'fall'
    ensureLoop()
  }

  /** 单击 / 键盘触发：未激活时是激活（出场），之后才是外层的点击行为（开关聊天） */
  function press() {
    if (pose.value === 'dormant') hold('emerge', EMERGE_MS)
    else options.onClick()
  }

  // ---------- 鼠标划过（不点击） ----------
  function cancelHoverTimer() {
    if (hoverTimer) clearTimeout(hoverTimer)
    hoverTimer = null
  }

  function react(next: 'wave' | 'giggle' | 'startled' | 'peek') {
    const now = performance.now()
    if (now - lastReactionAt < REACTION_COOLDOWN_MS) return
    lastReactionAt = now
    if (next === 'peek') {
      // 睡着时只睁一只眼偷看，随后接着睡；不动睡眠本身的定时器
      pose.value = 'peek'
      if (peekTimer) clearTimeout(peekTimer)
      peekTimer = setTimeout(() => {
        if (pose.value === 'peek') pose.value = 'sleep'
      }, PEEK_MS)
      return
    }
    hold(next, next === 'wave' ? WAVE_MS : next === 'giggle' ? GIGGLE_MS : STARTLED_MS)
  }

  function onPointerEnter(e: PointerEvent) {
    if (e.pointerType === 'touch' || pointerId !== null || options.chatPose()) return
    hoverStartedAt = performance.now()
    hoverDistance = 0
    hoverLastX = e.clientX
    hoverLastY = e.clientY
    cancelHoverTimer()
    if (pose.value === 'sleep') {
      react('peek')
      return
    }
    hoverTimer = setTimeout(() => {
      hoverTimer = null
      if (isInterruptible(pose.value) && pose.value !== 'handstand') react('wave')
    }, HOVER_DWELL_MS)
  }

  function trackHover(e: PointerEvent) {
    if (e.pointerType === 'touch' || !hoverStartedAt) return
    hoverDistance += Math.hypot(e.clientX - hoverLastX, e.clientY - hoverLastY)
    hoverLastX = e.clientX
    hoverLastY = e.clientY
    if (hoverDistance > TICKLE_DISTANCE && isInterruptible(pose.value)) {
      hoverDistance = 0
      lastReactionAt = 0
      react('giggle')
    }
  }

  function onPointerLeave(e: PointerEvent) {
    if (e.pointerType === 'touch' || !hoverStartedAt) return
    const dwell = performance.now() - hoverStartedAt
    hoverStartedAt = 0
    cancelHoverTimer()
    if (dwell < HOVER_DWELL_MS && pointerId === null && isInterruptible(pose.value)) react('startled')
  }

  // ---------- 眼睛跟随鼠标 ----------
  let lookFrame = 0
  function onWindowPointerMove(e: PointerEvent) {
    lastPointer = { x: e.clientX, y: e.clientY }
    if (lookFrame || !el.value) return
    lookFrame = requestAnimationFrame(() => {
      lookFrame = 0
      if (!el.value) return
      const cx = x.value + HM_WIDTH / 2
      const cy = window.innerHeight - y.value - HM_HEIGHT * 0.8
      const dx = e.clientX - cx
      const dy = e.clientY - cy
      const reach = Math.max(Math.hypot(dx, dy), 140)
      setVar('--hm-look-x', (dx / reach).toFixed(2))
      setVar('--hm-look-y', (dy / reach).toFixed(2))
      if (pose.value === 'spy') updateAim()
    })
  }

  function onResize() {
    const [minX, maxX] = xBounds(window.innerWidth)
    x.value = clamp(x.value, minX, maxX)
    if (walkTarget !== null) walkTarget = clamp(walkTarget, minX, maxX)
  }

  // ---------- 供外层调用 ----------
  /** 聊天开始 / 结束时让它停下或恢复自主活动；摔落等物理姿态不打断 */
  function settle() {
    if (pose.value === 'sleep' || pose.value === 'peek') {
      wakeUp()
      return
    }
    if (isInterruptible(pose.value) || pose.value === 'hide') scheduleNext()
  }

  /** 回复到达时张嘴说一会儿话 */
  function talk(ms: number) {
    if (isInterruptible(pose.value)) hold('talk', ms)
  }

  /** 原地睡下，直到被点醒 */
  function rest() {
    resting.value = true
    if (isInterruptible(pose.value) || pose.value === 'hide') scheduleNext()
  }

  onMounted(() => {
    x.value = xBounds(window.innerWidth)[1] - 16
    window.addEventListener('pointermove', onWindowPointerMove, { passive: true })
    window.addEventListener('resize', onResize)
  })

  onBeforeUnmount(() => {
    clearPoseTimer()
    cancelHoverTimer()
    if (peekTimer) clearTimeout(peekTimer)
    if (throwTimer) clearTimeout(throwTimer)
    if (rockTimer) clearTimeout(rockTimer)
    if (rockFrame) cancelAnimationFrame(rockFrame)
    if (frame) cancelAnimationFrame(frame)
    if (lookFrame) cancelAnimationFrame(lookFrame)
    window.removeEventListener('pointermove', onWindowPointerMove)
    window.removeEventListener('resize', onResize)
  })

  return {
    pose,
    x,
    y,
    resting,
    rock,
    aim,
    onPointerDown,
    onPointerMove,
    onPointerUp,
    onPointerEnter,
    onPointerLeave,
    press,
    settle,
    talk,
    rest
  }
}
