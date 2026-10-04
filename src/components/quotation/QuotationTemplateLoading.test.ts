// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, reactive, type App } from 'vue'
import Template from './QuotationTemplateMatrix.vue'
import { loadQuotationTemplates } from '@/data/quotationTemplates'
import type { QuotationMatrixRow } from './types'

vi.mock('@/data/quotationTemplates', async original => ({ ...await original<typeof import('@/data/quotationTemplates')>(), loadQuotationTemplates: vi.fn() }))
const load = vi.mocked(loadQuotationTemplates)
const row = { country: '美国', channelKey: 'a', quoteRegion: '全国统一', ruleId: 1, rule: '规则', carrier: '物流', transport: '渠道', channelCode: 'A', quote1: 10, quote2: 20, quote3: 30, quoteCustom: 50, taxConfigured: true, eta: '5天' } as QuotationMatrixRow
const template = { id: 't1', _version: 1, name: '旧用户模板', ownerKey: 'ACCOUNT:A', ownerAccount: 'A', ownerName: '甲', createdAt: '2026-09-01', updatedAt: '2026-09-01', items: [{ ...row, countryCode: 'US' }] }
let app: App
const tick = async () => { for (let i = 0; i < 12; i++) await nextTick() }
const button = (text: string) => [...document.querySelectorAll('button')].find(b => b.textContent?.includes(text))!
function mount(sourcePending = false) {
  const state = reactive({ active: true, sourcePending, countries: [{ name: '美国', code: 'US', channelCount: 1, lowestQuote: 10, stage: 'common' as const, continent: '北美洲' as const, sortOrder: 0, grouped: false }],
    contextKey: 'a', customQuantity: 5, adoptedCountry: '', adoptedRule: '', adoptedCarrier: '', exchangeRate: 7,
    ownerName: '甲', ownerAccount: 'A', ensureCountries: vi.fn(async () => true), quoteRowsForCountry: () => [row] })
  const host = document.createElement('div'); document.body.append(host)
  app = createApp({ render: () => h(Template, state) }); app.mount(host)
  return state
}
beforeEach(() => { vi.clearAllMocks(); load.mockResolvedValue([structuredClone(template)]) })
afterEach(() => { app?.unmount(); document.body.innerHTML = '' })

it('shows a recoverable list failure instead of pretending the user has no templates', async () => {
  load.mockRejectedValueOnce(new Error('网络超时'))
  mount(); await tick()
  expect(document.body.textContent).toContain('模板列表加载失败')
  expect(button('重新加载模板')).toBeDefined()
  button('重新加载模板').click(); await tick()
  expect(document.body.textContent).not.toContain('模板列表加载失败')
  expect(button('一键应用').disabled).toBe(false)
})

it('ignores an older list response after a newer refresh or account switch', async () => {
  let finish!: (value: typeof template[]) => void
  load.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  const state = mount(); await tick()
  state.ownerAccount = 'B'; state.ownerName = '乙'
  load.mockResolvedValue([{ ...template, id: 'b', name: '乙的模板', ownerAccount: 'B' }]); await tick()
  finish([template]); await tick()
  expect(document.querySelector('select[aria-label="选择个人报价模板"]')?.textContent).toContain('乙的模板')
  expect(document.querySelector('select[aria-label="选择个人报价模板"]')?.textContent).not.toContain('旧用户模板')
})

it('waits for an in-progress product query before loading template countries', async () => {
  const state = mount(true); await tick(); button('一键应用').click(); await tick()
  expect(state.ensureCountries).not.toHaveBeenCalled()
  state.sourcePending = false; await tick()
  expect(state.ensureCountries).toHaveBeenCalledExactlyOnceWith(['美国'])
  expect(document.querySelector('.template-status')?.textContent).toContain('1 条模板渠道可用')
})

it('restarts a superseded template load and ignores its late failure', async () => {
  const state = mount(); await tick()
  let finish!: (ok: boolean) => void
  state.ensureCountries.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  button('一键应用').click(); await tick()
  state.sourcePending = true; await tick(); state.sourcePending = false; await tick()
  expect(state.ensureCountries).toHaveBeenCalledTimes(2)
  finish(false); await tick()
  expect(document.body.textContent).not.toContain('模板渠道加载失败')
  expect(document.querySelector('.template-status')?.textContent).toContain('1 条模板渠道可用')
})

it('shows the real load error and retries the applied selection without overwriting the template', async () => {
  const state = mount(); await tick()
  state.ensureCountries.mockRejectedValueOnce(new Error('请先查询商品，再加载报价渠道'))
  button('一键应用').click(); await tick()
  expect(document.body.textContent).toContain('请先查询商品，再加载报价渠道')
  expect(document.querySelector('.template-status')?.textContent).not.toContain('条模板渠道可用')
  button('重试加载模板渠道').click(); await tick()
  expect(state.ensureCountries).toHaveBeenCalledTimes(2)
  expect(document.querySelector('.template-status')?.textContent).toContain('1 条模板渠道可用')
})

it('does not replay a saved template over temporary deletions when product data reloads', async () => {
  const state = mount(); await tick(); button('一键应用').click(); await tick()
  button('移出报价单').click(); await tick()
  state.sourcePending = true; await tick(); state.sourcePending = false; state.contextKey = 'new'; await tick()
  expect(document.querySelector('.selected-channels')).toBeNull()
  expect(state.ensureCountries).toHaveBeenCalledTimes(1)
})
