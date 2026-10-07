// @vitest-environment happy-dom
import { createApp, h, nextTick } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import Component from './FreightDiscountSettings.vue'
import { confirmedZhengzhouDiscount, ZHENGZHOU_DISCOUNT_COUNTRIES, type FreightDiscountChannel } from '@/data/freightDiscountSettings'
const flush = async () => { await new Promise(resolve => setTimeout(resolve, 0)); await nextTick() }
const store = vi.hoisted(() => ({ saved: { rules: [] } as Record<string, unknown>, write: vi.fn(), invalidate: vi.fn(), channels: vi.fn() }))
vi.mock('@/services/financeSettings', () => ({ readFinanceSetting: () => store.saved, writeFinanceSetting: (...args: unknown[]) => store.write(...args) }))
vi.mock('@/services/http', () => ({ api: { get: (...args: unknown[]) => store.channels(...args) } }))
vi.mock('@/data/publishedLogisticsRepository', () => ({ invalidatePublishedLogisticsCache: () => store.invalidate() }))
const first: FreightDiscountChannel = { channelId: '00000000-0000-0000-0000-000000000601', channelCode: 'C-44fc48641d26ef2cab34', providerName: '燕文', channelName: '中邮郑州线下E邮宝', countries: [...ZHENGZHOU_DISCOUNT_COUNTRIES.map(([code, name]) => ({ code, name })), { code: 'CA', name: '加拿大' }] }
const second: FreightDiscountChannel = { channelId: '00000000-0000-0000-0000-000000000602', channelCode: 'other', providerName: '其他承运商', channelName: '其他渠道', countries: [{ code: 'US', name: '美国' }] }
let app: ReturnType<typeof createApp>, host: HTMLDivElement
beforeEach(async () => {
  store.saved = { rules: [] }; store.write.mockReset(); store.invalidate.mockReset(); store.channels.mockReset()
  store.channels.mockResolvedValue([first, second])
  store.write.mockImplementation(async (_key, value) => { store.saved = JSON.parse(JSON.stringify(value)); return store.saved })
  host = document.createElement('div'); document.body.append(host)
  app = createApp({ render: () => h(Component) }); app.mount(host); await flush()
})
afterEach(() => { app.unmount(); host.remove() })
const field = (name: string) => host.querySelector<HTMLInputElement>(`[aria-label="${name}"]`)!
async function input(name: string, value: string) { const el = field(name); el.value = value; el.dispatchEvent(new Event('input')); await nextTick() }
async function click(text: string) { const button = [...host.querySelectorAll('button')].find(b => b.textContent?.includes(text))!; expect(button).toBeTruthy(); button.click(); await flush() }
it('publishes the preset independently from taxes and restores it on reopen', async () => {
  expect(field('越南折扣系数').value).toBe('0.96'); expect(field('加拿大折扣系数').value).toBe('1.05')
  await click('保存当前渠道')
  expect(store.write).toHaveBeenCalledWith('freight-discount-settings', expect.objectContaining({ rules: [confirmedZhengzhouDiscount(first)] }))
  expect(store.invalidate).toHaveBeenCalledOnce()
  expect(host.querySelector('[role=status]')?.textContent).toContain('已发布')
  app.unmount(); app = createApp({ render: () => h(Component) }); app.mount(host); await flush()
  expect(field('默认系数').value).toBe('1.05'); expect(host.textContent).not.toContain('尚未发布')
  field('启用当前渠道折扣').click(); await nextTick(); await click('保存当前渠道')
  expect((store.saved.rules as Array<{ enabled: boolean }>)[0]!.enabled).toBe(false)
})
it('retains drafts across channel switches and saves only the selected channel', async () => {
  await input('澳大利亚折扣系数', '.95')
  await click('其他渠道'); await input('默认系数', '.9')
  await click('中邮郑州'); expect(field('澳大利亚折扣系数').value).toBe('0.95')
  await click('保存当前渠道')
  expect(store.saved.rules).toHaveLength(1)
  await click('其他渠道'); expect(field('默认系数').value).toBe('0.9')
  await click('保存当前渠道'); expect(store.saved.rules).toHaveLength(2)
})
it('supports country search, batch editing, and fallback to the current default', async () => {
  await input('搜索折扣国家', '加拿大'); field('选择当前筛选国家').click(); await nextTick()
  await input('批量系数', '.91'); await click('应用到所选国家')
  expect(field('加拿大折扣系数').value).toBe('0.91')
  await click('所选恢复默认'); expect(field('加拿大折扣系数').value).toBe('1.05')
  await input('默认系数', '1.02'); expect(field('加拿大折扣系数').value).toBe('1.02')
  await input('搜索折扣国家', '澳大利亚'); expect(field('澳大利亚折扣系数').value).toBe('0.97')
})
it('blocks invalid factors and retains edited values on a concurrency conflict', async () => {
  await input('澳大利亚折扣系数', '0'); await click('保存当前渠道')
  expect(store.write).not.toHaveBeenCalled(); expect(host.querySelector('[role=alert]')?.textContent).toContain('大于0')
  await input('澳大利亚折扣系数', '.95')
  store.write.mockRejectedValueOnce(new Error('财务设置已被其他用户修改，请刷新后重试'))
  await click('保存当前渠道')
  expect(host.querySelector('[role=alert]')?.textContent).toContain('其他用户修改')
  expect(field('澳大利亚折扣系数').value).toBe('0.95'); expect(store.invalidate).not.toHaveBeenCalled()
})
it('does not allow catalogue failures to publish incomplete settings', async () => {
  app.unmount(); store.channels.mockRejectedValueOnce(new Error('渠道目录读取失败'))
  app = createApp({ render: () => h(Component) }); app.mount(host); await flush()
  expect(host.querySelector('[role=alert]')?.textContent).toContain('读取失败')
  expect(host.querySelector('.primary')).toBeNull(); expect(store.write).not.toHaveBeenCalled()
})
