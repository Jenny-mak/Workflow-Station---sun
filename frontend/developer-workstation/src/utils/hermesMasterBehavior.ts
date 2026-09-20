/**
 * Hermes Master（HM）行为的纯逻辑：姿态类型、随机动作选取、下落物理、气泡定位。
 * 不碰 DOM / 定时器，供 useHermesMasterBehavior 编排、单测直接覆盖。
 */

/** 自主动作：由调度器随机挑选 */
export type HmAutonomousPose = 'idle' | 'walk' | 'think' | 'sit' | 'handstand' | 'hide' | 'sleep' | 'wave' | 'spy'

/** 全部姿态 = 自主动作 + 交互/物理触发的姿态 */
export type HmPose =
  | HmAutonomousPose
  | 'dormant'
  | 'emerge'
  | 'wake'
  | 'throw'
  | 'giggle'
  | 'startled'
  | 'peek'
  | 'dragged'
  | 'fall'
  | 'land'
  | 'dizzy'
  | 'jump'
  | 'talk'

/** 机器人在页面上占的盒子（px）；与 HermesMasterFigure 的 viewBox 120×98 同比例 */
export const HM_WIDTH = 108
export const HM_HEIGHT = 88

/** 松手高度超过它就会摔晕；低于它只是轻轻落地 */
export const HM_FAINT_HEIGHT = 140
export const HM_GRAVITY = 2600
export const HM_WALK_SPEED = 46
/** 离屏幕左右边缘保留的最小间距 */
export const HM_EDGE_GAP = 8

/** 可被悬停反应 / 新动作打断的姿态；其余（摔落、晕倒、起身…）必须播完 */
const INTERRUPTIBLE: ReadonlySet<HmPose> = new Set<HmPose>([
  'idle', 'walk', 'think', 'sit', 'handstand', 'wave', 'spy', 'talk'
])

export function isInterruptible(pose: HmPose): boolean {
  return INTERRUPTIBLE.has(pose)
}

interface ActionSpec {
  pose: HmAutonomousPose
  weight: number
  /** 持续时间范围（ms）；walk 由路程决定，不用这个 */
  duration: [number, number]
}

const ACTIONS: readonly ActionSpec[] = [
  { pose: 'idle', weight: 24, duration: [1800, 4200] },
  { pose: 'walk', weight: 32, duration: [0, 0] },
  { pose: 'think', weight: 9, duration: [3500, 5500] },
  { pose: 'sit', weight: 11, duration: [5000, 9000] },
  { pose: 'wave', weight: 5, duration: [1800, 1800] },
  // 倒立与缩回 logo 的时长必须与 HermesMasterFigure 里对应 keyframes 的时长一致
  { pose: 'handstand', weight: 7, duration: [5200, 5200] },
  { pose: 'hide', weight: 7, duration: [5600, 5600] },
  { pose: 'sleep', weight: 5, duration: [12000, 24000] },
  // 双手举着双筒望远镜盯着鼠标看；权重压低，偶尔出现一次。必须留在最后一项：verify-hermes-master.mjs 靠 Math.random=0.999 选中它
  { pose: 'spy', weight: 3, duration: [4500, 7000] }
]

/** 减少动态效果时只留原地的安静动作 */
const CALM_POSES: ReadonlySet<HmAutonomousPose> = new Set<HmAutonomousPose>(['idle', 'think', 'sit', 'sleep'])

export interface PlannedAction {
  pose: HmAutonomousPose
  durationMs: number
}

/**
 * 挑下一个自主动作。不连续重复同一个动作（idle 除外），让它看起来不呆板。
 *
 * @param rand 返回 [0,1) 的随机源，单测里注入确定值
 */
export function pickNextAction(
  previous: HmPose | null,
  rand: () => number = Math.random,
  calm = false
): PlannedAction {
  const pool = ACTIONS.filter(a => (a.pose === 'idle' || a.pose !== previous) && (!calm || CALM_POSES.has(a.pose)))
  const total = pool.reduce((sum, a) => sum + a.weight, 0)
  let roll = rand() * total
  let chosen = pool[pool.length - 1]
  for (const action of pool) {
    roll -= action.weight
    if (roll < 0) {
      chosen = action
      break
    }
  }
  const [min, max] = chosen.duration
  return { pose: chosen.pose, durationMs: Math.round(min + (max - min) * rand()) }
}

export function clamp(value: number, min: number, max: number): number {
  return Math.min(Math.max(value, min), Math.max(min, max))
}

/** 机器人左边缘允许的水平范围 */
export function xBounds(viewportWidth: number): [number, number] {
  return [HM_EDGE_GAP, Math.max(HM_EDGE_GAP, viewportWidth - HM_WIDTH - HM_EDGE_GAP)]
}

/** 随机踱步目标：离当前位置至少 80px，否则看起来只是原地抖一下 */
export function pickWalkTarget(x: number, viewportWidth: number, rand: () => number = Math.random): number {
  const [min, max] = xBounds(viewportWidth)
  if (max - min < 160) return x < (min + max) / 2 ? max : min
  for (let i = 0; i < 6; i++) {
    const target = min + (max - min) * rand()
    if (Math.abs(target - x) >= 80) return target
  }
  return x < (min + max) / 2 ? max : min
}

export interface FallState {
  /** 离地高度（px），0 = 站在地面 */
  y: number
  /** 向上为正的速度（px/s） */
  vy: number
}

/** 自由落体一步；落地时 y 归零并返回 landed=true */
export function stepFall(state: FallState, dtSeconds: number): FallState & { landed: boolean } {
  const vy = state.vy - HM_GRAVITY * dtSeconds
  const y = state.y + vy * dtSeconds
  if (y <= 0) return { y: 0, vy: 0, landed: true }
  return { y, vy, landed: false }
}

/** 从多高松手决定落地后的反应 */
export function landingPose(releaseHeight: number): 'dizzy' | 'land' {
  return releaseHeight > HM_FAINT_HEIGHT ? 'dizzy' : 'land'
}

export interface BubblePlacement {
  left: number
  bottom: number
  /** 气泡尾巴相对气泡左边缘的水平位置，指向机器人头顶 */
  tailLeft: number
}

/** 聊天气泡贴在机器人头顶，整体夹在视口内 */
export function placeBubble(
  robot: { x: number; y: number },
  bubble: { width: number; height: number },
  viewport: { width: number; height: number },
  margin = 8
): BubblePlacement {
  const centerX = robot.x + HM_WIDTH / 2
  const left = clamp(centerX - bubble.width / 2, margin, viewport.width - bubble.width - margin)
  const bottom = clamp(robot.y + HM_HEIGHT + 12, margin, viewport.height - bubble.height - margin)
  const tailLeft = clamp(centerX - left, 24, bubble.width - 24)
  return { left, bottom, tailLeft }
}

/** 每次挑下一个动作时，有这么大的概率改成"捡起石头扔向鼠标" */
export const HM_THROW_CHANCE = 1 / 1000

/** 这一次要不要扔石头；减少动态效果时不扔 */
export function rollsRockThrow(rand: () => number = Math.random, calm = false): boolean {
  return !calm && rand() < HM_THROW_CHANCE
}

export interface Point {
  x: number
  y: number
}

/** 石头出手时在机器人盒子里的位置（hm-throw-arm 56% 那一帧右手所在处），换算成页面坐标 */
export function rockLaunchPoint(robot: { x: number; y: number }, viewportHeight: number): Point {
  return { x: robot.x + HM_WIDTH * 0.84, y: viewportHeight - robot.y - HM_HEIGHT * 0.76 }
}

/** 飞行时长随距离变化，近处不至于一闪而过，远处不至于慢吞吞 */
export function rockFlightMs(from: Point, to: Point): number {
  return Math.round(clamp(Math.hypot(to.x - from.x, to.y - from.y) * 0.9, 420, 900))
}

/**
 * 石头在 t∈[0,1] 时的位置：直线插值上叠一个向上拱的抛物线，t=0 在出手点、t=1 正好落在目标上。
 * 拱高随距离增长，封顶 160px，免得扔很近也划一个大弧。
 */
export function rockPosition(from: Point, to: Point, t: number): Point {
  const p = clamp(t, 0, 1)
  const arc = Math.min(Math.hypot(to.x - from.x, to.y - from.y) * 0.35, 160)
  return {
    x: from.x + (to.x - from.x) * p,
    y: from.y + (to.y - from.y) * p - arc * 4 * p * (1 - p)
  }
}

// ---------- 双筒望远镜：盯着鼠标看 ----------

/** HermesMasterFigure 的 viewBox 宽度；页面 px → viewBox 单位的换算基准 */
const FIGURE_VIEWBOX_WIDTH = 120
/** 以下坐标都是 viewBox 单位 */
const SPY_EYE_LEFT: Point = { x: 52.2, y: 18 }
const SPY_EYE_RIGHT: Point = { x: 66.8, y: 18 }
const SPY_SHOULDER_LEFT: Point = { x: 32.5, y: 44 }
const SPY_SHOULDER_RIGHT: Point = { x: 87.5, y: 44 }
/** 两只手沿镜筒方向离眼睛的平均距离，以及随目标左右偏移的幅度 */
const SPY_GRIP_DISTANCE = 10.5
const SPY_GRIP_LEAN = 4.5
/** 两只镜筒之间的中梁离眼睛多远 */
const SPY_BRIDGE_DISTANCE = 8

/** 页面坐标 → 机器人 viewBox 坐标 */
export function toFigurePoint(pagePoint: Point, robot: { x: number; y: number }, viewportHeight: number): Point {
  const scale = HM_WIDTH / FIGURE_VIEWBOX_WIDTH
  return {
    x: (pagePoint.x - robot.x) / scale,
    y: (pagePoint.y - (viewportHeight - robot.y - HM_HEIGHT)) / scale
  }
}

export interface BinocularsGeometry {
  /** 两只镜筒共同的转角（deg，0 = 水平朝右，-90 = 正上方）；各自绕自己那只眼睛转 */
  angle: number
  /** 目标在左侧：左镜筒离目标更近，要画在右镜筒上面 */
  leftOnTop: boolean
  /** 头朝目标一侧歪的角度（deg） */
  headTilt: number
  gripLeft: Point
  gripRight: Point
  /** 肩 → 握点的手臂路径（SVG path d）；肘部向外抬起，从身体外侧绕上去，不斜穿身体 */
  armLeft: string
  armRight: string
  /** 连接两只镜筒的中梁（SVG path d） */
  bridge: string
}

const round1 = (v: number) => Math.round(v * 10) / 10

/**
 * 双筒望远镜对准 target（viewBox 坐标）时的骨骼几何。HM 的手臂够不到头，
 * 所以举镜时两只手臂都不走固定长度的旋转关节，而是直接从肩画到镜筒上的握点。
 */
export function binocularsGeometry(target: Point): BinocularsGeometry {
  const midX = (SPY_EYE_LEFT.x + SPY_EYE_RIGHT.x) / 2
  const radians = Math.atan2(target.y - SPY_EYE_LEFT.y, target.x - midX)
  const ux = Math.cos(radians)
  const uy = Math.sin(radians)
  const along = (eye: Point, d: number): Point => ({ x: round1(eye.x + ux * d), y: round1(eye.y + uy * d) })
  // 靠近目标那侧的手握在镜筒前段，另一只手握在目镜附近
  const lean = clamp(ux * 1.6, -1, 1) * SPY_GRIP_LEAN
  const gripLeft = along(SPY_EYE_LEFT, SPY_GRIP_DISTANCE - lean)
  const gripRight = along(SPY_EYE_RIGHT, SPY_GRIP_DISTANCE + lean)
  const arm = (shoulder: Point, grip: Point, outward: number) =>
    `M${shoulder.x} ${shoulder.y} Q${round1(shoulder.x + outward * 4)} ${round1(grip.y + 7)} ${grip.x} ${grip.y}`
  const bridgeLeft = along(SPY_EYE_LEFT, SPY_BRIDGE_DISTANCE)
  const bridgeRight = along(SPY_EYE_RIGHT, SPY_BRIDGE_DISTANCE)
  return {
    angle: round1((radians * 180) / Math.PI),
    leftOnTop: ux < 0,
    headTilt: round1(ux * -4),
    gripLeft,
    gripRight,
    armLeft: arm(SPY_SHOULDER_LEFT, gripLeft, -1),
    armRight: arm(SPY_SHOULDER_RIGHT, gripRight, 1),
    bridge: `M${bridgeLeft.x} ${bridgeLeft.y} L${bridgeRight.x} ${bridgeRight.y}`
  }
}
