// @vitest-environment happy-dom
import { createApp, nextTick, type App } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import QuotationSystemView from './QuotationSystemView.vue'
import type { QuotationProduct } from '@/components/quotation/types'
import { api } from '@/services/http'
import { clearFinanceSettingsCache, hydrateFinanceSettings } from '@/services/financeSettings'
import { startQuotationSync } from '@/services/quotationSync'
import type { FinanceChannelPolicy, FinanceCountrySetting } from '@/data/financeChannelPolicies'
import type { FinanceTaxSettings } from '@/data/financeTaxSettings'
import type { FinanceSurchargeSettings } from '@/data/financeSurchargeSettings'

vi.mock('vue-router', () => ({ useRoute: () => ({ query: {} }), onBeforeRouteLeave: vi.fn() }))
vi.mock('@/data/authStore', () => ({ currentAuthUser: { value: { name: '员工', account: 'employee', role: 'employee', permissions: ['quotation'] } } }))
vi.mock('@/services/quotationSync', async importOriginal => ({
  ...await importOriginal<typeof import('@/services/quotationSync')>(),
  startQuotationSync: vi.fn(() => vi.fn()),
}))
vi.mock('@/data/publishedLogisticsRepository', async importOriginal => ({
  ...await importOriginal<typeof import('@/data/publishedLogisticsRepository')>(),
  loadPublishedLogisticsManifest: vi.fn().mockResolvedValue({ verified: true }),
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
  salePrice: (product: QuotationProduct) => number
  usdPriceFromCny: (cny: number) => number
  exchange: { usd: number; eurUsd: number }
  draftReady: boolean
  financePolicies: FinanceChannelPolicy[]
  financeCountrySettings: FinanceCountrySetting[]
  financeTaxSettings: FinanceTaxSettings
  financeSurchargeSettings: FinanceSurchargeSettings
  quotationAttributeOptions: string[]
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
  })

  async function mountPage(options: { grade?: string; warm?: boolean; disableS?: boolean } = {}) {
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
      if (path === '/quotation-drafts/mine/state') return options.grade
        ? { exists: true, version: 1, payload: { schemaVersion: 2, selectedCustomerGrade: options.grade, quoteMode: 'single' } }
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
  function expectHiddenCoefficient(coefficient: number) {
    expect(host.textContent).not.toContain('报价系数')
    expect(host.innerHTML).not.toContain(coefficient.toString())
    expect(state.salePrice({ purchase: 80, purchaseFreightPerUnit: 5, freight: 15 } as QuotationProduct)).toBeCloseTo(100 * coefficient, 10)
  }

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
