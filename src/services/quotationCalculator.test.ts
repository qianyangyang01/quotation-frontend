import { describe, expect, it } from 'vitest'
import { normalizePurchaseRecord } from '@/data/purchaseStore'
import {
  bundleBaseWeight,
  bundleDomesticFreight,
  bundleGoodsWeight,
  bundlePackagingWeight,
  bundlePurchaseCost,
  hasQuotationProduct,
  missingPurchaseTaxPointSkus,
  monthlySalesTierLabel,
  packagingWeightKg,
  purchaseInvoiceRatePercent,
  purchasePriceBreakdown,
  purchasePriceForMonthlySales,
  resolveBundleProductCategory,
  singleActualWeight,
  singleBaseWeight,
  singleChargeWeight,
  singlePackagingWeight,
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
const taxed = normalizePurchaseRecord({
  sku: 'BIZ-TAXED', catalogState: 'ready', weightG: 100, minOrderQty: 1, purchasePriceCny: 1,
  tier2MinQty: 10, tier2PriceCny: 17.98, invoiceType: '普票6%', taxIncludedPriceCny: 999,
})

describe('quotation purchase tiers', () => {
  it('prices bundle rows at independent tiers, including quantities, taxes and legacy fallback', () => {
    const records = ['BK2601961', 'BK2601961-1'].map(sku => normalizePurchaseRecord({
      sku, catalogState: 'ready', weightG: 200, minOrderQty: 100, purchasePriceCny: 13.8, tier2MinQty: 500, tier2PriceCny: 12.5,
      tier3MinQty: 1000, tier3PriceCny: 11.5, taxPoint: .02, invoiceType: '普票',
    }))
    const rows = records.map((record, index) => ({ sku: record.sku, purchaseTier: index ? '100' : '10',
      quantityPerSet: index + 1, purchaseUnitPrice: 999, purchaseFreightPerUnit: .21, weightKg: .2, customWeightKg: null }))
    // 1 x 14.08 + 2 x 12.75 per set; the global tier 3 must not override either row.
    expect(bundlePurchaseCost(rows, records, '100+', 3)).toBe(118.74)
    expect(bundlePurchaseCost(rows.map(row => ({ ...row, purchaseTier: undefined })), records, '100', 3)).toBe(114.75)
    expect(bundlePurchaseCost(rows.map(row => ({ ...row, purchaseInvoiceTaxApplied: false })), records, '100+', 3)).toBe(116.4)
    expect(bundlePurchaseCost([{ ...rows[0]!, sku: 'UNAVAILABLE', purchaseUnitPrice: 8 }], records, '100+', 2)).toBe(16)
  })

  it('maps persisted option values to purchase tier labels', () => {
    expect(['10', '100', '100+'].map(value => monthlySalesTierLabel(value))).toEqual(['阶梯价1', '阶梯价2', '阶梯价3'])
  })

  it('selects the displayed tiers for BK2601961-1 regardless of their minimum quantities', () => {
    const record = normalizePurchaseRecord({
      sku: 'BK2601961-1', weightG: 219, minOrderQty: 100, purchasePriceCny: 13.8,
      tier2MinQty: 500, tier2PriceCny: 12.5, tier3MinQty: 1000, tier3PriceCny: 11.5,
      taxPoint: .02, invoiceType: '普票', freight10Cny: 2.1,
    })
    expect(['10', '100', '100+'].map(value => purchasePriceBreakdown(record, value).baseUnitPriceCny)).toEqual([13.8, 12.5, 11.5])
    expect(['10', '100', '100+'].map(value => purchasePriceForMonthlySales(record, value))).toEqual([14.08, 12.75, 11.73])
    expect(monthlySalesTierLabel('100', record)).toBe('阶梯价2（500–999件）')
    const items = [{ sku: record.sku, quantityPerSet: 2, purchaseUnitPrice: 99, purchaseFreightPerUnit: .21, weightKg: .219, customWeightKg: null }]
    expect(bundlePurchaseCost(items, [record], '100', 3)).toBe(76.5)
    expect(bundleDomesticFreight(items, 3)).toBe(1.26)
  })

  it('uses the last available tier with an explicit fallback label when higher tiers are absent', () => {
    expect(purchasePriceForMonthlySales(taxed, '100+')).toBe(19.06)
    expect(monthlySalesTierLabel('100+', taxed)).toBe('阶梯价3未配置，采用阶梯价2（10件起）')
    const single = normalizePurchaseRecord({ sku: 'SINGLE', minOrderQty: 100, purchasePriceCny: 13.8 })
    expect(purchasePriceForMonthlySales(single, '100')).toBe(13.8)
    expect(monthlySalesTierLabel('100', single)).toBe('阶梯价2未配置，采用阶梯价1（100件起）')
    const noTiers = normalizePurchaseRecord({ purchasePriceCny: 8 })
    expect(purchasePriceForMonthlySales(noTiers, '100+')).toBe(8)
    expect(monthlySalesTierLabel('100+', noTiers)).toBe('阶梯价3未配置，采用基准采购价')
  })

  it('matches the purchase list order even when optional tier fields are sparse and preserves a zero price', () => {
    const sparse = normalizePurchaseRecord({ minOrderQty: 100, purchasePriceCny: 13.8, tier3MinQty: 1000, tier3PriceCny: 0 })
    expect(purchasePriceForMonthlySales(sparse, '100')).toBe(0)
    expect(monthlySalesTierLabel('100', sparse)).toBe('阶梯价2（1000件起）')
  })

  it('uses exact tier boundaries and accepts equal tier prices', () => {
    expect(purchasePriceForMonthlySales(first, '10')).toBe(20)
    expect(purchasePriceForMonthlySales(first, '100')).toBe(18)
    expect(purchasePriceForMonthlySales(first, '100+')).toBe(16)
    expect(purchasePriceForMonthlySales(second, '100+')).toBe(12)
  })

  it('applies the purchase invoice percentage after tier matching and rounds each unit to cents', () => {
    expect(purchasePriceForMonthlySales(taxed, '10')).toBe(1.06)
    expect(purchasePriceForMonthlySales(taxed, '100')).toBe(19.06)
    expect(purchasePriceBreakdown(taxed, '100')).toEqual({
      baseUnitPriceCny: 17.98,
      invoiceType: '普票6%',
      taxPoint: null,
      invoiceRatePercent: 6,
      invoiceMultiplier: 1.06,
      invoiceTaxApplied: true,
      priceSource: 'legacy-invoice-type',
      effectiveUnitPriceCny: 19.06,
    })
  })

  it('ignores the recorded tax-included price and treats missing or nonnumeric invoice types as zero percent', () => {
    expect(purchasePriceForMonthlySales(taxed, '10')).toBe(1.06)
    expect(purchaseInvoiceRatePercent('增值税普通发票')).toBe(0)
    expect(purchaseInvoiceRatePercent('不开票')).toBe(0)
    expect(purchaseInvoiceRatePercent('')).toBe(0)
  })

  it.each([
    ['普票1%', 1],
    ['普票3%', 3],
    ['普票6%', 6],
    ['专票13%', 13],
  ])('maps %s to a %d percent purchase invoice rate', (invoiceType, expected) => {
    expect(purchaseInvoiceRatePercent(invoiceType)).toBe(expected)
  })

  it('keeps legacy draft pricing unadjusted when the compatibility flag is false', () => {
    expect(purchasePriceForMonthlySales(taxed, '100', false)).toBe(17.98)
    expect(purchasePriceBreakdown(taxed, '100', false).invoiceRatePercent).toBe(6)
  })

  it('uses the independent tax point after tier selection', () => {
    const current = normalizePurchaseRecord({ ...taxed, invoiceType: '专票', taxPoint: 0.08, taxIncludedPriceCny: 999 })
    const result = purchasePriceBreakdown(current, '100')
    expect(result.baseUnitPriceCny).toBe(17.98)
    expect(result.effectiveUnitPriceCny).toBe(19.42)
    expect(result.priceSource).toBe('tier-tax-point')
    expect(result.invoiceRatePercent).toBe(8)
  })

  it('falls back to tax-included price only when the explicit tax point is blank', () => {
    const current = normalizePurchaseRecord({ ...taxed, invoiceType: '专票', taxPoint: null, taxIncludedPriceCny: 22 })
    const result = purchasePriceBreakdown(current, '100')
    expect(result.effectiveUnitPriceCny).toBe(22)
    expect(result.priceSource).toBe('tax-included-price')
  })

  it('uses the legacy final purchase price without applying the recorded ticket point twice', () => {
    const legacy = normalizePurchaseRecord({
      sku: 'OLD-260001', dataSource: 'legacy_2026', catalogState: 'ready', weightG: 70,
      purchasePriceCny: 6.18, purchasePriceBasis: 'tax_included', sourceQuotedPriceCny: 6,
      taxIncludedPriceCny: 6.18, taxPoint: 0.03, minOrderQty: 1, singleFreightCny: 1.7,
    })
    expect(purchasePriceBreakdown(legacy, '100+')).toMatchObject({
      baseUnitPriceCny: 6.18,
      effectiveUnitPriceCny: 6.18,
      invoiceMultiplier: 1,
      invoiceRatePercent: 3,
      priceSource: 'legacy-final-price',
    })
  })
})

describe('legacy purchase tax reconciliation', () => {
  const az = normalizePurchaseRecord({
    sku: 'AZ2601758', dataSource: 'legacy_2026', purchasePriceBasis: 'tax_included',
    purchasePriceCny: 6.8, sourceQuotedPriceCny: 6.8, taxIncludedPriceCny: 6.8,
    taxPoint: .01, invoiceType: '普票', minOrderQty: 1, weightG: 14, singleFreightCny: 1,
  })

  it.each([true, false])('adds the confirmed 1%% when the legacy included price equals the original (old draft flag %s)', flag => {
    expect(purchasePriceBreakdown(az, '10', flag)).toMatchObject({
      baseUnitPriceCny: 6.8, taxPoint: .01, invoiceRatePercent: 1, invoiceMultiplier: 1.01,
      effectiveUnitPriceCny: 6.87, invoiceTaxApplied: true, priceSource: 'legacy-tax-point',
    })
    expect(purchasePriceForMonthlySales(az, '100+', flag)).toBe(6.87)
  })

  it('charges the fallback 1% for zero and the actual confirmed rate for positive tax points', () => {
    expect(purchasePriceBreakdown({ ...az, taxPoint: 0 }, '10')).toMatchObject({ effectiveUnitPriceCny: 6.87, invoiceRatePercent: 1, priceSource: 'zero-tax-point' })
    expect(purchasePriceForMonthlySales({ ...az, taxPoint: .08 }, '10')).toBe(7.34)
    expect(purchasePriceForMonthlySales({ ...az, sourceQuotedPriceCny: null, purchasePriceBasis: 'quoted' }, '10')).toBe(6.87)
  })

  it('preserves separately supplied included prices and missing-original records without hiding their tax point', () => {
    for (const price of [6.5, 6.87, 7]) {
      const record = normalizePurchaseRecord({ ...az, purchasePriceCny: price, taxIncludedPriceCny: price })
      expect(purchasePriceBreakdown(record, '10')).toMatchObject({ effectiveUnitPriceCny: price, invoiceRatePercent: 1, invoiceMultiplier: 1, priceSource: 'legacy-final-price' })
    }
    expect(purchasePriceBreakdown({ ...az, sourceQuotedPriceCny: null }, '10')).toMatchObject({ effectiveUnitPriceCny: 6.8, invoiceRatePercent: 1, priceSource: 'legacy-final-price' })
    expect(missingPurchaseTaxPointSkus([az.sku], [{ ...az, taxPoint: null }])).toEqual([az.sku])
  })

  it('reconciles the reported combination without taxing domestic freight or changing purchase records', () => {
    const fz = normalizePurchaseRecord({ sku: 'FZ2500491', purchasePriceCny: 6.9, minOrderQty: 3, taxPoint: .08, invoiceType: '不开票', freight10Cny: 8 })
    const purchases = [fz, az], before = JSON.stringify(purchases)
    const items = [
      { sku: fz.sku, quantityPerSet: 2, purchaseUnitPrice: 7.45, purchaseFreightPerUnit: .8, weightKg: .035, customWeightKg: null },
      { sku: az.sku, quantityPerSet: 1, purchaseUnitPrice: 6.8, purchaseFreightPerUnit: 1, weightKg: .014, customWeightKg: null },
    ]
    expect(bundlePurchaseCost(items, purchases, '10')).toBe(21.77)
    expect(bundlePurchaseCost(items, purchases, '10', 3)).toBe(65.31)
    expect(bundleDomesticFreight(items)).toBe(2.6)
    expect(JSON.stringify(purchases)).toBe(before)
  })
})

describe('single SKU weight calculation', () => {
  const input = {
    quantity: 2, netWeight: 0.4, weightSource: 'purchase' as const, manualWeight: 0.7,
    volumetricEnabled: true, packageLengthCm: 40, packageWidthCm: 30, packageHeightCm: 20, volumeDivisor: 8000,
  }

  it('adds packaging to every physical item for purchase and manual weights', () => {
    expect(singleBaseWeight(input)).toBe(0.8)
    expect(singlePackagingWeight(input)).toBe(0.016)
    expect(singleActualWeight(input)).toBeCloseTo(0.816)
    expect(singleVolumeWeight(input)).toBe(6)
    expect(singleChargeWeight(input)).toBeCloseTo(0.816)
    expect(singleActualWeight({ ...input, weightSource: 'manual' })).toBeCloseTo(1.428)
  })

  it.each([
    [0, 0], [1, 1], [49, 1], [50, 1], [51, 2], [100, 2], [270, 6],
  ])('adds %sg base weight as %sg packaging', (baseGrams, packagingGrams) => {
    expect(packagingWeightKg(baseGrams / 1000)).toBeCloseTo(packagingGrams / 1000, 10)
  })

  it('disables volumetric calculation when dimensions are missing', () => {
    expect(singleVolumeWeight({ ...input, packageHeightCm: 0 })).toBe(0)
    expect(singleShipmentDimensions({ ...input, volumetricEnabled: false })).toBeUndefined()
    expect(singleShipmentDimensions(input)).toBeUndefined()
  })
})

describe('bundle SKU calculation', () => {
  const items = [
    { sku: first.sku, quantityPerSet: 2, purchaseUnitPrice: 99, purchaseFreightPerUnit: 1.5, weightKg: 0.2, customWeightKg: null },
    { sku: second.sku, quantityPerSet: 1, purchaseUnitPrice: 99, purchaseFreightPerUnit: 2, weightKg: 0.35, customWeightKg: 0.5 },
  ]

  it('aggregates each SKU quantity, freight, base weight and packaging per set', () => {
    expect(bundlePurchaseCost(items, [first, second], '100', 3)).toBe((18 * 2 + 12) * 3)
    expect(bundleDomesticFreight(items, 3)).toBe((1.5 * 2 + 2) * 3)
    expect(bundleBaseWeight(items, 3)).toBeCloseTo((0.2 * 2 + 0.5) * 3)
    expect(bundlePackagingWeight(items, 3)).toBeCloseTo((0.004 * 2 + 0.01) * 3)
    expect(bundleGoodsWeight(items, 3)).toBeCloseTo(((0.2 + 0.004) * 2 + 0.5 + 0.01) * 3)
  })

  it('applies each bundle SKU invoice rate independently without changing domestic freight', () => {
    const taxedItems = [
      { sku: taxed.sku, quantityPerSet: 2, purchaseUnitPrice: 0, purchaseInvoiceTaxApplied: true, purchaseFreightPerUnit: 1.5, weightKg: 0.1, customWeightKg: null },
      { sku: second.sku, quantityPerSet: 1, purchaseUnitPrice: 0, purchaseInvoiceTaxApplied: true, purchaseFreightPerUnit: 2, weightKg: 0.35, customWeightKg: null },
    ]
    expect(bundlePurchaseCost(taxedItems, [taxed, second], '10')).toBe(14.12)
    expect(bundleDomesticFreight(taxedItems)).toBe(1.5 * 2 + 2)
    expect(bundlePurchaseCost([{ ...taxedItems[0], purchaseInvoiceTaxApplied: false }], [taxed], '10')).toBe(2)
  })

  it('rounds CNY before converting to USD and guards invalid rates', () => {
    expect(usdPriceFromCny(70.126, 7)).toBeCloseTo(10.018571, 6)
    expect(usdPriceFromCny(-1, 0)).toBe(0)
  })

  it('recognizes loaded single and bundle products consistently before save', () => {
    const rows = (skus: string[]) => skus.map(sku => ({ sku, quantityPerSet: 1 }))
    expect(hasQuotationProduct('single', 'SKU-1', [])).toBe(true)
    expect(hasQuotationProduct('bundle', '', rows(['SKU-1', 'SKU-2']))).toBe(true)
    expect(hasQuotationProduct('bundle', '', rows(['', 'SKU-2']))).toBe(false)
    expect(hasQuotationProduct('bundle', '', rows(['sku-2', 'SKU-2']))).toBe(false)
    expect(hasQuotationProduct('bundle', '', rows(['', '  ']))).toBe(false)
  })

  it('accepts same-SKU sets and rejects single pieces, duplicate rows and invalid quantities', () => {
    for (const quantityPerSet of [2, 3, 10]) {
      expect(hasQuotationProduct('bundle', '', [{ sku: 'SKU-1', quantityPerSet }])).toBe(true)
    }
    for (const quantityPerSet of [0, 1, -1, 1.5, NaN, Infinity]) {
      expect(hasQuotationProduct('bundle', '', [{ sku: 'SKU-1', quantityPerSet }])).toBe(false)
    }
    expect(hasQuotationProduct('bundle', '', [{ sku: 'SKU-1', quantityPerSet: 3 }, { sku: '', quantityPerSet: 1 }])).toBe(true)
    expect(hasQuotationProduct('bundle', '', [{ sku: 'SKU-1', quantityPerSet: 3 }, { sku: 'sku-1', quantityPerSet: 1 }])).toBe(false)
  })

  it('calculates three identical pieces per set and six pieces for two sets', () => {
    const items = [{ sku: first.sku, quantityPerSet: 3, purchaseUnitPrice: 20, purchaseFreightPerUnit: 1.5, weightKg: .2, customWeightKg: null }]
    for (const sets of [1, 2]) {
      expect(bundlePurchaseCost(items, [first], '10', sets)).toBe(60 * sets)
      expect(bundleDomesticFreight(items, sets)).toBe(4.5 * sets)
      expect(bundleBaseWeight(items, sets)).toBeCloseTo(.6 * sets)
      expect(bundlePackagingWeight(items, sets)).toBeCloseTo(.012 * sets)
      expect(bundleGoodsWeight(items, sets)).toBeCloseTo(.612 * sets)
    }
  })

  it('preserves a manually selected bundle category across mixed SKU categories', () => {
    expect(resolveBundleProductCategory('内裤', '服装', ['护肤品'])).toBe('内裤')
    expect(resolveBundleProductCategory('', '服装', ['服装'])).toBe('服装')
    expect(resolveBundleProductCategory('', '服装', ['护肤品'])).toBe('')
  })
})

it('requires an explicit purchase tax point for every referenced SKU, including legacy and tax-included records', () => {
  expect(normalizePurchaseRecord(JSON.parse('{"taxPoint":"   "}')).taxPoint).toBeNull()
  const records = [
    normalizePurchaseRecord({ ...taxed, sku: 'EMPTY', taxPoint: null, taxIncludedPriceCny: 22 }),
    normalizePurchaseRecord({ ...taxed, sku: 'OLD', dataSource: 'legacy_2026', invoiceType: '13%' }),
    normalizePurchaseRecord({ ...taxed, sku: 'ZERO', taxPoint: 0 }),
    normalizePurchaseRecord({ ...taxed, sku: 'TAX', taxPoint: .03 }),
  ]
  expect(missingPurchaseTaxPointSkus(['EMPTY', 'OLD', 'ZERO', 'TAX', 'ABSENT', 'EMPTY'], records)).toEqual(['EMPTY', 'OLD', 'ABSENT'])
  records[0]!.taxPoint = 0
  expect(missingPurchaseTaxPointSkus(['EMPTY', 'ZERO', 'TAX'], records)).toEqual([])
})

it.each([true, false])('uses the 1.01 multiplier for an explicit zero even when the old draft tax flag is %s', invoiceFlag => {
  const zero = normalizePurchaseRecord({ ...first, taxPoint: 0, taxIncludedPriceCny: 999 })
  expect(purchasePriceBreakdown(zero, '10', invoiceFlag)).toMatchObject({ taxPoint: 0, invoiceMultiplier: 1.01, effectiveUnitPriceCny: 20.2, priceSource: 'zero-tax-point', invoiceTaxApplied: true })
  expect(purchasePriceForMonthlySales(zero, '100', invoiceFlag)).toBe(18.18)
  expect(purchasePriceForMonthlySales(zero, '100+', invoiceFlag)).toBe(16.16)
  const legacy = normalizePurchaseRecord({ ...zero, dataSource: 'legacy_2026', purchasePriceBasis: 'tax_included' })
  expect(purchasePriceForMonthlySales(legacy, '10', invoiceFlag)).toBe(20.2)
  expect(bundlePurchaseCost([{ sku: zero.sku, quantityPerSet: 2, purchaseUnitPrice: 20, purchaseInvoiceTaxApplied: invoiceFlag, purchaseFreightPerUnit: 0, weightKg: .2, customWeightKg: null }], [zero], '10', 3)).toBeCloseTo(121.2)
})
