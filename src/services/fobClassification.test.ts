// @vitest-environment happy-dom
import { afterEach, expect, it } from 'vitest'
import { authState } from '@/data/authStore'
import { canViewFobCoefficient, isFobSpecialInvoice } from './fobClassification'
import { fobQuantityQuote, fobTierQuotes, FOB_SMALL_ORDER_POLICY, type FobQuoteProduct } from './fobQuotation'
import { fobBatchSnapshot } from './fobBatchQuotation'
const product=():FobQuoteProduct=>({sku:'SKU001',category:'示例',weight:'100g',source:'fob',classification:'rebate-fob',invoiceType:'专票',purchasePriceBasis:'tax_included',notices:[],invoicePricing:{mode:'included',basePurchaseCny:22,baseIncludedCny:22,confirmed:true,policyVersion:2},parsed:{minOrderQty:1,orderMultiple:1,priceTiers:[{minQty:1,maxQty:null,unitPriceCny:22,unit:'件'}],freight:{quantity:100,totalFreightCny:60,unitFreightCny:.6,basis:'百件60元',estimated:false}},pricing:{version:3,rate:7.2,rows:[{minQty:1,maxQty:null,unit:'件',purchaseCny:22,taxIncludedCny:22,freightCny:.6,costCny:22.6,declaredUsd:'3.39',undeclaredUsd:'3.39',smallOrderUsd:'3.54',thresholdQty:9}]}})
afterEach(()=>{authState.current=null;authState.permissions=[]})
it('uses server prices without receiving a coefficient and preserves the small-order threshold',()=>{
 const p=product(), rows=fobTierQuotes(p,7.2)
 expect(rows[0]?.costCny).toBe(22.6)
 expect(fobQuantityQuote(p,rows,'8',7.2,FOB_SMALL_ORDER_POLICY).declaredUsd).toBe('3.54')
 expect(fobQuantityQuote(p,rows,'9',7.2,FOB_SMALL_ORDER_POLICY).declaredUsd).toBe('3.39')
 expect(JSON.stringify(p)).not.toContain('fobCoefficient')
 expect(()=>fobTierQuotes(p,6.63)).toThrow('重新查询')
})
it('does not allow ordinary invoice data to be used in either rebate category',()=>{
 for(const kind of ['rebate-fob','rebate-cfr-cif'] as const)for(const invoice of ['普票','无票','未确认']){
  const p=product();p.classification=kind;p.invoiceType=invoice
  expect(()=>fobTierQuotes(p,7.2)).toThrow('专票')
 }
 const p=product();p.invoicePricing!.confirmed=false;expect(()=>fobTierQuotes(p,7.2)).toThrow('专票')
 p.classification='ordinary-fob';expect(fobTierQuotes(p,7.2)).toHaveLength(1)
})
it('outputs one final price per SKU and keeps the category in a batch snapshot',()=>{
 const p=product()
 const result=fobBatchSnapshot([{id:1,sku:p.sku,quantity:'100',selected:true,formatError:'',product:p}],'quantity',7.2,FOB_SMALL_ORDER_POLICY,{title:'Quote',agent:'Sales',date:'2026-11-13',whatsapp:''})
 expect(result.sheet.quantityLabels).toEqual(['Unit Price'])
 expect(result.sheet.rows[0]?.prices).toEqual([3.39])
 expect(result.items?.[0]?.product.classification).toBe('rebate-fob')
})
it('finance permission alone cannot expose FOB coefficients',()=>{
 for(const role of ['employee','finance','purchase','super_admin'] as const){
  authState.current={id:role,account:role,name:role,role,status:'enabled',mustChangePassword:false,passwordUpdatedAt:''}
  authState.permissions=['finance','quote','purchase','allRecords']
  expect(canViewFobCoefficient.value).toBe(role==='super_admin')
 }
})


it('recognizes explicit special invoice labels but rejects conditional mixed text',()=>{
 for(const name of ['专票13','专票１０％','专票3个点','增值税专用发票'])expect(isFobSpecialInvoice(name)).toBe(true)
 for(const name of ['非专票','专票待确认','普票/专票13','专票13满1000开'])expect(isFobSpecialInvoice(name)).toBe(false)
})

it('allows missing invoice or ticket data on both sources only with the server default rule',()=>{
 for(const source of ['fob','standard'] as const){
  const p=product();p.source=source;p.invoiceType='';p.pricing!.policyVersion=4;
  p.invoicePricing={mode:'default-tax-point',policyVersion:4,basePurchaseCny:20,effectiveTaxPoint:.1,verificationRequired:true,ruleSource:'missing-invoice-default',missingFields:['票类型','票点']};
  expect(fobTierQuotes(p,7.2)).toHaveLength(1);
  p.invoiceType='普票';expect(()=>fobTierQuotes(p,7.2)).toThrow('票据');
  p.invoiceType='专票13';p.invoicePricing.verificationRequired=false;p.invoicePricing.ruleSource='procurement-tax-point';p.invoicePricing.mode='actual-tax-point';p.invoicePricing.effectiveTaxPoint=.13;
  expect(fobTierQuotes(p,7.2)).toHaveLength(1);
 }
})
