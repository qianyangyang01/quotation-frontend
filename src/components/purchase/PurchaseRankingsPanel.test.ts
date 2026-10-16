// @vitest-environment happy-dom
import { createApp, nextTick, type App } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { authState } from '@/data/authStore'
import { clearPurchaseRankingsCache } from '@/data/purchaseRankingsCache'
import { ApiError } from '@/services/http'
import type { PurchaseRankings, RankingPart, RankingScope } from '@/services/purchaseRankings'
import Panel from './PurchaseRankingsPanel.vue'

const load = vi.hoisted(() => vi.fn())
vi.mock('@/services/purchaseRankings', async original => ({ ...await original<object>(), loadPurchaseRankingPart: load }))
const apps = new Set<App>()
const result = (part: RankingPart = 'completion', scope: RankingScope = 'all', days = 30, extra: Partial<PurchaseRankings> = {}): PurchaseRankings => ({ scope, source: 'all', days: part === 'completion' ? 0 : days, from: '2026-09-16T00:00:00Z', to: '2026-10-16T00:00:00Z', salesPeriod: '', salesConfigured: true, completion: part === 'completion' ? [{ owner: '甲', total: 10, complete: 8, pending: 2, rate: 80 }] : [], exceptions: part === 'exceptions' ? [{ owner: '甲', count: 1, abnormalQuotes: 1, relatedQuotes: 10, rate: 10 }] : [], unclassified: 0, undated: 0, unmatchedSalesSkus: 0, ...extra })
const flush = async () => { for (let i = 0; i < 4; i++) { await Promise.resolve(); await nextTick() } }
const button = (host: HTMLElement, text: string) => [...host.querySelectorAll('button')].find(b => b.textContent?.includes(text))!
function mount() { const host = document.createElement('div'); document.body.append(host); const app = createApp(Panel); apps.add(app); app.mount(host); return { host, unmount: () => { app.unmount(); apps.delete(app); host.remove() } } }
function deferred() { let resolve!: (value: PurchaseRankings) => void; let reject!: (reason: unknown) => void; const promise = new Promise<PurchaseRankings>((yes, no) => { resolve = yes; reject = no }); return { promise, resolve, reject } }
function account(account = 'A') { authState.current = { id: account, account, name: account, role: 'super_admin', status: 'enabled', mustChangePassword: false, passwordUpdatedAt: '' }; authState.permissions = ['purchase', 'allRecords'] }
const changeDays = async (host: HTMLElement, days: number) => { const select = host.querySelector('select')!; select.value = String(days); select.dispatchEvent(new Event('change')); await flush() }
beforeEach(() => { account(); load.mockImplementation(async (part: RankingPart, scope: RankingScope, days: number) => result(part, scope, days)) })
afterEach(() => { apps.forEach(app => app.unmount()); apps.clear(); document.body.innerHTML = ''; authState.current = null; authState.permissions = []; clearPurchaseRankingsCache(); vi.resetAllMocks(); vi.useRealTimers() })

it('starts only when expanded and renders each part independently with its real timestamp', async () => {
  const left = deferred(); const right = deferred(); load.mockReturnValueOnce(left.promise).mockReturnValueOnce(right.promise)
  const { host } = mount(); await flush(); expect(load).not.toHaveBeenCalled(); expect(host.querySelector('table')).toBeNull()
  button(host, '展开').click(); await flush(); expect(load).toHaveBeenCalledTimes(2)
  left.resolve(result()); await flush()
  expect(host.textContent).toContain('80.0%'); expect(host.querySelectorAll('article')[0]?.getAttribute('aria-busy')).toBe('false')
  expect(host.querySelectorAll('article')[1]?.getAttribute('aria-busy')).toBe('true'); expect(button(host, '导出排名').disabled).toBe(true)
  right.resolve(result('exceptions', 'all', 30, { to: '2026-10-16T00:01:00Z' })); await flush()
  expect(button(host, '导出排名').disabled).toBe(false)
  expect(host.querySelector('.ranking-times')?.textContent).toContain(new Date('2026-10-16T00:00:00Z').toLocaleString('zh-CN'))
  expect(host.querySelector('.ranking-times')?.textContent).toContain(new Date('2026-10-16T00:01:00Z').toLocaleString('zh-CN'))
})

it('changes exception days independently, changes scope for both, and sorts locally', async () => {
  const { host } = mount(); button(host, '展开').click(); await flush()
  await changeDays(host, 7); expect(load).toHaveBeenCalledTimes(3); expect(load.mock.calls[2]?.slice(0, 4)).toEqual(['exceptions', 'all', 7, false])
  button(host, '完整度排名').click(); await flush(); expect(load).toHaveBeenCalledTimes(3)
  button(host, '在售 SKU').click(); await flush(); expect(load).toHaveBeenCalledTimes(5)
  expect(load.mock.calls.slice(3).map(call => call.slice(0, 3))).toEqual([['completion', 'sales', 7], ['exceptions', 'sales', 7]])
  button(host, '全部采购').click(); await flush(); await changeDays(host, 30); expect(load).toHaveBeenCalledTimes(5)
})

it('preserves same-page collapse behavior even when time passes or a read failed', async () => {
  vi.useFakeTimers(); load.mockRejectedValue(new Error('读取失败'))
  const { host } = mount(); button(host, '展开').click(); await flush()
  button(host, '收起').click(); await flush(); await vi.advanceTimersByTimeAsync(180000)
  button(host, '展开').click(); await flush(); expect(load).toHaveBeenCalledTimes(2); expect(host.textContent).toContain('读取失败')
  button(host, '重试资料统计').click(); await flush(); expect(load).toHaveBeenCalledTimes(3)
})

it('reuses fresh cross-page results and revalidates a short stale snapshot on re-entry', async () => {
  vi.useFakeTimers()
  const first = mount(); button(first.host, '展开').click(); await flush(); first.unmount()
  const fresh = mount(); button(fresh.host, '展开').click(); await flush(); expect(load).toHaveBeenCalledTimes(2); fresh.unmount()
  await vi.advanceTimersByTimeAsync(61000)
  const left = deferred(); const right = deferred(); load.mockReturnValueOnce(left.promise).mockReturnValueOnce(right.promise)
  const stale = mount(); button(stale.host, '展开').click(); await flush()
  expect(load).toHaveBeenCalledTimes(4); expect(stale.host.textContent).toContain('80.0%'); expect(stale.host.textContent).toContain('当前显示上次结果')
  left.resolve(result()); right.resolve(result('exceptions')); await flush()
})

it('shares active requests across page instances and does not cancel them on route unmount', async () => {
  const left = deferred(); const right = deferred(); load.mockReturnValueOnce(left.promise).mockReturnValueOnce(right.promise)
  const first = mount(); button(first.host, '展开').click(); await flush()
  const signal = load.mock.calls[0]![4] as AbortSignal; first.unmount()
  const second = mount(); button(second.host, '展开').click(); await flush()
  expect(signal.aborted).toBe(false); expect(load).toHaveBeenCalledTimes(2)
  left.resolve(result()); right.resolve(result('exceptions')); await flush(); expect(second.host.textContent).toContain('80.0%')
})

it('forces both parts on refresh, retains rows on network failure, and retries only the failed part', async () => {
  const { host } = mount(); button(host, '展开').click(); await flush()
  const left = deferred(); const right = deferred(); load.mockReturnValueOnce(left.promise).mockReturnValueOnce(right.promise)
  button(host, '刷新统计').click(); await flush(); button(host, '更新中').click(); await flush()
  expect(load).toHaveBeenCalledTimes(4); expect(load.mock.calls.slice(2).every(call => call[3] === true)).toBe(true)
  expect(host.textContent).toContain('80.0%'); expect(button(host, '导出排名').disabled).toBe(true)
  left.resolve(result('completion', 'all', 0, { completion: [{ owner: '甲', total: 10, complete: 10, pending: 0, rate: 100 }] })); right.reject(new Error('网络错误')); await flush()
  expect(host.textContent).toContain('100.0%'); expect(host.textContent).toContain('仍显示上次结果'); expect(button(host, '导出排名').disabled).toBe(true)
  button(host, '重试异常统计').click(); await flush(); expect(load).toHaveBeenCalledTimes(5); expect(load.mock.calls[4]?.[0]).toBe('exceptions')
  expect(button(host, '导出排名').disabled).toBe(false); expect(host.querySelector('[role="alert"]')).toBeNull()
})

it('does not display late results for a previous filter or export mismatched parts', async () => {
  const old = deferred(); load.mockImplementation((part: RankingPart, scope: RankingScope, days: number) => part === 'exceptions' && days === 30 ? old.promise : Promise.resolve(result(part, scope, days)))
  const { host } = mount(); button(host, '展开').click(); await flush(); await changeDays(host, 7)
  old.resolve(result('exceptions', 'all', 30, { exceptions: [{ owner: '旧时间人员', count: 1, abnormalQuotes: 1, relatedQuotes: 10, rate: 10 }] })); await flush()
  expect(host.textContent).not.toContain('旧时间人员'); expect(button(host, '导出排名').disabled).toBe(false)
  load.mockResolvedValueOnce(result('completion', 'all', 0, { source: 'standard' })).mockResolvedValueOnce(result('exceptions', 'all', 7, { source: 'all' }))
  button(host, '刷新统计').click(); await flush(); expect(button(host, '导出排名').disabled).toBe(true)
})

it('removes results after permission revocation or a server 403, including a late response', async () => {
  const { host } = mount(); button(host, '展开').click(); await flush()
  const left = deferred(); const right = deferred(); load.mockReturnValueOnce(left.promise).mockReturnValueOnce(right.promise)
  button(host, '刷新统计').click(); await flush(); right.reject(new ApiError('权限已撤回', 403, 'FORBIDDEN', 'test')); await flush()
  expect(host.textContent).not.toContain('80.0%'); expect(button(host, '导出排名').disabled).toBe(true)
  left.resolve(result()); await flush(); expect(host.textContent).not.toContain('80.0%')
  account('B'); await flush(); expect(host.textContent).toContain('80.0%')
  authState.permissions = []; await flush(); expect(host.textContent).not.toContain('80.0%')
})

it('keeps weighted totals, unassigned filtering, and local quantity/quality sorting', async () => {
  load.mockImplementation(async (part: RankingPart, scope: RankingScope, days: number) => result(part, scope, days, part === 'completion' ? { completion: [
    { owner: '小数量', total: 1, complete: 1, pending: 0, rate: 100 },
    { owner: '大数量', total: 100, complete: 90, pending: 10, rate: 90 },
    { owner: '', total: 500, complete: 0, pending: 500, rate: 0 },
  ] } : {}))
  const { host } = mount(); button(host, '展开').click(); await flush()
  const owners = () => [...host.querySelectorAll('table[aria-label="资料补全排名"] tbody th')].map(el => el.textContent)
  expect(owners()).toEqual(['大数量', '小数量']); expect(host.querySelector('[aria-label="采购排名汇总"]')?.textContent).toContain('90.1%')
  button(host, '完整度排名').click(); await flush(); expect(owners()).toEqual(['小数量', '大数量']); expect(load).toHaveBeenCalledTimes(2)
})
