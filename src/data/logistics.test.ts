import { describe, expect, it } from 'vitest'
import { calculateLogisticsFee, formatLogisticsEta, findPriceRow, logisticsQuoteRegions, replaceLogisticsRules, logisticsRuleByName, type LogisticsRule } from './logistics'

it('keeps a usable base quote when ETA is absent and displays an explicit explanation', () => {
  expect(formatLogisticsEta({ etaMinDays: 0, etaMaxDays: 0 })).toBe('该物流暂无时效说明')
  expect(formatLogisticsEta({ etaMinDays: 7, etaMaxDays: 15, etaStatus: 'conflict' })).toBe('该物流暂无时效说明')
  expect(formatLogisticsEta({ etaMinDays: 7, etaMaxDays: 15 })).toBe('7～15 天')
  const rule = { ...publishedRule, prices: publishedRule.prices.map(row => ({ ...row, etaMinDays: 0, etaMaxDays: 0 })) }
  expect(calculateLogisticsFee(rule, '美国', 0.5)?.total).toBe(59)
  expect(calculateLogisticsFee(rule, '美国', 0.5)?.total).toBe(calculateLogisticsFee(publishedRule, '美国', 0.5)?.total)
})

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

it('indexes a published snapshot without changing zone, weight-boundary or eligibility results and resets on replacement', () => {
  const rule = { ...publishedRule, name: '区域索引', prices: [
    { ...publishedRule.prices[0]!, areaName: '澳大利亚', countryCode: 'AU', zoneName: '澳大利亚1区', weightToKg: .5 },
    { ...publishedRule.prices[0]!, areaName: '澳大利亚', countryCode: 'AU', zoneName: '澳大利亚2区', pricePerKg: 99 },
    { ...publishedRule.prices[0]!, areaName: '澳大利亚', countryCode: 'AU', zoneName: '澳大利亚1区', weightFromKg: .5, pricePerKg: 66 },
  ] }
  const cases = ['AU','au','澳大利亚'].flatMap(country => ['', '澳大利亚1区','澳大利亚2区'].flatMap(zone => [0,.5,.500001,1,1.01].map(weight => ({ country, zone, weight }))))
  const before = cases.map(c => calculateLogisticsFee(rule,c.country,c.weight,['普货'],undefined,c.zone))
  replaceLogisticsRules([rule])
  expect(cases.map(c => calculateLogisticsFee(rule,c.country,c.weight,['普货'],undefined,c.zone))).toEqual(before)
  expect(findPriceRow(rule,'AU',.5,['普货'],'澳大利亚1区')?.pricePerKg).toBe(78)
  expect(logisticsQuoteRegions('AU')).toEqual(['澳大利亚1区','澳大利亚2区'])
  expect(logisticsRuleByName(rule.name)).toBe(rule)
  const replacement = { ...rule, prices: [{ ...rule.prices[0]!, zoneName: '', pricePerKg: 10 }] }
  replaceLogisticsRules([replacement])
  expect(logisticsQuoteRegions('AU')).toEqual([])
  expect(calculateLogisticsFee(replacement,'AU',.5)?.total).toBe(25)
  expect(logisticsRuleByName(rule.name)).toBe(replacement)
  replaceLogisticsRules([])
})
