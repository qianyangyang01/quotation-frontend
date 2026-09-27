// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest'
import { capturePhotoPaste, readPhotoClipboard, resolvePhotoPaste } from './quotePhotoClipboard'
import { MAX_QUOTE_PHOTO_BYTES } from './quoteLocalPhotos'

afterEach(() => { vi.unstubAllGlobals(); vi.useRealTimers() })
const photo = () => new File(['image'], 'image.png', { type: 'image/png' })
it('captures files synchronously and does not duplicate their HTML or item representations', async () => {
  const file = photo()
  const data = capturePhotoPaste({ files: [file], items: [{ kind: 'file', type: file.type, getAsFile: () => file }], getData: () => '<img src="https://example.com/image.png">' } as unknown as DataTransfer)
  expect(data).toEqual({ files: [file], html: '' })
  expect(await resolvePhotoPaste(data)).toEqual([file])
  expect(capturePhotoPaste({ files: [], items: [{ kind: 'file', type: file.type, getAsFile: () => file }], getData: () => '' } as unknown as DataTransfer).files).toEqual([file])
})
it('reads a preferred image format only once per clipboard item', async () => {
  const getType = vi.fn().mockResolvedValue(photo())
  vi.stubGlobal('navigator', { clipboard: { read: vi.fn().mockResolvedValue([{ types: ['image/jpeg', 'image/png', 'text/html'], getType }]) } })
  const result = await readPhotoClipboard()
  expect(result.files).toHaveLength(1)
  expect(result.files[0]!.type).toBe('image/png')
  expect(getType).toHaveBeenCalledExactlyOnceWith('image/png')
})
it('gives keyboard paste instructions for unavailable or denied clipboard access', async () => {
  vi.stubGlobal('navigator', {})
  await expect(readPhotoClipboard()).rejects.toThrow('Ctrl+V')
  vi.stubGlobal('navigator', { clipboard: { read: vi.fn().mockRejectedValue(new Error('denied')) } })
  await expect(readPhotoClipboard()).rejects.toThrow('Ctrl+V')
})
it('loads deduplicated HTML image sources as local files without inserting markup or sending cookies', async () => {
  const fetch = vi.fn().mockResolvedValue(new Response(new Uint8Array([1, 2]), { headers: { 'content-type': 'image/png' } }))
  vi.stubGlobal('fetch', fetch)
  const html = '<script>throw Error()</script><img src="https://example.com/image.png" onerror="alert(1)"><img src="https://example.com/image.png">'
  const files = await resolvePhotoPaste({ files: [], html })
  expect(files).toHaveLength(1)
  expect(files[0]!.size).toBe(2)
  expect(files[0]!.type).toBe('image/png')
  expect(fetch).toHaveBeenCalledWith('https://example.com/image.png', expect.objectContaining({ credentials: 'omit', referrerPolicy: 'no-referrer', mode: 'cors' }))
  expect(document.querySelector('img')).toBeNull()
})
it('supports HTML-only clipboard entries including embedded PNG sources', async () => {
  const html = '<img src="data:image/png;base64,AQ==">'
  vi.stubGlobal('navigator', { clipboard: { read: vi.fn().mockResolvedValue([{ types: ['text/html'], getType: vi.fn().mockResolvedValue(new Blob([html])) }]) } })
  expect(await readPhotoClipboard()).toEqual({ files: [], html })
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(new Uint8Array([1]), { headers: { 'content-type': 'image/png' } })))
  expect((await resolvePhotoPaste({ files: [], html }))[0]!.size).toBe(1)
})
it.each(['hello', '<img src="file:///private.png">', '<img src="javascript:alert(1)">', '<img src="blob:https://shimo.im/id">', '<img src="data:image/svg+xml;base64,AQ==">'])( 'rejects non-image content and unsupported sources: %s', async html => {
  const fetch = vi.fn(); vi.stubGlobal('fetch', fetch)
  await expect(resolvePhotoPaste({ files: [], html })).rejects.toThrow('复制图片')
  expect(fetch).not.toHaveBeenCalled()
})
it('rejects excess HTML images before fetching, and bounds downloads and CORS failures', async () => {
  const fetch = vi.fn(); vi.stubGlobal('fetch', fetch)
  await expect(resolvePhotoPaste({ files: [], html: Array.from({ length: 7 }, (_, i) => `<img src="https://example.com/${i}.png">`).join('') })).rejects.toThrow('6 张')
  expect(fetch).not.toHaveBeenCalled()
  const data = { files: [], html: '<img src="https://example.com/large.png">' }
  fetch.mockResolvedValueOnce(new Response(new Uint8Array(MAX_QUOTE_PHOTO_BYTES + 1), { headers: { 'content-type': 'image/png' } }))
  await expect(resolvePhotoPaste(data)).rejects.toThrow('不符合要求')
  fetch.mockRejectedValueOnce(new TypeError('Failed to fetch'))
  await expect(resolvePhotoPaste(data)).rejects.toThrow('复制图片')
})
it('times out a stalled image download', async () => {
  vi.useFakeTimers()
  vi.stubGlobal('fetch', vi.fn((_url: string, options: RequestInit) => new Promise((_resolve, reject) => options.signal!.addEventListener('abort', () => reject(new Error('aborted'))))))
  const result = resolvePhotoPaste({ files: [], html: '<img src="https://example.com/stalled.png">' }).catch(error => error)
  await vi.advanceTimersByTimeAsync(15000)
  expect((await result).message).toContain('复制图片')
})

it('aborts an in-flight download when the picker closes and never starts remaining images', async () => {
  vi.useFakeTimers()
  const controller = new AbortController()
  let downloading!: AbortSignal
  const fetch = vi.fn((_url: string, options: RequestInit) => new Promise((_resolve, reject) => {
    downloading = options.signal!
    downloading.addEventListener('abort', () => reject(new Error('cancelled')))
  }))
  vi.stubGlobal('fetch', fetch)
  const result = resolvePhotoPaste({ files: [], html: '<img src="https://example.com/one.png"><img src="https://example.com/two.png">' }, controller.signal).catch(error => error)
  controller.abort()
  expect(downloading.aborted).toBe(true)
  await result
  expect(fetch).toHaveBeenCalledTimes(1)
})

it('never downloads if cancellation happens while the clipboard permission is pending', async () => {
  const controller = new AbortController(); controller.abort()
  const fetch = vi.fn(); vi.stubGlobal('fetch', fetch)
  await expect(resolvePhotoPaste({ files: [], html: '<img src="https://example.com/image.png">' }, controller.signal)).rejects.toThrow()
  expect(fetch).not.toHaveBeenCalled()
})
