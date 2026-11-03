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
const saveGrades = vi.hoisted(() => vi.fn(async value => value))
vi.mock('@/data/financeChannelPolicies', async importOriginal => ({ ...await importOriginal<object>(), saveCustomerGradeSettings: saveGrades, channelsAvailableForCountry: () => [{ key: '1::测试::A', carrier: '测试', channel: '渠道A' }, { key: '2::测试::B', carrier: '测试', channel: '渠道B' }] }))
import Jerry from './JerryModuleView.vue'
import { normalizeCustomerGradeSettings } from '@/data/financeChannelPolicies'
let app: App
afterEach(() => { app?.unmount(); document.body.innerHTML = ''; saveGrades.mockClear() })

it('keeps both coefficient editors inside one card and preserves independent unsaved edits when switching', async () => {
  fixture.customerGrades = normalizeCustomerGradeSettings([{grade:'S',coefficient:1.2,enabled:true}])
  window.localStorage.setItem('milano.finance-settings-card-order.v1', JSON.stringify(['womens','grades','countries']))
  const host=document.createElement('div');document.body.append(host)
  app=createApp({render:()=>h(Jerry,{mode:'members'})});app.mount(host)
  for(let i=0;i<8;i++){await Promise.resolve();await nextTick()}
  const cards=[...host.querySelectorAll<HTMLElement>('.finance-stats>[role=button]')]
  expect(cards).toHaveLength(8)
  expect(cards.some(e=>e.textContent?.includes('女装报价系数'))).toBe(false)
  cards.find(e=>e.textContent?.includes('客户系数'))!.click();await nextTick()
  const grade=host.querySelector<HTMLInputElement>('.grade-grid input[type=number]')!
  grade.value='1.42';grade.dispatchEvent(new Event('input'));await nextTick()
  const tabs=host.querySelectorAll<HTMLButtonElement>('[role=tab]')
  tabs[1]!.click();await nextTick()
  expect(tabs[1]!.getAttribute('aria-selected')).toBe('true')
  const women=host.querySelector<HTMLInputElement>('[aria-label="第1档系数"]')!
  women.value='1.28';women.dispatchEvent(new Event('input',{bubbles:true}));await nextTick()
  tabs[0]!.click();await nextTick();expect(grade.value).toBe('1.42')
  tabs[1]!.click();await nextTick();expect(women.value).toBe('1.28')
  expect(saveGrades).not.toHaveBeenCalled()
  window.localStorage.removeItem('milano.finance-settings-card-order.v1')
})

it('lets finance configure and enable NEW independently, rejecting invalid input before saving', async () => {
  fixture.customerGrades = normalizeCustomerGradeSettings([{grade:'S',coefficient:1.21605,enabled:true},{grade:'E',coefficient:1.27635,enabled:true}])
  const host=document.createElement('div');document.body.append(host)
  app=createApp({render:()=>h(Jerry,{mode:'members'})});app.mount(host)
  for(let i=0;i<8;i++){await Promise.resolve();await nextTick()}
  ;[...host.querySelectorAll<HTMLElement>('.finance-stats>[role=button]')].find(e=>e.textContent?.includes('客户系数'))!.click();await nextTick()
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

it('shows failed grade saves, blocks duplicate submission and reads back the confirmed coefficient', async () => {
  fixture.customerGrades = normalizeCustomerGradeSettings([{ grade: 'S', coefficient: 1.2, enabled: true }])
  const host = document.createElement('div'); document.body.append(host)
  app = createApp({ render: () => h(Jerry, { mode: 'members' }) }); app.mount(host)
  const settle = async () => { for (let i = 0; i < 8; i++) { await Promise.resolve(); await nextTick() } }
  await settle()
  ;[...host.querySelectorAll<HTMLElement>('.finance-stats>[role=button]')].find(e => e.textContent?.includes('客户系数'))!.click()
  await settle()
  const save = host.querySelector<HTMLButtonElement>('.grade-settings>header button')!
  let fail!: (reason: Error) => void
  saveGrades.mockImplementationOnce(() => new Promise((_resolve, reject) => { fail = reject }))
  save.click(); await nextTick(); save.click(); await nextTick()
  expect(saveGrades).toHaveBeenCalledOnce()
  expect(save.disabled).toBe(true)
  fail(new Error('财务设置已被其他账号更新，请重新加载'))
  await settle()
  expect(host.textContent).toContain('财务设置已被其他账号更新，请重新加载')
  expect(host.textContent).not.toContain('客户等级计算系数已保存')
  expect(save.disabled).toBe(false)
  saveGrades.mockResolvedValueOnce(normalizeCustomerGradeSettings([{ grade: 'S', coefficient: 1.4, enabled: true }]))
  save.click(); await settle()
  const row = [...host.querySelectorAll('.grade-grid>label')].find(e => e.querySelector('strong')?.textContent?.includes('S'))!
  expect(row.querySelector<HTMLInputElement>('input[type=number]')!.value).toBe('1.4')
})
