import { beforeEach, describe, expect, it, vi } from 'vitest'
import { computed } from 'vue'

const http = vi.hoisted(() => ({ get: vi.fn(), put: vi.fn() }))
vi.mock('@/services/http', () => ({ api: http }))

import {
  clearFinanceSettingsCache,
  financeSettingsAreHydrated,
  financeSettingsAreLoading,
  financeSettingsLoadError,
  hydrateFinanceSettings,
  readFinanceSetting,
  writeFinanceSetting,
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

  it('updates an already rendered save blocker when finance hydration finishes without editing the quote', async () => {
    const saveBlocked = computed(() => !financeSettingsAreHydrated())
    expect(saveBlocked.value).toBe(true)
    http.get.mockResolvedValueOnce(settings())
    await hydrateFinanceSettings()
    expect(saveBlocked.value).toBe(false)

    let resolve!: (value: ReturnType<typeof settings>) => void
    http.get.mockReturnValueOnce(new Promise(done => { resolve = done }))
    const refresh = hydrateFinanceSettings({ force: true })
    expect(saveBlocked.value).toBe(true)
    resolve(settings())
    await refresh
    expect(saveBlocked.value).toBe(false)
    clearFinanceSettingsCache()
    expect(saveBlocked.value).toBe(true)
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

  it('loads a newly unconfigured surcharge separately and creates it with version -1', async () => {
    http.get.mockResolvedValue(settings())
    await hydrateFinanceSettings()
    expect(readFinanceSetting('surcharge-settings')).toMatchObject({ countries: [], providers: [] })
    const value = { countries: [], providers: [], updatedAt: 'test' }
    http.put.mockResolvedValue({value, _version: 0})
    await writeFinanceSetting('surcharge-settings', value)
    expect(http.put).toHaveBeenCalledWith('/finance-settings/surcharge-settings', value, {'If-Match': '-1'})
    expect(readFinanceSetting('tax-settings')).toMatchObject({updatedAt: '财务维护'})
  })

  it('marks a failed forced refresh unavailable instead of exposing stale values', async () => {
    http.get.mockResolvedValueOnce(settings(6.75))
    await hydrateFinanceSettings()
    expect(financeSettingsAreHydrated()).toBe(true)

    http.get.mockRejectedValueOnce(new Error('网络不可用'))
    await expect(hydrateFinanceSettings({ force: true })).rejects.toThrow('网络不可用')
    expect(financeSettingsAreHydrated()).toBe(false)
    expect(financeSettingsAreLoading()).toBe(false)
    expect(financeSettingsLoadError()).toBe('网络不可用')

    http.get.mockResolvedValueOnce(settings(7.1))
    await hydrateFinanceSettings()
    expect(readFinanceSetting<{ usdCny: number }>('exchange-rate')?.usdCny).toBe(7.1)
    expect(financeSettingsLoadError()).toBe('')
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
