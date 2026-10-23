import { describe, expect, it } from 'vitest'
import { normalizePurchaseRecord, purchaseQuoteFreight, purchaseQuoteFreightUnit, type PurchaseProductRecord } from '@/data/purchaseStore'
import { getDomesticFreightWarning } from './domesticFreightWarning'

const product = (values: Partial<PurchaseProductRecord> = {}) => normalizePurchaseRecord({
  sku: 'FREIGHT-260001', dataSource: 'legacy_2026', weightG: 1000, factoryInfo: '义乌市某某商行',
  minOrderQty: 1, purchasePriceCny: 10, singleFreightCny: 8, ...values,
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
  ])('rounds %sg from %s to %skg, with threshold %s', (weightG, factoryInfo, chargeableKg, expectedUnit) => {
    const result = getDomesticFreightWarning(product({ weightG: Number(weightG), factoryInfo: String(factoryInfo), singleFreightCny: 100 }))
    expect(result).toMatchObject({ chargeableKg, expectedUnit })
  })

  it.each([0, 4.99, 5, 5.5, 7.4999, 7.5])('does not warn for actual freight %s at or below the threshold', singleFreightCny => {
    expect(getDomesticFreightWarning(product({ singleFreightCny }))).toBeNull()
  })

  it('warns strictly above a 50 percent difference, without rounding before the comparison', () => {
    expect(getDomesticFreightWarning(product({ singleFreightCny: 7.5001 }))).toMatchObject({
      actualTotal: 7.5001, expectedUnit: 5, excessUnit: 2.5001,
    })
  })

  it.each([
    { weightG: 5000, minOrderQty: 1, quantity: 1, field: 'singleFreightCny', expectedUnit: 11 },
    { weightG: 1150, minOrderQty: 10, quantity: 10, field: 'freight10Cny', expectedUnit: 6.5 },
    { weightG: 120, minOrderQty: 100, quantity: 100, field: 'freight100Cny', expectedUnit: 5 },
  ])('uses a strict 50 percent threshold for $field', ({ weightG, minOrderQty, quantity, field, expectedUnit }) => {
    const threshold = expectedUnit * 1.5 * quantity
    const record = product({ weightG, minOrderQty, [field]: threshold })
    expect(getDomesticFreightWarning(record, quantity === 1 ? '10' : quantity === 10 ? '100' : '100+')).toBeNull()
    expect(getDomesticFreightWarning({ ...record, [field]: threshold + 0.0001 }, quantity === 1 ? '10' : quantity === 10 ? '100' : '100+')).toMatchObject({ expectedUnit })
  })

  it.each([34, 65, 97.5])('does not warn for TC2602250 at 10-piece freight %s', freight10Cny => {
    const record = product({ sku: 'TC2602250', dataSource: 'standard', weightG: 1150,
      factoryInfo: '杭州熊跑科技有限公司', minOrderQty: 2, singleFreightCny: 7, freight10Cny,
      freight100Cny: 304, tier2MinQty: 200, tier2PriceCny: 9 })
    const snapshot = structuredClone(record)
    expect(getDomesticFreightWarning(record)).toBeNull()
    expect(purchaseQuoteFreightUnit(record, '100')).toBe(freight10Cny / 10)
    expect(getDomesticFreightWarning(record, '100')).toBeNull()
    expect(record).toEqual(snapshot)
  })

  it('compares allocated unit freight with a single-item reference, preserving precision', () => {
    const record = product({ weightG: 1150, freight10Cny: 97.501 })
    expect(getDomesticFreightWarning(record, '100')).toMatchObject({
      quantity: 10, weightG: 1150, chargeableKg: 2, rate: 1.5,
      actualTotal: 97.501, actualUnit: 9.7501, expectedUnit: 6.5, excessUnit: 3.2501,
    })
  })

  it('uses the selected freight band independently of price minimums', () => {
    const record = product({ dataSource: 'standard', weightG: 120, freight10Cny: 100, freight100Cny: 800,
      tier2MinQty: 20, tier2PriceCny: 9, tier3MinQty: 500, tier3PriceCny: 8 })
    expect(['10', '100', '100+'].map(tier => getDomesticFreightWarning(record, tier)?.quantity)).toEqual([10, 10, 100])
    expect(['10', '100', '100+'].map(tier => getDomesticFreightWarning(record, tier)?.expectedUnit)).toEqual([5, 5, 5])
    expect(['10', '100', '100+'].map(tier => getDomesticFreightWarning(record, tier)?.actualTotal)).toEqual([100, 100, 800])
    for (const tier of ['10', '100', '100+']) {
      expect(getDomesticFreightWarning(record, tier)?.actualUnit).toBe(purchaseQuoteFreightUnit(record, tier))
    }
  })

  it.each([
    { minOrderQty: 1, tier2MinQty: 300, tier2PriceCny: 9, expected: [10, 10, 100] },
    { minOrderQty: 1, expected: [10, 10, 100] },
    { minOrderQty: 100, expected: [10, 10, 100] },
    { minOrderQty: 1, tier3MinQty: 500, tier3PriceCny: 8, expected: [10, 10, 100] },
  ])('ignores price tier fallback with $minOrderQty minimum', ({ expected, ...values }) => {
    const record = product({ dataSource: 'standard', weightG: 120, freight10Cny: 100, freight100Cny: 800, ...values })
    expect(['10', '100', '100+'].map(tier => getDomesticFreightWarning(record, tier)?.quantity)).toEqual(expected)
  })

  it('follows the legacy 100 to 10 to 1 freight fallback and exposes the actual quantity', () => {
    const record = product({ weightG: 120, minOrderQty: 100, freight10Cny: 100 })
    expect(purchaseQuoteFreight(record, '100+')).toMatchObject({ quantity: 100, choice: { quantity: 10 } })
    expect(getDomesticFreightWarning(record, '100+')).toMatchObject({ quantity: 10, actualTotal: 100, actualUnit: 10, expectedUnit: 5 })
    const single = product({ ...record, freight10Cny: null })
    expect(getDomesticFreightWarning(single, '100+')).toMatchObject({ quantity: 1, actualTotal: 8, expectedUnit: 5 })
    expect(getDomesticFreightWarning(product({ ...record, dataSource: 'standard' }), '100+')).toMatchObject({ quantity: 10, actualUnit: 10 })
  })

  it('uses the current factory and weightG, ignoring stale alias/weightKg values', () => {
    const record = { ...product({ weightG: 2000, factoryInfo: '广州市某某公司', singleFreightCny: 7 }),
      packagingInfo: '义乌市某某公司', weightKg: 0.1 }
    expect(getDomesticFreightWarning(record)).toBeNull()
  })

  it.each(['', '微信供应商', '朝阳区某某公司', '江苏省广州市某某公司'])('keeps only the universal first kilogram when region is unresolved: %s', factoryInfo => {
    expect(getDomesticFreightWarning(product({ factoryInfo }))).toMatchObject({ expectedUnit: 5, regionLabel: '地区待核实' })
    expect(getDomesticFreightWarning(product({ factoryInfo, weightG: 1000.1, singleFreightCny: 100 }))).toBeNull()
    expect(getDomesticFreightWarning(product({ factoryInfo, weightG: 100.01, freight10Cny: 100 }))).toMatchObject({ expectedUnit: 5 })
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
    expect(getDomesticFreightWarning(product({ weightG: 5400, weightOriginal: '5.4kg', singleFreightCny: 20 }))).toMatchObject({ expectedUnit: 12.5 })
  })

  it('never modifies or blocks the record, including malformed optional data', () => {
    const record = product()
    const snapshot = structuredClone(record)
    getDomesticFreightWarning(record)
    expect(record).toEqual(snapshot)
    expect(record.quoteReady).toBe(true)
    expect(getDomesticFreightWarning({ ...record, priceTiers: null } as unknown as PurchaseProductRecord)).toEqual(getDomesticFreightWarning(record))
  })
})
