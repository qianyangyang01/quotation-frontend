import { afterEach, expect, it, vi } from 'vitest'
import { buildCustomerQuoteSheet, newQuoteSheetEdits } from '@/data/customerQuoteSheet'

afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals(); vi.resetModules() })

it('times out stalled image loads, clears callbacks, and reloads assets on retry', async () => {
  vi.useFakeTimers()
  const instances: Array<{ onload: (() => void) | null; onerror: (() => void) | null }> = []
  vi.stubGlobal('Image', class {
    onload: (() => void) | null = null
    onerror: (() => void) | null = null
    constructor() { instances.push(this) }
    set src(_value: string) { /* Simulate a request with neither a load nor an error event. */ }
  })
  const { renderCustomerQuoteSheet } = await import('./customerQuoteSheetRenderer')
  const sheet = buildCustomerQuoteSheet({ rows: [{ country: 'US', carrier: '4PX', rule: 'rule', ruleId: 1, channelKey: 'a', channelCode: 'a', transport: 'route', eta: '5-8 days', quote1: 1, quote2: 2, quote3: 3, quoteCustom: 5 }], countries: [], edits: newQuoteSheetEdits('Alex'), customQuantity: 5, bundle: false })
  const pending = renderCustomerQuoteSheet(sheet).catch(error => error)
  await vi.advanceTimersByTimeAsync(15000)
  expect((await pending).message).toContain('加载超时')
  expect(instances).toHaveLength(3)
  expect(instances.every(image => image.onload === null && image.onerror === null)).toBe(true)
  const retry = renderCustomerQuoteSheet(sheet, () => true)
  expect(instances).toHaveLength(6)
  instances.slice(3).forEach(image => image.onload!())
  expect(await retry).toEqual([])
  expect(vi.getTimerCount()).toBe(0)
})
