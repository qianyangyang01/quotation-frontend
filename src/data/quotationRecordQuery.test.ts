import { afterEach, describe, expect, it, vi } from 'vitest'
import { loadFilteredRecords, loadRecordPage, recentRecordDates } from './quotationRecordQuery'
const get=vi.hoisted(()=>vi.fn())
vi.mock('@/services/http',()=>({api:{get}}))
afterEach(()=>vi.resetAllMocks())
const row=(id:number)=>({id:String(id),no:'Q-'+id,createdAt:'2026-09-10T00:00:00Z'})
describe('record query',()=>{
  it('encodes all filters and keeps backend page totals',async()=>{
    get.mockResolvedValue({items:[row(1)],page:2,size:30,total:70,totalPages:3})
    expect((await loadRecordPage('mine',{q:'客户 & SKU',startDate:'2026-09-01',endDate:'2026-09-10',country:'法国',status:'won',category:'服装'},2,30)).total).toBe(70)
    const query=new URLSearchParams(get.mock.calls[0]![0].split('?')[1]);expect(query.get('q')).toBe('客户 & SKU');expect(query.get('scope')).toBe('mine');expect(query.get('page')).toBe('2');expect(query.get('endDate')).toBe('2026-09-10')
  })
  it('exports all matching pages beyond 100 using captured filters',async()=>{
    get.mockResolvedValueOnce({items:Array.from({length:100},(_,i)=>row(i)),page:0,total:105,totalPages:2}).mockResolvedValueOnce({items:Array.from({length:5},(_,i)=>row(i+100)),page:1,total:105,totalPages:2})
    expect(await loadFilteredRecords('company',{startDate:'2026-09-01'})).toHaveLength(105)
    expect(get.mock.calls[1]![0]).toContain('page=1');expect(get.mock.calls[1]![0]).toContain('startDate=2026-09-01')
  })
  it('rejects a partial export when records change between requests',async()=>{
    get.mockResolvedValueOnce({items:[row(1)],page:0,total:105,totalPages:2}).mockResolvedValueOnce({items:[],page:1,total:104,totalPages:2})
    await expect(loadFilteredRecords('mine',{})).rejects.toThrow('发生变化')
  })
  it('uses Shanghai today and inclusive seven day ranges across UTC midnight',()=>{
    expect(recentRecordDates(7,new Date('2026-09-09T16:01:00Z'))).toEqual({startDate:'2026-09-04',endDate:'2026-09-10'})
    expect(recentRecordDates(1,new Date('2026-09-09T15:59:59Z'))).toEqual({startDate:'2026-09-09',endDate:'2026-09-09'})
  })
})
