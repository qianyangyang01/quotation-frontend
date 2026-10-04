import { ref } from 'vue'
import { ApiError, conditionalGet } from '@/services/http'
import { replaceLogisticsCountryCatalog, replaceLogisticsRules, type LogisticsRule } from './logistics'
import { countryIdentityMatches } from './countryIdentity'

export interface PublishedLogisticsManifest {
  rebuilding?: boolean
  revision: string
  generatedAt: string
  publishedChannels: number
  countries: Array<{ code: string; name: string }>
  attributes: string[]
}

type StoredManifest = { key: 'current'; etag: string; value: PublishedLogisticsManifest }
type StoredRules = { key: string; revision: string; rules: LogisticsRule[]; storedAt: number }
type RuleQuery = { attribute: string; countries: string[]; channelCodes?: string[] }

export function buildQuoteLogisticsCountryQuery(
  settings: Array<{ country: string; enabled: boolean; stage: string }>,
  currentCountry = '',
  selectedCountries: string[] = [],
) {
  const selected = normalized([...selectedCountries, currentCountry])
  // Other countries remain visible in the manifest and load when selected.
  return selected.length ? selected : normalized([settings.find(setting => setting.enabled && setting.stage === 'common')?.country || ''])
}

export const logisticsRebuilding = ref(false)
const DB_NAME = 'milano-quotation-cache'
const DB_VERSION = 3
const CACHE_SCHEMA = 'published-logistics-v6-validated-slices'
const MANIFEST_STORE = 'logisticsManifest'
const RULE_STORE = 'publishedRuleQueries'
const CACHE_EVENT = 'milano:published-logistics-cache'
const INDEXED_DB_TIMEOUT_MS = 1500
let manifestMemory: StoredManifest | null = null
const rulesMemory = new Map<string, StoredRules>()
let manifestRequest: Promise<{ manifest: PublishedLogisticsManifest; changed: boolean; verified: boolean }> | null = null
const ruleRequests = new Map<string, Promise<LogisticsRule[]>>()
let catalogGeneration = 0
let catalogMemory: StoredRules | null = null
const catalogRequests = new Map<string, Promise<StoredRules>>()
let databasePromise: Promise<IDBDatabase | null> | null = null
const cacheChannel = typeof BroadcastChannel !== 'undefined' ? new BroadcastChannel(CACHE_EVENT) : null

function openDatabase() {
  if (databasePromise) return databasePromise
  databasePromise = new Promise(resolve => {
    if (typeof indexedDB === 'undefined') return resolve(null)
    let settled = false
    const finish = (database: IDBDatabase | null) => {
      if (settled) {
        database?.close()
        return
      }
      settled = true
      globalThis.clearTimeout(timeout)
      resolve(database)
    }
    const timeout = globalThis.setTimeout(() => finish(null), INDEXED_DB_TIMEOUT_MS)
    let request: IDBOpenDBRequest
    try { request = indexedDB.open(DB_NAME, DB_VERSION) }
    catch { finish(null); return }
    request.onupgradeneeded = () => {
      const database = request.result
      const rulesExisted = database.objectStoreNames.contains(RULE_STORE)
      if (!database.objectStoreNames.contains(MANIFEST_STORE)) database.createObjectStore(MANIFEST_STORE, { keyPath: 'key' })
      if (!database.objectStoreNames.contains(RULE_STORE)) database.createObjectStore(RULE_STORE, { keyPath: 'key' })
      if (rulesExisted) request.transaction?.objectStore(RULE_STORE).clear()
    }
    request.onsuccess = () => {
      request.result.onversionchange = () => request.result.close()
      finish(request.result)
    }
    request.onerror = () => finish(null)
    request.onblocked = () => finish(null)
  })
  return databasePromise
}

function settleIndexedDb<T>(setup: (finish: (value: T) => void) => void, fallback: T) {
  return new Promise<T>(resolve => {
    let settled = false
    const finish = (value: T) => {
      if (settled) return
      settled = true
      globalThis.clearTimeout(timeout)
      resolve(value)
    }
    const timeout = globalThis.setTimeout(() => finish(fallback), INDEXED_DB_TIMEOUT_MS)
    try { setup(finish) }
    catch { finish(fallback) }
  })
}

async function readStore<T>(storeName: string, key: IDBValidKey): Promise<T | null> {
  const database = await openDatabase()
  if (!database) return null
  return settleIndexedDb<T | null>(resolve => {
    const request = database.transaction(storeName, 'readonly').objectStore(storeName).get(key)
    request.onsuccess = () => resolve((request.result as T | undefined) || null)
    request.onerror = () => resolve(null)
  }, null)
}

async function writeStore(storeName: string, value: unknown) {
  const database = await openDatabase()
  if (!database) return
  await settleIndexedDb<void>(resolve => {
    const request = database.transaction(storeName, 'readwrite').objectStore(storeName).put(value)
    request.onsuccess = () => resolve()
    request.onerror = () => resolve()
  }, undefined)
}

async function clearStore(storeName: string) {
  const database = await openDatabase()
  if (!database) return
  await settleIndexedDb<void>(resolve => {
    const request = database.transaction(storeName, 'readwrite').objectStore(storeName).clear()
    request.onsuccess = () => resolve()
    request.onerror = () => resolve()
  }, undefined)
}

function normalized(values: string[] | undefined) {
  return [...new Set((values || []).map(value => value.trim()).filter(Boolean))].sort((a, b) => a.localeCompare(b, 'zh-CN'))
}

function queryKey(revision: string, query: RuleQuery) {
  return [CACHE_SCHEMA, revision, query.attribute.trim() || '普货', normalized(query.countries).join(','), normalized(query.channelCodes).join(',')].join('|')
}

function countrySlice(rules: LogisticsRule[], country: string) {
  return rules.flatMap(rule => {
    const prices = rule.prices.filter(row => countryIdentityMatches(row.countryCode, row.areaName, country))
    return prices.length ? [{ ...rule, prices, priceRowCount: prices.length,
      areaCount: new Set(prices.map(row => row.countryCode || row.areaName)).size,
      phoneRequired: prices.some(row => row.phoneRequired) }] : []
  })
}

function joinCountrySlices(slices: LogisticsRule[][]) {
  const rules = new Map<number, LogisticsRule>()
  for (const slice of slices) for (const rule of slice) {
    const previous = rules.get(rule.id)
    if (previous && (previous.logisticsVersionId !== rule.logisticsVersionId || previous.logisticsChannelId !== rule.logisticsChannelId)) {
      throw new Error('物流渠道版本已变化，请重新加载')
    }
    const prices = [...(previous?.prices || []), ...rule.prices]
    rules.set(rule.id, { ...rule, prices, priceRowCount: prices.length,
      areaCount: new Set(prices.map(row => row.countryCode || row.areaName)).size,
      phoneRequired: prices.some(row => row.phoneRequired) })
  }
  return [...rules.values()].sort((a, b) => a.id - b.id)
}

async function cachedManifest() {
  if (manifestMemory) return manifestMemory
  manifestMemory = await readStore<StoredManifest>(MANIFEST_STORE, 'current')
  return manifestMemory
}

async function purgeRuleCache() {
  catalogGeneration += 1
  catalogMemory = null
  catalogRequests.clear()
  rulesMemory.clear()
  ruleRequests.clear()
  // Evict cached queries, but keep the displayed snapshot until a new one is ready.
  await clearStore(RULE_STORE)
}

function applyManifest(manifest: PublishedLogisticsManifest) {
  logisticsRebuilding.value = manifest.rebuilding === true
  replaceLogisticsCountryCatalog(manifest.countries || [])
  return manifest
}

export async function loadPublishedLogisticsManifest(options: { signal?: AbortSignal; allowStale?: boolean } = {}) {
  const allowStale = options.allowStale !== false
  if (manifestRequest && !options.signal && allowStale) return manifestRequest
  const request = (async () => {
    const cached = await cachedManifest()
    try {
      const response = await conditionalGet<PublishedLogisticsManifest>('/logistics/published/manifest', { etag: cached?.etag, signal: options.signal })
      if (response.status === 304 && cached) return { manifest: applyManifest(cached.value), changed: false, verified: true }
      if (response.status === 304) throw new Error('物流版本缓存不存在，请重新加载')
      const changed = Boolean(cached?.value.revision && cached.value.revision !== response.data.revision)
      if (changed) await purgeRuleCache()
      manifestMemory = { key: 'current', etag: response.etag, value: response.data }
      await writeStore(MANIFEST_STORE, manifestMemory)
      if (changed) cacheChannel?.postMessage({ type: 'revision', revision: response.data.revision })
      return { manifest: applyManifest(response.data), changed, verified: true }
    } catch (error) {
      if (error instanceof ApiError && (error.status === 401 || error.status === 403)) throw error
      if (allowStale && cached && !(error instanceof DOMException && error.name === 'AbortError')) return { manifest: applyManifest(cached.value), changed: false, verified: false }
      throw error
    }
  })()
  if (!options.signal && allowStale) manifestRequest = request
  try { return await request }
  finally { if (!options.signal && allowStale) manifestRequest = null }
}

export async function loadPublishedLogisticsRules(query: RuleQuery, options: {
  signal?: AbortSignal
  apply?: boolean
  manifestResult?: { manifest: PublishedLogisticsManifest; verified: boolean }
} = {}) {
  const { manifest, verified } = options.manifestResult || await loadPublishedLogisticsManifest({ signal: options.signal })
  const countries = normalized(query.countries.map(country => manifest.countries.find(item =>
    countryIdentityMatches(item.code, item.name, country))?.name || country))
  const generation = catalogGeneration
  const checkCurrent = () => {
    options.signal?.throwIfAborted()
    if (generation !== catalogGeneration) throw new Error('物流规则已失效，请重新加载')
  }
  options.signal?.throwIfAborted()
  if (!countries.length) {
    if (options.apply !== false) replaceLogisticsRules([])
    return { revision: manifest.revision, rules: [], source: 'manifest' as const, verified }
  }
  const key = queryKey(manifest.revision, { ...query, countries })
  let cached = rulesMemory.get(key)
  if (!cached) {
    cached = await readStore<StoredRules>(RULE_STORE, key) || undefined
    checkCurrent()
    if (cached) rulesMemory.set(key, cached)
  }
  checkCurrent()
  if (cached?.revision === manifest.revision) {
    if (options.apply !== false) replaceLogisticsRules(cached.rules)
    return { revision: manifest.revision, rules: cached.rules, source: 'cache' as const, verified }
  }
  const existing = options.signal ? undefined : ruleRequests.get(key)
  if (existing) {
    const rules = await existing
    checkCurrent()
    if (options.apply !== false) replaceLogisticsRules(rules)
    return { revision: manifest.revision, rules, source: 'network' as const, verified }
  }
  const slices = new Map<string, StoredRules>()
  if (countries.length > 1) await Promise.all(countries.map(async country => {
    const countryKey = queryKey(manifest.revision, { ...query, countries: [country] })
    const value = rulesMemory.get(countryKey) || await readStore<StoredRules>(RULE_STORE, countryKey)
    if (value?.revision === manifest.revision) slices.set(country, value)
  }))
  checkCurrent()
  // Another caller may have started the same request while these slices were
  // read from IndexedDB. Recheck before issuing a second network request.
  const shared = options.signal ? undefined : ruleRequests.get(key)
  if (shared) {
    const rules = await shared
    checkCurrent()
    if (options.apply !== false) replaceLogisticsRules(rules)
    return { revision: manifest.revision, rules, source: 'network' as const, verified }
  }
  const missing = countries.filter(country => !slices.has(country))
  if (!missing.length) {
    const rules = joinCountrySlices(countries.map(country => slices.get(country)!.rules))
    if (options.apply !== false) replaceLogisticsRules(rules)
    return { revision: manifest.revision, rules, source: 'cache' as const, verified }
  }
  const parameters = new URLSearchParams({ revision: manifest.revision, attribute: query.attribute.trim() || '普货' })
  missing.forEach(country => parameters.append('country', country))
  normalized(query.channelCodes).forEach(channel => parameters.append('channelCode', channel))
  const request = conditionalGet<{ revision: string; rules: LogisticsRule[] }>(`/logistics/published/rules?${parameters}`, { signal: options.signal })
    .then(response => {
      checkCurrent()
      if (response.status === 304) throw new Error('物流规则缓存不存在，请重新加载')
      if (response.data.revision !== manifest.revision) throw new Error('物流版本已变化，请重新加载')
      for (const country of missing) {
        const countryKey = queryKey(manifest.revision, { ...query, countries: [country] })
        const slice: StoredRules = { key: countryKey, revision: manifest.revision,
          rules: countrySlice(response.data.rules, country), storedAt: Date.now() }
        slices.set(country, slice)
      }
      const rules = missing.length === countries.length ? response.data.rules
        : joinCountrySlices(countries.map(country => slices.get(country)!.rules))
      // Validate the complete result before persisting any new country. A failed
      // merge must not poison subsequent retries with incompatible cached rows.
      for (const country of missing) {
        const slice = slices.get(country)!
        rulesMemory.set(slice.key, slice)
        void writeStore(RULE_STORE, slice)
      }
      const value: StoredRules = { key, revision: response.data.revision, rules, storedAt: Date.now() }
      rulesMemory.set(key, value)
      void writeStore(RULE_STORE, value)
      if (options.apply !== false) replaceLogisticsRules(value.rules)
      return value.rules
    })
    .finally(() => { if (ruleRequests.get(key) === request) ruleRequests.delete(key) })
  if (!options.signal) ruleRequests.set(key, request)
  return { revision: manifest.revision, rules: await request, source: 'network' as const, verified }
}

export async function loadPublishedLogisticsRuleCatalog(attributes: string[], countries: string[], options: { signal?: AbortSignal; manifest?: PublishedLogisticsManifest; apply?: boolean } = {}) {
  void attributes
  const { manifest, verified } = options.manifest
    ? { manifest: options.manifest, verified: true }
    : await loadPublishedLogisticsManifest({ signal: options.signal, allowStale: false })
  options.signal?.throwIfAborted()
  if (!normalized(countries).length) {
    if (options.apply !== false) replaceLogisticsRules([])
    return { revision: manifest.revision, rules: [] as LogisticsRule[], verified }
  }
  const generation = catalogGeneration
  try {
    let cached = catalogMemory?.revision === manifest.revision ? catalogMemory : null
    if (!cached) {
      let request = options.signal ? undefined : catalogRequests.get(manifest.revision)
      if (!request) {
        const parameters = new URLSearchParams({ revision: manifest.revision })
        request = conditionalGet<{ revision: string; rules: LogisticsRule[] }>(`/logistics/published/catalog?${parameters}`, { signal: options.signal })
          .then(response => {
            if (response.status === 304 || response.data.revision !== manifest.revision) throw new Error('物流目录版本已变化，请重新加载')
            const value: StoredRules = { key: 'catalog', revision: response.data.revision, rules: response.data.rules, storedAt: Date.now() }
            if (generation === catalogGeneration) catalogMemory = value
            return value
          })
        if (!options.signal) catalogRequests.set(manifest.revision, request)
      }
      try { cached = await request }
      finally { if (catalogRequests.get(manifest.revision) === request) catalogRequests.delete(manifest.revision) }
    }
    options.signal?.throwIfAborted()
    if (generation !== catalogGeneration) throw new Error('物流目录已失效，请重新加载')
    if (options.apply !== false) replaceLogisticsRules(cached.rules)
    return { revision: cached.revision, rules: cached.rules, verified }
  } catch (error) {
    if (generation === catalogGeneration && options.apply !== false) replaceLogisticsRules([])
    throw error
  }
}

export async function validatePublishedLogisticsRevision() {
  const previous = (await cachedManifest())?.value.revision || ''
  const result = await loadPublishedLogisticsManifest({ allowStale: false })
  return { ...result, changed: Boolean(previous && previous !== result.manifest.revision) }
}

export async function invalidatePublishedLogisticsCache(broadcast = true) {
  manifestMemory = null
  replaceLogisticsRules([])
  replaceLogisticsCountryCatalog([])
  await Promise.all([clearStore(MANIFEST_STORE), purgeRuleCache()])
  if (broadcast) cacheChannel?.postMessage({ type: 'invalidate' })
}

export async function clearPublishedLogisticsCache() {
  await invalidatePublishedLogisticsCache(true)
}

cacheChannel?.addEventListener('message', event => {
  if (event.data?.type !== 'revision' && event.data?.type !== 'invalidate') return
  manifestMemory = null
  void purgeRuleCache()
})

if (typeof window !== 'undefined') window.addEventListener('quotation:session-expired', () => { void clearPublishedLogisticsCache() })
