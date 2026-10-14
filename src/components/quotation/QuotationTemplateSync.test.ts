// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, type App } from 'vue'
import Sync from './QuotationTemplateSync.vue'
const services = vi.hoisted(() => ({ loadTemplateSyncAccounts: vi.fn(), loadTemplateSyncSource: vi.fn(), syncQuotationTemplates: vi.fn() }))
vi.mock('@/services/quotationTemplateSync', () => services)
vi.mock('@/data/authStore', () => ({ currentAuthUser: { value: { account: 'SOURCE' } } }))
vi.mock('@/services/http', () => ({ idempotencyKey: () => crypto.randomUUID() }))
let app: App
const tick = async () => { for (let i = 0; i < 12; i++) await nextTick() }
const template = (id = 'one') => ({ id, name: '普货', _version: 4, items: [{ country: '美国' }] })
const btn = (text: string) => [...document.querySelectorAll('button')].find(b => b.textContent?.includes(text))!
const input = (label: string) => document.querySelector<HTMLInputElement>(`[aria-label="${label}"]`)!
async function mount() { const host = document.createElement('div'); document.body.append(host); app = createApp({ render: () => h(Sync) }); app.mount(host); await tick() }
beforeEach(() => {
  vi.clearAllMocks()
  services.loadTemplateSyncAccounts.mockResolvedValue([{ account: 'SOURCE', name: '魏梦玉', templateCount: 1 }, { account: 'TARGET', name: '接收人', templateCount: 1 }])
  services.loadTemplateSyncSource.mockResolvedValue([template()])
  services.syncQuotationTemplates.mockResolvedValue({ created: 1, skipped: 0, recipients: [] })
})
afterEach(() => { app?.unmount(); document.body.innerHTML = '' })
it('defaults to current source, excludes self and submits selected versions with the prefix', async () => {
  await mount()
  expect(input('模板名称前缀').value).toBe('魏-')
  expect(document.querySelectorAll('.people-list label')).toHaveLength(1)
  expect(btn('一键同步').disabled).toBe(true)
  document.querySelector<HTMLInputElement>('.people-list input')!.click(); await tick(); btn('一键同步').click(); await tick()
  expect(services.syncQuotationTemplates).toHaveBeenCalledWith({ sourceAccount: 'SOURCE', prefix: '魏-', templates: [{ id: 'one', version: 4 }], targetAccounts: ['TARGET'] }, expect.any(String))
  expect(document.body.textContent).toContain('同步完成：新增 1 个')
  expect(btn('一键同步')).toBeUndefined()
})
it('reuses the request key on network retry and disables controls during submission', async () => {
  await mount(); document.querySelector<HTMLInputElement>('.people-list input')!.click(); await tick()
  let reject!: (e: Error) => void
  services.syncQuotationTemplates.mockImplementationOnce(() => new Promise((_r, fail) => { reject = fail }))
  btn('一键同步').click(); await tick()
  expect(document.querySelector('fieldset')?.disabled).toBe(true)
  expect(btn('取消').disabled).toBe(true)
  reject(new Error('网络中断')); await tick()
  expect(document.body.textContent).toContain('网络中断')
  btn('一键同步').click(); await tick()
  expect(services.syncQuotationTemplates.mock.calls[1]![1]).toBe(services.syncQuotationTemplates.mock.calls[0]![1])
})
it('ignores a late source response and removes a newly selected source from recipients', async () => {
  await mount(); document.querySelector<HTMLInputElement>('.people-list input')!.click(); await tick()
  let finish!: (rows: unknown[]) => void
  services.loadTemplateSyncSource.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  const select = document.querySelector<HTMLSelectElement>('[aria-label="来源账号"]')!
  select.value = 'TARGET'; select.dispatchEvent(new Event('change')); await tick()
  expect(btn('一键同步').disabled).toBe(true)
  select.value = 'SOURCE'; select.dispatchEvent(new Event('change')); await tick()
  finish([{ ...template('late'), name: '过期来源' }]); await tick()
  expect(document.querySelector('.source-list')?.textContent).not.toContain('过期来源')
  expect(document.body.textContent).toContain('已选 0 人')
})
it('shows skipped templates per recipient without claiming they were updated', async () => {
  services.syncQuotationTemplates.mockResolvedValue({ created: 0, skipped: 1, recipients: [{ account: 'TARGET', name: '接收人', created: 0, skipped: 1, templates: [{ sourceId: 'one', name: '魏-普货', status: 'skipped', reason: '已有同名模板，保留原模板' }] }] })
  await mount(); document.querySelector<HTMLInputElement>('.people-list input')!.click(); await tick(); btn('一键同步').click(); await tick()
  expect(document.querySelector('.sync-result')?.textContent).toContain('已有同名模板，保留原模板')
  expect(document.querySelector('.sync-result')?.textContent).toContain('新增 0 个，跳过 1 个')
})
