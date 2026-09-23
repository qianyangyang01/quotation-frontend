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

it.each([0, .21])('round-trips saved single freight %s and computes all quantities from the snapshot', freight => {
  const record = normalizeQuotationRecord({ id: 'saved', no: 'QT', purchaseUnitPriceCny: 79.79, domesticFreightPerUnitCny: freight, customQuoteQuantity: 5 })!
  const copy = normalizeQuotationRecord(JSON.parse(JSON.stringify(record)))!
  expect(copy.domesticFreightPerUnitCny).toBe(freight)
  const cost = quotationProductCostSnapshot(copy)
  expect(cost.rows.find(row => row.quantity === 5)).toEqual({ quantity: 5, purchase: 398.95, freight: freight * 5, total: freight ? 400 : 398.95 })
  expect(cost.freightRecovered).toBe(false)
})

function legacyRecord() {
  return normalizeQuotationRecord({ id: 'legacy', no: 'QT', purchaseUnitPriceCny: 79.79, customQuoteQuantity: 5,
    quoteOptions: [
      { id: 'a', totalCostCny: 440, freightCny: 9, quoteCustomUsd: 80, logisticsInput: { quantity: 1, weightKg: .1 },
        logisticsSamples: [{ quantity: 1, total: 9 }, { quantity: 5, total: 40 }] },
      { id: 'b', totalCostCny: 450, freightCny: 12, quoteCustomUsd: 82,
        logisticsSamples: [{ quantity: 5, total: 50 }] },
    ],
  } as unknown as Parameters<typeof normalizeQuotationRecord>[0])!
}

it('recovers old single freight from matching custom quantities across saved routes, without changing the record', () => {
  const record = legacyRecord(), before = JSON.stringify(record)
  const cost = quotationProductCostSnapshot(record)
  expect(cost.freightRecovered).toBe(true)
  expect(cost.items[0]?.freight).toBe(.21)
  expect(cost.rows[2]).toEqual({ quantity: 3, purchase: 239.37, freight: .63, total: 240 })
  expect(JSON.stringify(record)).toBe(before)
})

it('prefers explicit freight including zero over legacy arithmetic', () => {
  const record = legacyRecord()
  record.domesticFreightPerUnitCny = 0
  expect(quotationProductCostSnapshot(record).items[0]?.freight).toBe(0)
  expect(quotationProductCostSnapshot(record).freightRecovered).toBe(false)
})

it.each(['conflict', 'negative', 'missing-sample', 'null-sample', 'unavailable', 'missing-custom-price', 'missing-quantity'] as const)('keeps unsafe legacy recovery unknown: %s', reason => {
  const record = legacyRecord()
  if (reason === 'conflict') record.quoteOptions![1]!.totalCostCny = 460
  if (reason === 'negative') record.quoteOptions![0]!.totalCostCny = 40
  if (reason === 'missing-quantity') record.customQuoteQuantity = undefined
  for (const option of record.quoteOptions!) {
    if (reason === 'missing-sample') option.logisticsSamples = []
    if (reason === 'null-sample') option.logisticsSamples!.forEach(sample => { sample.total = null })
    if (reason === 'unavailable') option.available = false
    if (reason === 'missing-custom-price') option.quoteCustomUsd = null
  }
  const cost = quotationProductCostSnapshot(record)
  expect(cost.items[0]?.freight).toBeUndefined()
  expect(cost.rows[0]?.total).toBeUndefined()
  expect(cost.freightRecovered).toBe(false)
})
