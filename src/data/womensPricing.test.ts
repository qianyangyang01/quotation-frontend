import { describe, expect, it } from 'vitest'
import { defaultWomensPricing, womensPricingError, womensPricingSample } from './womensPricing'
import { normalizeQuotationMode, isBundleQuotation, isSingleQuotation } from './quotationModes'
import { normalizeQuotationRecord } from './quotationRecords'
import { quotationReissuePayload } from '@/services/quotationReissue'
import { quotationRecordQuoteSheetSource } from './quotationRecordQuoteSheet'
import { validateQuotationConditions } from '@/services/quotationValidation'
import { quotationDetailsCsv } from './quotationAnalytics'
import { quotationRecordReconciliationTsv } from './quotationRecordReconciliation'

describe('女装分档计价', () => {
  it.each([[19.999999,1.27],[20,1.25],[29.999999,1.25],[30,1.23],[49.999999,1.23],[50,1.22],[100,1.22]])('uses unrounded USD %s at coefficient %s', (amount, expected) => {
    expect(womensPricingSample(defaultWomensPricing(),amount,1).coefficient).toBe(expected)
  })
  it('does not round exchange conversion into the next tier or apply the customer grade twice', () => {
    expect(womensPricingSample(defaultWomensPricing(),139.999,7).coefficient).toBe(1.27)
    expect(womensPricingSample(defaultWomensPricing(),140,7).baseQuoteCny).toBe(175)
    expect(womensPricingSample(defaultWomensPricing(),175,7,2)).toMatchObject({coefficient:1.25,quantity:2,baseQuoteCny:218.75})
  })
  it('rejects malformed policies and disabled settings', () => {
    for (const change of [(p:ReturnType<typeof defaultWomensPricing>)=>p.tiers[1]!.fromUsd=19, (p:ReturnType<typeof defaultWomensPricing>)=>p.tiers[0]!.coefficient=0, (p:ReturnType<typeof defaultWomensPricing>)=>p.tiers[0]!.toUsd=30, (p:ReturnType<typeof defaultWomensPricing>)=>p.tiers[3]!.toUsd=100]) {
      const p=defaultWomensPricing();change(p);expect(womensPricingError(p)).not.toBe('')
    }
    const p=defaultWomensPricing();p.enabled=false
    expect(()=>womensPricingSample(p,10,7)).toThrow('停用')
  })
  it.each(['womens-single','womens-bundle'] as const)('preserves %s identity through records, reissue and customer sheets', mode => {
    const snapshot={...defaultWomensPricing(),version:3}
    const record=normalizeQuotationRecord({id:'w',no:'W1',quoteMode:mode,customerName:'客户',primarySku:mode==='womens-bundle'?'A、B':'A',customerGrade:'女装自动系数',womensPricing:snapshot,
      bundleItems:mode==='womens-bundle'?[{sku:'A',name:'上衣',quantityPerSet:1,effectiveWeightKg:.1,purchaseUnitPriceCny:10,domesticFreightPerUnitCny:1},{sku:'B',name:'裙',quantityPerSet:1,effectiveWeightKg:.1,purchaseUnitPriceCny:20,domesticFreightPerUnitCny:1}]:undefined,
      quoteOptions:[{id:'o',country:'美国',carrier:'测试',channel:'测试',rule:'测试',eta:'7天',quote1Usd:10,quote2Usd:20,quote3Usd:30,quoteCustomUsd:40,womensPricingSamples:[womensPricingSample(snapshot,140,7)]}]})!
    expect(normalizeQuotationMode(mode)).toBe(mode)
    expect(isSingleQuotation(mode)).toBe(mode==='womens-single');expect(isBundleQuotation(mode)).toBe(mode==='womens-bundle')
    const before=JSON.stringify(record)
    const reissue=quotationReissuePayload(record)
    expect(reissue.quoteMode).toBe(mode);expect(reissue).not.toHaveProperty('womensPricing')
    expect(quotationRecordQuoteSheetSource(record).bundle).toBe(mode==='womens-bundle')
    expect(record.womensPricing?.version).toBe(3);expect(record.quoteOptions?.[0]?.womensPricingSamples?.[0]?.coefficient).toBe(1.25)
    const csv=quotationDetailsCsv([record],[],{includeCost:true})
    expect(csv).toContain('女装规则版本');expect(csv).toContain('V4');expect(csv).toContain('1.27')
    expect(quotationDetailsCsv([record],[],{includeCost:false})).not.toContain('1.27')
    expect(quotationRecordReconciliationTsv(record)).toContain('女装规则版本\tV4')
    expect(JSON.stringify(record)).toBe(before)
  })
  it('only women bypass customer grade selection', () => {
    const input={customerName:'客户',quoteMode:'womens-single',sku:'A',productCategory:'女装',logisticsAttribute:'普货',allowedLogisticsAttributes:['普货'],customerGrade:'',enabledCustomerGrades:[],monthlySalesEstimate:'10'}
    expect(validateQuotationConditions(input,{includeSku:true,includeCategory:true})).toEqual([])
    expect(validateQuotationConditions({...input,quoteMode:'single'},{includeSku:true,includeCategory:true})).toContainEqual(expect.objectContaining({key:'customerGrade'}))
  })
})
