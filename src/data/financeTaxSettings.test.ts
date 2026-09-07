import { describe, expect, it } from 'vitest'
import { calculateFinanceQuoteTax, normalizeFinanceTaxSettings, saveFinanceTaxSettings, type FinanceTaxSettings } from './financeTaxSettings'

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

it('treats absent, unselected, disabled and zero duty countries as configured no-tax before provider lookup', () => {
  for (const country of ['澳大利亚', '英国', '加拿大']) {
    for (const provider of ['应税物流', '免税物流', '未配置物流']) {
      expect(calculateFinanceQuoteTax(settings, country, provider, 12)).toMatchObject({ configured: true, feeMode: 'no-tax', label: '无关税', taxUsd: 0, totalUsd: 12 })
    }
  }
  for (const change of [{ selected: false }, { enabled: false }, { fixedFeeUsd: 0 }]) {
    const modified = { ...settings, countries: [{ ...settings.countries[0]!, ...change }] }
    expect(calculateFinanceQuoteTax(normalizeFinanceTaxSettings(modified), '美国', '未配置物流', 12).feeMode).toBe('no-tax')
  }
  const second = { ...settings, countries: [...settings.countries, { ...settings.countries[0]!, country: '澳大利亚', fixedFeeUsd: 3 }] }
  expect(calculateFinanceQuoteTax(second, '澳大利亚', '应税物流', 12)).toMatchObject({ feeMode: 'fixed-order', totalUsd: 15 })
})

describe('country surcharge and channel exemptions', () => {
  function fees(): FinanceTaxSettings {
    return {
      ...settings,
      countries: [
        { ...settings.countries[0]!, fixedFeeUsd: 5, surchargeFeeUsd: 2, surchargeEnabled: true },
        { ...settings.countries[0]!, country: '英国', fixedFeeUsd: 3, surchargeFeeUsd: 4, surchargeEnabled: true },
      ],
    }
  }
  it.each([
    ['taxable', 'taxable', 5, 2, 27],
    ['taxable', 'exempt', 5, 0, 25],
    ['exempt', 'taxable', 0, 2, 22],
    ['exempt', 'exempt', 0, 0, 20],
  ] as const)('handles independent exemptions %s / %s', (taxMode, surchargeMode, taxUsd, surchargeUsd, totalUsd) => {
    const config = fees()
    config.channelFees = [{ country: '美国', channelKey: '1::应税物流::A', taxMode, surchargeMode }]
    expect(calculateFinanceQuoteTax(config, '美国', '应税物流', 20, '1::应税物流::A')).toMatchObject({ configured: true, taxUsd, surchargeUsd, totalUsd })
  })
  it('adds each fee once for every quoted quantity, including bundles', () => {
    for (const quantity of [1, 2, 3, 25]) {
      const result = calculateFinanceQuoteTax(fees(), '美国', '应税物流', 20 * quantity, '1::应税物流::A')
      expect(result).toMatchObject({ taxUsd: 5, surchargeUsd: 2, totalUsd: 20 * quantity + 7 })
    }
  })
  it('does not interpret provider tax exemption as surcharge exemption', () => {
    expect(calculateFinanceQuoteTax(fees(), '美国', '免税物流', 20, '2::免税物流::A')).toMatchObject({ included: true, taxUsd: 0, surchargeUsd: 2, totalUsd: 22 })
  })
  it('isolates country and exact channel identities, preserving retired bindings', () => {
    const config = fees()
    config.channelFees = [{ country: '美国', channelKey: '1::应税物流::A', taxMode: 'exempt', surchargeMode: 'exempt' }]
    expect(calculateFinanceQuoteTax(config, '英国', '应税物流', 20, '1::应税物流::A').totalUsd).toBe(27)
    expect(calculateFinanceQuoteTax(config, '美国', '应税物流', 20, '9::应税物流::A').totalUsd).toBe(27)
    expect(calculateFinanceQuoteTax(config, '美国', '应税物流', 20, '1::应税物流::B').totalUsd).toBe(27)
    expect(normalizeFinanceTaxSettings(config).channelFees).toEqual(config.channelFees)
  })
  it('leaves old quotes unchanged and defaults new surcharges to disabled', () => {
    expect(calculateFinanceQuoteTax(normalizeFinanceTaxSettings(settings), '美国', '应税物流', 20)).toMatchObject({ surchargeUsd: 0, surchargeEnabled: false, totalUsd: 21 })
    const config = fees()
    config.countries[0]!.surchargeEnabled = false
    expect(calculateFinanceQuoteTax(config, '美国', '应税物流', 20).totalUsd).toBe(25)
  })
  it('preserves explicitly configured zero and disabled duty while retaining production no-tax behavior', () => {
    const config = fees()
    config.countries[0] = { ...config.countries[0]!, fixedFeeUsd: 0, taxConfigured: true, enabled: true }
    expect(calculateFinanceQuoteTax(normalizeFinanceTaxSettings(config), '美国', '应税物流', 20)).toMatchObject({ configured: true, totalUsd: 22 })
    config.countries[0]!.enabled = false
    config.countries[0]!.fixedFeeUsd = 5
    expect(calculateFinanceQuoteTax(normalizeFinanceTaxSettings(config), '美国', '应税物流', 20)).toMatchObject({ configured: true, taxUsd: 0, totalUsd: 22 })
    delete config.countries[0]!.taxConfigured
    config.countries[0]!.fixedFeeUsd = 0
    expect(calculateFinanceQuoteTax(normalizeFinanceTaxSettings(config), '美国', '应税物流', 20)).toMatchObject({ configured: true, feeMode: 'no-tax', surchargeUsd: 2, totalUsd: 22 })
  })
  it('allows an explicit channel tax mode when no provider default exists', () => {
    const config = fees()
    config.channelFees = [{ country: '美国', channelKey: '3::新物流::A', taxMode: 'taxable' }]
    expect(calculateFinanceQuoteTax(config, '美国', '新物流', 20, '3::新物流::A')).toMatchObject({ configured: true, totalUsd: 27 })
    expect(calculateFinanceQuoteTax(config, '美国', '新物流', 20, '3::新物流::B').configured).toBe(false)
  })
  it.each([-1, NaN, Infinity, 1_000_001, '' as unknown as number])('rejects invalid surcharge %s before writing', async value => {
    const config = fees()
    config.countries[0]!.surchargeFeeUsd = value
    await expect(saveFinanceTaxSettings(config)).rejects.toThrow('税费金额须为')
  })
})
