// @vitest-environment happy-dom
import { createApp, h, nextTick, reactive, type App } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import CustomerQuoteSheet from './CustomerQuoteSheet.vue'
import { loadQuotePhotos, type QuoteLocalPhoto } from '@/services/quoteLocalPhotos'
import { renderCustomerQuoteSheet } from '@/services/customerQuoteSheetRenderer'
import { loadSavedQuoteSheetPhotos, saveQuoteSheetPhotos } from '@/services/quoteSheetPhotos'
import type { CustomerPriceSnapshot } from '@/data/customerQuotePrices'
vi.mock('@/services/quoteSheetPhotos', () => ({ loadSavedQuoteSheetPhotos: vi.fn(), saveQuoteSheetPhotos: vi.fn() }))
vi.mock('@/services/quoteLocalPhotos', async original => ({ ...await original<typeof import('@/services/quoteLocalPhotos')>(), loadQuotePhotos:vi.fn() }))
vi.mock('@/services/customerQuoteSheetRenderer', async original => ({ ...await original<typeof import('@/services/customerQuoteSheetRenderer')>(), renderCustomerQuoteSheet:vi.fn().mockResolvedValue([{blob:new Blob(['png']),width:1536,height:1024,firstRow:1,lastRow:2}]) }))
let app: App, exposed: InstanceType<typeof CustomerQuoteSheet>
const photo = (url='blob:local') => ({url,name:'sample.png',image:Object.assign(document.createElement('img'), {src:url})})
const click = async (text:string) => { [...document.querySelectorAll('button')].find(b=>b.textContent===text)!.click(); await settle() }
async function settle() { for(let i=0;i<10;i++) await nextTick() }
async function openPicker() {
  if (!document.querySelector('[role=dialog]')) await click([...document.querySelectorAll('button')].some(button => button.textContent === '管理图片') ? '管理图片' : '添加图片')
}
async function confirmPicker() {
  const button = [...document.querySelectorAll<HTMLButtonElement>('[role=dialog] button')].find(button => button.textContent?.startsWith('确定（'))!
  button.click(); await settle()
}
async function select() {
  await openPicker()
  const input=document.querySelector<HTMLInputElement>('input[type=file]')!
  Object.defineProperty(input,'files',{value:[new File(['x'],'sample.png',{type:'image/png'})],configurable:true})
  input.dispatchEvent(new Event('change')); await settle()
  if (!document.querySelector('[role=alert]')) await confirmPicker()
}
function mount(parent: HTMLElement = document.body, initialQuote?: CustomerPriceSnapshot) {
  const row={country:'US',carrier:'4PX',channelKey:'a',ruleId:1,rule:'',channelCode:'a',transport:'',eta:'5-8 days',quote1:1,quote2:2,quote3:3,quoteCustom:5}
  const state=reactive({rows:[row,{...row,channelKey:'b'}],skus:['ONE','TWO'],countries:[],salesperson:'QA',contextKey:'context',resetKey:'account/bundle',customQuantity:5,bundle:true,sourcePending:false})
  const host=document.createElement('div');parent.append(host)
  app=createApp({render:()=>h(CustomerQuoteSheet,{...state,initialQuote,ref:(vm:unknown)=>{exposed=vm as typeof exposed}})});app.mount(host)
  return state
}
beforeEach(()=>{
  vi.clearAllMocks();vi.mocked(loadQuotePhotos).mockResolvedValue([photo(),photo('blob:second')])
  vi.spyOn(URL,'createObjectURL').mockReturnValue('blob:preview');vi.spyOn(URL,'revokeObjectURL').mockImplementation(()=>{})
  vi.stubGlobal('isSecureContext',true);vi.stubGlobal('navigator',{clipboard:{writeText:vi.fn().mockResolvedValue(undefined)}})
})
afterEach(()=>{app?.unmount();document.body.innerHTML='';vi.restoreAllMocks();vi.unstubAllGlobals()})
it('saves only durable image references with the quote, retaining a hidden image choice', async () => {
  mount(); await select()
  const refs=[{assetId:'11111111-2222-3333-4444-555555555555',name:'sample.png'}]
  vi.mocked(saveQuoteSheetPhotos).mockResolvedValue(refs)
  const toggle=document.querySelector<HTMLInputElement>('[aria-label="显示商品图片列"]')!
  toggle.checked=false;toggle.dispatchEvent(new Event('change'));await settle()
  const captured=await exposed.captureForSave()
  expect(captured).toMatchObject({photos:refs,showPhotos:false})
  expect(JSON.stringify(captured)).not.toContain('blob:')
})
it('waits for saved images before rendering a record preview', async () => {
  let finish!: (photos: QuoteLocalPhoto[])=>void
  vi.mocked(loadSavedQuoteSheetPhotos).mockReturnValue(new Promise(resolve=>{finish=resolve}))
  mount(document.body,{quantities:[1],rows:[{optionId:'a',prices:[12]},{optionId:'b',prices:[13]}],photos:[{assetId:'11111111-2222-3333-4444-555555555555',name:'saved.png'}]})
  const pending=exposed.preview();await settle()
  expect(renderCustomerQuoteSheet).not.toHaveBeenCalled()
  finish([photo('blob:saved')]);await pending
  expect(vi.mocked(renderCustomerQuoteSheet).mock.lastCall![2]).toHaveLength(1)
})
it('blocks silent image loss on load failure and lets the user retry', async () => {
  vi.mocked(loadSavedQuoteSheetPhotos).mockRejectedValueOnce(new Error('图片读取失败'))
  mount(document.body,{quantities:[1],rows:[{optionId:'a',prices:[12]},{optionId:'b',prices:[13]}],photos:[{assetId:'11111111-2222-3333-4444-555555555555',name:'saved.png'}]})
  await settle();await exposed.preview()
  expect(renderCustomerQuoteSheet).not.toHaveBeenCalled()
  await expect(exposed.captureForSave()).rejects.toThrow('图片读取失败')
  vi.mocked(loadSavedQuoteSheetPhotos).mockResolvedValueOnce([photo('blob:retry')])
  await click('重试读取图片');await exposed.preview()
  expect(vi.mocked(renderCustomerQuoteSheet).mock.lastCall![2]).toHaveLength(1)
})
it('rejects a save if the quote changes during image upload', async () => {
  mount();await select()
  let finish!: (photos: [])=>void
  vi.mocked(saveQuoteSheetPhotos).mockReturnValue(new Promise(resolve=>{finish=resolve}))
  const pending=exposed.captureForSave();await settle()
  const title=document.querySelector<HTMLInputElement>('[aria-label="报价单标题"]')!
  title.value='changed';title.dispatchEvent(new Event('input'));await settle()
  finish([]);await expect(pending).rejects.toThrow('报价单已变化')
})
it('keeps the picker inside a native quotation modal and restores focus after cancellation', async () => {
  const modal=document.createElement('dialog');modal.setAttribute('open','');document.body.append(modal)
  mount(modal);await settle()
  const opener=[...modal.querySelectorAll('button')].find(b=>b.textContent==='添加图片')!
  opener.focus();await openPicker()
  const picker=modal.querySelector<HTMLElement>('[role=dialog]')!
  expect(picker).not.toBeNull();expect(document.activeElement).toBe(picker)
  await click('取消')
  expect(modal.querySelector('[role=dialog]')).toBeNull();expect(document.activeElement).toBe(opener)
})
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

it('moves the merged product column with its photos and preserves its position across hide/show', async () => {
  mount(); await select()
  document.querySelector('[data-group-handle="product"]')!.dispatchEvent(new Event('dragstart', { bubbles:true }))
  document.querySelector('[data-group="processingTime"]')!.dispatchEvent(new Event('drop', { bubbles:true, cancelable:true }))
  await settle()
  expect(document.querySelector('.sheet-editor tbody tr')!.lastElementChild?.previousElementSibling?.className).toContain('sheet-photo-cell')
  expect(document.querySelector('.sheet-photo-cell')?.getAttribute('rowspan')).toBe('2')
  expect(document.querySelectorAll('.sheet-photo-cell img')).toHaveLength(2)
  const toggle = document.querySelector<HTMLInputElement>('[aria-label="显示商品图片列"]')!
  toggle.click(); await settle(); toggle.click(); await settle()
  expect(document.querySelector('.sheet-editor tbody tr')!.lastElementChild?.previousElementSibling?.className).toContain('sheet-photo-cell')
  await click('预览报价单')
  expect(vi.mocked(renderCustomerQuoteSheet).mock.lastCall![0].columnOrder?.at(-1)).toBe('product')
  expect(vi.mocked(renderCustomerQuoteSheet).mock.lastCall![2]).toHaveLength(2)
})

it('keeps the shared photos on the first visible row with the correct span after hiding and restoring', async () => {
  mount(); await select()
  document.querySelector<HTMLButtonElement>('[aria-label="隐藏第 1 行"]')!.click(); await settle()
  expect(document.querySelectorAll('.sheet-photo-cell')).toHaveLength(1)
  expect(document.querySelector('.sheet-photo-cell')?.getAttribute('rowspan')).toBe('1')
  await click('预览报价单')
  expect(vi.mocked(renderCustomerQuoteSheet).mock.lastCall![0].rows).toHaveLength(1)
  expect(vi.mocked(renderCustomerQuoteSheet).mock.lastCall![2]).toHaveLength(2)
  await click('编辑报价单'); await click('恢复全部')
  expect(document.querySelector('.sheet-photo-cell')?.getAttribute('rowspan')).toBe('2')
})

function paste(target: Element, files = [new File(['png'], 'pasted.png', { type: 'image/png' })], html = '') {
  const event = new Event('paste', { bubbles: true, cancelable: true })
  Object.defineProperty(event, 'clipboardData', { value: { files, items: [], getData: () => html } })
  target.dispatchEvent(event)
  return event
}
it('collects repeated individual pastes in one dialog and changes the quote only on confirmation', async () => {
  mount(); const before = exposed.capturePrices()
  await openPicker()
  const dialog = document.querySelector('[role=dialog]')!
  for (let i = 1; i <= 6; i++) {
    vi.mocked(loadQuotePhotos).mockResolvedValueOnce([photo(`blob:pasted-${i}`)])
    expect(paste(dialog).defaultPrevented).toBe(true); await settle()
    expect(document.querySelectorAll('[role=dialog] img')).toHaveLength(i)
    expect(document.querySelector('.sheet-photo-cell')).toBeNull()
  }
  paste(dialog); await settle()
  expect(document.querySelector('[role=alert]')?.textContent).toContain('最多添加 6 张')
  expect(loadQuotePhotos).toHaveBeenCalledTimes(6)
  await confirmPicker()
  expect(document.querySelector('[role=dialog]')).toBeNull()
  expect(document.querySelectorAll('.sheet-photo-cell img')).toHaveLength(6)
  expect(exposed.capturePrices()).toEqual(before)
  await click('预览报价单')
  expect(vi.mocked(renderCustomerQuoteSheet).mock.lastCall![2]).toHaveLength(6)
})
it('cancels collected images and staged removal without altering the original quote or preview', async () => {
  mount(); await select(); await click('预览报价单')
  await openPicker()
  document.querySelector<HTMLButtonElement>('[aria-label="移除第 1 张图片"]')!.click(); await settle()
  vi.mocked(loadQuotePhotos).mockResolvedValueOnce([photo('blob:unconfirmed')])
  paste(document.querySelector('[role=dialog]')!); await settle()
  expect(URL.revokeObjectURL).not.toHaveBeenCalledWith('blob:local')
  await click('取消')
  expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:unconfirmed')
  expect(URL.revokeObjectURL).not.toHaveBeenCalledWith('blob:local')
  expect(document.querySelector('img[alt*="客户报价图片预览"]')).not.toBeNull()
  await click('编辑报价单')
  expect(document.querySelectorAll('.sheet-photo-cell img')).toHaveLength(2)
})
it('releases removed originals only on confirm, and supports confirmation of zero images', async () => {
  mount(); await select(); await openPicker()
  document.querySelector<HTMLButtonElement>('[aria-label="移除第 1 张图片"]')!.click(); await settle()
  expect(URL.revokeObjectURL).not.toHaveBeenCalledWith('blob:local')
  await confirmPicker()
  expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:local')
  expect(document.querySelectorAll('.sheet-photo-cell img')).toHaveLength(1)
  await openPicker()
  document.querySelector<HTMLButtonElement>('[aria-label="移除第 1 张图片"]')!.click(); await settle()
  await confirmPicker()
  expect(document.querySelector('.sheet-photo-cell')).toBeNull()
  expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:second')
})
it('releases newly removed photos immediately and keeps confirmed photos alive after closing', async () => {
  mount(); await openPicker()
  vi.mocked(loadQuotePhotos).mockResolvedValueOnce([photo('blob:temporary'), photo('blob:kept')])
  paste(document.querySelector('[role=dialog]')!); await settle()
  document.querySelector<HTMLButtonElement>('[aria-label="移除第 1 张图片"]')!.click(); await settle()
  expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:temporary')
  await confirmPicker()
  expect(URL.revokeObjectURL).not.toHaveBeenCalledWith('blob:kept')
  await click('移除图片')
  expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:kept')
})
it('supports clipboard button and text-only guidance while retaining collected images after denial', async () => {
  mount(); await openPicker()
  const read = vi.fn().mockResolvedValue([{ types: ['image/png'], getType: vi.fn().mockResolvedValue(new Blob(['png'], { type: 'image/png' })) }])
  Object.assign(navigator.clipboard, { read })
  await click('粘贴图片')
  expect(document.querySelectorAll('[role=dialog] img')).toHaveLength(2)
  paste(document.querySelector('[role=dialog]')!, []); await settle()
  expect(document.querySelector('[role=alert]')?.textContent).toContain('复制图片')
  read.mockRejectedValueOnce(new Error('permission denied')); await click('粘贴图片')
  expect(document.querySelector('[role=alert]')?.textContent).toContain('Ctrl+V')
  expect(document.querySelectorAll('[role=dialog] img')).toHaveLength(2)
  expect(document.querySelector('.sheet-photo-cell')).toBeNull()
})
it('ignores paste in ordinary quote input fields outside the dialog', async () => {
  mount()
  expect(paste(document.querySelector('[aria-label="报价单标题"]')!).defaultPrevented).toBe(false)
  expect(loadQuotePhotos).not.toHaveBeenCalled()
})
it.each(['cancel', 'switch', 'unmount'])('discards delayed clipboard reads after %s', async mode => {
  const state = mount(); await openPicker()
  let finish!: (items: unknown[]) => void
  Object.assign(navigator.clipboard, { read: vi.fn(() => new Promise(resolve => { finish = resolve })) })
  await click('粘贴图片')
  if (mode === 'cancel') await click('取消')
  else if (mode === 'switch') { state.skus = ['OTHER']; await settle() }
  else app.unmount()
  finish([{ types: ['image/png'], getType: vi.fn().mockResolvedValue(new Blob(['png'])) }]); await settle()
  expect(loadQuotePhotos).not.toHaveBeenCalled()
  expect(document.querySelector('[role=dialog]')).toBeNull()
})
it('allows cancellation during image decoding and releases the late result', async () => {
  mount(); await openPicker()
  let finish!: (photos: QuoteLocalPhoto[]) => void
  vi.mocked(loadQuotePhotos).mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  paste(document.querySelector('[role=dialog]')!); await settle()
  await click('取消'); finish([photo('blob:late')]); await settle()
  expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:late')
  expect(document.querySelector('.sheet-photo-cell')).toBeNull()
})
it.each(['cancel', 'switch'])('aborts pending HTML image downloads on %s', async mode => {
  const state = mount(); await openPicker()
  let signal!: AbortSignal
  const fetch = vi.fn((_url: string, options: RequestInit) => new Promise((_resolve, reject) => {
    signal = options.signal!
    signal.addEventListener('abort', () => reject(new Error('aborted')))
  }))
  vi.stubGlobal('fetch', fetch)
  paste(document.querySelector('[role=dialog]')!, [], '<img src="https://example.com/one.png"><img src="https://example.com/two.png">')
  await settle(); expect(signal.aborted).toBe(false)
  if (mode === 'cancel') await click('取消')
  else { state.skus = ['OTHER']; await settle() }
  expect(signal.aborted).toBe(true)
  expect(fetch).toHaveBeenCalledTimes(1)
  expect(loadQuotePhotos).not.toHaveBeenCalled()
  expect(document.querySelector('[role=dialog]')).toBeNull()
})
it('focuses the dialog, traps Tab, and restores focus on Escape', async () => {
  mount()
  const opener = [...document.querySelectorAll('button')].find(button => button.textContent === '添加图片')!
  opener.focus(); await openPicker()
  const dialog = document.querySelector<HTMLElement>('[role=dialog]')!
  expect(document.activeElement).toBe(dialog)
  dialog.dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', shiftKey: true, bubbles: true, cancelable: true }))
  expect(document.activeElement?.textContent).toBe('确定（0 张）')
  dialog.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true })); await settle()
  expect(document.querySelector('[role=dialog]')).toBeNull()
  expect(document.activeElement).toBe(opener)
})
