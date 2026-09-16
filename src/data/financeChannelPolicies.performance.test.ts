import { afterEach, describe, expect, it } from 'vitest'
import { logisticsRules, type LogisticsPriceRow, type LogisticsRule } from './logistics'
import { financeAllowedChannelKeys, financeAllowsLogisticsChannel, financeChannelKey, type FinanceChannelPolicy } from './financeChannelPolicies'

const original = [...logisticsRules]
afterEach(() => { logisticsRules.splice(0, logisticsRules.length, ...original) })
function fixture(count = 60) {
  let scans = 0
  const rules = Array.from({ length: count }, (_, id) => ({ id, status: '启用', name: `渠道${id}`,
    relations: [{ carrier: '物流商', channel: `渠道${id}`, channelCode: `C${id}`, discounts: '' }],
    get prices() { scans++; return [{ areaName: '荷兰', countryCode: 'NL' }] as LogisticsPriceRow[] },
  } as LogisticsRule))
  logisticsRules.splice(0, logisticsRules.length, ...rules)
  const policies: FinanceChannelPolicy[] = [{ id: 'general', category: '普货', enabled: true, updatedAt: '',
    countryRules: [{ country: '荷兰', allowedChannels: rules.map(rule => financeChannelKey(rule.id, rule.relations[0]!)), stage: 'rare', continent: '欧洲', sortOrder: 1 }] }]
  return { rules, policies, scans: () => scans }
}

describe('country-scoped finance authorization', () => {
  it('matches the old checks while reducing full country scans from one per channel to one total', () => {
    const { rules, policies, scans } = fixture()
    const old = rules.map(rule => financeAllowsLogisticsChannel(policies, '普货', '荷兰', rule.id, rule.relations[0]!))
    const oldScans = scans()
    const allowed = financeAllowedChannelKeys(policies, '普货', '荷兰')
    const next = rules.map(rule => allowed.has(financeChannelKey(rule.id, rule.relations[0]!)))
    expect(next).toEqual(old)
    expect(next.every(Boolean)).toBe(true)
    expect(oldScans).toBe(rules.length ** 2)
    expect(scans() - oldScans).toBe(rules.length)
  })

  it('retains deny-by-default, disabled policy, duplicate policy and disabled-channel behavior', () => {
    const { rules, policies } = fixture(4)
    policies[0]!.countryRules[0]!.allowedChannels.pop()
    rules[1]!.status = '停用'
    for (const attribute of ['普货', '带电']) for (const country of ['荷兰', 'NL', '西班牙']) {
      const allowed = financeAllowedChannelKeys(policies, attribute, country)
      rules.forEach(rule => expect(allowed.has(financeChannelKey(rule.id, rule.relations[0]!)))
        .toBe(financeAllowsLogisticsChannel(policies, attribute, country, rule.id, rule.relations[0]!)))
    }
    expect(financeAllowedChannelKeys([...policies, ...policies], '普货', 'NL').size).toBe(0)
    policies[0]!.enabled = false
    expect(financeAllowedChannelKeys(policies, '普货', 'NL').size).toBe(0)
  })

  it('reads current authorizations and published coverage on every new calculation', () => {
    const { rules, policies } = fixture(2)
    expect(financeAllowedChannelKeys(policies, '普货', '荷兰').size).toBe(2)
    policies[0]!.countryRules[0]!.allowedChannels.pop()
    expect(financeAllowedChannelKeys(policies, '普货', '荷兰').size).toBe(1)
    rules[0]!.status = '停用'
    expect(financeAllowedChannelKeys(policies, '普货', '荷兰').size).toBe(0)
  })
})
