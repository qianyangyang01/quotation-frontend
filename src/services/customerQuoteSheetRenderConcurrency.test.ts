import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { buildCustomerQuoteSheet, newQuoteSheetEdits } from '@/data/customerQuoteSheet'

const canvases: Array<{ width: number; height: number; getContext: () => unknown; toBlob: (done: (blob: Blob | null) => void) => void }> = []
const drawnText: string[] = []
const drawnImages: unknown[][] = []
const pending: Array<(blob: Blob | null) => void> = []
function sheet(count = 1) {
  return buildCustomerQuoteSheet({ rows: Array.from({ length: count }, (_, i) => ({ country: 'US', carrier: '4PX', channelKey: String(i), ruleId: i, rule: '', channelCode: '', transport: '', eta: '5-8 days', quote1: 1, quote2: 2, quote3: 3, quoteCustom: 5 })), countries: [], edits: newQuoteSheetEdits('QA'), customQuantity: 5, bundle: false })
}
async function settle() { for (let i = 0; i < 15; i++) await Promise.resolve() }
beforeEach(() => {
  vi.resetModules(); canvases.length = 0; pending.length = 0; drawnText.length = 0; drawnImages.length = 0
  vi.stubGlobal('Image', class { onload?: () => void; set src(_value: string) { queueMicrotask(() => this.onload?.()) } })
  vi.stubGlobal('document', { createElement: () => {
    const context = new Proxy({ drawImage: (...args: unknown[]) => drawnImages.push(args), fillText: (value: string) => drawnText.push(value), measureText: (text: string) => ({ width: text.length * 11 }) }, { get: (target, key) => target[key as keyof typeof target] ?? (() => {}) })
    const canvas = { width: 300, height: 150, getContext: () => context, toBlob: (done: (blob: Blob | null) => void) => { pending.push(done) } }
    canvases.push(canvas); return canvas
  } })
})
afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals() })

it('draws each local photo once per page, preserves aspect ratio and expands a single-row page', async () => {
  const { renderCustomerQuoteSheet } = await import('./customerQuoteSheetRenderer')
  const photo = {url:'blob:local',name:'local.png',image:{naturalWidth:800,naturalHeight:400} as HTMLImageElement}
  const data=sheet(25);data.hiddenColumns=['shippingTime','processingTime']
  const result=renderCustomerQuoteSheet(data,()=>false,[photo,photo]);await settle()
  pending[0](new Blob(['first']));await settle()
  expect(drawnText.filter(text=>text==='Product')).toHaveLength(2)
  const draws=drawnImages.filter(args=>args[0]===photo.image)
  expect(draws).toHaveLength(4)
  draws.forEach(args=>{expect(args[3]).toBe(160);expect(args[4]).toBe(80)})
  expect(canvases[1].height).toBeGreaterThan(269+350)
  pending[1](new Blob(['second']));expect(await result).toHaveLength(2)
})

it('cancels a 50-route render at the first encoded page without generating remaining pages', async () => {
  const { renderCustomerQuoteSheet } = await import('./customerQuoteSheetRenderer')
  let cancelled = false
  const result = renderCustomerQuoteSheet(sheet(50), () => cancelled); await settle()
  expect(pending).toHaveLength(1); cancelled = true; pending[0](new Blob(['old']))
  expect(await result).toEqual([]); expect(canvases).toHaveLength(1)
  expect(canvases[0]).toMatchObject({ width: 0, height: 0 })
})

it('isolates two concurrent multi-page renderers with reversed encodings', async () => {
  const { renderCustomerQuoteSheet } = await import('./customerQuoteSheetRenderer')
  const a = renderCustomerQuoteSheet(sheet(50)), b = renderCustomerQuoteSheet(sheet(25))
  await settle(); expect(pending).toHaveLength(2)
  pending[1](new Blob(['b1'])); await settle(); pending[2](new Blob(['b2'])); await settle()
  pending[0](new Blob(['a1'])); await settle(); pending[3](new Blob(['a2'])); await settle(); pending[4](new Blob(['a3']))
  const [ra, rb] = await Promise.all([a,b])
  expect(ra.map(p=>[p.firstRow,p.lastRow])).toEqual([[1,24],[25,48],[49,50]])
  expect(rb.map(p=>[p.firstRow,p.lastRow])).toEqual([[1,24],[25,25]])
  expect(await Promise.all(ra.map(p=>p.blob.text()))).toEqual(['a1','a2','a3'])
  expect(await Promise.all(rb.map(p=>p.blob.text()))).toEqual(['b1','b2'])
  expect(canvases.every(c=>c.width===0 && c.height===0)).toBe(true)
})

it('releases canvas backing memory after PNG encoding fails', async () => {
  const { renderCustomerQuoteSheet } = await import('./customerQuoteSheetRenderer')
  const result = renderCustomerQuoteSheet(sheet()).catch(error => error); await settle()
  pending[0](null); expect(await result).toBeInstanceOf(Error)
  expect(canvases[0]).toMatchObject({ width: 0, height: 0 })
})

it('times out a stalled PNG encoder and releases memory even if its callback arrives later', async () => {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
  const { renderCustomerQuoteSheet } = await import('./customerQuoteSheetRenderer')
  const result = renderCustomerQuoteSheet(sheet()).catch(error => error); await settle()
  await vi.advanceTimersByTimeAsync(15000)
  expect(await result).toMatchObject({ message: expect.stringContaining('超时') })
  expect(canvases[0]).toMatchObject({ width: 0, height: 0 })
  pending[0](new Blob(['late'])); expect(vi.getTimerCount()).toBe(0)
})

it('rejects pathological pasted notes before allocating a giant canvas', async () => {
  const { renderCustomerQuoteSheet } = await import('./customerQuoteSheetRenderer')
  const data = sheet(); data.notes = Array.from({ length: 4 }, ()=>'\n'.repeat(2999))
  const result = renderCustomerQuoteSheet(data).catch(error => error); await settle()
  // A supported implementation rejects before toBlob; finish it only to expose the old unbounded path.
  pending[0]?.(new Blob(['huge']))
  expect(await result).toMatchObject({ message: expect.stringContaining('内容过长') })
  expect(pending).toHaveLength(0)
  expect(canvases.every(c=>c.width===0 && c.height===0)).toBe(true)
})


it('renders hidden columns without their labels or values, wrapping long SKU text within the row', async () => {
  const { renderCustomerQuoteSheet } = await import('./customerQuoteSheetRenderer')
  const data = sheet()
  data.hiddenColumns = ['country', 'provider', 'shippingTime', 'processingTime']
  data.rows[0].sku = 'SKU-ABCDEFGHIJK-1234567890+SKU-SECOND-9876543210'
  const result = renderCustomerQuoteSheet(data); await settle()
  expect(drawnText).not.toContain('Country')
  expect(drawnText).not.toContain('United States')
  expect(drawnText).not.toContain('4PX')
  expect(drawnText).not.toContain('5-8 days')
  expect(drawnText).toContain('SKU')
  expect(drawnText).toContain('$1.00')
  const skuStart = drawnText.indexOf('1') + 1
  expect(drawnText.slice(skuStart, drawnText.indexOf('$1.00')).join('')).toBe(data.rows[0].sku)
  expect(canvases[0].height).toBeGreaterThan(269 + 57 + 400)
  pending[0](new Blob(['png'])); expect(await result).toHaveLength(1)
})
