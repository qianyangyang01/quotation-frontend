import { api } from '@/services/http'

export type FinanceSettingKey = 'country-classification' | 'channel-policies' | 'customer-grades' | 'exchange-rate' | 'tax-settings'

const cache = new Map<FinanceSettingKey, unknown>()
const versions = new Map<FinanceSettingKey, number>()

type VersionedSetting<T> = { value: T; _version: number }

export async function hydrateFinanceSettings() {
  const values = await api.get<Partial<Record<FinanceSettingKey, VersionedSetting<unknown>>>>('/finance-settings')
  for (const [rawKey, wrapped] of Object.entries(values)) {
    const key = rawKey as FinanceSettingKey
    cache.set(key, wrapped.value)
    versions.set(key, wrapped._version)
  }
}

export function readFinanceSetting<T>(key: FinanceSettingKey): T | undefined {
  return cache.get(key) as T | undefined
}

export async function writeFinanceSetting<T>(key: FinanceSettingKey, value: T) {
  const saved = await api.put<VersionedSetting<T>>(`/finance-settings/${key}`, value, { 'If-Match': String(versions.get(key) ?? -1) })
  cache.set(key, saved.value)
  versions.set(key, saved._version)
  return saved.value
}
