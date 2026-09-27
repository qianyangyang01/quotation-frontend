// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import ShimoSyncPanel from './ShimoSyncPanel.vue'
const mocks = vi.hoisted(() => ({request: vi.fn(), user: {value: {role: 'purchase'}}}))
vi.mock('@/services/http', () => ({request: mocks.request}))
vi.mock('@/data/authStore', () => ({currentAuthUser: mocks.user}))
let app: App
async function flush() { await Promise.resolve(); await nextTick(); await Promise.resolve(); await nextTick() }
const button = (text: string) => Array.from(document.querySelectorAll('button')).find(b => b.textContent?.includes(text))!
async function mount() { const host = document.createElement('div'); document.body.append(host); app = createApp(ShimoSyncPanel); app.mount(host); await flush() }
beforeEach(() => {
  mocks.user.value.role = 'purchase'
  mocks.request.mockImplementation(async (path: string) => {
    if (path.includes('/items')) return {total: 1, rows: [{sku: 'AB123',sheet: '老数据更新',source_row: 2,status: 'pending',reason: '缺票点',checked_at: '2026-09-27T04:30:00Z'}]}
    if (path.includes('/changes')) return {total: 1, rows: [{id: '1',sku: 'AB124',sheet: '业务新人',source_row: 3,created_at: '2026-09-27T04:31:00Z',fields: [{field: 'weightG',label: '克重(g)',before: 100,after: 120},{field: 'taxPoint',label: '票点',before: .08,after: 0}]}]}
    return {configured: true,enabled: true,running: false,intervalSeconds: 600,sheets: ['老数据更新','业务新人'],runs: [{status: 'fetch_failed',mode: 'incremental',started_at: '2026-09-27T04:30:00Z',current_sheet: '业务新人',next_row: 152,processed: 0,changed: 0,reason: '接口失败'}],counts: [{status: 'pending',total: 1}]}
  })
})
afterEach(() => {app?.unmount();document.body.innerHTML = '';vi.clearAllMocks()})
it('loads only when opened and shows checkpoints, pending reason and before/after weight and explicit zero tax', async () => {
  await mount();expect(mocks.request).not.toHaveBeenCalled()
  button('石墨新版自动同步').click();await flush()
  expect(document.body.textContent).toContain('152')
  expect(document.body.textContent).toContain('缺票点')
  expect(document.body.textContent).toContain('等待 10 分钟')
  expect(document.body.textContent).toContain('100 → 120')
  expect(document.body.textContent).toContain('8% → 0%')
  expect(button('暂停同步')).toBeUndefined()
  const filter = document.querySelector<HTMLInputElement>('input[type=checkbox]')!;filter.checked = true;filter.dispatchEvent(new Event('change'));await flush()
  expect(mocks.request).toHaveBeenCalledWith('/purchase-shimo-sync/changes?page=0&weightOnly=true')
})
it('allows administrators to pause without changing purchase fields', async () => {
  mocks.user.value.role = 'super_admin';await mount();button('石墨新版自动同步').click();await flush()
  button('暂停同步').click();await flush()
  expect(mocks.request).toHaveBeenCalledWith('/purchase-shimo-sync/enabled',{method: 'POST',body: '{"enabled":false}'})
})
it('reports a failed refresh rather than claiming the list is empty', async () => {
  await mount();mocks.request.mockRejectedValue(new Error('网络异常'));button('石墨新版自动同步').click();await flush()
  expect(document.querySelector('[role=alert]')?.textContent).toContain('网络异常')
  expect(document.body.textContent).not.toContain('当前没有待处理记录')
})
