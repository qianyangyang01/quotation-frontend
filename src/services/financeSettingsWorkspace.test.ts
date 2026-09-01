import { beforeEach, describe, expect, it, vi } from 'vitest'

const dependencies = vi.hoisted(() => ({
  hydrateFinanceSettings: vi.fn(),
  loadFinanceChannelPolicies: vi.fn(),
  loadFinanceCountrySettings: vi.fn(),
  loadCustomerGradeSettings: vi.fn(),
  loadFinanceExchangeRate: vi.fn(),
  loadFinanceTaxSettings: vi.fn(),
  apiGet: vi.fn(),
}))

vi.mock('@/services/financeSettings', () => ({ hydrateFinanceSettings: dependencies.hydrateFinanceSettings }))
vi.mock('@/data/financeChannelPolicies', () => ({
  loadFinanceChannelPolicies: dependencies.loadFinanceChannelPolicies,
  loadFinanceCountrySettings: dependencies.loadFinanceCountrySettings,
  loadCustomerGradeSettings: dependencies.loadCustomerGradeSettings,
  loadFinanceExchangeRate: dependencies.loadFinanceExchangeRate,
}))
vi.mock('@/data/financeTaxSettings', () => ({ loadFinanceTaxSettings: dependencies.loadFinanceTaxSettings }))
vi.mock('@/services/http', () => ({ api: { get: dependencies.apiGet } }))

import { loadFinanceRequiredPreview, loadFinanceRequiredPreviewDatasets, loadFinanceSettingsWorkspace } from './financeSettingsWorkspace'

function configureReaders() {
  dependencies.loadFinanceChannelPolicies.mockReturnValue([{ id: '普货' }])
  dependencies.loadFinanceCountrySettings.mockReturnValue([{ country: '美国' }])
  dependencies.loadCustomerGradeSettings.mockReturnValue([{ grade: 'S', coefficient: 1.12, enabled: true }])
  dependencies.loadFinanceExchangeRate.mockReturnValue({ usdCny: 6.75, updatedAt: '财务维护' })
  dependencies.loadFinanceTaxSettings.mockReturnValue({ countries: [], providers: [], updatedAt: '财务维护' })
}

describe('finance settings workspace loading', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    configureReaders()
  })

  it('does not expose normalized defaults before server hydration completes', async () => {
    let resolveHydration!: () => void
    dependencies.hydrateFinanceSettings.mockReturnValue(new Promise<void>(resolve => { resolveHydration = resolve }))

    const loading = loadFinanceSettingsWorkspace()
    await Promise.resolve()
    expect(dependencies.loadFinanceCountrySettings).not.toHaveBeenCalled()
    expect(dependencies.loadFinanceExchangeRate).not.toHaveBeenCalled()

    resolveHydration()
    const workspace = await loading
    expect(workspace.countries).toEqual([{ country: '美国' }])
    expect(workspace.exchangeRate.usdCny).toBe(6.75)
    expect(dependencies.loadFinanceChannelPolicies).toHaveBeenCalledOnce()
    expect(dependencies.loadFinanceTaxSettings).toHaveBeenCalledOnce()
  })

  it('keeps readers hidden after failure and supports a forced retry', async () => {
    dependencies.hydrateFinanceSettings.mockRejectedValueOnce(new Error('网络不可用')).mockResolvedValueOnce(undefined)

    await expect(loadFinanceSettingsWorkspace()).rejects.toThrow('网络不可用')
    expect(dependencies.loadFinanceCountrySettings).not.toHaveBeenCalled()

    const workspace = await loadFinanceSettingsWorkspace({ force: true })
    expect(dependencies.hydrateFinanceSettings).toHaveBeenLastCalledWith({ force: true })
    expect(workspace.customerGrades).toHaveLength(1)
  })

  it('loads preparing-dataset summaries and one read-only required-channel preview', async () => {
    dependencies.apiGet.mockResolvedValueOnce([{ id: 'dataset-1', confirmed: true, requiredCount: 6 }])
      .mockResolvedValueOnce({ datasetId: 'dataset-1', requiredCount: 6, readyCount: 0, channels: [{ id: 'channel-1', quoteReady: false }] })

    await expect(loadFinanceRequiredPreviewDatasets()).resolves.toEqual([{ id: 'dataset-1', confirmed: true, requiredCount: 6 }])
    await expect(loadFinanceRequiredPreview('dataset-1')).resolves.toEqual(expect.objectContaining({ datasetId: 'dataset-1', readyCount: 0 }))
    expect(dependencies.apiGet).toHaveBeenNthCalledWith(1, '/finance-settings/logistics-required-previews')
    expect(dependencies.apiGet).toHaveBeenNthCalledWith(2, '/finance-settings/logistics-required-previews/dataset-1')
  })
})
