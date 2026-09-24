// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, reactive, type App } from 'vue'
import Template from './QuotationTemplateMatrix.vue'
import type { QuotationMatrixRow } from './types'
import type { QuotationPersonalTemplate } from '@/data/quotationTemplates'

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn() }))
vi.mock('@/services/http', () => ({ api, idempotencyKey: () => crypto.randomUUID() }))
let app: App
let workbench: InstanceType<typeof Template>
let stored: QuotationPersonalTemplate[]
const tick = async () => { for (let i = 0; i < 12; i++) await nextTick() }
function row(name: string, region = '全国统一'): QuotationMatrixRow {
  return { country: '美国', channelKey: `1::物流::${name}`, channelCode: name, ruleId: 1,
    rule: name, carrier: '物流', transport: name, quoteRegion: region, quote1: 10, quote2: 20,
    quote3: 30, quoteCustom: 50, eta: '5天', taxConfigured: true } as QuotationMatrixRow
}
function button(text: string) { return [...document.querySelectorAll('button')].find(b => b.textContent?.includes(text))! }
function inputName(name: string) {
  const input = document.querySelector<HTMLInputElement>('.create-template input')!
  input.value = name
  input.dispatchEvent(new Event('input', { bubbles: true }))
}
async function mount() {
  const state = reactive({ active: true, countries: [{ name: '美国', code: 'US', channelCount: 3, lowestQuote: 10,
    grouped: false, stage: 'common' as const, continent: '北美洲' as const, sortOrder: 1 }], contextKey: 'a',
    customQuantity: 5, adoptedCountry: '', adoptedRule: '', adoptedCarrier: '', exchangeRate: 7,
    ownerName: '测试', ownerAccount: 'TEST', ensureCountries: vi.fn(async () => true),
    quoteRowsForCountry: () => [row('带电'), row('普货'), row('化妆品')] })
  const host = document.createElement('div'); document.body.append(host)
  app = createApp({ render: () => h(Template, { ...state, ref: instance => { workbench = instance as typeof workbench } }) })
  app.mount(host); await tick()
  button('一键应用').click(); await tick()
  return state
}
beforeEach(() => {
  vi.clearAllMocks()
  stored = [{ id: 'battery', name: '带电', ownerKey: 'ACCOUNT:TEST', ownerAccount: 'TEST', ownerName: '测试',
    createdAt: '2026-09-24', updatedAt: '2026-09-24', items: [{ ...row('带电'), countryCode: 'US' }] }]
  api.get.mockImplementation(async () => structuredClone(stored))
  api.post.mockImplementation(async (_path, body) => {
    const saved = { ...structuredClone(body), id: String(stored.length), createdAt: '2026-09-24', updatedAt: '2026-09-24' }
    stored.push(saved); return structuredClone(saved)
  })
})
afterEach(() => { app?.unmount(); document.body.innerHTML = '' })

it('saves different ordinary and cosmetic selections without copying the first battery template', async () => {
  const state = await mount()
  for (const name of ['普货', '化妆品']) {
    state.active = false; await tick()
    workbench.startFromSelection([row(name)], '指定国家与渠道清单')
    state.active = true; await tick()
    expect(document.querySelector('.creation-preview')?.textContent).toContain(name)
    expect(document.querySelector('.creation-preview')?.textContent).not.toContain('带电')
    inputName(name); await tick(); button('＋ 新建模板').click(); await tick()
  }
  expect(stored.map(template => [template.name, template.items.map(item => item.channelKey)])).toEqual([
    ['带电', [row('带电').channelKey]], ['普货', [row('普货').channelKey]], ['化妆品', [row('化妆品').channelKey]],
  ])
  app.unmount(); await mount()
  const select = document.querySelector<HTMLSelectElement>('[aria-label="选择个人报价模板"]')!
  select.value = '2'; select.dispatchEvent(new Event('change')); await tick()
  button('一键应用').click(); await tick()
  expect(document.querySelector('.selected-channels')?.textContent).toContain('化妆品')
  expect(document.querySelector('.selected-channels')?.textContent).not.toContain('带电')
})

it('blocks saving old rows while a new template is loading, including rejected loads', async () => {
  const state = await mount()
  stored.push({ ...stored[0]!, id: 'ordinary', name: '普货', items: [{ ...row('普货'), countryCode: 'US' }] })
  window.dispatchEvent(new CustomEvent('milano:quotation-personal-templates-updated')); await tick()
  let reject!: (reason: Error) => void
  state.ensureCountries.mockImplementationOnce(() => new Promise<boolean>((_resolve, fail) => { reject = fail }))
  const select = document.querySelector<HTMLSelectElement>('[aria-label="选择个人报价模板"]')!
  select.value = 'ordinary'; select.dispatchEvent(new Event('change')); await tick()
  button('一键应用').click(); await tick(); button('管理我的模板').click(); await tick()
  inputName('化妆品'); await tick()
  expect(button('＋ 新建模板').disabled).toBe(true)
  expect(button('更新模板').disabled).toBe(true)
  reject(new Error('网络失败')); await tick()
  expect(button('＋ 新建模板').disabled).toBe(true)
  expect(document.querySelector('.creation-preview')?.textContent).toContain('加载失败')
  expect(api.post).not.toHaveBeenCalled()
})

it('saves channel changes made directly inside template mode as independent templates', async () => {
  await mount()
  for (const name of ['普货', '化妆品']) {
    button('移出报价单').click(); await tick(); button('添加渠道').click(); await tick()
    const candidate = [...document.querySelectorAll('.picker-list label')].find(element => element.textContent?.includes(name))!
    candidate.querySelector<HTMLInputElement>('input')!.click(); await tick()
    button('批量添加渠道').click(); await tick(); button('管理我的模板').click(); await tick()
    inputName(name); await tick(); button('＋ 新建模板').click(); await tick(); button('完成').click(); await tick()
  }
  expect(stored.map(template => template.items.map(item => item.channelKey))).toEqual(
    ['带电', '普货', '化妆品'].map(name => [row(name).channelKey]),
  )
})

it('does not overwrite later channel edits when a save response arrives and prevents duplicate submissions', async () => {
  await mount()
  button('管理我的模板').click(); await tick(); inputName('副本'); await tick()
  let finish!: () => void
  api.post.mockImplementationOnce((_path, body) => new Promise(resolve => {
    finish = () => { const saved = { ...body, id: 'copy', createdAt: '2026-09-24', updatedAt: '2026-09-24' }; stored.push(saved); resolve(saved) }
  }))
  button('＋ 新建模板').click(); await tick()
  expect(button('保存中').disabled).toBe(true)
  button('完成').click(); await tick(); button('移出报价单').click(); await tick()
  finish(); await tick()
  expect(document.querySelector('.create-template')?.textContent).toContain('0 条渠道')
  expect(api.post).toHaveBeenCalledTimes(1)
  expect(stored.at(-1)?.items[0]?.channelKey).toBe(row('带电').channelKey)
})
