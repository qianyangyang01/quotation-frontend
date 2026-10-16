import { computed, reactive, ref, shallowReactive, watch } from 'vue'
import { authState } from './authStore'
import { loadPurchaseRankingPart, type PurchaseRankings, type RankingPart, type RankingScope } from '@/services/purchaseRankings'
import { ApiError } from '@/services/http'

export type RankingSnapshot = { data: PurchaseRankings | null; loading: boolean; error: string; receivedAt: number }
const FRESH_MS = 60_000
const STALE_MS = 5 * 60_000
const MAX_ENTRIES = 12
const snapshots = shallowReactive(new Map<string, RankingSnapshot>())
type Flight = { controller: AbortController; force: boolean; promise: Promise<void> }
const flights = new Map<string, Flight>()
const suspended = ref(false)
const generation = ref(0)
const identity = computed(() => {
  const user = authState.current
  return user?.status === 'enabled' && authState.permissions.includes('purchase')
    ? JSON.stringify([user.id, user.account.toUpperCase(), user.role, [...authState.permissions].sort()]) : ''
})
export const purchaseRankingsAccess = computed(() => suspended.value || !identity.value ? '' : `${generation.value}:${identity.value}`)
const unavailable = reactive<RankingSnapshot>({ data: null, loading: false, error: '登录或权限已变化，请重新进入采购资料。', receivedAt: 0 })

export function clearPurchaseRankingsCache() {
  generation.value++
  flights.forEach(flight => flight.controller.abort())
  flights.clear()
  // Clear objects as well as the map, so existing views cannot keep displaying old accounts.
  snapshots.forEach(snapshot => { snapshot.data = null; snapshot.loading = false; snapshot.error = ''; snapshot.receivedAt = 0 })
  snapshots.clear()
}
watch([identity, () => authState.current], () => { clearPurchaseRankingsCache(); suspended.value = false }, { flush: 'sync' })
function suspend() { suspended.value = true; clearPurchaseRankingsCache() }
if (typeof window !== 'undefined') {
  window.addEventListener('quotation:session-expired', suspend)
  window.addEventListener('quotation:account-changed', suspend)
}
const keyFor = (part: RankingPart, scope: RankingScope, days: number) => `${part}:${scope}:${part === 'completion' ? 0 : days}`

export function getPurchaseRankingSnapshot(part: RankingPart, scope: RankingScope, days: number) {
  if (!purchaseRankingsAccess.value) return unavailable
  const key = keyFor(part, scope, days)
  let snapshot = snapshots.get(key)
  if (!snapshot) {
    // There are ten supported filter combinations; cap memory even if future callers add more.
    while (snapshots.size >= MAX_ENTRIES) {
      const oldest = snapshots.keys().next().value!
      flights.get(oldest)?.controller.abort(); flights.delete(oldest); snapshots.delete(oldest)
    }
    snapshot = reactive<RankingSnapshot>({ data: null, loading: false, error: '', receivedAt: 0 })
    snapshots.set(key, snapshot)
  }
  return snapshot
}

export function loadPurchaseRankingSnapshot(part: RankingPart, scope: RankingScope, days: number, force = false): Promise<void> {
  const access = purchaseRankingsAccess.value
  if (!access) return Promise.resolve()
  const key = keyFor(part, scope, days)
  const snapshot = getPurchaseRankingSnapshot(part, scope, days)
  const pending = flights.get(key)
  if (pending && (!force || pending.force)) return pending.promise
  if (pending) pending.controller.abort()
  const age = Date.now() - snapshot.receivedAt
  if (!force && !snapshot.error && snapshot.data && age < FRESH_MS) return Promise.resolve()
  // A short stale snapshot may be shown while revalidating; never revive an old cross-page result.
  if (!force && age >= STALE_MS) snapshot.data = null
  const requestGeneration = generation.value
  const controller = new AbortController()
  const flight: Flight = { controller, force, promise: Promise.resolve() }
  snapshot.loading = true; snapshot.error = ''
  flights.set(key, flight)
  const current = () => generation.value === requestGeneration && purchaseRankingsAccess.value === access && flights.get(key) === flight && snapshots.get(key) === snapshot
  flight.promise = (async () => {
    try {
      const result = await loadPurchaseRankingPart(part, scope, days, force, controller.signal)
      if (!current() || controller.signal.aborted) return
      if (result.scope !== scope || (part === 'exceptions' && result.days !== days)) throw new Error('统计结果与所选范围不一致，请重试。')
      snapshot.data = result; snapshot.receivedAt = Date.now()
    } catch (reason) {
      if (current() && reason instanceof ApiError && ([401, 403].includes(reason.status) || reason.code === 'ACCOUNT_CHANGED')) { suspend(); return }
      if (current() && !controller.signal.aborted) snapshot.error = (snapshot.data ? '更新失败，仍显示上次结果。' : '') + (reason instanceof Error ? reason.message : '统计读取失败，请重试。')
    } finally {
      if (current()) { snapshot.loading = false; flights.delete(key) }
    }
  })()
  return flight.promise
}
