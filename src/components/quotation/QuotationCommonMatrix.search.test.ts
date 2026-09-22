// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, reactive, type App } from 'vue'
import Matrix from './QuotationCommonMatrix.vue'
import type { QuotationCountrySummary, QuotationMatrixRow } from './types'

let app: App
const tick = async () => { await nextTick(); await nextTick(); await nextTick() }
const row = (country: string, key = 'A') => ({ country, channelKey: key, rule: '规则', carrier: '物流甲', transport: `专线${key}`,
  channelCode: key, quote1: 10, quote2: 20, quote3: 30, quoteCustom: 50, taxConfigured: true, eta: '5天' }) as QuotationMatrixRow
const countries = [
  { name: '美国', code: 'US', stage: 'common', sortOrder: 0, channelCount: 1 },
  { name: '日本', code: 'JP', stage: 'rare', sortOrder: 1000, channelCount: 0, channelsLoaded: false },
] as QuotationCountrySummary[]
function mount(overrides: Record<string, unknown> = {}) {
  const changed = vi.fn()
  const state = reactive({ countries, active: true, contextKey: 'a', adoptedCountry: '', adoptedRule: '', adoptedCarrier: '', exchangeRate: 7,
    quoteRowsForCountry: (country: string) => [row(country), row(country, 'B')], onSelectionChange: changed, ...overrides })
  const host = document.createElement('div'); document.body.append(host)
  app = createApp({ render: () => h(Matrix, state) }); app.mount(host)
  return { state, changed }
}
async function search(value: string) {
  const input = document.querySelector<HTMLInputElement>('[aria-label="搜索全部国家或渠道"]')!
  input.value = value; input.dispatchEvent(new Event('input')); await tick()
}
const button = (text: string) => [...document.querySelectorAll('button')].find(item => item.textContent?.includes(text))!
afterEach(() => { app?.unmount(); document.body.innerHTML = ''; vi.useRealTimers() })

it('finds non-common countries by code, loads on click and retains additions after clearing search', async () => {
  vi.useFakeTimers()
  let complete!: (ok: boolean) => void
  const ensureCountries = vi.fn(() => new Promise<boolean>(resolve => { complete = resolve }))
  const { state, changed } = mount({ ensureCountries, searchChannelCountries: vi.fn().mockResolvedValue([]) })
  expect(document.querySelector('.country-grid')!.textContent).not.toContain('日本')
  await search('jp'); expect(document.querySelector('.country-grid')!.textContent).toContain('日本')
  button('日本').click(); await tick()
  expect(ensureCountries).toHaveBeenCalledWith(['日本'])
  expect(document.body.textContent).toContain('正在加载 日本')
  complete(true); await tick(); button('加入报价单').click(); await tick()
  expect(changed.mock.lastCall?.[0]).toEqual([expect.objectContaining({ country: '日本', channelKey: 'A' })])
  await search(''); state.contextKey = 'b'; await tick()
  expect(document.querySelector('.country-summary')!.textContent).toContain('日本')
  expect(document.querySelector('.country-grid')!.textContent).toContain('日本')
  button('已加入').click(); await tick(); expect(changed.mock.lastCall?.[0]).toEqual([])
})

it('searches channel names across countries and shows the matched channels after selection', async () => {
  vi.useFakeTimers()
  const searchChannelCountries = vi.fn().mockResolvedValue(['日本'])
  mount({ searchChannelCountries })
  await search('专线B'); await vi.advanceTimersByTimeAsync(250); await tick()
  expect(searchChannelCountries).toHaveBeenCalledWith('专线b')
  button('日本').click(); await tick()
  expect(document.querySelector('.quote-rows')!.textContent).toContain('专线B')
  expect(document.querySelector('.quote-rows')!.textContent).not.toContain('专线A')
})

it('ignores old search completion and offers retry after a search failure', async () => {
  vi.useFakeTimers()
  let old!: (result: string[]) => void
  const lookup = vi.fn().mockImplementationOnce(() => new Promise(resolve => { old = resolve })).mockRejectedValueOnce(new Error('offline')).mockResolvedValue([])
  mount({ searchChannelCountries: lookup })
  await search('旧'); await vi.advanceTimersByTimeAsync(250)
  await search('新'); await vi.advanceTimersByTimeAsync(250); await tick()
  old(['日本']); await tick()
  expect(document.body.textContent).toContain('渠道搜索加载失败')
  expect(document.querySelector('.country-grid')).toBeNull()
  button('重试搜索').click(); await vi.advanceTimersByTimeAsync(250); await tick()
  expect(document.body.textContent).not.toContain('渠道搜索加载失败')
})

it('keeps unavailable reissued channels visible and removable without reusing historic prices', async () => {
  const { changed } = mount({ presetSelection: [row('美国', 'expired')], presetVersion: 1 })
  await tick()
  expect(changed.mock.lastCall?.[0]).toEqual([expect.objectContaining({ channelKey: 'expired', available: false, quote1: null })])
  button('移除渠道').click(); await tick(); expect(changed.mock.lastCall?.[0]).toEqual([])
})
