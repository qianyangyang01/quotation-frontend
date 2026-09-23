import { readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { createHash } from 'node:crypto'
import { expect, it } from 'vitest'
import { calculateFinanceQuoteTax, type FinanceTaxSettings } from './financeTaxSettings'
import { calculateFinanceQuoteFees, type FinanceSurchargeSettings } from './financeSurchargeSettings'
import { financeAllowsLogisticsChannel, type FinanceChannelPolicy } from './financeChannelPolicies'
import { calculateLogisticsFee, replaceLogisticsRules, type LogisticsRule } from './logistics'
import { normalizeLogisticsPriceRow } from './logisticsRepository'
import type { LogisticsRateRow } from './logisticsWorkbook'
import { isEuCountry } from './europeanUnion'

const dir = process.env.SUNYOU_FINANCE_DIR
it.skipIf(!dir)('reconciles Sunyou source cells, tax snapshots, every attribute and all other channels', () => {
  const read = (name: string) => JSON.parse(readFileSync(join(dir!, name), 'utf8').replace(/^\uFEFF/, ''))
  const live = read('live-before.json') as {
    settings: Record<string, { payload: unknown }>
    channels: { id: string; ruleId: number; provider: string; code: string; name: string; countries: { code: string; name: string }[]; rows: LogisticsRateRow[] | null }[]
  }
  const finance = read('finance-prepared.json') as { 'tax-settings': FinanceTaxSettings; 'channel-policies': FinanceChannelPolicy[]; 'surcharge-settings'?: FinanceSurchargeSettings }
  const report = read('change-report.json') as {
    remoteScope: string
    ukSurcharge: string
    blockedCountryCodes: string[]
    channels: { key: string; name: string; attributes: string[] }[]
    sources: { rows: Record<string, { cells: unknown[] }> }[]
    taxChanges: { country: string; rule: { key: string; mode: string; amount: number } }[]
    authorizations: { channel: string; attribute: string; country: string; code: string }[]
  }
  expect(report.channels).toHaveLength(3)
  expect(report.blockedCountryCodes).toEqual(['US', 'GB', 'CA', 'AU', 'NZ', 'MX', 'RO'])
  expect(Object.keys(finance).sort()).toEqual(['channel-policies', 'tax-settings'])
  expect(report.sources.flatMap(s => Object.values(s.rows).flatMap(r => r.cells))).toHaveLength(72)
  const before = live.settings['tax-settings']!.payload as FinanceTaxSettings
  const surcharges = live.settings['surcharge-settings']!.payload as FinanceSurchargeSettings
  const afterSurcharges = finance['surcharge-settings'] || surcharges
  const countries = live.settings['country-classification']!.payload as { country: string; code: string }[]
  const rate = live.settings['exchange-rate']!.payload as { usdCny: number; eurUsd: number }
  const quote = (tax: FinanceTaxSettings, country: string, key: string, weightKg: number) => calculateFinanceQuoteTax(tax, country, key.split('::')[1]!, 10, { channelKey: key, weightKg, ...rate })
  const fees = (tax: FinanceTaxSettings, surcharge: FinanceSurchargeSettings, country: string, key: string) => calculateFinanceQuoteFees(tax, surcharge, country, key.split('::')[1]!, 10, key, { weightKg: .1, ...rate })
  const checks: unknown[] = []
  for (const channel of report.channels) for (const country of countries) for (const weight of [.07, .35, 1, 2]) {
    const result = quote(finance['tax-settings'], country.country, channel.key, weight)
    const blocked = report.blockedCountryCodes.includes(country.code)
    expect(result.configured, `${channel.name}/${country.country}`).toBe(!blocked)
    if (!blocked) expect(result.taxUsd, `${channel.name}/${country.country}`).toBe(isEuCountry(country.code) ? 3.51 : 0)
    checks.push({ key: channel.key, country: country.country, weight, result })
    if (report.remoteScope !== 'pending' && report.ukSurcharge !== 'pending' && !blocked) {
      const combined = fees(finance['tax-settings'], afterSurcharges, country.country, channel.key)
      expect(combined.configured, `${channel.name}/${country.country} fees`).toBe(true)
      if (country.code === 'GB') expect(combined.surchargeUsd).toBe(report.ukSurcharge === 'taxable' ? .5 : 0)
    }
  }
  let oldChecks = 0
  // Check all configured countries, not only each channel's currently displayed countries.
  for (const channel of live.channels.filter(c => c.provider !== '顺友')) for (const country of countries) {
    const key = `${channel.ruleId}::${channel.provider}::${channel.code}`
    expect(fees(finance['tax-settings'], afterSurcharges, country.country, key)).toEqual(fees(before, surcharges, country.country, key))
    oldChecks++
  }
  const sunyou = live.channels.filter(c => c.provider === '顺友')
  const rules: LogisticsRule[] = sunyou.map(c => ({ id: c.ruleId, name: c.name, englishName: '', type: '专线', currency: 'CNY', published: 'test', status: '启用', dates: '', users: '',
    relations: [{ carrier: c.provider, channel: c.name, channelCode: c.code, discounts: '' }], phoneRequired: false, areaCount: c.countries.length,
    priceRowCount: c.rows!.length, billingVerified: true, prices: c.rows!.map(normalizeLogisticsPriceRow) }))
  replaceLogisticsRules(rules)
  let authorizationChecks = 0
  for (const entry of report.authorizations) {
    const rule = rules.find(r => r.name === entry.channel)!
    expect(financeAllowsLogisticsChannel(finance['channel-policies'], entry.attribute, entry.country, rule.id, rule.relations[0]!), JSON.stringify(entry)).toBe(true)
    authorizationChecks++
  }
  for (const channel of report.channels) for (const policy of finance['channel-policies']) for (const rule of policy.countryRules) {
    if (!channel.attributes.includes(policy.category)) expect(rule.allowedChannels).not.toContain(channel.key)
  }
  let blockedAuthorizationChecks = 0
  for (const rule of rules) for (const country of countries.filter(c => report.blockedCountryCodes.includes(c.code))) for (const policy of finance['channel-policies']) {
    expect(financeAllowsLogisticsChannel(finance['channel-policies'], policy.category, country.country, rule.id, rule.relations[0]!)).toBe(false)
    blockedAuthorizationChecks++
  }
  let freightChecks = 0
  for (const rule of rules) for (const row of rule.prices) {
    const zone = row.countryCode === 'CA' && row.zoneName ? `顺友｜${rule.name}｜${row.zoneName}` : row.zoneName
    const weight = (row.weightFromKg + row.weightToKg) / 2
    const original = calculateLogisticsFee(rule, row.countryCode, weight, [], undefined, zone)
    expect(original, `${rule.name}/${row.countryCode}/${zone}`).not.toBeNull()
    for (const attribute of report.channels.find(c => c.name === rule.name)!.attributes) {
      expect(calculateLogisticsFee(rule, row.countryCode, weight, [attribute], undefined, zone)).toEqual(original)
      freightChecks++
    }
  }
  const preparedSha256 = createHash('sha256').update(readFileSync(join(dir!, 'finance-prepared.json'))).digest('hex')
  writeFileSync(join(dir!, 'tax-calculation-evidence.json'), JSON.stringify({ passed: true, preparedSha256, newTaxChecks: checks.length, unchangedOtherChannelChecks: oldChecks, authorizationChecks, blockedAuthorizationChecks, freightChecks, checks }, null, 2))
}, 60000)
