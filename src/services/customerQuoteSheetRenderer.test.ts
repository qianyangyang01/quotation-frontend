import { afterEach, describe, expect, it, vi } from 'vitest'
import { copyQuoteSheetImage } from './customerQuoteSheetRenderer'

afterEach(() => vi.unstubAllGlobals())
describe('customer quote image clipboard', () => {
  it('reports unsupported browsers without pretending that a copy succeeded', async () => {
    vi.stubGlobal('isSecureContext', false)
    await expect(copyQuoteSheetImage(new Blob())).rejects.toThrow('不支持复制图片')
  })
  it('copies the exact preview blob as image/png, with no text fallback', async () => {
    const write = vi.fn().mockResolvedValue(undefined)
    vi.stubGlobal('isSecureContext', true)
    vi.stubGlobal('navigator', { clipboard: { write } })
    class ClipboardMock { constructor(public items: Record<string, Blob>) {} }
    vi.stubGlobal('ClipboardItem', ClipboardMock)
    const blob = new Blob(['png bytes'], { type: 'image/png' })
    await copyQuoteSheetImage(blob)
    expect(write).toHaveBeenCalledTimes(1)
    expect(write.mock.calls[0][0][0].items).toEqual({ 'image/png': blob })
  })
  it('surfaces rejected clipboard permissions and never substitutes a download or upload', async () => {
    vi.stubGlobal('isSecureContext', true)
    vi.stubGlobal('navigator', { clipboard: { write: vi.fn().mockRejectedValue(new Error('NotAllowedError')) } })
    vi.stubGlobal('ClipboardItem', class {})
    await expect(copyQuoteSheetImage(new Blob())).rejects.toThrow('图片未复制成功')
  })
})
