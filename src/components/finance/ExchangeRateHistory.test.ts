// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, type App } from 'vue'
import History from './ExchangeRateHistory.vue'
const load = vi.hoisted(() => vi.fn())
vi.mock('@/services/exchangeRateHistory', async original => ({ ...await original<object>(), loadExchangeRateHistory: load }))
let app: App
afterEach(() => { app?.unmount(); document.body.innerHTML = ''; load.mockReset() })
const response = (page = 0, total = 13) => ({ items: [{ id: String(page), currency: 'usdCny', before: 6.7, after: 6.63, modifiedAt: '2026-11-26T01:30:12Z', actorAccount: 'FINANCE', actorName: '财务人员' }], total, page, size: 10, latest: {} })
async function flush() { for (let i=0; i<8; i++) { await Promise.resolve(); await nextTick() } }
async function mount() { const el = document.createElement('div'); document.body.append(el); app=createApp({ render: () => h(History, { revision: 0 }) }); app.mount(el); await flush(); return el }
function button(el: HTMLElement, text: string) { return [...el.querySelectorAll('button')].find(b => b.textContent === text)! }
it('shows server time in Shanghai and paginates, resetting the page on filter changes', async () => {
  load.mockResolvedValueOnce(response()).mockResolvedValueOnce(response(1)).mockResolvedValueOnce(response(0,1))
  const el=await mount(); expect(el.textContent).toContain('09:30:12'); expect(el.textContent).toContain('财务人员'); expect(button(el,'上一页').disabled).toBe(true)
  button(el,'下一页').click(); await flush(); expect(load.mock.calls[1]?.slice(0,2)).toEqual(['all',1]); expect(button(el,'下一页').disabled).toBe(true)
  button(el,'欧元 → 美元').click();await flush();expect(load.mock.calls[2]?.slice(0,2)).toEqual(['eurUsd',0]);expect(el.textContent).toContain('每页 10 条')
})
it('discards stale requests when switching filters and supports retry after errors', async () => {
  let resolve!: (value: ReturnType<typeof response>) => void
  load.mockImplementationOnce(() => new Promise(r => { resolve=r })).mockRejectedValueOnce(new Error('读取失败')).mockResolvedValueOnce(response(0,1))
  const el=await mount(); button(el,'欧元 → 美元').click(); await flush(); resolve(response(0,99)); await flush()
  expect(el.textContent).toContain('读取失败');expect(el.textContent).not.toContain('99');expect(load.mock.calls[0]?.[2].aborted).toBe(true)
  button(el,'重试').click();await flush();expect(el.textContent).toContain('共 1 条')
})
