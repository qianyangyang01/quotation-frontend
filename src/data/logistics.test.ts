import { describe, expect, it } from 'vitest'
import { calculateLogisticsFee, type LogisticsRule } from './logistics'

const publishedRule: LogisticsRule = {
  id: 1,
  name: '正式物流规则',
  englishName: 'published-rule',
  type: '专线',
  currency: 'CNY',
  published: 'V1',
  status: '启用',
  dates: '',
  users: '',
  relations: [{ carrier: '物流商', channel: '正式渠道', channelCode: 'CHANNEL-1', discounts: '' }],
  phoneRequired: false,
  areaCount: 1,
  priceRowCount: 1,
  prices: [{
    areaName: '美国', countryCode: 'US', etaMinDays: 10, etaMaxDays: 15,
    prohibitedMarks: '', allowedMarks: '', maxPerimeterCm: 0, maxSideCm: 0,
    volumeDivisor: 8000, weightFromKg: 0, weightToKg: 1, startWeightKg: 0,
    pricePerKg: 78, minChargeWeightKg: 0, firstWeightKg: 0, firstWeightPrice: 0,
    nextWeightKg: 0, nextWeightPrice: 0, intervalPrice: 0, registrationFee: 20,
    surcharge: 0, fuelSurchargeRate: 0, prohibitGeneralCargo: false, volumetric: true,
    phoneRequired: false, zoneName: '', zoneExclude: false,
  }],
}

describe('logistics fee calculation', () => {
  it('uses actual weight and ignores retained volumetric fields for verified billing', () => {
    const rule = { ...publishedRule, billingVerified: true }
    expect(calculateLogisticsFee(rule, '美国', 0.1)?.total).toBe(27.8)
    const result = calculateLogisticsFee(rule, '美国', 0.1, ['普货'], {
      lengthCm: 20, widthCm: 20, heightCm: 10, volumeDivisor: 999999,
    })
    expect(result?.volumeDivisor).toBe(0)
    expect(result?.chargeWeightKg).toBe(.1)
    expect(result?.total).toBe(27.8)
    expect(calculateLogisticsFee({ ...rule, prices: [{ ...rule.prices[0]!, volumeDivisor: 0 }] }, '美国', .1, ['普货'], {
      lengthCm: 20, widthCm: 20, heightCm: 10,
    })?.total).toBe(27.8)
  })

  it('keeps the quotation fee engine available after removing the management-page calculator', () => {
    expect(calculateLogisticsFee(publishedRule, '美国', 0.5)?.total).toBe(59)
  })

  it('retains supplied dimensions without letting them change the chargeable weight', () => {
    const result = calculateLogisticsFee(publishedRule, '美国', 0.18, ['普货'], {
      lengthCm: 32, widthCm: 24, heightCm: 8, volumeDivisor: 8000,
    })
    expect(result?.actualWeightKg).toBe(0.18)
    expect(result?.volumeWeightKg).toBe(0)
    expect(result?.chargeWeightKg).toBe(0.18)
    expect(result?.volumeDivisor).toBe(0)
    expect(result?.total).toBe(34.04)
  })

  it('does not let an adjusted quotation divisor change the current calculation', () => {
    const result = calculateLogisticsFee(publishedRule, '美国', 0.18, ['普货'], {
      lengthCm: 10, widthCm: 10, heightCm: 10, volumeDivisor: 4000,
    })
    expect(result?.volumeDivisor).toBe(0)
    expect(result?.chargeWeightKg).toBe(0.18)
  })
})
