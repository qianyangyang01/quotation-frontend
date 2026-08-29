import { describe, expect, it } from 'vitest'
import { deliveryLabel, normalizeQualityGrade, qualityLabel, validDeliveryDays } from './supplierRecordOptions'

describe('supplier record fixed quality and delivery rules', () => {
  it('maps historical quality values to the new fixed grades', () => {
    expect(normalizeQualityGrade('A（优）')).toBe('优')
    expect(normalizeQualityGrade('B')).toBe('良')
    expect(normalizeQualityGrade('C(不良)')).toBe('不良')
    expect(qualityLabel('良')).toBe('良：有次品但可退换')
  })

  it('accepts only a positive integer delivery day and formats new values', () => {
    expect(validDeliveryDays('')).toBe(true)
    expect(validDeliveryDays('7')).toBe(true)
    expect(validDeliveryDays('5-7天')).toBe(false)
    expect(validDeliveryDays('0')).toBe(false)
    expect(deliveryLabel('7')).toBe('7 天')
    expect(deliveryLabel('5-7天')).toBe('5-7天')
  })
})
