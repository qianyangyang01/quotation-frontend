// @vitest-environment happy-dom
import { createApp, nextTick } from 'vue'
import { afterEach, expect, it, vi } from 'vitest'
import CompanyChannelDirectory from './CompanyChannelDirectory.vue'
const mocks = vi.hoisted(() => ({ list: vi.fn(), job: vi.fn(), preview: vi.fn(), begin: vi.fn(), step: vi.fn() }))
vi.mock('@/data/companyChannels', () => ({ companyChannels: mocks }))
vi.mock('@/data/publishedLogisticsRepository', () => ({ invalidatePublishedLogisticsCache: vi.fn() }))
const cleanups: Array<() => void> = []
afterEach(() => { cleanups.splice(0).forEach(fn => fn()); vi.clearAllMocks() })
async function render(paused = false) {
  mocks.list.mockResolvedValue({ revision: 3, enabled: true, state: { paused, rebuild_id: paused ? 'job' : undefined }, entries: Array.from({ length: 31 }, (_, i) => ({ id: String(i), providerName: '花海', channelName: `渠道${i}`, logisticsAttribute: '普货', enabled: true, productCodes: [], aliases: [] })) })
  mocks.job.mockResolvedValue({ id: 'job', phase: 'backed-up', payload: { targetDatasetId: 'new', backup: { sha256: 'verified' } } })
  const host = document.createElement('div'); document.body.append(host)
  const app = createApp(CompanyChannelDirectory); app.mount(host); cleanups.push(() => { app.unmount(); host.remove() })
  await new Promise(resolve => setTimeout(resolve, 0)); await nextTick()
  host.querySelector('header button')!.dispatchEvent(new MouseEvent('click')); await nextTick()
  return host
}
it('loads read-only and paginates the company directory with 10/30/50 sizes', async () => {
  const host = await render()
  expect(host.querySelectorAll('tbody tr')).toHaveLength(10)
  const select = host.querySelector('select')!
  expect([...select.options].map(o => o.value)).toEqual(['10', '30', '50'])
  select.value = '30'; select.dispatchEvent(new Event('change')); await nextTick()
  expect(host.querySelectorAll('tbody tr')).toHaveLength(30)
  expect(mocks.begin).not.toHaveBeenCalled(); expect(mocks.step).not.toHaveBeenCalled()
})
it('keeps deletion disabled until explicit confirmation and shows paused quotations', async () => {
  const host = await render(true)
  expect(host.textContent).toContain('新报价提交已暂停')
  const remove = [...host.querySelectorAll('button')].find(b => b.textContent === '清理旧价格')!
  expect(remove.disabled).toBe(true); remove.click(); expect(mocks.step).not.toHaveBeenCalled()
  const checkbox = host.querySelector('.rebuild input[type=checkbox]') as HTMLInputElement
  checkbox.checked = true; checkbox.dispatchEvent(new Event('change')); await nextTick()
  expect(remove.disabled).toBe(false)
})
