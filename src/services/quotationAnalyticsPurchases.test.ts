import { beforeEach, expect, it, vi } from 'vitest'
const get = vi.hoisted(() => vi.fn())
vi.mock('@/services/http', () => ({ api: { get } }))
import { loadAnalyticsPurchases } from './quotationAnalyticsPurchases'
import { normalizePurchaseRecord } from '@/data/purchaseStore'
import { buildCategoryPerformance, buildDashboardSummary, filterQuotationRecords, quotationDetailsCsv } from '@/data/quotationAnalytics'
import type { QuotationRecord } from '@/data/quotationRecords'
beforeEach(() => get.mockReset())
it('loads one complete snapshot and preserves full-payload analytics and exports', async () => {
  const products = Array.from({ length: 1201 }, (_, i) => ({ sku: ' s-' + i + ' ', category: i % 2 ? ' 服装 ' : '', purchasePriceCny: i % 3 ? String(i / 100) : null }))
  get.mockResolvedValue({ items: products, total: products.length })
  const compact = await loadAnalyticsPurchases()
  const full = products.map(item => normalizePurchaseRecord(item as never))
  const records = [{ no: 'Q1', primarySku: 'S-1', customerName: '客户', productSummary: '服装', salespersonName: '业务', salespersonAccount: 'E1', createdAt: '2026-09-23T08:00:00Z', country: '美国', status: 'won', systemQuoteUsd: 12.34, systemQuoteCny: 86.38, totalCostCny: 40 }] as QuotationRecord[]
  expect(buildCategoryPerformance(records, compact)).toEqual(buildCategoryPerformance(records, full))
  expect(quotationDetailsCsv(records, compact)).toBe(quotationDetailsCsv(records, full))
  for (const category of ['', '服装', '其他']) {
    const filters = { keyword: '', startDate: '', endDate: '', country: '', salesperson: '', category }
    expect(buildDashboardSummary(filterQuotationRecords(records, filters, compact))).toEqual(buildDashboardSummary(filterQuotationRecords(records, filters, full)))
  }
  expect(Object.keys(compact[0]!)).toEqual(['sku', 'category', 'purchasePriceCny'])
  expect(get).toHaveBeenCalledOnce()
  expect(get).toHaveBeenCalledWith('/purchase-products/analytics-catalog', expect.objectContaining({ cache: 'no-store' }))
})
it.each([{ items: [{ sku: 'A' }], total: 2 }, { items: [{ sku: 'a' }, { sku: 'A' }], total: 2 }, { items: [{ sku: '' }], total: 1 }])('rejects incomplete and duplicate catalogs', async response => {
  get.mockResolvedValue(response)
  await expect(loadAnalyticsPurchases()).rejects.toThrow('不完整')
})
it('accepts a genuinely empty catalog', async () => {
  get.mockResolvedValue({ items: [], total: 0 })
  expect(await loadAnalyticsPurchases()).toEqual([])
})
