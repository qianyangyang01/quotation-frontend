// @vitest-environment happy-dom
import { createApp, h, nextTick, reactive, type App } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import CustomerQuoteSheet from './CustomerQuoteSheet.vue'
import { loadQuotePhotos, type QuoteLocalPhoto } from '@/services/quoteLocalPhotos'
import { renderCustomerQuoteSheet } from '@/services/customerQuoteSheetRenderer'
vi.mock('@/services/quoteLocalPhotos', async original => ({ ...await original<typeof import('@/services/quoteLocalPhotos')>(), loadQuotePhotos:vi.fn() }))
vi.mock('@/services/customerQuoteSheetRenderer', async original => ({ ...await original<typeof import('@/services/customerQuoteSheetRenderer')>(), renderCustomerQuoteSheet:vi.fn().mockResolvedValue([{blob:new Blob(['png']),width:1536,height:1024,firstRow:1,lastRow:2}]) }))
let app: App, exposed: InstanceType<typeof CustomerQuoteSheet>
const photo = (url='blob:local') => ({url,name:'sample.png',image:Object.assign(document.createElement('img'), {src:url})})
const click = async (text:string) => { [...document.querySelectorAll('button')].find(b=>b.textContent===text)!.click(); await settle() }
async function settle() { for(let i=0;i<10;i++) await nextTick() }
async function select() {
  const input=document.querySelector<HTMLInputElement>('input[type=file]')!
  Object.defineProperty(input,'files',{value:[new File(['x'],'sample.png',{type:'image/png'})],configurable:true})
  input.dispatchEvent(new Event('change')); await settle()
}
function mount() {
  const row={country:'US',carrier:'4PX',channelKey:'a',ruleId:1,rule:'',channelCode:'a',transport:'',eta:'5-8 days',quote1:1,quote2:2,quote3:3,quoteCustom:5}
  const state=reactive({rows:[row,{...row,channelKey:'b'}],skus:['ONE','TWO'],countries:[],salesperson:'QA',contextKey:'context',resetKey:'account/bundle',customQuantity:5,bundle:true,sourcePending:false})
  const host=document.createElement('div');document.body.append(host)
  app=createApp({render:()=>h(CustomerQuoteSheet,{...state,ref:(vm:unknown)=>{exposed=vm as typeof exposed}})});app.mount(host)
  return state
}
beforeEach(()=>{
  vi.clearAllMocks();vi.mocked(loadQuotePhotos).mockResolvedValue([photo(),photo('blob:second')])
  vi.spyOn(URL,'createObjectURL').mockReturnValue('blob:preview');vi.spyOn(URL,'revokeObjectURL').mockImplementation(()=>{})
  vi.stubGlobal('isSecureContext',true);vi.stubGlobal('navigator',{clipboard:{writeText:vi.fn().mockResolvedValue(undefined)}})
})
afterEach(()=>{app?.unmount();document.body.innerHTML='';vi.restoreAllMocks();vi.unstubAllGlobals()})
it('shares one image cell, exports photos separately and never includes them in price snapshots or copied data',async()=>{
  mount();const before=exposed.capturePrices()
  expect(document.querySelector('.sheet-photo-cell')).toBeNull()
  await select()
  expect(document.querySelectorAll('.sheet-photo-cell')).toHaveLength(1)
  expect(document.querySelector('.sheet-photo-cell')?.getAttribute('rowspan')).toBe('2')
  expect(exposed.capturePrices()).toEqual(before)
  await click('预览报价单')
  expect(vi.mocked(renderCustomerQuoteSheet).mock.lastCall![2]).toHaveLength(2)
  expect(JSON.stringify(vi.mocked(renderCustomerQuoteSheet).mock.lastCall![0])).not.toContain('blob:')
  await click('复制报价数据')
  const text=vi.mocked(navigator.clipboard.writeText).mock.lastCall![0]
  expect(text).toContain('ONE+TWO');expect(text).not.toMatch(/blob:|sample.png|Product/)
  const toggle=document.querySelector<HTMLInputElement>('[aria-label="显示商品图片列"]')!
  toggle.click();await settle();await click('预览报价单')
  expect(vi.mocked(renderCustomerQuoteSheet).mock.lastCall![2]).toEqual([])
  await click('移除图片');expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:local')
})
it('retains images for pricing changes but clears on product changes and discards late reads',async()=>{
  const state=mount();await select()
  state.contextKey='new-price';await settle();expect(document.querySelector('.sheet-photo-cell')).not.toBeNull()
  state.skus=['NEW'];await settle();expect(document.querySelector('.sheet-photo-cell')).toBeNull()
  let finish!:(p:QuoteLocalPhoto[])=>void
  vi.mocked(loadQuotePhotos).mockImplementationOnce(()=>new Promise(resolve=>{finish=resolve}))
  await select();state.resetKey='another-account';await settle();finish([photo('blob:late')]);await settle()
  expect(document.querySelector('.sheet-photo-cell')).toBeNull()
  expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:late')
})
it('keeps previous photos after a failed replacement and releases them on leaving',async()=>{
  mount();await select();vi.mocked(loadQuotePhotos).mockRejectedValueOnce(new Error('图片无法读取'))
  await select();expect(document.querySelector('[role=alert]')?.textContent).toContain('图片无法读取')
  expect(document.querySelectorAll('.sheet-photo-cell img')).toHaveLength(2)
  app.unmount();expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:local')
})
it('clears photos when leaving the browser page, including back/forward cache navigation',async()=>{
  mount();await select();window.dispatchEvent(new Event('pagehide'));await settle()
  expect(document.querySelector('.sheet-photo-cell')).toBeNull()
  expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:local')
})
