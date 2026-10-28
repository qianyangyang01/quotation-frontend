// @vitest-environment happy-dom
import {createApp,nextTick} from 'vue'
import {afterEach,beforeEach,expect,it,vi} from 'vitest'
import LocalView from './LogisticsPriceChangesLocalView.vue'
import {LOGISTICS_PRICE_EVENT} from '@/data/logisticsPriceEvents'
const native=vi.hoisted(()=>({request:vi.fn()}))
vi.mock('@/services/http',()=>({request:native.request}))

vi.mock('@/components/logistics/LogisticsPriceChanges.vue',()=>({default:{
  props:['snapshot','error','checkedAt','refreshing'],emits:['refresh','workspace'],
  template:'<div><span>{{snapshot.capturedAt}}</span><p>{{error}}</p><small>{{checkedAt}}</small><button @click="$emit(\'refresh\')">refresh</button></div>',
}}))
let unmount:()=>void
const request=vi.fn()
const snapshot=(capturedAt='2026-10-27T00:00:00Z')=>({capturedAt,channels:[],versions:[]})
const response=(data:unknown,status=200)=>new Response(status===204?null:JSON.stringify(data),{status,headers:{'X-Logistics-Checked-At':'2026-10-28T01:00:00Z'}})
async function flush(){for(let i=0;i<15;i++)await Promise.resolve();await nextTick()}
async function mount(source:'preview'|'system'='preview'){const host=document.createElement('div');document.body.append(host);const app=createApp(LocalView,{source});app.mount(host);unmount=()=>{app.unmount();host.remove()};await flush();return host}
beforeEach(()=>{
  vi.useFakeTimers();localStorage.clear();request.mockReset();native.request.mockReset();vi.stubGlobal('fetch',request)
  vi.stubGlobal('BroadcastChannel',class {onmessage=null;close(){}})
  Object.defineProperty(document,'visibilityState',{value:'visible',configurable:true})
})
afterEach(()=>{unmount?.();vi.useRealTimers();vi.unstubAllGlobals()})


function completed(id:string){window.dispatchEvent(new CustomEvent(LOGISTICS_PRICE_EVENT,{detail:{id,kind:'published',at:Date.now()}}))}
it('uses already-calculated system results after publication without calling the slow preview collector',async()=>{
 native.request.mockResolvedValueOnce({...snapshot(),sourceRevision:'r1'}).mockResolvedValueOnce({...snapshot('2026-10-28T02:00:00Z'),sourceRevision:'r2'})
 const host=await mount('system');completed('native-v4');completed('native-v4');await vi.advanceTimersByTimeAsync(250);await flush()
 expect(native.request).toHaveBeenCalledTimes(2);expect(native.request.mock.calls[1]![0]).toBe('/logistics/price-history')
 expect(request).not.toHaveBeenCalled();expect(host.textContent).toContain('2026-10-28T02:00:00Z')
 await vi.advanceTimersByTimeAsync(120000);expect(native.request).toHaveBeenCalledTimes(2)
})
it('does not poll or refresh on focus, and coalesces duplicate completion messages',async()=>{
  request.mockResolvedValueOnce(response(snapshot())).mockResolvedValueOnce(response(null,204))
  const host=await mount()
  await vi.advanceTimersByTimeAsync(120000);window.dispatchEvent(new Event('focus'));document.dispatchEvent(new Event('visibilitychange'))
  expect(request).toHaveBeenCalledTimes(1)
  completed('v4');completed('v4');await vi.advanceTimersByTimeAsync(250);await flush()
  expect(request).toHaveBeenCalledTimes(2)
  expect(request.mock.calls[1]![0]).toContain('/sync?capturedAt=')
  expect(host.textContent).toContain('2026-10-28T01:00:00Z')
  await vi.advanceTimersByTimeAsync(120000)
  expect(request).toHaveBeenCalledTimes(2)
})
it('manual refresh runs once, adopts a new snapshot, and preserves it on errors without auto retry',async()=>{
  request.mockResolvedValueOnce(response(snapshot())).mockResolvedValueOnce(response(snapshot('2026-10-28T02:00:00Z'))).mockResolvedValueOnce(response(null,503))
  const host=await mount()
  host.querySelector('button')!.click();host.querySelector('button')!.click();await flush()
  expect(request.mock.calls[1]![0]).toContain('/__local-logistics/history/refresh?capturedAt=')
  expect(request).toHaveBeenCalledTimes(2)
  expect(host.textContent).toContain('2026-10-28T02:00:00Z')
  host.querySelector('button')!.click();await flush()
  expect(host.textContent).toContain('503');expect(host.textContent).toContain('2026-10-28T02:00:00Z')
  await vi.advanceTimersByTimeAsync(120000)
  expect(request).toHaveBeenCalledTimes(3)
})
it('does not lose a publication arriving during refresh, and stops on unmount',async()=>{
  let finish!:(r:Response)=>void
  request.mockResolvedValueOnce(response(snapshot())).mockImplementationOnce(()=>new Promise<Response>(resolve=>{finish=resolve})).mockResolvedValueOnce(response(snapshot('2026-10-28T03:00:00Z')))
  const host=await mount()
  completed('v4');await vi.advanceTimersByTimeAsync(250)
  completed('v5');completed('v5');completed('v6');host.querySelector('button')!.click()
  await vi.advanceTimersByTimeAsync(1000)
  expect(request).toHaveBeenCalledTimes(2)
  finish(response(null,204));await flush();await vi.advanceTimersByTimeAsync(250);await flush()
  expect(request).toHaveBeenCalledTimes(3)
  expect(host.textContent).toContain('2026-10-28T03:00:00Z')
  unmount();unmount=()=>{};completed('v7');await vi.advanceTimersByTimeAsync(120000)
  expect(request).toHaveBeenCalledTimes(3)
})

it('ends a hung native read after eight seconds and ignores its late response without automatic retries',async()=>{
 let finish!:(data:unknown)=>void
 native.request.mockResolvedValueOnce({...snapshot(),sourceRevision:'r1'})
   .mockImplementationOnce(()=>new Promise(resolve=>{finish=resolve}))
   .mockResolvedValueOnce({...snapshot('2026-10-28T03:00:00Z'),sourceRevision:'r3'})
 const host=await mount('system');host.querySelector('button')!.click();await flush()
 const signal=native.request.mock.calls[1]![1].signal as AbortSignal
 await vi.advanceTimersByTimeAsync(8000);await flush()
 expect(signal.aborted).toBe(true);expect(host.textContent).toContain('超时')
 expect(host.textContent).toContain('2026-10-27T00:00:00Z')
 finish({...snapshot('2026-10-28T02:00:00Z'),sourceRevision:'r2'});await flush()
 expect(host.textContent).not.toContain('2026-10-28T02:00:00Z')
 await vi.advanceTimersByTimeAsync(120000);expect(native.request).toHaveBeenCalledTimes(2)
 host.querySelector('button')!.click();await flush();expect(host.textContent).toContain('2026-10-28T03:00:00Z')
})

it.each([
 {sourceRevision:'r2',channels:[],versions:[],capturedAt:'invalid date'},
 {...snapshot(),sourceRevision:'r2',channels:[null]},
 {...snapshot(),sourceRevision:'r2',versions:[{id:'bad',rows:null}]},
 null,
])('rejects a damaged response and retains the last valid snapshot: %j',async bad=>{
 native.request.mockResolvedValueOnce({...snapshot(),sourceRevision:'r1'}).mockResolvedValueOnce(bad)
 const host=await mount('system');host.querySelector('button')!.click();await flush()
 expect(host.textContent).toContain('数据有误');expect(host.textContent).toContain('2026-10-27T00:00:00Z')
 await vi.advanceTimersByTimeAsync(120000);expect(native.request).toHaveBeenCalledTimes(2)
})

it('initial API failure leaves a return path and never retries on its own',async()=>{
 native.request.mockRejectedValueOnce(new Error('价格展示暂不可用（503）'))
 const host=await mount('system');expect(host.textContent).toContain('503')
 expect(host.textContent).toContain('返回运费规则列表')
 await vi.advanceTimersByTimeAsync(120000);expect(native.request).toHaveBeenCalledTimes(1)
})

it('unmount aborts a pending native request and discards late data',async()=>{
 let finish!:(data:unknown)=>void
 native.request.mockImplementationOnce(()=>new Promise(resolve=>{finish=resolve}))
 await mount('system');const signal=native.request.mock.calls[0]![1].signal as AbortSignal
 unmount();unmount=()=>{};expect(signal.aborted).toBe(true)
 finish({...snapshot(),sourceRevision:'late'});await flush();await vi.advanceTimersByTimeAsync(120000)
 expect(native.request).toHaveBeenCalledTimes(1)
})
