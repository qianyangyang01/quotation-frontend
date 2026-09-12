// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, reactive, type App } from 'vue'
import CopyActions from './QuotationRecordCopyActions.vue'
import { normalizeQuotationRecord } from '@/data/quotationRecords'
import { renderCustomerQuoteSheet } from '@/services/customerQuoteSheetRenderer'

vi.mock('@/services/customerQuoteSheetRenderer', async importOriginal => ({
  ...await importOriginal<typeof import('@/services/customerQuoteSheetRenderer')>(), renderCustomerQuoteSheet: vi.fn(),
}))
let app: App
const writeText = vi.fn(), render = vi.mocked(renderCustomerQuoteSheet)
function record(id = 'one', carrier = '闪电猴') {
  return normalizeQuotationRecord({ id, no: `QT-${id}`, salespersonName: 'Alex', customQuoteQuantity: 5,
    quoteOptions: [{ id: 'a', country: '美国', countryCode: 'US', carrier, channel: '内部渠道', rule: '内部规则', eta: '5-12 天', quote1Usd: 6.2, quote2Usd: 8.3, quote3Usd: 10.95, quoteCustomUsd: 15.9 }] })!
}
function mount(carrier?: string) {
  const state = reactive({ record: record('one', carrier) })
  const host = document.createElement('div'); document.body.append(host)
  app = createApp({ render: () => h(CopyActions, state) }); app.mount(host); return state
}
async function settle() { for (let i = 0; i < 10; i++) await nextTick() }
function footerButton(label: string) { return [...document.querySelectorAll<HTMLButtonElement>('.record-copy-actions button')].find(button => button.textContent === label)! }
beforeEach(() => {
  writeText.mockReset().mockResolvedValue(undefined)
  render.mockReset().mockResolvedValue([{ blob: new Blob(['png'], { type: 'image/png' }), width: 1536, height: 1024, firstRow: 1, lastRow: 1 }])
  vi.stubGlobal('isSecureContext', true); vi.stubGlobal('navigator', { clipboard: { writeText } })
  vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:quote'); vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
})
afterEach(() => { app?.unmount(); document.body.innerHTML = ''; vi.restoreAllMocks(); vi.unstubAllGlobals() })

it('both record scopes share working footer copying, with the saved USD snapshot and no business writes', async () => {
  const state = mount(); const before = JSON.stringify(state.record)
  footerButton('复制报价数据').click(); await settle()
  expect(writeText.mock.calls[0][0]).toContain('SDH Express\t5-12 days\t1-2 days\t$6.20')
  expect(document.querySelector('dialog')!.open).toBe(false)
  footerButton('复制报价图片').click(); await settle()
  expect(document.querySelector('dialog')!.open).toBe(true)
  expect(render.mock.calls[0][0].rows[0]!.provider).toBe('SDH Express')
  document.querySelector<HTMLButtonElement>('[aria-label="关闭客户报价单"]')!.click(); await settle()
  expect(document.querySelector('dialog')!.open).toBe(false)
  expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:quote')
  expect(JSON.stringify(state.record)).toBe(before)
})

it('opens a repairable editor from failed record copying and reuses the local supplement', async () => {
  const state = mount('新物流')
  footerButton('复制报价数据').click(); await settle(); expect(writeText).not.toHaveBeenCalled()
  footerButton('打开报价单检查并重试').click(); await settle()
  const field = document.querySelector<HTMLInputElement>('[aria-label="新物流英文名"]')!
  expect(field).not.toBeNull(); field.value = 'New Logistics'; field.dispatchEvent(new Event('input')); await settle()
  document.querySelector<HTMLButtonElement>('[aria-label="关闭客户报价单"]')!.click(); await settle()
  footerButton('复制报价数据').click(); await settle()
  expect(writeText.mock.lastCall![0]).toContain('New Logistics')
  expect(state.record.quoteOptions![0]!.carrier).toBe('新物流')
})

it('does not display old clipboard completion on another record', async () => {
  let resolve!: () => void
  writeText.mockImplementationOnce(() => new Promise<void>(done => { resolve = done }))
  const state = mount(); footerButton('复制报价数据').click(); await settle()
  state.record = record('two'); await settle(); resolve(); await settle()
  expect(document.querySelector('.record-copy-actions>p')).toBeNull()
  footerButton('复制报价数据').click(); await settle()
  expect(document.querySelector('.record-copy-actions>p')?.textContent).toContain('已复制')
})

it('does not start a hidden render when the dialog closes before the opening tick completes', async () => {
  mount()
  footerButton('复制报价图片').click()
  document.querySelector<HTMLButtonElement>('[aria-label="关闭客户报价单"]')!.click()
  await settle()
  expect(document.querySelector('dialog')!.open).toBe(false)
  expect(render).not.toHaveBeenCalled()
})

it('does not render another record after an in-flight opening switches context', async () => {
  const state = mount()
  footerButton('复制报价图片').click()
  state.record = record('two')
  await settle()
  expect(document.querySelector('dialog')!.open).toBe(false)
  expect(render).not.toHaveBeenCalled()
})
