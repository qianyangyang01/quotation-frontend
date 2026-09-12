import { afterEach, expect, it, vi } from 'vitest'
import { api } from '@/services/http'
import { loadQuotationRecords } from './quotationRecords'
afterEach(() => vi.restoreAllMocks())
const rows = (from: number, count: number) => Array.from({ length: count }, (_, i) => ({ id: `q${from+i}`, no: `QT${from+i}`, createdAt: '2026-09-12T00:00:00Z' }))
it('includes records beyond the first 100 in analytics and exports, keeping scope', async () => {
  const get = vi.spyOn(api, 'get').mockResolvedValueOnce({ items: rows(0,100), total: 110, totalPages: 2 }).mockResolvedValueOnce({ items: rows(100,10), total:110,totalPages:2 })
  expect(await loadQuotationRecords('mine')).toHaveLength(110)
  expect(get.mock.calls.map(c=>c[0])).toEqual(['/quotations?scope=mine&size=100&page=0','/quotations?scope=mine&size=100&page=1'])
})
it('restarts when concurrent insertion shifts page boundaries', async () => {
  vi.spyOn(api,'get').mockResolvedValueOnce({items:rows(0,100),total:110,totalPages:2}).mockResolvedValueOnce({items:rows(99,12),total:111,totalPages:2})
    .mockResolvedValueOnce({items:rows(0,100),total:111,totalPages:2}).mockResolvedValueOnce({items:rows(100,11),total:111,totalPages:2})
  expect(await loadQuotationRecords()).toHaveLength(111)
})
it('does not expose partial statistics on a failed later page', async () => {
  vi.spyOn(api,'get').mockResolvedValueOnce({items:rows(0,100),total:110,totalPages:2}).mockRejectedValueOnce(new Error('network failure'))
  await expect(loadQuotationRecords()).rejects.toThrow('network failure')
})
it('rejects repeatedly inconsistent or duplicate pages', async () => {
  vi.spyOn(api,'get').mockImplementation(async path => path.endsWith('page=0') ? {items:rows(0,100),total:110,totalPages:2} : {items:rows(99,10),total:110,totalPages:2})
  await expect(loadQuotationRecords()).rejects.toThrow('完整统计尚未读取成功')
})
it('accepts an empty collection', async () => {
  vi.spyOn(api,'get').mockResolvedValue({items:[],total:0,totalPages:0})
  expect(await loadQuotationRecords()).toEqual([])
})
