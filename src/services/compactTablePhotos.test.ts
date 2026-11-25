// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest'
import { compactTablePhotos } from './compactQuoteTable'
import type { QuoteLocalPhoto } from './quoteLocalPhotos'

const photo = (width: number, height: number): QuoteLocalPhoto => {
  const image = document.createElement('img')
  Object.defineProperties(image, { naturalWidth: { value: width }, naturalHeight: { value: height } })
  return { image, url: 'blob:local', name: 'product.png' }
}
function canvasMock() {
  const context = { drawImage: vi.fn(), fillRect: vi.fn(), fillStyle: '' }
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(context as unknown as CanvasRenderingContext2D)
  const sizes: number[][] = []
  vi.spyOn(HTMLCanvasElement.prototype, 'toDataURL').mockImplementation(function (this: HTMLCanvasElement) {
    sizes.push([this.width, this.height])
    return 'data:image/png;base64,AAAA'
  })
  return { context, sizes }
}
afterEach(() => vi.restoreAllMocks())

it('puts both bundle photos into the first and only image accepted by a spreadsheet cell', () => {
  const { context, sizes } = canvasMock()
  const photos = [photo(800, 800), photo(800, 400)]
  const result = compactTablePhotos(photos)
  expect(result).toEqual([{ dataUrl: 'data:image/png;base64,AAAA', width: 140, height: 222 }])
  expect(sizes).toEqual([[640, 1015]])
  expect(context.drawImage.mock.calls.map(call => call[0])).toEqual(photos.map(p => p.image))
  const calls = context.drawImage.mock.calls
  expect(calls[0].slice(1)).toEqual([0, 0, 640, 640])
  expect(calls[1].slice(1)).toEqual([0, 695, 640, 320])
  expect(context.fillStyle).toBe('#ffffff')
  expect(photos.every(p => p.image.naturalWidth === 800)).toBe(true)
})

it('keeps all six portrait/landscape photos in order and within the canvas', () => {
  const { context, sizes } = canvasMock()
  const photos = [photo(400, 800), photo(800, 400), photo(50, 50), photo(800, 800), photo(800, 200), photo(200, 800)]
  expect(compactTablePhotos(photos)).toHaveLength(1)
  expect(context.drawImage.mock.calls.map(call => call[0])).toEqual(photos.map(p => p.image))
  for (const [, x, y, width, height] of context.drawImage.mock.calls) {
    expect(x).toBeGreaterThanOrEqual(0); expect(y).toBeGreaterThanOrEqual(0)
    expect(x + width).toBeLessThanOrEqual(sizes[0][0])
    expect(y + height).toBeLessThanOrEqual(sizes[0][1])
  }
})

it('preserves single-image size and skips the canvas when there are no photos', () => {
  const { context } = canvasMock()
  expect(compactTablePhotos([])).toEqual([])
  expect(context.drawImage).not.toHaveBeenCalled()
  expect(compactTablePhotos([photo(800, 400)])[0]).toMatchObject({ width: 140, height: 70 })
})

it('rejects an unreadable second image instead of silently copying only the first', () => {
  canvasMock()
  expect(() => compactTablePhotos([photo(800, 800), photo(0, 0)])).toThrow('尚未读取完成')
})

it('releases the canvas on encoding failure and propagates the error', () => {
  canvasMock()
  const canvases: HTMLCanvasElement[] = []
  vi.spyOn(HTMLCanvasElement.prototype, 'toDataURL').mockImplementation(function (this: HTMLCanvasElement) {
    canvases.push(this)
    throw new Error('encoding failed')
  })
  expect(() => compactTablePhotos([photo(800, 800), photo(800, 400)])).toThrow('encoding failed')
  expect([canvases[0]?.width, canvases[0]?.height]).toEqual([0, 0])
})
