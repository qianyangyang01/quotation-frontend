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
  it('starts a new ordinary quotation without copying priority or changing the original', () => {
    const original=record({priorityProcessing:true,financeReviewStatus:'pending'})
    const before=JSON.stringify(original)
    expect(quotationReissuePayload(original).priorityProcessing).not.toBe(true)
    expect(JSON.stringify(original)).toBe(before)
  })
  it('preserves commission and extra packaging inputs from the current production record', () => {
    const payload = quotationReissuePayload(record({ commissionThreshold: 0.95,
      weightSnapshot: buildQuotationWeightSnapshot([{ sku: 'SKU-A', quantityPerSet: 1, baseWeightKg: 0.05 }], 25, [1, 2]),
    }))
    expect(payload).toMatchObject({ commissionThreshold: 0.95, specialPackagingGrams: 25 })
  })
  it('preserves mixed bundle tiers through record normalization and reissue without mutating historical prices', () => {
    const original = record({ quoteMode: 'bundle', monthlySalesEstimate: '100+', bundleItems: [
      { sku: 'BK2601961', name: 'A', purchaseTier: '10', quantityPerSet: 1, effectiveWeightKg: .2, purchaseUnitPriceCny: 14.08, domesticFreightPerUnitCny: .21 },
      { sku: 'BK2601961-1', name: 'B', purchaseTier: '100', quantityPerSet: 2, effectiveWeightKg: .219, purchaseUnitPriceCny: 12.75, domesticFreightPerUnitCny: .21 },
    ] })
    const before = JSON.stringify(original)
    expect(original.bundleItems?.map(item => item.purchaseTier)).toEqual(['10', '100'])
    const payload = quotationReissuePayload(original)
    expect(payload.bundleItems.map(item => item.purchaseTier)).toEqual(['10', '100'])
    expect(payload.bundleItems.every(item => !('purchaseUnitPriceCny' in item))).toBe(true)
    expect(JSON.stringify(original)).toBe(before)
  })

  it('keeps a three-piece same-SKU set on reissue without changing the historical snapshot', () => {
    const original = record({ quoteMode: 'bundle', bundleItems: [
      { sku: 'SKU-A', name: 'A', quantityPerSet: 3, effectiveWeightKg: .2, purchaseUnitPriceCny: 12, domesticFreightPerUnitCny: 1.5 },
    ] })
    const before = JSON.stringify(original)
    const payload = quotationReissuePayload(original)
    expect(payload.quoteMode).toBe('bundle')
    expect(payload.bundleItems).toHaveLength(1)
    expect(payload.bundleItems[0]).toMatchObject({ sku: 'SKU-A', quantityPerSet: 3 })
    expect(JSON.stringify(original)).toBe(before)
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
    expect(payload[`${mode === 'specified' ? 'common' : mode}Selections`]).toHaveLength(2)
    expect(payload.quoteMatrixMode).toBe(mode === 'template' ? 'template' : 'common')
    expect(payload.specifiedSelections).toEqual([])
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


it('reissues a legacy specifiedQuotes record in quick mode without rewriting historical prices or states', () => {
  const original = record({ matrixMode: 'specified', customQuoteQuantity: 9, specifiedQuotes: [
    { country: '澳大利亚', quoteRegion: '澳大利亚1区', carrier: '物流甲', channel: '小包', rule: '规则甲', eta: '5天', quote1Usd: 12, quote2Usd: 22, quote3Usd: 32, quoteCustomUsd: 92 },
    { country: '澳大利亚', quoteRegion: '澳大利亚2区', carrier: '物流甲', channel: '小包', rule: '规则甲', eta: '5天', quote1Usd: 13, quote2Usd: 23, quote3Usd: 33, quoteCustomUsd: 93 },
  ] })
  const before = JSON.stringify(original)
  const next = quotationReissuePayload(original)
  expect(next.quoteMatrixMode).toBe('common')
  expect(next.customQuoteQuantity).toBe(9)
  expect(next.commonSelections.map(item => item.quoteRegion)).toEqual(['澳大利亚1区', '澳大利亚2区'])
  expect(next.specifiedSelections).toEqual([])
  expect(next).not.toHaveProperty('quoteOptions')
  expect(next).not.toHaveProperty('specifiedQuotes')
  expect(JSON.stringify(original)).toBe(before)
  expect(original).toMatchObject({ matrixMode: 'specified', status: 'won', quoteConfirmed: true, actualQuoteUsd: 999 })
})
