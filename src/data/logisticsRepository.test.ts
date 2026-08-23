import { describe, expect, it } from 'vitest'
import { isPriceRowEligible } from './logistics'
import { normalizeLogisticsPriceRow } from './logisticsRepository'

describe('published logistics row compatibility', () => {
  it('fills fields omitted by an older manual draft before quotation calculation', () => {
    const row = normalizeLogisticsPriceRow({
      areaName: '美国', countryCode: 'US', weightFromKg: 0, weightToKg: 5,
      pricePerKg: 50, registrationFee: 10,
    })

    expect(row.prohibitedMarks).toBe('')
    expect(row.allowedMarks).toBe('')
    expect(row.volumeDivisor).toBe(0)
    expect(isPriceRowEligible(row, ['普货'])).toBe(true)
  })
})
