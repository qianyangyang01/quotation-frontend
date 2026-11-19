// @vitest-environment happy-dom
import { createApp, nextTick, reactive, type App } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import Component from './QuotationChannelRanking.vue'
const mock=vi.hoisted(()=>({load:vi.fn(),account:{account:'ADMIN'}}))
vi.mock('@/services/quotationChannelRanking',()=>({loadChannelRanking:mock.load}))
vi.mock('@/data/authStore',()=>({currentAuthUser:{value:mock.account},hasPermission:()=>true}))
let app:App,root:HTMLDivElement
const response=(country='美国')=>({items:[{key:country,country,provider:'甲',channel:'标准',uses:8,share:100,latestAt:'2026-11-19T01:00:00Z',state:'unverified',channelId:'c',datasetId:'d'}],totalUses:8,totalGroups:1,pendingGroups:0,unmatchedGroups:0,topFiveShare:100,total:1,page:0,size:5,totalPages:1,startDate:'2026-10-21',endDate:'2026-11-19',generatedAt:'2026-11-19T02:00:00Z'})
function mount(){const filters=reactive({startDate:'2026-10-21',endDate:'2026-11-19',country:'',salesperson:'',category:'',keyword:''});root=document.createElement('div');document.body.append(root);app=createApp(Component,{filters});app.component('RouterLink',{template:'<a><slot /></a>'});app.mount(root);return filters}
async function settle(){await vi.advanceTimersByTimeAsync(310);await nextTick()}
beforeEach(()=>{vi.useFakeTimers();mock.load.mockReset();mock.account.account='ADMIN'})
afterEach(()=>{app?.unmount();root?.remove();vi.useRealTimers()})
it('debounces filters, aborts old requests and ignores late responses',async()=>{
 let oldResolve:(value:unknown)=>void=()=>{}
 mock.load.mockImplementationOnce(()=>new Promise(resolve=>{oldResolve=resolve})).mockResolvedValueOnce(response('德国'))
 const filters=mount();await settle()
 const oldSignal=mock.load.mock.calls[0][4] as AbortSignal
 filters.keyword='a';filters.keyword='ab';filters.country='德国';await settle()
 expect(mock.load).toHaveBeenCalledTimes(2);expect(oldSignal.aborted).toBe(true)
 oldResolve(response('美国'));await nextTick();await nextTick()
 expect(root.querySelector('tbody')?.textContent).toContain('德国')
 expect(root.querySelector('tbody')?.textContent).not.toContain('美国')
})
it('shows a recoverable failure instead of misleading zero statistics',async()=>{
 mock.load.mockRejectedValueOnce(new Error('统计超时')).mockResolvedValueOnce(response())
 mount();await settle();expect(root.querySelector('[role="alert"]')?.textContent).toContain('统计超时')
 expect(root.querySelector('.ranking-summary')).toBeNull()
 ;(root.querySelector('[role="alert"] button')as HTMLButtonElement).click();await settle()
 expect(root.querySelector('tbody')?.textContent).toContain('美国')
 expect(root.textContent).not.toContain('已核查')
})
it('paginates immediately with bounded requests, resets on filters and cancels pending work on unmount',async()=>{
 mock.load.mockImplementation(async(_filters,page,size)=>({...response(),page,size,total:774,totalPages:Math.ceil(774/size)}))
 const filters=mount();await settle()
 expect(mock.load.mock.calls[0].slice(1,4)).toEqual([0,5,false])
 expect(root.querySelector('.ranking-pagination')).not.toBeNull()
 expect(root.textContent).not.toContain('查看全部')
 expect(mock.load).toHaveBeenCalledTimes(1)
 ;([...root.querySelectorAll('button')].find(b=>b.textContent==='下一页') as HTMLButtonElement).click();await settle()
 expect(mock.load.mock.lastCall?.slice(1,4)).toEqual([1,5,false])
 expect(root.querySelector('tbody td i')?.textContent).toBe('6')
 const select=root.querySelector('.ranking-pagination select') as HTMLSelectElement
 select.value='20';select.dispatchEvent(new Event('change'));await settle()
 expect(mock.load.mock.lastCall?.slice(1,4)).toEqual([0,20,false])
 ;([...root.querySelectorAll('button')].find(b=>b.textContent==='下一页') as HTMLButtonElement).click();await settle()
 filters.country='德国';await settle()
 expect(mock.load.mock.lastCall?.slice(1,4)).toEqual([0,20,false])
 expect(mock.load.mock.lastCall?.[0].country).toBe('德国')
 const signal=mock.load.mock.lastCall?.[4] as AbortSignal
 app.unmount();expect(signal.aborted).toBe(true)
})
