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
  it('keeps the quotation fee engine available after removing the management-page calculator', () => {
    expect(calculateLogisticsFee(publishedRule, '美国', 0.5)?.total).toBe(59)
  })
})
