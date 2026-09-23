import { beforeEach, expect, it, vi } from 'vitest'
const http = vi.hoisted(() => ({ get: vi.fn(), put: vi.fn() }))
vi.mock('@/services/http', () => ({ api: http }))
import { clearFinanceSettingsCache, financeSettingVersions, hydrateFinanceSettings } from './financeSettings'
import { loadFinanceTaxSettings, saveFinanceTaxSettings } from '@/data/financeTaxSettings'
import { loadFinanceSurchargeSettings, saveFinanceSurchargeSettings } from '@/data/financeSurchargeSettings'
import { loadFinanceCountrySettings, saveFinanceCountrySettings, loadFinanceExchangeRate, saveFinanceExchangeRate, saveFinanceEurUsdRate, loadCustomerGradeSettings, saveCustomerGradeSettings } from '@/data/financeChannelPolicies'

function snapshot(version: number) {
  const rule = { key: '579::云速递::TEST', mode: version === 3 ? 'exempt' : 'fixed-order', amount: 3.51, currency: 'USD', perKg: 0 }
  const tax = { countries: [{ country: '美国', fixedFeeUsd: version, selected: true, enabled: true, sortOrder: 1, channelRules: [rule] }], providers: [], updatedAt: `v${version}` }
  return {
    'country-classification': { value: [{ country: '美国', enabled: version !== 3, sortOrder: 1 }], _version: version },
    'channel-policies': { value: [], _version: version },
    'customer-grades': { value: [{ grade: 'S', enabled: true, coefficient: version === 3 ? 1.3 : 1.2 }], _version: version },
    'exchange-rate': { value: { usdCny: version === 3 ? 7.1 : 6.7, eurUsd: version === 3 ? 1.2 : 1.16, updatedAt: `v${version}` }, _version: version },
    'tax-settings': { value: tax, _version: version },
    'surcharge-settings': { value: { ...tax, countries: tax.countries.map(c => ({ ...c, channelRules: undefined })) }, _version: version },
    'customer-operation-fees': { value: { customers: [] }, _version: version },
  }
}
beforeEach(() => { vi.resetAllMocks(); clearFinanceSettingsCache() })
const scenarios = [
  { key: 'tax-settings', save: () => saveFinanceTaxSettings(loadFinanceTaxSettings()), read: loadFinanceTaxSettings },
  { key: 'surcharge-settings', save: () => saveFinanceSurchargeSettings(loadFinanceSurchargeSettings()), read: loadFinanceSurchargeSettings },
  { key: 'country-classification', save: () => saveFinanceCountrySettings(loadFinanceCountrySettings()), read: loadFinanceCountrySettings },
  { key: 'exchange-rate', save: () => saveFinanceExchangeRate(6.8), read: loadFinanceExchangeRate },
  { key: 'exchange-rate', save: () => saveFinanceEurUsdRate(1.17), read: loadFinanceExchangeRate },
  { key: 'customer-grades', save: () => saveCustomerGradeSettings(loadCustomerGradeSettings()), read: loadCustomerGradeSettings },
] as const
it.each(scenarios)('returns the newest $key to the editor when a save response arrives after a newer read', async scenario => {
  http.get.mockResolvedValueOnce(snapshot(1)); await hydrateFinanceSettings()
  let resolve!: (value: unknown) => void
  http.put.mockImplementationOnce((_path, value) => new Promise(done => { resolve = () => done({ value, _version: 2 }) }))
  const saving = scenario.save()
  http.get.mockResolvedValueOnce(snapshot(3)); await hydrateFinanceSettings({ force: true })
  resolve(null)
  expect(await saving).toEqual(scenario.read())
  expect(financeSettingVersions()[scenario.key]).toBe(3)
})
