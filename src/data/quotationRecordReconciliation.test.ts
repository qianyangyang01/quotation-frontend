import { expect, it } from 'vitest'
import { normalizeQuotationRecord, type QuotationRecordQuoteOption } from './quotationRecords'
import { quotationRecordReconciliationTsv } from './quotationRecordReconciliation'

const option = (id: string): QuotationRecordQuoteOption => ({
  id, country: '美国', countryCode: 'US', quoteRegion: '全国统一', carrier: '燕文', channel: `燕文服装专线-${id}`, rule: '规则快照', channelCode: `C-${id}`, eta: '7～12 天',
  quote1Usd: 6.35, quote2Usd: 10, quote3Usd: null, quoteCustomUsd: 20, taxFeeMode: 'fixed-order', taxLabel: '关税 $0.30/单', tax1Usd: 0.3, tax2Usd: 0.3, tax3Usd: null, taxCustomUsd: 0.3,
  surchargeEnabled: true, surchargeLabel: '附加费 $0.20/单', surchargeUsd: 0.2,
})
const record = () => normalizeQuotationRecord({ id: 'record', no: 'QT-001', customerName: '客户', salespersonName: '业务员', salespersonAccount: 'EMPLOYEE',
  quoteMode: 'bundle', primarySku: 'wrong-summary', customQuoteQuantity: 5, exchangeRate: 6.7, commissionThreshold: 0.95,
  quoteOptions: [option('a'), option('b')], customerOperation: { id: 'c', name: '客户', feeUsd: 0.3 },
  bundleItems: [{ sku: 'SKU-A', name: 'A', quantityPerSet: 2, effectiveWeightKg: 0.1, purchaseUnitPriceCny: 10, domesticFreightPerUnitCny: 1 }, { sku: 'SKU-B', name: 'B', quantityPerSet: 1, effectiveWeightKg: 0.2, purchaseUnitPriceCny: 20, domesticFreightPerUnitCny: 2 }],
})!
function table(text: string) {
  const lines = text.split('\n').map(row => row.split('\t'))
  const start = lines.findIndex(row => row[0] === '序号')
  const headers = lines[start]!
  return lines.slice(start + 1).filter(row => /^\d+$/.test(row[0] || '')).map(row => {
    expect(row).toHaveLength(headers.length)
    return Object.fromEntries(headers.map((header, index) => [header, row[index]]))
  })
}

it.each(['common', 'specified', 'template'] as const)('exports every route, full identities and both currencies for %s without mutating or repricing', mode => {
  const saved = record(); saved.matrixMode = mode
  const before = JSON.stringify(saved), text = quotationRecordReconciliationTsv(saved), rows = table(text)
  expect(rows).toHaveLength(2)
  expect(rows[0]).toMatchObject({ 'SKU': 'SKU-A+SKU-B', '物流商': '燕文', '渠道': '燕文服装专线-a', '计费规则': '规则快照', '区域': '全国统一', '预计时效': '7～12 天', '渠道编码': 'C-a', '关税说明': '关税 $0.30/单', '附加费说明': '附加费 $0.20/单', '1套关税（USD）': '0.30', '1套系统价（USD）': '6.35', '1套客户价（USD）': '6.35', '1套系统价（CNY）': '42.55', '3套系统价（USD）': '未保存' })
  expect(rows[1]?.['渠道']).toBe('燕文服装专线-b')
  expect(text).toContain('佣金阈值\t0.95')
  expect(text).toContain('SKU-A\tA\t2\t0.1\t未保存\t10.00\t1.00')
  expect(JSON.stringify(saved)).toBe(before)
})

it('keeps final edits, explicit blank prices and additional quantities separate from saved system prices', () => {
  const saved = record()
  saved.systemQuantityQuotes = { quantities: [7], rows: [{ optionId: 'a', prices: [31.05] }] }
  saved.sheetQuote = { quantities: [1, 7], rows: [{ optionId: 'a', prices: [6.5, 32] }] }
  saved.customerQuote = { quantities: [1, 7], rows: [{ optionId: 'a', prices: [null, 33.5] }] }
  const rows = table(quotationRecordReconciliationTsv(saved))
  expect(rows[0]).toMatchObject({ '1套系统价（USD）': '6.35', '1套客户价（USD）': '未报价', '2套客户价（USD）': '未报价', '7套系统价（USD）': '31.05', '7套客户价（USD）': '33.50', '7套客户价（CNY）': '224.45', '7套关税（USD）': '未保存' })
  expect(rows[1]?.['1套客户价（USD）']).toBe('未报价')
  delete saved.customerQuote
  expect(table(quotationRecordReconciliationTsv(saved))[0]?.['1套客户价（USD）']).toBe('6.50')
})

it('does not invent missing historical FX, taxes or tiers; retains old unknown-quantity prices and the original headline amount', () => {
  const saved = normalizeQuotationRecord({ id: 'old', no: 'QT-OLD', systemQuoteUsd: 123, specifiedQuotes: [{ country: '英国', carrier: '未知商', channel: '原渠道', rule: '旧规则', eta: '—', quote1Usd: 0, quote2Usd: null, quote3Usd: null, quoteCustomUsd: 55 }] })!
  const text = quotationRecordReconciliationTsv(saved), row = table(text)[0]!
  expect(row).toMatchObject({ '1件系统价（USD）': '0.00', '1件系统价（CNY）': '未保存', '关税说明': '未保存', '附加费说明': '未保存', '1件关税（USD）': '未保存', '自定义（数量未保存）系统价（USD）': '55.00' })
  expect(text).toContain('首选系统价（USD）\t123.00')
})

it('uses saved per-quantity taxes, keeps all 49 routes, and sanitizes pasted cell boundaries and formulas', () => {
  const saved = record()
  saved.customerName = '=1+1\t\n'
  saved.quoteOptions = Array.from({ length: 49 }, (_, index) => ({ ...option(String(index)), channel: '@example\t\n', taxCalculations: { '1': { rule: 'channel-tax-v1', setting: { key: '1::carrier::channel', mode: 'weight', amount: 0.6, currency: 'EUR', perKg: 1.5 }, country: 'DE', weightKg: 1, usdCny: 6.7, eurUsd: 1.1, taxUsd: 2.31 } } }))
  const text = quotationRecordReconciliationTsv(saved), rows = table(text)
  expect(rows).toHaveLength(49)
  expect(rows[0]?.['1套关税（USD）']).toBe('2.31')
  expect(rows[0]?.['渠道']).toBe("'@example ")
  expect(text).toContain("客户\t'=1+1 ")
})
