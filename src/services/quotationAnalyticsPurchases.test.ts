import { expect, it, vi } from 'vitest'
const page = vi.hoisted(() => vi.fn())
vi.mock('@/data/purchaseStore', () => ({ loadPurchaseProductPage: page }))
import { loadAnalyticsPurchases } from './quotationAnalyticsPurchases'
it('loads categories beyond the first page and rejects incomplete or changing catalogs', async () => {
  page.mockResolvedValueOnce({ items: [{ sku: 'A' }], total: 2, totalPages: 2 }).mockResolvedValueOnce({ items: [{ sku: 'B' }], total: 2 })
  expect((await loadAnalyticsPurchases()).map(row => row.sku)).toEqual(['A', 'B'])
  page.mockResolvedValueOnce({ items: [{ sku: 'A' }], total: 2, totalPages: 2 }).mockResolvedValueOnce({ items: [{ sku: 'B' }], total: 3 })
  await expect(loadAnalyticsPurchases()).rejects.toThrow('变化')
  page.mockResolvedValueOnce({ items: [{ sku: 'A' }], total: 2, totalPages: 1 })
  await expect(loadAnalyticsPurchases()).rejects.toThrow('不完整')
})
