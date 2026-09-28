import { api } from '@/services/http'

export type SalesRow = { sku: string; sourceSku: string; sourceRow: number; months: number[]; total: number; monthlyTotal: number; salesDifference: number; productName?: string }
export type SalesProduct = {
  sku: string; quotationOwner: string | null; weightG: number | null; taxPoint: number | null; invoiceType: string | null
  purchasePriceCny: number | null; taxIncludedPriceCny: number | null; tier2PriceCny: number | null; tier3PriceCny: number | null
  minOrderQty: number | null; singleFreightCny: number | null; freeShipping?: string; dataSource: string
  catalogState: string; sourceSheet: string | null; sourceRow: number | null
}
export type SalesData = { source: { period: string; sourceFile: string; sourceSheet: string; sourceSha256: string; rows: SalesRow[]; monthlyOnlySkus: string[] }; products: SalesProduct[]; matchedAt: string }
export const reminderLabels = ['未匹配', '缺克重', '缺票点 / 待确认', '缺采购价', '缺1件运费', '缺报价人', '缺起订量', '待确认转正式', '已停用'] as const
export type Reminder = typeof reminderLabels[number]
const nonNegative = (value: unknown): value is number => typeof value === 'number' && Number.isFinite(value) && value >= 0
export function purchaseSalesReminders(product?: SalesProduct): Reminder[] {
  if (!product) return ['未匹配']
  const missing: Reminder[] = []
  if (!nonNegative(product.weightG) || product.weightG <= 0) missing.push('缺克重')
  if (!nonNegative(product.taxPoint) || product.taxPoint > 1 || product.invoiceType?.trim() === '待确认') missing.push('缺票点 / 待确认')
  const hasPrice = product.dataSource === 'legacy_2026'
    ? nonNegative(product.purchasePriceCny) && product.purchasePriceCny > 0
    : [product.purchasePriceCny, product.taxIncludedPriceCny, product.tier2PriceCny, product.tier3PriceCny].some(nonNegative)
  if (!hasPrice) missing.push('缺采购价')
  if (product.freeShipping !== '是' && !nonNegative(product.singleFreightCny)) missing.push('缺1件运费')
  if (!product.quotationOwner?.trim()) missing.push('缺报价人')
  if (!nonNegative(product.minOrderQty) || product.minOrderQty <= 0) missing.push('缺起订量')
  if (product.catalogState === 'disabled') missing.push('已停用')
  else if (product.catalogState === 'pending_template') missing.push('待确认转正式')
  return missing
}
export function matchPurchaseSales(data: SalesData) {
  const products = new Map(data.products.map(product => [product.sku, product]))
  return data.source.rows.map(row => {
    const product = products.get(row.sku)
    return { ...row, product, owner: product ? product.quotationOwner?.trim() || '未填写报价人' : '未匹配', reminders: purchaseSalesReminders(product) }
  })
}
export type MatchedSalesRow = ReturnType<typeof matchPurchaseSales>[number]
export function filterPurchaseSales(rows: MatchedSalesRow[], query: string, owner: string, reminders: Reminder[], onlyReminders: boolean, differenceOnly: boolean) {
  const text = query.trim().toUpperCase().replace(/\s+/g, '')
  return rows.filter(row => (!text || row.sku.includes(text)) && (!owner || row.owner === owner)
    && (!onlyReminders || row.reminders.length > 0) && (!differenceOnly || row.salesDifference !== 0)
    && reminders.every(reason => row.reminders.includes(reason)))
}
export const loadPurchaseSales = (signal?: AbortSignal) => api.get<SalesData>('/purchase-sales', { signal, cache: 'no-store' })
// Prevent spreadsheet formula evaluation in exported source strings.
const csvCell = (value: unknown) => '"' + String(value ?? '').replace(/^[=+@\-\t\r]/, match => "'" + match).replace(/"/g, '""') + '"'
export function salesCsv(rows: MatchedSalesRow[]) {
  const values: unknown[][] = [['主SKU', '采购报价人', '6月销量', '7月销量', '8月销量', '汇总表销量', '月度合计', '销量差异', '克重(g)', '票点', '采购价', '1件运费', '提醒', '销量汇总表行号', '采购来源表', '采购来源行', '商品名称（销量明细，仅供参考）']]
  rows.forEach(row => values.push([row.sourceSku, row.owner, ...row.months, row.total, row.monthlyTotal, row.salesDifference,
    row.product?.weightG, row.product?.taxPoint == null ? '' : `${row.product.taxPoint * 100}%`, row.product?.purchasePriceCny,
    row.product?.singleFreightCny, row.reminders.join('、'), row.sourceRow, row.product?.sourceSheet, row.product?.sourceRow, row.productName]))
  return '\uFEFF' + values.map(row => row.map(csvCell).join(',')).join('\r\n')
}
