// @vitest-environment happy-dom
import { createApp, nextTick, reactive, type App } from 'vue'
import { afterEach, expect, it } from 'vitest'
import Fields from './FobInvoiceFields.vue'
let app:App|undefined
afterEach(()=>{app?.unmount();document.body.innerHTML=''})
it('preserves old metadata until source changes without offering a separate reminder dismissal checkbox',async()=>{
 const record=reactive({invoiceType:'专票13',purchasePriceBasis:'quoted',taxIncludedPriceCny:60,invoiceVerificationNote:'核实说明',fobInvoiceRatioConfirmed:true,fobInvoiceConfirmedFingerprint:'old'})
 const signature=()=>JSON.stringify([record.invoiceType,record.purchasePriceBasis,record.taxIncludedPriceCny])
 const host=document.createElement('div');document.body.append(host)
 app=createApp({components:{Fields},setup:()=>({record,signature}),template:'<Fields :record="record" :price-signature="signature()" show-included />'});app.mount(host);await nextTick()
 expect(record.fobInvoiceRatioConfirmed).toBe(true)
 record.invoiceVerificationNote='补充供应商说明';await nextTick();expect(record.fobInvoiceRatioConfirmed).toBe(true)
 record.taxIncludedPriceCny=61;await nextTick();expect(record.fobInvoiceRatioConfirmed).toBe(false);expect(record.fobInvoiceConfirmedFingerprint).toBeUndefined()
 expect(document.querySelector('[type=checkbox]')).toBeNull();expect(host.textContent).toContain('实际票点')
 record.invoiceType='普票';await nextTick();expect(record.fobInvoiceRatioConfirmed).toBe(false)
})
it('clears an included price as null instead of retaining an old value or coercing it to zero',async()=>{
 const record=reactive({taxIncludedPriceCny:60 as number|null});const host=document.createElement('div');document.body.append(host)
 app=createApp(Fields,{record,priceSignature:'initial',showIncluded:true});app.mount(host)
 const input=document.querySelector<HTMLInputElement>('[aria-label="FOB起步含票价"]')!;input.value='';input.dispatchEvent(new Event('input',{bubbles:true}));await nextTick();expect(record.taxIncludedPriceCny).toBeNull()
})
