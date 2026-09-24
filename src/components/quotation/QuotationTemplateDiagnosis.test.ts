// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, reactive, type App } from 'vue'
import Template from './QuotationTemplateMatrix.vue'
import type { QuotationMatrixRow } from './types'
import type { QuotationPersonalTemplate } from '@/data/quotationTemplates'

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn() }))
vi.mock('@/services/http', () => ({ api, idempotencyKey: () => crypto.randomUUID() }))
let app: App
let stored: QuotationPersonalTemplate[]
const tick = async () => { for (let i = 0; i < 12; i++) await nextTick() }
function row(name: string, region = '全国统一'): QuotationMatrixRow {
  return { country: '美国', channelKey: `1::物流::${name}`, channelCode: name, ruleId: 1,
    rule: name, carrier: '物流', transport: name, quoteRegion: region, quote1: 10, quote2: 20,
    quote3: 30, quoteCustom: 50, eta: '5天', taxConfigured: true } as QuotationMatrixRow
}
function button(text: string) { return [...document.querySelectorAll('button')].find(b => b.textContent?.includes(text))! }
async function mount() {
  const state = reactive({ active: true, countries: [{ name: '美国', code: 'US', channelCount: 3, lowestQuote: 10,
    grouped: false, stage: 'common' as const, continent: '北美洲' as const, sortOrder: 1 }], contextKey: 'a',
    customQuantity: 5, adoptedCountry: '', adoptedRule: '', adoptedCarrier: '', exchangeRate: 7,
    ownerName: '测试', ownerAccount: 'TEST', ensureCountries: vi.fn(async () => true),
    quoteRowsForCountry: () => [row('带电'), row('普货'), row('化妆品')] })
  const host = document.createElement('div'); document.body.append(host)
  app = createApp({ render: () => h(Template, { ...state }) })
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

beforeEach(() => {
  stored[0]!._version = 0
  api.put.mockImplementation(async (path, body) => {
    const target = stored.find(t => path.endsWith('/' + t.id))!
    if (body._version !== target._version) throw new Error('version conflict')
    Object.assign(target, structuredClone(body), { _version: target._version! + 1 })
    return structuredClone(target)
  })
})
async function replaceCurrent(name: string) {
  button('移出报价单').click(); await tick(); button('添加渠道').click(); await tick()
  const candidate = [...document.querySelectorAll('.picker-list label')].find(element => element.textContent?.includes(name))!
  candidate.querySelector<HTMLInputElement>('input')!.click(); await tick()
  button('批量添加渠道').click(); await tick()
}

it('blocks updating a different applied template when the dropdown selection changes', async () => {
  stored.push({ ...stored[0]!, id: 'cosmetic', name: '化妆品', items: [{ ...row('化妆品'), countryCode: 'US' }] })
  await mount(); await replaceCurrent('普货')
  const select = document.querySelector<HTMLSelectElement>('[aria-label="选择个人报价模板"]')!
  select.value = 'cosmetic'; select.dispatchEvent(new Event('change')); await tick()
  expect(button('更新模板').textContent).toContain('带电')
  expect(button('更新模板').disabled).toBe(true)
  expect(document.querySelector('.template-conflict')?.textContent).toContain('选择的模板与当前应用不同')
  button('更新模板').click(); await tick()
  expect(api.put).not.toHaveBeenCalled()
})

it('keeps the original edit version when copying refreshes the list after a concurrent edit', async () => {
  await mount()
  stored[0]!.items = [{ ...row('化妆品'), countryCode: 'US' }]
  stored[0]!._version = 1
  button('管理我的模板').click(); await tick()
  const copyButton = document.querySelector<HTMLButtonElement>('.manager-actions button:nth-last-child(2)')!
  expect(copyButton.textContent).toBe('复制')
  copyButton.click(); await tick(); button('完成').click(); await tick()
  const select = document.querySelector<HTMLSelectElement>('[aria-label="选择个人报价模板"]')!
  select.value = 'battery'; select.dispatchEvent(new Event('change')); await tick()
  expect(document.querySelector('.selected-channels')?.textContent).toContain('带电')
  expect(button('更新模板').disabled).toBe(true)
  expect(document.querySelector('.template-conflict')?.textContent).toContain('模板已被修改')
  button('更新模板').click(); await tick()
  expect(api.put).not.toHaveBeenCalled()
  expect(stored[0]!.items[0]!.transport).toBe('化妆品')
})

it('requires explicit difference confirmation and preserves later edits without duplicate submissions', async () => {
  await mount(); await replaceCurrent('化妆品')
  button('更新模板').click(); await tick()
  expect(api.put).not.toHaveBeenCalled()
  expect(document.querySelector('.update-confirmation h2')?.textContent).toContain('带电')
  expect(document.querySelector('.update-diff > section:first-child')?.textContent).toContain('带电')
  expect(document.querySelector('.update-diff > section:nth-child(2)')?.textContent).toContain('化妆品')
  let finish!: () => void
  const save = api.put.getMockImplementation()!
  api.put.mockImplementationOnce((...args) => new Promise(resolve => { finish = async () => { resolve(await save(...args)) } }))
  button('确认保存模板变更').click(); await tick()
  expect(button('保存中').disabled).toBe(true)
  button('保存中').click(); await tick()
  expect(api.put).toHaveBeenCalledTimes(1)
  expect(api.put.mock.calls[0]![1]).toMatchObject({ _version: 0, _confirmedUpdate: { templateId: 'battery', baseVersion: 0, source: 'template-channel-confirmation' } })
  finish(); await tick()
  expect(stored[0]!.items[0]!.transport).toBe('化妆品')
  expect(document.querySelector('.update-confirmation')).toBeNull()
  await replaceCurrent('普货'); button('更新模板').click(); await tick()
  button('确认保存模板变更').click(); await tick()
  expect(api.put.mock.calls[1]![1]._version).toBe(1)
  expect(stored[0]!.items[0]!.transport).toBe('普货')
})

it('does not save on cancel or applying, recalculating and removing temporary channels', async () => {
  const state = await mount(); state.contextKey = 'weight-changed'; await tick()
  await replaceCurrent('化妆品'); button('更新模板').click(); await tick()
  document.querySelector<HTMLButtonElement>('.update-confirmation footer button')!.click(); await tick()
  expect(api.put).not.toHaveBeenCalled()
  expect(stored[0]!.items[0]!.transport).toBe('带电')
})

it('rejects a concurrent server edit occurring after confirmation opens without advancing the baseline', async () => {
  await mount(); await replaceCurrent('普货'); button('更新模板').click(); await tick()
  stored[0]!.items = [{ ...row('化妆品'), countryCode: 'US' }]; stored[0]!._version = 1
  button('确认保存模板变更').click(); await tick()
  expect(api.put.mock.calls[0]![1]._version).toBe(0)
  expect(stored[0]!._version).toBe(1)
  expect(stored[0]!.items[0]!.transport).toBe('化妆品')
  expect(document.querySelector('.update-confirmation')?.textContent).toContain('version conflict')
})

it('invalidates a pending confirmation if a list refresh discovers a new version', async () => {
  await mount(); await replaceCurrent('普货'); button('更新模板').click(); await tick()
  stored[0]!._version = 1
  window.dispatchEvent(new CustomEvent('milano:quotation-personal-templates-updated')); await tick()
  expect(button('确认保存模板变更').disabled).toBe(true)
  button('确认保存模板变更').click(); await tick()
  expect(api.put).not.toHaveBeenCalled()
})

it('requires reapplying a template after restoring an unversioned draft selection', async () => {
  const state = await mount()
  Object.assign(state, { draftTemplate: { id: 'battery', name: '带电' }, draftSelection: [row('普货')], draftVersion: 1 })
  await tick()
  expect(button('更新模板').disabled).toBe(true)
  expect(document.querySelector('.template-conflict')?.textContent).toContain('恢复的临时清单不能直接覆盖模板')
})
