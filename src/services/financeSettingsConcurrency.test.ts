import { beforeEach, describe, expect, it, vi } from 'vitest'
const http = vi.hoisted(() => ({ get: vi.fn(), put: vi.fn() }))
vi.mock('@/services/http', () => ({ api: http }))
import { clearFinanceSettingsCache, financeSettingVersions, hydrateFinanceSettings, readFinanceSetting, writeFinanceSetting } from './financeSettings'
import type { FinanceSettingKey } from './financeSettings'

function deferred<T>() { let resolve!: (value:T)=>void; const promise=new Promise<T>(r=>{resolve=r});return {promise,resolve} }
function snapshot(version=1, rate=6.7) {
  return {
    'country-classification': {value:[],_version:version}, 'channel-policies': {value:[],_version:version},
    'customer-grades': {value:[],_version:version}, 'exchange-rate': {value:{usdCny:rate},_version:version},
    'tax-settings': {value:{countries:[],providers:[]},_version:version},
    'surcharge-settings': {value:{countries:[],providers:[]},_version:version},
    'customer-operation-fees': {value:{customers:[]},_version:version},
  }
}
beforeEach(()=>{vi.resetAllMocks();clearFinanceSettingsCache()})
describe('finance concurrent reads and writes',()=>{
  it.each(Object.keys(snapshot()) as FinanceSettingKey[])('preserves a newer %s save against late background hydration',async key=>{
    http.get.mockResolvedValueOnce(snapshot());await hydrateFinanceSettings()
    const old=deferred<ReturnType<typeof snapshot>>();http.get.mockReturnValueOnce(old.promise)
    const refresh=hydrateFinanceSettings({force:true})
    const value=structuredClone(snapshot()[key].value)
    http.put.mockResolvedValueOnce({value,_version:2})
    await writeFinanceSetting(key,value)
    old.resolve(snapshot());await refresh
    expect(financeSettingVersions()[key]).toBe(2)
    expect(readFinanceSetting(key)).toEqual(value)
  })
  it('does not roll back a completed save when an older background response arrives late',async()=>{
    http.get.mockResolvedValueOnce(snapshot());await hydrateFinanceSettings()
    const old=deferred<ReturnType<typeof snapshot>>();http.get.mockReturnValueOnce(old.promise)
    const refresh=hydrateFinanceSettings({force:true})
    http.put.mockResolvedValueOnce({value:{usdCny:7.2},_version:2})
    await writeFinanceSetting('exchange-rate',{usdCny:7.2})
    old.resolve(snapshot());await refresh
    expect(readFinanceSetting('exchange-rate')).toEqual({usdCny:7.2})
    expect(financeSettingVersions()['exchange-rate']).toBe(2)
  })
  it('does not roll back a newer read when a previous save response arrives late',async()=>{
    http.get.mockResolvedValueOnce(snapshot());await hydrateFinanceSettings()
    const saved=deferred<{value:{usdCny:number};_version:number}>();http.put.mockReturnValueOnce(saved.promise)
    const write=writeFinanceSetting('exchange-rate',{usdCny:7.2})
    http.get.mockResolvedValueOnce(snapshot(3,7.4));await hydrateFinanceSettings({force:true})
    saved.resolve({value:{usdCny:7.2},_version:2});await write
    expect(readFinanceSetting('exchange-rate')).toEqual({usdCny:7.4})
    expect(financeSettingVersions()['exchange-rate']).toBe(3)
  })
  it('discards saves started before the account cache was cleared',async()=>{
    http.get.mockResolvedValueOnce(snapshot());await hydrateFinanceSettings()
    const saved=deferred<{value:{usdCny:number};_version:number}>();http.put.mockReturnValueOnce(saved.promise)
    const write=writeFinanceSetting('exchange-rate',{usdCny:7.2})
    clearFinanceSettingsCache()
    http.get.mockResolvedValueOnce(snapshot(4,7.6));await hydrateFinanceSettings()
    saved.resolve({value:{usdCny:7.2},_version:2});await write
    expect(readFinanceSetting('exchange-rate')).toEqual({usdCny:7.6})
  })
})
