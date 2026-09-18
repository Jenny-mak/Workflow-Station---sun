import { describe, expect, it } from 'vitest'
import {
  HM_EDGE_GAP,
  HM_FAINT_HEIGHT,
  HM_HEIGHT,
  HM_THROW_CHANCE,
  HM_WIDTH,
  isInterruptible,
  landingPose,
  pickNextAction,
  pickWalkTarget,
  placeBubble,
  rockFlightMs,
  rockLaunchPoint,
  rockPosition,
  rollsRockThrow,
  stepFall,
  xBounds
} from '../hermesMasterBehavior'

/** 依次吐出给定值的确定性随机源 */
function sequence(...values: number[]) {
  let i = 0
  return () => values[Math.min(i++, values.length - 1)]
}

describe('pickNextAction', () => {
  it('never repeats the previous action, except idle', () => {
    for (let i = 0; i < 100; i++) {
      const roll = i / 100
      expect(pickNextAction('walk', sequence(roll, 0.5)).pose).not.toBe('walk')
      expect(pickNextAction('handstand', sequence(roll, 0.5)).pose).not.toBe('handstand')
    }
    expect(pickNextAction('idle', sequence(0, 0)).pose).toBe('idle')
  })

  it('covers every autonomous action across the roll range', () => {
    const seen = new Set<string>()
    for (let i = 0; i < 1000; i++) seen.add(pickNextAction(null, sequence(i / 1000, 0.5)).pose)
    expect([...seen].sort()).toEqual(['handstand', 'hide', 'idle', 'sit', 'sleep', 'think', 'walk', 'wave'])
  })

  it('keeps durations inside the configured range', () => {
    expect(pickNextAction(null, sequence(0, 0))).toEqual({ pose: 'idle', durationMs: 1800 })
    expect(pickNextAction(null, sequence(0, 0.999)).durationMs).toBeLessThanOrEqual(4200)
  })

  it('only picks stationary actions when motion is reduced', () => {
    for (let i = 0; i < 200; i++) {
      expect(['idle', 'think', 'sit', 'sleep']).toContain(pickNextAction(null, sequence(i / 200, 0.5), true).pose)
    }
  })
})

describe('walking', () => {
  it('keeps the robot inside the viewport', () => {
    expect(xBounds(1200)).toEqual([HM_EDGE_GAP, 1200 - HM_WIDTH - HM_EDGE_GAP])
    // 视口比机器人还窄：范围退化成一个点，而不是 min > max
    expect(xBounds(50)).toEqual([HM_EDGE_GAP, HM_EDGE_GAP])
  })

  it('picks a target that is far enough away to look like a walk', () => {
    for (let i = 0; i < 50; i++) {
      const target = pickWalkTarget(600, 1400, sequence(i / 50))
      expect(Math.abs(target - 600)).toBeGreaterThanOrEqual(80)
      expect(target).toBeGreaterThanOrEqual(xBounds(1400)[0])
      expect(target).toBeLessThanOrEqual(xBounds(1400)[1])
    }
  })

  it('walks to the far edge when the viewport is too narrow for a random target', () => {
    const [min, max] = xBounds(240)
    expect(pickWalkTarget(min, 240)).toBe(max)
    expect(pickWalkTarget(max, 240)).toBe(min)
  })
})

describe('falling', () => {
  it('accelerates downwards and lands exactly on the ground', () => {
    let state = { y: 300, vy: 0 }
    let landed = false
    let steps = 0
    while (!landed && steps < 1000) {
      const next = stepFall(state, 1 / 60)
      expect(next.y).toBeLessThan(state.y)
      state = next
      landed = next.landed
      steps++
    }
    expect(landed).toBe(true)
    expect(state).toMatchObject({ y: 0, vy: 0 })
    // 300px 自由落体约 0.48s
    expect(steps).toBeGreaterThan(20)
    expect(steps).toBeLessThan(40)
  })

  it('faints only when dropped from above the threshold', () => {
    expect(landingPose(HM_FAINT_HEIGHT + 1)).toBe('dizzy')
    expect(landingPose(HM_FAINT_HEIGHT)).toBe('land')
    expect(landingPose(12)).toBe('land')
  })
})

describe('isInterruptible', () => {
  it('protects the physical sequence from hover reactions', () => {
    for (const pose of ['dormant', 'emerge', 'throw', 'fall', 'dizzy', 'jump', 'land', 'wake', 'dragged'] as const) {
      expect(isInterruptible(pose)).toBe(false)
    }
    expect(isInterruptible('walk')).toBe(true)
  })
})

describe('placeBubble', () => {
  const bubble = { width: 348, height: 450 }
  const viewport = { width: 1440, height: 900 }

  it('sits above the robot with the tail over its head', () => {
    const placed = placeBubble({ x: 600, y: 0 }, bubble, viewport)
    expect(placed.bottom).toBe(HM_HEIGHT + 12)
    expect(placed.left + placed.tailLeft).toBe(600 + HM_WIDTH / 2)
  })

  it('stays inside the viewport at the right edge and keeps the tail on the bubble', () => {
    const placed = placeBubble({ x: 1440 - HM_WIDTH - 8, y: 0 }, bubble, viewport)
    expect(placed.left).toBe(1440 - 348 - 8)
    expect(placed.tailLeft).toBeLessThanOrEqual(348 - 24)
  })

  it('does not run off the top while the robot is held high', () => {
    const placed = placeBubble({ x: 600, y: 800 }, bubble, viewport)
    expect(placed.bottom + bubble.height).toBeLessThanOrEqual(900 - 8)
  })
})

describe('rock throw', () => {
  it('happens once in a thousand picks', () => {
    expect(HM_THROW_CHANCE).toBe(0.001)
    expect(rollsRockThrow(() => 0.0009)).toBe(true)
    expect(rollsRockThrow(() => 0.001)).toBe(false)
    expect(rollsRockThrow(() => 0.5)).toBe(false)
  })

  it('never happens when motion is reduced', () => {
    expect(rollsRockThrow(() => 0, true)).toBe(false)
  })

  it('flies from the raised hand and lands exactly on the target', () => {
    const from = rockLaunchPoint({ x: 1000, y: 0 }, 900)
    const to = { x: 300, y: 200 }
    expect(from.x).toBeGreaterThan(1000 + HM_WIDTH / 2)
    expect(from.y).toBeLessThan(900 - HM_HEIGHT / 2)
    expect(rockPosition(from, to, 0)).toEqual(from)
    expect(rockPosition(from, to, 1)).toEqual(to)
    expect(rockPosition(from, to, 2)).toEqual(to)
  })

  it('arcs above the straight line mid-flight', () => {
    const from = { x: 1000, y: 800 }
    const to = { x: 200, y: 800 }
    const mid = rockPosition(from, to, 0.5)
    expect(mid.x).toBe(600)
    expect(mid.y).toBe(800 - 160)
    // 近距离只拱一点点
    expect(rockPosition(from, { x: 900, y: 800 }, 0.5).y).toBe(800 - 35)
  })

  it('scales the flight time with distance inside sane bounds', () => {
    expect(rockFlightMs({ x: 0, y: 0 }, { x: 50, y: 0 })).toBe(420)
    expect(rockFlightMs({ x: 0, y: 0 }, { x: 600, y: 0 })).toBe(540)
    expect(rockFlightMs({ x: 0, y: 0 }, { x: 3000, y: 0 })).toBe(900)
  })
})
