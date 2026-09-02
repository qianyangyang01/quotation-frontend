import { describe, expect, it } from 'vitest'
import { buildFinancePreview, canonicalChannelName, classifyBindings } from './logistics-binding-report.mjs'

const version = (id, rows) => ({ id, payload: { rows } })
const row = (countryCode, areaName) => ({ countryCode, areaName })

describe('logistics binding report', () => {
  it('matches only the explicit provider alias and a unique semantic identity', () => {
    const snapshot = {
      channels: [{ id: 'old', ruleId: 9, providerName: '联邮通', name: '联邮标准挂号带电', code: 'OLD', logisticsAttribute: '带电', currentVersionId: 'v1', channelKey: '9::联邮通::OLD' }],
      fullVersions: [version('v1', [row('US', '美国')])],
    }
    const current = {
      channels: [{ id: 'new', ruleId: 68, providerName: '递四方', name: '联邮通标准挂号-带电（OH）', code: 'NEW', logisticsAttribute: '带电', quoteReady: true, countries: ['US'], areaNames: ['美国'], priceRows: 1 }],
      finance: { payload: [{ category: '带电', countryRules: [{ country: '美国', allowedChannels: ['9::联邮通::OLD'] }] }] },
    }
    const [binding] = classifyBindings(snapshot, current, 'abc')
    expect(binding.status).toBe('verified')
    expect(binding.target.channelKey).toBe('68::递四方::NEW')
  })

  it('does not map a non-ready or country-incomplete channel', () => {
    const snapshot = { channels: [{ id: 'old', ruleId: 1, providerName: '顺丰', name: '顺丰服装', code: 'OLD', logisticsAttribute: '普货', currentVersionId: 'v1' }], fullVersions: [version('v1', [row('US', '美国')])] }
    const current = { channels: [{ id: 'new', ruleId: 2, providerName: '顺丰', name: '服装专线', code: 'NEW', logisticsAttribute: '普货', quoteReady: false, countries: ['GB'], areaNames: ['英国'] }], finance: { payload: [{ category: '普货', countryRules: [{ country: '美国', allowedChannels: ['1::顺丰::OLD'] }] }] } }
    expect(classifyBindings(snapshot, current, 'abc')[0].status).toBe('unavailable')
  })

  it('moves unresolved legacy keys to unavailableChannels without activating them', () => {
    const binding = { channelKey: '1::闪电猴::OLD', providerName: '闪电猴', name: '闪电猴普货', status: 'unavailable', backupSha256: 'abc', target: null }
    const payload = [{ category: '普货', countryRules: [{ country: '美国', allowedChannels: [binding.channelKey] }] }]
    const [policy] = buildFinancePreview(payload, [binding])
    expect(policy.countryRules[0].allowedChannels).toEqual([])
    expect(policy.countryRules[0].unavailableChannels).toMatchObject([{ legacyKey: binding.channelKey, status: 'unavailable' }])
  })

  it('keeps meaningful service-tier words while normalizing punctuation and attributes', () => {
    expect(canonicalChannelName('联邮标准挂号带电', '联邮通', '带电')).toBe('标准挂号')
    expect(canonicalChannelName('联邮通标准挂号-带电（OH）', '递四方', '带电')).toBe('标准挂号')
    expect(canonicalChannelName('美国标准小包专线-普货', '花海', '普货')).not.toBe(canonicalChannelName('美国全量商派专线-普货', '花海', '普货'))
  })
})
