import type { PurchaseProductRecord } from './purchaseStore'
import { decimal } from '@/services/quotationDecimal'

function finiteNumber(value: unknown): number | null {
  if (value == null || String(value).trim() === '') return null
  const number = Number(value)
  return Number.isFinite(number) ? number : null
}

// Never infer an untaxed amount from a stored included price.
export function legacyUntaxedUnitPrice(record: PurchaseProductRecord): number | null {
  const source = finiteNumber(record.sourceQuotedPriceCny)
  if (source != null && source > 0) return source
  return record.purchasePriceBasis === 'quoted' && record.purchasePriceCny != null && record.purchasePriceCny > 0
    ? record.purchasePriceCny : null
}

export function legacyAutoTaxPrice(unitPrice: unknown, taxPoint: unknown): number | null {
  const price = finiteNumber(unitPrice), rate = finiteNumber(taxPoint)
  if (price == null || price <= 0 || rate == null || rate < 0 || rate > 1) return null
  return decimal(price).times(decimal(rate).plus(1)).toDecimalPlaces(2).toNumber()
}

export function withLegacyAutoTax(record: PurchaseProductRecord, unitPrice: unknown): PurchaseProductRecord {
  const included = legacyAutoTaxPrice(unitPrice, record.taxPoint)
  if (included == null) throw new Error('请填写大于0的未税采购单价和0～1之间的票点（0票点请明确填写0）')
  // Keep the effective-price storage contract used by catalog/analytics consumers.
  // The editor's untaxed input is sourceQuotedPriceCny, never purchasePriceCny.
  return { ...record, legacyPriceMode: 'auto_tax', sourceQuotedPriceCny: Number(unitPrice),
    purchasePriceBasis: 'tax_included', purchasePriceCny: included, taxIncludedPriceCny: included }
}
