import { beforeEach, describe, expect, it, vi } from 'vitest'

const api = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  delete: vi.fn(),
  put: vi.fn(),
}))

vi.mock('@/services/http', () => ({ api }))

import { deletePurchaseProduct, loadPurchaseDeletionCheck, setPurchaseProductCatalogState } from './purchaseStore'

describe('purchase product safety actions', () => {
  beforeEach(() => vi.clearAllMocks())

  it('loads structured references using an encoded SKU', async () => {
    const result = { canDelete: false, version: 7, imageCount: 2, supplierLinks: 1, quotationRecords: 3, drafts: 0, templates: 0, importBatches: 1 }
    api.get.mockResolvedValue(result)

    await expect(loadPurchaseDeletionCheck('SKU / 01')).resolves.toEqual(result)
    expect(api.get).toHaveBeenCalledWith('/purchase-products/SKU%20%2F%2001/deletion-check')
  })

  it('sends the desired catalog state together with the expected version', async () => {
    api.post.mockResolvedValue({ sku: 'BIZ-01', catalogState: 'disabled', version: 8 })

    const result = await setPurchaseProductCatalogState('BIZ-01', 'disabled', 7)

    expect(api.post).toHaveBeenCalledWith('/purchase-products/BIZ-01/catalog-state', { state: 'disabled', expectedVersion: 7 })
    expect(result.catalogState).toBe('disabled')
    expect(result.quoteReady).toBe(false)
  })

  it('passes the checked version on destructive deletion', async () => {
    api.delete.mockResolvedValue(undefined)

    await deletePurchaseProduct('BIZ-01', 12)

    expect(api.delete).toHaveBeenCalledWith('/purchase-products/BIZ-01?expectedVersion=12')
  })
})
