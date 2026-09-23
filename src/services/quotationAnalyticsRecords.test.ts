import { beforeEach, expect, it, vi } from 'vitest'
const get = vi.hoisted(() => vi.fn())
vi.mock('@/services/http', () => ({ api: { get } }))
import { loadAnalyticsRecords } from './quotationAnalyticsRecords'
import { buildDashboardSummary, buildSalespersonRanking, recordCountries } from '@/data/quotationAnalytics'
beforeEach(() => get.mockReset())
it('reads more than one legacy page in one request and preserves amounts and country filters', async () => {
  const items = Array.from({ length: 203 }, (_, i) => ({ id: String(i), no: 'Q'+i, primarySku: 'S'+i, customerName: 'C', salespersonAccount: 'E1', salespersonName: 'E', createdAt: '2026-09-23T01:00:00Z', status: i%2 ? 'pending' : 'won', systemQuoteUsd: 1.05, systemQuoteCny: 7.35, country: '美国', quoteOptions: [{country:'美国'},{country:'英国'}] }))
  get.mockResolvedValue({items,total:items.length})
  const records=await loadAnalyticsRecords()
  expect(buildDashboardSummary(records)).toEqual({quotationCount:203,quotedSkuCount:203,quoteUsd:213.15,quoteCny:1492.05})
  expect(buildSalespersonRanking(records)[0]?.wonProductCount).toBe(102)
  expect(recordCountries(records[0]!)).toEqual(['美国','英国'])
  expect(get).toHaveBeenCalledOnce()
})
it.each([{items:[],total:1},{items:[{id:'a',no:'Q'},{id:'a',no:'Q'}],total:2},{items:[{id:'a'}],total:1}])('does not present incomplete records as complete statistics', async response => {
  get.mockResolvedValue(response)
  await expect(loadAnalyticsRecords()).rejects.toThrow('不完整')
})
