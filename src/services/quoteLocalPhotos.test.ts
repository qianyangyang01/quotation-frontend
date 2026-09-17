// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { loadQuotePhotos, releaseQuotePhotos } from './quoteLocalPhotos'

let fail = false, width = 600, height = 800, stall = false
const revoke = vi.fn()
beforeEach(() => {
  fail = false; width = 600; height = 800; stall = false; revoke.mockClear()
  vi.spyOn(URL, 'createObjectURL').mockImplementation(blob => `blob:${(blob as File).name}`)
  vi.spyOn(URL, 'revokeObjectURL').mockImplementation(revoke)
  vi.stubGlobal('Image', class {
    naturalWidth = width; naturalHeight = height
    onload?: () => void; onerror?: () => void
    set src(value: string) { if (value && !stall) queueMicrotask(() => fail ? this.onerror?.() : this.onload?.()) }
  })
})
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); vi.useRealTimers() })
const file = (name = 'one.png', type = 'image/png') => new File(['picture'], name, { type })
it('loads only local blob URLs and releases every owned image URL', async () => {
  const photos = await loadQuotePhotos([file(), file('two.webp', 'image/webp')])
  expect(photos.map(p => p.url)).toEqual(['blob:one.png', 'blob:two.webp'])
  expect(revoke).not.toHaveBeenCalled()
  releaseQuotePhotos(photos)
  expect(revoke.mock.calls.flat()).toEqual(['blob:one.png', 'blob:two.webp'])
})
it('rejects unsupported types, empty files, oversized files and excess count before allocating URLs', async () => {
  for (const files of [[file('bad.svg','image/svg+xml')], [new File([], 'empty.png', { type:'image/png' })], Array.from({length:5},()=>file()), [new File([new Uint8Array(10*1024*1024+1)],'large.png',{type:'image/png'})]]) {
    await expect(loadQuotePhotos(files)).rejects.toThrow()
  }
  expect(URL.createObjectURL).not.toHaveBeenCalled()
})
it.each(['decode', 'dimensions', 'timeout'])('cleans up failed %s images', async mode => {
  fail = mode === 'decode'; width = mode === 'dimensions' ? 100000 : 600; stall = mode === 'timeout'
  if (stall) vi.useFakeTimers()
  const result = loadQuotePhotos([file()]).catch(error => error)
  if (stall) await vi.advanceTimersByTimeAsync(15000)
  expect(await result).toBeInstanceOf(Error)
  expect(revoke).toHaveBeenCalledWith('blob:one.png')
})
