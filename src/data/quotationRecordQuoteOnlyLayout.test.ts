// @vitest-environment happy-dom
import { expect, it } from 'vitest'
import { normalizeQuotationRecord } from './quotationRecords'
import { quotationRecordQuoteOnlyLayout } from './quotationRecordQuoteOnlyLayout'
import { buildQuotationWeightSnapshot } from './quotationWeightSnapshot'

const record = () => normalizeQuotationRecord({ id: 'quote', no: 'QT-ONE', customerName: '客户甲', primarySku: '001', productSummary: '枕头', customQuoteQuantity: 12,
  customerGrade: 'E', commissionThreshold: .9876, totalCostCny: 987.65, purchaseUnitPriceCny: 876.54, note: '内部备注', salespersonAccount: 'PRIVATE-ACCOUNT',
  customerOperation: { id: 'internal', name: '内部操作费', feeUsd: 17.23 },
  quoteOptions: [{ id: 'a', country: '澳大利亚', quoteRegion: '3区', carrier: '燕文', channel: '化妆品专线', rule: '内部计费规则', eta: '6-12天', quote1Usd: 19.2, quote2Usd: 37.4, quote3Usd: 55, quoteCustomUsd: 222, freightCny: 765.43 }],
  customerQuote: { quantities: [1, 2, 12], rows: [{ optionId: 'a', prices: [20, null, 230] }] },
})!

it('adds the approved summary while preserving customer blanks and excluding unrelated internal details', () => {
  const saved = record(), before = JSON.stringify(saved), layout = quotationRecordQuoteOnlyLayout(saved)
  expect(layout.text).toContain('澳大利亚 · 3区\t燕文｜化妆品专线\t20.00\t230.00')
  expect(layout.text.split('\n').find(row => row.startsWith('国家\t'))).toBe('国家\t物流渠道\t1件\t12件\t预计时效')
  expect(layout.text).toContain('12件')
  expect(layout.text).toContain('230.00')
  expect(layout.text).toContain('客户\t客户甲\t客户等级\t普通客户')
  expect(layout.text).toContain(`创建时间\t${saved.createdAt}`)
  for (const output of [layout.text, layout.html]) {
    expect(output).toContain('普通客户')
    expect(output).toContain('876.54')
    for (const secret of ['内部备注', '内部计费规则', 'PRIVATE-ACCOUNT', '987.65', '765.43', '17.23', '19.20', '佣金', '操作费']) expect(output).not.toContain(secret)
  }
  expect(JSON.stringify(saved)).toBe(before)
  const doc = new DOMParser().parseFromString(layout.html, 'text/html')
  const columns = doc.querySelectorAll('col').length
  for (const row of doc.querySelectorAll('tr')) expect([...row.children].reduce((n, cell) => n + Number(cell.getAttribute('colspan')), 0)).toBe(columns)
})

function withSummary() {
  const saved = record()
  saved.customQuoteQuantity = 5
  saved.purchaseUnitPriceCny = 46
  saved.domesticFreightPerUnitCny = 2
  saved.weightSnapshot = buildQuotationWeightSnapshot([{ sku: '001', quantityPerSet: 1, baseWeightKg: .2 }], 3, [1, 2, 3, 5])
  saved.quoteOptions![0]!.isPrimary = true
  saved.quoteOptions![0]!.totalCostCny = 999 // The custom-quantity amount is not the 1-item cost.
  saved.quoteOptions![0]!.logisticsSamples = [{ quantity: 5, input: { weightKg: 1.023 }, total: 80 }, { quantity: 1, input: { weightKg: .207 }, total: 35 }]
  return saved
}

it('copies product cost excluding international freight and the complete packaging breakdown', () => {
  const saved = withSummary()
  saved.customerQuote = undefined
  const before = JSON.stringify(saved), layout = quotationRecordQuoteOnlyLayout(saved)
  const lines = layout.text.split('\n'), created = lines.findIndex(line => line.startsWith('创建时间\t'))
  expect(lines[created + 1]).toBe('总成本价（CNY/1件）\t48.00\t商品成本 46.00 + 国内运费 2.00（CNY，不含国际运费）')
  expect(lines[created + 2]).toBe('含包材重量（g/1件）\t207\t基础 200g + 普通包材 4g + 特殊包装 3g')
  expect(lines[created + 3]).toMatch(/^国家\t物流渠道\t/)
  expect(layout.text).toContain('每件商品每 50g 加 1g，不足 50g 按 50g 计算')
  expect(layout.text).toContain('特殊包装整票只加一次，不随件数或套数增加，特殊包装本身不再计算普通包材')
  for (const label of ['计算含税单价', '计算运费', '最终合计成本', '计算产品重量', '计算包材重量', '最终合计重量', '首选渠道']) expect(layout.text).not.toContain(label)
  expect(layout.text).not.toContain('999')
  const doc = new DOMParser().parseFromString(layout.html, 'text/html'), rows = [...doc.querySelectorAll('tr')]
  expect(rows[3]!.textContent).toContain('48.00')
  expect(rows[4]!.textContent).toContain('207')
  expect(rows[5]!.textContent).toContain('国家')
  for (const row of rows) expect([...row.children].reduce((n, cell) => n + Number(cell.getAttribute('colspan')), 0)).toBe(7)
  expect(JSON.stringify(saved)).toBe(before)
})

it('keeps product cost independent of selected international routes, preserving decimal totals', () => {
  const saved = withSummary(), primary = saved.quoteOptions![0]!
  saved.purchaseUnitPriceCny = .1
  saved.domesticFreightPerUnitCny = .2
  primary.logisticsSamples = [{ quantity: 1, input: { weightKg: .207 }, total: 0 }]
  saved.quoteOptions!.unshift({ ...primary, id: 'other', isPrimary: false, country: '美国', logisticsSamples: [{ quantity: 1, input: { weightKg: .207 }, total: 123 }] })
  const layout = quotationRecordQuoteOnlyLayout(saved)
  expect(layout.text).toContain('总成本价（CNY/1件）\t0.30\t商品成本 0.10 + 国内运费 0.20')
})

it.each(['no-sample', 'duplicate-sample', 'null-freight', 'negative-freight', 'unavailable', 'ambiguous-primary'] as const)('does not require international freight to display product cost: %s', reason => {
  const saved = withSummary(), primary = saved.quoteOptions![0]!
  if (reason === 'no-sample') primary.logisticsSamples = primary.logisticsSamples!.filter(row => row.quantity === 5)
  if (reason === 'duplicate-sample') primary.logisticsSamples!.push({ quantity: 1, input: { weightKg: .207 }, total: 42 })
  if (reason === 'null-freight') primary.logisticsSamples![1]!.total = null
  if (reason === 'negative-freight') primary.logisticsSamples![1]!.total = -1
  if (reason === 'unavailable') primary.available = false
  if (reason === 'ambiguous-primary') { primary.isPrimary = false; saved.quoteOptions!.push({ ...primary, id: 'other' }) }
  expect(quotationRecordQuoteOnlyLayout(saved).text).toContain('总成本价（CNY/1件）\t48.00')
})

it('does not turn absent legacy fields into zero, or derive packaging from current rules', () => {
  const saved = normalizeQuotationRecord({ id: 'legacy', no: 'QT-LEGACY', quoteOptions: [{ ...record().quoteOptions![0]!, id: 'a', freightCny: 35, logisticsInput: { country: '美国', quantity: 5, baseWeightKg: 1, packagingWeightKg: .02, weightKg: 1.02, marks: [] } }] })!
  const lines = quotationRecordQuoteOnlyLayout(saved).text.split('\n')
  for (const label of ['总成本价', '含包材重量']) expect(lines.find(line => line.startsWith(label))?.split('\t')[1]).toBe('未保存')
  saved.quoteOptions![0]!.logisticsInput!.quantity = 1
  expect(quotationRecordQuoteOnlyLayout(saved).text).toContain('含包材重量（g/1件）\t1020\t基础 1000g + 包材合计 20g（普通包材、特殊包装明细未保存）')
})

it('summarizes bundle component counts for one set and includes special packaging once', () => {
  const saved = withSummary()
  saved.quoteMode = 'bundle'
  saved.bundleItems = [
    { sku: 'A', name: 'A', effectiveWeightKg: .1, quantityPerSet: 2, purchaseUnitPriceCny: .1, domesticFreightPerUnitCny: .2 },
    { sku: 'B', name: 'B', effectiveWeightKg: .05, quantityPerSet: 1, purchaseUnitPriceCny: .2, domesticFreightPerUnitCny: 0 },
  ]
  saved.weightSnapshot = buildQuotationWeightSnapshot([{ sku: 'A', quantityPerSet: 2, baseWeightKg: .1 }, { sku: 'B', quantityPerSet: 1, baseWeightKg: .05 }], 3, [1, 5])
  const text = quotationRecordQuoteOnlyLayout(saved).text
  expect(text).toContain('总成本价（CNY/1套）\t0.80\t商品成本 0.40 + 国内运费 0.40')
  expect(text).toContain('含包材重量（g/1套）\t258\t基础 250g + 普通包材 5g + 特殊包装 3g')
})

it('preserves a saved total weight without inventing its missing product and packaging breakdown', () => {
  const saved = withSummary()
  saved.weightSnapshot = undefined
  expect(quotationRecordQuoteOnlyLayout(saved).text).toContain('含包材重量（g/1件）\t207\t基础 未保存g + 包材合计 未保存g')
})

it('does not turn missing domestic freight into zero or use a logistics total', () => {
  const saved = withSummary()
  saved.domesticFreightPerUnitCny = undefined
  saved.quoteOptions![0]!.totalCostCny = undefined
  expect(quotationRecordQuoteOnlyLayout(saved).text).toContain('总成本价（CNY/1件）\t未保存\t商品成本 46.00 + 国内运费 未保存')
})

it('retains zero domestic freight and rounds ordinary packaging per individual bundle item', () => {
  const saved = withSummary()
  saved.domesticFreightPerUnitCny = 0
  saved.weightSnapshot = buildQuotationWeightSnapshot([{ sku: 'A', quantityPerSet: 2, baseWeightKg: .026 }], 100, [1, 3])
  const text = quotationRecordQuoteOnlyLayout(saved).text
  expect(text).toContain('总成本价（CNY/1件）\t46.00')
  expect(text).toContain('含包材重量（g/1件）\t154\t基础 52g + 普通包材 2g + 特殊包装 100g')
})

it('uses saved system prices only when no customer sheet exists, including zero, and escapes names', () => {
  const saved = record()
  saved.customerQuote = undefined
  saved.quoteOptions![0]!.quote1Usd = 0
  saved.customerName = '=1+1\n<script>alert(1)</script>'
  saved.quoteOptions![0]!.channel = '<img src=x onerror=alert(1)>'
  const layout = quotationRecordQuoteOnlyLayout(saved)
  expect(layout.text).toContain("客户\t'=1+1 <script>")
  expect(layout.text).toContain('\t0.00\t37.40')
  expect(new DOMParser().parseFromString(layout.html, 'text/html').querySelector('script,img')).toBeNull()
})

it('does not invent customer prices for missing route rows and supports empty records', () => {
  const saved = record()
  saved.customerQuote!.rows = []
  expect(quotationRecordQuoteOnlyLayout(saved).text).not.toContain('19.20')
  saved.quoteOptions = []
  const layout = quotationRecordQuoteOnlyLayout(saved)
  expect(layout.text).toContain('未保存国家与物流渠道报价')
  const doc = new DOMParser().parseFromString(layout.html, 'text/html')
  for (const row of doc.querySelectorAll('tr')) expect([...row.children].reduce((n, cell) => n + Number(cell.getAttribute('colspan')), 0)).toBe(3)
})

it('keeps partial route blanks under priced columns and clearly distinguishes full Australian region names', () => {
  const saved = record()
  saved.quoteOptions![0]!.quoteRegion = '澳大利亚2区'
  saved.quoteOptions!.push({ ...saved.quoteOptions![0]!, id: 'b', quoteRegion: '澳大利亚3区' })
  saved.customerQuote!.rows.push({ optionId: 'b', prices: [null, 0, null] })
  const layout = quotationRecordQuoteOnlyLayout(saved)
  expect(layout.text).toContain('国家\t物流渠道\t1件\t2件\t12件\t预计时效')
  expect(layout.text).toContain('澳大利亚2区\t燕文｜化妆品专线\t20.00\t未报价\t230.00')
  expect(layout.text).toContain('澳大利亚3区\t燕文｜化妆品专线\t未报价\t0.00\t未报价')
  expect(layout.text).not.toContain('澳大利亚 · 澳大利亚')
})
