// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, type App } from 'vue'
const data = vi.hoisted(() => ({
  save: vi.fn(async (value: unknown) => value),
  options: [] as Array<{ key: string; carrier: string; channel: string; ruleName: string; channelCode: string; missingQuoteRegions: string[] }>,
  workspace: {
    policies: [{ id: 'policy', category: '普货', enabled: true, updatedAt: '', countryRules: [{ country: '德国', stage: 'standard', continent: '欧洲', sortOrder: 1, allowedChannels: ['91::万邦::TEST'], unavailableChannels: [{ legacyKey: 'old::旧物流::OLD', providerName: '旧物流', channelName: '旧渠道', status: 'unavailable', reason: '', backupSha256: '' }] }] }],
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
vi.mock('@/data/financeChannelPolicies', async original => ({ ...await original<object>(), channelsAvailableForCountry: () => data.options, countriesAvailableForCategory: () => data.options.length ? [{ name: '德国', code: 'DE' }] : [], saveFinanceChannelPolicies: data.save }))
import Jerry from './JerryModuleView.vue'
let app: App
beforeEach(() => { data.options = [] })
afterEach(() => { app?.unmount(); document.body.innerHTML = ''; vi.clearAllMocks() })
async function flush() { for (let i = 0; i < 15; i++) { await Promise.resolve(); await nextTick() } }
function button(text: string) { return [...document.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent === text)! }

async function openEditor() {
  const host = document.createElement('div'); document.body.append(host)
  const errors = vi.fn()
  app = createApp({ render: () => h(Jerry, { mode: 'members' }) }); app.config.errorHandler = errors; app.mount(host)
  await flush()
  const card = [...document.querySelectorAll<HTMLElement>('.finance-stats>[role=button]')].find(card => card.textContent?.includes('物流属性与渠道'))!
  card.click(); await flush()
  expect(document.body.textContent).not.toContain('暂不可用')
  expect(document.body.textContent).not.toContain('旧渠道')
  button('统一维护').click(); await flush()
  expect(document.querySelector('.finance-editor')?.textContent).not.toContain('原授权保留')
  expect(document.querySelector('.finance-editor')?.textContent).not.toContain('待审旧渠道')
  return errors
}

it('hides disabled and legacy channels but preserves all bindings when saving; re-enabled channels recover their selection', async () => {
  const errors = await openEditor()
  expect(document.querySelector('.finance-editor')?.textContent).not.toContain('TEST')
  expect(document.querySelectorAll('.country-carrier-grid input')).toHaveLength(0)
  const attribute = document.querySelector<HTMLInputElement>('.finance-attribute-combobox input')!
  attribute.dispatchEvent(new Event('blur')); await new Promise(resolve => setTimeout(resolve, 150)); await flush()
  button('保存设置').click(); await flush()
  expect(errors).not.toHaveBeenCalled()
  expect(data.save).toHaveBeenCalledOnce()
  expect(data.save.mock.calls[0]![0]).toMatchObject([{ countryRules: [{ country: '德国', continent: '欧洲', allowedChannels: ['91::万邦::TEST'], unavailableChannels: data.workspace.policies[0]!.countryRules[0]!.unavailableChannels }] }])
  data.options = [{ key: '91::万邦::TEST', carrier: '万邦', channel: '重新启用渠道', ruleName: '专线', channelCode: 'TEST', missingQuoteRegions: [] }]
  button('统一维护').click(); await flush()
  button('展开渠道 ↓').click(); await flush()
  expect(document.querySelector<HTMLInputElement>('.country-carrier-grid input')?.checked).toBe(true)
  expect(document.querySelector('.country-carrier-grid')?.textContent).toContain('重新启用渠道')
})

it('selecting all currently enabled channels keeps hidden previously selected channels and legacy bindings', async () => {
  data.options = [{ key: '92::万邦::ACTIVE', carrier: '万邦', channel: '启用渠道', ruleName: '专线', channelCode: 'ACTIVE', missingQuoteRegions: [] }]
  const errors = await openEditor()
  button('展开渠道 ↓').click(); await flush()
  expect(document.querySelectorAll('.country-carrier-grid input')).toHaveLength(1)
  expect(document.querySelector('.finance-editor')?.textContent).not.toContain('TEST')
  button('全选该物流商（1）').click(); await flush()
  button('保存设置').click(); await flush()
  expect(errors).not.toHaveBeenCalled()
  expect(data.save.mock.calls[0]![0]).toMatchObject([{ countryRules: [{ allowedChannels: ['91::万邦::TEST', '92::万邦::ACTIVE'], unavailableChannels: data.workspace.policies[0]!.countryRules[0]!.unavailableChannels }] }])
})
