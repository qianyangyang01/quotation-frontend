import { expect, it } from 'vitest'
import { normalizePurchaseRecord } from '@/data/purchaseStore'
import { legacyPurchaseDisplay, formatLegacyPurchaseUnitPrice } from './legacyPurchaseDisplay'
import { bundlePurchaseCost, purchasePriceBreakdown } from './quotationCalculator'

const record = (overrides = {}) => normalizePurchaseRecord({
  sku: 'FL2600293-2', dataSource: 'legacy_2026', legacyPriceMode: 'auto_tax',
  purchasePriceBasis: 'tax_included', sourceQuotedPriceCny: 110,
  purchasePriceCny: 121, taxIncludedPriceCny: 121, taxPoint: .1,
  minOrderQty: 2, weightG: 135, singleFreightCny: 5, ...overrides,
})

it.each([[110,'110.00'],[1.005,'1.005'],[1.234567,'1.234567'],[0.0000001,'0.0000001']])('preserves the exact source quote %s in display', (value, text) => {
  expect(formatLegacyPurchaseUnitPrice(Number(value))).toBe(text)
})

it.each([
  [110, .1, 121], [100, .08, 108], [.05, .01, .05], [19.99, .13, 22.59],
])('shows untaxed %s and keeps included cost %s exactly once', (untaxed, rate, included) => {
  const product = record({ sourceQuotedPriceCny: untaxed, taxPoint: rate, purchasePriceCny: included, taxIncludedPriceCny: included })
  const before = JSON.stringify(product)
  const pricing = purchasePriceBreakdown(product, '10')
  for (let i = 0; i < 3; i++) {
    expect(legacyPurchaseDisplay(product, '10')).toMatchObject({ label: '未税单价', unitPrice: untaxed, effectiveUnitPrice: included, range: '2件起订' })
  }
  expect(purchasePriceBreakdown(product, '10')).toEqual(pricing)
  expect(pricing.effectiveUnitPriceCny).toBe(included)
  expect(JSON.stringify(product)).toBe(before)
})

it('preserves a separately supplied included price instead of deriving or replacing it for display', () => {
  const product = record({ legacyPriceMode: undefined, purchasePriceCny: 125, taxIncludedPriceCny: 125 })
  expect(legacyPurchaseDisplay(product, '10')).toMatchObject({ unitPrice: 110, effectiveUnitPrice: 125 })
  expect(legacyPurchaseDisplay(product, '10')!.detail).toContain('含票价 ¥125.00')
  expect(legacyPurchaseDisplay(record({ sourceQuotedPriceCny: null }), '10')).toMatchObject({ label: '含票价', unitPrice: 121 })
  expect(legacyPurchaseDisplay(record({ sourceQuotedPriceCny: null }), '10')!.detail).toContain('未税单价待确认')
})

it('prices later quoted tiers independently even when a tier equals the included base', () => {
  const product = record({ tier2MinQty: 100, tier2PriceCny: 121, tier3MinQty: 500, tier3PriceCny: 90 })
  expect(['10', '100', '100+'].map(tier => legacyPurchaseDisplay(product, tier)!.unitPrice)).toEqual([110, 121, 90])
  expect(['10', '100', '100+'].map(tier => legacyPurchaseDisplay(product, tier)!.effectiveUnitPrice)).toEqual([121, 133.1, 99])
  const rows = [
    { sku: product.sku, purchaseTier: '10', quantityPerSet: 2, purchaseUnitPrice: 110, purchaseFreightPerUnit: 0, weightKg: .1, customWeightKg: null },
    { sku: product.sku, purchaseTier: '100', quantityPerSet: 3, purchaseUnitPrice: 121, purchaseFreightPerUnit: 0, weightKg: .1, customWeightKg: null },
  ]
  // 5 sets × (2 × 121 + 3 × 133.10), never use the displayed untaxed amounts.
  expect(bundlePurchaseCost(rows, [product], '100+', 5)).toBe(3206.5)
})

it('keeps the zero-point 1% adjustment separate from the displayed untaxed price', () => {
  const product = record({ taxPoint: 0, purchasePriceCny: 110, taxIncludedPriceCny: 110 })
  expect(legacyPurchaseDisplay(product, '10')).toMatchObject({ unitPrice: 110, effectiveUnitPrice: 111.1 })
  expect(legacyPurchaseDisplay(product, '10')!.detail).toBe('票点 0% · 报价另加1% · 计入采购成本 ¥111.10/件')
  expect(legacyPurchaseDisplay(record({ dataSource: 'standard' }), '10')).toBeUndefined()
  expect(legacyPurchaseDisplay(record({ purchasePriceCny: null, minOrderQty: null }), '10')).toBeUndefined()
})
