import { describe, expect, it } from 'vitest'
import { addCustomerOperationFee, resolveCustomerOperation, validateCustomerOperationSettings } from './customerOperationFees'
import { purchaseCategoryForSkus, resolveRecordCategory, buildCategoryPerformance, quotationDetailsCsv } from './quotationAnalytics'
import { normalizePurchaseRecord, type PurchaseProductRecord } from './purchaseStore'
import type { QuotationRecord } from './quotationRecords'

const customer = { id: 'client-1', name: '客户甲', enabled: true, feeUsd: 1.25 }
describe('explicit customer operation fee', () => {
  it('never matches a typed name, and invalidates removed, renamed or disabled selections', () => {
    const settings = { customers: [customer] }
    expect(resolveCustomerOperation(settings, '', customer.name)).toMatchObject({ configured: true, feeUsd: 0, snapshot: undefined })
    expect(resolveCustomerOperation(settings, customer.id, customer.name)).toMatchObject({ configured: true, feeUsd: 1.25, snapshot: { id: customer.id, name: customer.name, feeUsd: 1.25 } })
    for (const rows of [[], [{ ...customer, enabled: false }], [{ ...customer, name: '客户乙' }]]) expect(resolveCustomerOperation({ customers: rows }, customer.id, customer.name).configured).toBe(false)
    expect(resolveCustomerOperation(settings, customer.id, '修改名称').configured).toBe(false)
    expect(resolveCustomerOperation({ customers: [{ ...customer, feeUsd: 2 }] }, customer.id, customer.name).feeUsd).toBe(2)
  })
  it('adds once without quantity or coefficient multiplication and preserves the existing price ceiling', () => {
    for (const original of [10, 20, 30, 80, 100]) expect(addCustomerOperationFee(original, 1.25)).toBe(original + 1.25)
    expect(addCustomerOperationFee(10, 0)).toBe(10)
    expect(addCustomerOperationFee(10, 0.01)).toBe(10.05)
    expect(addCustomerOperationFee(10.05, 0.1)).toBe(10.15)
  })
  it('rejects duplicate names, blank names, invalid money and preserves disabled zero-fee rows', () => {
    for (const feeUsd of [NaN, Infinity, -1, 0.001, 1000001]) expect(() => validateCustomerOperationSettings({ customers: [{ ...customer, feeUsd }] })).toThrow()
    for (const name of ['', ' ', 'x'.repeat(121)]) expect(() => validateCustomerOperationSettings({ customers: [{ ...customer, name }] })).toThrow()
    expect(() => validateCustomerOperationSettings({ customers: [customer, { ...customer, id: '2', name: ` ${customer.name} ` }] })).toThrow()
    expect(() => validateCustomerOperationSettings({ customers: [{ ...customer, enabled: false, feeUsd: 0 }] })).not.toThrow()
  })
})
describe('procurement category statistics', () => {
  const purchases = [{ sku: 'A', category: '服装', purchasePriceCny: 1 }, { sku: 'B', category: '', purchasePriceCny: 2 }, { sku: 'C', category: '饰品', purchasePriceCny: 3 }] as PurchaseProductRecord[]
  const bySku = new Map(purchases.map(row => [row.sku, row]))
  const row = { no: 'Q1', primarySku: 'A', productCategory: '旧手填品类', systemQuoteUsd: 10, systemQuoteCny: 67, totalCostCny: 50, country: '法国', quoteOptions: [], createdAt: '', customerName: '甲', salespersonName: '', salespersonAccount: '' } as unknown as QuotationRecord
  it('ignores old manually selected categories and groups missing or mixed purchase categories as other', () => {
    expect(normalizePurchaseRecord({ sku: 'NOCAT', name: '商品名称', category: '' }).category).toBe('')
    expect(resolveRecordCategory(row, bySku)).toBe('服装')
    for (const skus of [['B'], ['MISSING'], ['A', 'C'], ['A', 'B'], []]) expect(purchaseCategoryForSkus(skus, bySku)).toBe('其他')
    expect(purchaseCategoryForSkus([' a ', 'A'], bySku)).toBe('服装')
    expect(quotationDetailsCsv([row], purchases)).toContain('服装')
    expect(quotationDetailsCsv([row], purchases)).not.toContain('旧手填品类')
    expect(buildCategoryPerformance([row], purchases).find(item => item.category === '服装')).toMatchObject({ quotationCount: 1, quoteUsd: 10 })
    expect(buildCategoryPerformance([row], purchases).some(item => item.category === '其他')).toBe(true)
  })
})
