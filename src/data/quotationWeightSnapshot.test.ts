import { expect, it } from 'vitest'
import { buildQuotationWeightSnapshot, normalizeQuotationWeightSnapshot, parseSpecialPackagingGrams } from './quotationWeightSnapshot'
import { bundleGoodsWeight, singleActualWeight, type SingleWeightInput } from '@/services/quotationCalculator'
import { normalizeQuotationRecord } from './quotationRecords'
import { quotationRecordReconciliationTsv } from './quotationRecordReconciliation'

const single = { quantity: 1, netWeight: .14, weightSource: 'purchase', manualWeight: 0 } as SingleWeightInput
it.each(['single', 'bundle'])('adds special packaging once per shipment and preserves the %s saved basis', mode => {
  const items = mode === 'single' ? [{sku:'A',quantityPerSet:1,baseWeightKg:.14}]
    : [{sku:'A',quantityPerSet:2,baseWeightKg:.05},{sku:'B',quantityPerSet:1,baseWeightKg:.050001}]
  const quantities = [1,2,3,5,8,10]
  const snapshot = buildQuotationWeightSnapshot(items, 10, quantities)
  for (const row of snapshot.quantities) {
    const q = row.quantity
    // The bundle packages each physical item; it must not ceil the combined base.
    const base = mode === 'single' ? 140 : 150.001
    const ordinary = mode === 'single' ? 3 : 4
    expect(row.baseWeightKg).toBeCloseTo(base*q/1000, 12)
    expect(row.standardPackagingWeightKg).toBe(ordinary*q/1000)
    expect(row.specialPackagingWeightKg).toBe(.01)
    expect(row.weightKg).toBeCloseTo(((base+ordinary)*q+10)/1000,12)
    const actual = mode === 'single' ? singleActualWeight(single,q,.01) : bundleGoodsWeight(items.map(item=>({
      ...item,weightKg:item.baseWeightKg,customWeightKg:null,purchaseUnitPrice:0,purchaseFreightPerUnit:0,
    })),q,.01)
    expect(actual).toBe(row.weightKg)
  }
  const record = normalizeQuotationRecord({id:'test',no:'QT-1',weightSnapshot:snapshot,systemQuoteUsd:6.35,exchangeRate:6.7})!
  snapshot.quantities[0]!.weightKg = 999
  expect(record.weightSnapshot!.quantities[0]!.weightKg).not.toBe(999)
  const text = quotationRecordReconciliationTsv(record)
  expect(text).toContain('特殊包装（g/票）\t10')
  expect(text).toContain('整票一次')
  expect(record.systemQuoteUsd).toBe(6.35)
})
it.each([['',0],[undefined,0],[0,0],['10',10],[' 25 ',25],[100000,100000],['1.5',null],[-1,null],[100001,null],[null,null],['abc',null],[' ',null],[NaN,null],[Infinity,null]])('validates special packaging %j', (input,expected) => {
  expect(parseSpecialPackagingGrams(input)).toBe(expected)
})
it('never synthesizes new weight rules or prices for historical records', () => {
  expect(normalizeQuotationWeightSnapshot(undefined)).toBeUndefined()
  const old = normalizeQuotationRecord({id:'old',no:'QT-OLD',systemQuoteUsd:42.60,quoteOptions:[{id:'old',country:'美国',carrier:'4PX',channel:'QC',rule:'QC',eta:'7 days',quote1Usd:42.6,quote2Usd:80,quote3Usd:120,quoteCustomUsd:200,logisticsInput:{country:'美国',weightKg:.292,marks:[]}}]})!
  expect(old.weightSnapshot).toBeUndefined()
  expect(old.quoteOptions![0]!.logisticsInput!.weightKg).toBe(.292)
  expect(old.systemQuoteUsd).toBe(42.60)
  expect(quotationRecordReconciliationTsv(old)).toContain('原重量及报价不回算')
})
