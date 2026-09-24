import { describe,expect,it } from 'vitest'
import { reactive } from 'vue'
import { normalizeQuotationRecord } from './quotationRecords'
import { priceComparison, recordCustomerPrices, representativePriceDifference } from './customerQuotePrices'
const snapshot=(prices:(number|null)[])=>({quantities:[1,2,4],rows:[{optionId:'yanwen-a',prices}]})
export function exampleRecord() {return normalizeQuotationRecord({id:'r',no:'QT-r',salespersonAccount:'QA',customQuoteQuantity:4,
  quoteOptions:[{id:'yanwen-a',country:'US',carrier:'Yanwen',channel:'A',rule:'',eta:'5-8 days',quote1Usd:2,quote2Usd:3,quote3Usd:null,quoteCustomUsd:5}],
  systemQuantityQuotes:snapshot([2,3,5]),sheetQuote:snapshot([1.8,2.7,4.6]),customerQuote:snapshot([1.8,2.7,4.6])})!}
describe('original system vs final customer price',()=>{
  it('round trips hidden option identities and clones them for price edits, including an explicit restore', () => {
    const raw = exampleRecord()
    raw.customerQuote!.hiddenOptionIds = ['yanwen-a']
    raw.sheetQuote!.hiddenOptionIds = ['yanwen-a']
    const saved = normalizeQuotationRecord(JSON.parse(JSON.stringify(raw)))!
    const draft = recordCustomerPrices(saved)
    expect(draft.hiddenOptionIds).toEqual(['yanwen-a'])
    draft.hiddenOptionIds!.splice(0)
    expect(saved.customerQuote!.hiddenOptionIds).toEqual(['yanwen-a'])
    saved.customerQuote = draft
    expect(recordCustomerPrices(saved).hiddenOptionIds).toEqual([])
    delete saved.customerQuote.hiddenOptionIds
    expect(recordCustomerPrices(saved).hiddenOptionIds).toEqual(['yanwen-a'])
    expect(saved.quoteOptions).toEqual(raw.quoteOptions)
  })
  it('preserves saved contacts on record normalization and price draft cloning', () => {
    const raw = exampleRecord()
    raw.customerQuote!.contact = { agent: 'Vivian', whatsapp: '+183 5650 6953' }
    const saved = normalizeQuotationRecord(JSON.parse(JSON.stringify(raw)))!
    expect(saved.customerQuote!.contact).toEqual(raw.customerQuote!.contact)
    const draft = recordCustomerPrices(saved)
    draft.contact!.agent = 'Changed'
    expect(saved.customerQuote!.contact!.agent).toBe('Vivian')
  })
  it('uses initial quote sheet edits until a record edit is saved, then compares final prices with the immutable original',()=>{
    const record=exampleRecord(),before=JSON.stringify(record.systemQuantityQuotes)
    expect(priceComparison(record).map(l=>[l.customer,l.difference,l.percent])).toEqual([[1.8,-.2,-10],[2.7,-.3,-10],[4.6,-.4,-8]])
    record.customerQuote!.rows[0].prices[1]=2.6
    expect(priceComparison(record).map(l=>[l.customer,l.difference,l.percent,l.recordEdited])).toEqual([[1.8,-.2,-10,false],[2.6,-.4,-13.33,true],[4.6,-.4,-8,false]])
    expect(JSON.stringify(record.systemQuantityQuotes)).toBe(before)
  })
  it('shows the smallest edited quantity, not the first displayed column or the biggest discount',()=>{
    const record=exampleRecord(); record.customerQuote!.quantities=[4,2,1];record.customerQuote!.rows[0].prices=[4.6,2.7,2]
    expect(representativePriceDifference(record).label).toContain('2件')
    record.customerQuote!.rows[0].prices=[5,3,2]
    expect(representativePriceDifference(record).label).toBe('客户报价＝系统报价')
  })
  it('falls back to the sheet snapshot when the record has not saved a customer override',()=>{
    const record=exampleRecord();delete record.customerQuote
    expect(recordCustomerPrices(record).rows[0].prices).toEqual([1.8,2.7,4.6])
    expect(representativePriceDifference(record).label).toBe('1件 · -$0.20')
  })
  it('supports reactive snapshots, zero prices, blank prices and added quantities without dividing by zero or inferring original prices',()=>{
    const record=reactive(exampleRecord()); record.systemQuantityQuotes!.rows[0].prices[0]=0
    record.customerQuote!.quantities=[1,2,50];record.customerQuote!.rows[0].prices=[0,null,99]
    const lines=priceComparison(record)
    expect(lines[0]).toMatchObject({difference:0,percent:null,changed:false})
    expect(lines[1]).toMatchObject({customer:null,difference:null})
    expect(lines[2]).toMatchObject({system:null,difference:null,percent:null})
    const clone=recordCustomerPrices(record);clone.rows[0].prices[0]=80
    expect(record.customerQuote!.rows[0].prices[0]).toBe(0)
  })
  it('retains regions and channel identities even when labels match and handles historical records without customer snapshots',()=>{
    const record=exampleRecord();delete record.customerQuote;delete record.sheetQuote;delete record.systemQuantityQuotes
    record.quoteOptions!.push({...record.quoteOptions![0],id:'yanwen-b',quoteRegion:'region2',quote2Usd:9})
    expect(priceComparison(record).every(l=>!l.changed)).toBe(true)
    const customer=recordCustomerPrices(record);customer.rows[1].prices[1]=8;record.customerQuote=customer
    const edited=priceComparison(record).filter(l=>l.changed)
    expect(edited).toHaveLength(1);expect(edited[0].option.id).toBe('yanwen-b');expect(edited[0].difference).toBe(-1)
  })
})
