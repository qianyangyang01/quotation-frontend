import { expect, it } from 'vitest'
import { buildQuotationWeightSnapshot, normalizeQuotationWeightSnapshot, parseSpecialPackagingGrams } from './quotationWeightSnapshot'
import { bundleGoodsWeight, singleActualWeight, type SingleWeightInput } from '@/services/quotationCalculator'
import { normalizeQuotationRecord } from './quotationRecords'
import { quotationRecordReconciliationTsv } from './quotationRecordReconciliation'
import { normalizePurchaseRecord } from './purchaseStore'

it('quotes fractional gram procurement weights without importing the old kg floating point tail', () => {
  const raw = { sku:'FL2600257', weightG:39.7, weightKg:0.039700000000000006 }
  const product = normalizePurchaseRecord(raw)
  expect(product.weightKg).toBe(0.0397)
  const snapshot = buildQuotationWeightSnapshot([{sku:product.sku, quantityPerSet:1, baseWeightKg:product.weightKg!}], 0, [1,2,3,7])
  expect(snapshot.quantities.map(row=>row.weightKg)).toEqual([0.0407,0.0814,0.1221,0.2849])
  for (const row of snapshot.quantities) {
    expect(singleActualWeight({...single,netWeight:product.weightKg!}, row.quantity)).toBe(row.weightKg)
  }
  expect(raw.weightKg).toBe(0.039700000000000006)
})

it('keeps fractional gram bundle weights and one-time special packaging consistent', () => {
  const items = [
    {sku:'FL2600257',quantityPerSet:2,baseWeightKg:normalizePurchaseRecord({weightG:39.7}).weightKg!},
    {sku:'SECOND',quantityPerSet:1,baseWeightKg:normalizePurchaseRecord({weightG:50.001}).weightKg!},
  ]
  const snapshot = buildQuotationWeightSnapshot(items, 13, [1,2,3])
  expect(snapshot.quantities.map(row=>row.weightKg)).toEqual([0.146401,0.279802,0.413203])
  expect(snapshot.quantities.map(row=>row.standardPackagingWeightKg)).toEqual([0.004,0.008,0.012])
  for (const row of snapshot.quantities) {
    expect(bundleGoodsWeight(items.map(item=>({...item,weightKg:item.baseWeightKg,customWeightKg:null,purchaseUnitPrice:0,purchaseFreightPerUnit:0})),row.quantity,.013)).toBe(row.weightKg)
  }
})

const single = { quantity: 1, netWeight: .14, weightSource: 'purchase', manualWeight: 0 } as SingleWeightInput
it('retains weight provenance through serialization, record normalization and reconciliation without repricing', () => {
  const item = {sku:'A',quantityPerSet:1,baseWeightKg:.315,weightSource:'manual' as const,purchaseWeightKg:.3}
  const snapshot = buildQuotationWeightSnapshot([item], 0, [1,2,3])
  const record = normalizeQuotationRecord(JSON.parse(JSON.stringify({id:'source',no:'QT-SOURCE',weightSnapshot:snapshot,systemQuoteUsd:42.6})))!
  item.purchaseWeightKg = .8
  expect(record.weightSnapshot!.items[0]).toMatchObject({weightSource:'manual',purchaseWeightKg:.3,baseWeightKg:.315})
  expect(record.weightSnapshot!.quantities.map(row=>row.weightKg)).toEqual([.322,.644,.966])
  expect(record.systemQuoteUsd).toBe(42.6)
  expect(quotationRecordReconciliationTsv(record)).toContain('业务指定重量\t0.3')
})
it('preserves old snapshots as unknown and ignores malformed optional provenance', () => {
  const old = buildQuotationWeightSnapshot([{sku:'A',quantityPerSet:1,baseWeightKg:.315}], 0, [1])
  expect(normalizeQuotationWeightSnapshot(old)).toEqual(old)
  expect(quotationRecordReconciliationTsv(normalizeQuotationRecord({id:'old-source',no:'OLD',weightSnapshot:old})!)).toContain('来源未记录')
  const malformed = {...old,items:[{...old.items[0],weightSource:'untrusted',purchaseWeightKg:-1}]}
  expect(normalizeQuotationWeightSnapshot(malformed)).toEqual(old)
  expect(malformed.items[0]!.weightSource).toBe('untrusted')
})
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
