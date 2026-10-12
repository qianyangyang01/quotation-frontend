import { afterEach, describe, expect, it, vi } from 'vitest'
import type { CustomerQuoteSheet } from '@/data/customerQuoteSheet'
import { renderFobBatchSheet } from './fobBatchSheetRenderer'

type DrawText = { value: string; x: number; y: number; font: string }
type DrawRect = { color: string; x: number; y: number; width: number; height: number }
function mockCanvases() {
  const pages: Array<{ texts: DrawText[]; rectangles: DrawRect[] }> = []
  vi.stubGlobal('document', { createElement: () => {
    const page = { texts: [] as DrawText[], rectangles: [] as DrawRect[] }
    pages.push(page)
    const ctx = {
      fillStyle: '', font: '', textAlign: '', textBaseline: '', strokeStyle: '', lineWidth: 1,
      measureText(value: string) { return { width: value.length * 12 } },
      fillText(value: string, x: number, y: number) { page.texts.push({ value, x, y, font: this.font }) },
      fillRect(x: number, y: number, width: number, height: number) { page.rectangles.push({ color: this.fillStyle, x, y, width, height }) },
      beginPath() {}, moveTo() {}, lineTo() {}, stroke() {},
    }
    return { width: 0, height: 0, getContext: () => ctx, toBlob: (done: (blob: Blob) => void) => done(new Blob(['png'], { type: 'image/png' })) }
  } })
  return pages
}
function sheet(skus: string[]): CustomerQuoteSheet {
  return {
    fobBatch: true, agent: 'Agent', date: '2026-10-12', quantityLabels: ['With declaration', 'Without declaration'], issues: [],
    rows: skus.map((sku, i) => ({ key: String(i), number: i+1, sku, quantityRange: `${i+1} pcs`, prices: [i+0.25, i+0.50], country: '', provider: '', shippingTime: '', sourceDescription: '' })),
  }
}
afterEach(() => vi.unstubAllGlobals())

describe('FOB batch image SKU grouping', () => {
  it('merges SKU labels while preserving every quantity, price and immutable source row', async () => {
    const pages = mockCanvases(), source = sheet(['SKU-A', 'SKU-A', 'SKU-A', 'SKU-B', 'SKU-B'])
    const before = JSON.stringify(source)
    source.rows.forEach(row => { Object.freeze(row.prices); Object.freeze(row) })
    Object.freeze(source.rows); Object.freeze(source)
    const images = await renderFobBatchSheet(source, () => false)
    expect(images).toHaveLength(1)
    const page = pages[0]!
    expect(page.texts.filter(t => t.value.startsWith('SKU-')).map(t => t.value)).toEqual(['SKU-A', 'SKU-B'])
    for (const row of source.rows) {
      expect(page.texts.filter(t => t.value === row.quantityRange)).toHaveLength(1)
      for (const price of row.prices) expect(page.texts.filter(t => t.value === `$${price!.toFixed(2)}`)).toHaveLength(1)
    }
    const merged = page.rectangles.filter(r => r.x === 126)
    expect(merged.map(r => r.height)).toEqual([3*58-2, 2*58-2])
    expect(merged[0]!.color).not.toBe(merged[1]!.color)
    const skuText = page.texts.find(t => t.value === 'SKU-A')!
    expect(skuText.y).toBe(merged[0]!.y - 1 + 3*58/2)
    expect(skuText.font).toContain('bold')
    expect(JSON.stringify(source)).toBe(before)
  })

  it('repeats the merged SKU on the next page with the same color and loses no rows', async () => {
    const pages = mockCanvases(), source = sheet(['SKU-A', ...Array<string>(22).fill('SKU-B'), 'SKU-C'])
    const images = await renderFobBatchSheet(source, () => false)
    expect(images.map(p => [p.firstRow, p.lastRow])).toEqual([[0,19],[20,23]])
    expect(pages.map(p => p.texts.filter(t => t.value.startsWith('SKU-')).map(t => t.value))).toEqual([['SKU-A','SKU-B'],['SKU-B','SKU-C']])
    const labels = pages.map(p => p.rectangles.filter(r => r.x === 126))
    expect(labels[0]![1]!.color).toBe(labels[1]![0]!.color)
    expect(labels[1]![0]!.color).not.toBe(labels[1]![1]!.color)
    expect(pages.flatMap(p => p.texts).filter(t => /^\d+ pcs$/.test(t.value)).map(t => t.value)).toEqual(source.rows.map(r => r.quantityRange))
  })

  it('does not merge nonadjacent occurrences or reorder saved rows', async () => {
    const pages = mockCanvases()
    await renderFobBatchSheet(sheet(['SKU-A','SKU-B','SKU-A']), () => false)
    expect(pages[0]!.texts.filter(t => t.value.startsWith('SKU-')).map(t => t.value)).toEqual(['SKU-A','SKU-B','SKU-A'])
  })
})
