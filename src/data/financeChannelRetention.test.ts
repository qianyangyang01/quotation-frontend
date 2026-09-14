import { afterEach, expect, it } from 'vitest'
import { logisticsRules, replaceLogisticsRules, type LogisticsRule } from './logistics'
import { normalizeLogisticsPriceRow } from './logisticsRepository'
import { financeAllowsLogisticsChannel, normalizePolicies, retainedFinanceCountryRule, type FinanceChannelPolicy } from './financeChannelPolicies'

const original = [...logisticsRules]
afterEach(() => replaceLogisticsRules(original))
const relation = { carrier: '万邦', channel: '测试渠道', channelCode: 'TEST', discounts: '' }
const rule: LogisticsRule = { id: 91, name: '测试', englishName: '', type: '专线', currency: 'CNY', published: 'V1', status: '禁用', dates: '', users: '', relations: [relation], phoneRequired: false, areaCount: 1, priceRowCount: 1, prices: [normalizeLogisticsPriceRow({ areaName: '德国', countryCode: 'DE', weightFromKg: 0, weightToKg: 3, pricePerKg: 10 })] }
const policy: FinanceChannelPolicy = { id: 'p1', category: '普货', enabled: true, updatedAt: '', countryRules: [{ country: '德国', allowedChannels: ['91::万邦::TEST'], stage: 'standard', continent: '欧洲', sortOrder: 1 }] }

it('retains authorization through disable, unrelated normalization and re-enable without allowing disabled quotes', () => {
  replaceLogisticsRules([rule])
  const saved = normalizePolicies([policy])
  expect(saved[0]?.countryRules[0]).toEqual({ ...policy.countryRules[0], unavailableChannels: [] })
  expect(financeAllowsLogisticsChannel(saved, '普货', '德国', 91, relation)).toBe(false)
  replaceLogisticsRules([])
  const refreshed = normalizePolicies(saved)
  expect(refreshed).toEqual(saved)
  replaceLogisticsRules([{ ...rule, status: '启用' }])
  expect(financeAllowsLogisticsChannel(refreshed, '普货', '德国', 91, relation)).toBe(true)
  expect(financeAllowsLogisticsChannel(refreshed, '带电', '德国', 91, relation)).toBe(false)
})

it('permits retention only for the same existing attribute and country, never grants new bindings', () => {
  expect(retainedFinanceCountryRule(policy, '普货', '德国')?.allowedChannels).toEqual(['91::万邦::TEST'])
  expect(retainedFinanceCountryRule(policy, '带电', '德国')).toBeUndefined()
  expect(retainedFinanceCountryRule(policy, '普货', '美国')).toBeUndefined()
  expect(retainedFinanceCountryRule(undefined, '普货', '德国')).toBeUndefined()
})
