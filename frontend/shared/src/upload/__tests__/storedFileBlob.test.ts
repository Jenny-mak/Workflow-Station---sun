import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  fetchStoredFileBlob,
  openStoredFileInNewTab,
  triggerBlobDownload,
} from '../storedFileBlob'

describe('storedFileBlob', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('returns the blob when fetch succeeds', async () => {
    const blob = new Blob(['pdf'], { type: 'application/pdf' })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      blob: () => Promise.resolve(blob),
    }))
    const result = await fetchStoredFileBlob('/api/v1/upload/files/a.pdf')
    expect(result).toEqual({ ok: true, blob })
    expect(fetch).toHaveBeenCalledWith('/api/v1/upload/files/a.pdf', { credentials: 'include' })
  })

  it('maps 404 to not-found', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 404 }))
    await expect(fetchStoredFileBlob('/missing')).resolves.toEqual({ ok: false, result: 'not-found' })
  })

  it('opens a blank tab first so preview is not popup-blocked', async () => {
    const tab = { close: vi.fn(), location: { replace: vi.fn() } }
    vi.stubGlobal('open', vi.fn().mockReturnValue(tab))
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      blob: () => Promise.resolve(new Blob(['x'])),
    }))
    vi.stubGlobal('URL', {
      createObjectURL: vi.fn().mockReturnValue('blob:preview'),
      revokeObjectURL: vi.fn(),
    })
    await expect(openStoredFileInNewTab('/api/v1/upload/files/a.pdf')).resolves.toBe('ok')
    expect(window.open).toHaveBeenCalledWith('about:blank', '_blank')
    expect(tab.location.replace).toHaveBeenCalledWith('blob:preview')
  })

  it('closes the placeholder tab when the file is missing', async () => {
    const tab = { close: vi.fn(), location: { replace: vi.fn() } }
    vi.stubGlobal('open', vi.fn().mockReturnValue(tab))
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 404 }))
    await expect(openStoredFileInNewTab('/missing')).resolves.toBe('not-found')
    expect(tab.close).toHaveBeenCalled()
    expect(tab.location.replace).not.toHaveBeenCalled()
  })

  it('clicks an anchor with the blob URL for download', () => {
    const click = vi.fn()
    const link = { href: '', download: '', click, rel: '' } as unknown as HTMLAnchorElement
    vi.spyOn(document, 'createElement').mockReturnValue(link)
    vi.spyOn(document.body, 'appendChild').mockImplementation((node) => node)
    vi.spyOn(document.body, 'removeChild').mockImplementation((node) => node)
    vi.stubGlobal('URL', {
      createObjectURL: vi.fn().mockReturnValue('blob:file'),
      revokeObjectURL: vi.fn(),
    })
    triggerBlobDownload(new Blob(['x']), 'report.pdf')
    expect(link.href).toBe('blob:file')
    expect(link.download).toBe('report.pdf')
    expect(click).toHaveBeenCalled()
  })
})
