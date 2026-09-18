import { describe, expect, it } from 'vitest'
import { customerOperationFeeForQuantity, fixedOperationFees, resolveCustomerOperation, validateCustomerOperationSettings, type OperationFeesByQuantity } from './customerOperationFees'
import { normalizeQuotationRecord } from './quotationRecords'
import { quotationRecordReconciliationTsv } from './quotationRecordReconciliation'

const tiers = { '1': .3, '2': .5, '3': .7, above3: .8 }
const customer = { id: 'c', name: '客户', enabled: true, feeUsd: .3, feesByQuantityUsd: tiers }
describe('quantity operation fees and immutable saved records', () => {
  it('selects tiers by pieces or sets without multiplication, and rejects unknown quantities', () => {
    expect([1,2,3,4,5,10].map(q => customerOperationFeeForQuantity(customer,q))).toEqual([.3,.5,.7,.8,.8,.8])
    for (const q of [0,-1,1.5,NaN,Infinity]) expect(customerOperationFeeForQuantity(customer,q)).toBeUndefined()
    expect(customerOperationFeeForQuantity(undefined,10)).toBe(0)
    expect(customerOperationFeeForQuantity({feeUsd:1.25},10)).toBe(1.25)
    expect(fixedOperationFees(0)).toEqual({'1':0,'2':0,'3':0,above3:0})
  })
  it('does not silently repair partial or invalid tiers using the legacy amount', () => {
    for (const value of [undefined,null,'',NaN,Infinity,-1,.001,1000001,'0.3']) {
      const bad = {...customer,feesByQuantityUsd:{...tiers,'2':value} as OperationFeesByQuantity}
      expect(() => validateCustomerOperationSettings({customers:[bad]})).toThrow()
      expect(resolveCustomerOperation({customers:[bad]},bad.id,bad.name).configured).toBe(false)
      expect(customerOperationFeeForQuantity(bad,1)).toBeUndefined()
    }
    expect(() => validateCustomerOperationSettings({customers:[{...customer,feesByQuantityUsd:fixedOperationFees(1_000_000)}]})).not.toThrow()
  })
  it('restores all snapshot tiers and exports each quantity without consulting current finance settings', () => {
    const selected=resolveCustomerOperation({customers:[customer]},customer.id,customer.name)
    const record=normalizeQuotationRecord({id:'r',no:'Q',quoteMode:'bundle',customerOperation:selected.snapshot,
      customQuoteQuantity:10, quoteOptions:[{id:'o',country:'美国',carrier:'测试商',channel:'测试渠道',rule:'测试规则',eta:'7天',quote1Usd:6,quote2Usd:10,quote3Usd:14,quoteCustomUsd:40}],
      systemQuantityQuotes:{quantities:[1,2,3,4,5,10],rows:[{optionId:'o',prices:[6,10,14,18,22,40]}]}})!
    selected.snapshot!.feesByQuantityUsd!['2']=9
    expect(record.customerOperation?.feesByQuantityUsd?.['2']).toBe(.5)
    const before=JSON.stringify(record), lines=quotationRecordReconciliationTsv(record).split('\n').map(line=>line.split('\t'))
    const start=lines.findIndex(row=>row[0]==='序号'), headers=lines[start]!, row=lines[start+1]!
    for(const [q,fee] of [[1,.3],[2,.5],[3,.7],[4,.8],[5,.8],[10,.8]]) expect(row[headers.indexOf(q+'套操作费（USD/单）')]).toBe(fee!.toFixed(2))
    expect(JSON.stringify(record)).toBe(before)
    const old=normalizeQuotationRecord({id:'old',no:'OLD',customerOperation:{id:'c',name:'客户',feeUsd:1.25}})!
    expect(old.customerOperation?.feesByQuantityUsd).toBeUndefined()
    expect(customerOperationFeeForQuantity(old.customerOperation,10)).toBe(1.25)
  })
})
