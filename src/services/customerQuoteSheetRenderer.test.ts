import { afterEach, describe, expect, it, vi } from 'vitest'
import { copyQuoteSheetImage, quoteSheetLayout } from './customerQuoteSheetRenderer'

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


describe('dynamic quote sheet image geometry',()=>{
  it.each(Array.from({length:10},(_,i)=>i+1))('fits every one of %i price columns without shrinking the base columns',count=>{
    const {width,right,columns}=quoteSheetLayout(count)
    expect(columns.slice(0,7)).toEqual([22,107,327,542,787,982,1158])
    expect(columns).toHaveLength(count+7)
    expect(columns.at(-1)).toBe(right);expect(right).toBe(width-21)
    for(let i=6;i<columns.length-1;i++)expect(columns[i+1]-columns[i]).toBeGreaterThanOrEqual(143)
    expect(width).toBe(Math.max(1536,1179+count*144))
  })
  it.each([0,11,1.5,Infinity])('rejects unsupported column counts %s',count=>expect(()=>quoteSheetLayout(count)).toThrow())
})


it('keeps all 16 visibility combinations aligned with 1–10 price columns', () => {
  const keys = ['country', 'provider', 'shippingTime', 'processingTime'] as const
  for (let mask = 0; mask < 16; mask++) for (let count = 1; count <= 10; count++) {
    const hidden = keys.filter((_, index) => mask & (1 << index))
    const { columns, fixedColumns, width, right } = quoteSheetLayout(count, hidden)
    expect(fixedColumns.map(column => column.key)).toEqual(['number', 'sku', ...keys.filter(key => !hidden.includes(key))])
    expect(columns).toHaveLength(fixedColumns.length + count + 1)
    expect(columns.at(-1)).toBe(right)
    expect(width).toBeGreaterThanOrEqual(1536)
    for (let i = fixedColumns.length; i < columns.length - 1; i++) expect(columns[i + 1] - columns[i]).toBeGreaterThanOrEqual(143.99)
  }
})
