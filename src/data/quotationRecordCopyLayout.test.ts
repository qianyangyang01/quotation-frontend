// @vitest-environment happy-dom
import { expect, it } from 'vitest'
import { normalizeQuotationRecord } from './quotationRecords'
import { quotationRecordCopyLayout } from './quotationRecordCopyLayout'
import { buildQuotationWeightSnapshot } from './quotationWeightSnapshot'

const record = () => normalizeQuotationRecord({
  id: 'layout', no: 'QT-LAYOUT', customerName: '客户甲', customerGrade: 'E级客户', quoteMode: 'bundle',
  primarySku: '001+002', exchangeRate: 6.7, customQuoteQuantity: 5,
  quoteOptions: [{ id: 'us', country: '美国', carrier: '燕文', channel: '化妆品专线', rule: '原规则', eta: '6-12天',
    quote1Usd: 19.2, quote2Usd: 37.4, quote3Usd: null, quoteCustomUsd: 88, surchargeUsd: 0,
    logisticsSamples: [{ quantity: 1, input: { weightKg: .603 }, total: 55.78 }, { quantity: 5, input: { weightKg: 3.015 }, total: 243.66 }],
  }],
  bundleItems: [{ sku: '001', name: '枕头', quantityPerSet: 2, effectiveWeightKg: .1, purchaseUnitPriceCny: 12.34, domesticFreightPerUnitCny: .21, purchaseBaseUnitPriceCny: 11, purchaseInvoiceType: '普票', purchaseInvoiceRatePercent: 6 }],
  weightSnapshot: buildQuotationWeightSnapshot([{ sku: '001', quantityPerSet: 2, baseWeightKg: .1 }], 10, [1, 2, 3, 5]),
  customerQuote: { quantities: [1, 4, 5], rows: [{ optionId: 'us', prices: [null, 70, 89] }] },
})!

it('keeps every route and its quantity-specific fees on one row, without changing saved values', () => {
  const saved = record(), before = JSON.stringify(saved), layout = quotationRecordCopyLayout(saved)
  expect(layout.text).toContain('一、报价基本信息')
  expect(layout.text).toContain('客户等级\t普通客户')
  const tableRows = layout.text.split('\n').map(row => row.split('\t'))
  const header = tableRows.find(row => row[0] === '国家')!
  const route = tableRows.find(row => row[0] === '美国')!
  expect(route[1]).toBe('燕文｜化妆品专线')
  expect(route[header.indexOf('1套')]).toBe('未报价')
  expect(route[header.indexOf('5套')]).toBe('89.00')
  expect(route[header.indexOf('系统报价 USD')]).toContain('1套：19.20')
  expect(route[header.indexOf('客户报价 CNY')]).toContain('5套：596.30')
  expect(route[header.indexOf('物流运费 CNY/单')]).toContain('1套：55.78')
  expect(route[header.indexOf('物流运费 CNY/单')]).toContain('5套：243.66')
  expect(route[header.indexOf('含包材重量 g')]).toContain('1套：214')
  expect(route[header.indexOf('附加费 USD/单')]).toBe('0.00')
  expect(layout.text).not.toContain('见税费明细')
  expect(layout.text.indexOf('4套\t')).toBeLessThan(layout.text.indexOf('5套\t'))
  expect(layout.text).toContain('001\t0.21\t普票\t6')
  expect(layout.text).toContain('001\t枕头\t2\t0.1\t11.00\t12.34')
  expect(layout.text).toContain('国家\t运输\t1套\t2套\t3套\t4套\t5套\t6套\t7套\t8套')
  expect(layout.text).toContain('产品成本快照（CNY）')
  const doc = new DOMParser().parseFromString(layout.html, 'text/html')
  for (const table of doc.querySelectorAll('table')) {
    const count = table.querySelectorAll('col').length
    for (const row of table.querySelectorAll('tr')) expect([...row.children].reduce((n, cell) => n + Number(cell.getAttribute('colspan') || 1), 0)).toBe(count)
  }
  expect(JSON.stringify(saved)).toBe(before)
})

it('escapes clipboard HTML and Excel formulas without dropping routes, appendix fields or explicit zero freight', () => {
  const saved = record()
  saved.customerName = '=1+1\t<script>alert(1)</script>'
  saved.quoteOptions = Array.from({ length: 49 }, (_, i) => ({ ...saved.quoteOptions![0]!, id: String(i), channel: `<img src=x onerror=alert(1)>-${i}`, logisticsSamples: [{ quantity: 1, input: { weightKg: .214 }, total: 0 }] }))
  const layout = quotationRecordCopyLayout(saved)
  expect(layout.text).toContain("客户\t'=1+1 <script>")
  expect(layout.text).toContain('1套：0.00')
  const doc = new DOMParser().parseFromString(layout.html, 'text/html')
  expect(doc.querySelector('script, img')).toBeNull()
  expect(doc.querySelectorAll('[data-quotation-route]')).toHaveLength(49)
  expect(doc.body.textContent).toContain('<script>alert(1)</script>')
})

it('keeps countries and regions on separate matrix rows, preserves quantities above eight and leaves unknown prices unquoted', () => {
  const saved = record()
  saved.customQuoteQuantity = 12
  saved.customerQuote = { quantities: [1, 12], rows: [{ optionId: 'us', prices: [19.2, 120] }] }
  saved.quoteOptions!.push({ ...saved.quoteOptions![0]!, id: 'au', country: '澳大利亚', quoteRegion: '3区' })
  const layout = quotationRecordCopyLayout(saved)
  const rows = layout.text.split('\n').map(row => row.split('\t'))
  const header = rows.find(row => row[0] === '国家')!
  expect(header).toContain('12套')
  const usa = rows.find(row => row[0] === '美国')!
  expect(usa[header.indexOf('1套')]).toBe('19.20')
  expect(usa[header.indexOf('8套')]).toBe('未报价')
  expect(usa[header.indexOf('12套')]).toBe('120.00')
  expect(rows.some(row => row[0] === '澳大利亚 · 3区')).toBe(true)
  const doc = new DOMParser().parseFromString(layout.html, 'text/html')
  const matrix = doc.querySelector('table')!
  expect(matrix.textContent).toContain('物流运费 CNY/单')
  expect(matrix.querySelectorAll('col')).toHaveLength(header.length)
  expect(matrix.querySelectorAll('[data-quotation-route]')).toHaveLength(2)
  expect(matrix.innerHTML).toContain('#c6e0b4')
})

it('exports actual saved taxes, surcharge and operation fees in the same route row', () => {
  const saved = record()
  saved.quoteOptions![0]!.tax1Usd = 1.25
  saved.quoteOptions![0]!.tax2Usd = 2.5
  saved.quoteOptions![0]!.surchargeUsd = .6
  saved.quoteOptions![0]!.taxLabel = '按单税费'
  saved.customerOperation = { id: 'pillow', name: '枕头客户', feeUsd: .3, feesByQuantityUsd: { '1': .3, '2': .5, '3': .6, above3: .6 } }
  const rows = quotationRecordCopyLayout(saved).text.split('\n').map(row => row.split('\t'))
  const header = rows.find(row => row[0] === '国家')!, route = rows.find(row => row[0] === '美国')!
  expect(route[header.indexOf('关税 USD/单')]).toContain('1套：1.25；2套：2.50')
  expect(route[header.indexOf('关税 USD/单')]).toContain('3套：未保存')
  expect(route[header.indexOf('附加费 USD/单')]).toBe('0.60')
  expect(route[header.indexOf('操作费 USD/单')]).toContain('1套：0.30；2套：0.50')
  expect(route[header.indexOf('关税说明')]).toBe('按单税费')
  expect(rows.filter(row => row[0] === '美国')).toHaveLength(1)
})
