// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import PurchaseDataWorkspace from './PurchaseDataWorkspace.vue'
import { normalizePurchaseRecord } from '@/data/purchaseStore'
import { authState, roleDefinitions } from '@/data/authStore'
import type { FobCatalogRecord } from '@/services/purchaseCatalog'

const mocks=vi.hoisted(()=>({page:vi.fn(),stats:vi.fn(),save:vi.fn(),update:vi.fn(),history:vi.fn(),fob:vi.fn(),fobHistory:vi.fn(),load:vi.fn(),promote:vi.fn()}))
vi.mock('@/services/fobPurchase',async original=>({...await original<object>(),loadFobRecord:mocks.fob,loadFobHistory:mocks.fobHistory}))
vi.mock('@/services/purchaseHistory',async original=>({...await original<object>(),updatePurchaseProduct:mocks.update,loadPurchaseHistory:mocks.history}))
vi.mock('./PurchaseSalesPanel.vue', () => ({ default: { template: '<section />' } }))
vi.mock('./PurchaseRankingsPanel.vue', () => ({ default: { template: '<section />' } }))
vi.mock('@/data/purchaseStore',async importOriginal=>({...await importOriginal<object>(),loadPurchaseStats:mocks.stats,upsertPurchaseProducts:mocks.save,loadPurchaseProduct:mocks.load,promotePurchaseProduct:mocks.promote}))
vi.mock('@/services/purchaseCatalog',()=>({loadPurchaseCatalogPage:mocks.page}))
let app:App
const row=(sku:string)=>normalizePurchaseRecord({sku,weightG:100,minOrderQty:1,purchasePriceCny:10,_version:5})
const legacyPriceRow=()=>normalizePurchaseRecord({...row('FL2600293-2'),dataSource:'legacy_2026',
  purchasePriceBasis:'tax_included',sourceQuotedPriceCny:110,purchasePriceCny:121,taxIncludedPriceCny:121,taxPoint:.1,singleFreightCny:5})
async function openLegacyEditor(product=legacyPriceRow()){
  mocks.page.mockResolvedValue({...page(product.sku),items:[product]})
  await mount()
  Array.from(document.querySelectorAll<HTMLButtonElement>('.actions button')).find(b=>b.textContent==='编辑')!.click()
  await flush()
}
async function setPriceField(label:string,value:string){
  const input=document.querySelector<HTMLInputElement>(`input[aria-label="${label}"]`)!
  input.value=value;input.dispatchEvent(new Event('input',{bubbles:true}));await nextTick()
}
function editorSave(){Array.from(document.querySelectorAll<HTMLButtonElement>('.editor-modal button')).find(b=>b.textContent==='保存资料')!.click()}
const page=(sku:string)=>({items:[row(sku)],total:1,totalPages:1,page:0,size:10})
async function flush(){await Promise.resolve();await nextTick();await Promise.resolve();await nextTick()}
async function mount(){const host=document.createElement('div');document.body.append(host);app=createApp(PurchaseDataWorkspace);app.mount(host);await flush()}
async function search(text:string){const input=document.querySelector('.toolbar input') as HTMLInputElement;input.value=text;input.dispatchEvent(new Event('input',{bubbles:true}));await nextTick()}
beforeEach(()=>{vi.useFakeTimers();mocks.page.mockResolvedValue(page('INITIAL'));mocks.stats.mockResolvedValue({total:1,ready:1,pending:0,generatedSku:0})})
afterEach(()=>{app?.unmount();document.body.innerHTML='';vi.clearAllMocks();vi.useRealTimers();authState.current=null;authState.permissions=[]})

it('edits legacy untaxed price and rate with a read-only included preview, then reads saved values back',async()=>{
  await openLegacyEditor()
  expect(document.querySelector<HTMLInputElement>('[aria-label="未税采购单价"]')!.value).toBe('110')
  const included=document.querySelector<HTMLInputElement>('[aria-label="含票价（自动计算）"]')!
  expect(included.readOnly).toBe(true);expect(included.value).toBe('121.00')
  await setPriceField('未税采购单价','100');expect(included.value).toBe('110.00')
  await setPriceField('采购票点','0.08');expect(included.value).toBe('108.00')
  mocks.update.mockImplementationOnce(async(_sku,input)=>normalizePurchaseRecord({...input,_version:6}))
  editorSave();await flush()
  expect(mocks.update).toHaveBeenCalledWith('FL2600293-2',expect.objectContaining({
    sourceQuotedPriceCny:100,taxPoint:.08,taxIncludedPriceCny:108,purchasePriceCny:108,legacyPriceMode:'auto_tax',_version:5}))
  expect(document.querySelector('table')?.textContent).toContain('¥108.00/件')
  expect(document.querySelector('.legacy-purchase-prices b')?.textContent).toBe('未税单价 ¥100.00/件')
  expect(document.querySelector('.legacy-purchase-prices')?.textContent).toContain('含票价 ¥108.00/件')
  expect(document.querySelector('.legacy-purchase-prices')?.textContent).not.toContain('第1档')
  Array.from(document.querySelectorAll<HTMLButtonElement>('.actions button')).find(b=>b.textContent==='编辑')!.click();await flush()
  expect(document.querySelector<HTMLInputElement>('[aria-label="未税采购单价"]')!.value).toBe('100')
  expect(document.querySelector<HTMLInputElement>('[aria-label="含票价（自动计算）"]')!.value).toBe('108.00')
})

it('does not reprice legacy data when only unrelated fields are saved',async()=>{
  const product=normalizePurchaseRecord({...legacyPriceRow(),taxIncludedPriceCny:125,purchasePriceCny:125})
  await openLegacyEditor(product)
  const notes=document.querySelector<HTMLTextAreaElement>('.editor-modal textarea')!
  notes.value='更新备注';notes.dispatchEvent(new Event('input',{bubbles:true}));await nextTick()
  mocks.update.mockResolvedValueOnce(product);editorSave();await flush()
  expect(mocks.update.mock.calls[0]![1]).toMatchObject({sourceQuotedPriceCny:110,purchasePriceCny:125,taxIncludedPriceCny:125,notes:'更新备注'})
  expect(mocks.update.mock.calls[0]![1].legacyPriceMode).toBeUndefined()
})

it('locks the entire editor during a slow save and ignores repeated save clicks',async()=>{
  await openLegacyEditor()
  await setPriceField('未税采购单价','100')
  let finish!:(value:ReturnType<typeof legacyPriceRow>)=>void
  mocks.update.mockReturnValueOnce(new Promise(resolve=>{finish=resolve}))
  editorSave();await flush()
  const saving=Array.from(document.querySelectorAll<HTMLButtonElement>('.editor-modal button')).find(b=>b.textContent==='保存中…')!
  saving.click();await flush()
  expect(mocks.update).toHaveBeenCalledTimes(1)
  expect(document.querySelector<HTMLFieldSetElement>('.editor-modal fieldset')?.disabled).toBe(true)
  const cancel=Array.from(document.querySelectorAll<HTMLButtonElement>('.editor-modal button')).find(b=>b.textContent==='取消')!
  expect(cancel.disabled).toBe(true)
  expect(document.querySelector<HTMLButtonElement>('.editor-modal .close')!.disabled).toBe(true)
  cancel.click();await flush();expect(document.querySelector('.editor-modal')).not.toBeNull()
  finish(normalizePurchaseRecord({...legacyPriceRow(),sourceQuotedPriceCny:100,purchasePriceCny:110,taxIncludedPriceCny:110,_version:6}))
  await flush();expect(document.querySelector('.editor-modal')).toBeNull()
})

it.each(['网络连接已断开','商品已被其他用户修改，请刷新后重试','登录状态已失效'])('retains unsaved price and unlocks the editor after %s',async message=>{
  await openLegacyEditor();await setPriceField('未税采购单价','100')
  mocks.update.mockRejectedValueOnce(new Error(message))
  editorSave();await flush()
  expect(document.querySelector<HTMLInputElement>('[aria-label="未税采购单价"]')!.value).toBe('100')
  expect(document.querySelector('.legacy-purchase-prices b')?.textContent).toBe('未税单价 ¥110.00/件')
  expect(document.querySelector<HTMLFieldSetElement>('.editor-modal fieldset')?.disabled).toBe(false)
  expect(document.body.textContent).toContain(message)
  expect(mocks.update).toHaveBeenCalledTimes(1)
})

it('cancel discards unsaved prices and readonly included-price tampering is not submitted',async()=>{
  await openLegacyEditor();await setPriceField('未税采购单价','100')
  Array.from(document.querySelectorAll<HTMLButtonElement>('.editor-modal button')).find(b=>b.textContent==='取消')!.click();await flush()
  Array.from(document.querySelectorAll<HTMLButtonElement>('.actions button')).find(b=>b.textContent==='编辑')!.click();await flush()
  expect(document.querySelector<HTMLInputElement>('[aria-label="未税采购单价"]')!.value).toBe('110')
  await setPriceField('含票价（自动计算）','999')
  mocks.update.mockResolvedValueOnce(legacyPriceRow());editorSave();await flush()
  expect(mocks.update).toHaveBeenCalledWith('FL2600293-2',expect.objectContaining({sourceQuotedPriceCny:110,purchasePriceCny:121,taxIncludedPriceCny:121}))
})

it('labels included-only legacy data without guessing an untaxed price',async()=>{
  await openLegacyEditor(normalizePurchaseRecord({...legacyPriceRow(),sourceQuotedPriceCny:null}))
  const prices=document.querySelector('.legacy-purchase-prices')!
  expect(prices.querySelector('b')?.textContent).toBe('含票价 ¥121.00/件')
  expect(prices.textContent).toContain('未税单价待确认')
  expect(prices.textContent).not.toContain('第1档')
})

it('does not round a fractional-cent source quote into a different displayed untaxed price',async()=>{
  await openLegacyEditor(normalizePurchaseRecord({...legacyPriceRow(),sourceQuotedPriceCny:1.005,purchasePriceCny:1.11,taxIncludedPriceCny:1.11}))
  expect(document.querySelector('.legacy-purchase-prices b')?.textContent).toBe('未税单价 ¥1.005/件')
})

it('a lost save response followed by retry cannot silently overwrite a committed price',async()=>{
  await openLegacyEditor();await setPriceField('未税采购单价','100')
  mocks.update.mockRejectedValueOnce(new Error('网络连接已断开'))
    .mockRejectedValueOnce(new Error('商品已被其他用户修改，请刷新后重试'))
  editorSave();await flush();editorSave();await flush()
  expect(mocks.update.mock.calls.map(call=>call[1]._version)).toEqual([5,5])
  expect(mocks.update.mock.calls.map(call=>call[1].purchasePriceCny)).toEqual([110,110])
  expect(document.querySelector<HTMLInputElement>('[aria-label="未税采购单价"]')!.value).toBe('100')
  expect(document.body.textContent).toContain('请刷新后重试')
})

it('preserves real tier ranges while displaying their own untaxed prices',async()=>{
  await openLegacyEditor(normalizePurchaseRecord({...legacyPriceRow(),minOrderQty:2,tier2MinQty:100,tier2PriceCny:100}))
  const prices=document.querySelector('.legacy-purchase-prices')!
  expect([...prices.querySelectorAll('b')].map(b=>b.textContent)).toEqual(['未税单价 ¥110.00/件','未税单价 ¥100.00/件'])
  expect(prices.textContent).toContain('第1档 · 2–99件')
  expect(prices.textContent).toContain('第2档 · 100件起订')
  expect(prices.textContent).toContain('含票价 ¥110.00/件')
})

it('requires confirmation of missing untaxed input before a rate change and never guesses from included price',async()=>{
  await openLegacyEditor(normalizePurchaseRecord({...legacyPriceRow(),sourceQuotedPriceCny:null}))
  expect(document.querySelector<HTMLInputElement>('[aria-label="未税采购单价"]')!.value).toBe('')
  expect(document.querySelector('.editor-modal')?.textContent).toContain('未税单价待确认')
  await setPriceField('采购票点','0.08');editorSave();await flush()
  expect(mocks.update).not.toHaveBeenCalled()
  expect(document.body.textContent).toContain('请填写大于0的未税采购单价')
})

it.each(['','-0.1','1.1'])('blocks invalid legacy rate %s without overwriting the saved record',async rate=>{
  await openLegacyEditor();await setPriceField('采购票点',rate);editorSave();await flush()
  expect(mocks.update).not.toHaveBeenCalled()
  expect(document.querySelector('table')?.textContent).toContain('¥121.00/件')
})

const fobRow=(sku:string):FobCatalogRecord=>({sku,dataSource:'fob',category:'鞋',weightRaw:'110',priceRaw:'1件23.99;100件19.5;300件19.5;500件19;1000件19',freightRaw:'100件预拍74',
  parsed:{minOrderQty:1,orderMultiple:1,priceTiers:[1,100,300,500,1000].map((minQty,i)=>({minQty,maxQty:[99,299,499,999,null][i]!,unitPriceCny:[23.99,19.5,19.5,19,19][i]!,unit:'件'})),freight:{quantity:100,totalFreightCny:74,unitFreightCny:0.74,estimated:true,basis:'100件总运费74÷100'}}})
it('shows both sources for the same SKU, retains five FOB tiers, and routes actions by source',async()=>{
  authState.current={id:'buyer',account:'buyer',name:'采购',role:'purchase',status:'enabled',mustChangePassword:false,passwordUpdatedAt:''};authState.permissions=['purchase']
  const fob=fobRow('FL2600088'),ordinary=normalizePurchaseRecord({...row(fob.sku),dataSource:'legacy_2026',singleFreightCny:5})
  mocks.page.mockResolvedValue({items:[ordinary,fob],total:2,totalPages:1,page:0,size:10});mocks.fob.mockResolvedValue(fob);mocks.fobHistory.mockResolvedValue([])
  await mount()
  const normal=document.querySelector('tr[data-source="legacy_2026"]')!,fo=document.querySelector('tr[data-source="fob"]')!
  expect(normal.textContent).toContain('2026旧数据');expect(fo.textContent).toContain('FOB数据');expect(fo.textContent).toContain('第5档')
  expect(fo.textContent).toContain('预估/预拍');expect(fo.textContent).not.toContain('删除');expect(fo.textContent).not.toContain('停用')
  fo.querySelector<HTMLButtonElement>('button')!.click();await flush()
  expect(document.querySelector('[aria-labelledby="fob-detail-title"]')?.textContent).toContain('FOB资料详情')
  document.querySelector<HTMLButtonElement>('[aria-label="关闭FOB详情"]')!.click();await flush()
  Array.from(fo.querySelectorAll<HTMLButtonElement>('button')).find(b=>b.textContent==='粘贴更新 / 修改记录')!.click();await flush();await flush()
  expect(mocks.fob).toHaveBeenCalledWith(fob.sku);expect(mocks.fobHistory).toHaveBeenCalledWith(fob.sku)
  expect(document.querySelector<HTMLDetailsElement>('details.lookup')?.open).toBe(true)
  expect(mocks.update).not.toHaveBeenCalled();expect(mocks.save).not.toHaveBeenCalled()
})

it('does not overwrite a FOB row when creating the ordinary product with the same SKU',async()=>{
  mocks.page.mockResolvedValue({items:[fobRow('PAIR-1')],total:1,totalPages:1,page:0,size:10})
  await mount();Array.from(document.querySelectorAll('button')).find(b=>b.textContent?.includes('新增采购资料'))!.click();await flush()
  const input=Array.from(document.querySelectorAll('.form-grid label')).find(l=>l.textContent?.includes('SKU'))!.querySelector('input')!
  input.value='PAIR-1';input.dispatchEvent(new Event('input',{bubbles:true}));await nextTick();mocks.save.mockResolvedValueOnce([row('PAIR-1')])
  Array.from(document.querySelectorAll('button')).find(b=>b.textContent==='保存资料')!.click();await flush()
  expect(mocks.save).toHaveBeenCalledTimes(1)
  expect(document.querySelectorAll('tr[data-sku="PAIR-1"]')).toHaveLength(2)
  expect(document.querySelector('tr[data-source="fob"]')?.textContent).toContain('第5档')
})

it.each(roleDefinitions)('allows FOB paste maintenance for procurement and administrators: $name',async role=>{
  authState.current={id:role.key,account:role.key,name:role.name,role:role.key,status:'enabled',mustChangePassword:false,passwordUpdatedAt:''};authState.permissions=[...role.permissions]
  await mount()
  const paste=Array.from(document.querySelectorAll('button')).find(b=>b.textContent==='FOB 粘贴更新')
  expect(!!paste).toBe(['super_admin','purchase'].includes(role.key))
  if(paste){
    paste.click();await flush()
    expect(document.querySelector('[aria-labelledby="fob-paste-title"]')).not.toBeNull()
    authState.current.role='logistics';authState.permissions=['logistics'];await flush()
    expect(document.querySelector('[aria-labelledby="fob-paste-title"]')).toBeNull()
    expect(Array.from(document.querySelectorAll('button')).some(b=>b.textContent==='FOB 粘贴更新')).toBe(false)
  }
})

it.each(['少量现货，7天补货', ''])('edits and saves optional stock notes without affecting quotation eligibility: %s', async stockStatus => {
  const product = normalizePurchaseRecord({ ...row('STOCK-260001'), stockStatus: '需预订' })
  mocks.page.mockResolvedValue({ ...page(product.sku), items: [product] })
  await mount()
  expect(document.querySelector('table')?.textContent).toContain('需预订')
  Array.from(document.querySelectorAll<HTMLButtonElement>('.actions button')).find(b => b.textContent === '编辑')!.click(); await flush()
  const field = Array.from(document.querySelectorAll('.editor-modal label')).find(l => l.textContent?.includes('是否有货'))!
  expect(field.textContent).toContain('选填')
  const input = field.querySelector('input')!
  expect(input.value).toBe('需预订')
  input.value = stockStatus; input.dispatchEvent(new Event('input', { bubbles: true })); await nextTick()
  mocks.update.mockResolvedValueOnce({ ...product, stockStatus })
  Array.from(document.querySelectorAll<HTMLButtonElement>('.editor-modal button')).find(b => b.textContent === '保存资料')!.click(); await flush()
  expect(mocks.update).toHaveBeenCalledWith(product.sku, expect.objectContaining({ stockStatus, quoteReady: true, _version: 5 }))
})

it('shows and saves legacy batch freight in both procurement details and the editor', async () => {
  const legacy = normalizePurchaseRecord({ ...row('YT2600676'), dataSource: 'legacy_2026', singleFreightCny: 5, freight10Cny: 5, freight100Cny: 11.5 })
  mocks.page.mockResolvedValue({ ...page(legacy.sku), items: [legacy] })
  await mount()
  Array.from(document.querySelectorAll<HTMLButtonElement>('.actions button')).find(b => b.textContent === '查看详情')!.click(); await flush()
  expect(document.querySelector('.detail-modal')?.textContent).toContain('10件总运费(CNY)')
  expect(document.querySelector('.detail-modal')?.textContent).toContain('100件总运费(CNY)')
  document.querySelector<HTMLButtonElement>('.detail-modal .close')!.click(); await flush()
  Array.from(document.querySelectorAll<HTMLButtonElement>('.actions button')).find(b => b.textContent === '编辑')!.click(); await flush()
  const field = Array.from(document.querySelectorAll('.editor-modal label')).find(l => l.textContent?.includes('10件总运费(CNY)'))!
  const input = field.querySelector('input')!
  expect(input.value).toBe('5')
  expect(field.textContent).toContain('÷10分摊')
  input.value = '0'; input.dispatchEvent(new Event('input', { bubbles: true })); await nextTick()
  mocks.update.mockResolvedValueOnce({ ...legacy, freight10Cny: 0 })
  Array.from(document.querySelectorAll<HTMLButtonElement>('.editor-modal button')).find(b => b.textContent === '保存资料')!.click(); await flush()
  expect(mocks.update).toHaveBeenCalledWith('YT2600676', expect.objectContaining({ freight10Cny: 0, freight100Cny: 11.5, _version: 5 }))
})

it('aborts superseded searches immediately and never applies their late results or repeats statistics',async()=>{
  await mount()
  let resolveOld!:(value:ReturnType<typeof page>)=>void
  mocks.page.mockImplementationOnce(()=>new Promise(resolve=>{resolveOld=resolve}))
  await search('OLD');await vi.advanceTimersByTimeAsync(250)
  const oldSignal=mocks.page.mock.calls.at(-1)![3] as AbortSignal
  await search('NEW');expect(oldSignal.aborted).toBe(true)
  mocks.page.mockResolvedValueOnce(page('NEW'))
  await vi.advanceTimersByTimeAsync(250);await flush()
  resolveOld(page('OLD'));await flush()
  expect(document.querySelector('table')?.textContent).toContain('NEW')
  expect(document.querySelector('table')?.textContent).not.toContain('OLD')
  expect(mocks.stats).toHaveBeenCalledTimes(1)
})

it('renders the page even when the independent statistics request fails',async()=>{
  mocks.stats.mockRejectedValueOnce(new Error('统计不可用'))
  await mount()
  expect(document.querySelector('table')?.textContent).toContain('INITIAL')
  expect(document.body.textContent).toContain('统计不可用')
})

it('does not present old SKU results as matches after a failed combined search',async()=>{
  await mount();mocks.page.mockRejectedValueOnce(new Error('查询失败'))
  await search('DIFFERENT-SKU');await vi.advanceTimersByTimeAsync(250);await flush()
  expect(document.querySelector('table')).toBeNull();expect(document.body.textContent).toContain('查询失败')
  expect(document.body.textContent).not.toContain('INITIAL')
})

it('reloads the last available page after concurrent deletions shrink the page count',async()=>{
  mocks.page.mockResolvedValueOnce({...page('FIRST'),total:20,totalPages:2})
  await mount()
  mocks.page.mockResolvedValueOnce({...page(''),items:[],total:1,totalPages:1}).mockResolvedValueOnce(page('LAST'))
  Array.from(document.querySelectorAll('button')).find(b=>b.textContent?.trim()==='2')!.click();await flush();await flush()
  expect(mocks.page.mock.calls.at(-1)![1]).toBe(0)
  expect(document.querySelector('table')?.textContent).toContain('LAST')
  expect(mocks.stats).toHaveBeenCalledTimes(1)
})

it('shares repeated clicks on the same loading page',async()=>{
  mocks.page.mockResolvedValueOnce({...page('FIRST'),total:20,totalPages:2})
  await mount()
  let resolvePage!:(value:ReturnType<typeof page>)=>void
  mocks.page.mockImplementationOnce(()=>new Promise(resolve=>{resolvePage=resolve}))
  const next=Array.from(document.querySelectorAll('button')).find(b=>b.textContent?.trim()==='2')!
  next.click();next.click();await nextTick()
  expect(mocks.page).toHaveBeenCalledTimes(2)
  resolvePage({...page('SECOND'),total:20,totalPages:2});await flush()
  expect(document.querySelector('table')?.textContent).toContain('SECOND')
})

it('uses authoritative save data on the unfiltered first page without a second list read',async()=>{
  await mount()
  Array.from(document.querySelectorAll('button')).find(b=>b.textContent?.includes('新增采购资料'))!.click();await flush()
  const sku=Array.from(document.querySelectorAll('.form-grid label')).find(l=>l.textContent?.includes('SKU'))!.querySelector('input')!
  sku.value='NEW-SAVED';sku.dispatchEvent(new Event('input',{bubbles:true}));await nextTick()
  mocks.save.mockResolvedValueOnce([row('NEW-SAVED')])
  const save=Array.from(document.querySelectorAll('button')).find(b=>b.textContent==='保存资料')!
  save.click();save.click();await flush()
  expect(mocks.save).toHaveBeenCalledTimes(1)
  expect(mocks.page).toHaveBeenCalledTimes(1)
  expect(document.querySelector('table')?.textContent).toContain('NEW-SAVED')
  expect(mocks.stats).toHaveBeenCalledTimes(2)
})

it('opens product history from list and editor and saves existing products atomically',async()=>{
  mocks.history.mockResolvedValue({items:[],page:0,size:10,total:0,totalPages:0})
  await mount()
  Array.from(document.querySelectorAll<HTMLButtonElement>('.actions button')).find(b=>b.textContent==='修改记录')!.click();await flush()
  expect(document.querySelector('[role=dialog]')?.textContent).toContain('INITIAL')
  document.querySelector<HTMLButtonElement>('[aria-label="关闭修改记录"]')!.click();await flush()
  Array.from(document.querySelectorAll<HTMLButtonElement>('.actions button')).find(b=>b.textContent==='编辑')!.click();await flush()
  const history=Array.from(document.querySelectorAll<HTMLButtonElement>('.editor-modal button')).find(b=>b.textContent==='修改记录')!
  history.click();await flush();expect(mocks.history).toHaveBeenCalledTimes(2)
  document.querySelector<HTMLButtonElement>('[aria-label="关闭修改记录"]')!.click();await flush()
  mocks.update.mockResolvedValueOnce(row('INITIAL'))
  Array.from(document.querySelectorAll<HTMLButtonElement>('.editor-modal button')).find(b=>b.textContent==='保存资料')!.click();await flush()
  expect(mocks.update).toHaveBeenCalledWith('INITIAL',expect.objectContaining({sku:'INITIAL',_version:5}))
  expect(mocks.save).not.toHaveBeenCalled()
})

it('serializes promotion with save and restores editing after a promotion failure',async()=>{
  const product=normalizePurchaseRecord({...legacyPriceRow(),catalogState:'pending_template'})
  await openLegacyEditor(product)
  let reject!:(error:Error)=>void
  mocks.save.mockReturnValueOnce(new Promise((_,failure)=>{reject=failure}))
  const promote=Array.from(document.querySelectorAll<HTMLButtonElement>('.editor-modal button')).find(b=>b.textContent==='确认可参与报价')!
  promote.click();promote.click();await flush()
  const save=Array.from(document.querySelectorAll<HTMLButtonElement>('.editor-modal button')).find(b=>b.textContent==='保存中…')!
  save.click();await flush()
  expect(mocks.save).toHaveBeenCalledTimes(1);expect(mocks.update).not.toHaveBeenCalled()
  expect(promote.disabled).toBe(true)
  reject(new Error('网络断开'));await flush()
  expect(document.querySelector<HTMLFieldSetElement>('.editor-modal fieldset')?.disabled).toBe(false)
  expect(document.querySelector<HTMLInputElement>('[aria-label="未税采购单价"]')!.value).toBe('110')
  mocks.save.mockResolvedValueOnce([product]);mocks.load.mockResolvedValueOnce({...product,_version:6});mocks.promote.mockResolvedValueOnce(product)
  promote.click();await flush();await flush()
  expect(mocks.promote).toHaveBeenCalledWith(product.sku,product.sku,6)
  expect(document.querySelector('.editor-modal')).toBeNull()
})
it.each(['','0','-1','1e309'])('blocks abnormal unit-price input %s without a save request',async price=>{
  await openLegacyEditor();await setPriceField('未税采购单价',price);editorSave();await flush()
  expect(mocks.update).not.toHaveBeenCalled()
})
it('rejects typing 10 instead of 0.10 for the ticket point',async()=>{
  await openLegacyEditor();await setPriceField('采购票点','10');editorSave();await flush()
  expect(mocks.update).not.toHaveBeenCalled()
  expect(document.body.textContent).toContain('0～1')
})
