import { describe, expect, it } from 'vitest'
import { findPurchaseProduct, normalizePurchaseRecord, purchaseUnitPrice } from './purchaseStore'

describe('purchase catalog state', () => {
  const complete = {
    weightG: 100, lengthCm: 10, widthCm: 8, heightCm: 4,
    minOrderQty: 1, purchasePriceCny: 12.5,
  }

  it('keeps TESTP templates locked even with complete reference values', () => {
    const template = normalizePurchaseRecord({ sku: 'TESTP260001', catalogState: 'pending_template', ...complete })
    expect(template.status).toContain('模板待补全')
    expect(template.quoteReady).toBe(false)
    expect(findPurchaseProduct([template], template.sku)).toBeUndefined()
  })

  it('only returns complete ready business products to quotation calculations', () => {
    const product = normalizePurchaseRecord({ sku: 'BIZ-260001', catalogState: 'ready', ...complete })
    expect(product.quoteReady).toBe(true)
    expect(findPurchaseProduct([product], product.sku)?.sku).toBe('BIZ-260001')
  })

  it('keeps products without dimensions quote-ready and leaves volumetric display disabled', () => {
    const product = normalizePurchaseRecord({
      sku: 'BIZ-260003', catalogState: 'ready', weightG: 180, minOrderQty: 1, purchasePriceCny: 36.8,
    })
    expect(product.quoteReady).toBe(true)
    expect(product.lengthCm).toBeNull()
    expect(findPurchaseProduct([product], product.sku)?.sku).toBe('BIZ-260003')
  })

  it('keeps disabled products visible but excludes them from new quotations', () => {
    const product = normalizePurchaseRecord({ sku: 'BIZ-260002', catalogState: 'disabled', ...complete })
    expect(product.status).toBe('已停用')
    expect(product.quoteReady).toBe(false)
    expect(findPurchaseProduct([product], product.sku)).toBeUndefined()
  })

  it('keeps identical dimensions and identical tier prices valid', () => {
    const product = normalizePurchaseRecord({
      sku: 'BIZ-260004', catalogState: 'ready', weightG: 180,
      lengthCm: 12, widthCm: 12, heightCm: 12,
      minOrderQty: 1, purchasePriceCny: 9.9,
      tier2MinQty: 20, tier2PriceCny: 9.9,
      tier3MinQty: 500, tier3PriceCny: 9.9,
    })
    expect(product.quoteReady).toBe(true)
    expect([product.lengthCm, product.widthCm, product.heightCm]).toEqual([12, 12, 12])
    expect([1, 19, 20, 499, 500].map(quantity => purchaseUnitPrice(product, quantity))).toEqual([9.9, 9.9, 9.9, 9.9, 9.9])
  })

  it('marks genuinely missing required cells incomplete without rejecting optional blanks', () => {
    const product = normalizePurchaseRecord({ sku: 'BIZ-260005', catalogState: 'ready', weightG: '', minOrderQty: 1, purchasePriceCny: 8 } as never)
    expect(product.quoteReady).toBe(false)
    expect(product.status).toContain('重量')
    expect(product.lengthCm).toBeNull()
    expect(product.tier2PriceCny).toBeNull()
  })
})
