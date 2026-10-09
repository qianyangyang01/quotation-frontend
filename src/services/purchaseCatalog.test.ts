import { expect, it, vi } from 'vitest'
import { api } from './http'
import { loadPurchaseCatalogPage } from './purchaseCatalog'
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
