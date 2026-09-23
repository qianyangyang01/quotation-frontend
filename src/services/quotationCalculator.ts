import { decimal, sumDecimal, productDecimal } from './quotationDecimal'
import type { ShipmentDimensions } from '@/data/logistics'
import { findPurchaseProduct, type PurchaseProductRecord } from '@/data/purchaseStore'

export type MonthlySalesEstimate = '10' | '100' | '100+'

export type BundleCalculationItem = {
  sku: string
  quantityPerSet: number
  purchaseUnitPrice: number
  purchaseInvoiceTaxApplied?: boolean
  purchaseFreightPerUnit: number
  weightKg: number
  customWeightKg: number | null
}

export type SingleWeightInput = {
  quantity: number
  netWeight: number
  weightSource: 'purchase' | 'manual'
  manualWeight: number
  volumetricEnabled: boolean
  packageLengthCm: number
  packageWidthCm: number
  packageHeightCm: number
  volumeDivisor: number
}

export function normalizedQuoteQuantity(value: number) {
  return Math.max(1, Math.floor(Number(value) || 1))
}

// Keep persisted option values compatible with existing drafts and reissued quotes.
function purchaseTierIndex(value: string): number {
  return value === '100+' ? 2 : value === '100' ? 1 : 0
}

function selectedPurchaseTier(record: PurchaseProductRecord, value: string) {
  const index = Math.min(purchaseTierIndex(value), record.priceTiers.length - 1)
  return { index, tier: record.priceTiers[index] }
}

export function monthlySalesTierLabel(value: string, record?: PurchaseProductRecord) {
  const requested = purchaseTierIndex(value) + 1
  if (!record) return `阶梯价${requested}`
  const { index, tier } = selectedPurchaseTier(record, value)
  if (!tier) return `阶梯价${requested}未配置，采用基准采购价`
  const range = tier.maxQty == null ? `${tier.minQty}件起` : `${tier.minQty}–${tier.maxQty}件`
  const actual = index + 1
  return `${actual === requested ? `阶梯价${actual}` : `阶梯价${requested}未配置，采用阶梯价${actual}`}（${range}）`
}

export type PurchasePriceBreakdown = {
  baseUnitPriceCny: number
  invoiceType: string
  taxPoint: number | null
  invoiceRatePercent: number
  invoiceMultiplier: number
  invoiceTaxApplied: boolean
  priceSource: 'zero-tax-point' | 'tier-tax-point' | 'tax-included-price' | 'untaxed-tier' | 'legacy-invoice-type' | 'legacy-final-price'
  effectiveUnitPriceCny: number
}

export function roundCny(value: number) {
  return decimal(Math.max(0, Number(value) || 0)).toDecimalPlaces(2).toNumber()
}

export function purchaseInvoiceRatePercent(invoiceType: string) {
  const matched = String(invoiceType || '').match(/(\d+(?:\.\d+)?)\s*%/)
  if (!matched) return 0
  const rate = Number(matched[1])
  return Number.isFinite(rate) && rate >= 0 && rate <= 100 ? rate : 0
}

/** Missing is different from an explicit zero, including legacy and tax-included purchases. */
export function missingPurchaseTaxPointSkus(skus: string[], records: PurchaseProductRecord[]) {
  return [...new Set(skus.filter(sku => sku.trim()))].filter(sku => {
    const record = findPurchaseProduct(records, sku)
    return !record || record.taxPoint == null || !Number.isFinite(record.taxPoint)
  })
}

export function purchasePriceBreakdown(record: PurchaseProductRecord, estimate: string, invoiceTaxApplied = true): PurchasePriceBreakdown {
  const { tier } = selectedPurchaseTier(record, estimate)
  const baseUnitPriceCny = roundCny(tier?.unitPriceCny ?? record.purchasePriceCny ?? 0)
  if (record.taxPoint === 0) {
    return {
      baseUnitPriceCny, invoiceType: record.invoiceType, taxPoint: 0,
      invoiceRatePercent: 1, invoiceMultiplier: 1.01, invoiceTaxApplied: true,
      priceSource: 'zero-tax-point', effectiveUnitPriceCny: roundCny(productDecimal(baseUnitPriceCny, 1.01)),
    }
  }
  if (record.dataSource === 'legacy_2026') {
    return {
      baseUnitPriceCny,
      invoiceType: record.invoiceType,
      taxPoint: record.taxPoint,
      invoiceRatePercent: 0,
      invoiceMultiplier: 1,
      invoiceTaxApplied: true,
      priceSource: 'legacy-final-price',
      effectiveUnitPriceCny: baseUnitPriceCny,
    }
  }
  const explicitTaxPoint = record.taxPointExplicit
  const taxPoint = record.taxPoint
  const legacyRatePercent = explicitTaxPoint ? 0 : purchaseInvoiceRatePercent(record.invoiceType)
  const invoiceRatePercent = taxPoint == null ? legacyRatePercent : productDecimal(taxPoint, 100)
  const appliedRatePercent = invoiceTaxApplied ? invoiceRatePercent : 0
  const useTaxIncludedPrice = invoiceTaxApplied && explicitTaxPoint && taxPoint == null && record.taxIncludedPriceCny != null
  const priceSource: PurchasePriceBreakdown['priceSource'] = !invoiceTaxApplied
    ? 'untaxed-tier'
    : taxPoint != null ? 'tier-tax-point'
      : useTaxIncludedPrice ? 'tax-included-price'
        : legacyRatePercent > 0 ? 'legacy-invoice-type' : 'untaxed-tier'
  return {
    baseUnitPriceCny,
    invoiceType: record.invoiceType,
    taxPoint,
    invoiceRatePercent,
    invoiceMultiplier: decimal(appliedRatePercent).div(100).plus(1).toNumber(),
    invoiceTaxApplied,
    priceSource,
    effectiveUnitPriceCny: useTaxIncludedPrice ? roundCny(record.taxIncludedPriceCny ?? 0) : roundCny(decimal(baseUnitPriceCny).times(decimal(appliedRatePercent).div(100).plus(1)).toNumber()),
  }
}

export function purchasePriceForMonthlySales(record: PurchaseProductRecord, estimate: string, invoiceTaxApplied = true) {
  return purchasePriceBreakdown(record, estimate, invoiceTaxApplied).effectiveUnitPriceCny
}

export function bundlePurchaseCost(
  items: BundleCalculationItem[],
  records: PurchaseProductRecord[],
  estimate: string,
  sets = 1,
) {
  const setCount = normalizedQuoteQuantity(sets)
  return items.reduce((sum, item) => {
    const record = findPurchaseProduct(records, item.sku)
    const purchasePrice = record ? purchasePriceForMonthlySales(record, estimate, item.purchaseInvoiceTaxApplied !== false) : item.purchaseUnitPrice
    return sumDecimal(sum, productDecimal(purchasePrice, normalizedQuoteQuantity(item.quantityPerSet), setCount))
  }, 0)
}

export function bundleDomesticFreight(items: BundleCalculationItem[], sets = 1) {
  const setCount = normalizedQuoteQuantity(sets)
  return items.reduce((sum, item) => sumDecimal(sum, productDecimal(item.purchaseFreightPerUnit, normalizedQuoteQuantity(item.quantityPerSet), setCount)), 0)
}

export function bundleGoodsWeight(items: BundleCalculationItem[], sets = 1, specialPackagingWeightKg = 0) {
  const setCount = normalizedQuoteQuantity(sets)
  return sumDecimal(specialPackagingWeightKg, items.reduce((sum, item) => {
    const baseWeightKg = bundleItemBaseWeight(item)
    return sumDecimal(sum, productDecimal(packagedUnitWeightKg(baseWeightKg), normalizedQuoteQuantity(item.quantityPerSet), setCount))
  }, 0))
}

export function packagingWeightKg(baseWeightKg: number) {
  const baseGrams = decimal(Math.max(0, Number(baseWeightKg) || 0)).times(1000).toNumber()
  if (baseGrams <= 0) return 0
  return decimal(baseGrams).div(50).ceil().div(1000).toNumber()
}

export function packagedUnitWeightKg(baseWeightKg: number) {
  const normalizedBase = Math.max(0, Number(baseWeightKg) || 0)
  return sumDecimal(normalizedBase, packagingWeightKg(normalizedBase))
}

function bundleItemBaseWeight(item: BundleCalculationItem) {
  return item.customWeightKg != null && Number.isFinite(Number(item.customWeightKg))
    ? Math.max(0, Number(item.customWeightKg))
    : Math.max(0, Number(item.weightKg) || 0)
}

export function bundleBaseWeight(items: BundleCalculationItem[], sets = 1) {
  const setCount = normalizedQuoteQuantity(sets)
  return items.reduce((sum, item) => sumDecimal(sum, productDecimal(bundleItemBaseWeight(item), normalizedQuoteQuantity(item.quantityPerSet), setCount)), 0)
}

export function bundlePackagingWeight(items: BundleCalculationItem[], sets = 1) {
  const setCount = normalizedQuoteQuantity(sets)
  return items.reduce((sum, item) => sumDecimal(sum, productDecimal(packagingWeightKg(bundleItemBaseWeight(item)), normalizedQuoteQuantity(item.quantityPerSet), setCount)), 0)
}

export function resolveBundleProductCategory(selectedCategory: string, recordCategory: string, existingCategories: string[]) {
  if (selectedCategory) return selectedCategory
  return recordCategory && existingCategories.every(category => category === recordCategory) ? recordCategory : ''
}

function singleUnitBaseWeight(input: SingleWeightInput) {
  const unitWeight = input.weightSource === 'manual' ? input.manualWeight : input.netWeight
  return Math.max(0, Number(unitWeight) || 0)
}

export function singleBaseWeight(input: SingleWeightInput, quantity = normalizedQuoteQuantity(input.quantity)) {
  return productDecimal(singleUnitBaseWeight(input), normalizedQuoteQuantity(quantity))
}

export function singlePackagingWeight(input: SingleWeightInput, quantity = normalizedQuoteQuantity(input.quantity)) {
  return productDecimal(packagingWeightKg(singleUnitBaseWeight(input)), normalizedQuoteQuantity(quantity))
}

export function singleActualWeight(input: SingleWeightInput, quantity = normalizedQuoteQuantity(input.quantity), specialPackagingWeightKg = 0) {
  return sumDecimal(productDecimal(packagedUnitWeightKg(singleUnitBaseWeight(input)), normalizedQuoteQuantity(quantity)), specialPackagingWeightKg)
}

export function singleVolumeWeight(input: SingleWeightInput, quantity = normalizedQuoteQuantity(input.quantity), divisor = input.volumeDivisor) {
  if (!input.volumetricEnabled || input.packageLengthCm <= 0 || input.packageWidthCm <= 0 || input.packageHeightCm <= 0) return 0
  return decimal(input.packageLengthCm).times(input.packageWidthCm).times(input.packageHeightCm).times(normalizedQuoteQuantity(quantity)).div(Math.max(1, Number(divisor) || 8000)).toNumber()
}

export function singleChargeWeight(input: SingleWeightInput, quantity = normalizedQuoteQuantity(input.quantity)) {
  return singleActualWeight(input, quantity)
}

export function singleShipmentDimensions(input: SingleWeightInput, quantity = normalizedQuoteQuantity(input.quantity)): ShipmentDimensions | undefined {
  void input
  void quantity
  return undefined
}

export function usdPriceFromCny(cny: number, usdCny: number) {
  const rate = Math.max(0.0001, Number(usdCny) || 0)
  return decimal(Math.max(0, Number(cny) || 0)).toDecimalPlaces(2).div(rate).toNumber()
}

export function hasQuotationProduct(mode: 'single' | 'bundle', primarySku: string, bundleSkus: string[]) {
  if (mode === 'single') return Boolean(primarySku.trim())
  return new Set(bundleSkus.map(sku => sku.trim().toUpperCase()).filter(Boolean)).size >= 2
}
