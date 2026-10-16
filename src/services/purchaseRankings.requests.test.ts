// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest'
import { loadPurchaseRankingPart } from './purchaseRankings'

const get = vi.hoisted(() => vi.fn())
vi.mock('./http', async original => ({ ...await original<object>(), api: { get } }))
afterEach(() => { vi.resetAllMocks(); vi.useRealTimers() })

it('sends split filters and explicit refresh, and normalizes completion days', async () => {
  get.mockResolvedValue({})
  await loadPurchaseRankingPart('completion', 'all', 30)
  await loadPurchaseRankingPart('exceptions', 'sales', 7, true)
  expect(get.mock.calls[0]?.[0]).toBe('/purchase-rankings?part=completion&scope=all&days=0')
  expect(get.mock.calls[1]?.[0]).toBe('/purchase-rankings?part=exceptions&scope=sales&days=7&refresh=true')
})

it('bounds reads at 30 seconds with an actionable ranking timeout message', async () => {
  vi.useFakeTimers()
  get.mockImplementation((_path, { signal }: { signal: AbortSignal }) => new Promise((_resolve, reject) => signal.addEventListener('abort', () => reject(signal.reason), { once: true })))
  const pending = loadPurchaseRankingPart('completion', 'all', 30)
  const assertion = expect(pending).rejects.toMatchObject({ code: 'PURCHASE_RANKINGS_TIMEOUT', message: '排名统计读取超时，请稍后重试。' })
  await vi.advanceTimersByTimeAsync(30000); await assertion
  expect(vi.getTimerCount()).toBe(0)
})

it('preserves account/session cancellation without reporting it as a timeout', async () => {
  vi.useFakeTimers()
  get.mockImplementation((_path, { signal }: { signal: AbortSignal }) => new Promise((_resolve, reject) => signal.addEventListener('abort', () => reject(signal.reason), { once: true })))
  const controller = new AbortController()
  const pending = loadPurchaseRankingPart('exceptions', 'all', 30, false, controller.signal)
  const assertion = expect(pending).rejects.toMatchObject({ name: 'AbortError' })
  controller.abort(); await assertion; expect(vi.getTimerCount()).toBe(0)
})
