// @vitest-environment happy-dom
import { createApp, nextTick, ref, type App } from 'vue'
import { afterEach, expect, it, vi } from 'vitest'
import Panel from './PurchaseRankingsPanel.vue'

const load = vi.hoisted(() => vi.fn())
vi.mock('@/services/purchaseRankings', async original => ({ ...await original<object>(), loadPurchaseRankings: load }))
let app: App | undefined
const result = { scope: 'all', source: 'all', days: 30, to: '2026-10-16T00:00:00Z', completion: [{ owner: '甲', total: 10, complete: 8, pending: 2, rate: 80 }], exceptions: [], unclassified: 0, undated: 0, unmatchedSalesSkus: 0 }
const flush = async () => { await Promise.resolve(); await nextTick(); await Promise.resolve(); await nextTick() }
const button = (host: HTMLElement, text: string) => [...host.querySelectorAll('button')].find(b => b.textContent?.includes(text))!
function mount() { const host = document.createElement('div'); document.body.append(host); app = createApp(Panel); app.mount(host); return host }
function deferred() { let resolve!: (value: unknown) => void; let reject!: (reason: unknown) => void; const promise = new Promise((yes, no) => { resolve = yes; reject = no }); return { promise, resolve, reject } }
afterEach(() => { app?.unmount(); document.body.innerHTML = ''; vi.resetAllMocks(); vi.useRealTimers() })

it('loads once on first expand and ignores time, parent updates and repeated expansion', async () => {
  vi.useFakeTimers(); load.mockResolvedValue(result)
  const tick = ref(0)
  const host = document.createElement('div'); document.body.append(host)
  app = createApp({ components: { Panel }, setup: () => ({ tick }), template: '<Panel /><span>{{ tick }}</span>' }); app.mount(host)
  await flush(); expect(load).not.toHaveBeenCalled(); expect(host.querySelector('table')).toBeNull()
  button(host, '展开').click(); await flush(); expect(load).toHaveBeenCalledTimes(1)
  expect(host.querySelector('details')?.hasAttribute('open')).toBe(false)
  tick.value++; await vi.advanceTimersByTimeAsync(180000); await flush(); expect(load).toHaveBeenCalledTimes(1)
  button(host, '收起').click(); await flush(); expect(host.querySelector('table')).toBeNull()
  button(host, '展开').click(); await flush(); expect(load).toHaveBeenCalledTimes(1); expect(host.textContent).toContain('80.0%')
})

it('shares an active read across collapse and reopening, and cancels only on unmount', async () => {
  const pending = deferred(); load.mockReturnValue(pending.promise)
  const host = mount(); button(host, '展开').click(); await flush()
  const signal = load.mock.calls[0]![2] as AbortSignal
  button(host, '收起').click(); await flush(); button(host, '展开').click(); await flush()
  expect(signal.aborted).toBe(false); expect(load).toHaveBeenCalledTimes(1)
  app!.unmount(); app = undefined; expect(signal.aborted).toBe(true)
  pending.resolve(result); await flush()
})

it('keeps current rows during manual refresh, coalesces clicks and retains them on failure', async () => {
  const pending = deferred(); load.mockResolvedValueOnce(result).mockReturnValueOnce(pending.promise).mockResolvedValue({ ...result, completion: [{ owner: '甲', total: 10, complete: 10, pending: 0, rate: 100 }] })
  const host = mount(); button(host, '展开').click(); await flush()
  const refresh = button(host, '刷新统计'); refresh.click(); await flush(); refresh.click(); await flush()
  expect(load).toHaveBeenCalledTimes(2); expect(host.textContent).toContain('80.0%'); expect(host.textContent).toContain('更新中…')
  pending.reject(new Error('网络错误')); await flush()
  expect(host.textContent).toContain('仍显示上次结果'); expect(host.textContent).toContain('80.0%')
  button(host, '收起').click(); await flush(); button(host, '展开').click(); await flush(); expect(load).toHaveBeenCalledTimes(2)
  button(host, '重试').click(); await flush(); expect(host.textContent).toContain('100.0%'); expect(host.querySelector('[role="alert"]')).toBeNull()
})

it('caches filter selections separately and never displays a late result under another scope', async () => {
  const all = deferred(); const sales = deferred()
  load.mockReturnValueOnce(all.promise).mockReturnValueOnce(sales.promise).mockResolvedValue({ ...result, days: 7 })
  const host = mount(); button(host, '展开').click(); await flush(); button(host, '在售 SKU').click(); await flush()
  all.resolve(result); await flush(); expect(host.textContent).not.toContain('80.0%')
  sales.resolve({ ...result, scope: 'sales', completion: [{ owner: '乙', total: 5, complete: 3, pending: 2, rate: 60 }] }); await flush()
  expect(host.textContent).toContain('60.0%')
  button(host, '全部采购').click(); await flush(); expect(load).toHaveBeenCalledTimes(2); expect(host.textContent).toContain('80.0%'); expect(host.textContent).not.toContain('60.0%')
  const select = host.querySelector('select')!; select.value = '7'; select.dispatchEvent(new Event('change')); await flush()
  expect(load).toHaveBeenCalledTimes(3)
  select.value = '30'; select.dispatchEvent(new Event('change')); await flush(); expect(load).toHaveBeenCalledTimes(3)
})

it('does not automatically retry failed initial reads when reopened', async () => {
  load.mockRejectedValue(new Error('读取失败'))
  const host = mount(); button(host, '展开').click(); await flush(); button(host, '收起').click(); await flush(); button(host, '展开').click(); await flush()
  expect(load).toHaveBeenCalledTimes(1); expect(host.textContent).toContain('读取失败')
  button(host, '重试').click(); await flush(); expect(load).toHaveBeenCalledTimes(2)
})
