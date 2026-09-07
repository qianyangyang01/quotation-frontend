// @vitest-environment happy-dom
import { createApp, nextTick } from 'vue'
import { afterEach, expect, it, vi } from 'vitest'
import LogisticsIssueSummary from './LogisticsIssueSummary.vue'
const cleanup: Array<() => void> = []
afterEach(() => { cleanup.splice(0).forEach(fn => fn()) })
it('shows missing original rows and raw evidence without offering a false price-row jump', async () => {
  const host = document.createElement('div'); document.body.append(host)
  const app = createApp(LogisticsIssueSummary, { rows: [], issues: [{ row: 0, sourceSheet: '通邮专线特货', sourceRows: [24], field: '未覆盖价格行', message: '无法解析', level: 'error', sourceEvidence: [{ row: 24, rawValues: { A: '<img src=x>' } }] }] })
  app.mount(host); cleanup.push(() => { app.unmount(); host.remove() }); await nextTick()
  expect(host.textContent).toContain('Sheet「通邮专线特货」· 第 24 行')
  expect(host.textContent).toContain('无法在下方标红')
  expect(host.querySelector('button')).toBeNull()
  expect(host.querySelector('img')).toBeNull()
})
it('offers a jump for a matched row and emits the exact issue without changing prices', async () => {
  const host = document.createElement('div'); document.body.append(host)
  const issue = { row: 4, sourceSheet: 'A', field: '公斤价', message: '无效价格', level: 'error' }
  const rows = [{ areaName: '美国', countryCode: 'US', sourceSheet: 'A', sourceRow: 4, weightFromKg: 0, weightToKg: 1, pricePerKg: 0 }]
  const locate = vi.fn()
  const app = createApp(LogisticsIssueSummary, { rows, issues: [issue], onLocate: locate })
  app.mount(host); cleanup.push(() => { app.unmount(); host.remove() }); await nextTick()
  host.querySelector('button')!.click()
  expect(locate).toHaveBeenCalledWith(issue)
  expect(rows[0]!.pricePerKg).toBe(0)
})
