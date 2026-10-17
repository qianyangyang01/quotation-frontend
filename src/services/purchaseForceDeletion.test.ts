import { beforeEach, expect, it, vi } from 'vitest'
const api=vi.hoisted(()=>({get:vi.fn(),post:vi.fn()}))
vi.mock('./http',()=>({api}))
import { forceDeletePurchaseProduct, loadDeletedPurchaseSnapshots, deletedPurchaseSnapshotForQuote } from './purchaseForceDeletion'
beforeEach(()=>vi.clearAllMocks())
it('sends the exact checked version and typed SKU to the separate privileged operation',async()=>{
  await forceDeletePurchaseProduct('SKU/1',7,'SKU/1')
  expect(api.post).toHaveBeenCalledWith('/purchase-products/SKU%2F1/force-delete',{expectedVersion:7,confirmationSku:'SKU/1'})
})
it('keeps old quote images separate when the same SKU is recreated and deleted again',()=>{
  const older={sku:'SAME',category:'旧商品',productImage:'old.png',physicalImage:'',createdAt:'2026-09-01T00:00:00Z',deletedAt:'2026-10-01T00:00:00Z'}
  const newer={...older,productImage:'new.png',createdAt:'2026-10-02T00:00:00Z',deletedAt:'2026-10-17T00:00:00Z'}
  expect(deletedPurchaseSnapshotForQuote([newer,older],'SAME','2026-09-20T00:00:00Z')).toEqual(older)
  expect(deletedPurchaseSnapshotForQuote([newer,older],'SAME','2026-10-10T00:00:00Z')).toEqual(newer)
  expect(deletedPurchaseSnapshotForQuote([newer,older],'SAME','2026-10-18T00:00:00Z')).toBeUndefined()
  expect(deletedPurchaseSnapshotForQuote([newer,older],'OTHER','2026-09-20T00:00:00Z')).toBeUndefined()
})
it('loads only distinct requested display snapshots and makes no empty request',async()=>{
  expect(await loadDeletedPurchaseSnapshots([])).toEqual([]);expect(api.get).not.toHaveBeenCalled()
  api.get.mockResolvedValue([{sku:'SKU/1',category:'宠物用品',productImage:'/api/v1/assets/image',physicalImage:''}])
  expect(await loadDeletedPurchaseSnapshots([' sku/1 ','SKU/1'])).toHaveLength(1)
  expect(api.get).toHaveBeenCalledWith('/purchase-products/deleted-snapshots?skus=SKU%2F1')
})
