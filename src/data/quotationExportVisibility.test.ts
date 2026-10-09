// @vitest-environment happy-dom
import { expect, it } from 'vitest'
import { normalizeQuotationRecord } from './quotationRecords'
import { quotationDetailsCsv } from './quotationAnalytics'
import { quotationRecordCopyLayout } from './quotationRecordCopyLayout'
import { quotationRecordQuoteOnlyLayout } from './quotationRecordQuoteOnlyLayout'

it('omits confidential columns and values from CSV and both clipboard formats, preserving snapshots', () => {
  const record = normalizeQuotationRecord({ id: 'private', no: 'QT-PRIVATE', primarySku: 'SKU-1', customerName: '客户甲', customerGrade: 'S',
    purchaseUnitPriceCny: 731.29, domesticFreightPerUnitCny: 12.37, totalCostCny: 827.12, systemQuoteUsd: 150, systemQuoteCny: 1000,
    quoteOptions: [{ id: 'a', country: '美国', carrier: '顺丰', channel: '专线', rule: '普货', quote1Usd: 150, quote2Usd: null, quote3Usd: null, quoteCustomUsd: null,
      freightCny: 83.46, totalCostCny: 827.12, profitCny: 172.88, eta: '5-8天' }] })!
  const before = JSON.stringify(record)
  const simple = quotationRecordQuoteOnlyLayout(record, 'full', { includeCost: false })
  const detailed = quotationRecordCopyLayout(record, 'full', { includeDetails: false, includeCost: false })
  const csv = quotationDetailsCsv([record], [], { includeReview: true, includeCost: false })
  for (const output of [simple.text, simple.html, detailed.text, detailed.html, csv]) {
    for (const secret of ['731.29', '12.37', '827.12', '83.46', '172.88', '1.27', '总成本', '采购成本', '成本(RMB)', '物流运费']) expect(output).not.toContain(secret)
    expect(output).toContain('150.00')
    expect(output).toContain('客户甲')
  }
  expect(simple.text).toContain('顺丰｜专线')
  expect(quotationDetailsCsv([record], [], { includeCost: true })).toContain('827.12')
  expect(quotationRecordQuoteOnlyLayout(record, 'full', { includeCost: true }).text).toContain('731.29')
  expect(quotationRecordCopyLayout(record).text).toContain('83.46')
  expect(JSON.stringify(record)).toBe(before)
})
