// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, type App } from 'vue'
const fixture = vi.hoisted(() => ({
  policies: [{ id: 'policy', category: '普货', enabled: true, countryRules: [], note: '' }],
  countries: [], customerGrades: [], exchangeRate: { usdCny: 6.7, updatedAt: '' },
  taxSettings: { countries: [], providers: [], updatedAt: '' },
  surchargeSettings: { countries: [{ country: '新西兰', selected: true, enabled: true, fixedFeeUsd: 1.5, sortOrder: 1, providers: [{ provider: '测试', mode: 'exempt', selected: true, channels: [] }] }, { country: '英国', selected: true, enabled: true, fixedFeeUsd: 0.5, sortOrder: 2, providers: [{ provider: '测试', mode: 'taxable', selected: true, channels: [] }] }], providers: [], updatedAt: '' },
}))
vi.mock('@/services/financeSettingsWorkspace', () => ({ loadFinanceSettingsWorkspace: async () => fixture, readFinanceSettingsWorkspace: () => fixture }))
vi.mock('@/data/publishedLogisticsRepository', async importOriginal => ({ ...await importOriginal<object>(),
  loadPublishedLogisticsManifest: async () => ({manifest:{revision:'test', attributes:[], countries:[]}}),
  loadPublishedLogisticsRuleCatalog: async () => [],
}))
vi.mock('@/services/quotationSync', () => ({startQuotationSync: () => () => {}, loadQuotationSync: async () => ({logisticsRevision:'test'})}))
vi.mock('@/data/financeChannelPolicies', async importOriginal => ({ ...await importOriginal<object>(), channelsAvailableForCountry: () => [{ key: '1::测试::A', carrier: '测试', channel: '渠道A' }, { key: '2::测试::B', carrier: '测试', channel: '渠道B' }] }))
import Jerry from './JerryModuleView.vue'
let app: App
afterEach(() => { app?.unmount(); document.body.innerHTML = '' })
it('renders only the surcharge workspace with existing logistics policies and empty surcharge settings', async () => {
  const errors = vi.fn()
  const host = document.createElement('div'); document.body.append(host)
  app = createApp({render: () => h(Jerry, {mode:'members'})})
  app.config.errorHandler = errors
  app.mount(host)
  for (let i=0; i<8; i++) { await Promise.resolve(); await nextTick() }
  const card = [...document.querySelectorAll<HTMLElement>('.finance-stats>[role=button]')].find(e => e.textContent?.includes('附加费设置'))!
  expect(card).toBeTruthy()
  card.click(); await nextTick()
  expect(errors).not.toHaveBeenCalled()
  expect(document.querySelector('.finance-tax-workspace')?.textContent).toContain('国家附加费')
  expect(document.querySelector('.finance-tax-workspace')?.textContent).toContain('点击国家名称')
  expect(document.querySelector('.table-card')).toBeNull()
  expect(document.querySelectorAll('.finance-stats>[role=button]')).toHaveLength(6)
  const open = (country: string) => document.querySelector<HTMLButtonElement>(`[aria-label="设置${country}物流商附加费"]`)!.click()
  open('新西兰'); await nextTick()
  expect(document.querySelector('.tax-provider-global')?.textContent).toContain('物流商附加费属性')
  expect(document.querySelector('.tax-provider-global button.active')?.textContent).toBe('免附加费')
  expect(document.querySelector('.tax-provider-global input[type=checkbox]')).toBeNull()
  open('英国'); await nextTick()
  expect(document.querySelector('.tax-provider-global button.active')?.textContent).toBe('不免附加费')
  const free = [...document.querySelectorAll<HTMLButtonElement>('.tax-provider-global button')].find(b => b.textContent === '免附加费')!
  free.click(); await nextTick()
  expect(fixture.surchargeSettings.countries[0]!.providers[0]!.mode).toBe('exempt')
  expect(fixture.surchargeSettings.countries[1]!.providers[0]!.mode).toBe('exempt')
  open('新西兰'); await nextTick()
  const paid = [...document.querySelectorAll<HTMLButtonElement>('.tax-provider-global button')].find(b => b.textContent === '不免附加费')!
  paid.click(); await nextTick()
  expect(fixture.surchargeSettings.countries[0]!.providers[0]!.mode).toBe('taxable')
  expect(fixture.surchargeSettings.countries[1]!.providers[0]!.mode).toBe('exempt')
})
