/**
 * Layered layout for the Data Model Relation Config ER diagram.
 *
 * Goals (in priority order):
 *  1. No relation line may run underneath a table card. Cards are placed in
 *     discrete columns ("ranks") and every edge only ever spans the gutter
 *     between two neighbouring ranks, which is kept card-free.
 *  2. Related tables sit next to each other so the FK -> PK direction reads
 *     left-to-right.
 *
 * The layout is deliberately dependency-free (no dagre/elk): the graphs here
 * are a handful of tables, so a longest-path ranking plus barycenter ordering
 * is both sufficient and cheap.
 */

/** Minimal edge shape the layout needs: an FK on `source` pointing at a PK on `target`. */
export interface LayoutEdgeInput {
  source: string
  target: string
  /**
   * Distance in px from each card's top to the centre of the field row the
   * line attaches to. A line is drawn between those rows, not between card
   * centres, so alignment has to work in row space to come out straight.
   */
  sourceAnchorOffset?: number
  targetAnchorOffset?: number
}

export interface LayoutNodeInput {
  id: string
  /** Rendered height in px; drives vertical packing inside a column. */
  height: number
}

export interface LayoutOptions {
  nodeWidth: number
  /** Card-free horizontal gutter between two ranks; edges are routed through it. */
  columnGap: number
  /** Vertical gap between two cards in the same column. */
  rowGap: number
}

export interface LayoutResult {
  positions: Record<string, { x: number; y: number }>
  /** Rank (column index) per node, exposed for edge side selection and tests. */
  ranks: Record<string, number>
}

/**
 * Assign each node a rank so that every edge goes from a lower rank to a higher
 * one. Cycles (A -> B -> A) cannot be ranked consistently; the iteration cap
 * makes them settle instead of looping forever.
 */
function computeRanks(nodeIds: string[], edges: LayoutEdgeInput[]): Record<string, number> {
  const ranks: Record<string, number> = {}
  nodeIds.forEach(id => {
    ranks[id] = 0
  })

  const relevant = edges.filter(e => e.source in ranks && e.target in ranks && e.source !== e.target)
  // Longest-path ranking: relax until stable, capped at node count to break cycles.
  for (let pass = 0; pass < nodeIds.length; pass++) {
    let changed = false
    for (const edge of relevant) {
      const wanted = ranks[edge.source] + 1
      if (ranks[edge.target] < wanted) {
        ranks[edge.target] = wanted
        changed = true
      }
    }
    if (!changed) break
  }

  tightenRanks(nodeIds, relevant, ranks)
  return ranks
}

/**
 * Longest-path ranking pins every node as far left as its own dependencies
 * allow, which leaves an edge spanning several columns whenever its source has
 * no long chain behind it — and a multi-column edge has to cross whatever
 * cards sit in the columns between. Pull each such source rightwards to sit
 * directly left of its nearest target so edges span one gutter wherever the
 * graph permits it.
 */
function tightenRanks(
  nodeIds: string[],
  edges: LayoutEdgeInput[],
  ranks: Record<string, number>,
): void {
  const outgoing: Record<string, string[]> = {}
  const incoming: Record<string, string[]> = {}
  edges.forEach(e => {
    ;(outgoing[e.source] ||= []).push(e.target)
    ;(incoming[e.target] ||= []).push(e.source)
  })

  for (let pass = 0; pass < nodeIds.length; pass++) {
    let changed = false
    for (const id of nodeIds) {
      const targets = outgoing[id]
      if (!targets?.length) continue
      // Sit one column left of the closest table this one points at...
      const nearestTarget = Math.min(...targets.map(t => ranks[t]))
      const candidate = nearestTarget - 1
      // ...but never left of, or level with, anything pointing at this one.
      const sources = incoming[id] || []
      const floor = sources.length ? Math.max(...sources.map(s => ranks[s])) + 1 : 0
      const next = Math.max(floor, candidate)
      if (next > ranks[id]) {
        ranks[id] = next
        changed = true
      }
    }
    if (!changed) break
  }
}

/**
 * Order nodes within each column by the average rank-position of their
 * neighbours (barycenter heuristic) so edges cross as little as possible.
 */
function orderColumn(
  columnIds: string[],
  neighbourIndex: Record<string, string[]>,
  previousOrder: Record<string, number>,
): string[] {
  const scored = columnIds.map((id, fallback) => {
    const neighbours = (neighbourIndex[id] || []).filter(n => n in previousOrder)
    const barycenter = neighbours.length
      ? neighbours.reduce((sum, n) => sum + previousOrder[n], 0) / neighbours.length
      : // Nodes with no placed neighbour keep their incoming order.
        fallback
    return { id, barycenter, fallback }
  })
  scored.sort((a, b) => a.barycenter - b.barycenter || a.fallback - b.fallback)
  return scored.map(s => s.id)
}

/** Lay tables out in card-free-gutter columns; returns absolute positions. */
export function layoutRelationDiagram(
  nodes: LayoutNodeInput[],
  edges: LayoutEdgeInput[],
  options: LayoutOptions,
): LayoutResult {
  const positions: Record<string, { x: number; y: number }> = {}
  if (!nodes.length) return { positions, ranks: {} }

  const nodeIds = nodes.map(n => n.id)
  const heightById: Record<string, number> = {}
  nodes.forEach(n => {
    heightById[n.id] = n.height
  })

  const ranks = computeRanks(nodeIds, edges)

  const columns: string[][] = []
  nodeIds.forEach(id => {
    const rank = ranks[id]
    if (!columns[rank]) columns[rank] = []
    columns[rank].push(id)
  })

  const incoming: Record<string, string[]> = {}
  edges.forEach(e => {
    if (!(e.source in ranks) || !(e.target in ranks)) return
    ;(incoming[e.target] ||= []).push(e.source)
  })

  // Left-to-right sweep: each column is ordered against the column before it.
  let previousOrder: Record<string, number> = {}
  const orderedColumns: string[][] = []
  for (let rank = 0; rank < columns.length; rank++) {
    const column = columns[rank] || []
    const ordered = orderColumn(column, incoming, previousOrder)
    orderedColumns.push(ordered)
    previousOrder = {}
    ordered.forEach((id, index) => {
      previousOrder[id] = index
    })
  }

  const columnHeights = orderedColumns.map(column =>
    column.reduce((sum, id, index) => sum + heightById[id] + (index ? options.rowGap : 0), 0),
  )
  const tallest = Math.max(0, ...columnHeights)
  const lastRank = orderedColumns.length - 1

  orderedColumns.forEach((column, rank) => {
    // Referenced tables (the main table every FK points at) rank highest, so
    // mirror the axis to seat the main table in the leftmost column.
    const x = (lastRank - rank) * (options.nodeWidth + options.columnGap)
    // Center each column vertically so the diagram reads as one balanced block.
    let y = (tallest - columnHeights[rank]) / 2
    column.forEach(id => {
      positions[id] = { x, y }
      y += heightById[id] + options.rowGap
    })
  })

  alignSingleNeighbourRows(orderedColumns, edges, heightById, positions)

  return { positions, ranks }
}

/**
 * Nudge cards vertically so relation lines run straight instead of stepping.
 *
 * Centering each column independently leaves anchors a few pixels apart, which
 * smoothstep renders as a visible dog-leg. A card alone in its column can move
 * freely, so seat it on its partner's row: one partner gives a perfectly
 * horizontal line, several give a balanced fan. Cards stacked in a column keep
 * their packed slots — moving one would push it into its neighbours, and
 * sliding the whole stack only trades one exact alignment for a compromise.
 */
function alignSingleNeighbourRows(
  orderedColumns: string[][],
  edges: LayoutEdgeInput[],
  heightById: Record<string, number>,
  positions: Record<string, { x: number; y: number }>,
): void {
  /**
   * For each node, the offsets of its own anchor row and its partner's, so we
   * can solve for the y that puts the two rows on the same line.
   */
  const links: Record<string, { other: string; ownOffset: number; otherOffset: number }[]> = {}
  edges.forEach(e => {
    if (!(e.source in positions) || !(e.target in positions) || e.source === e.target) return
    const sourceOffset = e.sourceAnchorOffset ?? heightById[e.source] / 2
    const targetOffset = e.targetAnchorOffset ?? heightById[e.target] / 2
    ;(links[e.source] ||= []).push({
      other: e.target,
      ownOffset: sourceOffset,
      otherOffset: targetOffset,
    })
    ;(links[e.target] ||= []).push({
      other: e.source,
      ownOffset: targetOffset,
      otherOffset: sourceOffset,
    })
  })

  for (const column of orderedColumns) {
    // Only a card alone in its column can move freely; shifting one card of a
    // stack would push it into its neighbours, and shifting the whole stack
    // just trades one card's exact alignment for a compromise that helps none.
    if (column.length !== 1) continue
    const id = column[0]
    const linked = (links[id] || []).filter(l => l.other in positions)
    if (!linked.length) continue

    // Each link wants `y + ownOffset === partnerY + otherOffset`; average the
    // solutions so a single link lands exactly and a fan lands balanced.
    const wanted =
      linked.reduce((sum, l) => sum + positions[l.other].y + l.otherOffset - l.ownOffset, 0) /
      linked.length
    if (Number.isFinite(wanted)) positions[id] = { x: positions[id].x, y: wanted }
  }
}

/** The subset of a field a collapsed card needs to decide whether to show it. */
export interface CollapsibleField {
  fieldName: string
  isPrimaryKey: boolean
  isForeignKey: boolean
}

/**
 * Rows a card shows while collapsed: the declared keys.
 *
 * Relations reaching this diagram are derived from FK/PK field metadata
 * (TableRelationComponentImpl.getByFunctionUnitId -> deriveRelationsFromFields),
 * so both ends of every relation are always a flagged key — which is exactly
 * why keys are enough to anchor every line.
 */
export function visibleFieldsForCard<T extends CollapsibleField>(
  fields: T[],
  expanded: boolean,
): T[] {
  if (expanded) return fields
  return fields.filter(f => f.isPrimaryKey || f.isForeignKey)
}

/**
 * Pick which side of each card an edge attaches to. Edges always leave the FK
 * card on the side facing the PK card, so a line never has to wrap around a
 * card to reach its anchor.
 */
export function chooseHandleSides(
  sourceX: number,
  targetX: number,
): { source: 'l' | 'r'; target: 'l' | 'r' } {
  return sourceX <= targetX ? { source: 'r', target: 'l' } : { source: 'l', target: 'r' }
}
