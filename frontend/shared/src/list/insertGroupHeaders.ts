export interface ListGroupCount {
  label: string | null
  count: number
}

export interface ListGroupHeaderRow {
  _isGroupHeader: true
  _groupLabel: string
  _groupCount: number
}

export function isListGroupHeaderRow(row: object): row is ListGroupHeaderRow {
  return (row as ListGroupHeaderRow)._isGroupHeader === true
}

/**
 * Slot a header in front of each run of rows sharing a group value.
 *
 * Counts come from the backend GROUP BY over the same predicate as the page.
 * A label the backend did not count means the two disagree — that is an error,
 * not something to paper over with 0 or an em-dash.
 */
export function insertListGroupHeaders<T extends object>(
  rows: T[],
  groupByField: string | null,
  groups: ListGroupCount[],
): Array<T | ListGroupHeaderRow> {
  if (!groupByField) return rows
  for (const group of groups) {
    if (typeof group.count !== 'number') {
      throw new Error(
        `Group on ${groupByField} is missing count — the page and its group counts came from different queries`,
      )
    }
  }
  const countByLabel = new Map(groups.map((g) => [g.label ?? '', g.count]))
  const out: Array<T | ListGroupHeaderRow> = []
  let currentLabel: string | null = null
  for (const row of rows) {
    const raw = (row as Record<string, unknown>)[groupByField]
    const label = raw == null ? '' : String(raw)
    if (label !== currentLabel) {
      const count = countByLabel.get(label)
      if (count === undefined) {
        throw new Error(
          `Group "${label}" on ${groupByField} was not counted by the server — the page and its group counts came from different queries`,
        )
      }
      out.push({ _isGroupHeader: true, _groupLabel: label || '—', _groupCount: count })
      currentLabel = label
    }
    out.push(row)
  }
  return out
}
