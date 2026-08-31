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
  it('preserves logistics version and calculation inputs without rewriting saved freight', () => {
    const logisticsInput = { country: '美国', weightKg: .5, marks: ['普货'], dimensions: { lengthCm: 10, widthCm: 10, heightCm: 10 } }
    const record = normalizeQuotationRecord({ ...baseRecord(), quoteOptions: [{
      id: 'q1', country: '美国', carrier: '物流商', channel: '渠道', rule: '规则', eta: '',
      logisticsChannelId: 'channel-v2', logisticsVersionId: 'price-v2', logisticsInput, freightCny: 45,
      quote1Usd: 14, quote2Usd: null, quote3Usd: null, quoteCustomUsd: null,
    }] })
    expect(record?.quoteOptions?.[0]).toMatchObject({ logisticsChannelId: 'channel-v2', logisticsVersionId: 'price-v2', logisticsInput, freightCny: 45 })
  })
  it('normalizes a structured immutable bundle snapshot', () => {
    const record = normalizeQuotationRecord({ ...baseRecord(), bundleItems: [
      { sku: ' sku-1 ', name: '商品一', quantityPerSet: 2, effectiveWeightKg: 0.2, purchaseBaseUnitPriceCny: 11.32, purchaseInvoiceType: '普票6%', purchaseInvoiceRatePercent: 6, purchaseInvoiceTaxApplied: true, purchaseUnitPriceCny: 12, domesticFreightPerUnitCny: 1.5 },
      { sku: 'SKU-2', name: '商品二', quantityPerSet: 1, effectiveWeightKg: 0.35, purchaseUnitPriceCny: 20, domesticFreightPerUnitCny: 0 },
    ] })
    expect(record?.bundleItems).toEqual([
      { sku: 'SKU-1', name: '商品一', quantityPerSet: 2, effectiveWeightKg: 0.2, purchaseBaseUnitPriceCny: 11.32, purchaseInvoiceType: '普票6%', purchaseInvoiceRatePercent: 6, purchaseInvoiceTaxApplied: true, purchaseUnitPriceCny: 12, domesticFreightPerUnitCny: 1.5 },
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
