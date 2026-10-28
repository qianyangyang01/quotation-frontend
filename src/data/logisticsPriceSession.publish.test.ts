// @vitest-environment happy-dom
import {afterEach,beforeEach,expect,it,vi} from 'vitest'
import {logisticsPriceSession,clearLogisticsPriceSessions} from './logisticsPriceSession'
import {logisticsRebuild as service,type Version} from './logisticsRebuild'
import {realChannelHistories,comparedVersions,type RealPriceSnapshot} from './logisticsRealPriceHistory'
const http=vi.hoisted(()=>({get:vi.fn(),post:vi.fn(),request:vi.fn()}))
vi.mock('@/services/http',()=>({api:{get:http.get,post:http.post},request:http.request,idempotencyKey:()=> 'fixture-key',downloadFile:vi.fn()}))
vi.mock('@/services/logisticsResumableUpload',()=>({resumableLogisticsUpload:vi.fn()}))
const snapshot=(latest:number):RealPriceSnapshot=>({
 capturedAt:'2026-10-28T02:00:00Z',sourceRevision:'r'+latest,dataset:'isolated-test',
 channels:[{id:'channel',ruleId:1,code:'TEST',provider:'fixture',current:'v'+latest,name:'fixture',attribute:'普货',enabled:true,quoteReady:true}],
 versions:Array.from({length:latest},(_,i)=>({id:'v'+(i+1),channel:'channel',number:i+1,status:i+1===latest?'published':'superseded',publishedAt:`2026-10-${20+i}T01:00:00Z`,fileName:'fixture.xlsx',rows:[],preparedPrices:{'NZ|1':{'全国统一':{price:15+i*10,rate:10+i*10,fee:5,chargeWeight:1,model:'per-kg',intervalPrice:null,reason:''}}}})),
})
beforeEach(()=>{vi.useFakeTimers();vi.clearAllMocks();localStorage.clear();clearLogisticsPriceSessions();vi.stubGlobal('BroadcastChannel',class {onmessage=null;postMessage(){}close(){}})})
afterEach(()=>{clearLogisticsPriceSessions();vi.useRealTimers();vi.unstubAllGlobals()})
it('successful business publication reaches the retained session and updates history and comparison once',async()=>{
 http.request.mockResolvedValueOnce(snapshot(1)).mockResolvedValueOnce(snapshot(2))
 const state=logisticsPriceSession('system');await state.ensureLoaded()
 expect(realChannelHistories(state.snapshot.value!,'NZ',1)[0]!.current!.price).toBe(15)
 const version={id:'published-session-fixture',channelId:'channel',status:'published'} as Version
 http.post.mockResolvedValue(version)
 await service.review(version,'isolated approval',true,true,'fixture-key')
 await vi.advanceTimersByTimeAsync(250)
 const history=realChannelHistories(state.snapshot.value!,'NZ',1)[0]!
 expect(history.current!.price).toBe(25);expect(history.history.map(p=>p.price)).toEqual([15,25])
 expect(comparedVersions(history,'currentPrevious').change).toBe(10)
 await state.ensureLoaded();await vi.advanceTimersByTimeAsync(120000)
 expect(http.request).toHaveBeenCalledTimes(2)
})
it('a failed business publication does not refresh or alter the cached price history',async()=>{
 http.request.mockResolvedValue(snapshot(1));const state=logisticsPriceSession('system');await state.ensureLoaded()
 http.post.mockRejectedValue(new Error('publication failed'))
 await expect(service.review({id:'failed-session-fixture',channelId:'channel'} as Version,'isolated approval',true,true,'fixture-key')).rejects.toThrow('publication failed')
 await vi.advanceTimersByTimeAsync(120000)
 expect(http.request).toHaveBeenCalledTimes(1);expect(realChannelHistories(state.snapshot.value!,'NZ',1)[0]!.current!.price).toBe(15)
})
