import { describe, expect, it } from 'vitest'
import { calculateLogisticsFee, findPriceRow, isPriceRowEligible, type LogisticsRule } from './logistics'
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

  const rule = (rows: Array<Partial<ReturnType<typeof normalizeLogisticsPriceRow>>>): LogisticsRule => ({
    id: 1, name: '测试渠道', englishName: 'test', type: '专线', currency: 'CNY', published: '', status: '启用', dates: '', users: '',
    relations: [{ carrier: '测试物流商', channel: '测试渠道', channelCode: 'TEST', discounts: '' }], phoneRequired: false,
    areaCount: rows.length, priceRowCount: rows.length, prices: rows.map(normalizeLogisticsPriceRow),
  })

  it('uses the documented open-lower and closed-upper weight boundaries', () => {
    const value = rule([
      { areaName: '美国', countryCode: 'US', weightFromKg: 0, weightToKg: 1, pricePerKg: 10 },
      { areaName: '美国', countryCode: 'US', weightFromKg: 1, weightToKg: 2, pricePerKg: 20 },
    ])
    expect(findPriceRow(value, '美国', 1)?.pricePerKg).toBe(10)
    expect(findPriceRow(value, '美国', 1.0001)?.pricePerKg).toBe(20)
  })

  it('charges by actual weight and includes fixed surcharges', () => {
    const value = rule([{ areaName: '美国', countryCode: 'US', weightFromKg: 0, weightToKg: 10, pricePerKg: 10, registrationFee: 2, surcharge: 3, volumeDivisor: 8000 }])
    const result = calculateLogisticsFee(value, '美国', 1, ['普货'], { lengthCm: 40, widthCm: 30, heightCm: 20, volumeMultiplier: 2 })
    expect(result?.chargeWeightKg).toBe(1)
    expect(result?.total).toBe(15)
  })

  it('requires the exact Australia quote region and never borrows another region', () => {
    const value = rule([
      { areaName: '澳大利亚', countryCode: 'AU', zoneName: '澳大利亚1区', weightFromKg: 0, weightToKg: 5, pricePerKg: 10 },
      { areaName: '澳大利亚', countryCode: 'AU', zoneName: '澳大利亚3区', weightFromKg: 0, weightToKg: 5, pricePerKg: 30 },
    ])
    expect(calculateLogisticsFee(value, '澳大利亚', 1)).toBeNull()
    expect(calculateLogisticsFee(value, '澳大利亚', 1, ['普货'], undefined, '澳大利亚1区')?.total).toBe(10)
    expect(calculateLogisticsFee(value, '澳大利亚', 1, ['普货'], undefined, '澳大利亚2区')).toBeNull()
    expect(calculateLogisticsFee(value, '澳大利亚', 1, ['普货'], undefined, '澳大利亚4区')).toBeNull()
  })

  it('matches a combined zone label but never falls back to the cheapest zone', () => {
    const value = rule([
      { areaName: '澳大利亚', countryCode: 'AU', zoneName: '1区/2区', weightFromKg: 0, weightToKg: 5, pricePerKg: 10 },
      { areaName: '澳大利亚', countryCode: 'AU', zoneName: '3区', weightFromKg: 0, weightToKg: 5, pricePerKg: 30 },
    ])
    expect(calculateLogisticsFee(value, '澳大利亚', 1, ['普货'], undefined, '澳大利亚2区')?.total).toBe(10)
    expect(calculateLogisticsFee(value, '澳大利亚', 1)).toBeNull()
  })

  it('does not quote Australia when all four regional rows are absent', () => {
    const value = rule([{ areaName: '美国', countryCode: 'US', weightFromKg: 0, weightToKg: 5, pricePerKg: 10 }])
    expect(['澳大利亚1区', '澳大利亚2区', '澳大利亚3区', '澳大利亚4区'].every(region =>
      calculateLogisticsFee(value, '澳大利亚', 1, ['普货'], undefined, region) == null)).toBe(true)
  })
})
