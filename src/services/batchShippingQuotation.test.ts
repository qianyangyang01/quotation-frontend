import { expect, it } from 'vitest'
import { batchItemLabel, mergeBatchShipping, mergeBatchSingle, type BatchSingleItem } from './batchSingleQuotation'
import { batchSheetEditorSource, captureBatchSheet } from './batchSingleSheetEditor'
import { parseShippingPaste } from './batchShippingInput'

function item(id: string, grams = 200, price = 5): BatchSingleItem {
  const input = {
    quoteMode: 'shipping-only', primarySku: '', shippingNote: '相同备注', manualPricing: { costCny: 0, weightGrams: grams },
    customerName: '测试客户', customerGrade: 'S级客户', exchangeRate: 6.7, commissionThreshold: 1,
    quoteOptions: [{ id: 'same', quoteSheetKey: 'route', country: 'US', countryCode: 'US', carrier: '4PX', channel: '测试', rule: '实重', eta: '7-12 working days', quote1Usd: price }],
    customerQuote: { quantities: [1], rows: [{ optionId: 'same', prices: [price] }] },
    systemQuantityQuotes: { quantities: [1], rows: [{ optionId: 'same', prices: [price] }] },
  } as unknown as BatchSingleItem['input']
  return { entryId: id, input, conditions: {} as BatchSingleItem['conditions'], sheet: {
    title: 'JerryFulfillment Shipping Quote', agent: '测试', date: '16 Nov 2026', quantityLabels: ['1 pc'], issues: [],
    rows: [{ key: 'route', number: 1, sku: batchItemLabel({ entryId: id, input }), country: 'United States', provider: '4PX', shippingTime: '7-12 working days', prices: [price], sourceDescription: '' }],
  } }
}
it('preserves duplicate weights and remarks, validates all pasted rows without truncating', () => {
  expect(parseShippingPaste('200\t短袖\n200\t短袖')).toHaveLength(2)
  for (const text of ['0', '1e3', '2.0001', '200\tnote\textra', '200\n-1', Array(51).fill('200').join('\n')]) expect(() => parseShippingPaste(text)).toThrow()
  expect(parseShippingPaste('0.001\t最轻\n1000000\t边界')).toHaveLength(2)
})
it('keeps 20 independent entries even when weight, note and channel match', () => {
  const items = Array.from({ length: 20 }, (_, i) => item(String(i + 1)))
  const before = JSON.stringify(items), document = mergeBatchShipping(items)
  expect(document.items).toHaveLength(20)
  expect(new Set(document.sheet.rows.map(row => row.sku)).size).toBe(20)
  const source = batchSheetEditorSource(document)
  expect(new Set(source.rows.map(row => row.channelKey)).size).toBe(20)
  expect(JSON.stringify(items)).toBe(before)
  expect(() => mergeBatchSingle(items)).toThrow()
  expect(() => mergeBatchShipping([item('1'), item('1')])).toThrow()
  const wrong = item('2'); wrong.input.quoteMode = 'single'
  expect(() => mergeBatchShipping([item('1'), wrong])).toThrow()
})
it('saves edited customer prices to their own entry, keeps system snapshots and sheet contact', () => {
  const document = mergeBatchShipping([item('1'), item('2', 200, 8)])
  const before = JSON.stringify(document), source = batchSheetEditorSource(document)
  const contact = { title: '客户代发报价单', agent: '业务员', date: '2026-11-16', whatsapp: '+86 123', shippingTimes: {} }
  const rows = source.entries.map((entry, i) => ({ key: entry.key, prices: [i ? 9 : 6], systemPrices: [i ? 8 : 5] }))
  const sheet = { ...source.initialSheet, title: contact.title, whatsapp: contact.whatsapp, rows: source.initialSheet.rows.map((row, i) => ({ ...row, prices: rows[i]!.prices })) }
  const result = captureBatchSheet(document, source, { quantities: [1], rows, contact, rowOrderKeys: rows.map(row => row.key) }, sheet)
  expect(result.items.map(row => row.input.customerQuote!.rows[0]!.prices)).toEqual([[6], [9]])
  expect(result.items.map(row => row.input.systemQuantityQuotes!.rows[0]!.prices)).toEqual([[5], [8]])
  expect(result.items.every(row => row.input.customerQuote!.contact!.whatsapp === '+86 123')).toBe(true)
  expect(result.items.map(row => row.entryId)).toEqual(['1', '2'])
  expect(JSON.stringify(document)).toBe(before)
})
