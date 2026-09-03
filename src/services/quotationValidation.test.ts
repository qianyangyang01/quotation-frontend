import { describe, expect, it } from 'vitest'
import { validateQuotationConditions, type QuotationConditionInput } from './quotationValidation'

const valid: QuotationConditionInput = {
  customerName: '客户A', quoteMode: 'single', sku: 'SKU-1', productCategory: '服装', logisticsAttribute: '普货',
  allowedLogisticsAttributes: ['普货', '带电'], customerGrade: 'S', enabledCustomerGrades: ['S', 'A'], monthlySalesEstimate: '10',
}

describe('quotation staged required validation', () => {
  it('allows product category to be filled by a successful SKU query', () => {
    expect(validateQuotationConditions({ ...valid, productCategory: '' }, { includeSku: true, includeCategory: false })).toEqual([])
    expect(validateQuotationConditions({ ...valid, productCategory: '' }, { includeSku: true, includeCategory: true })).toContainEqual({ key: 'productCategory', message: '请选择产品品类' })
  })

  it('requires every pre-query condition and trims customer names', () => {
    const issues = validateQuotationConditions({ ...valid, customerName: ' ', sku: '', logisticsAttribute: '', customerGrade: '', monthlySalesEstimate: '' }, { includeSku: true, includeCategory: false })
    expect(issues.map(item => item.key)).toEqual(['customerName', 'sku', 'logisticsAttribute', 'customerGrade', 'monthlySalesEstimate'])
  })

  it('does not require a top-level SKU for bundle pre-validation', () => {
    expect(validateQuotationConditions({ ...valid, quoteMode: 'bundle', sku: '' }, { includeSku: false, includeCategory: true })).toEqual([])
  })
})
