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
const saveGrades = vi.hoisted(() => vi.fn().mockResolvedValue(undefined))
vi.mock('@/data/financeChannelPolicies', async importOriginal => ({ ...await importOriginal<object>(), saveCustomerGradeSettings: saveGrades, channelsAvailableForCountry: () => [{ key: '1::测试::A', carrier: '测试', channel: '渠道A' }, { key: '2::测试::B', carrier: '测试', channel: '渠道B' }] }))
import Jerry from './JerryModuleView.vue'
import { normalizeCustomerGradeSettings } from '@/data/financeChannelPolicies'
let app: App
afterEach(() => { app?.unmount(); document.body.innerHTML = '' })

it('lets finance configure and enable NEW independently, rejecting invalid input before saving', async () => {
  fixture.customerGrades = normalizeCustomerGradeSettings([{grade:'S',coefficient:1.21605,enabled:true},{grade:'E',coefficient:1.27635,enabled:true}])
  const host=document.createElement('div');document.body.append(host)
  app=createApp({render:()=>h(Jerry,{mode:'members'})});app.mount(host)
  for(let i=0;i<8;i++){await Promise.resolve();await nextTick()}
  ;[...host.querySelectorAll<HTMLElement>('.finance-stats>[role=button]')].find(e=>e.textContent?.includes('客户等级系数'))!.click();await nextTick()
  expect(host.querySelectorAll('.grade-grid>label')).toHaveLength(7)
  const row=[...host.querySelectorAll('.grade-grid>label')].find(e=>e.textContent?.includes('新客户'))!
  const input=row.querySelector<HTMLInputElement>('input[type=number]')!
  const enabled=row.querySelector<HTMLInputElement>('input[type=checkbox]')!
  expect(input.value).toBe('1.27635');expect(enabled.checked).toBe(false)
  const save=host.querySelector<HTMLButtonElement>('.grade-settings>header button')!
  input.value='0';input.dispatchEvent(new Event('input'));await nextTick();save.click();await nextTick()
  expect(saveGrades).not.toHaveBeenCalled()
  input.value='1.45678';input.dispatchEvent(new Event('input'));enabled.click();await nextTick();save.click();await nextTick()
  expect(saveGrades).toHaveBeenCalledOnce()
  expect(saveGrades.mock.calls[0]![0]).toEqual(expect.arrayContaining([{grade:'NEW',coefficient:1.45678,enabled:true},{grade:'S',coefficient:1.21605,enabled:true},{grade:'E',coefficient:1.27635,enabled:true}]))
})
