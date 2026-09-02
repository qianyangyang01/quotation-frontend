import { describe, expect, it } from 'vitest'
import { findPurchaseProduct, normalizePurchaseRecord, purchaseQuoteFreightUnit, purchaseQuoteBlockingMessage, purchaseSourceLabel, purchaseUnitPrice } from './purchaseStore'

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
    expect(purchaseSourceLabel(product)).toBe('新数据')
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

  it('requires weight, final price and the single freight tier for legacy quotation', () => {
    const incomplete = normalizePurchaseRecord({
      sku: 'OLD-260001', dataSource: 'legacy_2026', catalogState: 'ready', weightG: null,
      purchasePriceCny: 6.18, singleFreightCny: null, category: '',
    })
    expect(incomplete.minOrderQty).toBe(1)
    expect(incomplete.quoteReady).toBe(false)
    expect(incomplete.quotationBlockingReasons).toEqual(expect.arrayContaining(['克重', '1件运费']))
    expect(purchaseQuoteBlockingMessage(incomplete)).toContain('该产品暂无克重信息或其他关键信息，请采购补全')

    const complete = normalizePurchaseRecord({ ...incomplete, weightG: 70, singleFreightCny: 0 })
    expect(complete.quoteReady).toBe(true)
    expect(purchaseQuoteFreightUnit(complete)).toBe(0)
    expect(purchaseSourceLabel(complete)).toBe('2026旧数据')
    expect(complete.category).toBe('')
    expect(complete.lengthCm).toBeNull()
  })

  it('rejects a zero legacy final price and recomputes stale backend reasons after procurement fills the fields', () => {
    const zeroPrice = normalizePurchaseRecord({
      sku: 'OLD-260002', dataSource: 'legacy_2026', catalogState: 'ready', weightG: 70,
      purchasePriceCny: 0, singleFreightCny: 1.7,
    })
    expect(zeroPrice.quoteReady).toBe(false)
    expect(zeroPrice.quotationBlockingReasons).toContain('有效价格')
    const completed = normalizePurchaseRecord({ ...zeroPrice, purchasePriceCny: 6.18, quotationBlockingReasons: ['克重'] })
    expect(completed.quotationBlockingReasons).toEqual([])
    expect(completed.quoteReady).toBe(true)
  })
})
