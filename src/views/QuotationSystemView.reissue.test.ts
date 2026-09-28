// @vitest-environment happy-dom
import { createApp, nextTick, type App } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import View from './QuotationSystemView.vue'
import { onBeforeRouteLeave } from 'vue-router'
import { api } from '@/services/http'
import { clearFinanceSettingsCache } from '@/services/financeSettings'
import { loadPublishedLogisticsRules } from '@/data/publishedLogisticsRepository'
import type { QuotationMatrixRow } from '@/components/quotation/types'
import { authState } from '@/data/authStore'
import { loadLastQuotationCustomer, rememberQuotationCustomer } from '@/data/lastQuotationCustomer'

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
let state: { flushDraft: () => Promise<void>; persistQuotation: (input: never) => Promise<unknown>; beforeWindowUnload: (event: BeforeUnloadEvent) => void; draftVersion: number; draftReady: boolean; draftStatus: string; reissueSource: string; customerName: string; products: Array<{ sku: string; purchase: number }>; modeSelections: { common: unknown[] }; searchChannelCountries: (query: string) => Promise<string[]>;
  quoteMatrixMode: 'common' | 'specified' | 'template'; logisticsLoadState: string; commonQuoteRows: QuotationMatrixRow[]; specifiedQuoteRows: QuotationMatrixRow[] }
let purchaseFailure = false
let sourceFailure = false
beforeEach(() => {
  clearFinanceSettingsCache(); vi.clearAllMocks(); localStorage.clear()
  authState.current = { id: 'employee-a', account: 'A', name: '业务员 A', role: 'employee', status: 'enabled', mustChangePassword: false, passwordUpdatedAt: '' }
  purchaseFailure = false; sourceFailure = false; router.query = { reissue: 'original', release: 'local' }
})
afterEach(() => { app?.unmount(); host?.remove(); clearFinanceSettingsCache(); vi.restoreAllMocks(); localStorage.clear(); authState.current = null })
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
  vi.spyOn(api, 'delete').mockRejectedValue(new Error('Unexpected draft deletion'))
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

it('reissues in memory, reads current purchase data and removes unavailable source channels without creating a record', async () => {
  rememberQuotationCustomer('employee-a', { name: '上次提交客户', selectedCustomerId: '' })
  await mount(false)
  await vi.waitFor(() => expect(state.reissueSource).toBe('QT-OLD'))
  expect(state.customerName).toBe('原客户')
  expect(state.quoteMatrixMode).toBe('common')
  // Current zero-tax-point purchase pricing adds the configured 1%, rather than copying the old 999.
  expect(state.products[0]).toMatchObject({ sku: 'BK100', purchase: 12.12 })
  expect(state.modeSelections.common).toEqual([])
  expect(loadPublishedLogisticsRules).toHaveBeenCalledWith(expect.objectContaining({ countries: expect.arrayContaining(['日本']) }), expect.anything())
  expect(router.replace).toHaveBeenCalledWith({ query: { release: 'local' } })
  expect(api.post).not.toHaveBeenCalled(); expect(api.patch).not.toHaveBeenCalled()
  await new Promise(resolve => setTimeout(resolve, 900))
  expect(api.put).not.toHaveBeenCalled()
})

it('ignores an old ordinary server draft when reissuing and never writes or deletes it', async () => {
  await mount(true)
  await vi.waitFor(() => expect(state.reissueSource).toBe('QT-OLD'))
  expect(state.customerName).toBe('原客户')
  expect(state.draftVersion).toBe(-1)
  expect(host.textContent).not.toContain('保留当前草稿')
  await state.flushDraft()
  expect(api.put).not.toHaveBeenCalled()
  expect(api.delete).not.toHaveBeenCalled()
})

it('leaves the workspace empty and allows retry if reissue purchase loading fails', async () => {
  purchaseFailure = true
  await mount(true)
  await vi.waitFor(() => expect(host.textContent).toContain('采购资料读取失败'))
  expect(state.customerName).toBe('')
  expect(api.put).not.toHaveBeenCalled()
  purchaseFailure = false
  button('载入并再次发起').click()
  await vi.waitFor(() => expect(state.reissueSource).toBe('QT-OLD'))
})

it('keeps ordinary edits local, warns before leaving, and clears without server draft requests', async () => {
  router.query = { release: 'local' }
  await mount(true)
  expect(state.customerName).toBe('')
  const leave = vi.mocked(onBeforeRouteLeave).mock.calls.at(-1)![0]
  expect(await leave.call(undefined as never, {} as never, {} as never, vi.fn())).toBe(true)
  state.customerName = '未保存客户'; await nextTick()
  await new Promise(resolve => setTimeout(resolve, 900))
  expect(api.put).not.toHaveBeenCalled()
  const event = { preventDefault: vi.fn(), returnValue: undefined } as unknown as BeforeUnloadEvent
  state.beforeWindowUnload(event)
  expect(event.preventDefault).toHaveBeenCalledOnce()
  const cancelled = leave.call(undefined as never, {} as never, {} as never, vi.fn())
  await nextTick()
  expect(host.textContent).toContain('报价尚未保存，是否离开？')
  expect(button('重试并离开')).toBeUndefined()
  button('继续编辑').click()
  expect(await cancelled).toBe(false)
  expect(state.customerName).toBe('未保存客户')
  const discarded = leave.call(undefined as never, {} as never, {} as never, vi.fn())
  await nextTick(); button('放弃未保存修改').click()
  expect(await discarded).toBe(true)
  expect(api.put).not.toHaveBeenCalled(); expect(api.delete).not.toHaveBeenCalled()
  state.customerName = '新的输入'; await nextTick()
  state.quoteMatrixMode = 'specified'; await nextTick()
  button('清空重新开始').click()
  await vi.waitFor(() => expect(state.customerName).toBe(''))
  expect(state.quoteMatrixMode).toBe('template')
  expect(api.delete).not.toHaveBeenCalled()
})

it('submits ordinary quotes directly without waiting for or creating a server draft', async () => {
  router.query = { release: 'local' }
  await mount(true)
  state.customerName = '正式报价'; await nextTick()
  vi.mocked(api.post).mockResolvedValue({ id: 'new-quote' })
  await state.persistQuotation({ customerName: '正式报价' } as never)
  expect(api.post).toHaveBeenCalledWith('/quotations', { customerName: '正式报价' }, expect.any(String))
  expect(api.put).not.toHaveBeenCalled(); expect(api.delete).not.toHaveBeenCalled()
})

it('starts empty again after reopening and stops warning when ordinary input is reverted', async () => {
  router.query = { release: 'local' }
  await mount(true)
  expect(state.quoteMatrixMode).toBe('template')
  state.customerName = '临时客户'; await nextTick()
  state.customerName = ''; await nextTick()
  const leave = vi.mocked(onBeforeRouteLeave).mock.calls.at(-1)![0]
  expect(await leave.call(undefined as never, {} as never, {} as never, vi.fn())).toBe(true)
  const event = { preventDefault: vi.fn() } as unknown as BeforeUnloadEvent
  state.beforeWindowUnload(event)
  expect(event.preventDefault).not.toHaveBeenCalled()
  state.customerName = '刷新前输入'; await nextTick()
  app.unmount(); host.remove()
  await mount(true)
  expect(state.customerName).toBe('')
  expect(state.draftVersion).toBe(-1)
  expect(state.quoteMatrixMode).toBe('template')
  expect(api.put).not.toHaveBeenCalled(); expect(api.delete).not.toHaveBeenCalled()
})

it('still autosaves edits to a withdrawn quotation with its original source and version', async () => {
  rememberQuotationCustomer('employee-a', { name: '上次提交客户', selectedCustomerId: '' })
  router.query = { release: 'local' }
  await mount(true, true)
  expect(state.customerName).toBe('未完成客户')
  state.customerName = '撤回后修改'; await nextTick()
  await vi.waitFor(() => expect(api.put).toHaveBeenCalledWith('/quotation-drafts/mine/state',
    expect.objectContaining({ customerName: '撤回后修改' }), { 'If-Match': '7', 'X-Quotation-Source': 'original' }), { timeout: 2000 })
})

it('remembers only successful submissions when returning to the quotation page', async () => {
  router.query = {}
  await mount(false)
  state.customerName = '已提交客户'
  vi.mocked(api.post).mockResolvedValue({ id: 'saved', no: 'QT-SAVED' })
  await state.persistQuotation({ customerName: '已提交客户' } as never)
  expect(loadLastQuotationCustomer('employee-a')).toEqual({ name: '已提交客户', selectedCustomerId: '' })
  state.customerName = '未提交修改'
  app.unmount(); host.remove()
  await mount(false)
  expect(state.customerName).toBe('已提交客户')
  expect(state.products[0].sku).toBe('')
  const leave = vi.mocked(onBeforeRouteLeave).mock.calls.at(-1)![0]
  expect(await leave.call(undefined as never, {} as never, {} as never, vi.fn())).toBe(true)
  vi.mocked(api.post).mockRejectedValue(new Error('保存失败'))
  await expect(state.persistQuotation({ customerName: '失败客户' } as never)).rejects.toThrow('保存失败')
  expect(loadLastQuotationCustomer('employee-a')?.name).toBe('已提交客户')
})

it('keeps remembered customers separate for each signed-in account', async () => {
  rememberQuotationCustomer('employee-b', { name: 'B 的客户', selectedCustomerId: '' })
  router.query = {}
  await mount(false)
  expect(state.customerName).toBe('')
  vi.mocked(api.post).mockResolvedValue({ id: 'saved', no: 'QT-SAVED' })
  await state.persistQuotation({ customerName: 'A 的客户' } as never)
  expect(loadLastQuotationCustomer('employee-a')?.name).toBe('A 的客户')
  expect(loadLastQuotationCustomer('employee-b')?.name).toBe('B 的客户')
})

it('does not fail a completed quotation when browser storage is unavailable', async () => {
  router.query = {}
  await mount(false)
  vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('Storage unavailable') })
  vi.mocked(api.post).mockResolvedValue({ id: 'saved', no: 'QT-SAVED' })
  await expect(state.persistQuotation({ customerName: '成功客户' } as never)).resolves.toMatchObject({ id: 'saved', no: 'QT-SAVED' })
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
