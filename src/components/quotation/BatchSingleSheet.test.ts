// @vitest-environment happy-dom
import { createApp, nextTick, type App } from 'vue'
import { afterEach, expect, it, vi } from 'vitest'
import Sheet from './BatchSingleSheet.vue'
import type { CustomerQuoteSheet } from '@/data/customerQuoteSheet'
import { renderCustomerQuoteSheet } from '@/services/customerQuoteSheetRenderer'
vi.mock('@/services/customerQuoteSheetRenderer', () => ({ renderCustomerQuoteSheet:vi.fn().mockResolvedValue([]), copyQuoteSheetImage:vi.fn() }))
let app: App
const button=(label:string)=>[...document.querySelectorAll('button')].find(b=>b.textContent===label)!
afterEach(()=>{app?.unmount();document.body.innerHTML='';vi.restoreAllMocks()})
it('paginates and searches all SKUs without trimming exports or mutating the full sheet',async()=>{
 const sheet:CustomerQuoteSheet={agent:'QA',date:'2026-10-11',quantityLabels:['1 pc'],issues:[],rows:Array.from({length:60},(_,i)=>({key:String(i),number:i+1,sku:`SKU-${Math.floor(i/3)}`,country:'US',provider:'4PX',shippingTime:'7-12 days',prices:[i+1],sourceDescription:''}))}
 const before=JSON.stringify(sheet),writeText=vi.fn().mockResolvedValue(undefined)
 vi.stubGlobal('navigator',{clipboard:{writeText}})
 vi.stubGlobal('isSecureContext',true)
 const host=document.createElement('div');document.body.append(host);app=createApp(Sheet,{sheet,paginated:true});app.mount(host);await nextTick()
 expect(document.querySelectorAll('tbody tr')).toHaveLength(12)
 button('下一页').click();await nextTick();expect(document.querySelector('tbody tr')!.textContent).toContain('SKU-4')
 const input=document.querySelector<HTMLInputElement>('input')!;input.value='sku-19';input.dispatchEvent(new Event('input'));await nextTick()
 expect(document.querySelectorAll('tbody tr')).toHaveLength(3)
 expect(document.querySelector('tbody td')!.textContent).toBe('58')
 button('复制报价数据').click();for(let i=0;i<10;i++)await nextTick()
 expect(writeText).toHaveBeenCalledOnce()
 expect(writeText.mock.calls[0]![0].split('\n')).toHaveLength(61)
 expect(writeText.mock.calls[0]![0]).toContain('SKU-0')
 button('预览报价图片').click();await nextTick()
 expect(renderCustomerQuoteSheet).toHaveBeenCalledWith(sheet,expect.any(Function))
 input.value='missing';input.dispatchEvent(new Event('input'));await nextTick()
 expect(document.body.textContent).toContain('未找到匹配的 SKU')
 expect(button('下一页').disabled).toBe(true)
 expect(JSON.stringify(sheet)).toBe(before)
 vi.unstubAllGlobals()
})
