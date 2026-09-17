// @vitest-environment happy-dom
import { createApp, nextTick, type App } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import QuotationSystemView from './QuotationSystemView.vue'
import { api } from '@/services/http'
import { clearFinanceSettingsCache } from '@/services/financeSettings'

vi.mock('vue-router', () => ({ useRoute: () => ({ query: {} }), onBeforeRouteLeave: vi.fn() }))
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
        payload: draftGrade ? { schemaVersion: 2, selectedCustomerGrade: draftGrade, quoteMode: 'single', skuSearch: '', logisticsAttribute: '普货' } : null,
      }
      throw new Error(`Unexpected request: ${path}`)
    })
    app = createApp(QuotationSystemView)
    app.mount(host)
    const selector = () => host.querySelector<HTMLSelectElement>('[data-validation-field="customerGrade"] select')!
    expect(Array.from(selector().options).some(option => option.value === 'NEW')).toBe(false)
    resolveFinance(finance)
    await vi.waitFor(() => expect(host.textContent).toContain(draftGrade ? '已恢复并保存草稿' : '自动草稿已开启'))
    await vi.waitFor(() => expect(Array.from(selector().options).map(option => option.value)).toEqual(['S', 'A', 'B', 'C', 'D', 'NEW']))
    expect(selector().value).toBe(expectedGrade)
    expect(Array.from(selector().options).find(option => option.value === 'NEW')?.textContent).toBe('新客户')
    selector().value = 'NEW'
    selector().dispatchEvent(new Event('change'))
    await nextTick()
    expect(host.textContent).toContain('报价系数 1.27635')
    expect(vi.mocked(api.get).mock.calls.some(([path]) => path.includes('/purchase') || path.includes('/logistics'))).toBe(false)
  })
})
