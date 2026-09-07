import { describe, it, expect } from 'vitest'
import {
  layoutRelationDiagram,
  chooseHandleSides,
  visibleFieldsForCard,
  type LayoutEdgeInput,
  type LayoutNodeInput,
} from '@platform-shared/relationDiagramLayout'

const OPTIONS = { nodeWidth: 260, columnGap: 200, rowGap: 60 }

function node(id: string, height = 100): LayoutNodeInput {
  return { id, height }
}

/** Cards overlap if their x ranges and y ranges both intersect. */
function overlaps(
  a: { x: number; y: number; h: number },
  b: { x: number; y: number; h: number },
  width: number,
): boolean {
  const xHit = a.x < b.x + width && b.x < a.x + width
  const yHit = a.y < b.y + b.h && b.y < a.y + a.h
  return xHit && yHit
}

describe('layoutRelationDiagram', () => {
  it('returns an empty layout for no tables', () => {
    expect(layoutRelationDiagram([], [], OPTIONS)).toEqual({ positions: {}, ranks: {} })
  })

  it('seats the referenced main table left of the FK table pointing at it', () => {
    const edges: LayoutEdgeInput[] = [{ source: 'attachment', target: 'meeting' }]
    const { positions, ranks } = layoutRelationDiagram(
      [node('attachment'), node('meeting')],
      edges,
      OPTIONS,
    )

    expect(ranks.attachment).toBe(0)
    expect(ranks.meeting).toBe(1)
    // Ranks still run FK -> PK, but the x axis is mirrored so main sits left.
    expect(positions.meeting.x).toBe(0)
    expect(positions.attachment.x - positions.meeting.x).toBe(OPTIONS.nodeWidth + OPTIONS.columnGap)
  })

  it('puts the main table leftmost when several tables reference it', () => {
    const edges: LayoutEdgeInput[] = [
      { source: 'attachment', target: 'meeting' },
      { source: 'participants', target: 'meeting' },
      { source: 'remark', target: 'meeting' },
    ]
    const { positions } = layoutRelationDiagram(
      [node('attachment'), node('participants'), node('remark'), node('meeting')],
      edges,
      OPTIONS,
    )

    const leftmost = Math.min(...Object.values(positions).map(p => p.x))
    expect(positions.meeting.x).toBe(leftmost)
    expect(positions.attachment.x).toBeGreaterThan(positions.meeting.x)
  })

  it('chains multi-hop relations into successive columns', () => {
    const edges: LayoutEdgeInput[] = [
      { source: 'attachment', target: 'participants' },
      { source: 'participants', target: 'meeting' },
    ]
    const { ranks } = layoutRelationDiagram(
      [node('attachment'), node('participants'), node('meeting')],
      edges,
      OPTIONS,
    )

    expect(ranks.attachment).toBe(0)
    expect(ranks.participants).toBe(1)
    expect(ranks.meeting).toBe(2)
  })

  it('leaves the gutter between columns free of cards so edges have room', () => {
    const edges: LayoutEdgeInput[] = [{ source: 'a', target: 'b' }]
    const { positions } = layoutRelationDiagram([node('a'), node('b')], edges, OPTIONS)

    const [left, right] = [positions.a, positions.b].sort((p, q) => p.x - q.x)
    const gutterStart = left.x + OPTIONS.nodeWidth
    const gutterEnd = right.x
    expect(gutterEnd - gutterStart).toBe(OPTIONS.columnGap)
    // No card may start inside the gutter.
    Object.values(positions).forEach(p => {
      expect(p.x >= gutterEnd || p.x + OPTIONS.nodeWidth <= gutterStart).toBe(true)
    })
  })

  it('never overlaps two cards, including different heights in one column', () => {
    const nodes = [node('a', 80), node('b', 240), node('c', 120), node('d', 60)]
    const edges: LayoutEdgeInput[] = [
      { source: 'a', target: 'd' },
      { source: 'b', target: 'd' },
      { source: 'c', target: 'd' },
    ]
    const { positions } = layoutRelationDiagram(nodes, edges, OPTIONS)

    const boxes = nodes.map(n => ({ ...positions[n.id], h: n.height }))
    for (let i = 0; i < boxes.length; i++) {
      for (let j = i + 1; j < boxes.length; j++) {
        expect(overlaps(boxes[i], boxes[j], OPTIONS.nodeWidth)).toBe(false)
      }
    }
  })

  it('places unrelated tables without stacking them on each other', () => {
    const nodes = [node('a', 100), node('b', 100), node('c', 100)]
    const { positions, ranks } = layoutRelationDiagram(nodes, [], OPTIONS)

    // No edges means one column; cards must still be spaced vertically.
    expect(Object.values(ranks)).toEqual([0, 0, 0])
    const ys = nodes.map(n => positions[n.id].y).sort((x, y) => x - y)
    expect(ys[1] - ys[0]).toBe(100 + OPTIONS.rowGap)
    expect(ys[2] - ys[1]).toBe(100 + OPTIONS.rowGap)
  })

  it('keeps an edge to a one column span so it never crosses an intermediate card', () => {
    // attachment -> meeting would span 2 columns under plain longest-path
    // ranking, cutting straight through participants in the middle column.
    const edges: LayoutEdgeInput[] = [
      { source: 'attachment', target: 'meeting' },
      { source: 'participants', target: 'meeting' },
      { source: 'people', target: 'participants' },
    ]
    const { ranks } = layoutRelationDiagram(
      [node('attachment'), node('participants'), node('meeting'), node('people')],
      edges,
      OPTIONS,
    )

    edges.forEach(e => {
      expect(ranks[e.target] - ranks[e.source]).toBe(1)
    })
  })

  it('still spans more than one column only when the graph forces it', () => {
    // a -> c and a -> b -> c: `a` cannot be adjacent to both b and c.
    const edges: LayoutEdgeInput[] = [
      { source: 'a', target: 'b' },
      { source: 'b', target: 'c' },
      { source: 'a', target: 'c' },
    ]
    const { ranks } = layoutRelationDiagram([node('a'), node('b'), node('c')], edges, OPTIONS)

    expect(ranks.b - ranks.a).toBe(1)
    expect(ranks.c - ranks.b).toBe(1)
    expect(ranks.c - ranks.a).toBe(2)
  })

  it('never pulls a source level with or left of its own dependencies', () => {
    const edges: LayoutEdgeInput[] = [
      { source: 'a', target: 'b' },
      { source: 'b', target: 'c' },
    ]
    const { ranks } = layoutRelationDiagram([node('a'), node('b'), node('c')], edges, OPTIONS)

    expect(ranks.a).toBeLessThan(ranks.b)
    expect(ranks.b).toBeLessThan(ranks.c)
  })

  it('aligns the two anchor ROWS, not the card centres, so the edge is straight', () => {
    // The line attaches to a field row: anchors at different offsets inside
    // differently sized cards must still end up on one horizontal line.
    const { positions } = layoutRelationDiagram(
      [node('fk', 90), node('main', 250)],
      [{ source: 'fk', target: 'main', sourceAnchorOffset: 72, targetAnchorOffset: 200 }],
      OPTIONS,
    )

    expect(positions.fk.y + 72).toBeCloseTo(positions.main.y + 200, 6)
  })

  it('falls back to card centres when an edge carries no anchor offsets', () => {
    const { positions } = layoutRelationDiagram(
      [node('fk', 90), node('main', 250)],
      [{ source: 'fk', target: 'main' }],
      OPTIONS,
    )

    expect(positions.fk.y + 45).toBeCloseTo(positions.main.y + 125, 6)
  })

  it('balances a hub card against the average of its partner rows', () => {
    const nodes = [node('a', 100), node('b', 100), node('c', 100), node('hub', 100)]
    const { positions } = layoutRelationDiagram(
      nodes,
      [
        { source: 'a', target: 'hub', sourceAnchorOffset: 60, targetAnchorOffset: 60 },
        { source: 'b', target: 'hub', sourceAnchorOffset: 60, targetAnchorOffset: 60 },
        { source: 'c', target: 'hub', sourceAnchorOffset: 60, targetAnchorOffset: 60 },
      ],
      OPTIONS,
    )

    const spokeAnchors = ['a', 'b', 'c'].map(id => positions[id].y + 60)
    const average = spokeAnchors.reduce((s, v) => s + v, 0) / spokeAnchors.length
    expect(positions.hub.y + 60).toBeCloseTo(average, 6)
  })

  it('slides a stacked column as one block, keeping spacing so cards never collide', () => {
    const nodes = [node('a', 100), node('b', 100), node('hub', 100)]
    const { positions } = layoutRelationDiagram(
      nodes,
      [
        { source: 'a', target: 'hub' },
        { source: 'b', target: 'hub' },
      ],
      OPTIONS,
    )

    // a and b share a column: whatever the shift, their gap is preserved.
    expect(Math.abs(positions.a.y - positions.b.y)).toBe(100 + OPTIONS.rowGap)
  })

  it('settles on a layout when relations form a cycle', () => {
    const edges: LayoutEdgeInput[] = [
      { source: 'a', target: 'b' },
      { source: 'b', target: 'a' },
    ]
    const { positions } = layoutRelationDiagram([node('a'), node('b')], edges, OPTIONS)

    expect(Object.keys(positions).sort()).toEqual(['a', 'b'])
    expect(Number.isFinite(positions.a.x)).toBe(true)
    expect(Number.isFinite(positions.b.x)).toBe(true)
  })

  it('ignores self-references when ranking', () => {
    const { ranks } = layoutRelationDiagram([node('a')], [{ source: 'a', target: 'a' }], OPTIONS)
    expect(ranks.a).toBe(0)
  })
})

describe('visibleFieldsForCard', () => {
  const field = (fieldName: string, isPrimaryKey = false, isForeignKey = false) => ({
    fieldName,
    isPrimaryKey,
    isForeignKey,
  })

  it('keeps both key kinds while collapsed, since lines anchor on them', () => {
    const fields = [field('id', true), field('main_id', false, true), field('note')]
    const visible = visibleFieldsForCard(fields, false)

    expect(visible.map(f => f.fieldName)).toEqual(['id', 'main_id'])
  })

  it('hides non-key fields while collapsed', () => {
    const fields = [field('id', true), field('note'), field('amount')]
    expect(visibleFieldsForCard(fields, false).map(f => f.fieldName)).toEqual(['id'])
  })

  it('shows every field once expanded', () => {
    const fields = [field('id', true), field('note')]
    expect(visibleFieldsForCard(fields, true).length).toBe(2)
  })
})

describe('chooseHandleSides', () => {
  it('leaves the left card on its right edge and enters the right card on its left', () => {
    expect(chooseHandleSides(0, 460)).toEqual({ source: 'r', target: 'l' })
  })

  it('mirrors the sides when the source sits to the right of the target', () => {
    expect(chooseHandleSides(460, 0)).toEqual({ source: 'l', target: 'r' })
  })
})
