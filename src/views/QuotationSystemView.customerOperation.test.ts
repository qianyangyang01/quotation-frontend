// @vitest-environment happy-dom
import { createApp, nextTick, type App } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import QuotationSystemView from './QuotationSystemView.vue'
import { api } from '@/services/http'
import { clearFinanceSettingsCache, readFinanceSetting } from '@/services/financeSettings'

vi.mock('vue-router', () => ({ useRoute: () => ({ query: {} }), onBeforeRouteLeave: vi.fn() }))
vi.mock('@/services/quotationSync', async importOriginal => ({
  ...await importOriginal<typeof import('@/services/quotationSync')>(),
  startQuotationSync: vi.fn(() => vi.fn()),
}))
vi.mock('@/data/publishedLogisticsRepository', async importOriginal => ({
  ...await importOriginal<typeof import('@/data/publishedLogisticsRepository')>(),
  loadPublishedLogisticsManifest: vi.fn().mockResolvedValue({ verified: true }),
}))

const customers = [
  { id: 'bk', name: 'BK', feeUsd: 0.3, enabled: true },
  { id: 'ck', name: 'CK', feeUsd: 0.4, enabled: true },
  { id: 'dk', name: 'DK', feeUsd: 1, enabled: true },
  { id: 'off', name: '已停用客户', feeUsd: 2, enabled: false },
]
const financeResponse = {
  'country-classification': { value: [], _version: 1 },
  'channel-policies': { value: [], _version: 1 },
  'customer-grades': { value: [{ grade: 'S', coefficient: 1.2, enabled: true }], _version: 1 },
  'exchange-rate': { value: { usdCny: 6.7, updatedAt: 'test' }, _version: 1 },
  'tax-settings': { value: { countries: [], providers: [], updatedAt: 'test' }, _version: 1 },
  'customer-operation-fees': { value: { customers }, _version: 3 },
}

describe('customer operation settings on the actual quotation page', () => {
  let app: App | undefined
  let host: HTMLDivElement
  let resolveFinance: (value: typeof financeResponse) => void

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

  async function mountWithDraft(customerName: string, selectedCustomerId = '', specialPackagingGrams?: number) {
    const finance = new Promise<typeof financeResponse>(resolve => { resolveFinance = resolve })
    vi.spyOn(api, 'get').mockImplementation(async path => {
      if (path === '/finance-settings') return finance
      if (path === '/quotation-templates') return []
      if (path === '/quotation-readiness') return { ready: true, missing: [] }
      if (path === '/quotation-drafts/mine/state') return {
        exists: true, version: 1, updatedAt: '2026-09-16T00:44:59Z',
        payload: { schemaVersion: 2, customerName, selectedCustomerId, specialPackagingGrams, quoteMode: 'single', skuSearch: '', logisticsAttribute: '普货' },
      }
      throw new Error(`Unexpected request: ${path}`)
    })
    app = createApp(QuotationSystemView)
    app.mount(host)
    expect(readFinanceSetting('customer-operation-fees')).toBeUndefined()
    resolveFinance(financeResponse)
    await vi.waitFor(() => expect(host.textContent).toContain('已恢复并保存草稿'))
  }

  it.each([undefined,10])('restores packaging from the account draft and retains it when autosaving: %s', async grams => {
    await mountWithDraft('包材草稿','',grams)
    const put=vi.spyOn(api,'put').mockResolvedValue({exists:true,version:2,updatedAt:'2026-09-18T04:00:00Z'})
    const ruleButton=[...host.querySelectorAll('button')].find(b=>b.textContent?.includes('计算规则'))!
    ruleButton.click();await nextTick()
    expect(host.textContent).toContain(`当前特殊包装：${grams??0}g／票，仅增加一次`)
    const field=host.querySelector<HTMLInputElement>('[aria-label="客户名称"]')!
    field.value='包材草稿更新';field.dispatchEvent(new Event('input'));await nextTick()
    await vi.waitFor(()=>expect(put).toHaveBeenCalledWith('/quotation-drafts/mine/state',expect.objectContaining({specialPackagingGrams:grams??0}),expect.anything()),{timeout:2500})
  })

  it.each(['', '测试客户1'])('loads saved finance customers after a cold refresh with draft name %j, before any SKU query', async name => {
    await mountWithDraft(name)
    const input = host.querySelector<HTMLInputElement>('[aria-label="客户名称"]')!
    expect(input.value).toBe(name)
    host.querySelector<HTMLButtonElement>('[aria-label="展开客户列表"]')!.click()
    await nextTick()
    const options = Array.from(host.querySelectorAll<HTMLButtonElement>('[role="option"]'))
    expect(options.map(option => option.textContent)).toEqual(['BK$0.30/单', 'CK$0.40/单', 'DK$1.00/单'])
    options[0]!.click()
    await nextTick()
    expect(input.value).toBe('BK')
    expect(host.textContent).toContain('公司操作费 $0.30/单')

    input.dispatchEvent(new Event('input'))
    await nextTick()
    expect(host.textContent).toContain('手动填写，不加公司操作费')
    expect(host.textContent).not.toContain('公司操作费 $0.30/单')
    expect(vi.mocked(api.get).mock.calls.some(([path]) => path.includes('/purchase'))).toBe(false)
  })

  it('restores the fee for an explicitly selected finance customer without requiring a product query', async () => {
    await mountWithDraft('CK', 'ck')
    expect(host.textContent).toContain('公司操作费 $0.40/单')
    expect(host.textContent).not.toContain('所选客户设置已变化')
  })
})
