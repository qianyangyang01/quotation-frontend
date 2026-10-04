// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, reactive, type App } from 'vue'
import Matrix from './QuotationMatrix.vue'
import type { QuotationMatrixRow } from './types'

let app: App
const tick = async () => { await nextTick(); await nextTick(); await nextTick() }
const button = (text: string) => [...document.querySelectorAll('button')].find(item => item.textContent?.includes(text))!
const visibleNames = () => [...document.querySelectorAll('.picker-list .channel-title')].map(item => item.textContent?.split('｜')[1])
function row(id: string, price: number | null, extra: Partial<QuotationMatrixRow> = {}): QuotationMatrixRow {
  return { country: '美国', channelKey: id, rule: '普货', carrier: '物流', transport: id, quoteRegion: '全国统一', quote1: price, quote2: 20, quote3: 30, quoteCustom: 50, eta: '5天', taxConfigured: true, ...extra } as QuotationMatrixRow
}
async function setup(rows: QuotationMatrixRow[]) {
  const changed = vi.fn()
  const state = reactive({
    countries: [{ name: '美国', code: 'US', lowestQuote: 10, grouped: false, continent: '北美洲' as const, stage: 'common' as const, sortOrder: 1, channelCount: rows.length }],
    quoteRowsForCountry: () => rows, variant: 'template' as const, presetSelection: [row('saved', 99)], presetVersion: 1,
    contextKey: 'initial', customQuantity: 5, exchangeRate: 7, adoptedCountry: '', adoptedRule: '', adoptedCarrier: '', onSelectionChange: changed,
  })
  const host = document.createElement('div')
  document.body.append(host)
  app = createApp({ render: () => h(Matrix, state) })
  app.mount(host)
  await tick()
  button('添加渠道').click()
  await tick()
  return { state, changed }
}
async function selectField(field: string) {
  const select = document.querySelector<HTMLSelectElement>('[aria-label="渠道价格排序依据"]')!
  select.value = field
  select.dispatchEvent(new Event('change', { bubbles: true }))
  await tick()
}
afterEach(() => { app?.unmount(); document.body.innerHTML = '' })

it('sorts the complete result before pagination and restores source order without changing saved selection', async () => {
  const rows = Array.from({ length: 10 }, (_, index) => row(`渠道${10 - index}`, 10 - index))
  const { changed } = await setup(rows)
  const callsBefore = changed.mock.calls.length
  button('下一页').click(); await tick()
  button('价格从低到高').click(); await tick()
  expect(visibleNames()).toEqual(Array.from({ length: 8 }, (_, index) => `渠道${index + 1}`))
  expect(document.querySelector('.picker-pagination b')?.textContent).toBe('1 / 2')
  button('下一页').click(); await tick()
  expect(visibleNames()).toEqual(['渠道9', '渠道10'])
  button('价格从低到高').click(); await tick()
  expect(visibleNames()).toEqual(rows.slice(0, 8).map(item => item.transport))
  expect(rows[0]?.quote1).toBe(10)
  expect(changed).toHaveBeenCalledTimes(callsBefore)
})

it('puts null and nonfinite prices last and retains input order for equal prices', async () => {
  await setup([row('无报价', null), row('相同A', 5), row('零元', 0), row('相同B', 5), row('无效', NaN), row('无限', Infinity)])
  button('价格从低到高').click(); await tick()
  expect(visibleNames()).toEqual(['零元', '相同A', '相同B', '无报价', '无效', '无限'])
})

it('uses the chosen quantity and keeps checked identities when sorting, filtering and adding', async () => {
  const { changed } = await setup([row('渠道A', 10, { quote2: 40, quote3: 5, quoteCustom: 90 }), row('渠道B', 20, { quote2: 10, quote3: null, quoteCustom: 30 })])
  document.querySelector<HTMLInputElement>('.picker-list input')!.click(); await tick()
  button('价格从低到高').click(); await tick()
  await selectField('quote2')
  expect(visibleNames()).toEqual(['渠道B', '渠道A'])
  expect([...document.querySelectorAll<HTMLInputElement>('.picker-list input')].map(input => input.checked)).toEqual([false, true])
  await selectField('quote3')
  expect(visibleNames()).toEqual(['渠道A', '渠道B'])
  await selectField('quoteCustom')
  expect(visibleNames()).toEqual(['渠道B', '渠道A'])
  const search = document.querySelector<HTMLInputElement>('.channel-dialog .dialog-search input')!
  search.value = '渠道A'; search.dispatchEvent(new Event('input', { bubbles: true })); await tick()
  expect(visibleNames()).toEqual(['渠道A'])
  button('批量添加渠道').click(); await tick()
  expect(changed.mock.lastCall?.[0].map((item: QuotationMatrixRow) => item.channelKey)).toEqual(['渠道A', 'saved'])
})

it('reorders after recalculation while leaving the original saved row intact', async () => {
  const { state, changed } = await setup([row('渠道A', 10), row('渠道B', 20)])
  button('价格从低到高').click(); await tick()
  state.quoteRowsForCountry = () => [row('渠道A', 30), row('渠道B', 5)]
  state.contextKey = 'recalculated'; await tick()
  expect(visibleNames()).toEqual(['渠道B', '渠道A'])
  expect(changed.mock.lastCall?.[0]).toEqual([expect.objectContaining({ channelKey: 'saved', quote1: null })])
  expect(state.presetSelection[0]?.quote1).toBe(99)
})
