// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, reactive, type App } from 'vue'
import CopyActions from './QuotationRecordCopyActions.vue'
import { normalizeQuotationRecord, updateQuotationRecord, type QuotationRecord } from '@/data/quotationRecords'
import { renderCustomerQuoteSheet } from '@/services/customerQuoteSheetRenderer'

vi.mock('@/services/customerQuoteSheetRenderer', async importOriginal => ({
  ...await importOriginal<typeof import('@/services/customerQuoteSheetRenderer')>(), renderCustomerQuoteSheet: vi.fn(),
}))
vi.mock('@/data/quotationRecords', async original => ({ ...await original<typeof import('@/data/quotationRecords')>(), updateQuotationRecord: vi.fn() }))
let app: App
const writeText = vi.fn(), render = vi.mocked(renderCustomerQuoteSheet)
function record(id = 'one', carrier = '闪电猴') {
  return normalizeQuotationRecord({ id, no: `QT-${id}`, salespersonName: 'Alex', customQuoteQuantity: 5,
    quoteOptions: [{ id: 'a', country: '美国', countryCode: 'US', carrier, channel: '内部渠道', rule: '内部规则', eta: '5-12 天', quote1Usd: 6.2, quote2Usd: 8.3, quote3Usd: 10.95, quoteCustomUsd: 15.9 }] })!
}
function mount(carrier?: string) {
  const state = reactive({ record: record('one', carrier), canEdit: false })
  const host = document.createElement('div'); document.body.append(host)
  app = createApp({ render: () => h(CopyActions, { ...state, onSaved: (saved: QuotationRecord) => { state.record = saved } }) }); app.mount(host); return state
}
async function settle() { for (let i = 0; i < 10; i++) await nextTick() }
function footerButton(label: string) { return [...document.querySelectorAll<HTMLButtonElement>('.record-copy-actions button')].find(button => button.textContent === label)! }
beforeEach(() => {
  vi.mocked(updateQuotationRecord).mockReset()
  writeText.mockReset().mockResolvedValue(undefined)
  render.mockReset().mockResolvedValue([{ blob: new Blob(['png'], { type: 'image/png' }), width: 1536, height: 1024, firstRow: 1, lastRow: 1 }])
  vi.stubGlobal('isSecureContext', true); vi.stubGlobal('navigator', { clipboard: { writeText } })
  vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:quote'); vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
})
afterEach(() => { app?.unmount(); document.body.innerHTML = ''; vi.restoreAllMocks(); vi.unstubAllGlobals() })

it('saves edited signature and contact from the record sheet and restores them on reopening', async () => {
  const state = mount(); state.canEdit = true; await settle()
  footerButton('复制报价图片').click(); await settle()
  const edit = [...document.querySelectorAll('button')].find(button => button.textContent === '编辑报价单')!
  edit.click(); await settle()
  for (const [label, value] of [['报价单署名', 'Vivian'], ['WhatsApp 联系方式', '+183 5650 6953']]) {
    const field = document.querySelector<HTMLInputElement>(`input[aria-label="${label}"]`)!
    field.value = value!; field.dispatchEvent(new Event('input', { bubbles: true }))
  }
  await settle()
  vi.mocked(updateQuotationRecord).mockImplementation(async (_id, patch) => normalizeQuotationRecord(JSON.parse(JSON.stringify({ ...state.record, ...patch, _version: 2 })))!)
  ;[...document.querySelectorAll('button')].find(button => button.textContent === '保存客户报价')!.click(); await settle()
  expect(vi.mocked(updateQuotationRecord).mock.lastCall![1].customerQuote?.contact).toEqual({ agent: 'Vivian', whatsapp: '+183 5650 6953' })
  expect(state.record.salespersonName).toBe('Alex')
  footerButton('复制报价图片').click(); await settle()
  expect(render.mock.lastCall![0]).toMatchObject({ agent: 'Vivian', whatsapp: '+183 5650 6953' })
})

it('both record scopes share working footer copying, with the saved USD snapshot and no business writes', async () => {
  const state = mount()
  state.record.quoteOptions![0]!.logisticsSamples = [{ quantity: 1, input: { weightKg: 0.6 }, total: 38.51 }]
  const before = JSON.stringify(state.record)
  footerButton('复制报价数据').click(); await settle()
  expect(writeText.mock.calls[0][0]).toContain('美国\t闪电猴｜内部渠道')
  expect(writeText.mock.calls[0][0]).toContain('计费规则：内部规则')
  expect(writeText.mock.calls[0][0]).toContain('系统报价 USD')
  expect(writeText.mock.calls[0][0]).toContain('6.20')
  expect(writeText.mock.calls[0][0]).toContain('物流运费 CNY')
  expect(writeText.mock.calls[0][0]).toContain('38.51')
  expect(document.querySelector('dialog')!.open).toBe(false)
  footerButton('复制报价图片').click(); await settle()
  expect(document.querySelector('dialog')!.open).toBe(true)
  expect(render.mock.calls[0][0].rows[0]!.provider).toBe('SDH Express')
  document.querySelector<HTMLButtonElement>('[aria-label="关闭客户报价单"]')!.click(); await settle()
  expect(document.querySelector('dialog')!.open).toBe(false)
  expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:quote')
  expect(JSON.stringify(state.record)).toBe(before)
})

it('copies internal details without needing an English provider name or rendering a customer image', async () => {
  const state = mount('新物流')
  footerButton('复制报价数据').click(); await settle()
  expect(writeText.mock.lastCall![0]).toContain('新物流｜内部渠道')
  expect(render).not.toHaveBeenCalled()
  expect(state.record.quoteOptions![0]!.carrier).toBe('新物流')
})

it('reports clipboard rejection and retries the full saved data without an editor', async () => {
  writeText.mockRejectedValueOnce(new Error('denied'))
  mount(); footerButton('复制报价数据').click(); await settle()
  expect(document.querySelector('.record-copy-actions>p')?.textContent).toContain('未复制成功')
  footerButton('重新复制对账明细').click(); await settle()
  expect(writeText).toHaveBeenCalledTimes(2)
  expect(document.querySelector('.record-copy-actions>p')?.textContent).toContain('已复制完整对账明细')
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


it('a rejected financial review is advisory and keeps both copy paths available', async () => {
  const state=mount();state.record.financeReviewStatus='rejected';await settle()
  footerButton('复制报价数据').click();await settle()
  expect(writeText.mock.lastCall![0]).toContain('价格异常')
  footerButton('复制报价图片').click();await settle()
  expect(document.querySelector('dialog')!.open).toBe(true)
  expect(render).toHaveBeenCalledTimes(1)
})

it('copies the quote-only route table and retries that same scope after clipboard failure', async () => {
  writeText.mockRejectedValueOnce(new Error('denied'))
  const state = mount(), before = JSON.stringify(state.record)
  footerButton('仅复制报价单').click(); await settle()
  expect(footerButton('重新复制报价单')).toBeTruthy()
  footerButton('重新复制报价单').click(); await settle()
  expect(writeText).toHaveBeenCalledTimes(2)
  for (const call of writeText.mock.calls) {
    expect(call[0]).toContain('美国\t闪电猴｜内部渠道')
    expect(call[0]).not.toContain('内部规则')
    expect(call[0]).not.toContain('产品成本快照')
    expect(call[0]).toContain('总成本价（CNY/1件）')
    expect(call[0]).toContain('含包材重量（g/1件）')
  }
  expect(document.querySelector('[role="status"]')?.textContent).toContain('已复制报价单（含物流渠道）')
  expect(JSON.stringify(state.record)).toBe(before)
  expect(render).not.toHaveBeenCalled()
})
