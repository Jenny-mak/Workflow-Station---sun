const UPLOAD_OVERLAY_Z_VARS = [
  '--sub-table-dialog-popper-z',
  '--sub-table-nested-popper-z',
]

function readCssZ(name: string): number {
  if (typeof document === 'undefined') return 0
  const raw = getComputedStyle(document.documentElement).getPropertyValue(name).trim()
  const n = Number.parseInt(raw, 10)
  return Number.isFinite(n) ? n : 0
}

/**
 * Drawer z-index above the current sub-table dialog/overlay.
 * Hardcoding 4100 loses to Element Plus `nextZIndex()` after a busy session.
 */
export function resolveUploadDrawerZIndex(nextZIndex: () => number): number {
  const ticket = nextZIndex()
  const overlayFloor = Math.max(0, ...UPLOAD_OVERLAY_Z_VARS.map(readCssZ))
  return Math.max(ticket, overlayFloor + 1)
}
