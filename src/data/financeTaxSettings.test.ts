import { describe, expect, it } from 'vitest'
import { calculateFinanceQuoteTax, normalizeFinanceTaxSettings, type FinanceTaxSettings } from './financeTaxSettings'

const settings: FinanceTaxSettings = {
  countries: [{ country: '美国', aFixedFeeUsd: 1, bPerItemFeeUsd: 0.25, selected: true, enabled: true, sortOrder: 10 }],
  providers: [
    { provider: '应税物流', mode: 'taxable', selected: true, channels: [] },
    { provider: '免税物流', mode: 'exempt', selected: true, channels: [] },
  ],
  updatedAt: 'test',
}

describe('quotation tax calculation', () => {
  it('adds one fixed fee for an A customer and a per-item fee for a B customer', () => {
    expect(calculateFinanceQuoteTax(settings, '美国', '应税物流', 10, 'A', 5)).toMatchObject({ taxUsd: 1, totalUsd: 11, feeMode: 'fixed-order' })
    expect(calculateFinanceQuoteTax(settings, '美国', '应税物流', 10, 'B', 5)).toMatchObject({ taxUsd: 1.25, totalUsd: 11.25, feeMode: 'per-item' })
  })

  it('does not add tax for exempt providers and reports missing configuration', () => {
    expect(calculateFinanceQuoteTax(settings, '美国', '免税物流', 10, 'A', 1)).toMatchObject({ configured: true, totalUsd: 10, feeMode: 'exempt' })
    expect(calculateFinanceQuoteTax(settings, '美国', '未配置物流', 10, 'A', 1)).toMatchObject({ configured: false, totalUsd: 10, feeMode: 'missing' })
  })

  it('normalizes negative/invalid prices and quantities without producing NaN', () => {
    const result = calculateFinanceQuoteTax(settings, '美国', '应税物流', Number.NaN, 'B', 0)
    expect(result).toMatchObject({ quantity: 1, taxUsd: 0.25, totalUsd: 0.25 })
  })

  it('preserves saved countries and providers before the logistics catalog is loaded', () => {
    const normalized = normalizeFinanceTaxSettings(settings)
    expect(normalized.countries).toContainEqual(settings.countries[0])
    expect(normalized.providers).toEqual(expect.arrayContaining(settings.providers))
  })
})
