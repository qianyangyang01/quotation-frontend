// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, reactive, type App, type Component } from 'vue'
import CommonMatrix from './QuotationCommonMatrix.vue'
import Matrix from './QuotationMatrix.vue'
import QuoteTaxMeta from './QuoteTaxMeta.vue'
import PriceSummary from './PriceSummary.vue'
import { createCountryQuotationCache } from '@/services/countryQuotationCache'
import { createCountryQuotationGeneration } from '@/services/countryQuotationGeneration'
import { ref } from 'vue'
import type { QuotationCountrySummary, QuotationMatrixRow } from './types'

let app: App

it.each(['specified', 'template'])('reuses country calculations while searching and opening channels in %s mode', async variant => {
  const generation = createCountryQuotationGeneration(ref(0))
  const pricing = reactive({ weight: 1 })
  const calculate = vi.fn((country: string) => {
    generation.read(country)
    return Array.from({ length: 12 }, (_, i) => ({ ...row(country, i), quote1: pricing.weight * 10 }))
  })
  const state = reactive({ active: true, variant, countries: [countries[0]!], contextKey: 'v1', customQuantity: 5,
    presetVersion: 1, presetSelection: [{ country: '美国', channelKey: '1' }], exchangeRate: 7,
    quoteRowsForCountry: createCountryQuotationCache(calculate), ensureCountries: async () => true })
  mount(Matrix, state); await nextTick(); await nextTick()
  const count = calculate.mock.calls.length
  button('添加渠道').click(); await nextTick(); await nextTick(); await nextTick()
  const input = document.querySelector('.channel-dialog input[placeholder]') as HTMLInputElement
  input.value = '渠道'; input.dispatchEvent(new Event('input', { bubbles: true })); await nextTick()
  expect(calculate).toHaveBeenCalledTimes(count)
  expect(document.querySelector('.channel-dialog')?.hasAttribute('data-load-dom-ms')).toBe(true)
  generation.invalidate(['荷兰']); await nextTick()
  expect(calculate).toHaveBeenCalledTimes(count)
  pricing.weight = 2; await nextTick(); await nextTick()
  expect(calculate).toHaveBeenCalledTimes(count + 1)
  expect(document.querySelector('.picker-list')?.textContent).toContain('$20.00')
})

it.each([['英国', '非偏远'], ['加拿大', '1区']])('restores old %s template regions to the same explicitly national channel', async (country, oldRegion) => {
  const changed = vi.fn()
  const current = { ...row(country, 1), quoteRegion: '全国统一' }
  const state = reactive({ active: true, variant: 'template', countries: [{ ...countries[0], name: country }],
    contextKey: 'light', customQuantity: 5, exchangeRate: 6.7, presetVersion: 1,
    presetSelection: [{ country, channelKey: '1', quoteRegion: oldRegion }],
    quoteRowsForCountry: () => [{ ...current, quote1: state.contextKey === 'light' ? 5 : 25 }], onSelectionChange: changed })
  mount(Matrix, state); await nextTick(); await nextTick()
  expect(changed.mock.lastCall?.[0]).toEqual([expect.objectContaining({ channelKey: '1', quoteRegion: '全国统一', quote1: 5 })])
  state.contextKey = 'heavy'; await nextTick(); await nextTick()
  expect(changed.mock.lastCall?.[0]).toEqual([expect.objectContaining({ quoteRegion: '全国统一', quote1: 25 })])
})

it.each([
  ['澳大利亚', [{ quoteRegion: '全国统一', channelKey: '1' }]],
  ['加拿大', [{ quoteRegion: '2区', channelKey: '1' }]],
  ['加拿大', [{ quoteRegion: '全国统一', channelKey: '1' }, { quoteRegion: '2区', channelKey: '1' }]],
  ['加拿大', [{ quoteRegion: '全国统一', channelKey: '2' }]],
])('does not guess a replacement region or channel for %s', async (country, options) => {
  const changed = vi.fn()
  mount(Matrix, { active: true, variant: 'template', countries: [{ ...countries[0], name: country }],
    contextKey: 'v1', customQuantity: 5, exchangeRate: 6.7, presetVersion: 1,
    presetSelection: [{ country, channelKey: '1', quoteRegion: '旧区域' }],
    quoteRowsForCountry: () => options.map(option => ({ ...row(country, 1), ...option })), onSelectionChange: changed })
  await nextTick(); await nextTick()
  expect(changed.mock.lastCall?.[0]).toEqual([expect.objectContaining({ available: false, quote1: null })])
})

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

it('preserves selected channels from both Australian regions when browsing another region and recalculates each scope', async () => {
  const changed = vi.fn()
  const state = reactive({ active: true, countries: [{ ...countries[1]!, quoteRegions: ['澳大利亚1区', '澳大利亚2区'], selectedQuoteRegion: '澳大利亚1区' }],
    contextKey: 'v1', customQuantity: 5, exchangeRate: 7, adoptedCountry: '', adoptedRule: '', adoptedCarrier: '',
    quoteRowsForCountry: (country: string, region?: string) => [{ ...row(country, 1), quoteRegion: region ?? state.countries[0]!.selectedQuoteRegion,
      quote1: (region ?? state.countries[0]!.selectedQuoteRegion).includes('1区') ? (state.contextKey === 'v1' ? 10 : 11) : 20 }],
    onSelectionChange: changed,
  })
  mount(CommonMatrix, state); await nextTick(); await nextTick()
  button('加入报价单').click(); await nextTick()
  state.countries[0]!.selectedQuoteRegion = '澳大利亚2区'; state.contextKey = 'v2'; await nextTick(); await nextTick()
  expect(changed.mock.lastCall?.[0]).toEqual([expect.objectContaining({ quoteRegion: '澳大利亚1区', quote1: 11 })])
  button('加入报价单').click(); await nextTick()
  expect(changed.mock.lastCall?.[0]).toEqual([
    expect.objectContaining({ quoteRegion: '澳大利亚1区', quote1: 11 }),
    expect.objectContaining({ quoteRegion: '澳大利亚2区', quote1: 20 }),
  ])
})

it('loads a non-common country on demand, sorts available countries first and preserves existing selections', async () => {
  let resolve!: (ok: boolean) => void
  const ensure = vi.fn(() => new Promise<boolean>(done => { resolve = done }))
  const changed = vi.fn()
  const state = reactive({ active: true, countries: [countries[0],
    { ...countries[1], name: '意大利', code: 'IT', channelCount: 0, channelsLoaded: false },
    { ...countries[1], name: '德国', code: 'DE', channelCount: 2 }],
    contextKey: 'v1', customQuantity: 5, exchangeRate: 7, ensureCountries: ensure,
    quoteRowsForCountry: (country: string) => country === '美国' || state.contextKey === 'v2' ? [row(country, 1)] : [],
    onSelectionChange: changed })
  mount(Matrix, state)
  await nextTick()
  button('添加国家').click(); await nextTick()
  const options = [...document.querySelectorAll<HTMLButtonElement>('.country-option-list button')]
  expect(options.map(b => b.textContent)).toEqual([expect.stringContaining('德国'), expect.stringContaining('选择后查询渠道')])
  options[1]!.click(); await nextTick()
  expect(ensure).toHaveBeenCalledWith(['意大利'])
  expect(document.body.textContent).toContain('正在加载渠道')
  expect(changed.mock.lastCall?.[0].map((r: QuotationMatrixRow) => r.country)).toEqual(['美国'])
  state.contextKey = 'v2'
  resolve(true); await nextTick(); await nextTick()
  expect(document.querySelector('.picker-list')?.textContent).toContain('渠道1')
  expect(document.body.textContent).not.toContain('正在加载渠道')
  expect(changed.mock.lastCall?.[0].map((r: QuotationMatrixRow) => r.country)).toEqual(['美国'])
})

it('waits for template countries and ignores an older template completion', async () => {
  const completions: Array<(ok: boolean) => void> = []
  const ensure = vi.fn(() => new Promise<boolean>(done => completions.push(done)))
  const changed = vi.fn()
  const state = reactive({ active: true, variant: 'template', countries,
    contextKey: 'v1', customQuantity: 5, exchangeRate: 7, ensureCountries: ensure,
    quoteRowsForCountry: (country: string) => [row(country, 1)],
    presetVersion: 1, presetSelection: [{ country: '美国', channelKey: '1' }], onSelectionChange: changed })
  mount(Matrix, state); await nextTick()
  expect(changed.mock.lastCall?.[0]).toEqual([])
  state.presetVersion = 2
  state.presetSelection = [{ country: '澳大利亚', channelKey: '1' }]
  await nextTick()
  completions[1]!(true); await nextTick(); await nextTick()
  completions[0]!(true); await nextTick(); await nextTick()
  expect(changed.mock.lastCall?.[0].map((r: QuotationMatrixRow) => r.country)).toEqual(['澳大利亚'])
})

it('offers retry after channel loading fails instead of reporting zero channels', async () => {
  const ensure = vi.fn().mockResolvedValueOnce(false).mockResolvedValue(true)
  mount(Matrix, { active: true, countries, contextKey: 'v1', customQuantity: 5, exchangeRate: 7,
    ensureCountries: ensure, quoteRowsForCountry: () => [] })
  await nextTick()
  button('添加渠道').click(); await nextTick(); await nextTick()
  const retry = document.querySelector('.picker-list p') as HTMLElement
  expect(retry.textContent).toContain('加载失败，点击重试')
  retry.click(); await nextTick(); await nextTick()
  expect(ensure).toHaveBeenCalledTimes(2)
  expect(document.querySelector('.picker-list p')?.textContent).toContain('没有匹配的可用渠道')
})

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
  expect(changed.mock.lastCall?.[0]).toEqual([expect.objectContaining({ available: false, quote1: null })])
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

it('lists Canadian regional prices independently without a region selector and restores the exact selected region', async () => {
  const changed = vi.fn()
  const caRows = [1, 2].map(zone => ({ ...row('加拿大', 1), quoteRegion: `燕文｜渠道1｜${zone}区`, quote1: zone * 10 }))
  const state = reactive({ active: true, countries: [{ ...countries[0]!, name: '加拿大', code: 'CA', selectedQuoteRegion: caRows[1]!.quoteRegion }],
    contextKey: 'ca', exchangeRate: 7, adoptedCountry: '加拿大', adoptedRule: '1', adoptedCarrier: '测试物流',
    quoteRowsForCountry: () => caRows, onSelectionChange: changed,
    presetVersion: 1, presetSelection: [{ country: '加拿大', channelKey: '1', quoteRegion: '加拿大2区' }] })
  mount(CommonMatrix, state); await nextTick()
  expect(document.querySelector('.quote-region-select')).toBeNull()
  expect(document.querySelectorAll('article.adopted')).toHaveLength(1)
  expect(document.querySelector('article.adopted')?.textContent).toContain('2区')
  expect(document.querySelectorAll('.selection-actions')).toHaveLength(2)
  expect(changed.mock.lastCall?.[0].map((r: QuotationMatrixRow) => r.quoteRegion)).toEqual([caRows[1]!.quoteRegion])
})

it('shows all Canadian regions in the channel picker with no hidden region filter', async () => {
  const caRows = [1, 2, 3].map(zone => ({ ...row('加拿大', 1), quoteRegion: `燕文｜渠道1｜${zone}区`, quote1: zone * 10 }))
  mount(Matrix, { active: true, customQuantity: 5, adoptedCountry: '', adoptedRule: '', adoptedCarrier: '', countries: [{ ...countries[0]!, name: '加拿大', code: 'CA' }], contextKey: 'ca', exchangeRate: 7,
    quoteRowsForCountry: () => caRows, presetVersion: 1, presetSelection: [{ country: '加拿大', channelKey: '1', quoteRegion: '加拿大2区' }] })
  await nextTick(); await nextTick()
  button('添加渠道').click(); await nextTick()
  expect(document.querySelector('.quote-region-select')).toBeNull()
  expect(document.querySelectorAll('.picker-list>label')).toHaveLength(3)
})

it('retains an unavailable template row across SKU changes and restores its current price', async () => {
  const changed = vi.fn()
  const state = reactive({ active: true, variant: 'template', countries, contextKey: 'light', customQuantity: 5,
    exchangeRate: 7, presetVersion: 1, presetSelection: [{ country: '美国', channelKey: '1' }],
    unavailableReason: () => '超过渠道重量上限',
    quoteRowsForCountry: () => state.contextKey === 'heavy' ? [] : [{ ...row('美国', 1), quote1: 12 }], onSelectionChange: changed })
  mount(Matrix, state); await nextTick(); await nextTick()
  expect(changed.mock.lastCall?.[0][0].quote1).toBe(12)
  state.contextKey = 'heavy'; await nextTick(); await nextTick()
  expect(changed.mock.lastCall?.[0]).toEqual([expect.objectContaining({ available: false, quote1: null, quote2: null, availabilityMessage: '超过渠道重量上限' })])
  expect(document.body.textContent).toContain('替换渠道')
  state.contextKey = 'light'; await nextTick(); await nextTick()
  expect(changed.mock.lastCall?.[0][0].quote1).toBe(12)
})


it.each(['common', 'specified', 'template'].flatMap(mode => ['件', '套'].map(unit => [mode, unit])))('shows quantity reasons and clears stale messages in %s / %s', async (mode, unit) => {
  const changed = vi.fn()
  const state = reactive({ active: true, variant: mode === 'common' ? undefined : mode, countries: [countries[0]!],
    contextKey: 'v1', customQuantity: 5, unitLabel: unit, exchangeRate: 7, adoptedCountry: '', adoptedRule: '', adoptedCarrier: '',
    presetVersion: 1, presetSelection: [{ country: '美国', channelKey: '1' }],
    quoteRowsForCountry: () => [{ ...row('美国', 1), quote3: state.contextKey === 'v1' ? null : 35, quoteCustom: null,
      quantityMessages: { '1': '不应显示的旧原因', '3': '3'+unit+'含包材重量3.600kg，超过上限3kg', [String(state.customQuantity)]: state.customQuantity+unit+'该重量段暂无运价' } }], onSelectionChange: changed })
  mount(mode === 'common' ? CommonMatrix : Matrix, state); await nextTick(); await nextTick()
  expect(document.body.textContent).toContain('3'+unit+'含包材重量3.600kg，超过上限3kg')
  expect(document.body.textContent).not.toContain('不应显示的旧原因')
  state.contextKey = 'v2'; state.customQuantity = 8; await nextTick(); await nextTick()
  expect(document.body.textContent).not.toContain('超过上限3kg')
  expect(document.body.textContent).toContain('8'+unit+'该重量段暂无运价')
  expect(document.body.textContent).not.toContain('5'+unit+'该重量段暂无运价')
  expect(changed.mock.lastCall?.[0][0].quote3).toBe(35)
})

it('blocks adding entirely unpriced rows in common mode but permits a partially priced row', async () => {
  const changed = vi.fn()
  const state = reactive({active: true, countries: [countries[0]!], contextKey: 'v1', customQuantity: 5, exchangeRate: 7,
    adoptedCountry: '', adoptedRule: '', adoptedCarrier: '', quoteRowsForCountry: () => [
      { ...row('美国', 1), quote1: null, quote2: null, quote3: null, quoteCustom: null },
      { ...row('美国', 2), quote3: null, quoteCustom: null }], onSelectionChange: changed })
  mount(CommonMatrix, state); await nextTick(); await nextTick()
  const buttons = [...document.querySelectorAll('button')].filter(b => b.textContent === '加入报价单')
  expect(buttons.filter(b => b.disabled)).toHaveLength(1)
  buttons.find(b => !b.disabled)!.click(); await nextTick()
  expect(changed.mock.lastCall?.[0]).toEqual([expect.objectContaining({channelKey:'2'})])
})

it('picker refuses a row that loses every price before confirmation', async () => {
  const changed = vi.fn()
  const state = reactive({ active: true, variant: 'template', countries: [countries[0]!], contextKey:'v1', customQuantity:5,
    exchangeRate:7, presetVersion:1, presetSelection:[{country:'美国',channelKey:'1'}],
    quoteRowsForCountry:()=>[row('美国',1), {...row('美国',2), quote1: state.contextKey==='v1'?10:null, quote2:null,quote3:null,quoteCustom:null}],
    onSelectionChange:changed })
  mount(Matrix,state);await nextTick();await nextTick()
  button('添加渠道').click();await nextTick();await nextTick()
  const input=[...document.querySelectorAll<HTMLInputElement>('.picker-list input')].find(el=>!el.disabled)!
  input.click();await nextTick()
  state.contextKey='v2';await nextTick();await nextTick()
  button('批量添加渠道').click();await nextTick();await nextTick()
  expect(changed.mock.lastCall?.[0].map((r:QuotationMatrixRow)=>r.channelKey)).toEqual(['1'])
  expect(document.body.textContent).toContain('原方案已保留')
})


it.each(['common','specified','template'])('preserves selected %s rows through loading, errors and recovery without stale quantity reasons', async mode => {
  const changed=vi.fn()
  const state=reactive({active:true,variant:mode==='common'?undefined:mode,countries:[countries[0]!],contextKey:'ready',customQuantity:8,
    exchangeRate:7,adoptedCountry:'',adoptedRule:'',adoptedCarrier:'',presetVersion:1,presetSelection:[{country:'美国',channelKey:'1'}],
    unavailableReason:(_preset:unknown,q=1)=>state.contextKey==='loading'?'渠道正在加载，请稍候':state.contextKey==='error'?'渠道加载失败，请重试':q+'套含包材重量'+q+'.000kg，超过上限0.5kg',
    quoteRowsForCountry:()=>state.contextKey==='ready'?[row('美国',1)]:[],onSelectionChange:changed})
  mount(mode==='common'?CommonMatrix:Matrix,state);await nextTick();await nextTick()
  for(const status of ['overweight','loading','error','ready']){
    state.contextKey=status;await nextTick();await nextTick()
    const saved=changed.mock.lastCall?.[0][0]
    expect(saved.channelKey).toBe('1')
    if(status==='ready') {expect(saved.quote1).toBe(10);expect(document.body.textContent).not.toContain('超过上限')}
    else {expect(saved.quote1).toBeNull();expect(saved.quantityMessages['8']).toBe(state.unavailableReason({},8))}
    if(status==='overweight') expect(document.body.textContent).toContain('8套含包材重量8.000kg')
    if(status==='loading'||status==='error') expect(document.body.textContent).not.toContain('超过上限')
  }
})

it('internal preview displays a missing-price reason and hides it once a price is supplied', async () => {
  const state=reactive({cnyPrice:70,usdPrice:10,productCost:1,logisticsCost:1,domesticFreightCost:0,profit:1,coefficient:1.2,grade:'A',status:'就绪',
    quoteOptions:[{...row('美国',1),quote3:null as number|null,quantityMessages:{'3':'3件含包材重量3.600kg，超过上限3kg'}}],exchangeRate:7})
  mount(PriceSummary,state);await nextTick();button('查看').click();await nextTick()
  expect(document.body.textContent).toContain('3件含包材重量3.600kg')
  state.quoteOptions[0]!.quote3=30;await nextTick()
  expect(document.body.textContent).not.toContain('超过上限3kg')
})
