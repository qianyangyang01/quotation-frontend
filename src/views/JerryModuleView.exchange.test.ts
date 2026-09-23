// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, type App } from 'vue'
const fixture = vi.hoisted(() => ({
  policies: [{ id: 'policy', category: '普货', enabled: true, countryRules: [], note: '' }],
  countries: [], customerGrades: [] as { grade: string; coefficient: number; enabled: boolean }[], exchangeRate: { usdCny: 6.7, updatedAt: '' },
  taxSettings: { countries: [], providers: [], updatedAt: '' },
  surchargeSettings: { countries: [{ country: '新西兰', selected: true, enabled: true, fixedFeeUsd: 1.5, sortOrder: 1, providers: [{ provider: '测试', mode: 'exempt', selected: true, channels: [] }] }, { country: '英国', selected: true, enabled: true, fixedFeeUsd: 0.5, sortOrder: 2, providers: [{ provider: '测试', mode: 'taxable', selected: true, channels: [] }] }], providers: [], updatedAt: '' },
}))
vi.mock('@/services/financeSettingsWorkspace', () => ({ loadFinanceSettingsWorkspace: async () => fixture, readFinanceSettingsWorkspace: () => fixture }))
vi.mock('@/data/publishedLogisticsRepository', async importOriginal => ({ ...await importOriginal<object>(),
  loadPublishedLogisticsManifest: async () => ({manifest:{revision:'test', attributes:[], countries:[]}}),
  loadPublishedLogisticsRuleCatalog: async () => [],
}))
vi.mock('@/services/quotationSync', () => ({startQuotationSync: () => () => {}, loadQuotationSync: async () => ({logisticsRevision:'test'})}))
const saveEuro = vi.hoisted(() => vi.fn())
const saveGrades = vi.hoisted(() => vi.fn(async value => value))
vi.mock('@/data/financeChannelPolicies', async importOriginal => ({ ...await importOriginal<object>(), saveCustomerGradeSettings: saveGrades, saveFinanceEurUsdRate: saveEuro, channelsAvailableForCountry: () => [{ key: '1::测试::A', carrier: '测试', channel: '渠道A' }, { key: '2::测试::B', carrier: '测试', channel: '渠道B' }] }))
import Jerry from './JerryModuleView.vue'
let app: App
afterEach(() => { app?.unmount(); document.body.innerHTML = '' })


it('keeps failed euro edits visible, blocks concurrent saves, then shows the saved rate', async () => {
  const host=document.createElement('div');document.body.append(host)
  app=createApp({render:()=>h(Jerry,{mode:'members'})});app.mount(host)
  for(let i=0;i<8;i++){await Promise.resolve();await nextTick()}
  ;[...host.querySelectorAll<HTMLElement>('.finance-stats>[role=button]')].find(e=>e.textContent?.includes('汇率设置'))!.click();await nextTick()
  const field=host.querySelector<HTMLInputElement>('input[aria-label="欧元兑美元汇率"]')!
  expect(field.value).toBe('')
  field.value='1.23456';field.dispatchEvent(new Event('input'));await nextTick()
  const buttons=[...host.querySelectorAll<HTMLButtonElement>('.exchange-settings footer button')]
  const euro=buttons.find(b=>b.textContent==='保存欧元汇率')!
  let reject!: (error:Error)=>void
  saveEuro.mockImplementationOnce(()=>new Promise((_resolve,fail)=>{reject=fail}))
  euro.click();euro.click();await nextTick()
  expect(saveEuro).toHaveBeenCalledExactlyOnceWith(1.23456)
  expect(buttons.every(b=>b.disabled)).toBe(true)
  reject(new Error('设置已被其他人修改，请刷新后重试'))
  for(let i=0;i<4;i++){await Promise.resolve();await nextTick()}
  expect(field.value).toBe('1.23456')
  expect(host.textContent).toContain('设置已被其他人修改')
  expect(buttons.every(b=>!b.disabled)).toBe(true)
  saveEuro.mockResolvedValueOnce({usdCny:6.7,eurUsd:1.23456,updatedAt:'test'})
  euro.click()
  for(let i=0;i<4;i++){await Promise.resolve();await nextTick()}
  expect(saveEuro).toHaveBeenCalledTimes(2)
  expect(host.textContent).toContain('欧元兑美元汇率已保存')
})
