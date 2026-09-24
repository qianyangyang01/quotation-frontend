// @vitest-environment happy-dom
import { createApp, nextTick, type App } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import QuotationSystemView from './QuotationSystemView.vue'
import type { QuotationProduct } from '@/components/quotation/types'
import { api } from '@/services/http'
import { clearFinanceSettingsCache, financeSettingVersions, hydrateFinanceSettings } from '@/services/financeSettings'
import { startQuotationSync } from '@/services/quotationSync'
import type { FinanceChannelPolicy, FinanceCountrySetting } from '@/data/financeChannelPolicies'
import type { FinanceTaxSettings } from '@/data/financeTaxSettings'
import type { FinanceSurchargeSettings } from '@/data/financeSurchargeSettings'
import { loadPublishedLogisticsRules } from '@/data/publishedLogisticsRepository'
import type { QuotationDraftPayload } from '@/services/quotationDrafts'
import type { PurchaseProductRecord } from '@/data/purchaseStore'
import { replaceLogisticsRules, type LogisticsRule, type LogisticsPriceRow } from '@/data/logistics'
import type { QuotationMatrixRow } from '@/components/quotation/types'
import type { QuoteSheetSourceRow } from '@/data/customerQuoteSheet'

vi.mock('vue-router', () => ({ useRoute: () => ({ query: {} }), useRouter: () => ({ replace: vi.fn().mockResolvedValue(undefined) }), onBeforeRouteLeave: vi.fn() }))
vi.mock('@/data/authStore', () => ({ currentAuthUser: { value: { name: '员工', account: 'employee', role: 'employee', permissions: ['quotation'] } } }))
vi.mock('@/services/quotationSync', async importOriginal => ({
  ...await importOriginal<typeof import('@/services/quotationSync')>(),
  startQuotationSync: vi.fn(() => vi.fn()),
}))
vi.mock('@/data/publishedLogisticsRepository', async importOriginal => ({
  ...await importOriginal<typeof import('@/data/publishedLogisticsRepository')>(),
  loadPublishedLogisticsManifest: vi.fn().mockResolvedValue({ verified: true }),
  loadPublishedLogisticsRules: vi.fn().mockResolvedValue({ revision:'r1', verified:true, rules:[] }),
}))

function financeResponse(disableS = false) {
  return {
    'country-classification': { value: [{ country: '美国', code: 'US', enabled: true, stage: 'rare', sortOrder: 21 }], _version: 1 },
    'channel-policies': { value: [{ id: '香氛', category: '香氛', enabled: true, countryRules: [{ country: '美国', stage: 'rare', allowedChannels: ['1::物流商::CHANNEL'] }] }], _version: 1 },
    'customer-grades': { value: [
      { grade: 'S', coefficient: 1.21605, enabled: !disableS },
      { grade: 'A', coefficient: 1.23615, enabled: true },
      { grade: 'NEW', coefficient: 1.21605, enabled: true },
    ], _version: 2 },
    'exchange-rate': { value: { usdCny: 6.7, eurUsd: 1.17, updatedAt: '财务维护' }, _version: 2 },
    'tax-settings': { value: { countries: [{ country: '美国', selected: true, enabled: true, fixedFeeUsd: 2, sortOrder: 1 }], providers: [{ provider: '物流商', selected: true, mode: 'taxable', channels: [] }], updatedAt: 'test' }, _version: 1 },
    'surcharge-settings': { value: { countries: [{ country: '美国', selected: true, enabled: true, fixedFeeUsd: 3, sortOrder: 1 }], providers: [{ provider: '物流商', selected: true, mode: 'taxable', channels: [] }], updatedAt: 'test' }, _version: 1 },
    'customer-operation-fees': { value: { customers: [{ id: 'bk', name: 'BK', feeUsd: 0.4, enabled: true }] }, _version: 1 },
  }
}

type PricingState = {
  ensureQuoteLogistics: (product: QuotationProduct) => Promise<void>
  normalizeRule: (product: QuotationProduct, silent?: boolean) => void
  applyDraftPayload: (payload: QuotationDraftPayload, purchases?: Map<string, PurchaseProductRecord | undefined>, options?: { restoreQuotation?: boolean }) => Promise<void>
  salePrice: (product: QuotationProduct) => number
  usdPriceFromCny: (cny: number) => number
  exchange: { usd: number; eurUsd: number }
  draftReady: boolean
  draftNeedsQuery: boolean
  queryProduct: () => Promise<void>
  queryBundleItems: () => Promise<void>
  changeLogisticsAttribute: (product: QuotationProduct, value: string) => Promise<void>
  bundleItems: Array<{ sku: string; quantityPerSet: number; customWeightKg: number | null }>
  showSaveValidation: boolean
  saveValidationIssues: Array<{ key: string; message: string }>
  customerName: string
  skuSearch: string
  customQuoteQuantity: number
  syncPending: string
  syncError: string
  draftPayload: () => unknown
  modeSelections: Record<string, Array<{ channelKey?: string }>>
  savedQuoteRows: QuotationMatrixRow[]
  quoteSheetPrice: (product: QuotationProduct, row: QuoteSheetSourceRow, quantity: number) => number | null
  financePolicies: FinanceChannelPolicy[]
  financeCountrySettings: FinanceCountrySetting[]
  financeTaxSettings: FinanceTaxSettings
  financeSurchargeSettings: FinanceSurchargeSettings
  quotationAttributeOptions: string[]
  products: QuotationProduct[]
  conditionIssues: (options: { includeSku: boolean; includeCategory: boolean }) => Array<{ key: string; message: string }>
  selectFinanceCustomer: (id: string) => void
  taxResult: (country: string, provider: string, cny: number, rule: string, channel: string, weight: number, quantity: number) => { configured: boolean; taxUsd: number; surchargeUsd: number; totalUsd: number }
}

describe('quotation finance initialization for an employee', () => {
  let app: App | undefined
  let host: HTMLDivElement
  let state: PricingState
  let resolveFinance: (value: ReturnType<typeof financeResponse>) => void
  let rejectFinance: (error: Error) => void

  beforeEach(() => {
    vi.clearAllMocks()
    clearFinanceSettingsCache()
    host = document.createElement('div')
    document.body.append(host)
  })
  afterEach(() => {
    app?.unmount()
    app = undefined
    host.remove()
    vi.restoreAllMocks()
    clearFinanceSettingsCache()
    replaceLogisticsRules([])
  })

  async function mountPage(options: { grade?: string; attribute?: string; warm?: boolean; disableS?: boolean; draft?: QuotationDraftPayload } = {}) {
    const finance = new Promise<ReturnType<typeof financeResponse>>((resolve, reject) => {
      resolveFinance = resolve
      rejectFinance = reject
    })
    let financeReads = 0
    vi.spyOn(api, 'get').mockImplementation(async path => {
      if (path === '/finance-settings') {
        if (options.warm && financeReads++ === 0) {
          const stale = financeResponse()
          stale['customer-grades'].value[0]!.coefficient = 1.12
          stale['exchange-rate'].value.usdCny = 6.75
          return stale
        }
        return finance
      }
      if (path === '/quotation-templates') return []
      if (path === '/quotation-readiness') return { ready: true, missing: [] }
      if (path === '/quotation-drafts/mine/state' && options.draft) return {exists:true, version:3, payload:options.draft}
      if (path === '/quotation-drafts/mine/state') return options.grade || options.attribute
        ? { exists: true, version: 1, payload: { schemaVersion: 2, selectedCustomerGrade: options.grade, logisticsAttribute: options.attribute, quoteMode: 'single' } }
        : { exists: false, version: -1, payload: null }
      throw new Error(`Unexpected request: ${path}`)
    })
    if (options.warm) {
      await hydrateFinanceSettings()
    }
    app = createApp(QuotationSystemView)
    const vm = app.mount(host)
    // Exercise the mounted page's real calculation functions and reactive state.
    state = (vm.$ as unknown as { setupState: PricingState }).setupState
    expect(state.draftReady).toBe(false)
  }

  function gradeField() { return host.querySelector<HTMLElement>('[data-validation-field="customerGrade"]')! }
  function attributeSelect() { return host.querySelector<HTMLSelectElement>('[data-validation-field="logisticsAttribute"] select')! }
  function selectableAttributes() { return Array.from(attributeSelect().options).filter(option => !option.disabled).map(option => option.value) }
  function expectHiddenCoefficient(coefficient: number) {
    expect(host.textContent).not.toContain('报价系数')
    expect(host.innerHTML).not.toContain(coefficient.toString())
    expect(state.salePrice({ purchase: 80, purchaseFreightPerUnit: 5, freight: 15 } as QuotationProduct)).toBeCloseTo(100 * coefficient, 10)
  }

  it.each(['common', 'specified', 'template'] as const)('restores only conditions for a %s draft and leaves routes unselected after explicit query', async mode => {
    const old = [{country:'美国', channelKey:'1::物流商::CHANNEL', rule:'旧规则', carrier:'物流商', transport:'旧渠道'}]
    const payload = {schemaVersion:2, quoteMode:'single', quoteMatrixMode:mode, skuSearch:'RESTORE', logisticsAttribute:'香氛',
      customerName:'原客户', selectedCustomerGrade:'S', monthlySalesEstimate:'100', commissionThreshold:0.95,
      customQuoteQuantity:6, specialPackagingGrams:3, product:{sku:'RESTORE',weightSource:'manual',manualWeight:0.4},
      commonSelections:old,specifiedSelections:old,templateSelections:old,activeTemplate:{id:'old',name:'旧模板'}} as QuotationDraftPayload
    await mountPage({draft:payload}); resolveFinance(financeResponse())
    await vi.waitFor(() => expect(state.draftReady).toBe(true))
    expect(state.customerName).toBe('原客户'); expect(state.skuSearch).toBe('RESTORE')
    expect(state.customQuoteQuantity).toBe(6); expect(state.draftNeedsQuery).toBe(true)
    expect(state.products[0]).toMatchObject({logisticsAttribute:'香氛',weightSource:'manual',manualWeight:0.4})
    expect(loadPublishedLogisticsRules).not.toHaveBeenCalled()
    expect(vi.mocked(api.get).mock.calls.some(([path]) => path.startsWith('/purchase-products/'))).toBe(false)
    expect(state.savedQuoteRows).toEqual([])
    expect(host.querySelector('.matrix-workbench')).toBeNull()
    expect(host.textContent).toContain('已恢复报价条件。点击“查询商品”')
    // Changing an input before Query must not restore/generate any routes either.
    await state.changeLogisticsAttribute(state.products[0]!, '香氛')
    expect(loadPublishedLogisticsRules).not.toHaveBeenCalled()
    const originalGet = vi.mocked(api.get).getMockImplementation()!
    vi.mocked(api.get).mockImplementation(async (path,...args) => path === '/purchase-products/RESTORE'
      ? {sku:'RESTORE',category:'日用品',catalogState:'ready',weightG:50,minOrderQty:1,purchasePriceCny:12,singleFreightCny:0,taxPoint:0}
      : originalGet(path,...args))
    const price = {areaName:'美国',countryCode:'US',etaMinDays:6,etaMaxDays:12,weightFromKg:0,weightToKg:10,pricePerKg:20,
      registrationFee:5,allowedMarks:'',prohibitedMarks:'',minChargeWeightKg:0,startWeightKg:0,firstWeightKg:0,firstWeightPrice:0,
      nextWeightKg:0,nextWeightPrice:0,intervalPrice:0,surcharge:0,fuelSurchargeRate:0} as LogisticsPriceRow
    const rules = [{id:1,name:'最新渠道',status:'启用',prices:[price],relations:[{carrier:'物流商',channel:'最新渠道',channelCode:'CHANNEL',discounts:''}]} as LogisticsRule]
    vi.mocked(loadPublishedLogisticsRules).mockImplementationOnce(async () => { replaceLogisticsRules(rules); return {revision:'r1',verified:true,rules,source:'network'} })
    const query = [...host.querySelectorAll('button')].find(button => button.textContent?.trim() === '查询商品')!
    expect(query).toBeDefined(); query.click()
    await vi.waitFor(() => expect(loadPublishedLogisticsRules).toHaveBeenCalledTimes(1))
    await vi.waitFor(() => expect(state.draftNeedsQuery).toBe(false))
    expect(host.querySelector('.matrix-workbench')).not.toBeNull()
    expect(state.products[0]!.manualWeight).toBe(0.4)
    expect(state.savedQuoteRows).toEqual([])
    expect(Object.values(state.modeSelections).flat()).toEqual([])
    expect(JSON.stringify(state.draftPayload())).not.toContain('旧模板')
  })

  it('restores bundle SKU conditions without purchase or logistics requests', async () => {
    await mountPage({draft:{schemaVersion:2,quoteMode:'bundle',logisticsAttribute:'香氛',customerName:'组合客户',
      bundleItems:[{sku:'BUNDLE1',quantityPerSet:2,customWeightKg:0.3}],commonSelections:[{country:'美国',channelKey:'old'}]} as QuotationDraftPayload})
    resolveFinance(financeResponse()); await vi.waitFor(() => expect(state.draftReady).toBe(true))
    expect(state.bundleItems[0]).toMatchObject({sku:'BUNDLE1',quantityPerSet:2,customWeightKg:0.3})
    expect(state.savedQuoteRows).toEqual([])
    expect(loadPublishedLogisticsRules).not.toHaveBeenCalled()
    expect(vi.mocked(api.get).mock.calls.some(([path]) => path.startsWith('/purchase-products/'))).toBe(false)
    expect(host.querySelector('.matrix-workbench')).toBeNull()
    const originalGet = vi.mocked(api.get).getMockImplementation()!
    vi.mocked(api.get).mockImplementation(async (path,...args) => path === '/purchase-products/BUNDLE1'
      ? {sku:'BUNDLE1',category:'日用品',catalogState:'ready',weightG:50,minOrderQty:1,purchasePriceCny:12,singleFreightCny:0,taxPoint:0}
      : originalGet(path,...args))
    await state.queryBundleItems()
    await vi.waitFor(() => expect(loadPublishedLogisticsRules).toHaveBeenCalledTimes(1))
    expect(state.draftNeedsQuery).toBe(false)
    expect(state.bundleItems[0]).toMatchObject({sku:'BUNDLE1',quantityPerSet:2,customWeightKg:0.3})
    expect(state.savedQuoteRows).toEqual([])
  })

  it('keeps a restored draft waiting for Query after purchase lookup fails', async () => {
    await mountPage({draft:{schemaVersion:2,quoteMode:'single',logisticsAttribute:'香氛',customerName:'客户',skuSearch:'FAIL',
      product:{sku:'FAIL'},commonSelections:[{country:'美国',channelKey:'old'}]} as QuotationDraftPayload})
    resolveFinance(financeResponse()); await vi.waitFor(() => expect(state.draftReady).toBe(true))
    await state.queryProduct()
    expect(state.draftNeedsQuery).toBe(true)
    expect(loadPublishedLogisticsRules).not.toHaveBeenCalled()
    expect(state.savedQuoteRows).toEqual([])
    expect(host.querySelector('.matrix-workbench')).toBeNull()
  })

  it.each(['common', 'specified', 'template'] as const)('removes unauthorized restored channels from %s after loading, including preview and the next draft', async mode => {
    await mountPage()
    resolveFinance(financeResponse())
    await vi.waitFor(() => expect(state.draftReady).toBe(true))
    const price = { areaName:'美国', countryCode:'US', etaMinDays:6, etaMaxDays:12,
      weightFromKg:0, weightToKg:10, pricePerKg:20, registrationFee:5, allowedMarks:'', prohibitedMarks:'',
      minChargeWeightKg:0, startWeightKg:0, firstWeightKg:0, firstWeightPrice:0, nextWeightKg:0, nextWeightPrice:0,
      intervalPrice:0, surcharge:0, fuelSurchargeRate:0 } as LogisticsPriceRow
    const rules = [1,2].map(id => ({ id, name:'同名规则', status:'启用', prices:[price],
      relations:[{carrier:'物流商', channel:'同名渠道', channelCode:id === 1 ? 'CHANNEL' : 'OLD', discounts:''}] } as LogisticsRule))
    let finish!: () => void
    vi.mocked(loadPublishedLogisticsRules).mockImplementationOnce(() => new Promise(resolve => { finish = () => {
      replaceLogisticsRules(rules); resolve({ revision:'r1', verified:true, rules, source:'network' })
    } }))
    const selected = [1,2].map(id => ({ country:'美国', channelKey:`${id}::物流商::${id === 1 ? 'CHANNEL' : 'OLD'}`, rule:'同名规则', carrier:'物流商', transport:'同名渠道' }))
    const purchase = { sku:'RESTORE', category:'宠物用品', productName:'宠物用品', purchasePriceCny:10, weightKg:0.21,
      quoteReady:true, status:'资料完整', taxPoint:0, domesticFreight:0.2, priceTiers:[] } as unknown as PurchaseProductRecord
    const payload = { schemaVersion:2, quoteMode:'single', quoteMatrixMode:mode, skuSearch:'RESTORE', logisticsAttribute:'香氛',
      product:{sku:'RESTORE'}, customerName:'客户', commonSelections:selected, specifiedSelections:selected, templateSelections:selected } as QuotationDraftPayload
    const restoring = state.applyDraftPayload(payload, new Map([['RESTORE', purchase]]), { restoreQuotation: true })
    await vi.waitFor(() => expect(finish).toBeDefined())
    expect(state.modeSelections[mode]).toHaveLength(2)
    finish(); await restoring
    await vi.waitFor(() => expect(state.savedQuoteRows.map(row => row.channelKey)).toEqual(['1::物流商::CHANNEL']))
    for (const selections of Object.values(state.modeSelections)) expect(selections.map(row => row.channelKey)).toEqual(['1::物流商::CHANNEL'])
    expect(JSON.stringify(state.draftPayload())).not.toContain('2::物流商::OLD')
    expect(host.textContent).toContain('已按当前物流属性“香氛”移除')
    const good = state.savedQuoteRows[0]!
    expect(state.quoteSheetPrice(state.products[0]!, good, 5)).toEqual(expect.any(Number))
    expect(state.quoteSheetPrice(state.products[0]!, { ...good, channelKey:'2::物流商::OLD', ruleId:2 }, 5)).toBeNull()
    expect(state.quoteSheetPrice(state.products[0]!, { ...good, available:false }, 5)).toBeNull()
  })

  it.each(['failed', 'unverified'] as const)('retains draft channels when logistics is %s, and prunes only after a verified retry', async outcome => {
    await mountPage(); resolveFinance(financeResponse())
    await vi.waitFor(() => expect(state.draftReady).toBe(true))
    if (outcome === 'failed') vi.mocked(loadPublishedLogisticsRules).mockRejectedValueOnce(new Error('网络失败'))
    else vi.mocked(loadPublishedLogisticsRules).mockResolvedValueOnce({revision:'r1', verified:false, rules:[], source:'network'})
    const purchase = { sku:'RESTORE', category:'宠物用品', productName:'宠物用品', purchasePriceCny:10, weightKg:0.21,
      quoteReady:true, status:'资料完整', taxPoint:0, domesticFreight:0.2, priceTiers:[] } as unknown as PurchaseProductRecord
    const selection = {country:'美国', channelKey:'2::物流商::OLD', rule:'旧规则', carrier:'物流商', transport:'旧渠道'}
    await state.applyDraftPayload({schemaVersion:2, quoteMode:'single', quoteMatrixMode:'common', logisticsAttribute:'香氛',
      product:{sku:'RESTORE'}, commonSelections:[selection]} as QuotationDraftPayload, new Map([['RESTORE',purchase]]), { restoreQuotation: true })
    expect(state.modeSelections.common).toEqual([selection])
    expect(host.textContent).not.toContain('已按当前物流属性')
    await state.ensureQuoteLogistics(state.products[0]!)
    await vi.waitFor(() => expect(state.modeSelections.common).toEqual([]))
    expect(state.savedQuoteRows).toEqual([])
  })

  it('reuses hydrated finance on attribute loads without flashing authorization errors and still refreshes known changes', async () => {
    await mountPage()
    resolveFinance(financeResponse())
    await vi.waitFor(() => expect(state.draftReady).toBe(true))
    const beforeReads = vi.mocked(api.get).mock.calls.filter(([path]) => path === '/finance-settings').length
    let finish!: (value: Awaited<ReturnType<typeof loadPublishedLogisticsRules>>) => void
    vi.mocked(loadPublishedLogisticsRules).mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
    const loading = state.ensureQuoteLogistics(state.products[0])
    await vi.waitFor(() => expect(finish).toBeDefined())
    state.normalizeRule(state.products[0], true)
    await nextTick()
    expect(selectableAttributes()).toContain('香氛')
    expect(attributeSelect().textContent).not.toContain('暂无财务授权')
    expect(state.products[0].status).toContain('正在加载')
    expect(vi.mocked(api.get).mock.calls.filter(([path]) => path === '/finance-settings')).toHaveLength(beforeReads)
    finish({ revision:'r1', verified:true, rules:[], source:'network' })
    await loading
    state.syncPending = '财务设置已变化'
    await state.ensureQuoteLogistics(state.products[0])
    expect(vi.mocked(api.get).mock.calls.filter(([path]) => path === '/finance-settings')).toHaveLength(beforeReads + 1)
  })

  it('updates the rendered product status after asynchronously restoring a draft', async () => {
    await mountPage()
    resolveFinance(financeResponse())
    await vi.waitFor(() => expect(state.draftReady).toBe(true))
    let finish!: (value: Awaited<ReturnType<typeof loadPublishedLogisticsRules>>) => void
    vi.mocked(loadPublishedLogisticsRules).mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
    const purchase = { sku:'RESTORE', category:'宠物用品', productName:'宠物用品', purchasePriceCny:10, weightKg:0.21,
      quoteReady:true, status:'资料完整', taxPoint:0, domesticFreight:0.2, priceTiers:[] } as unknown as PurchaseProductRecord
    const payload = { schemaVersion:2, quoteMode:'single', skuSearch:'RESTORE', logisticsAttribute:'香氛',
      product:{sku:'RESTORE'}, customerName:'客户' } as QuotationDraftPayload
    const restoring = state.applyDraftPayload(payload, new Map([['RESTORE', purchase]]), { restoreQuotation: true })
    await vi.waitFor(() => expect(finish).toBeDefined())
    expect(host.querySelector('.product-card .state')?.textContent).toContain('正在加载')
    finish({ revision:'r1', verified:true, rules:[], source:'network' })
    await restoring; await nextTick()
    expect(state.products[0].status).toBe('当前条件没有已发布物流渠道')
    expect(host.querySelector('.product-card .state')?.textContent).toContain('当前条件没有已发布物流渠道')
    expect(host.querySelector('.product-card .state')?.textContent).not.toContain('正在加载')
  })

  it('rechecks a transient sync failure without writing or reloading the draft or recalculating the quote', async () => {
    await mountPage()
    resolveFinance(financeResponse())
    await vi.waitFor(() => expect(state.draftReady).toBe(true))
    state.customerName = '保留当前客户'
    state.customQuoteQuantity = 7
    state.syncError = '网络短暂不可用'
    const before = JSON.stringify(state.draftPayload())
    const originalGet = vi.mocked(api.get).getMockImplementation()!
    vi.mocked(api.get).mockImplementation(async (path, ...args) => {
      if (path.startsWith('/quotation-sync')) return { purchaseVersions: {}, logisticsRevision: '', financeVersions: financeSettingVersions() }
      return originalGet(path, ...args)
    })
    const writeDraft = vi.spyOn(api, 'put').mockRejectedValue(new Error('本次重试不应写草稿'))
    const draftReads = vi.mocked(api.get).mock.calls.filter(([path]) => path === '/quotation-drafts/mine/state').length
    await nextTick()
    const retry = host.querySelector<HTMLButtonElement>('.live-data-notice button')!
    expect(retry.textContent).toBe('重试核验')
    retry.click()
    await vi.waitFor(() => expect(state.syncError).toBe(''))
    expect(state.syncPending).toBe('')
    expect(writeDraft).not.toHaveBeenCalled()
    expect(JSON.stringify(state.draftPayload())).toBe(before)
    expect(vi.mocked(api.get).mock.calls.filter(([path]) => path === '/quotation-drafts/mine/state')).toHaveLength(draftReads)
  })

  it.each([false, true])('recovers finance through the rendered retry action while preserving current input; changed settings %s', async changed => {
    await mountPage()
    resolveFinance(financeResponse())
    await vi.waitFor(() => expect(state.draftReady).toBe(true))
    state.customerName = '保留当前客户'
    state.skuSearch = 'UNSAVED-SKU'
    state.customQuoteQuantity = 7
    state.showSaveValidation = true
    const before = JSON.stringify(state.draftPayload())
    const originalGet = vi.mocked(api.get).getMockImplementation()!
    vi.mocked(api.get).mockImplementationOnce(async () => { throw new Error('财务读取超时') })
    await expect(hydrateFinanceSettings({ force: true })).rejects.toThrow('财务读取超时')
    await nextTick()
    expect(host.textContent).toContain('财务设置读取失败：财务读取超时')
    const latest = financeResponse()
    const versions = financeSettingVersions()
    if (changed) {
      latest['exchange-rate']._version = 3
      latest['exchange-rate'].value.usdCny = 7.1
      versions['exchange-rate'] = 3
    }
    const draftReads = vi.mocked(api.get).mock.calls.filter(([path]) => path === '/quotation-drafts/mine/state').length
    vi.mocked(api.get).mockImplementation(async (path, ...args) => {
      if (path === '/finance-settings') return latest
      if (path.startsWith('/quotation-sync')) return { purchaseVersions: {}, logisticsRevision: '', financeVersions: versions }
      return originalGet(path, ...args)
    })
    const retry = [...host.querySelectorAll<HTMLButtonElement>('.validation-summary button')].find(button => button.textContent?.includes('重试读取'))!
    expect(retry).toBeDefined()
    retry.click()
    await vi.waitFor(() => expect(state.saveValidationIssues.some(issue => issue.key === 'financeSettings')).toBe(false))
    await vi.waitFor(() => expect(host.textContent).not.toContain('正在重新检查'))
    expect(JSON.stringify(state.draftPayload())).toBe(before)
    expect(vi.mocked(api.get).mock.calls.filter(([path]) => path === '/quotation-drafts/mine/state')).toHaveLength(draftReads)
    expect(state.exchange.usd).toBe(6.7)
    expect(state.syncPending).toBe(changed ? '汇率' : '')
    if (changed) expect(state.saveValidationIssues.some(issue => issue.key === 'liveData')).toBe(true)
  })

  it('reports a readiness initialization error as a workspace problem rather than missing finance', async () => {
    await mountPage()
    const originalGet = vi.mocked(api.get).getMockImplementation()!
    vi.mocked(api.get).mockImplementation(async (path, ...args) => {
      if (path === '/quotation-readiness') throw new Error('业务就绪检查超时')
      return originalGet(path, ...args)
    })
    resolveFinance(financeResponse())
    await vi.waitFor(() => expect(state.saveValidationIssues).toContainEqual({ key: 'workspaceInitialization', label: '报价工作区', message: '业务就绪检查超时' }))
    expect(state.saveValidationIssues.some(issue => issue.key === 'financeSettings')).toBe(false)
    state.showSaveValidation = true
    await nextTick()
    expect(host.querySelector('.validation-summary')?.textContent).toContain('查看错误')
  })

  it.each([false, true])('uses the saved precise coefficient and exchange rates with warm cache %s', async warm => {
    await mountPage({ warm })
    resolveFinance(financeResponse())
    await vi.waitFor(() => expect(state.draftReady).toBe(true))
    expectHiddenCoefficient(1.21605)
    expect(host.textContent).toContain('财务汇率 6.7')
    expect(state.exchange.eurUsd).toBe(1.17)
    const product = { purchase: 80, purchaseFreightPerUnit: 5, freight: 15 } as QuotationProduct
    expect(state.salePrice(product)).toBe(121.605)
    expect(state.usdPriceFromCny(state.salePrice(product))).toBeCloseTo(121.61 / 6.7, 10)

    const select = gradeField().querySelector('select')!
    select.value = 'A'
    select.dispatchEvent(new Event('change'))
    await nextTick()
    expectHiddenCoefficient(1.23615)
    expect(state.salePrice(product)).toBe(123.615)
  })

  it.each(['NEW', 'A'])('restores saved grade %s after server settings have loaded', async grade => {
    await mountPage({ grade })
    resolveFinance(financeResponse())
    await vi.waitFor(() => expect(state.draftReady).toBe(true))
    expect(gradeField().querySelector('select')!.value).toBe(grade)
    expectHiddenCoefficient(grade === 'A' ? 1.23615 : 1.21605)
  })

  it('applies country, channel, tax, surcharge and customer fee settings to the actual quotation calculation', async () => {
    await mountPage()
    resolveFinance(financeResponse())
    await vi.waitFor(() => expect(state.draftReady).toBe(true))
    expect(state.financeCountrySettings).toContainEqual(expect.objectContaining({ country: '美国', stage: 'rare', sortOrder: 21 }))
    expect(state.financePolicies).toContainEqual(expect.objectContaining({ category: '香氛', enabled: true, countryRules: [expect.objectContaining({ country: '美国', allowedChannels: ['1::物流商::CHANNEL'] })] }))
    expect(state.quotationAttributeOptions).toContain('香氛')
    expect(state.financeTaxSettings.countries).toContainEqual(expect.objectContaining({ country: '美国', fixedFeeUsd: 2 }))
    expect(state.financeSurchargeSettings.countries).toContainEqual(expect.objectContaining({ country: '美国', fixedFeeUsd: 3 }))
    state.selectFinanceCustomer('bk')
    const product = { purchase: 80, purchaseFreightPerUnit: 5, freight: 15 } as QuotationProduct
    expect(state.taxResult('美国', '物流商', state.salePrice(product), '', '', 1, 1)).toMatchObject({ configured: true, taxUsd: 2, surchargeUsd: 3, totalUsd: 23.55 })
  })

  it('offers only enabled finance attributes, without adding the built-in attributes', async () => {
    await mountPage()
    expect(selectableAttributes()).toEqual([])
    const response = financeResponse()
    const categories = ['服装', '纯电', '保健品', '化妆品', '带电', '普货', '香水', '大货普货', '大货带电']
    response['channel-policies'].value = [...categories, '粉末'].map(category => ({
      ...response['channel-policies'].value[0]!, id: category, category, enabled: category !== '粉末',
    }))
    resolveFinance(response)
    await vi.waitFor(() => expect(state.draftReady).toBe(true))
    expect(selectableAttributes()).toEqual(['普货', '化妆品', '保健品', '带电', '纯电', '服装', '香水', '大货普货', '大货带电'])
    expect(state.quotationAttributeOptions).toEqual(selectableAttributes())
  })

  it('retains an unauthorized draft attribute without silently changing the shipment type', async () => {
    await mountPage({ attribute: '粉末' })
    resolveFinance(financeResponse())
    await vi.waitFor(() => expect(state.draftReady).toBe(true))
    expect(selectableAttributes()).toEqual(['香氛'])
    expect(state.products[0]!.logisticsAttribute).toBe('粉末')
    expect(attributeSelect().selectedOptions[0]!.disabled).toBe(true)
    expect(attributeSelect().selectedOptions[0]!.textContent).toContain('原物流属性未获财务授权')
    expect(state.conditionIssues({ includeSku: false, includeCategory: false })).toContainEqual({ key: 'logisticsAttribute', message: '请选择物流属性' })
  })

  it('reacts to finance additions and removals without replacing the current attribute', async () => {
    await mountPage({ attribute: '香氛' })
    resolveFinance(financeResponse())
    await vi.waitFor(() => expect(state.draftReady).toBe(true))
    const policy = state.financePolicies[0]!
    state.financePolicies = [{ ...policy, enabled: false }, { ...policy, id: '粉末', category: '粉末' }]
    await nextTick()
    expect(selectableAttributes()).toEqual(['粉末'])
    expect(state.products[0]!.logisticsAttribute).toBe('香氛')
    expect(attributeSelect().selectedOptions[0]!.disabled).toBe(true)
    state.financePolicies = []
    await nextTick()
    expect(selectableAttributes()).toEqual([])
    expect(attributeSelect().textContent).toContain('暂无财务授权的物流属性')
    expect(state.conditionIssues({ includeSku: false, includeCategory: false })).toContainEqual({ key: 'logisticsAttribute', message: '请选择物流属性' })
  })

  it('automatically applies a later finance revision to an empty quotation', async () => {
    await mountPage()
    resolveFinance(financeResponse())
    await vi.waitFor(() => expect(startQuotationSync).toHaveBeenCalledOnce())
    const latest = financeResponse()
    latest['customer-grades']._version++
    latest['customer-grades'].value[0]!.coefficient = 1.3
    latest['exchange-rate']._version++
    latest['exchange-rate'].value.usdCny = 7
    vi.mocked(api.get).mockResolvedValueOnce({ purchaseVersions: {}, logisticsRevision: 'r1', financeVersions: Object.fromEntries(Object.entries(latest).map(([key, value]) => [key, value._version])) })
      .mockResolvedValueOnce(latest)
      .mockResolvedValueOnce({ ready: true, missing: [] })
    await vi.mocked(startQuotationSync).mock.calls[0]![0](new AbortController().signal)
    await nextTick()
    expectHiddenCoefficient(1.3)
    expect(host.textContent).toContain('财务汇率 7')
  })

  it.each([undefined, 'S'])('selects an enabled grade when S is disabled, with draft grade %s', async grade => {
    await mountPage({ grade })
    resolveFinance(financeResponse(true))
    await vi.waitFor(() => expect(state.draftReady).toBe(true))
    expect(gradeField().querySelector('select')!.value).toBe('A')
    expectHiddenCoefficient(1.23615)
    expect(Array.from(gradeField().querySelectorAll('option')).map(option => option.value)).not.toContain('S')
  })

  it('keeps initialization unavailable on failure and applies saved values on retry', async () => {
    await mountPage()
    rejectFinance(new Error('财务设置网络不可用'))
    await vi.waitFor(() => expect(host.textContent).toContain('重试读取'))
    expect(state.draftReady).toBe(false)
    vi.mocked(api.get).mockResolvedValueOnce(financeResponse())
    Array.from(host.querySelectorAll('button')).find(button => button.textContent === '重试读取')!.click()
    await vi.waitFor(() => expect(state.draftReady).toBe(true))
    expectHiddenCoefficient(1.21605)
    expect(host.textContent).toContain('财务汇率 6.7')
  })
})
