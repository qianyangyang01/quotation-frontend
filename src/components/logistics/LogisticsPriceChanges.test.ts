// @vitest-environment happy-dom
import {createApp,h,nextTick,shallowRef} from 'vue'
import {expect,it} from 'vitest'
import LogisticsPriceChanges from './LogisticsPriceChanges.vue'
import mapping from '@/data/logisticsPriceWatchlistBindings.json'
import type {RealPriceSnapshot} from '@/data/logisticsRealPriceHistory'
import type {LogisticsPriceRow} from '@/data/logistics'

it('keeps weight, search, comparison and the open detail drawer when a new published snapshot arrives',async()=>{
 const binding=mapping.bindings['云途普货CHC']
 const version=(n:number)=>({id:'v'+n,channel:binding.id,number:n,status:'published',publishedAt:`2026-10-${10+n}T01:00:00Z`,fileName:'价格'+n,
  rows:[{countryCode:'DE',areaName:'德国',zoneName:'',pricePerKg:10*n,registrationFee:5,weightFromKg:0,weightToKg:10,pricingModel:'per-kg',minChargeWeightKg:0} as LogisticsPriceRow]})
 const snapshot=shallowRef<RealPriceSnapshot>({capturedAt:'2026-10-28T01:00:00Z',dataset:'test',channels:[{...binding,ruleId:1,code:'CHC',current:'v2',attribute:'普货',enabled:true,quoteReady:true}],versions:[version(1),version(2)]})
 const host=document.createElement('div');document.body.append(host)
 const app=createApp({render:()=>h(LogisticsPriceChanges,{snapshot:snapshot.value,refreshing:false,error:''})});app.mount(host)
 try{
  const weight=host.querySelector<HTMLSelectElement>('select[aria-label="重量"]')!
  expect(weight.value).toBe('1');weight.value='2';weight.dispatchEvent(new Event('change'))
  const compare=host.querySelector<HTMLSelectElement>('select[aria-label="比较版本"]')!
  compare.value='previousOlder';compare.dispatchEvent(new Event('change'))
  const search=host.querySelector<HTMLInputElement>('.channel-search')!
  search.value='CHC';search.dispatchEvent(new Event('input'));await nextTick()
  host.querySelector<HTMLButtonElement>('.detail-button')!.click();await nextTick()
  snapshot.value={...snapshot.value,capturedAt:'2026-10-28T02:00:00Z',channels:snapshot.value.channels.map(c=>({...c,current:'v3'})),versions:[...snapshot.value.versions,version(3)]}
  await nextTick()
  expect(weight.value).toBe('2');expect(compare.value).toBe('previousOlder');expect(search.value).toBe('CHC')
  expect(host.querySelector('[role="dialog"]')).not.toBeNull()
  expect(host.querySelector('.card-price strong')!.textContent).toContain('65.00')
  expect(host.querySelector('.card-change')!.textContent).toContain('+20.00')
 }finally{app.unmount();host.remove()}
})
