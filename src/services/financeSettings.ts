import { api } from '@/services/http'

export type FinanceSettingKey = 'country-classification' | 'channel-policies' | 'customer-grades' | 'exchange-rate' | 'tax-settings' | 'surcharge-settings' | 'customer-operation-fees'

const financeSettingKeys: FinanceSettingKey[] = ['country-classification', 'channel-policies', 'customer-grades', 'exchange-rate', 'tax-settings', 'surcharge-settings', 'customer-operation-fees']
export type FinanceSettingVersions = Partial<Record<FinanceSettingKey, number>>
const financeSettingLabels: Record<FinanceSettingKey, string> = {
  'country-classification': '国家分类', 'channel-policies': '物流渠道权限', 'customer-grades': '客户等级系数',
  'exchange-rate': '汇率', 'tax-settings': '税费', 'surcharge-settings': '附加费', 'customer-operation-fees': '客户操作费',
}
const cache = new Map<FinanceSettingKey, unknown>()
const versions = new Map<FinanceSettingKey, number>()
let hydrationRequest: Promise<void> | null = null
let hydrationGeneration = 0
let hydrated = false

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
    return { usdCny: value.usdToCny, ...('eurUsd' in value ? { eurUsd: value.eurUsd } : {}), updatedAt: String(value.effectiveAt || '财务维护') }
  }
  if (key === 'tax-settings' && Array.isArray(value.rules) && !('countries' in value) && !('providers' in value)) {
    return { countries: [], providers: [], updatedAt: '尚未保存' }
  }
  return value
}

export function financeSettingsAreHydrated() {
  return hydrated
}

export function financeSettingVersions(): FinanceSettingVersions {
  return Object.fromEntries(versions)
}

export function changedFinanceSettings(applied: FinanceSettingVersions, latest: FinanceSettingVersions): string[] {
  return financeSettingKeys.filter(key => (applied[key] ?? -1) !== (latest[key] ?? -1)).map(key => financeSettingLabels[key])
}

export function clearFinanceSettingsCache() {
  hydrationGeneration += 1
  hydrationRequest = null
  hydrated = false
  cache.clear()
  versions.clear()
}

export function hydrateFinanceSettings(options: { force?: boolean; signal?: AbortSignal } = {}) {
  if (hydrated && !options.force) return Promise.resolve()
  if (hydrationRequest) return hydrationRequest
  if (options.force) hydrated = false

  const generation = hydrationGeneration
  const request = (async () => {
    const values = await api.get<Partial<Record<FinanceSettingKey, VersionedSetting<unknown>>>>('/finance-settings', { signal: options.signal, cache: 'no-store' })
    const nextCache = new Map<FinanceSettingKey, unknown>()
    const nextVersions = new Map<FinanceSettingKey, number>()
    const missing = financeSettingKeys.filter(key => !values[key] && key !== 'surcharge-settings' && key !== 'customer-operation-fees')
    if (missing.length) throw new Error(`财务设置返回不完整：${missing.join('、')}`)

    financeSettingKeys.forEach(key => {
      const wrapped = values[key] ?? { value: key === 'customer-operation-fees' ? { customers: [] } : { countries: [], providers: [], updatedAt: '尚未保存' }, _version: -1 }
      if (!Object.prototype.hasOwnProperty.call(wrapped, 'value')) throw new Error(`财务设置内容无效：${key}`)
      if (!Number.isFinite(Number(wrapped._version))) throw new Error(`财务设置版本无效：${key}`)
      const normalized = normalizeFinanceSettingValue(key, wrapped.value)
      const expectsArray = key === 'country-classification' || key === 'channel-policies' || key === 'customer-grades'
      if (expectsArray ? !Array.isArray(normalized) : !isRecord(normalized)) throw new Error(`财务设置内容无效：${key}`)
      nextCache.set(key, normalized)
      nextVersions.set(key, Number(wrapped._version))
    })

    if (generation !== hydrationGeneration) return
    cache.clear()
    versions.clear()
    nextCache.forEach((value, key) => cache.set(key, value))
    nextVersions.forEach((value, key) => versions.set(key, value))
    hydrated = true
  })()

  hydrationRequest = request
  return request.finally(() => {
    if (hydrationRequest === request) hydrationRequest = null
  })
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

if (typeof window !== 'undefined') window.addEventListener('quotation:session-expired', clearFinanceSettingsCache)
