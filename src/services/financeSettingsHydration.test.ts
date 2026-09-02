import { beforeEach, describe, expect, it, vi } from 'vitest'

const http = vi.hoisted(() => ({ get: vi.fn(), put: vi.fn() }))
vi.mock('@/services/http', () => ({ api: http }))

import {
  clearFinanceSettingsCache,
  financeSettingsAreHydrated,
  hydrateFinanceSettings,
  readFinanceSetting,
} from './financeSettings'

function settings(exchangeRate = 6.75) {
  return {
    'country-classification': { value: [{ country: '美国' }], _version: 3 },
    'channel-policies': { value: [{ id: '普货' }], _version: 3 },
    'customer-grades': { value: [{ grade: 'S', coefficient: 1.12, enabled: true }], _version: 3 },
    'exchange-rate': { value: { usdCny: exchangeRate, updatedAt: '财务维护' }, _version: 1 },
    'tax-settings': { value: { countries: [], providers: [], updatedAt: '财务维护' }, _version: 3 },
  }
}

describe('finance settings hydration', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    clearFinanceSettingsCache()
  })

  it('coalesces concurrent requests and publishes all settings atomically', async () => {
    let resolveRequest!: (value: ReturnType<typeof settings>) => void
    http.get.mockReturnValue(new Promise(resolve => { resolveRequest = resolve }))

    const first = hydrateFinanceSettings()
    const second = hydrateFinanceSettings()
    expect(http.get).toHaveBeenCalledOnce()
    expect(financeSettingsAreHydrated()).toBe(false)
    expect(readFinanceSetting('exchange-rate')).toBeUndefined()

    resolveRequest(settings())
    await Promise.all([first, second])
    expect(financeSettingsAreHydrated()).toBe(true)
    expect(readFinanceSetting<{ usdCny: number }>('exchange-rate')?.usdCny).toBe(6.75)
    expect(readFinanceSetting<unknown[]>('customer-grades')).toHaveLength(1)
  })

  it('marks a failed forced refresh unavailable instead of exposing stale values', async () => {
    http.get.mockResolvedValueOnce(settings(6.75))
    await hydrateFinanceSettings()
    expect(financeSettingsAreHydrated()).toBe(true)

    http.get.mockRejectedValueOnce(new Error('网络不可用'))
    await expect(hydrateFinanceSettings({ force: true })).rejects.toThrow('网络不可用')
    expect(financeSettingsAreHydrated()).toBe(false)

    http.get.mockResolvedValueOnce(settings(7.1))
    await hydrateFinanceSettings()
    expect(readFinanceSetting<{ usdCny: number }>('exchange-rate')?.usdCny).toBe(7.1)
  })

  it('rejects incomplete responses without publishing partial settings', async () => {
    const incomplete = settings() as Partial<ReturnType<typeof settings>>
    delete incomplete['tax-settings']
    http.get.mockResolvedValue(incomplete)

    await expect(hydrateFinanceSettings()).rejects.toThrow('tax-settings')
    expect(financeSettingsAreHydrated()).toBe(false)
    expect(readFinanceSetting('country-classification')).toBeUndefined()
  })
})
