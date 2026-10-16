// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { authState } from './authStore'
import { clearPurchaseRankingsCache, getPurchaseRankingSnapshot as snapshot, loadPurchaseRankingSnapshot as load } from './purchaseRankingsCache'
import type { PurchaseRankings } from '@/services/purchaseRankings'

const request = vi.hoisted(() => vi.fn())
vi.mock('@/services/purchaseRankings', async original => ({ ...await original<object>(), loadPurchaseRankingPart: request }))
const result: PurchaseRankings = { scope: 'all', source: 'all', days: 0, from: '', to: '2026-10-16T00:00:00Z', salesPeriod: '', salesConfigured: false, unmatchedSalesSkus: 0, unclassified: 0, undated: 0, completion: [], exceptions: [] }
function deferred() { let resolve!: (value: PurchaseRankings) => void; let reject!: (reason: unknown) => void; const promise = new Promise<PurchaseRankings>((yes, no) => { resolve = yes; reject = no }); return { promise, resolve, reject } }
function account(account = 'A') { authState.current = { id: account, account, name: account, role: 'super_admin', status: 'enabled', mustChangePassword: false, passwordUpdatedAt: '' }; authState.permissions = ['purchase', 'allRecords'] }
beforeEach(() => { vi.useFakeTimers(); account(); request.mockResolvedValue(result) })
afterEach(() => { authState.current = null; authState.permissions = []; clearPurchaseRankingsCache(); vi.resetAllMocks(); vi.useRealTimers() })

it('coalesces concurrent reads and shares completion across exception windows', async () => {
  const pending = deferred(); request.mockReturnValue(pending.promise)
  const first = load('completion', 'all', 30); const second = load('completion', 'all', 7)
  expect(first).toBe(second); expect(request).toHaveBeenCalledTimes(1)
  pending.resolve(result); await first
  expect(snapshot('completion', 'all', 7)).toBe(snapshot('completion', 'all', 30))
  await load('completion', 'all', 90); expect(request).toHaveBeenCalledTimes(1)
})

it('a forced refresh supersedes an ordinary read and ignores its late result', async () => {
  const old = deferred(); const fresh = deferred(); request.mockReturnValueOnce(old.promise).mockReturnValueOnce(fresh.promise)
  const first = load('completion', 'all', 30)
  const oldSignal = request.mock.calls[0]![4] as AbortSignal
  const forced = load('completion', 'all', 30, true)
  expect(load('completion', 'all', 30, true)).toBe(forced); expect(oldSignal.aborted).toBe(true)
  fresh.resolve({ ...result, to: '2026-10-16T00:01:00Z' }); await forced
  old.resolve(result); await first
  expect(snapshot('completion', 'all', 30).data?.to).toBe('2026-10-16T00:01:00Z')
  expect(snapshot('completion', 'all', 30).loading).toBe(false); expect(request).toHaveBeenCalledTimes(2)
})

it('expires cross-page data and retries failed reads instead of caching failures as success', async () => {
  await load('completion', 'all', 30); await vi.advanceTimersByTimeAsync(301000)
  const pending = deferred(); request.mockReturnValueOnce(pending.promise)
  const refresh = load('completion', 'all', 30)
  expect(snapshot('completion', 'all', 30).data).toBeNull()
  pending.reject(new Error('temporary')); await refresh
  expect(snapshot('completion', 'all', 30).error).toContain('temporary')
  await load('completion', 'all', 30); expect(request).toHaveBeenCalledTimes(3); expect(snapshot('completion', 'all', 30).error).toBe('')
})

it('immediately drops old account data, aborts outstanding reads, and rejects late responses', async () => {
  await load('completion', 'all', 30)
  const oldSnapshot = snapshot('completion', 'all', 30)
  const pending = deferred(); request.mockReturnValueOnce(pending.promise)
  const refresh = load('completion', 'all', 30, true)
  const signal = request.mock.calls[1]![4] as AbortSignal
  account('B'); expect(signal.aborted).toBe(true); expect(oldSnapshot.data).toBeNull()
  await load('completion', 'all', 30)
  pending.resolve({ ...result, to: 'OLD_ACCOUNT' }); await refresh
  expect(snapshot('completion', 'all', 30).data?.to).toBe(result.to)
  account('A'); expect(snapshot('completion', 'all', 30).data).toBeNull()
})

it('invalidates for role, permission and logout changes even if the account is unchanged', async () => {
  await load('completion', 'all', 30)
  authState.current!.role = 'purchase'; expect(snapshot('completion', 'all', 30).data).toBeNull()
  await load('completion', 'all', 30)
  authState.permissions = ['purchase']; expect(snapshot('completion', 'all', 30).data).toBeNull()
  await load('completion', 'all', 30)
  authState.current = null; expect(snapshot('completion', 'all', 30).data).toBeNull()
  await load('completion', 'all', 30); expect(request).toHaveBeenCalledTimes(3)
})

it.each(['quotation:session-expired', 'quotation:account-changed'])('suspends after %s until the authenticated session changes', async event => {
  await load('completion', 'all', 30)
  window.dispatchEvent(new CustomEvent(event))
  expect(snapshot('completion', 'all', 30).data).toBeNull()
  await load('completion', 'all', 30); expect(request).toHaveBeenCalledTimes(1)
  account(); await load('completion', 'all', 30); expect(request).toHaveBeenCalledTimes(2)
})

it('rejects results with a different scope or exception period', async () => {
  request.mockResolvedValueOnce({ ...result, scope: 'sales' }).mockResolvedValueOnce({ ...result, days: 7 })
  await load('completion', 'all', 30); await load('exceptions', 'all', 30)
  expect(snapshot('completion', 'all', 30).data).toBeNull(); expect(snapshot('exceptions', 'all', 30).data).toBeNull()
  expect(snapshot('exceptions', 'all', 30).error).toContain('范围不一致')
})

it('bounds filter memory and evicts older entries', async () => {
  request.mockImplementation(async (_part, _scope, days: number) => ({ ...result, days }))
  for (let days = 1; days <= 20; days++) await load('exceptions', 'all', days)
  expect(snapshot('exceptions', 'all', 20).data).not.toBeNull()
  expect(snapshot('exceptions', 'all', 1).data).toBeNull()
})
