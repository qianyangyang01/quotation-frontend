// @vitest-environment happy-dom
import { createApp, nextTick, ref, type App } from 'vue'
import { afterEach, expect, it, vi } from 'vitest'
import Panel from './PurchaseRankingsPanel.vue'

const load = vi.hoisted(() => vi.fn())
vi.mock('@/services/purchaseRankings', async original => ({ ...await original<object>(), loadPurchaseRankings: load }))
let app: App | undefined
const result = { scope: 'all', source: 'all', days: 30, to: '2026-10-16T00:00:00Z', completion: [], exceptions: [], unclassified: 0, undated: 0 }
const flush = async () => { await Promise.resolve(); await nextTick(); await Promise.resolve(); await nextTick() }
afterEach(() => { app?.unmount(); document.body.innerHTML = ''; vi.resetAllMocks() })
it('loads only when expanded, ignores background refresh while collapsed, and keeps help collapsed', async () => {
  load.mockResolvedValue(result)
  const key = ref(0)
  const host = document.createElement('div'); document.body.append(host)
  app = createApp({ components: { Panel }, setup: () => ({ key }), template: '<Panel :refresh-key="key" />' }); app.mount(host)
  await flush(); expect(load).not.toHaveBeenCalled(); expect(host.querySelector('table')).toBeNull()
  key.value++; await flush(); expect(load).not.toHaveBeenCalled()
  const toggle = () => (host.querySelector('.ranking-toggle') as HTMLButtonElement).click()
  toggle(); await flush(); expect(load).toHaveBeenCalledTimes(1); expect(host.querySelectorAll('table')).toHaveLength(2)
  expect(host.querySelector('details')?.hasAttribute('open')).toBe(false)
  toggle(); await flush(); key.value++; await flush(); expect(load).toHaveBeenCalledTimes(1); expect(host.querySelector('table')).toBeNull()
  toggle(); await flush(); expect(load).toHaveBeenCalledTimes(2)
})
it('aborts an in-flight read on collapse and ignores a late response after reopening', async () => {
  let resolveOld!: (value: unknown) => void
  load.mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve })).mockResolvedValue({ ...result, unclassified: 2 })
  const host = document.createElement('div'); document.body.append(host); app = createApp(Panel); app.mount(host)
  const toggle = () => (host.querySelector('.ranking-toggle') as HTMLButtonElement).click()
  toggle(); await flush(); const signal = load.mock.calls[0]![2] as AbortSignal
  toggle(); await flush(); expect(signal.aborted).toBe(true)
  toggle(); await flush(); resolveOld({ ...result, unclassified: 999 }); await flush()
  expect(host.querySelector('.help-content')?.textContent).toContain('未分类 2 条')
  expect(host.querySelector('.help-content')?.textContent).not.toContain('999')
})
