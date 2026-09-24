import { savedSystemPrice, recordQuoteSheetVersion } from './customerQuotePrices'
import { customerGradeDisplayLabel } from './financeChannelPolicies'
import type { QuotationRecord } from './quotationRecords'
import { quotationRecordCopyCountry } from './quotationRecordCopyCountry'
import Decimal from 'decimal.js'
import { quotationProductCostSnapshot, snapshotMoney } from './quotationProductCostSnapshot'

const safe = (value: unknown) => {
  const text = String(value ?? '').replace(/[\t\r\n]+/g, ' ')
  return /^[=+@-]/.test(text.trimStart()) ? `'${text}` : text
}
const escapeHtml = (value: string) => value.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;')

const savedAmount = (value: unknown) => typeof value === 'number' && Number.isFinite(value) && value >= 0 ? value : undefined
const grams = (value: unknown) => {
  const kg = savedAmount(value)
  return kg == null ? '未保存' : new Decimal(kg).mul(1000).toFixed()
}

/** Only the requested summary fields; all values use saved, matching-quantity data. */
function costWeightSummary(record: QuotationRecord, unit: string) {
  const options = record.quoteOptions ?? []
  const matching = options.filter(option => option.country === record.country && option.carrier === record.carrier && option.channel === record.channel && option.rule === record.rule)
  const selected = options.find(option => option.isPrimary) ?? (matching.length === 1 ? matching[0] : options.length === 1 ? options[0] : undefined)
  const primary = selected?.available === false ? undefined : selected
  const samples = primary?.logisticsSamples?.filter(sample => sample.quantity === 1) ?? []
  const cost = quotationProductCostSnapshot(record).rows.find(row => row.quantity === 1)
  const weight = record.weightSnapshot?.quantities.find(row => row.quantity === 1)
  const input = primary?.logisticsInput?.quantity === 1 ? primary.logisticsInput : undefined
  const finalWeight = weight ? weight.weightKg : input?.weightKg ?? (samples.length === 1 ? samples[0]?.input?.weightKg : undefined)
  const weightDetails = weight
    ? `基础 ${grams(weight.baseWeightKg)}g + 普通包材 ${grams(weight.standardPackagingWeightKg)}g + 特殊包装 ${grams(weight.specialPackagingWeightKg)}g`
    : `基础 ${grams(input?.baseWeightKg)}g + 包材合计 ${grams(input?.packagingWeightKg)}g（普通包材、特殊包装明细未保存）`
  return {
    rows: [
      [`总成本价（CNY/1${unit}）`, snapshotMoney(cost?.total), `商品成本 ${snapshotMoney(cost?.purchase)} + 国内运费 ${snapshotMoney(cost?.freight)}（CNY，不含国际运费）`],
      [`含包材重量（g/1${unit}）`, grams(finalWeight), weightDetails],
    ],
    note: record.weightSnapshot
      ? '普通包材：每件商品每 50g 加 1g，不足 50g 按 50g 计算；特殊包装整票只加一次，不随件数或套数增加，特殊包装本身不再计算普通包材。'
      : '包材规则及拆分明细未保存；缺失项显示“未保存”，不按当前规则回算。',
  }
}

/** Quotation table with the explicitly requested saved cost and weight summary. */
export function quotationRecordQuoteOnlyLayout(record: QuotationRecord, version: 'full' | 'visible' = 'full'): { text: string; html: string } {
  const snapshot = record.customerQuote ?? record.sheetQuote
  const options = recordQuoteSheetVersion(record, version).quoteOptions ?? []
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
  const summary = costWeightSummary(record, unit)
  const sku = record.quoteMode === 'bundle' && record.bundleItems?.length ? record.bundleItems.map(item => `${item.sku} × ${item.quantityPerSet}`).join(' + ') : record.primarySku
  const header = ['国家', '物流渠道', ...quantities.map(q => q ? `${q}${unit}` : '自定义（数量未保存）'), '预计时效']
  const rows: { kind: 'metadata' | 'header' | 'route' | 'note'; cells: string[] }[] = [
    { kind: 'metadata', cells: ['报价编号', record.no, '客户', record.customerName, '客户等级', customerGradeDisplayLabel(record.customerGrade)] },
    { kind: 'metadata', cells: ['SKU', sku, '商品', record.productSummary, '币种', 'USD（美元/单）'] },
    { kind: 'metadata', cells: ['创建时间', record.createdAt] },
    ...summary.rows.map(cells => ({ kind: 'metadata' as const, cells })),
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
  rows.push({ kind: 'note', cells: [summary.note] })
  const columnCount = header.length
  const htmlRows = rows.flatMap(row => row.kind === 'metadata' && columnCount < row.cells.length
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
