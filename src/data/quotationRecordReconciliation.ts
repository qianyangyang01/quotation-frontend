import Decimal from 'decimal.js'
import { financeReviewLabel } from './quotationRecords'
import type { QuotationRecord, QuotationRecordQuoteOption } from './quotationRecords'
import { savedSystemPrice } from './customerQuotePrices'
import { quoteCnyFromUsd } from '@/services/quotationMoney'

const missing = '未保存'
const money = (value: number | null | undefined) => value == null || !Number.isFinite(value) ? missing : value.toFixed(2)
// Keep pasted Excel cells rectangular and treat user-entered names as text.
const cell = (value: unknown) => {
  const text = String(value ?? missing).replace(/[\t\r\n]+/g, ' ')
  return /^[=+@-]/.test(text.trimStart()) ? `'${text}` : text
}
const line = (values: unknown[]) => values.map(cell).join('\t')

function taxDescription(option: QuotationRecordQuoteOption) {
  if (option.taxLabel) return option.taxLabel
  if (option.taxFeeMode === 'exempt' || option.taxIncluded === true) return '已含税 / 免税'
  if (option.taxFeeMode === 'no-tax') return '无关税'
  if (option.taxConfigured === false || option.taxFeeMode === 'missing') return '税费未配置'
  const modes = { 'fixed-order': '按单固定税费', 'per-item': '按件税费', 'weight-eur': '按重量欧元税费', 'weight-order': '按整单重量税费' }
  return option.taxFeeMode && option.taxFeeMode in modes ? modes[option.taxFeeMode as keyof typeof modes] : missing
}
function savedTax(option: QuotationRecordQuoteOption, quantity: number, custom?: number) {
  // Never apply today's tax rules to an old quotation.
  const calculation = option.taxCalculations?.[String(quantity)]
  if (calculation) return money(calculation.taxUsd)
  const value = quantity === 1 ? option.tax1Usd : quantity === 2 ? option.tax2Usd : quantity === 3 ? option.tax3Usd : quantity === custom ? option.taxCustomUsd : undefined
  if (value != null) return money(value)
  if (option.taxFeeMode === 'no-tax' || option.taxFeeMode === 'exempt' || option.taxIncluded === true) return '0.00'
  return missing
}
function surchargeDescription(option: QuotationRecordQuoteOption) {
  if (option.surchargeLabel) return option.surchargeLabel
  if (option.surchargeExempt) return '附加费豁免'
  if (option.surchargeEnabled === false) return '无附加费'
  return option.surchargeConfigured === false ? '附加费未配置' : missing
}

// Read historical snapshots only. Never apply today's packaging rules to old quotes.
export function savedFinalWeightKg(record: QuotationRecord, option: QuotationRecordQuoteOption, quantity: number): number | undefined {
  const value = record.weightSnapshot?.quantities.find(row => row.quantity === quantity)?.weightKg
    ?? option.logisticsSamples?.find(sample => sample.quantity === quantity)?.input?.weightKg
    ?? (option.logisticsInput?.quantity === quantity ? option.logisticsInput.weightKg : undefined)
  return typeof value === 'number' && Number.isFinite(value) && value > 0 ? value : undefined
}
const grams = (value?: number) => typeof value === 'number' && Number.isFinite(value) && value > 0 ? new Decimal(value).mul(1000).toFixed() : missing

/** Internal reconciliation export: saved fields only, independent of the customer-facing sheet. */
export function quotationRecordReconciliationTsv(record: QuotationRecord): string {
  const snapshot = record.customerQuote ?? record.sheetQuote
  const quantities = [...new Set([1, 2, 3, ...(record.customQuoteQuantity ? [record.customQuoteQuantity] : []),
    ...(record.systemQuantityQuotes?.quantities ?? []), ...(snapshot?.quantities ?? []),
    ...(!record.customQuoteQuantity && record.quoteOptions?.some(option => option.quoteCustomUsd != null) ? [0] : [])])]
  const unit = record.quoteMode === 'bundle' ? '套' : '件'
  const sku = record.quoteMode === 'bundle' && record.bundleItems?.length ? record.bundleItems.map(item => item.sku).join('+') : record.primarySku
  const cny = (usd: number | null | undefined) => usd == null || !Number.isFinite(usd) || !(record.exchangeRate > 0) ? missing : money(quoteCnyFromUsd(usd, record.exchangeRate))
  const lines = [
    line(['报价记录对账明细', '使用本条记录已保存的数据；人民币按该记录汇率换算；未保存的字段不补算']),
    line(['报价编号', record.no, '客户', record.customerName, '业务员', record.salespersonName, '账号', record.salespersonAccount]),
    line(['SKU', sku, '商品', record.productSummary, '报价类型', record.quoteMode === 'bundle' ? '组合报价' : '单品报价', '物流属性', record.logisticsAttribute]),
    line(['客户等级', record.customerGrade, '汇率（CNY/USD）', record.exchangeRate > 0 ? record.exchangeRate : missing, '佣金阈值', record.commissionThreshold ?? 1, '公司操作费（USD/单）', money(record.customerOperation?.feeUsd)]),
    line(['报价模式', record.matrixMode === 'template' ? '模板报价' : record.matrixMode === 'specified' ? '指定国家与渠道' : '常用国家', '模板', record.quotationTemplateName, '创建时间', record.createdAt, '修改时间', record.updatedAt]),
    line(['财务审核', financeReviewLabel(record.financeReviewStatus), '审核人', record.financeReviewedBy, '审核时间', record.financeReviewedAt]),
    line(['处理状态', record.status === 'won' ? '已成交' : record.status === 'lost' ? '未成交' : record.quoteConfirmed ? '已处理' : '待处理', '备注', record.note || '', '成交日期', record.closedAt]),
    line(['采购原价（CNY/件）', money(record.purchaseBaseUnitPriceCny), '计入采购价（CNY/件）', money(record.purchaseUnitPriceCny), '采购发票', record.purchaseInvoiceType, '采购票点（%）', record.purchaseInvoiceRatePercent]),
    line(['首选系统价（USD）', money(record.systemQuoteUsd), '首选系统价（CNY快照）', money(record.systemQuoteCny), '首选综合成本（CNY）', money(record.totalCostCny)]),
    line(['成交价（USD）', money(record.actualQuoteUsd), '成交价（CNY快照）', money(record.actualQuoteCny), '成交数量', record.dealQuantity, '成交方案', record.dealOptionLabel]),
    '',
    line(['序号', '报价编号', 'SKU', '国家', '国家代码', '区域', '物流商', '渠道', '计费规则', '渠道编码', '预计时效', '首选', '可用状态', '关税说明', '税率（%）', '附加费说明', '附加费（USD/单）', '计费重量快照（kg）', '最终含包材重量快照（g）', '重量快照对应数量', '物流运费快照（CNY）', `${record.customQuoteQuantity ? `${record.customQuoteQuantity}${unit}` : '自定义档'}综合成本（CNY）`,
      ...quantities.flatMap(q => {
        const label = q ? `${q}${unit}` : '自定义（数量未保存）'
        return [`${label}最终含包材重量（g）`, `${label}系统价（USD）`, `${label}系统价（CNY）`, `${label}客户价（USD）`, `${label}客户价（CNY）`, `${label}关税（USD）`]
      })]),
  ]
  for (const [index, option] of (record.quoteOptions ?? []).entries()) {
    const customerRow = snapshot?.rows.find(row => row.optionId === option.id)
    lines.push(line([index + 1, record.no, sku, option.country, option.countryCode, option.quoteRegion, option.carrier, option.channel, option.rule, option.channelCode, option.eta, option.isPrimary ? '是' : '否', option.available === false ? option.availabilityMessage || '不可用' : '已保存报价',
      taxDescription(option), option.taxRatePercent, surchargeDescription(option), money(option.surchargeUsd), option.logisticsInput?.weightKg, grams(option.logisticsInput?.weightKg), option.logisticsInput?.quantity ?? '未保存数量（不可推算）', money(option.freightCny), money(option.totalCostCny),
      ...quantities.flatMap(q => {
        const system = savedSystemPrice(record, option, q)
        const customerIndex = snapshot?.quantities.indexOf(q) ?? -1
        const customer = snapshot ? customerRow && customerIndex >= 0 ? customerRow.prices[customerIndex] : null : system
        return [grams(savedFinalWeightKg(record, option, q)), money(system), cny(system), customer == null ? '未报价' : money(customer), customer == null ? '未报价' : cny(customer), savedTax(option, q, record.customQuoteQuantity)]
      }),
    ]))
  }
  lines.push('', '包材与重量快照')
  if (record.weightSnapshot) {
    const w = record.weightSnapshot
    lines.push(line(['普通包材规则','每件每50g加1g，不足向上取整','特殊包装（g/票）',w.specialPackagingGrams,'增加方式','整票一次']))
    lines.push(line(['数量','商品重量（kg）','普通包材（kg）','特殊包装（kg）','整票含包材重量（kg）']))
    for (const q of w.quantities) lines.push(line([q.quantity,q.baseWeightKg,q.standardPackagingWeightKg,q.specialPackagingWeightKg,q.weightKg]))
    lines.push(line(['SKU','单套件数','单件基础重量（kg）','单件普通包材（kg）']))
    for (const item of w.items) lines.push(line([item.sku,item.quantityPerSet,item.baseWeightKg,item.standardPackagingWeightKg]))
  } else lines.push('旧记录未保存完整包材规则及特殊包装快照；原重量及报价不回算')
  if (record.bundleItems?.length) {
    lines.push('', '组合商品快照', line(['SKU', '商品', '每套件数', '单件重量（kg）', '采购原价（CNY）', '计入采购价（CNY）', '单件国内运费（CNY）', '采购发票', '采购票点（%）']))
    for (const item of record.bundleItems) lines.push(line([item.sku, item.name, item.quantityPerSet, item.effectiveWeightKg, money(item.purchaseBaseUnitPriceCny), money(item.purchaseUnitPriceCny), money(item.domesticFreightPerUnitCny), item.purchaseInvoiceType, item.purchaseInvoiceRatePercent]))
  }
  if (record.dealLines?.length) {
    lines.push('', '成交明细', line(['方案', '国家', '物流商', '渠道', '单价（USD）', '数量', '金额（USD）']))
    for (const deal of record.dealLines) lines.push(line([deal.optionLabel, deal.country, deal.carrier, deal.channel, money(deal.unitPriceUsd), deal.quantity, money(deal.amountUsd)]))
  }
  return lines.join('\n')
}
