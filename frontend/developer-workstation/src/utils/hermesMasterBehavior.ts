/**
 * Hermes Master（HM）行为的纯逻辑：姿态类型、随机动作选取、下落物理、气泡定位。
 * 不碰 DOM / 定时器，供 useHermesMasterBehavior 编排、单测直接覆盖。
 */

/** 自主动作：由调度器随机挑选 */
export type HmAutonomousPose = 'idle' | 'walk' | 'think' | 'sit' | 'handstand' | 'hide' | 'sleep' | 'wave'

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
  'idle', 'walk', 'think', 'sit', 'handstand', 'wave', 'talk'
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
  { pose: 'sleep', weight: 5, duration: [12000, 24000] }
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
