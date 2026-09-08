// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, reactive, type App, type Component } from 'vue'
import CommonMatrix from './QuotationCommonMatrix.vue'
import Matrix from './QuotationMatrix.vue'
import QuoteTaxMeta from './QuoteTaxMeta.vue'
import { createCountryQuotationCache } from '@/services/countryQuotationCache'
import type { QuotationCountrySummary, QuotationMatrixRow } from './types'

let app: App
afterEach(() => { app?.unmount(); document.body.innerHTML = '' })
const countries = ['美国', '澳大利亚'].map((name, index) => ({
  name, code: index ? 'AU' : 'US', stage: 'common', sortOrder: index, channelCount: 12,
})) as QuotationCountrySummary[]
function row(country: string, index: number): QuotationMatrixRow {
  return { country, channelKey: String(index), rule: String(index), carrier: '测试物流', transport: '渠道' + index,
    quote1: 10, quote2: 20, quote3: 30, quoteCustom: 50, freight: 5, eta: '5～8 天',
    taxConfigured: true, taxIncluded: false, taxFeeMode: 'no-tax', taxLabel: '无关税',
  } as QuotationMatrixRow
}
function mount(component: Component, state: Record<string, unknown>) {
  const host = document.createElement('div'); document.body.append(host)
  app = createApp({ render: () => h(component, state) }); app.mount(host)
}
function button(text: string) {
  return [...document.querySelectorAll('button')].find(b => b.textContent?.includes(text))!
}

it.each(['specified', 'template'])('keeps the same AU channel in independent regions in %s mode', async variant => {
  const rows = [3, 4, 5].map(region => ({ ...row('澳大利亚', 1), quoteRegion: `澳大利亚${region}区`, quote1: region * 10 }))
  const changed = vi.fn()
  mount(Matrix, { active: true, variant, countries: [{ ...countries[1], quoteRegions: rows.map(r => r.quoteRegion) }],
    contextKey: 'v1', customQuantity: 5, exchangeRate: 7, quoteRowsForCountry: () => rows,
    presetVersion: 1, presetSelection: rows.slice(0, 2), onSelectionChange: changed })
  await nextTick()
  expect(changed.mock.lastCall?.[0].map((r: QuotationMatrixRow) => [r.quoteRegion, r.quote1])).toEqual([['澳大利亚3区', 30], ['澳大利亚4区', 40]])
  const select = document.querySelector('.selected-channels select') as HTMLSelectElement
  select.value = '澳大利亚5区'; select.dispatchEvent(new Event('change', { bubbles: true })); await nextTick()
  expect(changed.mock.lastCall?.[0].map((r: QuotationMatrixRow) => [r.quoteRegion, r.quote1])).toEqual([['澳大利亚4区', 40], ['澳大利亚5区', 50]])
  const selections = document.querySelectorAll('.selected-channels select')
  const first = selections[0] as HTMLSelectElement
  first.value = '澳大利亚5区'; first.dispatchEvent(new Event('change', { bubbles: true })); await nextTick()
  expect(document.body.textContent).toContain('报价方案已存在')
  expect(changed.mock.lastCall?.[0]).toHaveLength(2)
  button('移出报价单').click(); await nextTick()
  expect(changed.mock.lastCall?.[0]).toHaveLength(1)
  expect(changed.mock.lastCall?.[0][0].quoteRegion).toBe('澳大利亚5区')
})

it('does not silently assign a region to a legacy regional template', async () => {
  const changed = vi.fn()
  mount(Matrix, { variant: 'template', countries: [countries[1]], contextKey: 'v1', customQuantity: 5,
    exchangeRate: 7, quoteRowsForCountry: () => [{ ...row('澳大利亚', 1), quoteRegion: '澳大利亚3区' }],
    presetVersion: 1, presetSelection: [{ country: '澳大利亚', channelKey: '1' }], onSelectionChange: changed })
  await nextTick()
  expect(changed.mock.lastCall?.[0]).toEqual([])
  expect(document.body.textContent).toContain('旧模板未保存区域')
})
it('searches, sorts and pages without re-running pricing and suspends hidden common mode', async () => {
  const calculate = vi.fn((country: string) => Array.from({ length: 12 }, (_, i) => row(country, i)))
  const props = reactive({ active: true, countries, contextKey: 'v1', quoteRowsForCountry: createCountryQuotationCache(calculate), exchangeRate: 7 })
  mount(CommonMatrix, props)
  await nextTick()
  expect(calculate.mock.calls.map(([country]) => country)).toEqual(['美国'])
  const input = document.querySelector('[aria-label="搜索物流渠道"]') as HTMLInputElement
  input.value = '渠道'; input.dispatchEvent(new Event('input', { bubbles: true })); await nextTick()
  button('价格从低到高').click(); await nextTick()
  button('下一页').click(); await nextTick()
  expect(calculate).toHaveBeenCalledTimes(1)
  button('澳大利亚').click(); await nextTick()
  expect(calculate).toHaveBeenCalledTimes(2)
  props.active = false; await nextTick()
  props.contextKey = 'v2'; await nextTick()
  expect(calculate).toHaveBeenCalledTimes(2)
  props.active = true; await nextTick()
  expect(document.querySelector('.country-title')?.textContent).toContain('澳大利亚')
  expect((document.querySelector('[aria-label="搜索物流渠道"]') as HTMLInputElement).value).toBe('渠道')
  expect(document.body.textContent).toContain('无关税')
  expect(document.body.textContent).not.toContain('关税待设置')
})

it('retains specified channel selections across pricing changes and hidden mode', async () => {
  const pricing = reactive({ price: 10 })
  const calculate = vi.fn((country: string) => [ { ...row(country, 0), quote1: pricing.price }, row(country, 1)])
  const changed = vi.fn()
  const props = reactive({ active: true, countries, contextKey: 'v1', variant: 'specified', customQuantity: 5,
    presetVersion: 1, presetSelection: [{ country: '美国', channelKey: '1' }],
    quoteRowsForCountry: createCountryQuotationCache(calculate), exchangeRate: 7, onSelectionChange: changed })
  mount(Matrix, props)
  await nextTick()
  expect(changed.mock.lastCall?.[0].map((r: QuotationMatrixRow) => r.channelKey)).toEqual(['1'])
  props.active = false; await nextTick()
  const count = calculate.mock.calls.length
  pricing.price = 99; props.contextKey = 'v2'; await nextTick()
  expect(calculate).toHaveBeenCalledTimes(count)
  props.active = true; await nextTick()
  expect(changed.mock.lastCall?.[0].map((r: QuotationMatrixRow) => r.channelKey)).toEqual(['1'])
})

it('distinguishes absent country duty, provider exemption and a missing provider', async () => {
  const state = reactive({ row: row('澳大利亚', 0) })
  const host = document.createElement('div'); document.body.append(host)
  app = createApp({ render: () => h(QuoteTaxMeta, state) }); app.mount(host)
  expect(host.textContent).toBe('无关税')
  Object.assign(state.row, { taxFeeMode: 'exempt', taxIncluded: true }); await nextTick()
  expect(host.textContent).toBe('免税')
  Object.assign(state.row, { taxFeeMode: 'missing', taxIncluded: false, taxConfigured: false, taxLabel: '物流商税务属性待设置' }); await nextTick()
  expect(host.textContent).toBe('物流商税务属性待设置')
})

it('displays surcharge amount and provider exemption independently of tax', async () => {
  const props = reactive({row: {...row('美国', 0), surchargeEnabled:true, surchargeConfigured:true, surchargeLabel:'附加费 $2.00/单'}})
  mount(QuoteTaxMeta, props); await nextTick()
  expect(document.body.textContent).toContain('无关税')
  expect(document.body.textContent).toContain('附加费 $2.00/单')
  props.row.surchargeLabel = '免附加费'; await nextTick()
  expect(document.body.textContent).toContain('免附加费')
  props.row.surchargeEnabled = false; await nextTick()
  expect(document.body.textContent).not.toContain('附加费')
})
