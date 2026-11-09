import { expect, it, vi } from 'vitest'
import { api } from './http'
import { loadPurchaseCatalogPage, purchaseCatalogExportUrl } from './purchaseCatalog'
vi.mock('./http', () => ({ api: { get: vi.fn() } }))
it('normalizes ordinary products without truncating or relabeling FOB tiers', async () => {
  const tiers = [1,100,300,500,1000].map(minQty => ({minQty,maxQty:null,unitPriceCny:19,unit:'件'}))
  vi.mocked(api.get).mockResolvedValueOnce({items:[{sku:'PAIR',dataSource:'legacy_2026',purchasePriceCny:21.5},{sku:'PAIR',dataSource:'fob',parsed:{priceTiers:tiers}}],total:2,totalPages:1,page:0,size:10})
  const controller=new AbortController(),page=await loadPurchaseCatalogPage('PAIR',0,10,controller.signal)
  expect(api.get).toHaveBeenCalledWith('/purchase-catalog?q=PAIR&page=0&size=10',{signal:controller.signal})
  expect(page.items[0]).toMatchObject({dataSource:'legacy_2026',priceTiers:[{unitPriceCny:21.5}]})
  expect(page.items[1]).toMatchObject({dataSource:'fob',parsed:{priceTiers:tiers}})
  expect(page.items[1]).not.toHaveProperty('purchasePriceCny')
})

it('uses the same keyword and dates for paging and an export without page limits', async () => {
  vi.mocked(api.get).mockResolvedValueOnce({items:[],total:0,totalPages:0,page:2,size:10})
  const dates={dateFrom:'2026-11-01',dateTo:'2026-11-09'}
  await loadPurchaseCatalogPage('支 & BK',2,10,undefined,dates)
  expect(api.get).toHaveBeenLastCalledWith('/purchase-catalog?q=%E6%94%AF%20%26%20BK&page=2&size=10&dateFrom=2026-11-01&dateTo=2026-11-09',{signal:undefined})
  expect(purchaseCatalogExportUrl('支 & BK',dates)).toBe('/api/v1/purchase-catalog/export.xlsx?q=%E6%94%AF%20%26%20BK&dateFrom=2026-11-01&dateTo=2026-11-09')
  expect(purchaseCatalogExportUrl('',{dateTo:'2026-11-09'})).toBe('/api/v1/purchase-catalog/export.xlsx?q=&dateTo=2026-11-09')
})
