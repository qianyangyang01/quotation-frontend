import { decimal } from '@/services/quotationDecimal'

export type QuotationMode = 'single' | 'bundle' | 'womens-single' | 'womens-bundle' | 'freight-trial' | 'shipping-only'
export type ManualPricing = { costCny: number; weightGrams: number }
export type SavedQuotationMode = QuotationMode | 'fob'
export const quotationModeLabels: Record<SavedQuotationMode, string> = {
  'womens-single': '女装单品 SKU 报价', 'womens-bundle': '女装组合 SKU 报价',
  fob: 'FOB（批发）报价', single: '单品 SKU 报价', bundle: '组合 SKU 报价',
  'freight-trial': '报价试算', 'shipping-only': '仅代发货报价',
}
export function isWomensQuotation(mode: unknown) { return mode === 'womens-single' || mode === 'womens-bundle' }
export function isSingleQuotation(mode: unknown) { return mode === 'single' || mode === 'womens-single' }
export function isBundleQuotation(mode: unknown) { return mode === 'bundle' || mode === 'womens-bundle' }
// The workbench chooses product structure and customer pricing separately.
// Keep the saved modes so existing quotes and backend snapshots stay compatible.
export function quotationInputMode(mode: SavedQuotationMode) {
  return mode === 'womens-single' ? 'single' : mode === 'womens-bundle' ? 'bundle' : mode
}
export function quotationModeForCustomer(mode: QuotationMode, womens: boolean): QuotationMode {
  if (isSingleQuotation(mode)) return womens ? 'womens-single' : 'single'
  if (isBundleQuotation(mode)) return womens ? 'womens-bundle' : 'bundle'
  return mode
}
export function isManualQuotation(mode: string): mode is 'freight-trial' | 'shipping-only' {
  return mode === 'freight-trial' || mode === 'shipping-only'
}
export function normalizeQuotationMode(mode: unknown): QuotationMode {
  return mode !== 'fob' && typeof mode === 'string' && Object.prototype.hasOwnProperty.call(quotationModeLabels, mode) ? mode as QuotationMode : 'single'
}
// Reject blank, scientific notation, negative, non-finite and silently rounded input.
export function manualDecimal(input: unknown, places: number): number | null {
  if (typeof input !== 'number' && typeof input !== 'string') return null
  const text = String(input).trim()
  if (!/^\d+(\.\d+)?$/.test(text)) return null
  const value = decimal(text)
  return value.isFinite() && value.gte(0) && value.lte(1_000_000) && value.decimalPlaces() <= places ? value.toNumber() : null
}
export function manualPricingInput(mode: string, cost: unknown, grams: unknown): ManualPricing | null {
  const costCny = mode === 'shipping-only' ? 0 : manualDecimal(cost, 2)
  const weightGrams = manualDecimal(grams, 3)
  return isManualQuotation(mode) && costCny != null && weightGrams != null && weightGrams > 0 ? { costCny, weightGrams } : null
}
export function manualQuantity(pricing: ManualPricing, quantity: number) {
  if (!Number.isSafeInteger(quantity) || quantity < 1) throw new Error('报价数量必须为正整数')
  return { costCny: decimal(pricing.costCny).times(quantity).toNumber(), weightKg: decimal(pricing.weightGrams).times(quantity).div(1000).toNumber() }
}
