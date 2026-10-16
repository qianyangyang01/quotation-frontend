import { expect, it } from 'vitest'
import { normalizeQuotationRecord } from './quotationRecords'
import { quotationDetailsCsv } from './quotationAnalytics'
import { quotationRecordCopyLayout } from './quotationRecordCopyLayout'
import { quotationRecordQuoteOnlyLayout } from './quotationRecordQuoteOnlyLayout'
import { quotationRecordReconciliationTsv } from './quotationRecordReconciliation'

it('preserves note text in filtered CSV and copied saved details without changing record snapshots', () => {
  const note = 'SKU-A, SKU-B "加厚" <em>原样</em>'
  const record = normalizeQuotationRecord({ id: 'shipping', no: 'QT-SHIPPING', quoteMode: 'shipping-only', shippingNote: note, productSummary: `仅代发货报价：${note}`, manualPricing: { costCny: 0, weightGrams: 500 }, totalCostCny: 12.34 })!
  const snapshot = JSON.stringify(record)
  const csv = quotationDetailsCsv([record], [], { includeCost: false, includeReview: true })
  expect(csv.split('\r\n')[0]).toContain('代发备注')
  expect(csv).toContain('"SKU-A, SKU-B ""加厚"" <em>原样</em>"')
  expect(csv).not.toContain('成本(RMB)'); expect(csv).not.toContain('12.34')
  const copied = quotationRecordCopyLayout(record)
  const quotes = quotationRecordQuoteOnlyLayout(record)
  expect(quotationRecordReconciliationTsv(record)).toContain(record.productSummary)
  expect(copied.text).toContain(record.productSummary); expect(quotes.text).toContain(record.productSummary)
  expect(copied.html).toContain('&lt;em&gt;原样&lt;/em&gt;'); expect(quotes.html).toContain('&lt;em&gt;原样&lt;/em&gt;')
  expect(copied.html).not.toContain('<em>原样</em>')
  expect(JSON.stringify(record)).toBe(snapshot)
})

it('keeps existing CSV columns for other modes and leaves historical shipping notes empty', () => {
  const single = normalizeQuotationRecord({ id: 'single', no: 'QT-SINGLE', quoteMode: 'single', productSummary: '商品' })!
  expect(quotationDetailsCsv([single], []).split('\r\n')[0]).not.toContain('代发备注')
  const old = normalizeQuotationRecord({ id: 'old', no: 'QT-OLD', quoteMode: 'shipping-only' })!
  const csv = quotationDetailsCsv([old, single], [])
  expect(csv.split('\r\n')[0]).toContain('代发备注')
  expect(csv.split('\r\n').slice(1).every(line => line.endsWith(','))).toBe(true)
})

it('exports a note beginning with a spreadsheet formula marker as text', () => {
  const record = normalizeQuotationRecord({ id: 'shipping', no: 'QT-SHIPPING', quoteMode: 'shipping-only', shippingNote: '=1+1', productSummary: '仅代发货报价：=1+1' })!
  expect(quotationDetailsCsv([record], [])).toContain(",'=1+1")
})
