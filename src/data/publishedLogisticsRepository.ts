import { ApiError, conditionalGet } from '@/services/http'
import { replaceLogisticsCountryCatalog, replaceLogisticsRules, type LogisticsPriceRow, type LogisticsRule } from './logistics'

export interface PublishedLogisticsManifest {
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
  return normalized([
    ...selectedCountries,
    ...settings.filter(setting => setting.enabled && setting.stage === 'common').map(setting => setting.country),
    currentCountry,
  ])
}

const DB_NAME = 'milano-quotation-cache'
const DB_VERSION = 1
const CACHE_SCHEMA = 'published-logistics-v1'
const MANIFEST_STORE = 'logisticsManifest'
const RULE_STORE = 'publishedRuleQueries'
const CACHE_EVENT = 'milano:published-logistics-cache'
const INDEXED_DB_TIMEOUT_MS = 1500
const MAX_RULE_QUERY_COUNTRIES = 100
let manifestMemory: StoredManifest | null = null
const rulesMemory = new Map<string, StoredRules>()
let manifestRequest: Promise<{ manifest: PublishedLogisticsManifest; changed: boolean; verified: boolean }> | null = null
const ruleRequests = new Map<string, Promise<LogisticsRule[]>>()
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
      if (!database.objectStoreNames.contains(MANIFEST_STORE)) database.createObjectStore(MANIFEST_STORE, { keyPath: 'key' })
      if (!database.objectStoreNames.contains(RULE_STORE)) database.createObjectStore(RULE_STORE, { keyPath: 'key' })
    }
    request.onsuccess = () => finish(request.result)
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

async function cachedManifest() {
  if (manifestMemory) return manifestMemory
  manifestMemory = await readStore<StoredManifest>(MANIFEST_STORE, 'current')
  return manifestMemory
}

async function purgeRuleCache() {
  rulesMemory.clear()
  ruleRequests.clear()
  replaceLogisticsRules([])
  await clearStore(RULE_STORE)
}

function applyManifest(manifest: PublishedLogisticsManifest) {
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

export async function loadPublishedLogisticsRules(query: RuleQuery, options: { signal?: AbortSignal } = {}) {
  const countries = normalized(query.countries)
  const { manifest, verified } = await loadPublishedLogisticsManifest({ signal: options.signal })
  if (!countries.length) {
    replaceLogisticsRules([])
    return { revision: manifest.revision, rules: [], source: 'manifest' as const, verified }
  }
  const key = queryKey(manifest.revision, query)
  let cached = rulesMemory.get(key)
  if (!cached) {
    cached = await readStore<StoredRules>(RULE_STORE, key) || undefined
    if (cached) rulesMemory.set(key, cached)
  }
  if (cached?.revision === manifest.revision) {
    replaceLogisticsRules(cached.rules)
    return { revision: manifest.revision, rules: cached.rules, source: 'cache' as const, verified }
  }
  const existing = ruleRequests.get(key)
  if (existing) return { revision: manifest.revision, rules: await existing, source: 'network' as const, verified }
  const parameters = new URLSearchParams({ revision: manifest.revision, attribute: query.attribute.trim() || '普货' })
  countries.forEach(country => parameters.append('country', country))
  normalized(query.channelCodes).forEach(channel => parameters.append('channelCode', channel))
  const request = conditionalGet<{ revision: string; rules: LogisticsRule[] }>(`/logistics/published/rules?${parameters}`, { signal: options.signal })
    .then(response => {
      if (response.status === 304) throw new Error('物流规则缓存不存在，请重新加载')
      const value: StoredRules = { key, revision: response.data.revision, rules: response.data.rules, storedAt: Date.now() }
      rulesMemory.set(key, value)
      void writeStore(RULE_STORE, value)
      replaceLogisticsRules(value.rules)
      return value.rules
    })
    .finally(() => ruleRequests.delete(key))
  ruleRequests.set(key, request)
  return { revision: manifest.revision, rules: await request, source: 'network' as const, verified }
}

export async function loadPublishedLogisticsRuleCatalog(attributes: string[], countries: string[], options: { signal?: AbortSignal } = {}) {
  const normalizedAttributes = normalized(attributes).length ? normalized(attributes) : ['普货']
  const normalizedCountries = normalized(countries)
  if (!normalizedCountries.length) {
    replaceLogisticsRules([])
    return { revision: (await loadPublishedLogisticsManifest({ signal: options.signal })).manifest.revision, rules: [] as LogisticsRule[], verified: true }
  }
  const countryBatches: string[][] = []
  for (let index = 0; index < normalizedCountries.length; index += MAX_RULE_QUERY_COUNTRIES) {
    countryBatches.push(normalizedCountries.slice(index, index + MAX_RULE_QUERY_COUNTRIES))
  }
  try {
    const results = await Promise.all(normalizedAttributes.flatMap(attribute => countryBatches.map(batch =>
      loadPublishedLogisticsRules({ attribute, countries: batch }, options))))
    const rules = new Map<number, LogisticsRule>()
    const priceKeys = new Map<number, Set<string>>()
    results.flatMap(result => result.rules).forEach(rule => {
      if (!rules.has(rule.id)) {
        rules.set(rule.id, { ...rule, relations: rule.relations.map(relation => ({ ...relation })), prices: [] })
        priceKeys.set(rule.id, new Set())
      }
      const merged = rules.get(rule.id)!
      const seen = priceKeys.get(rule.id)!
      rule.prices.forEach((price: LogisticsPriceRow) => {
        const key = JSON.stringify(price)
        if (!seen.has(key)) { seen.add(key); merged.prices.push(price) }
      })
    })
    const merged = [...rules.values()].map(rule => ({
      ...rule,
      areaCount: new Set(rule.prices.map(price => price.countryCode || price.areaName)).size,
      priceRowCount: rule.prices.length,
    }))
    replaceLogisticsRules(merged)
    return { revision: results[0]?.revision || '', rules: merged, verified: results.every(result => result.verified) }
  } catch (error) {
    replaceLogisticsRules([])
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
  replaceLogisticsRules([])
  void purgeRuleCache()
})

if (typeof window !== 'undefined') window.addEventListener('quotation:session-expired', () => { void clearPublishedLogisticsCache() })
