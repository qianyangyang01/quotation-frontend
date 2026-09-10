// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import View from './QuotationRecordsView.vue'
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
it('places pagination above records and resets date and page size changes to page one',async()=>{
  vi.useFakeTimers();query.loadRecordPage.mockResolvedValue(result(35));await mount()
  const nav=document.querySelector('[aria-label="报价记录分页"]')!;expect(nav.compareDocumentPosition(document.querySelector('.records')!) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
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
