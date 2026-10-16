// @vitest-environment happy-dom
import { createApp, nextTick, type App } from 'vue'
import { afterEach, expect, it } from 'vitest'
import QuotationRevisionSnapshot from './QuotationRevisionSnapshot.vue'

let app: App | undefined
let host: HTMLDivElement | undefined
afterEach(() => { app?.unmount(); host?.remove(); app = undefined; host = undefined })

function snapshot(shippingNote?: string, quoteMode = 'shipping-only') {
  return JSON.stringify({ id: 'quote-1', no: 'QT-1', quoteMode, shippingNote, customerName: '客户A', systemQuoteUsd: 12, quoteOptions: [] })
}

async function render(before: string, after: string) {
  host = document.createElement('div')
  document.body.append(host)
  app = createApp(QuotationRevisionSnapshot, { before, after })
  app.mount(host)
  await nextTick()
  return host
}

it.each([
  { before: undefined, after: 'TC2600009-1', values: ['未填写', 'TC2600009-1'] },
  { before: 'TC2600009-1', after: 'TC2600009-2 / 客户自备', values: ['TC2600009-1', 'TC2600009-2 / 客户自备'] },
  { before: '客户自备商品', after: '', values: ['客户自备商品', '未填写'] },
])('shows shipping note additions, edits and clearing in historical revisions: $values', async ({ before, after, values }) => {
  const root = await render(snapshot(before), snapshot(after))
  const row = [...root.querySelectorAll('tbody tr')].find(item => item.querySelector('th')?.textContent === '备注信息')
  expect([...row!.querySelectorAll('td')].map(cell => cell.textContent)).toEqual(values)
  expect(root.textContent).toContain('$12.00')
})

it('keeps non-shipping historical quotes unchanged even if they contain an unrelated note field', async () => {
  const root = await render(snapshot('不属于商品报价', 'single'), snapshot(undefined, 'bundle'))
  expect(root.textContent).not.toContain('备注信息')
  expect(root.textContent).not.toContain('不属于商品报价')
  expect(root.textContent).toContain('客户A')
})

it('shows shipping remarks as plain text and preserves them when the revised mode changes', async () => {
  const note = '<img src=x onerror=alert(1)> / TC2600009-1'
  const root = await render(snapshot(note), snapshot(undefined, 'freight-trial'))
  const row = [...root.querySelectorAll('tbody tr')].find(item => item.querySelector('th')?.textContent === '备注信息')
  expect([...row!.querySelectorAll('td')].map(cell => cell.textContent)).toEqual([note, '不适用'])
  expect(root.querySelector('img')).toBeNull()
})
