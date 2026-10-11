import { formatQuoteDate, type CustomerQuoteSheet } from '@/data/customerQuoteSheet'
import { ApiError } from './http'
import { fobQuantityQuote, fobSheetRows, fobTierQuotes, type FobQuoteProduct, type FobSmallOrderPolicy } from './fobQuotation'
import type { FobQuotationSnapshot } from './fobQuotationRecords'

export type FobBatchMode = 'quantity' | 'tiers'
export interface FobBatchLine { id: number; sku: string; quantity: string; formatError: string; selected: boolean; loading?: boolean; error?: string; product?: FobQuoteProduct }
export function parseFobBatch(text: string): FobBatchLine[] {
  if (text.length > 50000) throw new Error('粘贴内容过长，请每次最多粘贴100行')
  const lines = text.replace(/^\uFEFF/, '').split(/\r?\n/).map((raw, i) => ({ raw: raw.trim(), id: i + 1 })).filter(l => l.raw)
  if (!lines.length) throw new Error('请先粘贴SKU清单')
  if (lines.length > 100) throw new Error(`共${lines.length}行，超过100行上限；请分批粘贴，未截断任何数据`)
  return lines.map(({ raw, id }) => {
    const cells = raw.split(/[\t,，;；\s]+/)
    return { id, sku: cells[0]!.toUpperCase(), quantity: cells[1] || '', selected: true, formatError: cells.length > 2 ? '无法识别格式：每行只填写SKU和数量，用空格、逗号或Tab分隔' : '' }
  })
}
export function fobBatchIssue(line: FobBatchLine, lines: FobBatchLine[], mode: FobBatchMode, rate: number, policy: FobSmallOrderPolicy | null): string {
  if (line.formatError) return line.formatError
  if (!/^[A-Z0-9][A-Z0-9._/-]{0,95}$/.test(line.sku)) return 'SKU格式无效'
  if (line.loading) return '正在查询'
  if (line.error) return line.error
  if (!line.product) return '待查询'
  if (lines.some(other => other.id < line.id && other.selected && other.sku === line.sku && (mode === 'tiers' || Number(other.quantity) === Number(line.quantity)))) return '重复行：相同SKU和报价范围已存在，请取消重复行或修改数量'
  if (mode === 'quantity' && !line.quantity.trim()) return '请填写报价数量'
  try {
    if (mode === 'tiers') fobSheetRows(line.product, rate, policy)
    else fobQuantityQuote(line.product, fobTierQuotes(line.product, rate), line.quantity, rate, policy)
  } catch (e) { return e instanceof Error ? e.message : '资料不完整，暂不能报价' }
  return ''
}
export function fobBatchLookupError(e: unknown) {
  if (e instanceof ApiError) {
    if (e.status === 404) return '未找到SKU，请核对编号或采购来源'
    if (e.status === 401 || e.status === 403) return '登录已失效或无查询权限，请重新登录或联系管理员'
    if (e.status >= 500 || e.status === 429) return '查询失败，服务繁忙，请重试'
    return e.message
  }
  if (e instanceof Error && ['TimeoutError', 'AbortError', 'TypeError'].includes(e.name)) return '查询失败或超时，请重试（不代表SKU不存在）'
  return e instanceof Error ? e.message : '查询失败，请重试'
}
export function fobBatchQuantity(product: FobQuoteProduct, quantity: string, mode: FobBatchMode) {
  if (mode === 'quantity') return Number(quantity)
  const multiple = product.parsed.orderMultiple
  for (const tier of product.parsed.priceTiers) {
    const first = Math.ceil(Math.max(product.parsed.minOrderQty, tier.minQty) / multiple) * multiple
    if (tier.maxQty == null || first <= tier.maxQty) return first
  }
  throw new Error('采购阶梯中没有满足下单倍数的数量')
}
export function fobBatchSnapshot(lines: FobBatchLine[], mode: FobBatchMode, rate: number, policy: FobSmallOrderPolicy, metadata: { title: string; agent: string; date: string; whatsapp: string }): FobQuotationSnapshot {
  const selected = lines.filter(l => l.selected)
  if (!selected.length) throw new Error('请至少选择一个有效商品')
  if (selected.length > 100) throw new Error('每份批量报价最多100行商品')
  const items = selected.map(line => {
    const issue = fobBatchIssue(line, selected, mode, rate, policy)
    if (issue) throw new Error(`第${line.id}行 ${line.sku}：${issue}`)
    const product = line.product!, quantity = fobBatchQuantity(product, line.quantity, mode)
    const ranges = mode === 'tiers' ? fobSheetRows(product, rate, policy) : (() => {
      const q = fobQuantityQuote(product, fobTierQuotes(product, rate), String(quantity), rate, policy)
      return [{ minQty: quantity, maxQty: quantity, unit: q.row.unit, declaredUsd: q.declaredUsd, undeclaredUsd: q.undeclaredUsd }]
    })()
    const sheet: CustomerQuoteSheet = { ...metadata, date: formatQuoteDate(metadata.date), columnOrder: ['number', 'sku', 'prices'],
      quantityLabels: ['With declaration', 'Without declaration'], priceGroupLabel: 'FOB Final Unit Price (USD)', showQuantityRange: true,
      hiddenColumns: ['country', 'provider', 'shippingTime', 'processingTime'], issues: [],
      notes: ['Prices apply to the quantities shown. Applicable small-order adjustments are included.', 'FOB unit prices in USD. International shipping is not included.',
        ...selected.filter(l => l.product!.parsed.orderMultiple > 1).map(l => `${l.sku}: Order in multiples of ${l.product!.parsed.orderMultiple}.`)],
      rows: ranges.map((r, i) => ({ key: `${product.sku}-${i}`, number: i + 1, sku: product.sku,
        quantityRange: `${r.maxQty == null ? `${r.minQty}+` : r.minQty === r.maxQty ? r.minQty : `${r.minQty}–${r.maxQty}`} ${r.unit === '件' ? 'pcs' : r.unit}`,
        prices: [Number(r.declaredUsd), Number(r.undeclaredUsd)], country: '', provider: '', shippingTime: '', sourceDescription: '' })) }
    if (!sheet.date) throw new Error('请填写有效报价日期')
    // Group order-multiple notes into one note so the existing sheet contract stays bounded.
    const notes = sheet.notes!
    const grouped = notes.slice(0, 2)
    for (const note of notes.slice(2)) {
      if (grouped.length > 2 && grouped[grouped.length - 1]!.length + note.length < 2900) grouped[grouped.length - 1] += ' ' + note
      else grouped.push(note)
    }
    sheet.notes = grouped
    return { schemaVersion: 1 as const, product, quantity, rate, policy, displayMode: mode, sheet }
  })
  const rows = items.flatMap((item, index) => item.sheet.rows.map(row => ({ ...row, key: `${index}:${row.key}` }))).map((row, i) => ({ ...row, number: i + 1 }))
  if (rows.length > 2000) throw new Error('报价阶梯超过2000行，请减少商品后分批生成')
  return { ...items[0]!, schemaVersion: 2, items, sheet: { ...items[0]!.sheet, fobBatch: true, rows } }
}
