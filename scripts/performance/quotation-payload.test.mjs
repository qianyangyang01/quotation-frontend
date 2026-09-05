import { describe, expect, it } from 'vitest'
import { buildQuotationPayload, performanceBundleItem } from './quotation-payload.mjs'

describe('performance quotation payload', () => {
  it('sends current logistics revision and independently calculated bundled freight', () => {
    const payload = buildQuotationPayload('PERF01', 0, true, 'revision-test')
    expect(payload.logisticsRevision).toBe('revision-test')
    expect(payload.quoteOptions[0].logisticsInput.weightKg).toBe(0.305)
    expect(payload.quoteOptions[0].freightCny).toBe(22.64)
  })
  it('keeps single quotations free of bundle items', () => {
    const payload = buildQuotationPayload('PERF01', 0, false)
    expect(payload.quoteMode).toBe('single')
    expect(payload.primarySku).toBe('PERF-SKU-00001')
    expect(payload).not.toHaveProperty('bundleItems')
  })

  it('builds ordered structured bundle items from the seed rules', () => {
    const payload = buildQuotationPayload('PERF01', 0, true)
    expect(payload.primarySku).toBe('PERF-SKU-00001、PERF-SKU-00002')
    expect(payload.bundleItems).toHaveLength(2)
    expect(payload.bundleItems.map(item => item.sku)).toEqual(['PERF-SKU-00001', 'PERF-SKU-00002'])
    expect(payload.bundleItems[0]).toEqual({
      sku: 'PERF-SKU-00001', name: '性能商品 PERF-SKU-00001', quantityPerSet: 1,
      effectiveWeightKg: 0.101, purchaseUnitPriceCny: 6.1, domesticFreightPerUnitCny: 0.5,
    })
    expect(payload.bundleItems[1].quantityPerSet).toBe(2)
  })

  it('wraps the second SKU at the end of the deterministic catalog', () => {
    expect(buildQuotationPayload('PERF01', 9_999, true).bundleItems.map(item => item.sku))
      .toEqual(['PERF-SKU-10000', 'PERF-SKU-00001'])
    expect(performanceBundleItem(100, 1).purchaseUnitPriceCny).toBe(6)
  })
})
