import { describe, expect, it } from 'vitest'
import { buildDashboardSummary, buildSalespersonRanking, quotationDetailsCsv, quotationSkus } from './quotationAnalytics'
import { fobBatchSnapshot, parseFobBatch } from '@/services/fobBatchQuotation'
import { FOB_SMALL_ORDER_POLICY, type FobQuoteProduct } from '@/services/fobQuotation'
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
  it('counts every batch SKU but only actually won SKUs and excludes ambiguous aggregate prices',()=>{
    const product=(sku:string):FobQuoteProduct=>({sku,source:'fob',category:'日用品',weight:'',notices:[],parsed:{minOrderQty:1,orderMultiple:1,priceTiers:[{minQty:1,maxQty:null,unitPriceCny:10,unit:'件'}],freight:{quantity:100,totalFreightCny:0,unitFreightCny:0,basis:'包邮',estimated:false}}})
    const fob=fobBatchSnapshot(parseFobBatch('FIRST 20\nSECOND 20').map(l=>({...l,product:product(l.sku)})),'quantity',6.7,FOB_SMALL_ORDER_POLICY,{title:'批量报价',agent:'业务员',date:'2026-10-11',whatsapp:''})
    const row=record({id:'batch',quoteMode:'fob',fob,primarySku:'FIRST',customerName:'客户',salespersonName:'业务员',salespersonAccount:'SALE',status:'won',createdAt:'2026-10-11',systemQuoteUsd:999,systemQuoteCny:999})
    row.dealLines=[{id:'x',optionId:'fob:1:declared',optionLabel:'SECOND',country:'',carrier:'',channel:'',unitPriceUsd:2,quantity:10,amountUsd:20}]
    expect(quotationSkus(row)).toEqual(['FIRST','SECOND']);expect(buildDashboardSummary([row])).toMatchObject({quotationCount:1,quotedSkuCount:2,quoteUsd:0,quoteCny:0})
    expect(buildSalespersonRanking([row])[0]).toMatchObject({quotedProductCount:2,wonProductCount:1,conversionRate:50})
    const csv=quotationDetailsCsv([row],[],{includeCost:false});expect(csv).toContain('逐商品查看');expect(csv).toContain('SECOND 20 pcs');expect(csv).not.toContain('999.00')
  })
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
    normalizeQuotationRecord({id:'d',no:'QT-D',financeReviewStatus:'logistics-exempt',status:'pending'})!,
  ]
  const before=JSON.stringify(rows), csv=quotationDetailsCsv(rows,[],{includeReview:true})
  expect(csv.split('\r\n')[0]).toContain('审核状态,审核人,审核时间,成交结果')
  expect(csv).toContain('待审核,,,未标记成交')
  expect(csv).toContain('审核通过,管理员,2026-09-27T02:00:00Z,已成交')
  expect(csv).toContain('同渠道免审,,,未成交')
  expect(csv).toContain('物流免审-采购已审,,,未标记成交')
  expect(csv).not.toMatch(/待处理|已处理|处理状态/)
  expect(JSON.stringify(rows)).toBe(before)
})

it('exports inspection metadata with internal review columns only and preserves the source snapshot', () => {
  const marked = normalizeQuotationRecord({id:'checked',no:'QT-CHECKED',spotChecked:true,spotCheckedBy:'管理员',spotCheckedAt:'2026-10-08T08:00:00Z'})!
  const before = JSON.stringify(marked)
  const csv = quotationDetailsCsv([marked], [], {includeReview:true})
  expect(csv).toContain('抽检标记,抽检人,抽检时间')
  expect(csv).toContain('已抽检,管理员,2026-10-08T08:00:00Z')
  expect(quotationDetailsCsv([marked], [])).not.toContain('抽检')
  expect(JSON.stringify(marked)).toBe(before)
})
