import type { ShipmentDimensions } from '@/data/logistics'
import { findPurchaseProduct, purchaseUnitPrice, type PurchaseProductRecord } from '@/data/purchaseStore'

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

export function purchaseQuantityForMonthlySales(value: string): number {
  return value === '100+' ? 100 : value === '100' ? 10 : 1
}

export function monthlySalesTierLabel(value: string) {
  return value === '100+' ? '100件采购价' : value === '100' ? '10件采购价' : '1件参考价'
}

export type PurchasePriceBreakdown = {
  baseUnitPriceCny: number
  invoiceType: string
  taxPoint: number | null
  invoiceRatePercent: number
  invoiceMultiplier: number
  invoiceTaxApplied: boolean
  priceSource: 'tier-tax-point' | 'tax-included-price' | 'untaxed-tier' | 'legacy-invoice-type'
  effectiveUnitPriceCny: number
}

export function roundCny(value: number) {
  return Math.round((Math.max(0, Number(value) || 0) + Number.EPSILON) * 100) / 100
}

export function purchaseInvoiceRatePercent(invoiceType: string) {
  const matched = String(invoiceType || '').match(/(\d+(?:\.\d+)?)\s*%/)
  if (!matched) return 0
  const rate = Number(matched[1])
  return Number.isFinite(rate) && rate >= 0 && rate <= 100 ? rate : 0
}

export function purchasePriceBreakdown(record: PurchaseProductRecord, estimate: string, invoiceTaxApplied = true): PurchasePriceBreakdown {
  const baseUnitPriceCny = roundCny(purchaseUnitPrice(record, purchaseQuantityForMonthlySales(estimate)))
  const explicitTaxPoint = record.taxPointExplicit
  const taxPoint = record.taxPoint
  const legacyRatePercent = explicitTaxPoint ? 0 : purchaseInvoiceRatePercent(record.invoiceType)
  const invoiceRatePercent = taxPoint == null ? legacyRatePercent : taxPoint * 100
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
    invoiceMultiplier: 1 + appliedRatePercent / 100,
    invoiceTaxApplied,
    priceSource,
    effectiveUnitPriceCny: useTaxIncludedPrice ? roundCny(record.taxIncludedPriceCny ?? 0) : roundCny(baseUnitPriceCny * (1 + appliedRatePercent / 100)),
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
    return sum + purchasePrice * normalizedQuoteQuantity(item.quantityPerSet) * setCount
  }, 0)
}

export function bundleDomesticFreight(items: BundleCalculationItem[], sets = 1) {
  const setCount = normalizedQuoteQuantity(sets)
  return items.reduce((sum, item) => sum + item.purchaseFreightPerUnit * normalizedQuoteQuantity(item.quantityPerSet) * setCount, 0)
}

export function bundleGoodsWeight(items: BundleCalculationItem[], sets = 1) {
  const setCount = normalizedQuoteQuantity(sets)
  return items.reduce((sum, item) => {
    const weightKg = item.customWeightKg != null && Number.isFinite(Number(item.customWeightKg))
      ? Math.max(0, Number(item.customWeightKg))
      : Math.max(0, Number(item.weightKg) || 0)
    return sum + weightKg * normalizedQuoteQuantity(item.quantityPerSet) * setCount
  }, 0)
}

export function resolveBundleProductCategory(selectedCategory: string, recordCategory: string, existingCategories: string[]) {
  if (selectedCategory) return selectedCategory
  return recordCategory && existingCategories.every(category => category === recordCategory) ? recordCategory : ''
}

export function singleActualWeight(input: SingleWeightInput, quantity = normalizedQuoteQuantity(input.quantity)) {
  const unitWeight = input.weightSource === 'manual' ? input.manualWeight : input.netWeight
  return Math.max(0, Number(unitWeight) || 0) * normalizedQuoteQuantity(quantity)
}

export function singleVolumeWeight(input: SingleWeightInput, quantity = normalizedQuoteQuantity(input.quantity), divisor = input.volumeDivisor) {
  if (!input.volumetricEnabled || input.packageLengthCm <= 0 || input.packageWidthCm <= 0 || input.packageHeightCm <= 0) return 0
  return input.packageLengthCm * input.packageWidthCm * input.packageHeightCm * normalizedQuoteQuantity(quantity) / Math.max(1, Number(divisor) || 8000)
}

export function singleChargeWeight(input: SingleWeightInput, quantity = normalizedQuoteQuantity(input.quantity)) {
  return Math.max(singleActualWeight(input, quantity), singleVolumeWeight(input, quantity))
}

export function singleShipmentDimensions(input: SingleWeightInput, quantity = normalizedQuoteQuantity(input.quantity)): ShipmentDimensions | undefined {
  if (!input.volumetricEnabled) return undefined
  return {
    lengthCm: Math.max(0, Number(input.packageLengthCm) || 0),
    widthCm: Math.max(0, Number(input.packageWidthCm) || 0),
    heightCm: Math.max(0, Number(input.packageHeightCm) || 0),
    volumeMultiplier: normalizedQuoteQuantity(quantity),
    volumeDivisor: Math.max(1, Number(input.volumeDivisor) || 8000),
    defaultVolumeDivisor: 8000,
  }
}

export function usdPriceFromCny(cny: number, usdCny: number) {
  const rate = Math.max(0.0001, Number(usdCny) || 0)
  return Math.round(Math.max(0, Number(cny) || 0) * 100) / 100 / rate
}

export function hasQuotationProduct(mode: 'single' | 'bundle', primarySku: string, bundleSkus: string[]) {
  if (mode === 'single') return Boolean(primarySku.trim())
  return new Set(bundleSkus.map(sku => sku.trim().toUpperCase()).filter(Boolean)).size >= 2
}
