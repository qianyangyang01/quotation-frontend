import type { PurchaseProductRecord } from './purchaseStore'
import type { QuotationRecord } from './quotationRecords'

export interface DashboardFilters {
  keyword: string
  startDate: string
  endDate: string
  country: string
  salesperson: string
  category: string
}

export interface DashboardSummary {
  quotationCount: number
  quotedSkuCount: number
  quoteUsd: number
  quoteCny: number
  expectedProfitCny: number
  weightedMarginPercent: number | null
}

export interface TrendPoint {
  key: string
  label: string
  quoteUsd: number
  quoteCny: number
  quotationCount: number
}

export interface SalespersonRankingRow {
  key: string
  name: string
  account: string
  quotationCount: number
  customerCount: number
  quoteUsd: number
  quoteCny: number
  marginPercent: number | null
  latestQuoteAt: string
}

export interface CategoryPerformanceRow {
  category: string
  skuCount: number
  quotedSkuCount: number
  averagePurchasePriceCny: number | null
  quotationCount: number
  quoteUsd: number
  quoteCny: number
  marginPercent: number | null
  skus: string[]
}

const round = (value: number) => Number(value.toFixed(2))

export function quotationSkus(record: QuotationRecord) {
  return [...new Set(record.primarySku.split(/[、,+\s]+/).map(value => value.trim().toUpperCase()).filter(Boolean))]
}

export function recordCountries(record: QuotationRecord) {
  return [...new Set([record.country, ...(record.quoteOptions || []).map(option => option.country)].map(value => value?.trim()).filter((value): value is string => Boolean(value && value !== '—')))]
}

export function resolveRecordCategory(record: QuotationRecord, purchaseBySku: Map<string, PurchaseProductRecord>) {
  const explicit = record.productCategory?.trim()
  if (explicit) return explicit
  for (const sku of quotationSkus(record)) {
    const category = purchaseBySku.get(sku)?.category.trim()
    if (category) return category
  }
  return '未分类'
}

export function filterQuotationRecords(records: QuotationRecord[], filters: DashboardFilters, purchases: PurchaseProductRecord[]) {
  const purchaseBySku = new Map(purchases.map(item => [item.sku.toUpperCase(), item]))
  const keyword = filters.keyword.trim().toLowerCase()
  return records.filter(record => {
    const category = resolveRecordCategory(record, purchaseBySku)
    const countries = recordCountries(record)
    const createdDate = record.createdAt.slice(0, 10)
    const searchable = [record.no, record.customerName, record.primarySku, record.productSummary, record.salespersonName, record.salespersonAccount, category, ...countries].join(' ').toLowerCase()
    return (!keyword || searchable.includes(keyword))
      && (!filters.startDate || createdDate >= filters.startDate)
      && (!filters.endDate || createdDate <= filters.endDate)
      && (!filters.country || countries.includes(filters.country))
      && (!filters.salesperson || record.salespersonName === filters.salesperson)
      && (!filters.category || category === filters.category)
  })
}

export function weightedMarginPercent(records: QuotationRecord[]) {
  const quoteCny = records.reduce((sum, record) => sum + record.systemQuoteCny, 0)
  if (!(quoteCny > 0)) return null
  const profit = records.reduce((sum, record) => sum + record.systemQuoteCny - record.totalCostCny, 0)
  return round(profit / quoteCny * 100)
}

export function buildDashboardSummary(records: QuotationRecord[]): DashboardSummary {
  return {
    quotationCount: records.length,
    quotedSkuCount: new Set(records.flatMap(quotationSkus)).size,
    quoteUsd: round(records.reduce((sum, record) => sum + record.systemQuoteUsd, 0)),
    quoteCny: round(records.reduce((sum, record) => sum + record.systemQuoteCny, 0)),
    expectedProfitCny: round(records.reduce((sum, record) => sum + record.systemQuoteCny - record.totalCostCny, 0)),
    weightedMarginPercent: weightedMarginPercent(records),
  }
}

export function buildSalespersonRanking(records: QuotationRecord[]): SalespersonRankingRow[] {
  const groups = new Map<string, QuotationRecord[]>()
  for (const record of records) {
    const key = record.salespersonAccount || record.salespersonName || '—'
    const rows = groups.get(key)
    if (rows) rows.push(record)
    else groups.set(key, [record])
  }
  return [...groups.entries()].map(([key, rows]) => ({
    key,
    name: rows[0]?.salespersonName || '未指定业务员',
    account: rows[0]?.salespersonAccount || '—',
    quotationCount: rows.length,
    customerCount: new Set(rows.map(row => row.customerName.trim()).filter(Boolean)).size,
    quoteUsd: round(rows.reduce((sum, row) => sum + row.systemQuoteUsd, 0)),
    quoteCny: round(rows.reduce((sum, row) => sum + row.systemQuoteCny, 0)),
    marginPercent: weightedMarginPercent(rows),
    latestQuoteAt: rows.reduce((latest, row) => row.createdAt > latest ? row.createdAt : latest, ''),
  })).sort((a, b) => b.quoteCny - a.quoteCny || b.quotationCount - a.quotationCount || a.name.localeCompare(b.name, 'zh-CN'))
}

export function buildTrend(records: QuotationRecord[]): TrendPoint[] {
  if (!records.length) return []
  const timestamps = records.map(record => new Date(record.createdAt).getTime()).filter(Number.isFinite)
  const spanDays = timestamps.length ? (Math.max(...timestamps) - Math.min(...timestamps)) / 86_400_000 : 0
  const groupByDay = spanDays <= 45
  const groups = new Map<string, QuotationRecord[]>()
  for (const record of records) {
    const key = groupByDay ? record.createdAt.slice(0, 10) : record.createdAt.slice(0, 7)
    const rows = groups.get(key)
    if (rows) rows.push(record)
    else groups.set(key, [record])
  }
  return [...groups.entries()].sort(([a], [b]) => a.localeCompare(b)).map(([key, rows]) => ({
    key,
    label: groupByDay ? key.slice(5) : key,
    quoteUsd: round(rows.reduce((sum, row) => sum + row.systemQuoteUsd, 0)),
    quoteCny: round(rows.reduce((sum, row) => sum + row.systemQuoteCny, 0)),
    quotationCount: rows.length,
  }))
}

export function buildCategoryPerformance(records: QuotationRecord[], purchases: PurchaseProductRecord[]): CategoryPerformanceRow[] {
  const purchaseBySku = new Map(purchases.map(item => [item.sku.toUpperCase(), item]))
  const catalog = new Map<string, PurchaseProductRecord[]>()
  for (const product of purchases) {
    const category = product.category.trim() || '未分类'
    const rows = catalog.get(category)
    if (rows) rows.push(product)
    else catalog.set(category, [product])
  }
  const quotationGroups = new Map<string, QuotationRecord[]>()
  for (const record of records) {
    const category = resolveRecordCategory(record, purchaseBySku)
    const rows = quotationGroups.get(category)
    if (rows) rows.push(record)
    else quotationGroups.set(category, [record])
  }
  const categories = new Set([...catalog.keys(), ...quotationGroups.keys()])
  return [...categories].map(category => {
    const products = catalog.get(category) || []
    const rows = quotationGroups.get(category) || []
    const purchasePrices = products.map(item => item.purchasePriceCny).filter((value): value is number => value != null)
    const quotedSkus = new Set(rows.flatMap(quotationSkus))
    return {
      category,
      skuCount: products.length,
      quotedSkuCount: quotedSkus.size,
      averagePurchasePriceCny: purchasePrices.length ? round(purchasePrices.reduce((sum, value) => sum + value, 0) / purchasePrices.length) : null,
      quotationCount: rows.length,
      quoteUsd: round(rows.reduce((sum, row) => sum + row.systemQuoteUsd, 0)),
      quoteCny: round(rows.reduce((sum, row) => sum + row.systemQuoteCny, 0)),
      marginPercent: weightedMarginPercent(rows),
      skus: [...new Set(products.map(item => item.sku.toUpperCase()))],
    }
  }).sort((a, b) => b.quoteCny - a.quoteCny || b.quotationCount - a.quotationCount || a.category.localeCompare(b.category, 'zh-CN'))
}

function csvCell(value: string | number) {
  const text = String(value)
  return /[",\r\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text
}

export function quotationDetailsCsv(records: QuotationRecord[], purchases: PurchaseProductRecord[]) {
  const purchaseBySku = new Map(purchases.map(item => [item.sku.toUpperCase(), item]))
  const header = ['报价编号', '报价时间', '客户名称', '业务员', '业务员账号', '国家', '产品品类', '主SKU', '成本(RMB)', '报价(USD)', '报价(RMB)', '预计毛利(RMB)', '毛利率']
  const rows = records.map(record => {
    const margin = record.systemQuoteCny > 0 ? (record.systemQuoteCny - record.totalCostCny) / record.systemQuoteCny * 100 : null
    return [record.no, record.createdAt, record.customerName, record.salespersonName, record.salespersonAccount, recordCountries(record).join('、'), resolveRecordCategory(record, purchaseBySku), record.primarySku, record.totalCostCny.toFixed(2), record.systemQuoteUsd.toFixed(2), record.systemQuoteCny.toFixed(2), (record.systemQuoteCny - record.totalCostCny).toFixed(2), margin == null ? '' : `${margin.toFixed(2)}%`]
  })
  return `\uFEFF${[header, ...rows].map(row => row.map(csvCell).join(',')).join('\r\n')}`
}
