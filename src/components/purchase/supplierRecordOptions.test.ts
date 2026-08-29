import { describe, expect, it } from 'vitest'
import { calculateSupplierScore, deliveryLabel, invoiceNeedsTaxPoint, normalizeInvoiceType, normalizeQualityGrade, qualityLabel, taxPointDecimalForInvoice, validDeliveryDays } from './supplierRecordOptions'

describe('supplier record fixed quality and delivery rules', () => {
  it('maps historical quality values to the new fixed grades', () => {
    expect(normalizeQualityGrade('A（优）')).toBe('优')
    expect(normalizeQualityGrade('B')).toBe('良')
    expect(normalizeQualityGrade('C(不良)')).toBe('不良')
    expect(qualityLabel('良')).toBe('良：有次品但可退换')
  })

  it('accepts only a non-negative integer delivery day and formats new values', () => {
    expect(validDeliveryDays('')).toBe(true)
    expect(validDeliveryDays('7')).toBe(true)
    expect(validDeliveryDays('5-7天')).toBe(false)
    expect(validDeliveryDays('0')).toBe(true)
    expect(validDeliveryDays('01')).toBe(false)
    expect(deliveryLabel('7')).toBe('7 天')
    expect(deliveryLabel('5-7天')).toBe('5-7天')
  })

  it('normalizes historical no-invoice values and identifies tax point requirements', () => {
    expect(normalizeInvoiceType('不开票')).toBe('没票')
    expect(invoiceNeedsTaxPoint('专票')).toBe(true)
    expect(invoiceNeedsTaxPoint('普票')).toBe(true)
    expect(invoiceNeedsTaxPoint('没票')).toBe(false)
    expect(taxPointDecimalForInvoice('专票', 13)).toBe(0.13)
    expect(taxPointDecimalForInvoice('没票', 13)).toBeNull()
  })
})

describe('supplier record score preview', () => {
  const completeInput = {
    qualityGrade: '优', deliveryTerms: '0', afterSalesAvailable: true,
    hotProductRecommendation: true, freeSample: true, priceLevel: '市场最低',
    invoiceType: '专票', taxPointPercent: 11,
  }

  it('calculates the full one hundred point score', () => {
    expect(calculateSupplierScore(completeInput)).toEqual({
      complete: true,
      total: 100,
      missingItems: [],
      breakdown: { quality: 30, delivery: 20, afterSales: 10, hotProduct: 10, freeSample: 5, priceLevel: 10, invoice: 15 },
    })
  })

  it.each([
    [0, 20], [1, 15], [2, 10], [7, 10], [8, 0],
  ])('scores %d delivery days as %d points', (days, expected) => {
    expect(calculateSupplierScore({ ...completeInput, deliveryTerms: String(days) }).breakdown.delivery).toBe(expected)
  })

  it('scores invoice thresholds and no-invoice records', () => {
    expect(calculateSupplierScore({ ...completeInput, invoiceType: '专票', taxPointPercent: 11.01 }).breakdown.invoice).toBe(0)
    expect(calculateSupplierScore({ ...completeInput, invoiceType: '普票', taxPointPercent: 1 }).breakdown.invoice).toBe(10)
    expect(calculateSupplierScore({ ...completeInput, invoiceType: '普票', taxPointPercent: 1.01 }).breakdown.invoice).toBe(0)
    expect(calculateSupplierScore({ ...completeInput, invoiceType: '没票', taxPointPercent: null }).breakdown.invoice).toBe(0)
    expect(calculateSupplierScore({ ...completeInput, invoiceType: '专票', taxPointPercent: -1 }).breakdown.invoice).toBeNull()
  })

  it('keeps incomplete records pending instead of treating missing values as zero', () => {
    const result = calculateSupplierScore({ ...completeInput, qualityGrade: '', afterSalesAvailable: null, invoiceType: '普票', taxPointPercent: '' })
    expect(result.complete).toBe(false)
    expect(result.total).toBeNull()
    expect(result.missingItems).toEqual(['质量', '售后', '开票及票点'])
    expect(result.breakdown.quality).toBeNull()
    expect(result.breakdown.afterSales).toBeNull()
    expect(result.breakdown.invoice).toBeNull()
  })
})
