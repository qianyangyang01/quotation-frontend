import { describe, expect, it } from 'vitest'
import { calculateFinanceQuoteTax, normalizeFinanceTaxSettings, type FinanceTaxSettings } from './financeTaxSettings'

const settings: FinanceTaxSettings = {
  countries: [{ country: '美国', fixedFeeUsd: 1, selected: true, enabled: true, sortOrder: 10 }],
  providers: [
    { provider: '应税物流', mode: 'taxable', selected: true, channels: [] },
    { provider: '免税物流', mode: 'exempt', selected: true, channels: [] },
  ],
  updatedAt: 'test',
}

describe('quotation tax calculation', () => {
  it('adds one fixed customs duty regardless of quote quantity', () => {
    expect(calculateFinanceQuoteTax(settings, '美国', '应税物流', 10)).toMatchObject({ taxUsd: 1, totalUsd: 11, feeMode: 'fixed-order' })
    expect(calculateFinanceQuoteTax(settings, '美国', '应税物流', 50)).toMatchObject({ taxUsd: 1, totalUsd: 51, feeMode: 'fixed-order' })
  })

  it('does not add tax for exempt providers and reports missing configuration', () => {
    expect(calculateFinanceQuoteTax(settings, '美国', '免税物流', 10)).toMatchObject({ configured: true, totalUsd: 10, feeMode: 'exempt' })
    expect(calculateFinanceQuoteTax(settings, '美国', '未配置物流', 10)).toMatchObject({ configured: false, totalUsd: 10, feeMode: 'missing' })
  })

  it('normalizes an invalid base price without producing NaN', () => {
    const result = calculateFinanceQuoteTax(settings, '美国', '应税物流', Number.NaN)
    expect(result).toMatchObject({ taxUsd: 1, totalUsd: 1 })
  })

  it('migrates the legacy A fixed amount and ignores the legacy B per-item amount', () => {
    const normalized = normalizeFinanceTaxSettings({
      countries: [{ country: '美国', aFixedFeeUsd: 2, bPerItemFeeUsd: 9, selected: true, enabled: true, sortOrder: 10 } as never],
      providers: settings.providers,
      updatedAt: 'legacy',
    })
    expect(normalized.countries.find(item => item.country === '美国')).toMatchObject({ fixedFeeUsd: 2, selected: true, enabled: true })
    expect(normalized.countries.find(item => item.country === '美国')).not.toHaveProperty('bPerItemFeeUsd')
    const bOnly = normalizeFinanceTaxSettings({
      countries: [{ country: '美国', aFixedFeeUsd: 0, bPerItemFeeUsd: 9, selected: true, enabled: true, sortOrder: 10 } as never],
      providers: settings.providers,
      updatedAt: 'legacy',
    })
    expect(bOnly.countries.find(item => item.country === '美国')).toMatchObject({ fixedFeeUsd: 0, selected: true, enabled: false })
  })

  it('preserves saved countries and providers before the logistics catalog is loaded', () => {
    const normalized = normalizeFinanceTaxSettings(settings)
    expect(normalized.countries).toContainEqual(settings.countries[0])
    expect(normalized.providers).toEqual(expect.arrayContaining(settings.providers))
  })
})
