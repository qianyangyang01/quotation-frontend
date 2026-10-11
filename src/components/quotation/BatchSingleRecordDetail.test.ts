// @vitest-environment happy-dom
import { createApp, nextTick, type App } from 'vue'
import { afterEach, expect, it, vi } from 'vitest'
import Detail from './BatchSingleRecordDetail.vue'
import type { BatchSingleDocument } from '@/services/batchSingleQuotation'
import { setPricingTestPermissions, clearPricingTestPermissions } from '@/test/pricingPermissions'
vi.mock('./BatchSingleSheet.vue', () => ({ default: { template: '<div data-sheet />' } }))
let app: App | undefined
const fixture = () => ({ id:'record', no:'BQS-TEST', customerName:'客户', sheet:{ quantityLabels:['1 pc','2 pcs'], rows:[] }, items:Array.from({length:21},(_,i)=>({ input:{primarySku:`SKU${i}`,purchaseUnitPriceCny:8.3,exchangeRate:6.7}, sheet:{rows:[{key:'a',country:'US',provider:'4PX',shippingTime:'7-12 days',prices:[12.3,22.5]}]} })) }) as unknown as BatchSingleDocument
async function mount(permissions: Parameters<typeof setPricingTestPermissions>[0]=['quote','myRecords']) { setPricingTestPermissions(permissions); const host=document.createElement('div');document.body.append(host); const record=fixture(),before=JSON.stringify(record),close=vi.fn(); app=createApp(Detail,{document:record,onClose:close});app.mount(host);await nextTick();return {record,before,close} }
const button=(label:string)=>[...document.querySelectorAll('button')].find(b=>b.textContent?.includes(label))!
afterEach(()=>{app?.unmount();document.body.innerHTML='';clearPricingTestPermissions()})
it('paginates by SKU, searches all pages, and leaves the saved snapshot unchanged',async()=>{
 const {record,before}=await mount();expect(document.querySelectorAll('.sku-group')).toHaveLength(10);expect(document.body.textContent).toContain('12.30')
 button('下一页').click();await nextTick();button('下一页').click();await nextTick();expect(document.querySelectorAll('.sku-group')).toHaveLength(1)
 const query=document.querySelector<HTMLInputElement>('input')!;query.value='SKU1';query.dispatchEvent(new Event('input'));await nextTick();expect(document.querySelectorAll('.sku-group')).toHaveLength(10);expect(document.body.textContent).toContain('1 / 2');expect(JSON.stringify(record)).toBe(before)
})
it('hides internal cost and exchange-rate values from staff without pricing permissions',async()=>{
 await mount();expect(document.body.textContent).not.toContain('8.30');expect(document.body.textContent).not.toContain('保存汇率');button('查看完整客户报价单').click();await nextTick();expect(document.querySelector('[data-sheet]')).not.toBeNull()
})
it('shows authorized cost snapshots and restores scroll and focus after closing',async()=>{
 const focus=document.createElement('button');document.body.append(focus);focus.focus();document.body.style.overflow='auto';const {close}=await mount(['purchase','finance']);expect(document.body.textContent).toContain('8.30');expect(document.body.textContent).toContain('保存汇率 6.7');expect(document.body.style.overflow).toBe('hidden');document.querySelector('[role="dialog"]')!.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',bubbles:true}));expect(close).toHaveBeenCalledOnce();app!.unmount();app=undefined;expect(document.body.style.overflow).toBe('auto');expect(document.activeElement).toBe(focus)
})
