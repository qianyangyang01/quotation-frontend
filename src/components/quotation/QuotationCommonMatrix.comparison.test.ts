// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, reactive, type App } from 'vue'
import Matrix from './QuotationCommonMatrix.vue'
import type { QuotationCountrySummary, QuotationMatrixRow } from './types'

let app: App
const tick = async () => { await nextTick(); await nextTick(); await nextTick() }
const channelCount = 24
function mount(overrides: Record<string, unknown> = {}) {
  const rows = Array.from({ length: channelCount }, (_, i) => ({ country: '美国', channelKey: `A${i}`, channelCode: `A${i}`, rule: '规则', carrier: '物流甲', transport: `专线${i}`,
    quote1: 10 + i, quote2: 20 + i, quote3: 30 + i, quoteCustom: 50 + i, taxConfigured: true, eta: '5～8 天' })) as QuotationMatrixRow[]
  let revision = 0
  const calculate = vi.fn((row: QuotationMatrixRow, quantity: number) => row.channelKey === 'A0' && quantity === 12 ? null : quantity * 10 + Number(row.channelKey.slice(1)) + revision)
  const reason = vi.fn(() => '12件含包材重量6kg，超过上限5kg')
  const source = vi.fn((country: string) => country === '美国' ? rows : [])
  const copied = vi.fn(), selected = vi.fn()
  const state = reactive({ countries: [{ name: '美国', code: 'US', stage: 'common', sortOrder: 0, channelCount }, { name: '日本', code: 'JP', stage: 'common', sortOrder: 1, channelCount }] as QuotationCountrySummary[],
    active: true, sourcePending: false, contextKey: 'a', customQuantity: 5, adoptedCountry: '', adoptedRule: '', adoptedCarrier: '', exchangeRate: 7,
    quoteRowsForCountry: source, calculateQuantityPrice: calculate, unavailableReason: reason, onCopy: copied, onSelectionChange: selected, ...overrides })
  const host = document.createElement('div'); document.body.append(host)
  app = createApp({ render: () => h(Matrix, state) }); app.mount(host)
  return { state, calculate, reason, source, copied, selected, rows, changeRevision: () => { revision += 100; state.contextKey += 'b' } }
}
async function input(value: string) {
  const field = document.querySelector<HTMLInputElement>('[aria-label="比价数量"]')!
  field.value = value; field.dispatchEvent(new Event('input')); await tick()
}
async function apply() { [...document.querySelectorAll('button')].find(b => b.textContent === '应用数量')!.click(); await tick() }
async function select(selector: string, value: string) { const field = document.querySelector<HTMLSelectElement>(selector)!; field.value = value; field.dispatchEvent(new Event('change')); await tick() }
const headers = () => [...document.querySelectorAll('.table-head>button')].map(b => b.textContent)
afterEach(() => { app?.unmount(); document.body.innerHTML = ''; localStorage.clear() })

it('defaults to 1/2/3 using existing prices and calculates only non-existing quantities after Apply', async () => {
  const { calculate, source } = mount(); await tick()
  expect(headers()).toHaveLength(3); expect(headers().map(x => x?.slice(0, 1))).toEqual(['1', '2', '3'])
  expect(calculate).not.toHaveBeenCalled()
  expect(source.mock.calls.every(([country]) => country === '美国')).toBe(true)
  await input('1, 5, 8'); expect(calculate).not.toHaveBeenCalled()
  await apply(); expect(calculate).toHaveBeenCalledTimes(channelCount)
  expect(calculate.mock.calls.every(([, q]) => q === 8)).toBe(true)
})

it('caps input at three values and preserves prior prices without calculating rejected or unchanged inputs', async () => {
  const { calculate } = mount(); await tick()
  await input('4, 8, 12'); await apply(); expect(calculate).toHaveBeenCalledTimes(channelCount * 3)
  const previous = headers()
  await input('5, 10, 20, 30'); await apply()
  expect(document.querySelector('[role="alert"]')?.textContent).toContain('最多输入 3 个数量')
  expect(headers()).toEqual(previous); expect(calculate).toHaveBeenCalledTimes(channelCount * 3)
  await input('4,8,12'); await apply(); expect(calculate).toHaveBeenCalledTimes(channelCount * 3)
  await input('0,2,3'); await apply(); expect(headers()).toEqual(previous)
})

it('reuses calculation and unavailability results through sorting, search, pagination and availability filters', async () => {
  const { calculate, reason } = mount(); await tick()
  await input('4,8,12'); await apply()
  expect(calculate).toHaveBeenCalledTimes(channelCount * 3); expect(reason).toHaveBeenCalledTimes(1)
  await select('[aria-label="排序数量"]', '12')
  await select('.common-matrix footer select', '10')
  const search = document.querySelector<HTMLInputElement>('[aria-label="搜索物流渠道"]')!
  search.value = '专线'; search.dispatchEvent(new Event('input')); await tick()
  const filter = document.querySelector<HTMLInputElement>('.only-quotable input')!
  filter.checked = true; filter.dispatchEvent(new Event('change')); await tick()
  ;[...document.querySelectorAll('button')].find(b => b.textContent === '下一页')!.click(); await tick()
  expect(calculate).toHaveBeenCalledTimes(channelCount * 3); expect(reason).toHaveBeenCalledTimes(1)
  expect(document.querySelectorAll('.quote-rows article')).toHaveLength(10)
})

it('refreshes for changed pricing context but skips work while inactive or sources are pending', async () => {
  const { state, calculate, changeRevision } = mount(); await tick()
  await input('4,8,12'); await apply()
  calculate.mockClear(); changeRevision(); await tick()
  expect(calculate).toHaveBeenCalledTimes(channelCount * 3)
  expect(document.querySelector('.comparison-price')?.textContent).toContain('$140.00')
  state.active = false; await tick(); calculate.mockClear(); changeRevision(); await tick()
  expect(calculate).not.toHaveBeenCalled()
  state.sourcePending = true; state.active = true; await tick(); expect(calculate).not.toHaveBeenCalled()
  state.sourcePending = false; await tick(); expect(calculate).toHaveBeenCalledTimes(channelCount * 3)
})

it.each(['', ' ', '0', '-1,2,3', '1.5,2,3', 'abc', '1e3,2,3', '0x10', 'Infinity', 'NaN', '9007199254740992', '1,2,3,4', '1,1,1,1'])('rejects malformed quantity input %j without changing prices', async value => {
  const { calculate } = mount(); await tick(); const before = headers()
  await input(value); await apply()
  expect(document.querySelector('[role="alert"]')).not.toBeNull(); expect(headers()).toEqual(before)
  expect(calculate).not.toHaveBeenCalled()
})

it.each([['4，8、12', ['4','8','12']], ['12;4;8', ['12','4','8']], ['4 8 12', ['4','8','12']], ['4,4,8', ['4','8']], ['10', ['10']], ['10,20', ['10','20']]])('accepts %s with at most three distinct columns', async (value, expected) => {
  mount(); await tick(); await input(value as string); await apply()
  expect(headers().map(text => text?.match(/^\d+/)?.[0])).toEqual(expected)
  expect(document.querySelector('[role="alert"]')).toBeNull()
})

it('isolates exceptions, NaN, infinity and negative results without treating them as free or overweight', async () => {
  mount({ calculateQuantityPrice: (row: QuotationMatrixRow) => {
    if (row.channelKey === 'A0') throw new Error('malformed rate')
    return row.channelKey === 'A1' ? NaN : row.channelKey === 'A2' ? Infinity : row.channelKey === 'A3' ? -1 : 0
  } }); await tick(); await input('4'); await apply()
  expect(document.querySelectorAll('.comparison-overweight')).toHaveLength(0)
  expect(document.body.textContent).toContain('计价异常')
  const buttons = [...document.querySelectorAll<HTMLButtonElement>('.selection-actions>button')]
  expect(buttons.slice(0,4).every(button => button.disabled)).toBe(true)
  expect(buttons[4]?.disabled).toBe(false)
  expect(document.querySelectorAll('.comparison-lowest')).toHaveLength(1)
})

it('shows tied minima across all pages and clears the minimum when all prices are unavailable', async () => {
  const calculate = vi.fn((_row: QuotationMatrixRow, q: number) => q === 4 ? 7 : null)
  mount({ calculateQuantityPrice: calculate }); await tick(); await input('4'); await apply()
  expect(document.querySelectorAll('.comparison-lowest')).toHaveLength(5)
  ;[...document.querySelectorAll('button')].find(b => b.textContent === '下一页')!.click(); await tick()
  expect(document.querySelectorAll('.comparison-lowest')).toHaveLength(5)
  await input('8'); await apply()
  expect(document.querySelector('.metric.lowest b')?.textContent).toBe('—')
  const filter = document.querySelector<HTMLInputElement>('.only-quotable input')!
  filter.checked = true; filter.dispatchEvent(new Event('change')); await tick()
  expect(document.querySelector('.empty-rows')?.textContent).toContain('没有 8件可报价渠道')
})

it('copies the active three quantities in filtered price order and keeps the selected list independent', async () => {
  const { copied, selected, calculate } = mount(); await tick()
  document.querySelector<HTMLButtonElement>('.selection-actions button')!.click(); await tick()
  const selectedKey = selected.mock.lastCall?.[0][0].channelKey
  await input('4,8,12'); await apply(); await select('[aria-label="排序数量"]','12')
  const filter = document.querySelector<HTMLInputElement>('.only-quotable input')!
  filter.checked = true; filter.dispatchEvent(new Event('change')); await tick()
  const before = calculate.mock.calls.length
  ;[...document.querySelectorAll('button')].find(b => b.textContent?.includes('复制当前国家'))!.click(); await tick()
  const [rows, copy] = copied.mock.lastCall!
  expect(copy.quantities).toEqual([4,8,12]); expect(copy.prices).toHaveLength(23)
  expect(copy.prices[0]).toEqual([41,81,121]); expect(rows[0].channelKey).toBe('A1')
  expect(calculate).toHaveBeenCalledTimes(before)
  expect(selected.mock.lastCall?.[0].map((row: QuotationMatrixRow) => row.channelKey)).toEqual([selectedKey])
})

it('retains explicitly selected channels that disappear from the current comparison without automatically selecting others', async () => {
  const visible = reactive({ show: true })
  const row = { country:'美国',channelKey:'special',channelCode:'special',rule:'规则',carrier:'物流甲',transport:'高起重',quote1:null,quote2:null,quote3:null,quoteCustom:null,eta:'5天' } as QuotationMatrixRow
  const resolver = vi.fn(() => ({ ...row }))
  const { selected } = mount({ quoteRowsForCountry: () => visible.show ? [row] : [], calculateQuantityPrice: () => 10, resolveSelectedRow: resolver })
  await tick(); await input('4,8,12'); await apply()
  document.querySelector<HTMLButtonElement>('.selection-actions button')!.click(); await tick()
  visible.show=false; await input('1,2,3'); await apply()
  expect(selected.mock.lastCall?.[0].map((row: QuotationMatrixRow) => row.channelKey)).toEqual(['special'])
  expect(resolver).toHaveBeenCalledWith(expect.objectContaining({channelKey:'special'}),[4,8,12])
})
