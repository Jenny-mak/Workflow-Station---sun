import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  clearUploadWidgetState,
  getUploadSubmitBlock,
  setUploadWidgetState,
  warnIfUploadsBlocking,
} from '../uploadSubmitGate'

vi.mock('element-plus', () => ({
  ElMessage: { warning: vi.fn(), error: vi.fn() },
}))

describe('uploadSubmitGate', () => {
  afterEach(() => {
    clearUploadWidgetState('a')
    clearUploadWidgetState('b')
  })

  it('blocks submit while a file is still uploading', () => {
    setUploadWidgetState('a', [{ status: 'success' }, { status: 'uploading' }])
    expect(getUploadSubmitBlock()).toBe('inflight')
    expect(warnIfUploadsBlocking({ inflight: 'wait', failed: 'fail' })).toBe(false)
  })

  it('blocks submit when any file failed', () => {
    setUploadWidgetState('a', [{ status: 'success' }, { status: 'fail' }])
    expect(getUploadSubmitBlock()).toBe('failed')
  })

  it('allows submit when every widget is idle and successful', () => {
    setUploadWidgetState('a', [{ status: 'success' }])
    setUploadWidgetState('b', [])
    expect(getUploadSubmitBlock()).toBeNull()
    expect(warnIfUploadsBlocking({ inflight: 'wait', failed: 'fail' })).toBe(true)
  })
})
