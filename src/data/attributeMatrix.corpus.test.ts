import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { afterEach, expect, it } from 'vitest'
import { financeAllowsLogisticsChannel, type FinanceChannelPolicy } from './financeChannelPolicies'
import { replaceLogisticsRules, type LogisticsRule } from './logistics'

const directory = process.env.ATTRIBUTE_MATRIX_DIR
afterEach(() => replaceLogisticsRules([]))
it.skipIf(!directory)('applies the reviewed matrix and keeps unlisted authorizations unchanged', () => {
  const read = (name: string) => JSON.parse(readFileSync(join(directory!, name), 'utf8').replace(/^\uFEFF/, ''))
  const policies: FinanceChannelPolicy[] = read('attribute-policies.json')
  const original: FinanceChannelPolicy[] = read('live-before.json').settings['channel-policies'].payload
  const report: { matched: Array<{ key: string; attributes: string[]; countries: string[] }> } = read('attribute-report.json')
  const listed = new Set(report.matched.map(row => row.key))
  // Isolate the authorization matrix from independent channel enabled/billing gates.
  replaceLogisticsRules(report.matched.map(row => {
    const [id, carrier, channelCode] = row.key.split('::')
    return { id: Number(id), name: row.key, status: '启用', billingVerified: true,
      relations: [{ carrier: carrier!, channel: row.key, channelCode: channelCode!, discounts: '' }],
      prices: row.countries.map(country => ({ areaName: country, countryCode: country, weightFromKg: 0, weightToKg: 30,
        pricePerKg: 1, registrationFee: 0, quoteReady: true, allowedMarks: '', prohibitedMarks: '' })),
    } as LogisticsRule
  }))
  const attributes = ['普货', '化妆品', '服装', '带电', '纯电', '保健品', '香水', '大货普货', '大货带电', '以色列自提', '以色列到门']
  expect(report.matched.length).toBeGreaterThanOrEqual(108)
  for (const row of report.matched) {
    const [id, carrier, channelCode] = row.key.split('::')
    const relation = { carrier: carrier!, channelCode: channelCode!, channel: '', discounts: '' }
    for (const attribute of attributes) {
      for (const country of new Set([...row.countries, '以色列', '美国'])) {
        const expected = row.attributes.includes(attribute) && row.countries.includes(country)
          && (!attribute.startsWith('以色列') || country === '以色列')
        expect(financeAllowsLogisticsChannel(policies, attribute, country, Number(id), relation), `${row.key}/${attribute}/${country}`).toBe(expected)
      }
    }
  }
  const unchanged = (items: FinanceChannelPolicy[]) => items.flatMap(p => p.countryRules.flatMap(r =>
    r.allowedChannels.filter(k => !listed.has(k) || !attributes.includes(p.category)).map(k => `${p.category}|${r.country}|${k}`))).sort()
  expect(unchanged(policies)).toEqual(unchanged(original))
  for (const attribute of ['以色列自提', '以色列到门']) {
    const policy = policies.find(p => p.category === attribute)!
    expect(policy.enabled).toBe(true)
    expect(policy.countryRules.filter(r => r.allowedChannels.length).map(r => r.country)).toEqual(['以色列'])
  }
})
