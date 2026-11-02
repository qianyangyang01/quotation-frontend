// @vitest-environment happy-dom
import { createApp, h, nextTick, shallowRef } from 'vue'
import { expect, it, vi } from 'vitest'
import LogisticsPriceChanges from './LogisticsPriceChanges.vue'
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

it('opens from the header, shows exact before/after and money, filters, switches batches/weight, and closes without requests or parent-filter changes', async () => {
  const state = { country: '德国', attribute: '普货/服装', weight: 1, comparison: 'currentPrevious', search: '', page: 1 }
  const source = shallowRef(fixture())
  const request = vi.spyOn(globalThis, 'fetch')
  const host = document.createElement('div'); document.body.append(host)
  const app = createApp({ render: () => h(LogisticsPriceChanges, { snapshot: source.value, refreshing: false, error: '', state }) }); app.mount(host)
  try {
    const trigger = host.querySelector<HTMLButtonElement>('.overview-button')!
    trigger.focus(); trigger.click(); await nextTick()
    const dialog = host.querySelector<HTMLDialogElement>('dialog')!
    expect(dialog.open).toBe(true)
    expect(dialog.querySelectorAll('tbody tr')).toHaveLength(5)
    expect(dialog.querySelector('tbody tr')!.textContent).toContain('涨价 14.00 元/票')
    expect(dialog.querySelector('tbody tr')!.textContent).toContain('¥ 53.00')
    expect(dialog.querySelector('tbody tr')!.textContent).toContain('¥ 67.00')
    expect(dialog.textContent).toContain('降价 2.00 元/票')
    expect(dialog.querySelector('.stat-up')!.textContent).toContain('4')
    expect(dialog.querySelector('.stat-down')!.textContent).toContain('1')
    const buttons = [...dialog.querySelectorAll<HTMLButtonElement>('.overview-tabs button')]
    buttons.find(button => button.textContent?.startsWith('全部'))!.click(); await nextTick()
    expect(dialog.querySelectorAll('tbody tr')).toHaveLength(7)
    const weight = dialog.querySelector<HTMLSelectElement>('[aria-label="概览试算重量"]')!
    weight.value = '2'; weight.dispatchEvent(new Event('change')); await nextTick()
    expect(dialog.textContent).toContain('涨价 28.00 元/票')
    expect(state.weight).toBe(1)
    const batch = dialog.querySelector<HTMLSelectElement>('[aria-label="更新批次"]')!
    batch.value = 'import:previous'; batch.dispatchEvent(new Event('change')); await nextTick()
    expect(dialog.textContent).toContain('首次记录')
    expect(dialog.querySelector('.stat-up')!.textContent).toContain('0')
    source.value = { ...source.value, capturedAt: '2026-11-03T02:00:00Z', versions: [...source.value.versions] }; await nextTick()
    expect(batch.value).toBe('import:previous')
    expect(weight.value).toBe('2')
    dialog.dispatchEvent(new Event('cancel', { cancelable: true })); await nextTick()
    expect(host.querySelector('dialog')).toBeNull()
    expect(document.activeElement).toBe(trigger)
    expect(request).not.toHaveBeenCalled()
  } finally { app.unmount(); host.remove(); request.mockRestore() }
})

it('shows the empty state, preserves errors and does not enable an empty export', async () => {
  const host = document.createElement('div'); document.body.append(host)
  const data = { ...fixture(), channels: [], versions: [] }
  const app = createApp({ render: () => h(LogisticsPriceChanges, { snapshot: data, refreshing: true, error: '读取失败' }) }); app.mount(host)
  try {
    host.querySelector<HTMLButtonElement>('.overview-button')!.click(); await nextTick()
    const dialog = host.querySelector('dialog')!
    expect(dialog.textContent).toContain('当前范围暂无已发布更新记录')
    expect(dialog.textContent).toContain('当前保留上次成功读取的数据')
    expect(dialog.querySelector<HTMLButtonElement>('.primary')!.disabled).toBe(true)
  } finally { app.unmount(); host.remove() }
})

it('expands original uploads and drills down without changing the four-hour group or losing other updates', async () => {
  const data = fixture()
  data.importBatches = [
    { id: 'unpublished-first', importedAt: '2026-11-02T00:00:00Z', operator: '物流甲' },
    { id: 'latest', importedAt: '2026-11-02T00:10:00Z', operator: '物流甲' },
    { id: 'second', importedAt: '2026-11-02T03:59:00Z', operator: '物流甲' },
    { id: 'previous', importedAt: '2026-11-01T00:00:00Z', operator: '物流甲' },
  ]
  data.versions.find(item => item.id === 'v4-2')!.importBatchId = 'second'
  const source = shallowRef(data)
  const host = document.createElement('div'); document.body.append(host)
  const app = createApp({ render: () => h(LogisticsPriceChanges, { snapshot: source.value, refreshing: false, error: '' }) }); app.mount(host)
  try {
    host.querySelector<HTMLButtonElement>('.overview-button')!.click(); await nextTick()
    const dialog = host.querySelector<HTMLDialogElement>('dialog')!
    const selection = dialog.querySelector<HTMLSelectElement>('[aria-label="更新批次"]')!
    expect(selection.value).toBe('window:unpublished-first')
    expect(dialog.textContent).toContain('08:00 — 2026-11-02 12:00')
    const details = dialog.querySelector<HTMLDetailsElement>('.original-batches')!
    details.open = true
    expect(details.querySelectorAll('.original-batch-item')).toHaveLength(3)
    expect(details.querySelector<HTMLButtonElement>('[data-batch="import:unpublished-first"] button')!.disabled).toBe(true)
    details.querySelector<HTMLButtonElement>('[data-batch="import:second"] button')!.click(); await nextTick()
    expect(dialog.querySelectorAll('tbody tr')).toHaveLength(1)
    expect(dialog.querySelector('tbody')!.textContent).toContain('降价 2.00 元/票')
    expect(dialog.querySelector('.stat-up')!.textContent).toContain('0')
    source.value = { ...data, capturedAt: '2026-11-03T00:00:00Z' }; await nextTick()
    expect(selection.value).toBe('window:unpublished-first')
    expect(dialog.querySelectorAll('tbody tr')).toHaveLength(1)
    dialog.querySelector<HTMLButtonElement>('.original-scope button')!.click(); await nextTick()
    expect(dialog.querySelectorAll('tbody tr')).toHaveLength(5)
    expect(dialog.querySelector('.stat-up')!.textContent).toContain('4')
  } finally { app.unmount(); host.remove() }
})
