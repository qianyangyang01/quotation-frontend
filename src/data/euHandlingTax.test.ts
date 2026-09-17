import { describe, expect, it } from 'vitest'
import { calculateFinanceQuoteTax, normalizeFinanceTaxSettings, type FinanceTaxSettings } from './financeTaxSettings'
import { calculateFinanceQuoteFees, normalizeFinanceSurchargeSettings } from './financeSurchargeSettings'
import { type ChannelTaxRule } from './channelTaxRules'

const key = '1::燕文::C-test'
const rule = (amount: number, changes: Partial<ChannelTaxRule> = {}): ChannelTaxRule => ({ key, mode: 'fixed-order', amount, perKg: 0, currency: 'USD', ...changes })
function settings() {
  return normalizeFinanceTaxSettings({ countries: [
    { country: '欧盟', selected: true, enabled: true, fixedFeeUsd: 0, sortOrder: 1, channelRules: [rule(3)] },
    { country: '罗马尼亚', selected: true, enabled: true, fixedFeeUsd: 7, sortOrder: 2, euTaxMode: 'add-handling', channelRules: [rule(7)], handlingRules: [rule(1)] },
  ] })
}
const quote = (value: FinanceTaxSettings, country = '罗马尼亚', channelKey = key, weightKg = 0.5, eurUsd = 1.2) => calculateFinanceQuoteTax(value, country, channelKey.split('::')[1]!, 10, { channelKey, weightKg, usdCny: 6.7, eurUsd })
describe('EU duty plus member-country handling fee', () => {
  it.each(['罗马尼亚', 'Romania', 'RO'])('adds 3 + 1 for %s and preserves the original override', country => {
    const value = settings(), original = JSON.stringify(value)
    expect(quote(value, country)).toMatchObject({ taxUsd: 4, totalUsd: 14, fixedFeeUsd: 4, calculation: { rule: 'eu-handling-v1', euTaxUsd: 3, handlingFeeUsd: 1 } })
    expect(JSON.stringify(value)).toBe(original)
    expect(quote(value, '德国').taxUsd).toBe(3)
    expect(quote(value, '美国').taxUsd).toBe(0)
    value.countries.find(c => c.country === '罗马尼亚')!.euTaxMode = 'override'
    expect(quote(value).taxUsd).toBe(7)
  })
  it('round-trips mode and distinct fee rules without treating them as surcharge settings', () => {
    const value = normalizeFinanceTaxSettings(JSON.parse(JSON.stringify(settings())))
    expect(quote(value).taxUsd).toBe(4)
    expect(normalizeFinanceSurchargeSettings(value).countries.find(c => c.country === '罗马尼亚')).not.toHaveProperty('euTaxMode')
  })
  it.each(['no-tax', 'exempt'] as const)('EU %s does not waive processing fees', mode => {
    const value = settings()
    value.countries.find(c => c.country === '欧盟')!.channelRules = [rule(3, { mode })]
    expect(quote(value)).toMatchObject({ included: false, taxUsd: 1, calculation: { euTaxUsd: 0, handlingFeeUsd: 1 } })
  })
  it.each(['no-tax', 'exempt'] as const)('local %s does not waive EU duty', mode => {
    const value = settings()
    value.countries.find(c => c.country === '罗马尼亚')!.handlingRules = [rule(0, { mode })]
    expect(quote(value).taxUsd).toBe(3)
    value.countries.find(c => c.country === '罗马尼亚')!.handlingRules = []
    expect(quote(value).taxUsd).toBe(3)
  })
  it('blocks missing EU group and unavailable EU/local channels', () => {
    for (const name of ['欧盟', '罗马尼亚']) {
      const value = settings(), row = value.countries.find(c => c.country === name)!
      if (name === '欧盟') row.channelRules = [rule(0, { mode: 'unavailable' })]
      else row.handlingRules = [rule(0, { mode: 'unavailable' })]
      expect(quote(value)).toMatchObject({ configured: false, totalUsd: 10 })
    }
    const value = settings()
    value.countries.find(c => c.country === '欧盟')!.selected = false
    expect(quote(value).configured).toBe(false)
  })
  it('inherits legacy EU fixed duty, but blocks missing provider settings', () => {
    const value = settings(), eu = value.countries.find(c => c.country === '欧盟')!
    eu.channelRules = []; eu.fixedFeeUsd = 3; eu.providers = []
    expect(quote(value).configured).toBe(false)
    eu.providers = [{ provider: '燕文', mode: 'taxable', selected: true, channels: [] }]
    expect(quote(value).taxUsd).toBe(4)
  })
  it('adds processing once to the CHC EUR formula and converts its own currency', () => {
    const value = settings(), chc = '1::云途::C-600c364a09421e97a32f'
    value.countries.find(c => c.country === '罗马尼亚')!.handlingRules = [rule(6.7, { key: chc, currency: 'CNY' })]
    expect(quote(value, 'RO', chc)).toMatchObject({ taxUsd: 2.62, fixedFeeUsd: 0, feeMode: 'weight-order', calculation: { euTaxUsd: 1.62, handlingFeeUsd: 1 } })
    expect(quote(value, 'RO', chc, 1)).toMatchObject({ taxUsd: 3.52 })
    expect(quote(value, 'RO', chc, 0).configured).toBe(false)
    expect(quote(value, 'RO', chc, 1, 0).configured).toBe(false)
  })
  it('keeps additional fees separate and charges fixed handling once for every quantity', () => {
    const value = settings()
    const surcharges = normalizeFinanceSurchargeSettings({ countries: [{ country: '罗马尼亚', selected: true, enabled: true, sortOrder: 1, fixedFeeUsd: 2, exemptChannelKeys: [] }] })
    for (const quantity of [1, 2, 3, 5, 10]) {
      expect(calculateFinanceQuoteFees(value, surcharges, '罗马尼亚', '燕文', quantity * 10, key, { quantity, weightKg: quantity * 0.5, usdCny: 6.7 }))
        .toMatchObject({ taxUsd: 4, surchargeUsd: 2, totalUsd: quantity * 10 + 6 })
    }
  })
})
