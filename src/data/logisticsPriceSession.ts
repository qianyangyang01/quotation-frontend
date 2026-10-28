import { reactive, ref, shallowRef } from 'vue'
import { request } from '@/services/http'
import { assertPriceSnapshot } from './logisticsPriceSnapshotValidation'
import { publicationDate, type RealPriceSnapshot } from './logisticsRealPriceHistory'
import { subscribeLogisticsPriceChanges, type LogisticsPriceEvent } from './logisticsPriceEvents'

export type PriceHistorySource = 'preview' | 'system'
export interface PriceHistoryFilters {
  country: string; attribute: string; weight: number; comparison: string; search: string; page: number
}
type ReadMode = 'cached' | 'sync' | 'manual'
const sessions = new Map<PriceHistorySource, ReturnType<typeof createSession>>()

/** Memory only, scoped to the current login. Navigation never starts a second read. */
export function logisticsPriceSession(source: PriceHistorySource) {
  let session = sessions.get(source)
  if (!session) { session = createSession(source); sessions.set(source, session) }
  return session
}
export function clearLogisticsPriceSessions() {
  for (const session of sessions.values()) {
    try { session.dispose() } catch { /* Optional display cleanup must not block login/logout. */ }
  }
  sessions.clear()
}
function createSession(source: PriceHistorySource) {
  const snapshot = shallowRef<RealPriceSnapshot | null>(null)
  const refreshing = ref(false), error = ref(''), checkedAt = ref('')
  const filters = reactive<PriceHistoryFilters>({ country: '德国', attribute: '普货/服装', weight: 1, comparison: 'currentPrevious', search: '', page: 1 })
  let started = false, attempted = false, disposed = false
  let timer: ReturnType<typeof setTimeout> | undefined
  let controller: AbortController | undefined
  let unsubscribe: (() => void) | undefined
  let pendingEvent: LogisticsPriceEvent | undefined
  let running: Promise<void> | undefined
  function scheduleEventRefresh() {
    if (disposed || !attempted || refreshing.value || !pendingEvent) return
    clearTimeout(timer)
    timer = setTimeout(() => { void load('sync') }, 250)
  }
  async function read(mode: ReadMode, signal: AbortSignal): Promise<{ data?: RealPriceSnapshot; checkedAt?: string }> {
    if (source === 'system') {
      const data = await request<RealPriceSnapshot>('/logistics/price-history', { signal })
      if (!data?.sourceRevision) throw new Error('价格展示数据有误，请稍后手动刷新')
      return { data, checkedAt: data.capturedAt }
    }
    const suffix = mode === 'cached' ? '' : (mode === 'manual' ? '/refresh' : '/sync') + '?capturedAt=' + encodeURIComponent(snapshot.value?.capturedAt || '')
    const response = await fetch('/__local-logistics/history' + suffix, {
      method: mode === 'cached' ? 'GET' : 'POST', headers: { 'X-Local-Logistics': 'read-only' }, cache: 'no-store', signal,
    })
    if (!response.ok) throw new Error(`价格展示读取失败（${response.status}），请稍后手动刷新`)
    if (response.status === 204) return { checkedAt: response.headers.get('X-Logistics-Checked-At') || undefined }
    const data = await response.json()
    return { data, checkedAt: mode !== 'cached' ? response.headers.get('X-Logistics-Checked-At') || data?.capturedAt : undefined }
  }
  function load(mode: ReadMode = 'cached'): Promise<void> {
    if (disposed) return Promise.resolve()
    if (refreshing.value) return running || Promise.resolve()
    clearTimeout(timer)
    // Consume events already known at read start. New events during the read
    // schedule one follow-up, so a concurrent publication cannot be lost.
    pendingEvent = undefined
    attempted = true
    refreshing.value = true
    error.value = ''
    controller = new AbortController()
    const active = controller
    running = (async () => {
      const timeout = setTimeout(() => active.abort(new Error('价格展示读取超时，请稍后手动刷新')), source === 'system' ? 8000 : 30000)
      const aborted = new Promise<never>((_, reject) => active.signal.addEventListener('abort', () => reject(active.signal.reason), { once: true }))
      try {
        const result = await Promise.race([read(mode, active.signal), aborted])
        if (disposed || active.signal.aborted) return
        if (result.data) {
          assertPriceSnapshot(result.data)
          if (!snapshot.value || snapshot.value.sourceRevision !== result.data.sourceRevision || publicationDate(snapshot.value.capturedAt) !== publicationDate(result.data.capturedAt) || (source === 'preview' && snapshot.value.capturedAt !== result.data.capturedAt)) snapshot.value = result.data
        }
        if (result.checkedAt) checkedAt.value = result.checkedAt
      } catch (e) {
        if (!disposed) error.value = e instanceof Error ? e.message : '价格展示读取失败，请稍后手动刷新'
      } finally {
        clearTimeout(timeout)
        refreshing.value = false
        running = undefined
        scheduleEventRefresh()
      }
    })()
    return running
  }
  function ensureLoaded() {
    if (disposed) return Promise.resolve()
    if (!started) {
      started = true
      unsubscribe = subscribeLogisticsPriceChanges(event => { pendingEvent = event; scheduleEventRefresh() })
      window.addEventListener('quotation:session-expired', clearLogisticsPriceSessions)
      window.addEventListener('quotation:account-changed', clearLogisticsPriceSessions)
    }
    return attempted ? running || Promise.resolve() : load()
  }
  function dispose() {
    disposed = true
    snapshot.value = null
    checkedAt.value = ''
    error.value = ''
    clearTimeout(timer)
    controller?.abort()
    window.removeEventListener('quotation:session-expired', clearLogisticsPriceSessions)
    window.removeEventListener('quotation:account-changed', clearLogisticsPriceSessions)
    unsubscribe?.()
  }
  return { snapshot, refreshing, error, checkedAt, filters, ensureLoaded, refresh: () => load('manual'), dispose }
}
