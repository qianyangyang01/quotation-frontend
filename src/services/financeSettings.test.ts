import { describe, expect, it } from 'vitest'
import { normalizeFinanceSettingValue } from './financeSettings'

describe('finance setting compatibility', () => {
  it('unwraps database bootstrap containers into the frontend canonical shapes', () => {
    expect(normalizeFinanceSettingValue('country-classification', { countries: [{ country: '美国' }] })).toEqual([{ country: '美国' }])
    expect(normalizeFinanceSettingValue('channel-policies', { policies: [{ category: '普货' }] })).toEqual([{ category: '普货' }])
    expect(normalizeFinanceSettingValue('customer-grades', { grades: [{ grade: 'A' }] })).toEqual([{ grade: 'A' }])
    expect(normalizeFinanceSettingValue('tax-settings', { rules: [] })).toEqual({ countries: [], providers: [], updatedAt: '尚未保存' })
  })

  it('converts the bootstrap exchange-rate field names and preserves canonical values', () => {
    expect(normalizeFinanceSettingValue('exchange-rate', { usdToCny: 7.12, effectiveAt: '2026-08-22' }))
      .toEqual({ usdCny: 7.12, updatedAt: '2026-08-22' })
    const canonical = { usdCny: 7.2, updatedAt: '财务维护' }
    expect(normalizeFinanceSettingValue('exchange-rate', canonical)).toBe(canonical)
  })
})
