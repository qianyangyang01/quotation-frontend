import Decimal from 'decimal.js'
import type { QuotationRecord } from './quotationRecords'

const amount = (value?: number) => typeof value === 'number' && Number.isFinite(value) && value >= 0 ? value : undefined
export const snapshotMoney = (value?: number) => value == null ? '未保存' : value.toFixed(2)

// Old single-product records saved the custom-quantity cost and freight samples.
// Match their quantities explicitly: freightCny/logisticsInput can describe a
// different quantity. Only recover when every usable saved route agrees.
function savedSingleFreight(record: QuotationRecord) {
  const direct = amount(record.domesticFreightPerUnitCny)
  if (record.domesticFreightPerUnitCny != null) return { value: direct, recovered: false }
  const purchase = amount(record.purchaseUnitPriceCny), quantity = record.customQuoteQuantity
  if (purchase == null || !quantity || !Number.isInteger(quantity) || quantity < 1) return { value: undefined, recovered: false }
  const candidates: Decimal[] = []
  for (const option of record.quoteOptions ?? []) {
    if (option.available === false || option.quoteCustomUsd == null) continue
    const cost = amount(option.totalCostCny)
    const samples = option.logisticsSamples?.filter(sample => sample.quantity === quantity) ?? []
    if (cost == null || samples.length !== 1) continue
    const freight = amount(samples[0]!.total ?? undefined)
    if (freight == null) continue
    const candidate = new Decimal(cost).minus(freight).div(quantity).minus(purchase).toDecimalPlaces(8)
    if (candidate.isNegative()) return { value: undefined, recovered: false }
    candidates.push(candidate)
  }
  const first = candidates[0]
  return first && candidates.every(value => value.eq(first))
    ? { value: first.toNumber(), recovered: true } : { value: undefined, recovered: false }
}

/** Display the purchase fields saved on this quotation; never fetch the current catalog. */
export function quotationProductCostSnapshot(record: QuotationRecord) {
  const bundle = record.quoteMode === 'bundle'
  const singleFreight = bundle ? undefined : savedSingleFreight(record)
  const items = bundle ? (record.bundleItems ?? []).map(item => ({
    sku: item.sku, name: item.name, count: item.quantityPerSet,
    base: amount(item.purchaseBaseUnitPriceCny), purchase: amount(item.purchaseUnitPriceCny),
    freight: amount(item.domesticFreightPerUnitCny), invoice: item.purchaseInvoiceType,
    rate: item.purchaseInvoiceRatePercent, taxApplied: item.purchaseInvoiceTaxApplied,
  })) : [{
    sku: record.primarySku, name: record.productSummary, count: 1,
    base: amount(record.purchaseBaseUnitPriceCny), purchase: amount(record.purchaseUnitPriceCny),
    freight: singleFreight?.value, invoice: record.purchaseInvoiceType,
    rate: record.purchaseInvoiceRatePercent, taxApplied: record.purchaseInvoiceTaxApplied,
  }]
  const sum = (key: 'purchase' | 'freight') => !items.length || items.some(item => item[key] == null)
    ? undefined : items.reduce((total, item) => total.plus(new Decimal(item[key]!).mul(item.count)), new Decimal(0)).toNumber()
  const purchase = sum('purchase'), freight = sum('freight')
  const quantities = [...new Set([1, 2, 3, ...(record.customQuoteQuantity ? [record.customQuoteQuantity] : []),
    ...(record.weightSnapshot?.quantities.map(row => row.quantity) ?? []),
    ...(record.customerQuote?.quantities ?? record.sheetQuote?.quantities ?? []),
    ...(record.systemQuantityQuotes?.quantities ?? []),
  ])].filter(quantity => quantity > 0).sort((a, b) => a - b)
  const rows = quantities.map(quantity => ({ quantity,
    purchase: purchase == null ? undefined : new Decimal(purchase).mul(quantity).toNumber(),
    freight: freight == null ? undefined : new Decimal(freight).mul(quantity).toNumber(),
    total: purchase == null || freight == null ? undefined : new Decimal(purchase).plus(freight).mul(quantity).toNumber(),
  }))
  return { items, rows, bundle, freightRecovered: singleFreight?.recovered === true, unit: bundle ? '套' : '件' }
}
