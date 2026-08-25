import { describe, expect, it } from 'vitest'
import { normalizeQuotationRecord } from './quotationRecords'

function baseRecord() {
  return {
    id: 'record-1', no: 'QT-1', salespersonName: '测试人员', salespersonAccount: 'TEST', customerName: '测试客户',
    quoteMode: 'bundle' as const, productSummary: 'SKU-1 × 2 + SKU-2 × 1', primarySku: 'SKU-1、SKU-2',
    logisticsAttribute: '普货', country: '美国', carrier: '物流商', channel: '渠道', rule: '规则', customerGrade: 'S级客户',
    systemQuoteCny: 100, systemQuoteUsd: 14, totalCostCny: 80, exchangeRate: 7.1, status: 'pending' as const,
    createdAt: '2026-08-25T00:00:00Z', updatedAt: '2026-08-25T00:00:00Z', revisions: [],
  }
}

describe('quotation record bundle snapshots', () => {
  it('normalizes a structured immutable bundle snapshot', () => {
    const record = normalizeQuotationRecord({ ...baseRecord(), bundleItems: [
      { sku: ' sku-1 ', name: '商品一', quantityPerSet: 2, effectiveWeightKg: 0.2, purchaseUnitPriceCny: 12, domesticFreightPerUnitCny: 1.5 },
      { sku: 'SKU-2', name: '商品二', quantityPerSet: 1, effectiveWeightKg: 0.35, purchaseUnitPriceCny: 20, domesticFreightPerUnitCny: 0 },
    ] })
    expect(record?.bundleItems).toEqual([
      { sku: 'SKU-1', name: '商品一', quantityPerSet: 2, effectiveWeightKg: 0.2, purchaseUnitPriceCny: 12, domesticFreightPerUnitCny: 1.5 },
      { sku: 'SKU-2', name: '商品二', quantityPerSet: 1, effectiveWeightKg: 0.35, purchaseUnitPriceCny: 20, domesticFreightPerUnitCny: 0 },
    ])
  })

  it('keeps legacy bundle records readable without inventing item details', () => {
    const record = normalizeQuotationRecord(baseRecord())
    expect(record?.quoteMode).toBe('bundle')
    expect(record?.productSummary).toBe('SKU-1 × 2 + SKU-2 × 1')
    expect(record?.bundleItems).toBeUndefined()
  })
})
