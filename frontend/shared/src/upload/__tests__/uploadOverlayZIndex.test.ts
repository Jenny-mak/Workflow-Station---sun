import { afterEach, describe, expect, it } from 'vitest'
import { resolveUploadDrawerZIndex } from '../uploadOverlayZIndex'

describe('resolveUploadDrawerZIndex', () => {
  afterEach(() => {
    document.documentElement.style.removeProperty('--sub-table-dialog-popper-z')
    document.documentElement.style.removeProperty('--sub-table-nested-popper-z')
  })

  it('uses the Element Plus ticket when no overlay CSS var is set', () => {
    expect(resolveUploadDrawerZIndex(() => 2100)).toBe(2100)
  })

  it('sits above a sub-table dialog that already took a higher overlay ticket', () => {
    document.documentElement.style.setProperty('--sub-table-dialog-popper-z', '5000')
    expect(resolveUploadDrawerZIndex(() => 2100)).toBe(5001)
  })

  it('sits above the DW nested modal popper floor', () => {
    document.documentElement.style.setProperty('--sub-table-nested-popper-z', '4100')
    expect(resolveUploadDrawerZIndex(() => 2010)).toBe(4101)
  })
})
