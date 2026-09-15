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

it('adds one EU row, unions and deduplicates member channels, saves and reloads independent provider settings', async () => {
  dependencies.save.mockImplementation(async (value: FinanceTaxSettings) => {
    fixture.taxSettings = normalizeFinanceTaxSettings(JSON.parse(JSON.stringify(value)))
    return fixture.taxSettings
  })
  await mount()
  button('＋ 添加欧盟（27国）').click(); await settle()
  expect(document.querySelectorAll('[aria-label="欧盟关税"]')).toHaveLength(1)
  const fee=document.querySelector<HTMLInputElement>('[aria-label="欧盟关税"]')!
  fee.value='3.52'; fee.dispatchEvent(new Event('input',{bubbles:true})); await settle()
  expect(document.querySelector('.tax-eu-scope')?.textContent).toContain('马耳他（MT）')
  expect(document.querySelector('.tax-eu-scope')?.textContent).toContain('单独国家设置优先')
  const hidden={provider:'暂时停用物流商',selected:true,mode:'exempt' as const,channels:[]}
  fixture.taxSettings.countries.find(row=>row.country==='欧盟')!.providers!.push(hidden)
  const panel=document.querySelector('.tax-provider-global')!
  button('＋ 添加物流商',panel).click(); await settle()
  const select=panel.querySelector<HTMLSelectElement>('select')!
  expect([...select.options].map(row=>row.textContent)).toEqual(expect.arrayContaining(['云途 · 1个渠道','燕文 · 1个渠道']))
  select.value='云途'; select.dispatchEvent(new Event('change',{bubbles:true})); await settle()
  button('确认添加',panel).click(); await settle()
  button('保存并发布').click(); await settle()
  expect(dependencies.save).toHaveBeenCalledTimes(1)
  const eu=fixture.taxSettings.countries.find(row=>row.country==='欧盟')!
  expect(eu).toMatchObject({selected:true,enabled:true,fixedFeeUsd:3.52})
  expect(eu.providers).toContainEqual(hidden)
  expect(calculateFinanceQuoteTax(fixture.taxSettings,'德国','云途',10).taxUsd).toBe(3.52)
  expect(calculateFinanceQuoteTax(fixture.taxSettings,'法国','云途',10).taxUsd).toBe(3.52)
  expect(fixture.taxSettings.countries.find(row=>row.country==='美国')!.fixedFeeUsd).toBe(0.3)
  app.unmount(); document.body.innerHTML=''; await mount()
  button('欧盟（27国） ›').click(); await settle()
  expect(document.querySelector<HTMLInputElement>('[aria-label="欧盟关税"]')!.value).toBe('3.52')
  expect(document.querySelector('.tax-provider-global button.active')?.textContent).toBe('不免税')
  button('免税',document.querySelector('.tax-provider-global')!).click(); await settle()
  button('保存并发布').click(); await settle()
  expect(calculateFinanceQuoteTax(fixture.taxSettings,'德国','云途',10).feeMode).toBe('exempt')
  document.querySelector<HTMLButtonElement>('[aria-label="删除欧盟关税设置"]')!.click(); await settle()
  button('保存并发布').click(); await settle()
  expect(calculateFinanceQuoteTax(fixture.taxSettings,'德国','云途',10).feeMode).toBe('no-tax')
})
