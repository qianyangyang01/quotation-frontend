import { describe, expect, it } from 'vitest'
import { buildSalespersonRanking, quotationDetailsCsv, quotationSkus } from './quotationAnalytics'
import { normalizeQuotationRecord, type QuotationRecord } from './quotationRecords'

function record(input: Partial<QuotationRecord> & Pick<QuotationRecord, 'id' | 'salespersonName' | 'salespersonAccount' | 'customerName' | 'primarySku' | 'status' | 'createdAt'>): QuotationRecord {
  return {
    no: input.id,
    quoteMode: 'single',
    productSummary: '测试商品',
    logisticsAttribute: '普货',
    country: '美国',
    carrier: '测试物流',
    channel: '测试渠道',
    rule: '测试规则',
    customerGrade: 'S',
    systemQuoteCny: 7,
    systemQuoteUsd: 1,
    totalCostCny: 5,
    exchangeRate: 7,
    updatedAt: input.createdAt,
    revisions: [],
    ...input,
  }
}

describe('quotation overview conversion metrics', () => {
  const rows = [
    record({ id: 'a-1', salespersonName: '甲', salespersonAccount: 'a', customerName: '客户A', primarySku: 'SKU1, SKU1, SKU2', status: 'won', createdAt: '2026-08-01T00:00:00Z' }),
    record({ id: 'a-2', salespersonName: '甲', salespersonAccount: 'a', customerName: '客户A', primarySku: 'SKU1', status: 'pending', createdAt: '2026-08-02T00:00:00Z' }),
    record({ id: 'b-1', salespersonName: '乙', salespersonAccount: 'b', customerName: '客户B', primarySku: 'SKU3', status: 'won', createdAt: '2026-08-03T00:00:00Z' }),
    record({ id: 'c-1', salespersonName: '丙', salespersonAccount: 'c', customerName: '客户C', primarySku: '—', status: 'won', createdAt: '2026-08-04T00:00:00Z' }),
  ]

  it('counts each valid SKU once per quotation and excludes placeholder values', () => {
    expect(quotationSkus(rows[0])).toEqual(['SKU1', 'SKU2'])
    expect(quotationSkus(rows[3])).toEqual([])
  })

  it('sorts by conversion rate and keeps zero-denominator salespeople last', () => {
    const ranking = buildSalespersonRanking(rows)

    expect(ranking.map(item => item.name)).toEqual(['乙', '甲', '丙'])
    expect(ranking[1]).toMatchObject({
      customerCount: 1,
      quotedProductCount: 3,
      wonProductCount: 2,
      conversionRate: 66.67,
    })
    expect(ranking[2].conversionRate).toBeNull()
  })

  it('does not export profit or margin columns', () => {
    const csv = quotationDetailsCsv(rows, [])

    expect(csv).not.toContain('预计毛利')
    expect(csv).not.toContain('毛利率')
    expect(csv.split('\r\n')[0]).toContain('报价(RMB)')
    expect(csv.split('\r\n')[0]!.split(',')).toHaveLength(12)
    expect(csv.split('\r\n')[0]).not.toContain('审核状态')
  })
})

it('exports review conclusions and deal results independently from legacy confirmation without changing snapshots', () => {
  const rows = [
    normalizeQuotationRecord({id:'a',no:'QT-A',quoteConfirmed:true,financeReviewStatus:'pending',status:'pending'})!,
    normalizeQuotationRecord({id:'b',no:'QT-B',quoteConfirmed:false,financeReviewStatus:'approved',financeReviewedBy:'管理员',financeReviewedAt:'2026-09-27T02:00:00Z',status:'won'})!,
    normalizeQuotationRecord({id:'c',no:'QT-C',quoteConfirmed:true,financeReviewStatus:'channel-exempt',status:'lost'})!,
  ]
  const before=JSON.stringify(rows), csv=quotationDetailsCsv(rows,[],{includeReview:true})
  expect(csv.split('\r\n')[0]).toContain('审核状态,审核人,审核时间,成交结果')
  expect(csv).toContain('待审核,,,未标记成交')
  expect(csv).toContain('审核通过,管理员,2026-09-27T02:00:00Z,已成交')
  expect(csv).toContain('同渠道免审,,,未成交')
  expect(csv).not.toMatch(/待处理|已处理|处理状态/)
  expect(JSON.stringify(rows)).toBe(before)
})
