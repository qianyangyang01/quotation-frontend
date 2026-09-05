import { beforeEach, describe, expect, it, vi } from 'vitest'

const dependencies = vi.hoisted(() => ({
  hydrateFinanceSettings: vi.fn(),
  loadPublishedLogisticsManifest: vi.fn(),
  loadFinanceCountrySettings: vi.fn(),
  loadFinanceTaxSettings: vi.fn(),
  loadFinanceChannelPolicies: vi.fn(),
  loadFinanceExchangeRate: vi.fn(),
  loadCustomerGradeSettings: vi.fn(),
}))

vi.mock('@/services/financeSettings', () => ({ hydrateFinanceSettings: dependencies.hydrateFinanceSettings }))
vi.mock('@/data/publishedLogisticsRepository', () => ({ loadPublishedLogisticsManifest: dependencies.loadPublishedLogisticsManifest }))
vi.mock('@/data/financeChannelPolicies', () => ({
  loadFinanceCountrySettings: dependencies.loadFinanceCountrySettings,
  loadFinanceChannelPolicies: dependencies.loadFinanceChannelPolicies,
  loadFinanceExchangeRate: dependencies.loadFinanceExchangeRate,
  loadCustomerGradeSettings: dependencies.loadCustomerGradeSettings,
}))
vi.mock('@/data/financeTaxSettings', () => ({ loadFinanceTaxSettings: dependencies.loadFinanceTaxSettings }))

describe('quotation workspace configuration bootstrap', () => {
  beforeEach(() => vi.clearAllMocks())

  it('waits for finance settings and the published country catalog before normalizing quote configuration', async () => {
    let resolveFinance!: () => void
    let resolveManifest!: () => void
    dependencies.hydrateFinanceSettings.mockReturnValue(new Promise<void>(resolve => { resolveFinance = resolve }))
    dependencies.loadPublishedLogisticsManifest.mockReturnValue(new Promise<void>(resolve => { resolveManifest = resolve }))
    dependencies.loadFinanceCountrySettings.mockReturnValue([{ country: '美国' }, { country: '英国' }, { country: '法国' }, { country: '澳大利亚' }])
    dependencies.loadFinanceTaxSettings.mockReturnValue({ countries: [] })
    dependencies.loadFinanceChannelPolicies.mockReturnValue([{ category: '普货' }])

    const { loadQuotationWorkspaceConfiguration } = await import('./quotationWorkspaceBootstrap')
    const loading = loadQuotationWorkspaceConfiguration()
    await Promise.resolve()
    expect(dependencies.loadFinanceCountrySettings).not.toHaveBeenCalled()

    resolveFinance()
    await Promise.resolve()
    expect(dependencies.loadFinanceCountrySettings).not.toHaveBeenCalled()

    resolveManifest()
    const configuration = await loading

    expect(configuration.countrySettings.map(setting => setting.country)).toEqual(['美国', '英国', '法国', '澳大利亚'])
    expect(dependencies.loadFinanceCountrySettings).toHaveBeenCalledOnce()
    expect(dependencies.loadFinanceChannelPolicies).toHaveBeenCalledOnce()
  })

  it('reads recovered exchange rates and grades only after a failed finance load succeeds on retry', async () => {
    dependencies.loadPublishedLogisticsManifest.mockResolvedValue(undefined)
    dependencies.hydrateFinanceSettings.mockRejectedValueOnce(new Error('finance unavailable'))
    const { loadQuotationWorkspaceConfiguration } = await import('./quotationWorkspaceBootstrap')
    await expect(loadQuotationWorkspaceConfiguration()).rejects.toThrow('finance unavailable')
    expect(dependencies.loadFinanceExchangeRate).not.toHaveBeenCalled()
    expect(dependencies.loadCustomerGradeSettings).not.toHaveBeenCalled()

    dependencies.hydrateFinanceSettings.mockImplementationOnce(async () => {
      dependencies.loadFinanceExchangeRate.mockReturnValue({ usdCny: 7, updatedAt: 'recovered' })
      dependencies.loadCustomerGradeSettings.mockReturnValue([{ grade: 'S', enabled: true, coefficient: 1.23 }])
    })
    const recovered = await loadQuotationWorkspaceConfiguration()
    expect(recovered.exchangeRate).toEqual({ usdCny: 7, updatedAt: 'recovered' })
    expect(recovered.customerGrades).toEqual([{ grade: 'S', enabled: true, coefficient: 1.23 }])
  })
})
