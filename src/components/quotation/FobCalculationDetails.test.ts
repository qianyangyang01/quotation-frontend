// @vitest-environment happy-dom
import { createApp, reactive, nextTick, type App } from 'vue'
import { beforeEach, afterEach, expect, it } from 'vitest'
import Details from './FobCalculationDetails.vue'
import { setPricingTestPermissions, clearPricingTestPermissions } from '@/test/pricingPermissions'
import { authState } from '@/data/authStore'
import { fobTierQuotes, type FobQuoteProduct } from '@/services/fobQuotation'
let app:App|undefined
const row={minQty:1,maxQty:null,unit:'件',purchaseCny:45,taxIncludedCny:49.5,freightCny:.6,costCny:50.1,declaredUsd:'7.52',undeclaredUsd:'7.52',smallOrderUsd:'7.67',thresholdQty:4}
const product=():FobQuoteProduct=>({sku:'OLD001',source:'fob',category:'',weight:'',notices:[],classification:'rebate-fob',invoiceType:'',invoicePricing:{mode:'legacy-ten-point',policyVersion:3,basePurchaseCny:45,baseIncludedCny:49.5,assumedTaxPoint:.1,ruleSource:'legacy-fob-default'},pricing:{version:1,policyVersion:3,rate:7.2,fobCoefficient:1.08,rows:[row]},parsed:{minOrderQty:1,orderMultiple:1,priceTiers:[{minQty:1,maxQty:null,unit:'件',unitPriceCny:45}],freight:{quantity:100,totalFreightCny:60,unitFreightCny:.6,basis:'百件运费',estimated:false}}})
beforeEach(()=>setPricingTestPermissions())
afterEach(()=>{app?.unmount();document.body.innerHTML='';clearPricingTestPermissions()})
function mount(extraCny=0){const host=document.createElement('div');document.body.append(host);app=createApp(Details,{product:product(),rate:7.2,row,quantity:extraCny?1:100,extraCny,finalUsd:extraCny?'7.67':'7.52'});app.mount(host);return host.textContent!}
it('shows the assumed rule separately without pretending procurement was confirmed',()=>{
 const p=product();expect(fobTierQuotes(p,7.2)[0]?.taxIncludedCny).toBe(49.5)
 const text=mount();expect(text).toContain('未修改或回填 FOB 采购数据');expect(text).toContain('45.00 ×（1 + 10%）= ¥49.50');expect(text).not.toContain('1.08')
})
it('shows full final calculation and surcharge only to administrators',()=>{
 authState.current!.role='super_admin';const text=mount(1);expect(text).toContain('（50.10 + 1.00） × 1.08 ÷ 7.20 = $7.67');expect(text).toContain('每件 ¥1.00 小额订单加价')
})
it('respects existing purchase-cost permission as well as coefficient privacy',()=>{
 setPricingTestPermissions(['quote','myRecords']);const text=mount();expect(text).toContain('系统统一按10点专票计价');expect(text).toContain('$7.52');expect(text).not.toContain('49.50');expect(text).not.toContain('1.08')
})
it('does not treat a standard-source fake legacy marker as an invoice exception',()=>{
 const p=product();p.source='standard';expect(()=>fobTierQuotes(p,7.2)).toThrow('专票')
})

it('keeps the source SKU reminder until a fresh resolved policy replaces the incomplete one',async()=>{
 const p=reactive(product());p.source='standard';p.pricing!.policyVersion=4;p.invoicePricing={mode:'default-tax-point',policyVersion:4,basePurchaseCny:45,effectiveTaxPoint:.1,verificationRequired:true,ruleSource:'missing-invoice-default',missingFields:['票类型','票点']};
 const host=document.createElement('div');document.body.append(host);app=createApp(Details,{product:p,rate:7.2,row,quantity:100,finalUsd:'7.52'});app.mount(host);
 expect(host.textContent).toContain('【系统新采购数据】中 SKU OLD001');expect(host.querySelector('.legacy-invoice-notice')).not.toBeNull();
 p.category='仅改名称';p.updatedAt='new-metadata-version';await nextTick();expect(host.querySelector('.legacy-invoice-notice')).not.toBeNull();
 p.invoiceType='专票13';await nextTick();expect(host.querySelector('.legacy-invoice-notice')).not.toBeNull();
 p.invoicePricing={mode:'actual-tax-point',policyVersion:4,basePurchaseCny:45,effectiveTaxPoint:.13,verificationRequired:false,ruleSource:'procurement-tax-point'};await nextTick();expect(host.querySelector('.legacy-invoice-notice')).toBeNull();expect(host.textContent).toContain('1 + 13%');expect(host.textContent).not.toContain('1.08');
})
