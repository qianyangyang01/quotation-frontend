import { expect, it } from 'vitest'
import { discountFobPrice, validateFobDiscount, fobQuantityQuote, fobSheetRows, fobTierQuotes, FOB_SMALL_ORDER_POLICY as policy, type FobQuoteProduct } from './fobQuotation'
import { fobBatchSnapshot } from './fobBatchQuotation'
import { correctionChanged } from './fobCorrections'
const product = (): FobQuoteProduct => ({sku:'DISCOUNT-TEST',category:'',weight:'60',source:'fob',notices:[],parsed:{minOrderQty:1,orderMultiple:1,priceTiers:[{minQty:1,maxQty:null,unitPriceCny:7.2,unit:'件'}],freight:{quantity:100,totalFreightCny:0,unitFreightCny:0,estimated:false,basis:'包邮'}}})
it('multiplies the existing final price and rounds half up, with default 1 unchanged',()=>{
  expect(discountFobPrice('100',.95)).toBe('95.00')
  expect(discountFobPrice('1.50',.95)).toBe('1.43')
  expect(discountFobPrice('100')).toBe('100.00')
  for(const bad of [0,-1,1.01,NaN,Infinity]) expect(()=>validateFobDiscount(bad)).toThrow('折扣')
  for(let cents=1;cents<=20000;cents+=7) {
    for(const percent of [1,50,95,99,100]) {
      const expected=Math.floor((cents*percent+50)/100)
      expect(discountFobPrice((cents/100).toFixed(2),percent/100)).toBe((expected/100).toFixed(2))
    }
  }
})
it('keeps surcharge thresholds and costs unchanged, discounts each final range exactly once',()=>{
  const p=product(), before=JSON.stringify(p), rows=fobTierQuotes(p,6.7)
  for(const q of ['1','25','26','1000']) {
    const base=fobQuantityQuote(p,rows,q,6.7,policy)
    expect(fobQuantityQuote(p,rows,q,6.7,policy,1)).toEqual(base)
    const discounted=fobQuantityQuote(p,rows,q,6.7,policy,.95)
    expect(discounted.declaredUsd).toBe(discountFobPrice(base.declaredUsd,.95))
    expect(discounted.undeclaredUsd).toBe(discountFobPrice(base.undeclaredUsd,.95))
    expect(discounted.extraCny).toBe(base.extraCny)
  }
  expect(fobSheetRows(p,6.7,policy,.95)).toEqual([
    {minQty:1,maxQty:25,unit:'件',declaredUsd:'1.44',undeclaredUsd:'1.47'},
    {minQty:26,maxQty:null,unit:'件',declaredUsd:'1.28',undeclaredUsd:'1.30'},
  ])
  expect(JSON.stringify(p)).toBe(before)
})
it('uses canonical prices for all three classifications without changing invoice calculations',()=>{
  for(const classification of ['rebate-fob','rebate-cfr-cif','ordinary-fob'] as const){
    const p=product(), rows=fobTierQuotes(p,6.7)
    p.classification=classification
    p.pricing={version:1,rate:6.7,rows:rows.map(r=>({...r,declaredUsd:'100.00',undeclaredUsd:'100.00',smallOrderUsd:'101.00'}))}
    expect(fobQuantityQuote(p,rows,'26',6.7,policy,.95).declaredUsd).toBe('95.00')
    expect(fobQuantityQuote(p,rows,'1',6.7,policy,.95).declaredUsd).toBe('95.95')
    expect(p.pricing.rows[0]!.declaredUsd).toBe('100.00')
  }
})
it('persists discount for each batch item, repeated SKU quantities and corrections',()=>{
  const p=product(), lines=['1','26'].map((quantity,i)=>({id:i+1,sku:p.sku,quantity,selected:true,formatError:'',product:p}))
  const meta={title:'Test',agent:'QA',date:'2026-11-20',whatsapp:''}
  const base=fobBatchSnapshot(lines,'quantity',6.7,policy,meta)
  const result=fobBatchSnapshot(lines,'quantity',6.7,policy,meta,.95)
  expect(result.items!.map(i=>i.discount)).toEqual([.95,.95])
  expect(result.sheet.rows.map(r=>r.prices)).toEqual([[1.44,1.47],[1.28,1.30]])
  expect(correctionChanged(base.items![0]!,result.items![0]!)).toBe(true)
  expect(base.sheet.rows[0]!.prices).toEqual([1.52,1.55])
  expect(()=>fobBatchSnapshot(lines,'quantity',6.7,policy,meta,0)).toThrow('折扣')
})
