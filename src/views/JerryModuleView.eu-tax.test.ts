// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, type App } from 'vue'
import { normalizeFinanceTaxSettings, calculateFinanceQuoteTax, type FinanceTaxSettings } from '@/data/financeTaxSettings'

const dependencies = vi.hoisted(() => ({ save: vi.fn() }))
vi.mock('@/data/financeTaxSettings', async original => ({ ...await original<object>(), saveFinanceTaxSettings: dependencies.save }))
vi.mock('@/services/financeSettingsWorkspace', () => ({ loadFinanceSettingsWorkspace: async () => fixture, readFinanceSettingsWorkspace: () => fixture }))
vi.mock('@/data/publishedLogisticsRepository', async original => ({ ...await original<object>(),
  loadPublishedLogisticsManifest: async () => ({manifest:{revision:'test', attributes:[], countries:[]}}), loadPublishedLogisticsRuleCatalog: async () => [],
}))
vi.mock('@/services/quotationSync', () => ({startQuotationSync: () => () => {}, loadQuotationSync: async () => ({logisticsRevision:'test'})}))
vi.mock('@/data/financeChannelPolicies', async original => ({ ...await original<object>(), channelsAvailableForCountry: (country: string) => {
  const common = { key: '1::云途::A', carrier: '云途', channel: '普通渠道A', ruleName: 'A' }
  if (country === '德国') return [common]
  if (country === '法国') return [common, { key: '2::燕文::B', carrier: '燕文', channel: '法国渠道B', ruleName: 'B' }]
  return []
} }))
import Jerry from './JerryModuleView.vue'

const fixture = {
  policies: [], countries: [], customerGrades: [], exchangeRate: { usdCny: 6.7, eurUsd: 1.16, updatedAt: '' },
  surchargeSettings: { countries: [], providers: [], updatedAt: '' },
  taxSettings: normalizeFinanceTaxSettings({ countries: [{ country: '美国', selected: true, enabled: true, fixedFeeUsd: 0.3, sortOrder: 2, providers: [] }], providers: [] }),
}
let app: App
afterEach(() => { app?.unmount(); document.body.innerHTML = '' })
async function settle() { for (let i=0;i<10;i++) { await Promise.resolve(); await nextTick() } }
function button(text: string, root: ParentNode = document) {
  const target = [...root.querySelectorAll<HTMLButtonElement>('button')].find(row => row.textContent?.trim() === text)
  expect(target, text).toBeTruthy(); return target!
}
async function mount() {
  const host=document.createElement('div'); document.body.append(host)
  app=createApp({render:()=>h(Jerry,{mode:'members'})}); app.mount(host); await settle()
  const tab=[...document.querySelectorAll<HTMLElement>('.finance-stats>[role=button]')].find(row=>row.textContent?.includes('税率设置'))!
  tab.click(); await settle()
}

it('adds an EU group, deduplicates member channels, saves and preserves drafts after save conflicts', async () => {
  dependencies.save.mockImplementation(async (value: FinanceTaxSettings) => {
    fixture.taxSettings = normalizeFinanceTaxSettings(JSON.parse(JSON.stringify(value)))
    return fixture.taxSettings
  })
  await mount()
  const add=document.querySelector<HTMLSelectElement>('[aria-label="添加税费国家"]')!
  add.value='欧盟'; add.dispatchEvent(new Event('change')); await settle()
  button('＋ 添加国家').click(); await settle()
  button('全选全部渠道（2）').click(); await settle()
  button('批量设置').click(); await settle()
  const fee=document.querySelector<HTMLInputElement>('[aria-label="原币金额"]')!
  fee.value='3.52'; fee.dispatchEvent(new Event('input')); await settle()
  button('应用到所选渠道').click(); await settle()
  button('保存并发布').click(); await settle()
  expect(dependencies.save).toHaveBeenCalledTimes(1)
  const eu=fixture.taxSettings.countries.find(row=>row.country==='欧盟')!
  expect(eu.channelRules).toHaveLength(2)
  for(const country of ['德国','法国']) expect(calculateFinanceQuoteTax(fixture.taxSettings,country,'云途',10,{channelKey:'1::云途::A'}).taxUsd).toBe(3.52)
  expect(fixture.taxSettings.countries.find(row=>row.country==='美国')!.fixedFeeUsd).toBe(0.3)
  app.unmount(); document.body.innerHTML=''; await mount()
  button('欧盟（27国）').click(); await settle()
  expect(document.querySelector('.matrix')?.textContent).toContain('$3.52')
  dependencies.save.mockRejectedValueOnce(new Error('财务设置已被其他用户修改，请刷新后重试'))
  button('全选全部渠道（2）').click(); await settle(); button('批量设置').click(); await settle()
  const updated=document.querySelector<HTMLInputElement>('[aria-label="原币金额"]')!
  updated.value='4'; updated.dispatchEvent(new Event('input')); await settle(); button('应用到所选渠道').click(); await settle()
  button('保存并发布').click(); await settle()
  expect(document.querySelector('.matrix')?.textContent).toContain('$4.00')
  expect(document.body.textContent).toContain('其他用户修改')
  expect(calculateFinanceQuoteTax(fixture.taxSettings,'德国','云途',10,{channelKey:'1::云途::A'}).taxUsd).toBe(3.52)
})
