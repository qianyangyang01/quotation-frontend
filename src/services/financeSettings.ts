import { api } from '@/services/http'
import { ref } from 'vue'

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
const hydrated = ref(false)
const hydrating = ref(false)
const hydrationError = ref('')

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
  return hydrated.value
}

export function financeSettingsAreLoading() { return hydrating.value }
export function financeSettingsLoadError() { return hydrationError.value }

export function financeSettingVersions(): FinanceSettingVersions {
  return Object.fromEntries(versions)
}

export function changedFinanceSettings(applied: FinanceSettingVersions, latest: FinanceSettingVersions): string[] {
  return financeSettingKeys.filter(key => (applied[key] ?? -1) !== (latest[key] ?? -1)).map(key => financeSettingLabels[key])
}

export function clearFinanceSettingsCache() {
  hydrationGeneration += 1
  hydrationRequest = null
  hydrated.value = false
  hydrating.value = false
  hydrationError.value = ''
  cache.clear()
  versions.clear()
}

export function hydrateFinanceSettings(options: { force?: boolean; signal?: AbortSignal } = {}) {
  if (options.signal?.aborted) return Promise.reject(options.signal.reason)
  if (hydrated.value && !options.force) return Promise.resolve()
  if (hydrationRequest) return waitForFinance(hydrationRequest, options.signal)
  if (options.force) hydrated.value = false
  hydrating.value = true
  hydrationError.value = ''

  const generation = hydrationGeneration
  // The read is shared, but cancelling a SKU query must only cancel that caller.
  // Bound the shared request so an interrupted connection cannot lock all retries.
  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(new Error('财务设置读取超时，请重试读取')), 15000)
  const request = (async () => {
    const values = await api.get<Partial<Record<FinanceSettingKey, VersionedSetting<unknown>>>>('/finance-settings', { signal: controller.signal, cache: 'no-store' })
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
    // A save can complete while this read is in flight. Versions are monotonic
    // within a session; an older response must not undo a confirmed setting.
    financeSettingKeys.forEach(key => {
      if ((versions.get(key) ?? -1) > (nextVersions.get(key) ?? -1)) {
        nextCache.set(key, cache.get(key))
        nextVersions.set(key, versions.get(key)!)
      }
    })
    cache.clear()
    versions.clear()
    nextCache.forEach((value, key) => cache.set(key, value))
    nextVersions.forEach((value, key) => versions.set(key, value))
    hydrated.value = true
  })().catch(error => {
    if (generation === hydrationGeneration) {
      hydrated.value = false
      hydrationError.value = error instanceof Error ? error.message : '财务设置读取失败'
    }
    throw error
  }).finally(() => {
    clearTimeout(timeout)
    if (hydrationRequest === request) {
      hydrationRequest = null
      hydrating.value = false
    }
  })
  hydrationRequest = request
  return waitForFinance(request, options.signal)
}

function waitForFinance(request: Promise<void>, signal?: AbortSignal): Promise<void> {
  if (!signal) return request
  return new Promise((resolve, reject) => {
    const cancel = () => reject(signal.reason)
    signal.addEventListener('abort', cancel, { once: true })
    request.then(resolve, reject).finally(() => signal.removeEventListener('abort', cancel))
    if (signal.aborted) cancel()
  })
}

export function readFinanceSetting<T>(key: FinanceSettingKey): T | undefined {
  return cache.get(key) as T | undefined
}

export async function writeFinanceSetting<T>(key: FinanceSettingKey, value: T) {
  const generation = hydrationGeneration
  const saved = await api.put<VersionedSetting<T>>(`/finance-settings/${key}`, value, { 'If-Match': String(versions.get(key) ?? -1) })
  if (generation !== hydrationGeneration) return saved.value
  if ((versions.get(key) ?? -1) > saved._version) return cache.get(key) as T
  cache.set(key, saved.value)
  versions.set(key, saved._version)
  return saved.value
}

if (typeof window !== 'undefined') window.addEventListener('quotation:session-expired', clearFinanceSettingsCache)
