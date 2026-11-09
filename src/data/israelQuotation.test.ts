import { afterEach, expect, it } from 'vitest'
import { quotationLogisticsAttribute, quotationLogisticsAttributeOptions } from './logisticsAttributes'
import { financeAllowedChannelKeys, financeAllowsLogisticsChannel, financeCountriesForCategory, financeIsraelDeliveryModes, type FinanceChannelPolicy } from './financeChannelPolicies'
import { calculateLogisticsFee, isPriceRowEligible, replaceLogisticsRules, type LogisticsRule } from './logistics'

const relation = (id: number) => ({ carrier: '测试物流', channel: `渠道${id}`, channelCode: `C${id}`, discounts: '' })
const key = (id: number) => `${id}::测试物流::C${id}`
const policy = (category: string, ids: number[], enabled = true): FinanceChannelPolicy => ({ id: category, category, enabled, updatedAt: '', countryRules: [{ country: '以色列', allowedChannels: ids.map(key), stage: 'common', continent: '亚洲', sortOrder: 1 }] })
const rules = [1, 2, 3].map(id => ({ id, name: `渠道${id}`, status: '启用', relations: [relation(id)], prices: [{ areaName: '以色列', countryCode: 'IL', weightFromKg: 0, weightToKg: 2, pricePerKg: 50, registrationFee: id * 10, allowedMarks: id === 1 ? '以色列自提' : '以色列到门', prohibitedMarks: '', quoteReady: true }] } as LogisticsRule))
afterEach(() => replaceLogisticsRules([]))

it('groups only quotation options and accepts legacy draft values', () => {
  expect(quotationLogisticsAttributeOptions(['普货', '以色列自提', '以色列到门'])).toEqual(['普货', '以色列'])
  expect(quotationLogisticsAttributeOptions(['以色列到门'])).toEqual(['以色列'])
  expect(quotationLogisticsAttributeOptions(['普货'])).toEqual(['普货'])
  expect(quotationLogisticsAttribute('以色列自提')).toBe('以色列')
})

it('unions explicitly authorized channels, retains both labels, and never expands other countries', () => {
  replaceLogisticsRules(rules)
  const policies = [policy('以色列自提', [1, 3]), policy('以色列到门', [2, 3]), policy('普货', [99])]
  const snapshot = JSON.stringify(policies)
  expect([...financeAllowedChannelKeys(policies, '以色列', 'IL')]).toEqual([key(1), key(2), key(3)])
  expect(financeCountriesForCategory(policies, '以色列')).toEqual(['以色列'])
  expect(financeAllowsLogisticsChannel(policies, '以色列', '以色列', 2, relation(2))).toBe(true)
  expect(financeAllowedChannelKeys(policies, '以色列', '美国').size).toBe(0)
  expect(financeIsraelDeliveryModes(policies, 'IL', key(1))).toEqual(['自提'])
  expect(financeIsraelDeliveryModes(policies, 'IL', key(2))).toEqual(['到门'])
  expect(financeIsraelDeliveryModes(policies, 'IL', key(3))).toEqual(['自提', '到门'])
  expect(financeIsraelDeliveryModes(policies, '美国', key(3))).toEqual([])
  expect(JSON.stringify(policies)).toBe(snapshot)
})

it('excludes disabled, ambiguous and unrelated authorizations', () => {
  replaceLogisticsRules(rules)
  const policies = [policy('以色列自提', [1]), policy('以色列到门', [2], false), policy('普货', [3])]
  expect([...financeAllowedChannelKeys(policies, '以色列', '以色列')]).toEqual([key(1)])
  expect(financeIsraelDeliveryModes(policies, 'IL', key(2))).toEqual([])
  policies.push(policy('以色列自提', [3]))
  expect(financeAllowedChannelKeys(policies, '以色列', 'IL').size).toBe(0)
})

it('keeps each channel price unchanged at every quantity and weight limit', () => {
  for (const rule of rules.slice(0, 2)) {
    for (const weight of [.5, 1, 1.5, 2, 2.01]) {
      const original = calculateLogisticsFee(rule, '以色列', weight, [rule.prices[0]!.allowedMarks])
      expect(calculateLogisticsFee(rule, '以色列', weight, ['以色列'])?.total).toBe(original?.total)
    }
  }
  expect(calculateLogisticsFee(rules[0]!, '以色列', .5, ['以色列'])?.total).toBe(35)
  expect(calculateLogisticsFee(rules[1]!, '以色列', .5, ['以色列'])?.total).toBe(45)
  const price = rules[0]!.prices[0]!
  expect(isPriceRowEligible({ ...price, prohibitedMarks: '以色列自提' }, ['以色列'])).toBe(false)
  expect(isPriceRowEligible({ ...price, countryCode: 'US', areaName: '美国' }, ['以色列'])).toBe(false)
})
