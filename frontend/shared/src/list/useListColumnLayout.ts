import { computed, onBeforeUnmount, onMounted, reactive, ref, watch, type CSSProperties, type MaybeRefOrGetter, type Ref, toValue } from 'vue'
import type { ListColumnKind } from './columnMeta'
import { clampColumnWidth, clampDisplayWidth } from './columnResizeCursor'
import { headerFitColumnWidth, invalidateHeaderLabelMeasureCache, LIST_COLUMN_LAYOUT_STORE_VERSION, allocateFilledDisplayWidths } from './columnWidthLayout'

function readStoredWidths(key: string): Record<string, number> {
  if (!key) return {}
  try {
    const raw = sessionStorage.getItem(key)
    if (!raw) return {}
    const parsed = JSON.parse(raw) as { v?: number; columnWidths?: Record<string, number> }
    if (parsed.v !== LIST_COLUMN_LAYOUT_STORE_VERSION) return {}
    if (parsed.columnWidths && typeof parsed.columnWidths === 'object') {
      return parsed.columnWidths
    }
    return {}
  } catch {
    // FALLBACK(ux): unreadable layout costs remembered widths only; the row data is unaffected.
    return {}
  }
}

function writeStoredWidths(key: string, columnWidths: Record<string, number>) {
  if (!key) return
  try {
    sessionStorage.setItem(key, JSON.stringify({
      v: LIST_COLUMN_LAYOUT_STORE_VERSION,
      columnWidths: { ...columnWidths },
    }))
  } catch {
    // FALLBACK(ux): quota errors must not interrupt the resize the user just performed.
  }
}

function replaceWidths(target: Record<string, number>, next: Record<string, number>) {
  for (const key of Object.keys(target)) {
    delete target[key]
  }
  Object.assign(target, next)
}

function useGridViewport(gridScrollRef: Ref<HTMLElement | null>) {
  const gridViewportWidth = ref(0)
  const gridViewportHeight = ref(0)
  let observer: ResizeObserver | null = null

  function applySize(el: HTMLElement) {
    gridViewportWidth.value = el.clientWidth
    gridViewportHeight.value = el.clientHeight
  }

  watch(gridScrollRef, (el, prev) => {
    if (prev) observer?.unobserve(prev)
    if (el) {
      applySize(el)
      observer?.observe(el)
    } else {
      gridViewportWidth.value = 0
      gridViewportHeight.value = 0
    }
  })

  onMounted(() => {
    if (typeof ResizeObserver === 'undefined') return
    observer = new ResizeObserver((entries) => {
      const w = entries[0]?.contentRect.width ?? 0
      const h = entries[0]?.contentRect.height ?? 0
      if (w > 0) gridViewportWidth.value = w
      if (h > 0) gridViewportHeight.value = h
    })
    if (gridScrollRef.value) observer.observe(gridScrollRef.value)
  })

  onBeforeUnmount(() => {
    observer?.disconnect()
    observer = null
  })

  return { gridViewportWidth, gridViewportHeight }
}

/**
 * Host-owned column widths for shared ListColumnHeader's resize handle.
 * Session stores the dragged **base**. When bases fit the viewport, leftover is
 * spread into unlocked columns so the table fills the card; overflow still scrolls.
 */
export function useListColumnLayout(opts: {
  storageKey: MaybeRefOrGetter<string>
  fields: MaybeRefOrGetter<string[]>
  extraWidth?: MaybeRefOrGetter<number>
  defaultWidthOf?: (field: string) => number | undefined
  labelOf?: (field: string) => string
  kindOf?: (field: string) => ListColumnKind | undefined
  /**
   * Full-page lists freeze the header inside the leftover viewport.
   * Mixed card+list pages (`.page-stack`) keep page scroll and cap the nested
   * `.list-data-grid-scroll` in CSS — leave this at default so the table fills
   * that pane and still has an inner vertical bar. Pass false only to size the
   * table to its rows (no inner bar).
   */
  fillViewport?: MaybeRefOrGetter<boolean>
}) {
  const columnWidths = reactive<Record<string, number>>({})
  const dragPreview = reactive<{ field: string | null; displayWidth: number }>({
    field: null,
    displayWidth: 0,
  })
  const dragFrozen = reactive<Record<string, number>>({})
  const gridScrollRef = ref<HTMLElement | null>(null)
  const { gridViewportWidth, gridViewportHeight } = useGridViewport(gridScrollRef)
  const gridTableHeight = computed(() => {
    if (toValue(opts.fillViewport) === false) return undefined
    return gridViewportHeight.value > 0 ? gridViewportHeight.value : undefined
  })
  const measureEpoch = ref(0)

  onMounted(() => {
    const fonts = document.fonts
    if (!fonts?.ready) return
    void fonts.ready.then(() => {
      invalidateHeaderLabelMeasureCache()
      measureEpoch.value += 1
    })
  })

  function defaultBaseOf(field: string): number {
    void measureEpoch.value
    const override = opts.defaultWidthOf?.(field)
    if (override != null) return override
    return headerFitColumnWidth(opts.labelOf?.(field) ?? field, opts.kindOf?.(field))
  }

  function baseWidthOf(field: string): number {
    const remembered = columnWidths[field]
    if (remembered == null) return clampColumnWidth(defaultBaseOf(field))
    return clampColumnWidth(remembered)
  }

  function extra(): number {
    return toValue(opts.extraWidth) ?? 0
  }

  const displayByField = computed<Record<string, number>>(() => {
    const fields = toValue(opts.fields)
    const bases = fields.map((field) => baseWidthOf(field))
    const locked = fields.map((field) => columnWidths[field] != null)
    const allocated = allocateFilledDisplayWidths(bases, gridViewportWidth.value - extra(), locked)
    const next: Record<string, number> = {}
    fields.forEach((field, i) => {
      next[field] = allocated[i] ?? bases[i]
    })
    return next
  })

  function widthOf(field: string): number {
    if (dragPreview.field === field) return clampDisplayWidth(dragPreview.displayWidth)
    if (dragPreview.field != null && dragFrozen[field] != null) return dragFrozen[field]
    return displayByField.value[field] ?? baseWidthOf(field)
  }

  function freezeDisplayForDrag() {
    for (const key of Object.keys(dragFrozen)) delete dragFrozen[key]
    const fields = toValue(opts.fields)
    for (const field of fields) {
      dragFrozen[field] = displayByField.value[field] ?? baseWidthOf(field)
    }
  }

  function setWidth(field: string, displayWidth: number) {
    const fields = toValue(opts.fields)
    if (!fields.includes(field)) return
    if (dragPreview.field == null) freezeDisplayForDrag()
    dragPreview.field = field
    dragPreview.displayWidth = clampDisplayWidth(displayWidth)
  }

  function clearDragFrozen() {
    for (const key of Object.keys(dragFrozen)) delete dragFrozen[key]
  }

  function commitPreview() {
    const field = dragPreview.field
    if (!field) return
    const fields = toValue(opts.fields)
    const index = fields.indexOf(field)
    const displayWidth = dragPreview.displayWidth
    dragPreview.field = null
    clearDragFrozen()
    if (index < 0) return
    columnWidths[field] = clampColumnWidth(displayWidth)
  }

  function persistWidths() {
    commitPreview()
    writeStoredWidths(toValue(opts.storageKey), columnWidths)
  }

  const gridFits = computed(() => {
    const fields = toValue(opts.fields)
    const bases = fields.reduce((sum, field) => sum + baseWidthOf(field), 0) + extra()
    return gridViewportWidth.value <= 0 || bases <= gridViewportWidth.value
  })

  const gridInnerStyle = computed<CSSProperties>(() => {
    if (gridViewportWidth.value <= 0) {
      const fields = toValue(opts.fields)
      const total = fields.reduce((sum, field) => sum + baseWidthOf(field), 0) + extra()
      if (total > 0) {
        return {
          width: `${total}px`,
          minWidth: `${total}px`,
          maxWidth: `${total}px`,
          alignSelf: 'flex-start',
        }
      }
    }
    return { width: '100%', minWidth: '100%' }
  })

  watch(() => toValue(opts.storageKey), (key) => {
    dragPreview.field = null
    clearDragFrozen()
    replaceWidths(columnWidths, readStoredWidths(key))
  }, { immediate: true })

  return {
    gridScrollRef,
    gridFits,
    gridTableHeight,
    gridInnerStyle,
    widthOf,
    setWidth,
    persistWidths,
  }
}
