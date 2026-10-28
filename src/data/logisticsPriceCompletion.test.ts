import {beforeEach,expect,it,vi} from 'vitest'
const mocks=vi.hoisted(()=>({get:vi.fn(),post:vi.fn(),notify:vi.fn()}))
vi.mock('@/services/http',()=>({api:{get:mocks.get,post:mocks.post},idempotencyKey:()=> 'key',downloadFile:vi.fn()}))
vi.mock('@/services/logisticsResumableUpload',()=>({resumableLogisticsUpload:vi.fn()}))
vi.mock('./logisticsPriceEvents',()=>({notifyLogisticsPriceChange:mocks.notify}))
import {logisticsRebuild as service,type Version} from './logisticsRebuild'
beforeEach(()=>vi.resetAllMocks())
it('notifies only completed imports and successfully published versions',async()=>{
 mocks.get.mockResolvedValueOnce({id:'b',status:'processing'}).mockResolvedValueOnce({id:'b',status:'completed',payload:{results:[{versionId:'v'}]}})
 await service.batch('b');expect(mocks.notify).not.toHaveBeenCalled()
 await service.batch('b');expect(mocks.notify).toHaveBeenCalledWith('imported','b:v')
 mocks.notify.mockClear();mocks.post.mockRejectedValueOnce(new Error('failed'))
 const v={id:'v',channelId:'c'} as Version
 await expect(service.review(v,'',true,true,'k')).rejects.toThrow('failed');expect(mocks.notify).not.toHaveBeenCalled()
 mocks.post.mockResolvedValueOnce({...v,status:'published'})
 await service.review(v,'',true,true,'k');expect(mocks.notify).toHaveBeenCalledWith('published','v')
})
it('emits one notification for a partial-success batch and none for an all-failed batch',async()=>{
 mocks.post.mockResolvedValueOnce({publishedCount:0,published:[],failedCount:2}).mockResolvedValueOnce({publishedCount:2,published:[{versionId:'b'},{versionId:'a'}],failedCount:1})
 await service.publishReady('batch',[],'','key',{coverageToken:'t',partialUpdateConfirmed:true});expect(mocks.notify).not.toHaveBeenCalled()
 await service.publishReady('batch',[],'','key2',{coverageToken:'t',partialUpdateConfirmed:true})
 expect(mocks.notify).toHaveBeenCalledTimes(1);expect(mocks.notify).toHaveBeenCalledWith('published','a,b')
})

it('never changes successful business results when display notification or its optional fields fail',async()=>{
 const v={id:'v',channelId:'c',status:'published'} as Version
 mocks.post.mockResolvedValueOnce(v);mocks.notify.mockImplementation(()=>{throw new Error('display failed')})
 await expect(service.review(v,'QA',true,true,'key')).resolves.toBe(v)
 const partial={publishedCount:2,published:null};mocks.post.mockResolvedValueOnce(partial)
 await expect(service.publishReady('batch',[],'QA','key',{coverageToken:'t',partialUpdateConfirmed:true})).resolves.toBe(partial)
 const batch={id:'batch',status:'completed',payload:{}};mocks.get.mockResolvedValueOnce(batch)
 await expect(service.batch('batch')).resolves.toBe(batch)
})
