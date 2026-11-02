// @vitest-environment happy-dom
import { createApp, h, nextTick, shallowRef } from 'vue'
import { expect, it, vi } from 'vitest'
import LogisticsPriceChanges from './LogisticsPriceChanges.vue'
import LogisticsUpdateOverview from './LogisticsUpdateOverview.vue'
import type { RealPriceSnapshot, PreparedPrice } from '@/data/logisticsRealPriceHistory'

function fixture(): RealPriceSnapshot {
  const prices = [[53, 67], [63, 66], [64, 66], [61, 62], [75, 73], [20, 20], [30, 30]]
  const point = (price: number): PreparedPrice => ({ price, rate: price, fee: 0, chargeWeight: 1, model: 'per-kg', intervalPrice: null, reason: '' })
  return {
    capturedAt: '2026-11-02T02:00:00Z', dataset: 'test',
    channels: prices.map((_, i) => ({ id: 'c' + i, ruleId: i, name: '渠道' + i, provider: '测试商', code: 'C' + i, attribute: '普货', current: `v${i}-2`, enabled: true, quoteReady: true })),
    versions: prices.flatMap((values, i) => values.map((price, n) => ({ id: `v${i}-${n + 1}`, channel: 'c' + i, number: n + 1, status: n ? 'published' : 'superseded', publishedAt: `2026-11-0${n + 1}T02:00:00Z`, fileName: '测试价格.xlsx', importBatchId: n ? 'latest' : 'previous', rows: [], preparedPrices: { 'DE|1': { 全国统一: point(price) }, 'DE|2': { 全国统一: point(price * 2) } } }))),
  }
}

const click = async (host: Element, selector: string) => { const button = host.querySelector<HTMLButtonElement>(selector)!; expect(button).toBeTruthy(); button.focus(); button.click(); await nextTick() }
const fill = async (host: Element, selector: string, value: string) => { const input = host.querySelector<HTMLInputElement>(selector)!; input.value = value; input.dispatchEvent(new Event('input')); await nextTick() }
const select = async (host: Element, selector: string, value: string) => { const input = host.querySelector<HTMLSelectElement>(selector)!; input.value = value; input.dispatchEvent(new Event('change')); await nextTick() }
function mount(data = fixture()) {
  const source = shallowRef(data)
  const host = document.createElement('div'); document.body.append(host)
  const app = createApp({ render: () => h(LogisticsUpdateOverview, { snapshot: source.value, initialCountry: '德国', initialAttribute: '普货/服装', initialWeight: 1, refreshing: false, error: '' }) }); app.mount(host)
  return { host, source, dispose: () => { app.unmount(); host.remove() } }
}

it('defaults to all countries and attributes, shows only rises/falls, and keeps country selection and row pagination independent', async () => {
  const data = fixture()
  data.channels[0]!.attribute = '敏感货'
  const codes = ['DE', 'US', 'GB', 'FR', 'AU', 'CA', 'JP', 'KR', 'IT', 'ES', 'NL', 'BE']
  for (const version of data.versions) version.preparedPrices = Object.fromEntries(codes.map(code => [`${code}|1`, version.preparedPrices!['DE|1']!]))
  const original = JSON.stringify(data)
  const request = vi.spyOn(globalThis, 'fetch')
  const { host, dispose } = mount(data)
  try {
    await nextTick()
    expect(host.querySelector('.country-trigger')!.textContent).toContain('全部国家')
    expect(host.querySelector<HTMLSelectElement>('[aria-label="概览物流属性"]')!.value).toBe('')
    expect(host.querySelector('.overview-stats')!.textContent).toContain('12 个国家')
    expect(host.querySelectorAll('.overview-table tbody tr')).toHaveLength(10)
    expect(host.querySelectorAll('.country-chips button')).toHaveLength(6)
    expect(host.querySelector('[aria-label="渠道明细分页"]')!.textContent).toContain('共 60 条')
    const first = host.querySelector('.overview-table tbody')!.textContent
    await click(host, '[aria-label="下一页渠道明细"]')
    expect(host.querySelector('[aria-label="渠道明细分页"]')!.textContent).toContain('2 / 6')
    expect(host.querySelector('.overview-table tbody')!.textContent).not.toBe(first)
    await click(host, '.country-chips button:nth-child(2)')
    expect(host.querySelectorAll('.overview-table tbody tr')).toHaveLength(5)
    expect(host.querySelector('[aria-label="渠道明细分页"]')!.textContent).toContain('1 / 1')
    expect(host.querySelectorAll('.country-chips button')).toHaveLength(6)
    await click(host, '.country-chips button:first-child')
    await select(host, '[aria-label="每页明细条数"]', '20')
    expect(host.querySelectorAll('.overview-table tbody tr')).toHaveLength(20)
    expect(host.querySelector('[aria-label="渠道明细分页"]')!.textContent).toContain('1 / 3')
    await fill(host, '[aria-label="搜索更新渠道"]', 'US')
    expect(host.querySelectorAll('.overview-table tbody tr')).toHaveLength(5)
    expect(host.querySelector('.overview-table tbody')!.textContent).toContain('美国')
    expect(JSON.stringify(data)).toBe(original)
    expect(request).not.toHaveBeenCalled()
  } finally { dispose(); request.mockRestore() }
})

it('paginates and searches batch cards, preserves selected ID across refresh, and clears stale prices on no results', async () => {
  const data = fixture(), base = data.versions[0]!
  data.channels = [data.channels[0]!]
  data.versions = Array.from({ length: 23 }, (_, i) => ({ ...base, id: `page-v${i}`, number: i + 1, publishedAt: new Date(Date.UTC(2026, 9, i + 1)).toISOString(), importBatchId: `page-b${i}`, baseVersionId: i ? `page-v${i - 1}` : '', preparedPrices: { 'DE|1': { 全国统一: { ...base.preparedPrices!['DE|1']!['全国统一']!, price: 50 + i } } } }))
  data.channels[0]!.current = 'page-v22'
  const { host, source, dispose } = mount(data)
  const selected = () => host.querySelector('.batch-card[aria-pressed="true"]')?.getAttribute('data-batch-id')
  try {
    await nextTick()
    expect(host.querySelectorAll('.batch-card')).toHaveLength(10)
    expect(selected()).toBe('import:page-b22')
    expect(host.querySelector<HTMLButtonElement>('[aria-label="上一页更新批次"]')!.disabled).toBe(true)
    await click(host, '[aria-label="下一页更新批次"]')
    expect(selected()).toBe('import:page-b12')
    expect(host.querySelector('.overview-table tbody')!.textContent).toContain('¥ 62.00')
    await click(host, '[aria-label="下一页更新批次"]')
    expect(host.querySelectorAll('.batch-card')).toHaveLength(3)
    expect(host.querySelector<HTMLButtonElement>('[aria-label="下一页更新批次"]')!.disabled).toBe(true)
    await click(host, '[aria-label="上一页更新批次"]')
    await click(host, '[data-batch-id="import:page-b3"]')
    source.value = { ...data, versions: [...data.versions, { ...data.versions[22]!, id: 'page-v23', number: 24, publishedAt: '2026-10-24T00:00:00Z', importBatchId: 'page-b23', baseVersionId: 'page-v22' }] }; await nextTick()
    expect(selected()).toBe('import:page-b3')
    expect(host.querySelector('.batch-pagination')!.textContent).toContain('3 / 3')
    expect(host.querySelector('.overview-table tbody')!.textContent).toContain('¥ 53.00')
    await fill(host, '[aria-label="搜索更新批次"]', '2026-10-22')
    expect(host.querySelectorAll('.batch-card')).toHaveLength(1)
    expect(selected()).toBe('import:page-b21')
    await fill(host, '[aria-label="搜索更新批次"]', '无匹配操作人')
    expect(host.querySelector('.overview-table tbody')!.textContent).toBe('')
    expect(host.querySelector<HTMLButtonElement>('.primary')!.disabled).toBe(true)
    await fill(host, '[aria-label="搜索更新批次"]', '')
    expect(selected()).toBe('import:page-b23')
    source.value = { ...source.value, versions: [] }; await nextTick()
    expect(host.querySelectorAll('.batch-card')).toHaveLength(0)
    expect(host.querySelector('.overview-table tbody')!.textContent).toBe('')
  } finally { dispose() }
})

it('opens precise row details and restores focus; Escape closes the drawer first without altering parent quotation filters', async () => {
  const state = { country: '德国', attribute: '普货/服装', weight: 1, comparison: 'currentPrevious', search: '', page: 1 }
  const original = JSON.stringify(state)
  const request = vi.spyOn(globalThis, 'fetch')
  const host = document.createElement('div'); document.body.append(host)
  const app = createApp({ render: () => h(LogisticsPriceChanges, { snapshot: fixture(), refreshing: false, error: '', state }) }); app.mount(host)
  try {
    await click(host, '.overview-button')
    const dialog = host.querySelector<HTMLDialogElement>('dialog')!
    expect(dialog.open).toBe(true)
    expect(dialog.querySelector('[aria-label="涨价 14.00 元/票"]')).not.toBeNull()
    const trigger = dialog.querySelector<HTMLButtonElement>('.overview-table tbody tr button')!
    await click(dialog, '.overview-table tbody tr button')
    const drawer = dialog.querySelector('.detail-drawer')!
    expect(drawer.textContent).toContain('¥ 53.00')
    expect(drawer.textContent).toContain('¥ 67.00')
    expect(drawer.textContent).toContain('涨价 14.00 元/票')
    expect(drawer.textContent).toContain('测试价格.xlsx')
    expect(dialog.querySelector('.overview-layout')!.hasAttribute('inert')).toBe(true)
    dialog.dispatchEvent(new Event('cancel', { cancelable: true })); await nextTick(); await nextTick()
    expect(dialog.querySelector('.detail-drawer')).toBeNull()
    expect(dialog.open).toBe(true)
    expect(document.activeElement).toBe(trigger)
    await select(dialog, '[aria-label="概览试算重量"]', '2')
    expect(dialog.querySelector('[aria-label="涨价 28.00 元/票"]')).not.toBeNull()
    expect(JSON.stringify(state)).toBe(original)
    dialog.dispatchEvent(new Event('cancel', { cancelable: true })); await nextTick()
    expect(host.querySelector('dialog')).toBeNull()
    expect(document.activeElement).toBe(host.querySelector('.overview-button'))
    expect(request).not.toHaveBeenCalled()
  } finally { app.unmount(); host.remove(); request.mockRestore() }
})

it('keeps original upload drilldown, four-hour anchor and returned aggregate intact', async () => {
  const data = fixture()
  data.importBatches = [
    { id: 'anchor', importedAt: '2026-11-02T00:00:00Z', operator: '物流甲' },
    { id: 'latest', importedAt: '2026-11-02T00:10:00Z', operator: '物流甲' },
    { id: 'second', importedAt: '2026-11-02T03:59:00Z', operator: '物流甲' },
    { id: 'previous', importedAt: '2026-11-01T00:00:00Z', operator: '物流甲' },
  ]
  data.versions.find(item => item.id === 'v4-2')!.importBatchId = 'second'
  const { host, source, dispose } = mount(data)
  try {
    await nextTick()
    expect(host.querySelector('.batch-card.active')!.getAttribute('data-batch-id')).toBe('window:anchor')
    expect(host.querySelector('.batch-heading')!.textContent).toContain('08:00 — 12:00')
    await click(host, '.batch-heading>button')
    expect(host.querySelectorAll('.original-batch-item')).toHaveLength(3)
    expect(host.querySelector<HTMLButtonElement>('[data-batch="import:anchor"] button')!.disabled).toBe(true)
    await click(host, '[data-batch="import:second"] button')
    expect(host.querySelector('.detail-drawer')).toBeNull()
    expect(host.querySelectorAll('.overview-table tbody tr')).toHaveLength(1)
    expect(host.querySelector('[aria-label="降价 2.00 元/票"]')).not.toBeNull()
    source.value = { ...data, capturedAt: '2026-11-03T00:00:00Z' }; await nextTick()
    expect(host.querySelectorAll('.overview-table tbody tr')).toHaveLength(1)
    await click(host, '.original-scope button')
    expect(host.querySelectorAll('.overview-table tbody tr')).toHaveLength(5)
  } finally { dispose() }
})

it('separates first/incomparable records from default rises/falls and keeps them accessible', async () => {
  const { host, dispose } = mount()
  try {
    await nextTick()
    await click(host, '[data-batch-id="import:previous"]')
    expect(host.querySelectorAll('.overview-table tbody tr')).toHaveLength(0)
    expect(host.querySelector('.stat-exceptional')!.textContent).toContain('7')
    expect(host.querySelector('.overview-empty')!.textContent).toContain('另有 7 条')
    await click(host, '.stat-exceptional')
    expect(host.querySelectorAll('.overview-table tbody tr')).toHaveLength(7)
    expect(host.querySelector('.overview-table tbody')!.textContent).toContain('首次记录')
    expect(host.querySelectorAll('[data-status="up"]')).toHaveLength(0)
  } finally { dispose() }
})

it('retains failure notice and disables export when empty', async () => {
  const host = document.createElement('div'); document.body.append(host)
  const app = createApp({ render: () => h(LogisticsUpdateOverview, { snapshot: { ...fixture(), channels: [], versions: [] }, initialCountry: '德国', initialAttribute: '', initialWeight: 1, refreshing: true, error: '读取失败' }) }); app.mount(host)
  try {
    await nextTick()
    expect(host.textContent).toContain('当前范围暂无已发布更新记录')
    expect(host.textContent).toContain('当前保留上次成功读取的数据')
    expect(host.querySelector<HTMLButtonElement>('.primary')!.disabled).toBe(true)
  } finally { app.unmount(); host.remove() }
})
