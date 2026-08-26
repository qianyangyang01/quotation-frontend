import { beforeEach, describe, expect, it, vi } from 'vitest'

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), patch: vi.fn(), delete: vi.fn(), put: vi.fn() }))
vi.mock('./http', () => ({ api }))

import { linkSupplierProduct, loadSupplierProducts, unlinkSupplierProduct, updateSupplierProductLink } from './masterData'

describe('supplier product links', () => {
  beforeEach(() => vi.clearAllMocks())

  it('loads and creates links by business SKU', async () => {
    api.get.mockResolvedValue([])
    api.post.mockResolvedValue({ id: 'LINK-1', productSku: 'SKU-1' })

    await loadSupplierProducts('SUP-1')
    await linkSupplierProduct('SUP-1', { sku: 'SKU-1', supplierSku: 'VENDOR-1' })

    expect(api.get).toHaveBeenCalledWith('/suppliers/SUP-1/products')
    expect(api.post).toHaveBeenCalledWith('/suppliers/SUP-1/products', { sku: 'SKU-1', supplierSku: 'VENDOR-1' })
  })

  it('updates lifecycle state and removes only the relationship', async () => {
    api.patch.mockResolvedValue({ id: 'LINK-1', enabled: false })
    api.delete.mockResolvedValue(undefined)

    await updateSupplierProductLink('SUP-1', 'LINK-1', { supplierSku: 'VENDOR-2', enabled: false })
    await unlinkSupplierProduct('SUP-1', 'LINK-1')

    expect(api.patch).toHaveBeenCalledWith('/suppliers/SUP-1/products/LINK-1', { supplierSku: 'VENDOR-2', enabled: false })
    expect(api.delete).toHaveBeenCalledWith('/suppliers/SUP-1/products/LINK-1')
  })
})
