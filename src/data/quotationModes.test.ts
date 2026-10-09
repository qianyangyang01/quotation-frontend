import { expect, it } from 'vitest'
import { manualPricingInput, manualQuantity } from './quotationModes'
import { normalizeQuotationRecord } from './quotationRecords'
import { quotationRecordReconciliationTsv } from './quotationRecordReconciliation'
import { filterQuotationRecords, quotationSkus } from './quotationAnalytics'

it('preserves decimal gram precision and forbids silently rounding invalid costs', () => {
  const price=manualPricingInput('freight-trial','0.10','50.125')!
  expect(manualQuantity(price,3)).toEqual({costCny:.3,weightKg:.150375})
  for(const cost of ['', '-1', '1.001', 'NaN', '1e3', '1000001']) expect(manualPricingInput('freight-trial',cost,'500')).toBeNull()
  expect(manualPricingInput('shipping-only','old invalid cost','500')).toEqual({costCny:0,weightGrams:500})
  expect(()=>manualQuantity(price,1.5)).toThrow()
})
it('exports the saved manual inputs without false procurement or packaging descriptions', () => {
  for(const quoteMode of ['freight-trial','shipping-only'] as const){
    const record=normalizeQuotationRecord({id:quoteMode,no:'LOCAL-QA',quoteMode,manualPricing:{costCny:quoteMode==='shipping-only'?0:30,weightGrams:500}})!
    const before=JSON.stringify(record),tsv=quotationRecordReconciliationTsv(record)
    expect(tsv).toContain(quoteMode==='freight-trial'?'报价试算':'仅代发货报价')
    expect(tsv).toContain('手填重量（g/件）\t500')
    expect(tsv).toContain('不增加包材重量')
    expect(tsv).not.toContain('旧记录未保存');expect(tsv).not.toContain('采购原价')
    expect(JSON.stringify(record)).toBe(before)
  }
})
it('does not count trials as sales or represent shipping services as product SKUs', () => {
  const rows=(['freight-trial','shipping-only','single'] as const).map(quoteMode=>normalizeQuotationRecord({id:quoteMode,no:'QA',quoteMode,primarySku:'SKU-1'})!)
  expect(quotationSkus(rows[0]!)).toEqual([]);expect(quotationSkus(rows[1]!)).toEqual([])
  const filtered=filterQuotationRecords(rows,{keyword:'',startDate:'',endDate:'',country:'',salesperson:'',category:''},[])
  expect(filtered.map(r=>r.quoteMode)).toEqual(['shipping-only','single'])
})
