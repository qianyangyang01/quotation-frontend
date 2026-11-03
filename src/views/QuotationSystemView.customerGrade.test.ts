// @vitest-environment happy-dom
import { createApp, nextTick, type App } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import QuotationSystemView from './QuotationSystemView.vue'
import { api } from '@/services/http'
import { clearFinanceSettingsCache } from '@/services/financeSettings'
import { defaultWomensPricing, WOMENS_PRICING_UNAVAILABLE } from '@/data/womensPricing'

vi.mock('vue-router', () => ({ useRoute: () => ({ query: {} }), useRouter: () => ({ replace: vi.fn().mockResolvedValue(undefined) }), onBeforeRouteLeave: vi.fn() }))
vi.mock('@/services/quotationSync', async importOriginal => ({
  ...await importOriginal<typeof import('@/services/quotationSync')>(),
  startQuotationSync: vi.fn(() => vi.fn()),
}))
vi.mock('@/data/publishedLogisticsRepository', async importOriginal => ({
  ...await importOriginal<typeof import('@/data/publishedLogisticsRepository')>(),
  loadPublishedLogisticsManifest: vi.fn().mockResolvedValue({ verified: true }),
}))

const finance = {
  'country-classification': { value: [], _version: 1 },
  'channel-policies': { value: [], _version: 1 },
  'customer-grades': { value: ['S', 'A', 'B', 'C', 'D', 'E', 'NEW'].map(grade => ({
    grade, coefficient: grade === 'NEW' ? 1.27635 : 1.21605, enabled: grade !== 'E',
  })), _version: 8 },
  'exchange-rate': { value: { usdCny: 6.7, eurUsd: 1.16, updatedAt: 'test' }, _version: 7 },
  'tax-settings': { value: { countries: [], providers: [], updatedAt: 'test' }, _version: 1 },
  'customer-operation-fees': { value: { customers: [] }, _version: 1 },
}

describe('customer grades on a cold quotation page', () => {
  let app: App | undefined
  let host: HTMLDivElement
  beforeEach(() => {
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
  })

  it.each([
    { draftGrade: undefined, expectedGrade: 'S' },
    { draftGrade: 'NEW', expectedGrade: 'NEW' },
    { draftGrade: 'E', expectedGrade: 'S' },
  ])('loads enabled grades before restoring $draftGrade without querying a SKU', async ({ draftGrade, expectedGrade }) => {
    let resolveFinance!: (value: typeof finance) => void
    const pendingFinance = new Promise<typeof finance>(resolve => { resolveFinance = resolve })
    vi.spyOn(api, 'get').mockImplementation(async path => {
      if (path === '/finance-settings') return pendingFinance
      if (path === '/quotation-templates') return []
      if (path === '/quotation-readiness') return { ready: true, missing: [] }
      if (path === '/quotation-drafts/mine/state') return {
        exists: Boolean(draftGrade), version: draftGrade ? 1 : -1,
        ...(draftGrade ? { sourceQuote: { id: 'withdrawn', no: 'QT-WITHDRAWN', version: 1 } } : {}),
        payload: draftGrade ? { schemaVersion: 2, selectedCustomerGrade: draftGrade, quoteMode: 'single', skuSearch: '', logisticsAttribute: '普货' } : null,
      }
      throw new Error(`Unexpected request: ${path}`)
    })
    app = createApp(QuotationSystemView, { resumeQuoteId: draftGrade ? 'withdrawn' : undefined })
    app.mount(host)
    const selector = () => host.querySelector<HTMLSelectElement>('[data-validation-field="customerGrade"] select')!
    expect(Array.from(selector().options).some(option => option.value === 'NEW')).toBe(false)
    resolveFinance(finance)
    await vi.waitFor(() => expect(host.textContent).toContain(draftGrade ? '已恢复并保存草稿' : '当前输入不会自动保存'))
    await vi.waitFor(() => expect(Array.from(selector().options).map(option => option.value)).toEqual(['S', 'A', 'B', 'C', 'D', 'NEW', 'WOMENS']))
    expect(selector().value).toBe(expectedGrade)
    expect(Array.from(selector().options).find(option => option.value === 'NEW')?.textContent).toBe('新客户')
    selector().value = 'NEW'
    selector().dispatchEvent(new Event('change'))
    await nextTick()
    expect(selector().value).toBe('NEW')
    expect(host.textContent).not.toContain('报价系数')
    expect(host.innerHTML).not.toContain('1.27635')
    expect(vi.mocked(api.get).mock.calls.some(([path]) => path.includes('/purchase') || path.includes('/logistics'))).toBe(false)
  })
  it.each(['single', 'bundle'] as const)('selects womens pricing through the grade dropdown for %s and switches back', async mode => {
    vi.spyOn(api, 'get').mockImplementation(async path => {
      if (path === '/finance-settings') return finance
      if (path === '/quotation-templates') return []
      if (path === '/quotation-readiness') return { ready: true, missing: [] }
      if (path === '/quotation-drafts/mine/state') return { exists: false, version: -1, payload: null }
      throw new Error(`Unexpected request: ${path}`)
    })
    app = createApp(QuotationSystemView)
    app.mount(host)
    const grade = () => host.querySelector<HTMLSelectElement>('[aria-label="客户等级"]')!
    const modeSelect = () => host.querySelector<HTMLSelectElement>('[aria-label="报价模式"]')!
    await vi.waitFor(() => expect([...grade().options].some(o => o.value === 'NEW')).toBe(true))
    if (mode === 'bundle') {
      modeSelect().value = mode; modeSelect().dispatchEvent(new Event('change')); await nextTick()
    }
    grade().value = 'WOMENS'; grade().dispatchEvent(new Event('change')); await nextTick()
    expect(grade().value).toBe('WOMENS')
    expect(modeSelect().value).toBe(mode)
    expect([...modeSelect().options].some(o => o.value.startsWith('womens'))).toBe(false)
    expect(host.textContent).toContain('按报价金额自动匹配系数')
    // Selecting the other product structure keeps the customer pricing category.
    const otherMode = mode === 'single' ? 'bundle' : 'single'
    modeSelect().value = otherMode; modeSelect().dispatchEvent(new Event('change')); await nextTick()
    expect(grade().value).toBe('WOMENS')
    expect(modeSelect().value).toBe(otherMode)
    grade().value = 'NEW'; grade().dispatchEvent(new Event('change')); await nextTick()
    expect(grade().value).toBe('NEW')
    expect(host.textContent).not.toContain('按报价金额自动匹配系数')
    expect(modeSelect().value).toBe(otherMode)
  })

  it.each([
    ['womens-single', true], ['womens-bundle', true],
    ['womens-single', false], ['womens-bundle', false],
  ] as const)('restores saved %s through the customer dropdown with policy enabled=%s', async (mode, enabled) => {
    const policy={...defaultWomensPricing(),enabled}
    vi.spyOn(api,'get').mockImplementation(async path => {
      if(path==='/finance-settings') return {...finance,'womens-pricing':{value:policy,_version:9}}
      if(path==='/quotation-templates') return []
      if(path==='/quotation-readiness') return {ready:true,missing:[]}
      if(path==='/quotation-drafts/mine/state') return {
        exists:true,version:4,sourceQuote:{id:'withdrawn',no:'QT-WOMENS',version:1},
        payload:{schemaVersion:2,selectedCustomerGrade:'NEW',quoteMode:mode,skuSearch:'',logisticsAttribute:'普货'},
      }
      throw new Error(`Unexpected request: ${path}`)
    })
    app=createApp(QuotationSystemView,{resumeQuoteId:'withdrawn'})
    const vm=app.mount(host)
    const state=(vm.$ as unknown as {setupState:{quoteMode:string;womensPricing:{version:number};womensPricingBlock:string;draftPayload:()=>{quoteMode:string}}}).setupState
    await vi.waitFor(()=>expect(host.textContent).toContain('已恢复并保存草稿'))
    const grade=host.querySelector<HTMLSelectElement>('[aria-label="客户等级"]')!
    const modeSelector=host.querySelector<HTMLSelectElement>('[aria-label="报价模式"]')!
    expect(grade.value).toBe('WOMENS')
    expect(modeSelector.value).toBe(mode==='womens-single'?'single':'bundle')
    expect(state.draftPayload().quoteMode).toBe(mode)
    expect(state.womensPricing.version).toBe(9)
    expect(state.womensPricingBlock).toBe(enabled?'':WOMENS_PRICING_UNAVAILABLE)
    grade.value='NEW';grade.dispatchEvent(new Event('change'));await nextTick()
    expect(state.quoteMode).toBe(modeSelector.value)
    expect(grade.value).toBe('NEW')
    expect(state.womensPricingBlock).toBe('')
  })

})
