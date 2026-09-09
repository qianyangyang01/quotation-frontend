// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, type App } from 'vue'
const fixture = vi.hoisted(() => ({
  policies: [{ id: 'policy', category: '普货', enabled: true, countryRules: [], note: '' }],
  countries: [], customerGrades: [], exchangeRate: { usdCny: 6.7, updatedAt: '' },
  taxSettings: { countries: [], providers: [], updatedAt: '' },
  surchargeSettings: { countries: [{ country: '新西兰', selected: true, enabled: true, fixedFeeUsd: 1.5, sortOrder: 1, exemptChannelKeys: ['1::测试::A'] }, { country: '英国', selected: true, enabled: true, fixedFeeUsd: 0.5, sortOrder: 2, exemptChannelKeys: [] }], providers: [], updatedAt: '' },
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
  const open = (country: string) => document.querySelector<HTMLButtonElement>(`[aria-label="设置${country}免附加费渠道"]`)!.click()
  open('新西兰'); await nextTick()
  let checks = [...document.querySelectorAll<HTMLInputElement>('.surcharge-country-channels input')]
  expect(checks.map(c => c.checked)).toEqual([true, false])
  checks[1]!.click(); await nextTick()
  document.querySelector<HTMLButtonElement>('[aria-label="新西兰附加费详情"] footer button')!.click(); await nextTick()
  open('新西兰'); await nextTick()
  checks = [...document.querySelectorAll<HTMLInputElement>('.surcharge-country-channels input')]
  expect(checks.map(c => c.checked)).toEqual([true, false])
  open('英国'); await nextTick()
  checks = [...document.querySelectorAll<HTMLInputElement>('.surcharge-country-channels input')]
  expect(checks.map(c => c.checked)).toEqual([false, false])
  checks[1]!.click(); await nextTick()
  document.querySelector<HTMLButtonElement>('[aria-label="英国附加费详情"] footer button.primary')!.click(); await nextTick()
  expect(fixture.surchargeSettings.countries[0]!.exemptChannelKeys).toEqual(['1::测试::A'])
  expect(fixture.surchargeSettings.countries[1]!.exemptChannelKeys).toEqual(['2::测试::B'])
})
