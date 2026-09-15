// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, type App } from 'vue'
const fixture = vi.hoisted(() => ({
  policies: [{ id: 'policy', category: '普货', enabled: true, countryRules: [], note: '' }],
  countries: [], customerGrades: [], exchangeRate: { usdCny: 6.7, updatedAt: '' },
  surchargeSettings: { countries: [], providers: [], updatedAt: '' },
  taxSettings: { countries: [{ country: '新西兰', selected: true, enabled: true, fixedFeeUsd: 1.5, sortOrder: 1, providers: [{ provider: '测试', mode: 'exempt', selected: true, channels: [] }] }, { country: '英国', selected: true, enabled: true, fixedFeeUsd: 0.5, sortOrder: 2, providers: [{ provider: '测试', mode: 'taxable', selected: true, channels: [] }] }], providers: [], updatedAt: '' },
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
it('renders channel tax workspace and preserves legacy country provider defaults', async () => {
  const errors = vi.fn()
  const host = document.createElement('div'); document.body.append(host)
  app = createApp({render: () => h(Jerry, {mode:'members'})})
  app.config.errorHandler = errors
  app.mount(host)
  for (let i=0; i<8; i++) { await Promise.resolve(); await nextTick() }
  const card = [...document.querySelectorAll<HTMLElement>('.finance-stats>[role=button]')].find(e => e.textContent?.includes('税率设置'))!
  expect(card).toBeTruthy()
  card.click(); await nextTick()
  expect(errors).not.toHaveBeenCalled()
  expect(document.querySelector('.channel-tax-workspace')?.textContent).toContain('渠道税费设置')
  expect(document.querySelector('.table-card')).toBeNull()
  expect(document.querySelectorAll('.finance-stats>[role=button]')).toHaveLength(7)
  const open = async (country: string) => { [...document.querySelectorAll<HTMLButtonElement>('.countries nav button')].find(b=>b.textContent===country)!.click(); await nextTick() }
  await open('新西兰')
  expect(document.querySelector('.matrix tbody')?.textContent).toContain('已含税')
  await open('英国')
  expect(document.querySelector('.matrix tbody')?.textContent).toContain('$0.50')
  expect(document.querySelector('.matrix tbody')?.textContent).toContain('固定金额')
  expect(errors).not.toHaveBeenCalled()
})
