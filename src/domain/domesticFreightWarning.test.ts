import { describe, expect, it } from 'vitest'
import { normalizePurchaseRecord, purchaseQuoteFreight, purchaseQuoteFreightUnit, type PurchaseProductRecord } from '@/data/purchaseStore'
import { getDomesticFreightWarning } from './domesticFreightWarning'

const product = (values: Partial<PurchaseProductRecord> = {}) => normalizePurchaseRecord({
  sku: 'FREIGHT-260001', dataSource: 'legacy_2026', weightG: 1000, factoryInfo: '义乌市某某商行',
  minOrderQty: 1, purchasePriceCny: 10, singleFreightCny: 6, ...values,
})

describe('domestic freight notice', () => {
  it.each([
    [1000, '义乌市某某商行', 1, 5],
    [1000.1, '义乌市某某商行', 2, 6.5],
    [2000, '义乌市某某商行', 2, 6.5],
    [2000.1, '义乌市某某商行', 3, 8],
    [1000, '广州市某某公司', 1, 5],
    [1000.1, '广州市某某公司', 2, 8],
    [2000, '广州市某某公司', 2, 8],
    [2000.1, '广州市某某公司', 3, 11],
  ])('rounds %sg from %s to %skg, with threshold %s', (weightG, factoryInfo, chargeableKg, expectedTotal) => {
    const result = getDomesticFreightWarning(product({ weightG: Number(weightG), factoryInfo: String(factoryInfo), singleFreightCny: 100 }))
    expect(result).toMatchObject({ chargeableKg, expectedTotal })
  })

  it.each([0, 4.99, 5])('does not warn for actual freight %s at or below the threshold', singleFreightCny => {
    expect(getDomesticFreightWarning(product({ singleFreightCny }))).toBeNull()
  })

  it('warns even below a 10 percent difference, without rounding before the comparison', () => {
    expect(getDomesticFreightWarning(product({ singleFreightCny: 5.0001 }))).toMatchObject({
      actualTotal: 5.0001, expectedTotal: 5, excessTotal: 0.0001,
    })
  })

  it('uses 10-piece total weight and total freight, preserving exact per-unit differences', () => {
    const record = product({ weightG: 100.01, freight10Cny: 6.501 })
    expect(getDomesticFreightWarning(record)).toMatchObject({
      quantity: 10, totalWeightG: 1000.1, chargeableKg: 2, rate: 1.5,
      actualTotal: 6.501, expectedTotal: 6.5, excessTotal: 0.001,
      actualUnit: 0.6501, expectedUnit: 0.65, excessUnit: 0.0001,
    })
    expect(getDomesticFreightWarning(product({ weightG: 100.01, freight10Cny: 6.5 }))).toBeNull()
  })

  it('compares each selected purchase price tier using its minimum, not the option ID', () => {
    const record = product({ dataSource: 'standard', weightG: 120, freight10Cny: 8, freight100Cny: 30,
      tier2MinQty: 20, tier2PriceCny: 9, tier3MinQty: 500, tier3PriceCny: 8 })
    expect(['10', '100', '100+'].map(tier => getDomesticFreightWarning(record, tier)?.quantity)).toEqual([10, 10, 100])
    expect(['10', '100', '100+'].map(tier => getDomesticFreightWarning(record, tier)?.expectedTotal)).toEqual([6.5, 6.5, 21.5])
    expect(['10', '100', '100+'].map(tier => getDomesticFreightWarning(record, tier)?.actualTotal)).toEqual([8, 8, 30])
    for (const tier of ['10', '100', '100+']) {
      expect(getDomesticFreightWarning(record, tier)?.actualUnit).toBe(purchaseQuoteFreightUnit(record, tier))
    }
  })

  it.each([
    { minOrderQty: 1, tier2MinQty: 300, tier2PriceCny: 9, expected: [10, 100, 100] },
    { minOrderQty: 1, expected: [10, 10, 10] },
    { minOrderQty: 100, expected: [100, 100, 100] },
    { minOrderQty: 1, tier3MinQty: 500, tier3PriceCny: 8, expected: [10, 100, 100] },
  ])('follows price tier fallback with $minOrderQty minimum', ({ expected, ...values }) => {
    const record = product({ dataSource: 'standard', weightG: 120, freight10Cny: 100, freight100Cny: 200, ...values })
    expect(['10', '100', '100+'].map(tier => getDomesticFreightWarning(record, tier)?.quantity)).toEqual(expected)
  })

  it('follows the legacy 100 to 10 to 1 freight fallback and exposes the actual quantity', () => {
    const record = product({ weightG: 120, minOrderQty: 100, freight10Cny: 100 })
    expect(purchaseQuoteFreight(record, '100+')).toMatchObject({ quantity: 100, choice: { quantity: 10 } })
    expect(getDomesticFreightWarning(record, '100+')).toMatchObject({ quantity: 10, actualTotal: 100, expectedTotal: 6.5 })
    const single = product({ ...record, freight10Cny: null })
    expect(getDomesticFreightWarning(single, '100+')).toMatchObject({ quantity: 1, actualTotal: 6, expectedTotal: 5 })
    expect(getDomesticFreightWarning(product({ ...record, dataSource: 'standard' }), '100+')).toBeNull()
  })

  it('uses the current factory and weightG, ignoring stale alias/weightKg values', () => {
    const record = { ...product({ weightG: 2000, factoryInfo: '广州市某某公司', singleFreightCny: 7 }),
      packagingInfo: '义乌市某某公司', weightKg: 0.1 }
    expect(getDomesticFreightWarning(record)).toBeNull()
  })

  it.each(['', '微信供应商', '朝阳区某某公司', '江苏省广州市某某公司'])('keeps only the universal first kilogram when region is unresolved: %s', factoryInfo => {
    expect(getDomesticFreightWarning(product({ factoryInfo }))).toMatchObject({ expectedTotal: 5, regionLabel: '地区待核实' })
    expect(getDomesticFreightWarning(product({ factoryInfo, weightG: 1000.1, singleFreightCny: 100 }))).toBeNull()
    expect(getDomesticFreightWarning(product({ factoryInfo, weightG: 100.01, freight10Cny: 100 }))).toBeNull()
  })

  it.each(['standard', 'legacy_2026'] as const)('never warns for free shipping even with stored positive amounts: %s', dataSource => {
    expect(getDomesticFreightWarning(product({ dataSource, freeShipping: '是', singleFreightCny: 100, freight10Cny: 1000 }))).toBeNull()
  })

  it.each([null, 0, -1, NaN, Infinity])('does not warn for invalid weight %s', weightG => {
    expect(getDomesticFreightWarning({ ...product(), weightG })).toBeNull()
  })

  it.each([null, -1, NaN, Infinity])('does not turn unavailable freight %s into a warning', singleFreightCny => {
    expect(getDomesticFreightWarning({ ...product(), singleFreightCny })).toBeNull()
  })

  it.each(['5.4kg', ' 5.4 KG ', '5.4千克', '5.4公斤', '５.４ｋｇ'])('preserves and excludes explicit weight-unit conflict: %s', weightOriginal => {
    const record = product({ weightG: 5.4, weightOriginal })
    expect(record.weightOriginal).toBe(weightOriginal)
    expect(normalizePurchaseRecord(record).weightOriginal).toBe(weightOriginal)
    expect(getDomesticFreightWarning(record)).toBeNull()
  })

  it('accepts an original kilogram value when it agrees with current grams', () => {
    expect(getDomesticFreightWarning(product({ weightG: 5400, weightOriginal: '5.4kg', singleFreightCny: 15 }))).toMatchObject({ expectedTotal: 12.5 })
  })

  it('never modifies or blocks the record, including malformed optional data', () => {
    const record = product()
    const snapshot = structuredClone(record)
    getDomesticFreightWarning(record)
    expect(record).toEqual(snapshot)
    expect(record.quoteReady).toBe(true)
    expect(getDomesticFreightWarning({ ...record, priceTiers: null } as unknown as PurchaseProductRecord)).toBeNull()
  })
})
