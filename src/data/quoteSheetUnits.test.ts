import { expect, it } from 'vitest'
import { normalizeCustomerPrices, recordCustomerPrices } from './customerQuotePrices'
import { quoteSheetUnit, validQuoteSheetUnits } from './quoteSheetUnits'
import type { QuotationRecord } from './quotationRecords'
it('preserves custom units through normalization and clones historical snapshots', () => {
  const original = { quantities: [1, 3], quantityUnits: { '1': 'set', '3': '套' }, rows: [{ optionId: 'a', prices: [12, 36] }] }
  const saved = normalizeCustomerPrices(JSON.parse(JSON.stringify(original)))!
  expect(saved).toEqual(original)
  const copy = recordCustomerPrices({ customerQuote: saved } as QuotationRecord)
  copy.quantityUnits!['1'] = 'box'
  expect(saved.quantityUnits!['1']).toBe('set')
  expect(normalizeCustomerPrices({ ...original, quantityUnits: {} })!.quantityUnits).toEqual({})
  expect(quoteSheetUnit(1, false)).toBe('pc')
  expect(quoteSheetUnit(2, false)).toBe('pcs')
  expect(quoteSheetUnit(1, true)).toBe('set')
  expect(quoteSheetUnit(2, true)).toBe('sets')
  expect(quoteSheetUnit(2, false, ' pair ')).toBe('pair')
})
it('rejects malformed units and units belonging to other quantities', () => {
  for (const units of [null, [], { '1': 1 }, { '1': '' }, { '1': 'x'.repeat(25) }, { '1': 'a\nb' }, { '2': 'sets' }, { '01': 'sets' }]) expect(validQuoteSheetUnits(units, [1, 3])).toBe(false)
  expect(validQuoteSheetUnits({ '1': '套/箱' }, [1, 3])).toBe(true)
})
