import { api } from '@/services/http'

export type FinanceSettingKey = 'country-classification' | 'channel-policies' | 'customer-grades' | 'exchange-rate' | 'tax-settings'

const cache = new Map<FinanceSettingKey, unknown>()
const versions = new Map<FinanceSettingKey, number>()

type VersionedSetting<T> = { value: T; _version: number }

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

export function normalizeFinanceSettingValue(key: FinanceSettingKey, value: unknown): unknown {
  if (!isRecord(value)) return value
  if (key === 'country-classification' && Array.isArray(value.countries)) return value.countries
  if (key === 'channel-policies' && Array.isArray(value.policies)) return value.policies
  if (key === 'customer-grades' && Array.isArray(value.grades)) return value.grades
  if (key === 'exchange-rate' && !('usdCny' in value) && 'usdToCny' in value) {
    return { usdCny: value.usdToCny, updatedAt: String(value.effectiveAt || '财务维护') }
  }
  if (key === 'tax-settings' && Array.isArray(value.rules) && !('countries' in value) && !('providers' in value)) {
    return { countries: [], providers: [], updatedAt: '尚未保存' }
  }
  return value
}

export async function hydrateFinanceSettings() {
  const values = await api.get<Partial<Record<FinanceSettingKey, VersionedSetting<unknown>>>>('/finance-settings')
  for (const [rawKey, wrapped] of Object.entries(values)) {
    const key = rawKey as FinanceSettingKey
    cache.set(key, normalizeFinanceSettingValue(key, wrapped.value))
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
