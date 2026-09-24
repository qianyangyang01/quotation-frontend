// @vitest-environment happy-dom
import { createApp, nextTick, type App } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import View from './QuotationSystemView.vue'
import { api } from '@/services/http'
import { clearFinanceSettingsCache } from '@/services/financeSettings'
import { loadPublishedLogisticsRules } from '@/data/publishedLogisticsRepository'
import type { QuotationMatrixRow } from '@/components/quotation/types'

const router = vi.hoisted(() => ({ replace: vi.fn().mockResolvedValue(undefined), query: { reissue: 'original', release: 'local' } as Record<string, string> }))
vi.mock('vue-router', () => ({ useRoute: () => ({ query: router.query }), useRouter: () => router, onBeforeRouteLeave: vi.fn() }))
vi.mock('@/services/quotationSync', async original => ({ ...await original<typeof import('@/services/quotationSync')>(), startQuotationSync: vi.fn(() => vi.fn()) }))
vi.mock('@/data/publishedLogisticsRepository', async original => ({
  ...await original<typeof import('@/data/publishedLogisticsRepository')>(),
  loadPublishedLogisticsManifest: vi.fn().mockResolvedValue({ verified: true, manifest: { revision: 'latest' } }),
  loadPublishedLogisticsRules: vi.fn().mockResolvedValue({ verified: true, revision: 'latest', rules: [] }),
}))

const finance = {
  'country-classification': { value: [], _version: 1 }, 'channel-policies': { value: [], _version: 1 },
  'customer-grades': { value: [{ grade: 'NEW', coefficient: 1.2, enabled: true }], _version: 1 },
  'exchange-rate': { value: { usdCny: 6.7, updatedAt: 'test' }, _version: 1 },
  'tax-settings': { value: { countries: [], providers: [], updatedAt: 'test' }, _version: 1 },
  'customer-operation-fees': { value: { customers: [] }, _version: 1 },
}
const source = { id: 'original', no: 'QT-OLD', customerName: '原客户', primarySku: 'BK100', quoteMode: 'single',
  logisticsAttribute: '普货', customerGrade: '新客户', country: '日本', carrier: '原物流', rule: '原规则', channel: '原渠道',
  quoteConfirmed: true, status: 'won', customQuoteQuantity: 8, purchaseUnitPriceCny: 999,
  quoteOptions: [{ id: 'option', country: '日本', channelKey: '1::原物流::JP', carrier: '原物流', rule: '原规则', channel: '原渠道', isPrimary: true }],
}
const oldDraft = { schemaVersion: 2, quoteMode: 'single', customerName: '未完成客户', skuSearch: '', logisticsAttribute: '普货' }
let app: App
let host: HTMLDivElement
let state: { draftReady: boolean; draftStatus: string; reissueSource: string; customerName: string; products: Array<{ sku: string; purchase: number }>; modeSelections: { common: unknown[] }; searchChannelCountries: (query: string) => Promise<string[]>;
  quoteMatrixMode: 'common' | 'specified' | 'template'; logisticsLoadState: string; commonQuoteRows: QuotationMatrixRow[]; specifiedQuoteRows: QuotationMatrixRow[] }
let purchaseFailure = false
let sourceFailure = false
beforeEach(() => { clearFinanceSettingsCache(); vi.clearAllMocks(); purchaseFailure = false; sourceFailure = false; router.query = { reissue: 'original', release: 'local' } })
afterEach(() => { app?.unmount(); host?.remove(); clearFinanceSettingsCache(); vi.restoreAllMocks() })
async function mount(existing: boolean, withdrawn = false) {
  vi.spyOn(api, 'get').mockImplementation(async path => {
    if (path === '/finance-settings') return finance
    if (path === '/quotation-readiness') return { ready: true, missing: [] }
    if (path === '/quotation-templates') return []
    if (path === '/quotations/original') { if (sourceFailure) throw new Error('无权读取原报价'); return source }
    if (path === '/quotation-drafts/mine/state') return { exists: existing, version: existing ? 7 : -1, payload: existing ? oldDraft : null, ...(withdrawn ? { sourceQuote: { id: 'original', no: 'QT-OLD', version: 8 } } : {}) }
    if (path === '/purchase-products/BK100') {
      if (purchaseFailure) throw new Error('采购资料读取失败')
      return { sku: 'BK100', category: '日用品', catalogState: 'ready', weightG: 50, minOrderQty: 1, purchasePriceCny: 12, singleFreightCny: 0, taxPoint: 0 }
    }
    throw new Error(`Unexpected API: ${path}`)
  })
  vi.spyOn(api, 'put').mockResolvedValue({ exists: true, version: 8, updatedAt: 'now' })
  vi.spyOn(api, 'post').mockRejectedValue(new Error('Unexpected quotation creation'))
  vi.spyOn(api, 'patch').mockRejectedValue(new Error('Unexpected original update'))
  host = document.createElement('div'); document.body.append(host)
  app = createApp(View)
  const vm = app.mount(host)
  state = (vm.$ as unknown as { setupState: typeof state }).setupState
  await vi.waitFor(() => expect(state.draftReady || state.draftStatus === 'error').toBe(true))
}
const button = (text: string) => [...host.querySelectorAll('button')].find(item => item.textContent?.includes(text))!

it('opens a new draft, reads current purchase data and removes unavailable source channels without creating a record', async () => {
  await mount(false)
  await vi.waitFor(() => expect(state.reissueSource).toBe('QT-OLD'))
  expect(state.customerName).toBe('原客户')
  // Current zero-tax-point purchase pricing adds the configured 1%, rather than copying the old 999.
  expect(state.products[0]).toMatchObject({ sku: 'BK100', purchase: 12.12 })
  expect(state.modeSelections.common).toEqual([])
  expect(loadPublishedLogisticsRules).toHaveBeenCalledWith(expect.objectContaining({ countries: expect.arrayContaining(['日本']) }), expect.anything())
  expect(router.replace).toHaveBeenCalledWith({ query: { release: 'local' } })
  expect(api.post).not.toHaveBeenCalled(); expect(api.patch).not.toHaveBeenCalled()
  await vi.waitFor(() => expect(api.put).toHaveBeenCalled(), { timeout: 2000 })
  expect(api.put).toHaveBeenCalledWith('/quotation-drafts/mine/state', expect.objectContaining({ customerName: '原客户' }), { 'If-Match': '-1' })
})

it('keeps an existing server draft until the user chooses to replace it', async () => {
  await mount(true)
  expect(host.textContent).toContain('保留当前草稿')
  expect(state.customerName).toBe('未完成客户')
  expect(api.put).not.toHaveBeenCalled()
  button('保留当前草稿').click(); await nextTick()
  expect(state.customerName).toBe('未完成客户')
  expect(state.reissueSource).toBe('')
  expect(api.put).not.toHaveBeenCalled()
})

it('uses the existing draft version on explicit replacement', async () => {
  await mount(true)
  button('载入并再次发起').click()
  await vi.waitFor(() => expect(state.reissueSource).toBe('QT-OLD'))
  await vi.waitFor(() => expect(api.put).toHaveBeenCalled(), { timeout: 2000 })
  expect(api.put).toHaveBeenCalledWith('/quotation-drafts/mine/state', expect.objectContaining({ customerName: '原客户' }), { 'If-Match': '7' })
})

it('leaves the current draft intact if purchase loading fails before replacement', async () => {
  await mount(true); purchaseFailure = true
  button('载入并再次发起').click()
  await vi.waitFor(() => expect(host.textContent).toContain('采购资料读取失败'))
  expect(state.customerName).toBe('未完成客户')
  expect(api.put).not.toHaveBeenCalled()
})

it('does not copy inaccessible source records', async () => {
  sourceFailure = true; await mount(false)
  expect(state.draftStatus).toBe('error')
  expect(state.reissueSource).toBe('')
  expect(api.put).not.toHaveBeenCalled(); expect(api.post).not.toHaveBeenCalled(); expect(api.patch).not.toHaveBeenCalled()
})

it.each(['common', 'specified'] as const)('saves the selected %s list through the actual page template entry', async mode => {
  await mount(false)
  await vi.waitFor(() => expect(state.reissueSource).toBe('QT-OLD'))
  state.quoteMatrixMode = mode; await nextTick()
  state.logisticsLoadState = 'ready'; await nextTick()
  const selected = { country: '日本', quoteRegion: '全国统一', rule: '指定普货', carrier: '原物流', transport: '指定普货',
    channelKey: '1::原物流::JP', channelCode: 'JP', ruleId: 1, quote1: 10, taxConfigured: true } as QuotationMatrixRow
  if (mode === 'common') state.commonQuoteRows = [selected]
  else state.specifiedQuoteRows = [selected]
  await nextTick()
  const entry = [...host.querySelectorAll<HTMLButtonElement>('.save-selection-template')][mode === 'common' ? 0 : 1]!
  expect(entry.disabled).toBe(false); entry.click()
  await vi.waitFor(() => expect(host.querySelector('.creation-preview')?.textContent).toContain('指定普货'))
  expect(state.quoteMatrixMode).toBe('template')
  const name = host.querySelector<HTMLInputElement>('.create-template input')!
  name.value = '普货'; name.dispatchEvent(new Event('input')); await nextTick()
  vi.mocked(api.post).mockImplementationOnce(async (_path, body) => ({ ...(body as object), id: 'new-template', createdAt: '2026-09-24', updatedAt: '2026-09-24' }))
  button('＋ 新建模板').click()
  await vi.waitFor(() => expect(api.post).toHaveBeenCalledWith('/quotation-templates', expect.objectContaining({
    name: '普货', items: [expect.objectContaining({ country: '日本', channelKey: '1::原物流::JP', transport: '指定普货' })],
  }), expect.any(String)))
})

it('restores the original quotation identity and explicitly cancels it when abandoning a withdrawn draft', async () => {
  router.query = { release: 'local' }
  await mount(true, true)
  expect(host.textContent).toContain('撤回重新编辑 · 报价 QT-OLD')
  const confirm = vi.fn().mockReturnValue(false)
  Object.defineProperty(window, 'confirm', { configurable: true, value: confirm })
  button('放弃编辑并取消报价').click(); await nextTick()
  expect(api.post).not.toHaveBeenCalled()
  confirm.mockReturnValue(true)
  vi.mocked(api.post).mockResolvedValue({ cancelled: true })
  button('放弃编辑并取消报价').click()
  button('放弃编辑并取消报价').click()
  await vi.waitFor(() => expect(api.post).toHaveBeenCalledWith('/quotations/original/cancel', { _version: 8, draftVersion: 7 }, 'cancel:original:8:7'))
  expect(api.post).toHaveBeenCalledTimes(1)
  await vi.waitFor(() => expect(host.textContent).not.toContain('撤回重新编辑 · 报价 QT-OLD'))
})
it('refuses to overwrite a withdrawn draft through the reissue dialog', async () => {
  await mount(true, true)
  button('载入并再次发起').click()
  await vi.waitFor(() => expect(host.textContent).toContain('再次发起不会覆盖它'))
  expect(api.put).not.toHaveBeenCalled()
})
