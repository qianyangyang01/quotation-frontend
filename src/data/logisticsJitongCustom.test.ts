import { readFileSync } from 'node:fs'
import { expect, it } from 'vitest'
import { calculateLogisticsFee, type LogisticsRule } from './logistics'
import { normalizeLogisticsPriceRow } from './logisticsRepository'
import { calculateFinanceQuoteTax, normalizeFinanceTaxSettings } from './financeTaxSettings'
import { financeAuthorizedChannelKeys, type FinanceChannelPolicy } from './financeChannelPolicies'
import { decimal } from '@/services/quotationDecimal'

type Row = { sourceRow: number; countryCode: string; weightFromKg: number; weightToKg: number; weightFromInclusive: boolean; weightToInclusive: boolean; pricePerKg: number; registrationFee: number }
type Channel = { name: string; attributes: string[]; countries: { code: string; name: string }[]; rows: Row[] }
type Sample = { country: string; weightKg: number; chargeWeightKg: number; total: number }
const root = process.env.JITONG_PACKAGE
const read = (name: string) => JSON.parse(readFileSync(`${root}/${name}.json`, 'utf8'))

it.skipIf(!root)('reconciles the four Jitong channels against source prices at every gram and fractional boundary', () => {
  const expected = read('expected') as { channels: Channel[] }
  const parsed = read('parsed') as { channels: { channelName: string; rows: Record<string, unknown>[] }[] }
  const published = process.env.JITONG_PUBLISHED ? read('published-rules') as LogisticsRule[] : null
  const cases = read('billing-cases') as { channels: { name: string; samples: Sample[]; rejected: Sample[] }[] }
  let checked = 0
  for (const channel of expected.channels) {
    const payload = parsed.channels.find(c => c.channelName === channel.name)!
    const live = published?.find(r => r.relations.some(x => x.carrier === '极通环球' && x.channel === channel.name))
    if (published) expect(live, channel.name).toBeTruthy()
    const actualRows = live?.prices ?? payload.rows
    expect(actualRows).toHaveLength(channel.rows.length)
    const rule = { id: 999, name: channel.name, englishName: '', type: '专线', currency: 'CNY', published: '', status: '启用', dates: '', users: '', relations: [], phoneRequired: false, areaCount: 13, priceRowCount: channel.rows.length, billingVerified: true, ...live,
      prices: actualRows!.map(r => normalizeLogisticsPriceRow(r)) } as LogisticsRule
    for (const country of channel.countries) {
      const rows = channel.rows.filter(r => r.countryCode === country.code)
      const max = Math.max(...rows.map(r => r.weightToKg))
      for (let gram = 1; gram <= max * 1000; gram++) {
        const charged = Math.max(50, gram) / 1000
        const source = rows.filter(r => (charged > r.weightFromKg || charged === r.weightFromKg && r.weightFromInclusive) && (charged < r.weightToKg || charged === r.weightToKg && r.weightToInclusive))
        expect(source).toHaveLength(1)
        const total = decimal(charged).times(source[0]!.pricePerKg).plus(source[0]!.registrationFee).toDecimalPlaces(2).toNumber()
        const actual = calculateLogisticsFee(rule, country.code, gram / 1000)
        if (!actual || actual.total !== total || actual.chargeWeightKg !== charged) throw new Error(`${channel.name}/${country.code}/${gram}g: ${JSON.stringify(actual)} != ${total}`)
        checked++
      }
    }
    const samples = cases.channels.find(c => c.name === channel.name)!
    for (const s of samples.samples) expect(calculateLogisticsFee(rule, s.country, s.weightKg)).toMatchObject({ total: s.total, chargeWeightKg: s.chargeWeightKg })
    for (const s of samples.rejected) expect(calculateLogisticsFee(rule, s.country, s.weightKg)).toBeNull()
  }
  expect(checked).toBeGreaterThan(200000)
}, 120000)

it.skipIf(!root || !process.env.JITONG_PUBLISHED)('verifies published tax and shipment authorizations without extending the user scope', () => {
  const expected = read('expected') as { channels: Channel[] }
  const published = read('published-rules') as LogisticsRule[]
  const finance = read('finance-after')
  const taxes = normalizeFinanceTaxSettings(finance['tax-settings'].payload)
  const policies = finance['channel-policies'].payload as FinanceChannelPolicy[]
  for (const channel of expected.channels) {
    const rule = published.find(r => r.relations.some(x => x.carrier === '极通环球' && x.channel === channel.name))!
    const relation = rule.relations.find(r => r.carrier === '极通环球' && r.channel === channel.name)!
    const key = `${rule.id}::极通环球::${relation.channelCode || relation.channel}`
    for (const country of channel.countries) {
      for (const weightKg of [.001, .05, .5, 1, 2, 5]) expect(calculateFinanceQuoteTax(taxes, country.name, '极通环球', 0, { channelKey: key, weightKg, usdCny: 6.63, eurUsd: 1.16 }))
        .toMatchObject({ configured: true, included: true, taxUsd: 0, feeMode: 'exempt' })
      for (const attribute of ['普货', '化妆品', '带电', '保健品', '纯电', '香水', '服装']) expect(financeAuthorizedChannelKeys(policies, attribute, country.name).has(key)).toBe(channel.attributes.includes(attribute))
    }
    for (const country of ['美国', '英国', '哥伦比亚', '日本']) for (const attribute of channel.attributes) expect(financeAuthorizedChannelKeys(policies, attribute, country).has(key)).toBe(false)
  }
})
