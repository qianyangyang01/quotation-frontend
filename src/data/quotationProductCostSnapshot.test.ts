import { expect, it } from 'vitest'
import { normalizeQuotationRecord } from './quotationRecords'
import { quotationProductCostSnapshot } from './quotationProductCostSnapshot'

it('preserves a single saved purchase price and unknown domestic freight without inventing a total', () => {
  const record = normalizeQuotationRecord({ id: 'single', no: 'QT-SINGLE', primarySku: '001', purchaseBaseUnitPriceCny: 10, purchaseUnitPriceCny: 10.6, purchaseInvoiceRatePercent: 6, customQuoteQuantity: 5 })!
  const before = JSON.stringify(record), cost = quotationProductCostSnapshot(record)
  expect(cost.items[0]).toMatchObject({ base: 10, purchase: 10.6, rate: 6, freight: undefined })
  expect(cost.rows.find(row => row.quantity === 3)).toEqual({ quantity: 3, purchase: 31.8, freight: undefined, total: undefined })
  expect(JSON.stringify(record)).toBe(before)
})

it('sums bundle item counts and saved decimal costs exactly, including explicit zero freight', () => {
  const record = normalizeQuotationRecord({ id: 'bundle', no: 'QT-BUNDLE', quoteMode: 'bundle', bundleItems: [
    { sku: 'A', name: 'A', effectiveWeightKg: .1, quantityPerSet: 2, purchaseUnitPriceCny: .1, domesticFreightPerUnitCny: .2 },
    { sku: 'B', name: 'B', effectiveWeightKg: .1, quantityPerSet: 1, purchaseUnitPriceCny: .2, domesticFreightPerUnitCny: 0 },
  ] })!
  expect(quotationProductCostSnapshot(record).rows[2]).toEqual({ quantity: 3, purchase: 1.2, freight: 1.2, total: 2.4 })
})

it('keeps missing purchase prices and empty legacy bundles unknown', () => {
  const single = normalizeQuotationRecord({ id: 'old', no: 'QT-OLD' })!
  expect(quotationProductCostSnapshot(single).rows[0]?.purchase).toBeUndefined()
  const bundle = normalizeQuotationRecord({ id: 'old-bundle', no: 'QT-OLD-BUNDLE', quoteMode: 'bundle' })!
  expect(quotationProductCostSnapshot(bundle).rows[0]?.total).toBeUndefined()
})
