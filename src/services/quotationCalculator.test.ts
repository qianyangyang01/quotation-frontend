import { describe, expect, it } from 'vitest'
import { normalizePurchaseRecord } from '@/data/purchaseStore'
import {
  bundleDomesticFreight,
  bundleGoodsWeight,
  bundlePurchaseCost,
  hasQuotationProduct,
  monthlySalesTierLabel,
  purchasePriceForMonthlySales,
  purchaseQuantityForMonthlySales,
  resolveBundleProductCategory,
  singleActualWeight,
  singleChargeWeight,
  singleShipmentDimensions,
  singleVolumeWeight,
  usdPriceFromCny,
} from './quotationCalculator'

const first = normalizePurchaseRecord({
  sku: 'BIZ-001', catalogState: 'ready', weightG: 200, minOrderQty: 1, purchasePriceCny: 20,
  tier2MinQty: 10, tier2PriceCny: 18, tier3MinQty: 100, tier3PriceCny: 16,
})
const second = normalizePurchaseRecord({
  sku: 'BIZ-002', catalogState: 'ready', weightG: 350, minOrderQty: 1, purchasePriceCny: 12,
  tier2MinQty: 10, tier2PriceCny: 12, tier3MinQty: 100, tier3PriceCny: 12,
})

describe('quotation purchase tiers', () => {
  it('maps monthly sales bands to their documented purchase quantities', () => {
    expect(['10', '100', '100+'].map(purchaseQuantityForMonthlySales)).toEqual([1, 10, 100])
    expect(['10', '100', '100+'].map(monthlySalesTierLabel)).toEqual(['1件参考价', '10件采购价', '100件采购价'])
  })

  it('uses exact tier boundaries and accepts equal tier prices', () => {
    expect(purchasePriceForMonthlySales(first, '10')).toBe(20)
    expect(purchasePriceForMonthlySales(first, '100')).toBe(18)
    expect(purchasePriceForMonthlySales(first, '100+')).toBe(16)
    expect(purchasePriceForMonthlySales(second, '100+')).toBe(12)
  })
})

describe('single SKU weight calculation', () => {
  const input = {
    quantity: 2, netWeight: 0.4, weightSource: 'purchase' as const, manualWeight: 0.7,
    volumetricEnabled: true, packageLengthCm: 40, packageWidthCm: 30, packageHeightCm: 20, volumeDivisor: 8000,
  }

  it('uses actual or manual weight and chooses the larger volumetric weight', () => {
    expect(singleActualWeight(input)).toBe(0.8)
    expect(singleVolumeWeight(input)).toBe(6)
    expect(singleChargeWeight(input)).toBe(6)
    expect(singleActualWeight({ ...input, weightSource: 'manual' })).toBe(1.4)
  })

  it('disables volumetric calculation when dimensions are missing', () => {
    expect(singleVolumeWeight({ ...input, packageHeightCm: 0 })).toBe(0)
    expect(singleShipmentDimensions({ ...input, volumetricEnabled: false })).toBeUndefined()
  })
})

describe('bundle SKU calculation', () => {
  const items = [
    { sku: first.sku, quantityPerSet: 2, purchaseUnitPrice: 99, purchaseFreightPerUnit: 1.5, weightKg: 0.2, customWeightKg: null },
    { sku: second.sku, quantityPerSet: 1, purchaseUnitPrice: 99, purchaseFreightPerUnit: 2, weightKg: 0.35, customWeightKg: 0.5 },
  ]

  it('aggregates each SKU quantity, freight and weight per set', () => {
    expect(bundlePurchaseCost(items, [first, second], '100', 3)).toBe((18 * 2 + 12) * 3)
    expect(bundleDomesticFreight(items, 3)).toBe((1.5 * 2 + 2) * 3)
    expect(bundleGoodsWeight(items, 3)).toBe((0.2 * 2 + 0.5) * 3)
  })

  it('rounds CNY before converting to USD and guards invalid rates', () => {
    expect(usdPriceFromCny(70.126, 7)).toBeCloseTo(10.018571, 6)
    expect(usdPriceFromCny(-1, 0)).toBe(0)
  })

  it('recognizes loaded single and bundle products consistently before save', () => {
    expect(hasQuotationProduct('single', 'SKU-1', [])).toBe(true)
    expect(hasQuotationProduct('bundle', '', ['SKU-1', 'SKU-2'])).toBe(true)
    expect(hasQuotationProduct('bundle', '', ['', 'SKU-2'])).toBe(false)
    expect(hasQuotationProduct('bundle', '', ['sku-2', 'SKU-2'])).toBe(false)
    expect(hasQuotationProduct('bundle', '', ['', '  '])).toBe(false)
  })

  it('preserves a manually selected bundle category across mixed SKU categories', () => {
    expect(resolveBundleProductCategory('内裤', '服装', ['护肤品'])).toBe('内裤')
    expect(resolveBundleProductCategory('', '服装', ['服装'])).toBe('服装')
    expect(resolveBundleProductCategory('', '服装', ['护肤品'])).toBe('')
  })
})
