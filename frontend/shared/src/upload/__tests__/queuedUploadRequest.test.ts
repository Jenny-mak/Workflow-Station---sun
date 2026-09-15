import { describe, expect, it, vi } from 'vitest'
import { queuedUploadRequest, resolveQueuedUploadOptions } from '../queuedUploadRequest'

describe('queuedUploadRequest abort', () => {
  it('abort before send never calls onSuccess', async () => {
    const onSuccess = vi.fn()
    const onError = vi.fn()
    const xhr = queuedUploadRequest({
      action: 'http://127.0.0.1:1/does-not-exist',
      file: new File(['a'], 'a.txt', { type: 'text/plain' }),
      onSuccess,
      onError,
    })
    expect(xhr).toBeInstanceOf(XMLHttpRequest)
    xhr.abort()
    await new Promise((resolve) => setTimeout(resolve, 50))
    expect(onSuccess).not.toHaveBeenCalled()
    expect(onError).toHaveBeenCalled()
  })
})

describe('resolveQueuedUploadOptions', () => {
  it('unwraps form-create inject args so the File is still posted', () => {
    const file = new File(['a'], 'a.txt', { type: 'text/plain' })
    const inner = {
      action: '/api/v1/upload',
      file,
      onSuccess() {},
      onError() {},
    }
    expect(resolveQueuedUploadOptions({ args: [inner] })?.file).toBe(file)
  })

  it('does not throw when form-create calls httpRequest without a File', () => {
    const onError = vi.fn()
    const xhr = queuedUploadRequest({ args: [{}], onError } as never)
    expect(xhr).toBeInstanceOf(XMLHttpRequest)
    expect(onError).toHaveBeenCalled()
  })
})

describe('queuedUploadRequest abort', () => {
  it('abort before send never calls onSuccess', async () => {
    const onSuccess = vi.fn()
    const onError = vi.fn()
    const xhr = queuedUploadRequest({
      action: 'http://127.0.0.1:1/does-not-exist',
      file: new File(['a'], 'a.txt', { type: 'text/plain' }),
      onSuccess,
      onError,
    })
    expect(xhr).toBeInstanceOf(XMLHttpRequest)
    xhr.abort()
    await new Promise((resolve) => setTimeout(resolve, 50))
    expect(onSuccess).not.toHaveBeenCalled()
    expect(onError).toHaveBeenCalled()
  })
})
