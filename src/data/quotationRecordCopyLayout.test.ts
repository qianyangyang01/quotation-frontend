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

it('creates narrow route sections, separates prices and freight, and preserves saved values and missing customer prices', () => {
  const saved = record(), before = JSON.stringify(saved), layout = quotationRecordCopyLayout(saved)
  expect(layout.text).toContain('一、报价基本信息')
  expect(layout.text).toContain('客户等级\t普通客户')
  expect(layout.text).toContain('美国 · 燕文 · 化妆品专线')
  expect(layout.text).toContain('1套\t19.20\t128.64\t未报价\t未报价')
  expect(layout.text).toContain('5套\t88.00\t589.60\t89.00\t596.30')
  expect(layout.text).toContain('1套\t214\t55.78\t未保存\t0.00\t未保存')
  expect(layout.text).toContain('5套\t1030\t243.66')
  expect(layout.text.indexOf('4套\t')).toBeLessThan(layout.text.indexOf('5套\t'))
  expect(layout.text).toContain('001\t0.21\t普票\t6')
  expect(layout.text).toContain('001\t枕头\t2\t0.1\t11.00\t12.34')
  expect(layout.text.split('\n').every(row => row.split('\t').length <= 6)).toBe(true)
  const doc = new DOMParser().parseFromString(layout.html, 'text/html')
  for (const row of doc.querySelectorAll('tr')) expect([...row.children].reduce((n, cell) => n + Number(cell.getAttribute('colspan') || 1), 0)).toBe(6)
  expect(doc.querySelectorAll('col')).toHaveLength(6)
  expect(JSON.stringify(saved)).toBe(before)
})

it('escapes clipboard HTML and Excel formulas without dropping routes, appendix fields or explicit zero freight', () => {
  const saved = record()
  saved.customerName = '=1+1\t<script>alert(1)</script>'
  saved.quoteOptions = Array.from({ length: 49 }, (_, i) => ({ ...saved.quoteOptions![0]!, id: String(i), channel: `<img src=x onerror=alert(1)>-${i}`, logisticsSamples: [{ quantity: 1, input: { weightKg: .214 }, total: 0 }] }))
  const layout = quotationRecordCopyLayout(saved)
  expect(layout.text).toContain("客户\t'=1+1 <script>")
  expect(layout.text).toContain('1套\t214\t0.00')
  expect(layout.text).toContain('49. 美国')
  const doc = new DOMParser().parseFromString(layout.html, 'text/html')
  expect(doc.querySelector('script, img')).toBeNull()
  expect(doc.body.textContent).toContain('<script>alert(1)</script>')
})
