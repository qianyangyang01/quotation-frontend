import { expect, it } from 'vitest'
import { normalizeQuotationRecord } from './quotationRecords'
import { priceComparison, recordCustomerPrices } from './customerQuotePrices'
import { customerPriceRevision } from './customerPriceRevision'
import { quotationRecordQuoteOnlyLayout } from './quotationRecordQuoteOnlyLayout'
import { quotationRecordCopyLayout } from './quotationRecordCopyLayout'

function record() {
  return normalizeQuotationRecord({ id: 'r', no: 'QT-AVG', primarySku: 'AVG-TEST', quoteOptions: [
    { id: 'a', country: '美国', rule: '', eta: '', quote2Usd: null, quote3Usd: null, quoteCustomUsd: null, carrier: 'SDH', channel: 'A', quote1Usd: 5 },
    { id: 'b', country: '美国', rule: '', eta: '', quote2Usd: null, quote3Usd: null, quoteCustomUsd: null, carrier: '顺丰', channel: 'B', quote1Usd: 5.2 },
  ], customerQuote: { quantities: [1], rows: [{ optionId: 'a', prices: [null] }, { optionId: 'b', prices: [null] }], averagePlans: [
    { id: 'p', mode: 'equal', display: 'summary', provider: 'Combined Shipping', shippingTime: '7-12 workingdays', quantities: [1],
      members: [{ optionId: 'a', weight: 1, sourcePrices: [5] }, { optionId: 'b', weight: 1, sourcePrices: [5.2] }], systemPrices: [5.1], prices: [4.9] },
  ] } })!
}
it('clones the full plan and shows its price difference and readable revision history', () => {
  const r = record(), before = JSON.stringify(r.customerQuote)
  const draft = recordCustomerPrices(r); draft.averagePlans![0]!.prices[0] = 4.8
  expect(r.customerQuote!.averagePlans![0]!.prices[0]).toBe(4.9)
  expect(priceComparison(r, draft).find(line => line.option.id === 'average:p')).toMatchObject({ quantity: 1, system: 5.1, customer: 4.8, difference: -0.3 })
  expect(customerPriceRevision(r, before, JSON.stringify(draft)).groups[0]).toMatchObject({ route: '综合报价 · Combined Shipping', changes: [{ quantity: 1, before: '$4.90', after: '$4.80' }] })
})
it('copies aggregate-only customer rows even if every channel customer price is blank, while full copy retains all sources', () => {
  const r = record(), visible = quotationRecordQuoteOnlyLayout(r, 'visible'), full = quotationRecordQuoteOnlyLayout(r, 'full')
  expect(visible.text).toContain('1件'); expect(visible.text).toContain('4.90')
  expect(visible.text).toContain('Combined Shipping'); expect(visible.text).not.toContain('SDH｜A')
  expect(full.text).toContain('SDH｜A'); expect(full.text).toContain('Combined Shipping')
  const audit = quotationRecordCopyLayout(r)
  expect(audit.text).toContain('综合报价方案（保存快照）'); expect(audit.text).toContain('5.10'); expect(audit.text).toContain('4.90')
})
