// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, reactive, type App } from 'vue'
import Preview from './QuotationPreviewSave.vue'
vi.mock('./CustomerQuoteSheet.vue',()=>({default:{template:'<div />'}}))
let app:App
afterEach(()=>{app?.unmount();document.body.innerHTML=''})
it('defaults ordinary, binds the choice beside save, and prevents changes during submission',async()=>{
  const state=reactive({priority:false,saving:false});const host=document.createElement('div');document.body.append(host)
  app=createApp({render:()=>h(Preview,{rows:[],countries:[],salesperson:'ME',contextKey:'test',sourcePending:false,matrixModeLabel:'快速报价',customerName:'客户',productName:'商品',sku:'SKU',customerGrade:'S',coefficient:1,customQuantity:3,unitLabel:'件',exchangeRate:6.7,primaryCountry:'',primaryCarrier:'',primaryRule:'',primaryCnyPrice:0,primaryUsdPrice:0,priorityProcessing:state.priority,saving:state.saving,'onUpdate:priorityProcessing':(value:boolean)=>{state.priority=value}})})
  app.mount(host);await nextTick()
  const checkbox=host.querySelector<HTMLInputElement>('footer input[type=checkbox]')!
  expect(checkbox.checked).toBe(false);expect(checkbox.closest('label')?.nextElementSibling?.classList.contains('save')).toBe(true)
  checkbox.click();await nextTick();expect(state.priority).toBe(true)
  state.saving=true;await nextTick();expect(checkbox.disabled).toBe(true)
  state.saving=false;state.priority=false;await nextTick();expect(checkbox.checked).toBe(false)
})
