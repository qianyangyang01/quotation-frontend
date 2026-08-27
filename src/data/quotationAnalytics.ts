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
}

export interface SalespersonRankingRow {
  key: string
  name: string
  account: string
  customerCount: number
  quotedProductCount: number
  wonProductCount: number
  conversionRate: number | null
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
  skus: string[]
}

const round = (value: number) => Number(value.toFixed(2))
const invalidSkuValues = new Set(['—', '-', '--', '暂无数据', '无', 'N/A', 'NA'])

export function quotationSkus(record: QuotationRecord) {
  return [...new Set(record.primarySku.split(/[、,+\s]+/)
    .map(value => value.trim().toUpperCase())
    .filter(value => Boolean(value) && !invalidSkuValues.has(value)))]
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

export function buildDashboardSummary(records: QuotationRecord[]): DashboardSummary {
  return {
    quotationCount: records.length,
    quotedSkuCount: new Set(records.flatMap(quotationSkus)).size,
    quoteUsd: round(records.reduce((sum, record) => sum + record.systemQuoteUsd, 0)),
    quoteCny: round(records.reduce((sum, record) => sum + record.systemQuoteCny, 0)),
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
  return [...groups.entries()].map(([key, rows]) => {
    const quotedProductCount = rows.reduce((sum, row) => sum + quotationSkus(row).length, 0)
    const wonProductCount = rows.reduce((sum, row) => sum + (row.status === 'won' ? quotationSkus(row).length : 0), 0)
    return {
      key,
      name: rows[0]?.salespersonName || '未指定业务员',
      account: rows[0]?.salespersonAccount || '—',
      customerCount: new Set(rows.map(row => row.customerName.trim()).filter(Boolean)).size,
      quotedProductCount,
      wonProductCount,
      conversionRate: quotedProductCount > 0 ? round(wonProductCount / quotedProductCount * 100) : null,
      latestQuoteAt: rows.reduce((latest, row) => row.createdAt > latest ? row.createdAt : latest, ''),
    }
  }).sort((a, b) => {
    if (a.conversionRate == null && b.conversionRate != null) return 1
    if (a.conversionRate != null && b.conversionRate == null) return -1
    return (b.conversionRate ?? 0) - (a.conversionRate ?? 0)
      || b.wonProductCount - a.wonProductCount
      || b.quotedProductCount - a.quotedProductCount
      || b.customerCount - a.customerCount
      || a.name.localeCompare(b.name, 'zh-CN')
  })
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
  const header = ['报价编号', '报价时间', '客户名称', '业务员', '业务员账号', '国家', '产品品类', '主SKU', '成本(RMB)', '报价(USD)', '报价(RMB)']
  const rows = records.map(record => [record.no, record.createdAt, record.customerName, record.salespersonName, record.salespersonAccount, recordCountries(record).join('、'), resolveRecordCategory(record, purchaseBySku), record.primarySku, record.totalCostCny.toFixed(2), record.systemQuoteUsd.toFixed(2), record.systemQuoteCny.toFixed(2)])
  return `\uFEFF${[header, ...rows].map(row => row.map(csvCell).join(',')).join('\r\n')}`
}
