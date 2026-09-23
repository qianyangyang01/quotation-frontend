// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import View from './QuotationRecordsView.vue'
import { normalizeQuotationRecord } from '@/data/quotationRecords'
const query=vi.hoisted(()=>({loadRecordPage:vi.fn(),loadFilteredRecords:vi.fn(),loadRecord:vi.fn(),recentRecordDates:()=>({startDate:'2026-09-04',endDate:'2026-09-10'})}))
vi.mock('@/data/quotationRecordQuery',()=>query)
vi.mock('@/data/purchaseStore',()=>({loadPurchaseProducts:()=>Promise.resolve([])}))
vi.mock('vue-router',()=>({useRoute:()=>({query:{}})}))
let app:App
const result=(total:number,page=0)=>({items:[],page,size:10,total,totalPages:Math.ceil(total/10),summary:{pending:total,won:0,lost:0,total},countries:['美国']})
const flush=async()=>{await nextTick();await Promise.resolve();await nextTick()}
const button=(text:string)=>Array.from(document.querySelectorAll('button')).find(b=>b.textContent===text)!
async function mount(scope='mine'){const host=document.createElement('div');document.body.append(host);app=createApp(View,{scope});app.component('RouterLink',{template:'<a><slot /></a>'});app.mount(host);await flush()}
afterEach(()=>{app?.unmount();document.body.innerHTML='';vi.useRealTimers();vi.resetAllMocks()})
it.each(['mine', 'company'])('places pagination below records and resets date and page size changes to page one for %s',async scope=>{
  vi.useFakeTimers();query.loadRecordPage.mockResolvedValue(result(35));await mount(scope)
  const nav=document.querySelector('[aria-label="报价记录分页"]')!;expect(nav.compareDocumentPosition(document.querySelector('.records')!) & Node.DOCUMENT_POSITION_PRECEDING).toBeTruthy()
  expect(button('导出筛选结果').compareDocumentPosition(document.querySelector('.records')!) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  query.loadRecordPage.mockResolvedValueOnce(result(35,1));button('下一页').click();await flush();expect(query.loadRecordPage.mock.calls[1]![2]).toBe(1)
  button('近 7 天').click();await flush();await vi.advanceTimersByTimeAsync(250);await flush();expect(query.loadRecordPage.mock.lastCall?.[2]).toBe(0);expect(query.loadRecordPage.mock.lastCall?.[1]).toMatchObject({startDate:'2026-09-04',endDate:'2026-09-10'})
  const select=document.querySelector('[aria-label="每页记录数"]') as HTMLSelectElement;select.value='30';select.dispatchEvent(new Event('change'));await flush();await vi.advanceTimersByTimeAsync(250);expect(query.loadRecordPage.mock.lastCall?.[3]).toBe(30)
})
it('ignores an older response after filtering and uses company scope',async()=>{
  vi.useFakeTimers();let resolveOld!:(v:ReturnType<typeof result>)=>void;query.loadRecordPage.mockReturnValueOnce(new Promise(resolve=>{resolveOld=resolve})).mockResolvedValue(result(2))
  await mount('company');button('今天').click();await flush();await vi.advanceTimersByTimeAsync(250);await flush();resolveOld(result(99));await flush()
  expect(document.querySelector('[aria-label="报价记录分页"]')!.textContent).toContain('共 2 条');expect(query.loadRecordPage.mock.lastCall?.[0]).toBe('company')
})
it('blocks a reversed date range without submitting a query',async()=>{
  vi.useFakeTimers();query.loadRecordPage.mockResolvedValue(result(5));await mount()
  for(const [name,value] of [['开始日期','2026-09-11'],['结束日期','2026-09-10']]){const input=document.querySelector(`[aria-label="${name}"]`) as HTMLInputElement;input.value=value!;input.dispatchEvent(new Event('input'))}
  await flush();await vi.advanceTimersByTimeAsync(250);await flush();expect(query.loadRecordPage).toHaveBeenCalledTimes(1);expect(document.querySelector('[role="alert"]')!.textContent).toContain('开始日期不能晚于结束日期')
})

it.each(['mine', 'company'])('filters pending finance reviews and resets pagination for %s', async scope => {
  vi.useFakeTimers(); query.loadRecordPage.mockResolvedValue(result(35)); await mount(scope)
  query.loadRecordPage.mockResolvedValueOnce(result(35,1)); button('下一页').click(); await flush()
  const select = document.querySelector('.filters select') as HTMLSelectElement
  expect(Array.from(select.options).find(option => option.value === 'finance-pending')?.textContent).toBe('待财务审核')
  query.loadRecordPage.mockResolvedValue(result(3))
  select.value = 'finance-pending'; select.dispatchEvent(new Event('change'))
  await flush(); await vi.advanceTimersByTimeAsync(250); await flush()
  expect(query.loadRecordPage.mock.lastCall).toEqual([scope, expect.objectContaining({status:'finance-pending'}), 0, 10])
  expect(document.querySelector('[aria-label="报价记录分页"]')!.textContent).toContain('共 3 条')
  expect(document.querySelector('.stats')!.textContent).toContain('3')
})

it.each(['mine', 'company'])('opens the quotation overview from the detail cell and resets the previous tab for %s', async scope => {
  const row = normalizeQuotationRecord({ id: 'saved', no: 'QT-SAVED', primarySku: 'SKU', customerName: '客户',
    quoteOptions: [{ id: 'us', country: '美国', carrier: '燕文', channel: '原渠道', rule: '原规则', eta: '6-12天', quote1Usd: 19.2, quote2Usd: null, quote3Usd: null, quoteCustomUsd: null }] })!
  query.loadRecordPage.mockResolvedValue({ ...result(1), items: [row] })
  await mount(scope)
  document.querySelector<HTMLButtonElement>('.difference-cell')!.click(); await flush()
  expect(document.querySelector('.detail-tabs .active')?.textContent).toBe('报价概览')
  expect(document.querySelector('.overview-panel')).not.toBeNull()
  expect(document.querySelector('.drawer-view-footer')?.textContent).toContain('复制报价数据')
  document.querySelectorAll<HTMLButtonElement>('.detail-tabs button')[1]!.click(); await flush()
  expect(document.querySelector('.option-detail-panel')).not.toBeNull()
  document.querySelector<HTMLButtonElement>('[aria-label="关闭"]')!.click(); await flush()
  document.querySelector<HTMLButtonElement>('.difference-cell')!.click(); await flush()
  expect(document.querySelector('.detail-tabs .active')?.textContent).toBe('报价概览')
})
