import { describe, expect, it } from 'vitest'
import { normalizeQuotationRecord, type QuotationRecord } from '@/data/quotationRecords'
import { quotationReissuePayload } from './quotationReissue'
import { buildQuotationWeightSnapshot } from '@/data/quotationWeightSnapshot'

const record = (overrides: Partial<QuotationRecord> = {}) => normalizeQuotationRecord({
  id: 'original', no: 'QT-OLD', customerName: '客户甲', primarySku: 'SKU-A', customerGrade: 'A级客户',
  logisticsAttribute: '带电', country: '加拿大', carrier: '物流甲', channel: '小包', rule: '规则甲',
  status: 'won', actualQuoteUsd: 999, quoteConfirmed: true, note: '旧备注', ...overrides,
})!

describe('reissuing a quotation as new editable inputs', () => {
  it('preserves commission and extra packaging inputs from the current production record', () => {
    const payload = quotationReissuePayload(record({ commissionThreshold: 0.95,
      weightSnapshot: buildQuotationWeightSnapshot([{ sku: 'SKU-A', quantityPerSet: 1, baseWeightKg: 0.05 }], 25, [1, 2]),
    }))
    expect(payload).toMatchObject({ commissionThreshold: 0.95, specialPackagingGrams: 25 })
  })
  it('copies a legacy record without copying its identity, deal, approval or prices', () => {
    const original = record()
    const before = JSON.stringify(original)
    const payload = quotationReissuePayload(original)
    expect(payload).toMatchObject({ customerName: '客户甲', logisticsAttribute: '带电', selectedCustomerGrade: 'A',
      product: { sku: 'SKU-A', weightSource: 'purchase' },
      commonSelections: [{ country: '加拿大', carrier: '物流甲', transport: '小包', rule: '规则甲' }] })
    for (const field of ['id', 'no', 'status', 'actualQuoteUsd', 'quoteConfirmed', 'customerQuote', 'note', 'revisions']) expect(payload).not.toHaveProperty(field)
    expect(JSON.stringify(original)).toBe(before)
  })

  it.each(['common', 'specified', 'template'] as const)('preserves every channel and region for %s', mode => {
    const payload = quotationReissuePayload(record({ matrixMode: mode, quotationTemplateId: 'T1', quotationTemplateName: '常用模板',
      customerGrade: '新客户', customerOperation: { id: 'customer1', name: '客户甲', feeUsd: 0.3 }, customQuoteQuantity: 8,
      quoteOptions: ['澳大利亚1区', '澳大利亚2区'].map((quoteRegion, index) => ({
        id: String(index), country: '澳大利亚', quoteRegion, channelKey: '1::物流甲::A', carrier: '物流甲', channel: '小包',
        rule: '规则甲', eta: '5天', quote1Usd: 10, quote2Usd: 20, quote3Usd: 30, quoteCustomUsd: 80, isPrimary: index === 0,
      })),
    }))
    expect(payload[`${mode}Selections`]).toHaveLength(2)
    expect(payload.selectedCustomerGrade).toBe('NEW')
    expect(payload.selectedCustomerId).toBe('customer1')
    expect(payload.customQuoteQuantity).toBe(8)
    expect(payload.product.primaryChannelKey).toBe('1::物流甲::A')
    expect(payload.selectedQuoteRegions['澳大利亚']).toBe('澳大利亚1区')
    expect(payload.activeTemplate?.id).toBe(mode === 'template' ? 'T1' : undefined)
  })

  it('keeps bundle SKUs and quantities and refreshes purchase weight and prices', () => {
    const payload = quotationReissuePayload(record({ quoteMode: 'bundle', primarySku: 'SKU-A、SKU-B', bundleItems: [
      { sku: 'SKU-A', name: 'A', quantityPerSet: 2, effectiveWeightKg: 8, purchaseUnitPriceCny: 100, domesticFreightPerUnitCny: 1, purchaseInvoiceTaxApplied: true },
      { sku: 'SKU-B', name: 'B', quantityPerSet: 3, effectiveWeightKg: 9, purchaseUnitPriceCny: 200, domesticFreightPerUnitCny: 1 },
    ] }))
    expect(payload.product.sku).toBe('')
    expect(payload.bundleItems).toEqual([
      { sku: 'SKU-A', quantityPerSet: 2, customWeightKg: null, purchaseInvoiceTaxApplied: true },
      { sku: 'SKU-B', quantityPerSet: 3, customWeightKg: null, purchaseInvoiceTaxApplied: undefined },
    ])
  })
})
