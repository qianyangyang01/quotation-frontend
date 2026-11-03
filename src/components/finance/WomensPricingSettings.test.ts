// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import WomensPricingSettings from './WomensPricingSettings.vue'
import { clearFinanceSettingsCache, hydrateFinanceSettings } from '@/services/financeSettings'
import { defaultWomensPricing, loadWomensPricing, WOMENS_PRICING_UPDATED } from '@/data/womensPricing'

const http = vi.hoisted(() => ({ get: vi.fn(), put: vi.fn() }))
vi.mock('@/services/http', () => ({ api: http }))
let app: App, host: HTMLDivElement
async function settle() { for (let i = 0; i < 6; i++) { await Promise.resolve(); await nextTick() } }
function input(label: string) { return host.querySelector<HTMLInputElement>(`[aria-label="${label}"]`)! }
async function fill(label: string, value: string) { input(label).value = value; input(label).dispatchEvent(new Event('input', { bubbles: true })); await nextTick() }
function button(text: string) { return [...host.querySelectorAll<HTMLButtonElement>('button')].find(b => b.textContent?.trim() === text)! }
beforeEach(async () => {
  clearFinanceSettingsCache(); http.get.mockReset(); http.put.mockReset()
  http.get.mockResolvedValue({
    'country-classification': { value: [], _version: 0 }, 'channel-policies': { value: [], _version: 0 },
    'customer-grades': { value: [], _version: 0 }, 'exchange-rate': { value: { usdCny: 7 }, _version: 0 },
    'tax-settings': { value: {}, _version: 0 }, 'womens-pricing': { value: defaultWomensPricing(), _version: 2 },
  })
  await hydrateFinanceSettings()
  http.put.mockImplementation(async (_url, value) => ({ value: structuredClone(value), _version: 3 }))
  host = document.createElement('div'); document.body.append(host); app = createApp(WomensPricingSettings); app.mount(host)
})
afterEach(() => { app.unmount(); document.body.innerHTML = ''; clearFinanceSettingsCache(); vi.restoreAllMocks() })

it('shows the editable reference factor and tiers without an amount trial panel',()=>{
  expect(input('女装选档基准系数').value).toBe('1.23')
  expect(host.querySelector('[aria-label=女装系数试算金额]')).toBeNull()
  expect(host.querySelector('.trial')).toBeNull()
  expect(host.textContent).not.toContain('综合成本 USD')
})
it.each(['0','-1','11','1.1234567',''])('blocks invalid coefficient %s before publication', async value => {
  await fill('第1档系数', value)
  expect(host.querySelector('[role=alert]')).not.toBeNull()
  expect(button('保存设置').disabled).toBe(true)
  button('保存设置').click(); await nextTick()
  expect(http.put).not.toHaveBeenCalled()
})
it('updates adjacent bounds, rejects reversed ranges and leaves the published cache intact', async () => {
  await fill('第1档上限','22')
  expect(host.querySelectorAll('tbody tr')[1]?.querySelector('td')?.textContent).toBe('22')
  expect(loadWomensPricing()!.tiers[0]!.toUsd).toBe(20)
  await fill('第1档上限','31')
  expect(button('保存设置').disabled).toBe(true)
})
it('adds and removes tiers while retaining continuous coverage and a final open range', async () => {
  button('＋ 增加档位').click(); await nextTick()
  expect(host.querySelectorAll('tbody tr')).toHaveLength(5)
  expect(input('第4档上限').value).toBe('70')
  button('删除档位').click(); await nextTick()
  expect(host.querySelectorAll('tbody tr')).toHaveLength(4)
  expect(host.querySelector('tbody td')?.textContent).toBe('0')
  expect(host.querySelector('tbody tr:last-child')?.textContent).toContain('无上限')
  expect(host.querySelector('[role=alert]')).toBeNull()
})
it('saves once from the top button with the correct version and updates the cache immediately', async () => {
  const event = vi.fn(); window.addEventListener(WOMENS_PRICING_UPDATED,event)
  expect(host.querySelector('header')?.contains(button('保存设置'))).toBe(true)
  expect(host.textContent).not.toContain('预览发布变更')
  await fill('第1档系数','1.29')
  expect(http.put).not.toHaveBeenCalled()
  button('保存设置').click(); await settle()
  expect(http.put).toHaveBeenCalledOnce()
  expect(http.put).toHaveBeenCalledWith('/finance-settings/womens-pricing', expect.objectContaining({tiers:expect.arrayContaining([expect.objectContaining({coefficient:1.29})])}), {'If-Match':'2'})
  expect(loadWomensPricing()).toMatchObject({version:3,tiers:expect.arrayContaining([expect.objectContaining({coefficient:1.29})])})
  expect(host.textContent).toContain('已生效 V4'); expect(event).toHaveBeenCalledOnce()
  expect(host.textContent).toContain('设置已保存并生效')
  await fill('第1档系数','1.3')
  expect(host.textContent).not.toContain('设置已保存并生效')
  window.removeEventListener(WOMENS_PRICING_UPDATED,event)
})
it('blocks duplicate publishes, preserves edits on conflict and never displays a false success', async () => {
  let reject!: (error: Error) => void
  http.put.mockImplementationOnce(() => new Promise((_resolve, fail) => { reject = fail }))
  await fill('第1档系数','1.3')
  const publish = button('保存设置'); publish.click(); publish.click(); await nextTick()
  expect(http.put).toHaveBeenCalledOnce(); expect(publish.disabled).toBe(true)
  reject(new Error('财务设置已被其他用户修改，请刷新后重试')); await settle()
  expect(host.textContent).toContain('已被其他用户修改'); expect(host.textContent).not.toContain('设置已保存并生效')
  expect(input('第1档系数').value).toBe('1.3'); expect(loadWomensPricing()!.version).toBe(2)
})
it('can publish disabled status while retaining all saved tier values', async () => {
  host.querySelector<HTMLInputElement>('input[type=checkbox]')!.click(); await nextTick()
  button('保存设置').click(); await settle()
  expect(loadWomensPricing()).toMatchObject({enabled:false,tiers:defaultWomensPricing().tiers})
})

it('saves the configurable reference factor with one click and no hidden tier changes',async()=>{
  await fill('女装选档基准系数','1.5')
  expect(loadWomensPricing()!.referenceCoefficient).toBe(1.23)
  button('保存设置').click();await settle()
  expect(http.put).toHaveBeenCalledOnce()
  expect(loadWomensPricing()).toMatchObject({schemaVersion:2,basis:'reference-price-usd',referenceCoefficient:1.5,tiers:defaultWomensPricing().tiers})
})
it.each(['0','-1','11','1.1234567',''])('rejects invalid reference factor %s without publishing',async value=>{
  await fill('女装选档基准系数',value)
  expect(button('保存设置').disabled).toBe(true)
  expect(http.put).not.toHaveBeenCalled()
})
