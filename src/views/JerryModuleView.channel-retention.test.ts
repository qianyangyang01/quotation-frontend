// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, type App } from 'vue'
const data = vi.hoisted(() => ({
  save: vi.fn(async (value: unknown) => value),
  workspace: {
    policies: [{ id: 'policy', category: '普货', enabled: true, updatedAt: '', countryRules: [{ country: '德国', stage: 'standard', continent: '欧洲', sortOrder: 1, allowedChannels: ['91::万邦::TEST'] }] }],
    countries: [], customerGrades: [], exchangeRate: { usdCny: 6.7, updatedAt: '' },
    taxSettings: { countries: [], providers: [], updatedAt: '' }, surchargeSettings: { countries: [], providers: [], updatedAt: '' },
  },
}))
vi.mock('@/services/financeSettingsWorkspace', () => ({ loadFinanceSettingsWorkspace: async () => data.workspace, readFinanceSettingsWorkspace: () => data.workspace }))
vi.mock('@/data/publishedLogisticsRepository', async original => ({ ...await original<object>(),
  loadPublishedLogisticsManifest: async () => ({ manifest: { revision: 'test', attributes: [], countries: [] } }),
  loadPublishedLogisticsRuleCatalog: async () => [],
}))
vi.mock('@/services/quotationSync', () => ({ startQuotationSync: () => () => {}, loadQuotationSync: async () => ({ logisticsRevision: 'test' }) }))
vi.mock('@/data/financeChannelPolicies', async original => ({ ...await original<object>(), channelsAvailableForCountry: () => [], countriesAvailableForCategory: () => [], saveFinanceChannelPolicies: data.save }))
import Jerry from './JerryModuleView.vue'
let app: App
afterEach(() => { app?.unmount(); document.body.innerHTML = ''; vi.clearAllMocks() })
async function flush() { for (let i = 0; i < 15; i++) { await Promise.resolve(); await nextTick() } }
function button(text: string) { return [...document.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent === text)! }

it('shows retained unavailable authorization and saves it while all country channels are disabled', async () => {
  const host = document.createElement('div'); document.body.append(host)
  const errors = vi.fn()
  app = createApp({ render: () => h(Jerry, { mode: 'members' }) }); app.config.errorHandler = errors; app.mount(host)
  await flush()
  const card = [...document.querySelectorAll<HTMLElement>('.finance-stats>[role=button]')].find(card => card.textContent?.includes('物流属性与渠道'))!
  card.click(); await flush()
  expect(document.body.textContent).toContain('暂不可用，授权保留')
  button('统一维护').click(); await flush()
  expect(document.querySelector('.finance-editor')?.textContent).toContain('原授权保留')
  const attribute = document.querySelector<HTMLInputElement>('.finance-attribute-combobox input')!
  attribute.dispatchEvent(new Event('blur')); await new Promise(resolve => setTimeout(resolve, 150)); await flush()
  button('保存设置').click(); await flush()
  expect(errors).not.toHaveBeenCalled()
  expect(data.save).toHaveBeenCalledOnce()
  expect(data.save.mock.calls[0]![0]).toMatchObject([{ countryRules: [{ country: '德国', continent: '欧洲', allowedChannels: ['91::万邦::TEST'] }] }])
})
