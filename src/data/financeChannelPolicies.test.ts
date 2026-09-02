import { describe, expect, it } from 'vitest'
import { COMMON_COUNTRY_LIMIT, normalizeCustomerGradeSettings, normalizeFinanceCountrySettings } from './financeChannelPolicies'

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
