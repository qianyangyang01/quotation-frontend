import { readFileSync } from 'node:fs'
import { expect, it } from 'vitest'
import { calculateLogisticsFee, type LogisticsRule } from './logistics'
import { normalizeLogisticsPriceRow } from './logisticsRepository'
import type { LogisticsRateRow } from './logisticsWorkbook'
import { calculateFinanceQuoteTax, type FinanceTaxSettings } from './financeTaxSettings'

const evidencePath = process.env.WANBANG_PREFERRED_BILLING_CASES
it.skipIf(!evidencePath)('reconciles all 81 preferred tariffs and their boundary gaps against source and backend', () => {
  const evidence: {
    channels: Array<{ channelName: string; providerName: string; rows: LogisticsRateRow[] }>
    cases: Array<{ channel: string; country: string; weightKg: number; total: number }>
    rejected: Array<{ channel: string; country: string; weightKg: number }>
  } = JSON.parse(readFileSync(evidencePath!, 'utf8'))
  expect(evidence.channels).toHaveLength(3)
  expect(evidence.cases).toHaveLength(243)
  expect(evidence.rejected).toHaveLength(81)
  const rules = new Map(evidence.channels.map((channel, i) => [channel.channelName, {
    id: i + 1, name: channel.channelName, englishName: '', type: '专线', currency: 'CNY', published: 'test', status: '启用', dates: '', users: '',
    relations: [{ carrier: channel.providerName, channel: channel.channelName, channelCode: `WBP${i}`, discounts: '' }],
    phoneRequired: false, areaCount: 6, priceRowCount: channel.rows.length, billingVerified: true,
    prices: channel.rows.map(normalizeLogisticsPriceRow),
  } as LogisticsRule]))
  for (const sample of evidence.cases) expect(calculateLogisticsFee(rules.get(sample.channel)!, sample.country, sample.weightKg), JSON.stringify(sample)).toMatchObject({ total: sample.total, chargeWeightKg: sample.weightKg })
  for (const sample of evidence.rejected) expect(calculateLogisticsFee(rules.get(sample.channel)!, sample.country, sample.weightKg), JSON.stringify(sample)).toBeNull()
})

const liveDir = process.env.WANBANG_PREFERRED_FINANCE_DIR
it.skipIf(!liveDir)('uses the live prepared tax settings without adding EU duty or member-country handling fees', () => {
  const before = JSON.parse(readFileSync(`${liveDir}/before.json`, 'utf8'))
  const prepared = JSON.parse(readFileSync(`${liveDir}/finance-prepared.json`, 'utf8'))
  const tax = prepared['tax-settings'] as FinanceTaxSettings
  const plan = JSON.parse(readFileSync(`${liveDir}/finance-plan-resolved.json`, 'utf8')) as { channels: Array<{ name: string; key: string; attributes: string[] }>; countries: string[] }
  for (const channel of plan.channels) for (const country of plan.countries) {
    expect(calculateFinanceQuoteTax(tax, country, '万邦', 10, { channelKey: channel.key, weightKg: 1, usdCny: 6.7, eurUsd: 1.2 }), `${channel.name}/${country}`).toMatchObject({ configured: true, included: true, feeMode: 'exempt', taxUsd: 0, totalUsd: 10 })
    for (const policy of prepared['channel-policies']) {
      const allowed = policy.countryRules.find((rule: { country: string }) => rule.country === country)?.allowedChannels.includes(channel.key) ?? false
      expect(allowed, `${channel.name}/${country}/${policy.category}`).toBe(channel.attributes.includes(policy.category))
    }
  }
  for (const channel of before.channels) for (const country of plan.countries) {
    const context = { channelKey: `${channel.ruleId}::${channel.provider}::${channel.code}`, weightKg: 1, usdCny: 6.7, eurUsd: 1.2 }
    expect(calculateFinanceQuoteTax(tax, country, channel.provider, 10, context)).toEqual(calculateFinanceQuoteTax(before.finance['tax-settings'].payload, country, channel.provider, 10, context))
  }
})
