import { describe, expect, it } from 'vitest'
import { calculateSupplierScore, DELIVERY_OPTIONS, deliveryLabel, deliveryValueForRequest, invoiceNeedsTaxPoint, legacyDeliveryText, normalizeInvoiceType, normalizeQualityGrade, qualityLabel, taxPointDecimalForInvoice, validDeliveryOption } from './supplierRecordOptions'

describe('supplier record fixed quality and delivery rules', () => {
  it('maps historical quality values to the new fixed grades', () => {
    expect(normalizeQualityGrade('A（优）')).toBe('优')
    expect(normalizeQualityGrade('B')).toBe('良')
    expect(normalizeQualityGrade('C(不良)')).toBe('不良')
    expect(qualityLabel('良')).toBe('良：有次品但可退换')
  })

  it('exposes only the four delivery options and preserves historical values', () => {
    expect(DELIVERY_OPTIONS).toEqual([
      { value: '0', label: '0天（随时到货）', score: 20 },
      { value: '1', label: '1天到货', score: 15 },
      { value: '7', label: '7天内到货', score: 10 },
      { value: '8', label: '7天以上到货', score: 0 },
    ])
    expect(validDeliveryOption('')).toBe(true)
    expect(validDeliveryOption('0')).toBe(true)
    expect(validDeliveryOption('1')).toBe(true)
    expect(validDeliveryOption('7')).toBe(true)
    expect(validDeliveryOption('8')).toBe(true)
    expect(validDeliveryOption('2')).toBe(false)
    expect(validDeliveryOption('5-7天')).toBe(false)
    expect(deliveryLabel('0')).toBe('0天（随时到货）')
    expect(deliveryLabel('7')).toBe('7天内到货')
    expect(deliveryLabel('8')).toBe('7天以上到货')
    expect(deliveryLabel('5-7天')).toBe('5-7天')
    expect(legacyDeliveryText(' 5-7天 ')).toBe('5-7天')
    expect(legacyDeliveryText('2')).toBe('2')
    expect(legacyDeliveryText('7')).toBe('')
    expect(legacyDeliveryText('')).toBe('')
    expect(deliveryValueForRequest('', '5-7天')).toBe('5-7天')
    expect(deliveryValueForRequest(' 7 ', '5-7天')).toBe('7')
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
    [0, 20], [1, 15], [7, 10], [8, 0],
  ])('scores delivery option %d as %d points', (days, expected) => {
    expect(calculateSupplierScore({ ...completeInput, deliveryTerms: String(days) }).breakdown.delivery).toBe(expected)
  })

  it('keeps historical exact delivery days compatible with the previous scoring rule', () => {
    expect(calculateSupplierScore({ ...completeInput, deliveryTerms: '3' }).breakdown.delivery).toBe(10)
    expect(calculateSupplierScore({ ...completeInput, deliveryTerms: '9' }).breakdown.delivery).toBe(0)
    expect(calculateSupplierScore({ ...completeInput, deliveryTerms: '3-5天' }).breakdown.delivery).toBeNull()
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

  it('treats a complete zero score as complete rather than missing', () => {
    const result = calculateSupplierScore({
      qualityGrade: '不良', deliveryTerms: '8', afterSalesAvailable: false,
      hotProductRecommendation: false, freeSample: false, priceLevel: '偏高',
      invoiceType: '没票', taxPointPercent: null,
    })

    expect(result.complete).toBe(true)
    expect(result.total).toBe(0)
    expect(result.missingItems).toEqual([])
    expect(result.breakdown).toEqual({ quality: 0, delivery: 0, afterSales: 0, hotProduct: 0, freeSample: 0, priceLevel: 0, invoice: 0 })
  })

  it.each([
    ['优', 30], ['良', 20], ['不良', 0],
  ])('scores quality %s as %d points', (qualityGrade, expected) => {
    expect(calculateSupplierScore({ ...completeInput, qualityGrade }).breakdown.quality).toBe(expected)
  })

  it.each([
    ['市场最低', 10], ['居中', 5], ['偏高', 0],
  ])('scores price level %s as %d points', (priceLevel, expected) => {
    expect(calculateSupplierScore({ ...completeInput, priceLevel }).breakdown.priceLevel).toBe(expected)
  })
})
