// @vitest-environment happy-dom
import { createApp, nextTick, type App } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import View from './QuotationSystemView.vue'
import { api } from '@/services/http'
import { clearFinanceSettingsCache, hydrateFinanceSettings } from '@/services/financeSettings'
import { replaceLogisticsRules, type LogisticsRule } from '@/data/logistics'
import { loadPublishedLogisticsRules } from '@/data/publishedLogisticsRepository'
import type { QuotationMode, QuotationProduct, QuotationMatrixRow } from '@/components/quotation/types'
import type { QuotationDraftPayload } from '@/services/quotationDrafts'
import { normalizeQuotationRecord } from '@/data/quotationRecords'
import { quotationReissuePayload } from '@/services/quotationReissue'
import { quotationRecordQuoteSheetSource } from '@/data/quotationRecordQuoteSheet'
import { quoteSheetModeDefaults } from '@/data/customerQuoteSheet'
import { authState } from '@/data/authStore'

vi.mock('vue-router', () => ({ useRoute: () => ({ query: {} }), useRouter: () => ({ replace: vi.fn() }), onBeforeRouteLeave: vi.fn() }))
vi.mock('@/services/quotationSync', async original => ({ ...await original<typeof import('@/services/quotationSync')>(), startQuotationSync: vi.fn(() => vi.fn()) }))
vi.mock('@/data/publishedLogisticsRepository', async original => ({
  ...await original<typeof import('@/data/publishedLogisticsRepository')>(),
  loadPublishedLogisticsManifest: vi.fn().mockResolvedValue({ verified: true, manifest: { revision: 'test' } }),
  loadPublishedLogisticsRules: vi.fn(),
}))
const rule = {
  id: 9910, name: '直接克重', billingVerified: true, status: '启用', logisticsChannelId: 'test-channel', logisticsVersionId: 'test-version',
  relations: [{ carrier: '燕文', channel: '测试', channelCode: 'TEST' }],
  prices: [{ areaName: '美国', countryCode: 'US', weightFromKg: 0, weightToKg: 100, pricingModel: 'per-kg', quoteReady: true, minChargeWeightKg: 0, startWeightKg: 0, firstWeightKg: 0, firstWeightPrice: 0, nextWeightKg: 0, nextWeightPrice: 0, pricePerKg: 50, registrationFee: 5,
    intervalWeightKg: 0, intervalPrice: 0, surcharge: 0, fuelSurchargeRate: 0, volumetric: false, zoneName: '', prohibitedMarks: '', allowedMarks: '' }],
} as unknown as LogisticsRule
const fees = (amount: number) => ({ countries: [{ country: '美国', fixedFeeUsd: amount, selected: true, enabled: true, sortOrder: 1 }], providers: [{ provider: '燕文', mode: 'taxable', selected: true, channels: [] }], updatedAt: 'test' })
let app: App | undefined, host: HTMLDivElement
let state: {
  notice: string; draftReady: boolean; manualCost: string; manualWeightGrams: string; manualQueried: boolean; manualPanelError: string
  manualMode: boolean; quoteMode: QuotationMode; products: QuotationProduct[]; specialPackagingGrams: string
  quoteMatrixMode: string; commonQuoteRows: QuotationMatrixRow[]; templateQuoteRows: QuotationMatrixRow[]
  changeQuoteMode: (mode: QuotationMode) => void; queryManualQuote: () => Promise<void>
  queryProduct: () => Promise<void>; skuSearch: string; purchaseTaxBlockReason: string
  quantityCostBreakdown: (p: QuotationProduct, rule: string, quantity: number, country: string, carrier: string, region: string, key: string) => { cost: number; freight: number; quoteUsd: number; quoteCny: number } | null
  chargeWeight: (p: QuotationProduct) => number; draftPayload: () => QuotationDraftPayload
  applyDraftPayload: (payload: QuotationDraftPayload, purchases?: undefined, options?: { restoreQuotation: boolean }) => Promise<void>
  buildQuoteOptions: () => Array<{ logisticsInput: {weightKg: number; packagingWeightKg: number}; totalCostCny: number; logisticsSamples: Array<{quantity: number; input: {weightKg: number}}> }>
  excelQuoteRows: (p: QuotationProduct, country: string) => QuotationMatrixRow[]
  changeLogisticsAttribute: (p: QuotationProduct, attribute: string) => Promise<void>
  save: () => Promise<void>; commissionThreshold: string; saveValidationIssues: Array<{key: string}>
}
beforeEach(async () => {
  authState.current = {id:'test-admin',name:'管理员',account:'ADMIN',role:'super_admin',status:'enabled',mustChangePassword:false,passwordUpdatedAt:''}
  vi.clearAllMocks(); clearFinanceSettingsCache()
  vi.mocked(loadPublishedLogisticsRules).mockImplementation(async () => { replaceLogisticsRules([rule]); return { verified: true, revision: 'test', rules: [rule] } as never })
  vi.spyOn(api, 'get').mockImplementation(async path => {
    if (path === '/finance-settings') return {
      'country-classification': { value: [{country:'美国',enabled:true,stage:'common',sortOrder:1}], _version: 1 },
      'channel-policies': { value: [{ id: '普货', category: '普货', enabled: true, countryRules: [{ country: '美国', allowedChannels: ['9910::燕文::TEST'] }] }], _version: 1 },
      'customer-grades': { value: [{ grade: 'S', coefficient: 1.2, enabled: true }], _version: 1 },
      'exchange-rate': { value: { usdCny: 6, eurUsd: 1.1, updatedAt: 'test' }, _version: 1 },
      'tax-settings': { value: fees(.3), _version: 1 }, 'surcharge-settings': { value: fees(.2), _version: 1 },
      'customer-operation-fees': { value: { customers: [] }, _version: 1 },
    }
    if (path === '/quotation-readiness') return { ready: false, missing: ['至少需要1个已确认转正式的采购商品'], purchase: {ready:false}, logistics: {ready:true}, finance:{ready:true} }
    if (path === '/quotation-templates') return []
    if (path === '/quotation-drafts/mine/state') return { exists: false }
    throw new Error('Unexpected API: ' + path)
  })
  host = document.createElement('div'); document.body.append(host)
  app = createApp(View); const vm = app.mount(host)
  state = (vm.$ as unknown as { setupState: typeof state }).setupState
  await vi.waitFor(() => expect(state.draftReady).toBe(true))
  const input = host.querySelector<HTMLInputElement>('.customer-field input')!
  input.value = '手填回归'; input.dispatchEvent(new Event('input', {bubbles:true})); await nextTick()
})
afterEach(() => { app?.unmount(); host?.remove(); vi.restoreAllMocks(); clearFinanceSettingsCache(); replaceLogisticsRules([]); authState.current=null })
it.each(['employee','finance','purchase','logistics'] as const)('does not expose or activate FOB for %s, and preserves ordinary quotations',async role=>{
  authState.current!.role=role;await nextTick()
  const before=state.draftPayload()
  expect(host.querySelector('option[value="fob"]')).toBeNull()
  state.changeQuoteMode('fob' as QuotationMode);await nextTick()
  expect(host.querySelector('.fob-card')).toBeNull()
  expect(state.draftPayload()).toEqual(before)
  expect([...host.querySelectorAll('.mode-field option')].map(o=>(o as HTMLOptionElement).value)).toEqual(['single','bundle','freight-trial','shipping-only'])
})
it('switches to FOB through the existing dropdown without exposing or saving channel quotations', async () => {
  const before = state.draftPayload()
  const post = vi.spyOn(api,'post').mockResolvedValue({} as never)
  clearFinanceSettingsCache()
  const selector = host.querySelector<HTMLSelectElement>('[aria-label="报价模式"]')!
  selector.value='fob'; selector.dispatchEvent(new Event('change',{bubbles:true})); await nextTick()
  expect(document.querySelector('[aria-label="FOB查询SKU"]')).not.toBeNull()
  expect(host.querySelector('.matrix-workbench')).toBeNull()
  expect(host.querySelector('.logistics-field')).toBeNull()
  expect(host.querySelector('.commission-field')).toBeNull()
  expect(host.querySelector('.customer-field')).toBeNull()
  expect(host.querySelector('.fob-card')).not.toBeNull()
  expect(host.querySelector('.fob-parameters')?.textContent).toContain('0 CNY/USD')
  await hydrateFinanceSettings(); await nextTick()
  expect(host.querySelector('.fob-parameters')?.textContent).toContain('6 CNY/USD')
  await state.save();expect(post).not.toHaveBeenCalled()
  expect(state.draftPayload()).toEqual(before)
  selector.value='single';selector.dispatchEvent(new Event('change',{bubbles:true}));await nextTick()
  expect(document.querySelector('[aria-label="FOB查询SKU"]')).toBeNull()
  expect(host.querySelector('.fob-card')).toBeNull();expect(host.querySelector('.sku-field')).not.toBeNull()
})
async function query(mode: QuotationMode) {
  state.changeQuoteMode(mode); state.manualCost = '30'; state.manualWeightGrams = '500'
  // Residue from the SKU editor must never leak into manual calculation.
  Object.assign(state.products[0]!, { purchase: 999, purchaseFreightPerUnit: 99, netWeight: 8, manualWeight: 9 })
  state.specialPackagingGrams = '500'
  await state.queryManualQuote(); await nextTick()
}
function quote(q: number) { return state.quantityCostBreakdown(state.products[0]!, rule.name, q, '美国', '燕文', '', '9910::燕文::TEST') }

it('offers five modes, hides SKU/tier and directly computes manual cost and grams at every quantity', async () => {
  await query('freight-trial')
  expect([...host.querySelectorAll('.mode-field option')].map(o => o.textContent)).toEqual(['单品 SKU 报价','组合 SKU 报价','运费试算','仅代发货报价','FOB（批发）报价'])
  expect(host.querySelector('.sku-field')).toBeNull(); expect(host.querySelector('.sales-field')).toBeNull()
  expect(host.querySelector('.manual-quote-panel')?.textContent).toContain('查询试算')
  expect(state.chargeWeight(state.products[0]!)).toBe(.5)
  for (const [q, cost, freight, usd] of [[1,60,30,12.5],[2,115,55,23.5],[3,170,80,34.5],[7,390,180,78.5]]) {
    expect(quote(q!)).toMatchObject({cost, freight, quoteUsd:usd, quoteCny:usd!*6})
  }
  expect(state.saveValidationIssues.some(i => i.key === 'businessReadiness' || i.key === 'sku')).toBe(false)
  expect(vi.mocked(api.get).mock.calls.some(([path]) => path.startsWith('/purchase-products/'))).toBe(false)
})
it('shipping-only excludes all product cost while preserving finance charges and commission rounding', async () => {
  await query('shipping-only')
  expect(host.querySelector('[aria-label="成本价格"]')).toBeNull()
  expect(quote(1)).toMatchObject({cost:30, freight:30, quoteUsd:6.5, quoteCny:39})
  expect(quote(7)).toMatchObject({cost:180, freight:180, quoteUsd:36.5, quoteCny:219})
  state.commissionThreshold = '.95'; await nextTick()
  expect(quote(1)?.quoteUsd).toBe(6.85)
  expect(state.draftPayload().manualPricing?.costCny).toBe(0)
})
it.each(['', '-1', 'NaN', 'Infinity', '1e3', '1000001', '0.0001'])('blocks invalid gram input %s and invalidates old prices immediately', async grams => {
  await query('freight-trial')
  state.manualWeightGrams = grams
  expect(state.manualQueried).toBe(false); expect(quote(1)).toBeNull()
  await state.queryManualQuote()
  expect(state.manualPanelError).not.toBe(''); expect(state.products[0]?.rule).toBe('')
})
it('does not reuse the previous mode or attribute prices and preserves zero cost as valid', async () => {
  await query('freight-trial'); state.manualCost='0'; await state.queryManualQuote()
  expect(quote(1)?.cost).toBe(30)
  state.changeQuoteMode('shipping-only'); expect(state.manualQueried).toBe(false); expect(quote(1)).toBeNull()
  await state.queryManualQuote(); expect(quote(1)?.cost).toBe(30)
  await state.changeLogisticsAttribute(state.products[0]!, '带电'); expect(state.manualQueried).toBe(false)
  state.changeQuoteMode('single'); expect(state.manualMode).toBe(false); expect(state.products[0]?.rule).toBe('')
})
it.each(['freight-trial','shipping-only'] as const)('round-trips %s conditions and direct weights through save, record and reissue adapters', async mode => {
  await query(mode)
  state.quoteMatrixMode='common'
  state.commonQuoteRows=state.excelQuoteRows(state.products[0]!, '美国')
  const options=state.buildQuoteOptions()
  expect(options[0]?.logisticsInput).toMatchObject({weightKg:.5,packagingWeightKg:0})
  expect(options[0]?.logisticsSamples.map(s=>[s.quantity,s.input.weightKg])).toEqual([[1,.5],[2,1],[3,1.5]])
  const draft = state.draftPayload()
  expect(draft.product.sku).toBe(''); expect(draft.bundleItems).toEqual([]); expect(draft.specialPackagingGrams).toBe(0)
  const record=normalizeQuotationRecord({ id:'manual-record',no:'Q-MANUAL',quoteMode:mode,customerName:'手填回归',customerGrade:'S级客户',logisticsAttribute:'普货',manualPricing:draft.manualPricing,quoteOptions:options } as never)!
  expect(record.quoteMode).toBe(mode); expect(record.manualPricing?.weightGrams).toBe(500)
  const source=quotationRecordQuoteSheetSource(record)
  expect(source.quoteMode).toBe(mode); expect(source.skus).toEqual([mode==='freight-trial'?'运费试算':'仅代发货报价'])
  const payload=quotationReissuePayload(record)
  expect(payload.manualPricing).toEqual(draft.manualPricing)
  await state.applyDraftPayload(payload,undefined,{restoreQuotation:true})
  expect(state.manualQueried).toBe(true); expect(state.chargeWeight(state.products[0]!)).toBe(.5)
  expect(quote(1)?.cost).toBe(mode==='freight-trial'?60:30)
  expect(quoteSheetModeDefaults('shipping-only').notes.join(' ')).not.toContain('include product cost')
})

it.each(['freight-trial','shipping-only'] as const)('saves the complete %s payload with no SKU costs and reopens the same snapshot', async mode => {
  await query(mode)
  state.quoteMatrixMode='common'
  await nextTick()
  await vi.waitFor(() => expect(host.querySelector('.selection-actions button')).not.toBeNull())
  host.querySelector<HTMLButtonElement>('.selection-actions button')!.click()
  await nextTick()
  const post=vi.spyOn(api,'post').mockImplementation(async (_path,body) => ({...body as object,id:'saved-manual',no:'Q-MANUAL',createdAt:'2026-10-07T00:00:00Z'}))
  await state.save()
  expect(post, state.notice).toHaveBeenCalledTimes(1)
  const payload=post.mock.calls[0]![1] as Record<string,unknown>
  expect(payload).toMatchObject({quoteMode:mode,primarySku:'',manualPricing:{costCny:mode==='freight-trial'?30:0,weightGrams:500},purchaseVersions:{}})
  expect(payload.weightSnapshot).toBeUndefined();expect(payload.purchaseUnitPriceCny).toBeUndefined();expect(payload.domesticFreightPerUnitCny).toBeUndefined()
  const record=normalizeQuotationRecord({...payload,id:'saved-manual',no:'Q-MANUAL'} as never)!
  expect(record.systemQuoteUsd).toBe(mode==='freight-trial'?12.5:6.5)
  expect(record.customerQuote?.rows[0]?.prices[0]).toBe(mode==='freight-trial'?12.5:6.5)
  expect(quotationRecordQuoteSheetSource(record).quoteMode).toBe(mode)
})
