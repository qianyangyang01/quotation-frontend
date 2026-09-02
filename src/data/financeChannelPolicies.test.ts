import { describe, expect, it } from 'vitest'
import { COMMON_COUNTRY_LIMIT, normalizeCustomerGradeSettings, normalizeFinanceCountrySettings, normalizePolicies } from './financeChannelPolicies'

describe('common country settings', () => {
  it('allows finance to configure up to 40 common countries', () => {
    expect(COMMON_COUNTRY_LIMIT).toBe(40)
  })

  it('keeps saved country master data when no active channel currently publishes it', () => {
    const settings = normalizeFinanceCountrySettings([{ country: '历史国家', code: 'HX', stage: 'standard', continent: '亚洲', sortOrder: 700, enabled: false }])

    expect(settings.find(setting => setting.country === '历史国家')).toEqual({ country: '历史国家', code: 'HX', stage: 'standard', continent: '亚洲', sortOrder: 700, enabled: false })
  })
})

describe('customer grade settings', () => {
  it('restores all S-E rows when the persisted setting is empty', () => {
    const settings = normalizeCustomerGradeSettings([])

    expect(settings.map(setting => setting.grade)).toEqual(['S', 'A', 'B', 'C', 'D', 'E'])
    expect(settings.every(setting => setting.enabled)).toBe(true)
  })

  it('preserves configured values and fills missing grades', () => {
    const settings = normalizeCustomerGradeSettings([
      { grade: 'S', coefficient: 1.08, enabled: false },
      { grade: 'C', coefficient: 1.35, enabled: true },
    ])

    expect(settings).toHaveLength(6)
    expect(settings.find(setting => setting.grade === 'S')).toEqual({ grade: 'S', coefficient: 1.08, enabled: false })
    expect(settings.find(setting => setting.grade === 'C')).toEqual({ grade: 'C', coefficient: 1.35, enabled: true })
    expect(settings.find(setting => setting.grade === 'E')).toEqual({ grade: 'E', coefficient: 1.3, enabled: true })
  })

  it('keeps a complete disabled configuration instead of replacing it', () => {
    const settings = normalizeCustomerGradeSettings(['S', 'A', 'B', 'C', 'D', 'E'].map((grade, index) => ({
      grade: grade as 'S' | 'A' | 'B' | 'C' | 'D' | 'E',
      coefficient: 1 + index / 10,
      enabled: false,
    })))

    expect(settings.every(setting => !setting.enabled)).toBe(true)
  })
})

describe('unavailable logistics bindings', () => {
  it('preserves and deduplicates disabled legacy channels without making them allowed', () => {
    const legacy = { legacyKey: '1::旧物流::OLD', providerName: '旧物流', channelName: '旧渠道', status: 'unavailable' as const, reason: 'no-current-equivalent', backupSha256: 'abc' }
    const [policy] = normalizePolicies([{ id: '普通', category: '普货', enabled: true, updatedAt: 'now', countryRules: [{ country: '美国', allowedChannels: [], unavailableChannels: [legacy, legacy], stage: 'common', continent: '北美洲', sortOrder: 1 }] }])

    expect(policy.countryRules[0].allowedChannels).toEqual([])
    expect(policy.countryRules[0].unavailableChannels).toEqual([legacy])
  })
})
