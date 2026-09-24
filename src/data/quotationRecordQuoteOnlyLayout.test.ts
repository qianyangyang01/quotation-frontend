// @vitest-environment happy-dom
import { expect, it } from 'vitest'
import { normalizeQuotationRecord } from './quotationRecords'
import { quotationRecordQuoteOnlyLayout } from './quotationRecordQuoteOnlyLayout'

const record = () => normalizeQuotationRecord({ id: 'quote', no: 'QT-ONE', customerName: '客户甲', primarySku: '001', productSummary: '枕头', customQuoteQuantity: 12,
  customerGrade: 'E', commissionThreshold: .9876, totalCostCny: 987.65, purchaseUnitPriceCny: 876.54, note: '内部备注', salespersonAccount: 'PRIVATE-ACCOUNT',
  customerOperation: { id: 'internal', name: '内部操作费', feeUsd: 17.23 },
  quoteOptions: [{ id: 'a', country: '澳大利亚', quoteRegion: '3区', carrier: '燕文', channel: '化妆品专线', rule: '内部计费规则', eta: '6-12天', quote1Usd: 19.2, quote2Usd: 37.4, quote3Usd: 55, quoteCustomUsd: 222, freightCny: 765.43 }],
  customerQuote: { quantities: [1, 2, 12], rows: [{ optionId: 'a', prices: [20, null, 230] }] },
})!

it('copies only customer quotation fields and routes, preserving customer blanks without leaking internal costs', () => {
  const saved = record(), before = JSON.stringify(saved), layout = quotationRecordQuoteOnlyLayout(saved)
  expect(layout.text).toContain('澳大利亚 · 3区\t燕文｜化妆品专线\t20.00\t230.00')
  expect(layout.text.split('\n').find(row => row.startsWith('国家\t'))).toBe('国家\t物流渠道\t1件\t12件\t预计时效')
  expect(layout.text).toContain('12件')
  expect(layout.text).toContain('230.00')
  expect(layout.text).toContain('客户\t客户甲\t客户等级\t普通客户')
  expect(layout.text).toContain(`创建时间\t${saved.createdAt}`)
  for (const output of [layout.text, layout.html]) {
    expect(output).toContain('普通客户')
    for (const secret of ['内部备注', '内部计费规则', 'PRIVATE-ACCOUNT', '987.65', '876.54', '765.43', '17.23', '19.20', '采购', '佣金', '操作费', '成本']) expect(output).not.toContain(secret)
  }
  expect(JSON.stringify(saved)).toBe(before)
  const doc = new DOMParser().parseFromString(layout.html, 'text/html')
  const columns = doc.querySelectorAll('col').length
  for (const row of doc.querySelectorAll('tr')) expect([...row.children].reduce((n, cell) => n + Number(cell.getAttribute('colspan')), 0)).toBe(columns)
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
