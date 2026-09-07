// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, type App } from 'vue'
const fixture = vi.hoisted(() => ({
  policies: [{ id: 'policy', category: '普货', enabled: true, countryRules: [], note: '' }],
  countries: [], customerGrades: [], exchangeRate: { usdCny: 6.7, updatedAt: '' },
  taxSettings: { countries: [], providers: [], updatedAt: '' },
  surchargeSettings: { countries: [], providers: [], updatedAt: '' },
}))
vi.mock('@/services/financeSettingsWorkspace', () => ({ loadFinanceSettingsWorkspace: async () => fixture, readFinanceSettingsWorkspace: () => fixture }))
vi.mock('@/data/publishedLogisticsRepository', async importOriginal => ({ ...await importOriginal<object>(),
  loadPublishedLogisticsManifest: async () => ({manifest:{revision:'test', attributes:[], countries:[]}}),
  loadPublishedLogisticsRuleCatalog: async () => [],
}))
vi.mock('@/services/quotationSync', () => ({startQuotationSync: () => () => {}, loadQuotationSync: async () => ({logisticsRevision:'test'})}))
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
  expect(document.querySelector('.finance-tax-workspace')?.textContent).toContain('物流商附加费属性')
  expect(document.querySelector('.table-card')).toBeNull()
  expect(document.querySelectorAll('.finance-stats>[role=button]')).toHaveLength(6)
})
