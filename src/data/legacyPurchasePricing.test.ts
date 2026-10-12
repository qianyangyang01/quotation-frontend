import { expect, it } from 'vitest'
import { normalizePurchaseRecord } from './purchaseStore'
import { legacyUntaxedUnitPrice, legacyAutoTaxPrice, withLegacyAutoTax } from './legacyPurchasePricing'
import { purchasePriceBreakdown } from '@/services/quotationCalculator'

const old = () => normalizePurchaseRecord({ sku: 'FL2600293-2', dataSource: 'legacy_2026',
  purchasePriceBasis: 'tax_included', sourceQuotedPriceCny: 110, purchasePriceCny: 121,
  taxIncludedPriceCny: 121, taxPoint: .1, minOrderQty: 2, weightG: 135, singleFreightCny: 5 })

it('keeps untaxed input, derived included price, catalog price and quotation price consistent after repeated saves', () => {
  const original = old()
  const snapshot = JSON.stringify(original)
  expect(legacyUntaxedUnitPrice(original)).toBe(110)
  const edited = normalizePurchaseRecord(withLegacyAutoTax(original, 100))
  expect(edited).toMatchObject({ sourceQuotedPriceCny: 100, purchasePriceCny: 110, taxIncludedPriceCny: 110, legacyPriceMode: 'auto_tax' })
  expect(edited.priceTiers[0]?.unitPriceCny).toBe(110)
  expect(purchasePriceBreakdown(edited, '10')).toMatchObject({ effectiveUnitPriceCny: 110, invoiceMultiplier: 1 })
  const changedRate = normalizePurchaseRecord(withLegacyAutoTax({ ...edited, taxPoint: .08 }, legacyUntaxedUnitPrice(edited)))
  expect(changedRate.taxIncludedPriceCny).toBe(108)
  expect(purchasePriceBreakdown(changedRate, '10').effectiveUnitPriceCny).toBe(108)
  expect(JSON.stringify(original)).toBe(snapshot)
})

it('never guesses an untaxed price from an included-only historical record', () => {
  expect(legacyUntaxedUnitPrice({ ...old(), sourceQuotedPriceCny: null })).toBeNull()
  expect(legacyUntaxedUnitPrice({ ...old(), sourceQuotedPriceCny: null, purchasePriceBasis: 'quoted' })).toBe(121)
})

it.each([null, '', ' ', -1, 1.01, Infinity, 'invalid'])('does not produce an included price for invalid/missing rate %s', rate => {
  expect(legacyAutoTaxPrice(110, rate)).toBeNull()
})

it.each([null, '', 0, -1, Infinity, 'invalid'])('rejects invalid/missing untaxed input %s', price => {
  expect(() => withLegacyAutoTax(old(), price)).toThrow('未税采购单价')
})

it('rounds half up and keeps zero-point quotation adjustment separate from included price', () => {
  expect(legacyAutoTaxPrice(1.005, 0)).toBe(1.01)
  const zero = normalizePurchaseRecord(withLegacyAutoTax({ ...old(), taxPoint: 0 }, 110))
  expect(zero.taxIncludedPriceCny).toBe(110)
  expect(purchasePriceBreakdown(zero, '10')).toMatchObject({ effectiveUnitPriceCny: 111.1, priceSource: 'zero-tax-point' })
})

it('does not apply the amount-equality heuristic to an explicitly calculated included price', () => {
  const tiny = normalizePurchaseRecord(withLegacyAutoTax({ ...old(), taxPoint: .004 }, 1.004))
  expect(tiny.taxIncludedPriceCny).toBe(1.01)
  expect(purchasePriceBreakdown(tiny, '10').effectiveUnitPriceCny).toBe(1.01)
  // An explicitly saved included base remains authoritative even if original-price metadata happens to equal it.
  expect(purchasePriceBreakdown({ ...tiny, sourceQuotedPriceCny: 1.01 }, '10').invoiceMultiplier).toBe(1)
})

it('keeps optional tier prices untaxed and applies the current ticket point once', () => {
  const tiered = normalizePurchaseRecord(withLegacyAutoTax({ ...old(), tier2MinQty: 10, tier2PriceCny: 90 }, 100))
  expect(purchasePriceBreakdown(tiered, '100')).toMatchObject({ baseUnitPriceCny: 90, effectiveUnitPriceCny: 99 })
})
