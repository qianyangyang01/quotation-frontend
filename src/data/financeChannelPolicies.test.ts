import { describe, expect, it } from 'vitest'
import { COMMON_COUNTRY_LIMIT, describeAustraliaQuoteRegions, normalizeCustomerGradeSettings, normalizeFinanceCountrySettings, normalizePolicies, toggleFinanceChannelSelection } from './financeChannelPolicies'

it('selects a whole provider without duplicating existing channels or removing another provider', () => {
  const original = ['other-provider', 'sf-1']
  expect(toggleFinanceChannelSelection(original, ['sf-1', 'sf-2', 'sf-3'])).toEqual(['other-provider', 'sf-1', 'sf-2', 'sf-3'])
  expect(original).toEqual(['other-provider', 'sf-1'])
  expect(toggleFinanceChannelSelection(['other-provider', 'sf-1', 'sf-2'], ['sf-1', 'sf-2'])).toEqual(['other-provider'])
  expect(toggleFinanceChannelSelection(original, [])).toEqual(original)
})

describe('Australia channel region descriptions', () => {
  const prices = (zones: string[]) => zones.map(zoneName => ({ zoneName, zoneExclude: false }))
  it('recognizes the bare and combined region names present in provider prices', () => {
    for (const zones of [['1区', '2区', '3区', '4区'], ['1区/2区', '3区/4区'], ['澳大利亚一区', '澳大利亚二区', '3区、4区']]) {
      const result = describeAustraliaQuoteRegions(prices(zones))
      expect(result.quoteRegions).toEqual(['澳大利亚1区', '澳大利亚2区', '澳大利亚3区', '澳大利亚4区'])
      expect(result.missingQuoteRegions).toEqual([])
    }
  })
  it('identifies only the missing fourth zone and excludes expressly excluded zones', () => {
    const result = describeAustraliaQuoteRegions([...prices(['1区', '2区', '3区']), { zoneName: '4区', zoneExclude: true }])
    expect(result.missingQuoteRegions).toEqual(['澳大利亚4区'])
    expect(result.quoteRegionSummary).toContain('未提供澳大利亚4区价格')
  })
  it('does not invent missing numbered regions for unzoned or provider-defined pricing', () => {
    expect(describeAustraliaQuoteRegions(prices(['']))).toMatchObject({ missingQuoteRegions: [], quoteRegionSummary: '原表未区分澳大利亚分区，按该渠道国家价格报价' })
    expect(describeAustraliaQuoteRegions(prices(['Zone3亚太及南美主要国家']))).toMatchObject({ missingQuoteRegions: [], quoteRegionSummary: '原表分区：Zone3亚太及南美主要国家' })
    expect(describeAustraliaQuoteRegions(prices(['1区', '']))).toMatchObject({ missingQuoteRegions: [], quoteRegionSummary: '已提供分区：澳大利亚1区；另有未分区价格' })
  })
})

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
