import { describe, expect, it } from 'vitest'
import type { QuotationRecord, QuotationRecordQuoteOption } from './quotationRecords'
import { quotationDealDifference, signedPercent, signedUsd } from './quotationDealDifference'

function option(id: string, quote1Usd: number, quote2Usd: number | null = null): QuotationRecordQuoteOption {
  return { id, country: '美国', countryCode: 'US', carrier: '云途', channel: `渠道${id}`, channelCode: id, rule: '规则', eta: '8天', quoteRegion: '', isPrimary: id === 'a', quote1Usd, quote2Usd, quote3Usd: null, quoteCustomUsd: null, totalCostCny: 0 }
}

function record(input: Partial<QuotationRecord> = {}): QuotationRecord {
  return { id: 'r1', no: 'QT-1', salespersonName: '业务员', salespersonAccount: 'sales', customerName: '客户', quoteMode: 'single', productSummary: '商品', primarySku: 'SKU-1', logisticsAttribute: '普货', volumetricEnabled: false, country: '美国', carrier: '云途', channel: '渠道a', rule: '规则', customerGrade: 'A', matrixMode: 'common', quoteOptions: [option('a', 10, 18), option('b', 12, 22)], systemQuoteCny: 67.5, systemQuoteUsd: 10, totalCostCny: 50, exchangeRate: 6.75, status: 'pending', createdAt: '2026-08-26T00:00:00Z', updatedAt: '2026-08-26T00:00:00Z', revisions: [], ...input }
}

describe('quotation deal difference', () => {
  it('keeps pending and lost records out of price comparison', () => {
    expect(quotationDealDifference(record()).kind).toBe('pending')
    expect(quotationDealDifference(record({ status: 'lost' })).kind).toBe('lost')
  })

  it('compares each structured deal line against its matching quantity tier', () => {
    const result = quotationDealDifference(record({ status: 'won', dealLines: [
      { id: 'd1', optionId: 'a', optionLabel: '美国 · 渠道a', country: '美国', carrier: '云途', channel: '渠道a', unitPriceUsd: 10, quantity: 1, amountUsd: 10 },
      { id: 'd2', optionId: 'b', optionLabel: '美国 · 渠道b', country: '美国', carrier: '云途', channel: '渠道b', unitPriceUsd: 12, quantity: 2, amountUsd: 24 },
    ] }))
    expect(result).toMatchObject({ kind: 'higher', systemUsd: 32, actualUsd: 34, differenceUsd: 2, percent: 6.3 })
    expect(result.lines).toHaveLength(2)
  })

  it('marks unsupported quantities as missing instead of inventing a baseline', () => {
    const result = quotationDealDifference(record({ status: 'won', dealLines: [{ id: 'd1', optionId: 'a', optionLabel: '美国 · 渠道a', country: '美国', carrier: '云途', channel: '渠道a', unitPriceUsd: 8, quantity: 4, amountUsd: 32 }] }))
    expect(result.kind).toBe('missing')
    expect(result.percent).toBeNull()
  })

  it('supports legacy single-line records and signed formatting', () => {
    const result = quotationDealDifference(record({ status: 'won', actualQuoteUsd: 9, dealQuantity: 1 }))
    expect(result).toMatchObject({ kind: 'lower', differenceUsd: -1, percent: -10 })
    expect(signedUsd(result.differenceUsd)).toBe('−$1.00')
    expect(signedPercent(result.percent)).toBe('-10.0%')
  })
})
