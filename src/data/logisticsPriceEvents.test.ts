// @vitest-environment happy-dom
import {afterEach,expect,it,vi} from 'vitest'
import {notifyLogisticsPriceChange,subscribeLogisticsPriceChanges,LOGISTICS_PRICE_EVENT} from './logisticsPriceEvents'
let stop=()=>{}
afterEach(()=>{stop();localStorage.clear();vi.unstubAllGlobals()})
it('delivers a completion only once across local, storage and broadcast notifications',()=>{
 const receive=vi.fn();stop=subscribeLogisticsPriceChanges(receive)
 notifyLogisticsPriceChange('published','one');notifyLogisticsPriceChange('published','one')
 window.dispatchEvent(new StorageEvent('storage',{key:LOGISTICS_PRICE_EVENT,newValue:localStorage.getItem(LOGISTICS_PRICE_EVENT)}))
 expect(receive).toHaveBeenCalledTimes(1)
 notifyLogisticsPriceChange('imported','batch');expect(receive).toHaveBeenCalledTimes(2)
 stop();notifyLogisticsPriceChange('published','two');expect(receive).toHaveBeenCalledTimes(2)
})
it('replays a missed completion when the page opens and works without broadcast support',()=>{
 vi.stubGlobal('BroadcastChannel',undefined)
 notifyLogisticsPriceChange('published','before-open')
 const receive=vi.fn();stop=subscribeLogisticsPriceChanges(receive)
 expect(receive).toHaveBeenCalledWith(expect.objectContaining({id:'published:before-open'}))
 notifyLogisticsPriceChange('rollback','later');expect(receive).toHaveBeenCalledTimes(2)
})
it('a throwing display listener cannot break a successful publication notification',()=>{
 stop=subscribeLogisticsPriceChanges(()=>{throw new Error('chart failed')})
 expect(()=>notifyLogisticsPriceChange('published','broken-chart')).not.toThrow()
})
it('disabled storage, broken broadcast and event dispatch cannot turn publication into failure',()=>{
 const dispatch=vi.spyOn(window,'dispatchEvent').mockImplementation(()=>{throw new Error('dispatch failed')})
 const storage=vi.spyOn(Storage.prototype,'setItem').mockImplementation(()=>{throw new Error('quota')})
 vi.stubGlobal('BroadcastChannel',class {constructor(){throw new Error('unavailable')}})
 try{expect(()=>notifyLogisticsPriceChange('published','broken-transports')).not.toThrow()}
 finally{dispatch.mockRestore();storage.mockRestore()}
})
