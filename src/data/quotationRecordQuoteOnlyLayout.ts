import { savedSystemPrice } from './customerQuotePrices'
import type { QuotationRecord } from './quotationRecords'
import { quotationRecordCopyCountry } from './quotationRecordCopyCountry'

const safe = (value: unknown) => {
  const text = String(value ?? '').replace(/[\t\r\n]+/g, ' ')
  return /^[=+@-]/.test(text.trimStart()) ? `'${text}` : text
}
const escapeHtml = (value: string) => value.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;')

/** Customer quotation allowlist. No procurement, margin, commission or cost fields. */
export function quotationRecordQuoteOnlyLayout(record: QuotationRecord): { text: string; html: string } {
  const snapshot = record.customerQuote ?? record.sheetQuote
  const options = record.quoteOptions ?? []
  const price = (option: NonNullable<QuotationRecord['quoteOptions']>[number], quantity: number) => {
    const index = snapshot?.quantities.indexOf(quantity) ?? -1
    return snapshot ? index >= 0 ? snapshot.rows.find(row => row.optionId === option.id)?.prices[index] : null : savedSystemPrice(record, option, quantity)
  }
  const quantities = [...new Set([
    1, 2, 3,
    ...(record.customQuoteQuantity ? [record.customQuoteQuantity] : []),
    ...(record.systemQuantityQuotes?.quantities ?? []), ...(snapshot?.quantities ?? []),
    ...options.flatMap(option => (option.logisticsSamples ?? []).map(sample => sample.quantity)),
    ...(!record.customQuoteQuantity && options.some(option => option.quoteCustomUsd != null) ? [0] : []),
  ])].filter(q => Number.isSafeInteger(q) && q >= 0 && options.some(option => {
    const value = price(option, q)
    return value != null && Number.isFinite(value)
  })).sort((a, b) => (a || Infinity) - (b || Infinity))
  const unit = record.quoteMode === 'bundle' ? '套' : '件'
  const sku = record.quoteMode === 'bundle' && record.bundleItems?.length ? record.bundleItems.map(item => `${item.sku} × ${item.quantityPerSet}`).join(' + ') : record.primarySku
  const header = ['国家', '物流渠道', ...quantities.map(q => q ? `${q}${unit}` : '自定义（数量未保存）'), '预计时效']
  const rows: { kind: 'metadata' | 'header' | 'route' | 'note'; cells: string[] }[] = [
    { kind: 'metadata', cells: ['报价编号', record.no, '客户', record.customerName, '创建时间', record.createdAt] },
    { kind: 'metadata', cells: ['SKU', sku, '商品', record.productSummary, '币种', 'USD（美元/单）'] },
    { kind: 'header', cells: header },
  ]
  for (const option of options) {
    const country = quotationRecordCopyCountry(option.country, option.quoteRegion)
    const prices = quantities.map(quantity => {
      const value = price(option, quantity)
      return value == null || !Number.isFinite(value) ? '未报价' : value.toFixed(2)
    })
    rows.push({ kind: 'route', cells: [country, [option.carrier, option.channel].filter(Boolean).join('｜'), ...prices, option.eta || '未保存'] })
  }
  if (!options.length) rows.push({ kind: 'note', cells: ['未保存国家与物流渠道报价'] })
  rows.push({ kind: 'note', cells: ['各数量价格为整单报价；未报价项不补算。'] })
  const columnCount = header.length
  const htmlRows = rows.flatMap(row => row.kind === 'metadata' && columnCount < 6
    ? [0, 2, 4].map(i => ({ ...row, cells: row.cells.slice(i, i + 2) })) : [row]).map(row => `<tr>${row.cells.map((value, i) => {
    const span = row.kind === 'metadata' && i === row.cells.length - 1 ? columnCount - row.cells.length + 1 : row.kind === 'note' ? columnCount : 1
    const bg = row.kind === 'header' ? '#c6e0b4' : row.kind === 'route' && i === 1 ? '#92d050' : '#ffffff'
    return `<td colspan="${span}" style="border:1px solid #555;padding:5px 8px;background:${bg};font-size:${row.kind === 'header' ? '18' : '12'}px;font-weight:${row.kind === 'header' ? '700' : '400'};text-align:${row.kind === 'metadata' || row.kind === 'note' ? 'left' : 'center'};vertical-align:middle;white-space:normal;word-wrap:break-word;mso-number-format:'\\@'">${escapeHtml(safe(value))}</td>`
  }).join('')}</tr>`).join('')
  const widths = [170, 300, ...quantities.map(() => 100), 140]
  return {
    text: rows.map(row => row.cells.map(safe).join('\t')).join('\n'),
    html: `<html><head><meta charset="utf-8"></head><body><!--StartFragment--><table aria-label="报价单" style="border-collapse:collapse;table-layout:fixed;width:${widths.reduce((a, b) => a + b, 0)}px;font-family:Arial,'Microsoft YaHei',sans-serif;color:#000"><colgroup>${widths.map(width => `<col width="${width}" style="width:${width}px">`).join('')}</colgroup>${htmlRows}</table><!--EndFragment--></body></html>`,
  }
}
