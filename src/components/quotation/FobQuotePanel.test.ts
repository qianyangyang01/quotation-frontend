// @vitest-environment happy-dom
import { createApp, nextTick, type App } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { setPricingTestPermissions } from '@/test/pricingPermissions'
beforeEach(() => setPricingTestPermissions())
import Panel from './FobQuotePanel.vue'
import { FOB_SMALL_ORDER_POLICY, loadFobQuoteProduct, type FobQuoteProduct } from '@/services/fobQuotation'
import { copyQuoteSheetData } from '@/services/customerQuoteSheetClipboard'
import { renderCustomerQuoteSheet } from '@/services/customerQuoteSheetRenderer'
import { authState, roleDefinitions } from '@/data/authStore'
vi.mock('@/services/customerQuoteSheetClipboard', () => ({ copyQuoteSheetData: vi.fn().mockResolvedValue(undefined) }))
vi.mock('@/services/customerQuoteSheetRenderer', () => ({ renderCustomerQuoteSheet: vi.fn().mockResolvedValue([]), copyQuoteSheetImage: vi.fn().mockResolvedValue(undefined) }))
vi.mock('@/services/fobQuotation', async original => ({ ...await original<typeof import('@/services/fobQuotation')>(), loadFobQuoteProduct: vi.fn() }))
let app: App | undefined
const tick=async()=>{await nextTick();await Promise.resolve();await nextTick()}
const sample=():FobQuoteProduct=>({sku:'PF2600053',category:'文胸',weight:'80',source:'fob',notices:[],parsed:{minOrderQty:1,orderMultiple:1,freight:{quantity:100,totalFreightCny:0,unitFreightCny:0,estimated:false,basis:'包邮'},priceTiers:[16,13.8,13,13,12.8].map((v,i)=>({minQty:[1,100,300,500,1000][i]!,maxQty:[99,299,499,999,null][i]!,unitPriceCny:v,unit:'件'}))}})
function mount(financeError=''){const host=document.createElement('div');document.body.append(host);app=createApp(Panel,{rate:6.7,policy:FOB_SMALL_ORDER_POLICY,financeError});app.mount(host)}
async function sku(value:string){const input=document.querySelector<HTMLInputElement>('[aria-label="FOB查询SKU"]')!;input.value=value;input.dispatchEvent(new Event('input',{bubbles:true}));await tick()}
async function query(){document.querySelector('form')!.dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));await tick()}
afterEach(()=>{app?.unmount();document.body.innerHTML='';vi.resetAllMocks();authState.current=null;authState.permissions=[]})
it('keeps employee FOB quoting available while hiding purchase values, notices and pricing factors',async()=>{
  setPricingTestPermissions(['quote','myRecords'])
  const product=sample();product.parsed.priceTiers=[{minQty:1,maxQty:null,unitPriceCny:731.29,unit:'件'}]
  product.parsed.freight.unitFreightCny=12.37;product.parsed.freight.basis='采购运费12.37';product.notices=['原价731.29']
  const before=JSON.stringify(product)
  vi.mocked(loadFobQuoteProduct).mockResolvedValue(product);mount();await sku(product.sku);await query()
  expect(document.querySelector('.final-quote')).not.toBeNull()
  expect(document.querySelector('[aria-label="FOB报价数量"]')).not.toBeNull()
  expect(Array.from(document.querySelectorAll('button')).some(b=>b.textContent==='保存FOB报价')).toBe(true)
  for(const secret of ['731.29','12.37','×1.14','×1.1628','成本价','FOB计算规则'])expect(document.body.innerHTML).not.toContain(secret)
  expect(JSON.stringify(product)).toBe(before)
})
it('quotes and copies a one-yuan small-order surcharge and removes it at the existing threshold',async()=>{
  const writeText=vi.fn().mockResolvedValue(undefined)
  Object.defineProperty(navigator,'clipboard',{value:{writeText},configurable:true})
  const p=sample();p.parsed.priceTiers=[{minQty:1,maxQty:null,unitPriceCny:7.2,unit:'件'}]
  mount();vi.mocked(loadFobQuoteProduct).mockResolvedValue(p);await sku(p.sku);await query()
  const quantity=document.querySelector<HTMLInputElement>('[aria-label="FOB报价数量"]')!
  quantity.value='25';quantity.dispatchEvent(new Event('input',{bubbles:true}));await tick()
  expect(document.querySelector('.quantity-status')!.textContent).toContain('最终价已含每件1元小额订单加价')
  expect([...document.querySelectorAll('.current-price')].map(cell=>cell.textContent)).toEqual(['报关 $1.52/件','不报关 $1.55/件'])
  expect(document.body.textContent).toContain('不足200元时，每件加1元')
  Array.from(document.querySelectorAll('button')).find(b=>b.textContent==='复制当前数量报价')!.click();await tick()
  expect(writeText).toHaveBeenCalledWith(expect.stringContaining('已含每件1元小额订单加价'))
  quantity.value='26';quantity.dispatchEvent(new Event('input',{bubbles:true}));await tick()
  expect(document.querySelector('.quantity-status')!.textContent).toContain('无小额订单加价')
  expect(document.querySelector('.final-quote')!.textContent).toContain('报关 $1.35/件')
})
it('keeps only the latest result after a burst of twenty queries resolving in reverse order',async()=>{
  mount()
  const pending:Array<{resolve:(p:FobQuoteProduct)=>void;reject:(e:Error)=>void}>=[]
  vi.mocked(loadFobQuoteProduct).mockImplementation(()=>new Promise((resolve,reject)=>pending.push({resolve,reject})))
  for(let i=0;i<20;i++){await sku(`BURST-${i}`);await query()}
  pending[19]!.resolve({...sample(),sku:'BURST-19'});await tick()
  for(let i=18;i>=0;i--){if(i%2)pending[i]!.reject(new Error('旧请求超时'));else pending[i]!.resolve({...sample(),sku:`BURST-${i}`});await tick()}
  expect(document.querySelector('.fob-sku')!.textContent).toBe('BURST-19')
  expect(document.body.textContent).not.toContain('旧请求超时')
  expect(document.querySelectorAll('.fob-card tbody tr')).toHaveLength(5)
  for(const call of vi.mocked(loadFobQuoteProduct).mock.calls.slice(0,-1))expect(call[2]!.aborted).toBe(true)
})
it.each(['super_admin','finance','employee'] as const)('shows the same tier prices and quantity quote for %s',async role=>{
  authState.current={id:role,account:role,name:role,role,status:'enabled',mustChangePassword:false,passwordUpdatedAt:''};authState.permissions=[...roleDefinitions.find(r=>r.key===role)!.permissions]
  mount();vi.mocked(loadFobQuoteProduct).mockResolvedValue(sample());await sku('PF2600053');await query()
  expect(Array.from(document.querySelectorAll('.fob-card tbody tr')).map(row=>Array.from(row.querySelectorAll('.quote-price')).map(cell=>cell.textContent))).toEqual([['2.99','3.05'],['2.58','2.63'],['2.43','2.48'],['2.43','2.48'],['2.40','2.44']])
  expect(document.querySelector('.current-price')?.getAttribute('rowspan')).toBe('5')
  const quantity=document.querySelector<HTMLInputElement>('[aria-label="FOB报价数量"]')!;quantity.value='100';quantity.dispatchEvent(new Event('input'));await tick()
  expect([...document.querySelectorAll('.current-price')].map(cell=>cell.textContent)).toEqual(['报关 $2.58/件','不报关 $2.63/件'])
  expect(document.querySelector('.current-heading')!.textContent).toContain('100件 · 对外报价请用此处')
  expect(document.querySelector('.selected')!.textContent).toContain('阶梯2当前档')
})
it('keeps SKU query inline before and after loading, renders all five tiers and copies the actual quantity quote',async()=>{
  const writeText=vi.fn().mockResolvedValue(undefined);Object.defineProperty(navigator,'clipboard',{value:{writeText},configurable:true})
  mount();expect(document.querySelector('[role="dialog"]')).toBeNull()
  expect(document.querySelector('.fob-card form[aria-label="FOB查询"]')).not.toBeNull()
  vi.mocked(loadFobQuoteProduct).mockResolvedValue(sample());await sku('PF2600053');await query()
  expect(document.querySelector('[role="dialog"]')).toBeNull();expect(document.querySelectorAll('.fob-card tbody tr')).toHaveLength(5)
  expect(document.querySelector<HTMLInputElement>('[aria-label="FOB查询SKU"]')?.value).toBe('PF2600053')
  expect(document.querySelector('.fob-card form[aria-label="FOB查询"]')).not.toBeNull()
  expect(document.querySelectorAll('[aria-label="FOB报价单预览"] tbody tr')).toHaveLength(5)
  expect(document.body.textContent).toContain('FOB数据');expect(document.body.textContent).toContain('报关 $2.99/件')
  const input=document.querySelector<HTMLInputElement>('[aria-label="FOB报价数量"]')!;input.value='100';input.dispatchEvent(new Event('input',{bubbles:true}));await tick()
  expect(document.body.textContent).toContain('报关 $2.58/件')
  Array.from(document.querySelectorAll('button')).find(b=>b.textContent==='复制当前数量报价')!.click();await tick()
  expect(writeText).toHaveBeenCalledWith(expect.stringContaining('PF2600053\t100\t2.58\t2.63'))
})
it('previews and copies customer-only prices and refreshes the current quantity view',async()=>{
  mount();vi.mocked(loadFobQuoteProduct).mockResolvedValue(sample());await sku('PF2600053');await query()
  const sheet=document.querySelector('[aria-label="FOB客户报价单"]')!
  const preview=()=>sheet.querySelector('[aria-label="FOB报价单预览"]')!
  expect(preview().textContent).toContain('PF2600053')
  for(const text of ['采购价','成本价','汇率','×1.14','FOB数据']) expect(preview().textContent).not.toContain(text)
  const mode=sheet.querySelector<HTMLSelectElement>('select')!;mode.value='quantity';mode.dispatchEvent(new Event('change'));await tick()
  const input=document.querySelector<HTMLInputElement>('[aria-label="FOB报价数量"]')!;input.value='100';input.dispatchEvent(new Event('input'));await tick()
  expect(preview().querySelectorAll('tbody tr')).toHaveLength(1);expect(preview().textContent).toContain('$2.58')
  Array.from(sheet.querySelectorAll('button')).find(b=>b.textContent==='复制报价数据')!.click();await tick()
  expect(copyQuoteSheetData).toHaveBeenCalledWith(expect.objectContaining({rows:[expect.objectContaining({sku:'PF2600053',quantityRange:'100 pcs',prices:[2.58,2.63]})]}))
  expect(renderCustomerQuoteSheet).toHaveBeenLastCalledWith(expect.objectContaining({showQuantityRange:true,priceGroupLabel:'FOB Unit Price (USD)'}),expect.any(Function))
  expect(document.body.textContent).not.toContain('资料更新时间')
  input.value='0';input.dispatchEvent(new Event('input'));await tick()
  expect(preview()).toBeNull();expect(sheet.textContent).toContain('正整数')
  expect(Array.from(sheet.querySelectorAll('button')).find(b=>b.textContent==='复制报价数据')!.disabled).toBe(true)
})
it('discards late results when the SKU changes and keeps errors visible on a failed query',async()=>{
  mount();let finish!:(p:FobQuoteProduct)=>void
  vi.mocked(loadFobQuoteProduct).mockImplementationOnce(()=>new Promise(resolve=>{finish=resolve}))
  await sku('PF2600053');await query();await sku('PF2600054');finish(sample());await tick()
  expect(document.querySelectorAll('tbody tr')).toHaveLength(0);expect(document.querySelector('[role="dialog"]')).toBeNull()
  expect(document.querySelector('.fob-card form[aria-label="FOB查询"]')).not.toBeNull()
  vi.mocked(loadFobQuoteProduct).mockRejectedValueOnce(new Error('运费缺失'));await query()
  expect(document.body.textContent).toContain('运费缺失');expect(document.querySelectorAll('tbody tr')).toHaveLength(0)
})
it('carries edited FOB notes into both the shared image renderer and copied quotation data',async()=>{
  mount();vi.mocked(loadFobQuoteProduct).mockResolvedValue(sample());await sku('PF2600053');await query()
  const button=(name:string)=>Array.from(document.querySelectorAll('button')).find(b=>b.textContent===name)!
  button('编辑报价单').click();await tick()
  const note=document.querySelector<HTMLTextAreaElement>('[aria-label="FOB第 1 条报价说明"]')!
  expect(note).not.toBeNull()
  expect(document.querySelectorAll('.sheet-notes-editor textarea')).toHaveLength(2)
  expect(document.body.textContent).not.toContain('Payment methods: PayPal, Payoneer, Bank transfer, etc.')
  expect(document.body.textContent).not.toContain('All quotes are all-inclusive')
  note.value='FOB prices in USD. Please confirm quantities before ordering.';note.dispatchEvent(new Event('input',{bubbles:true}));await tick()
  button('预览报价单').click();await tick()
  expect(renderCustomerQuoteSheet).toHaveBeenLastCalledWith(expect.objectContaining({notes:expect.arrayContaining([note.value])}),expect.any(Function))
  button('复制报价数据').click();await tick()
  expect(copyQuoteSheetData).toHaveBeenLastCalledWith(expect.objectContaining({notes:expect.arrayContaining([note.value])}))
})
it('never displays prices with failed finance loading and blocks invalid quantities',async()=>{
  mount('财务读取失败');vi.mocked(loadFobQuoteProduct).mockResolvedValue(sample());await sku('PF2600053');await query()
  expect(document.body.textContent).toContain('财务读取失败');expect(document.querySelectorAll('tbody tr')).toHaveLength(0)
  app?.unmount();document.body.innerHTML='';mount();await sku('PF2600053');await query()
  const input=document.querySelector<HTMLInputElement>('[aria-label="FOB报价数量"]')!;input.value='0';input.dispatchEvent(new Event('input',{bubbles:true}));await tick()
  expect(document.body.textContent).toContain('正整数');expect(document.querySelector('.final-quote')).toBeNull()
  expect(Array.from(document.querySelectorAll('button')).find(b=>b.textContent==='复制当前数量报价')!.disabled).toBe(true)
})
