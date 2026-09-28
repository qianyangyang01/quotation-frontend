// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, reactive, type App } from 'vue'
import Comments from './QuotationReviewComments.vue'
import { normalizeQuotationRecord, type QuotationReviewEvent, type QuotationReviewState } from '@/data/quotationRecords'
const mocks = vi.hoisted(() => ({ get: vi.fn(), patch: vi.fn() }))
vi.mock('@/services/http', () => ({ api: mocks }))
let app: App
const flush = async () => { for (let i = 0; i < 8; i++) { await nextTick(); await Promise.resolve() } }
const event = (note = '请核实包装重量'): QuotationReviewEvent => ({ id: 'c1', action: 'comment', before: 'pending', after: 'pending', actorName: '财务甲', actorAccount: 'F1', at: '2026-09-28T06:00:00Z', note, quoteVersion: 2 })
const record = () => normalizeQuotationRecord({ id: 'r1', no: 'QT-1', _version: 2, _reviewVersion: 0, financeReviewStatus: 'pending', primarySku: 'TC2602094', customerName: '新客户' })!
const button = (label: string) => [...document.querySelectorAll('button')].find(el => el.textContent === label)!
const dialog = () => document.querySelector<HTMLDialogElement>('dialog')!
async function mount(canReview = true, state: QuotationReviewState = record()) {
  const props = reactive({ record: record(), state, account: canReview ? 'F1' : 'E1', canReview, busy: false })
  const saved = vi.fn()
  mocks.get.mockResolvedValue([])
  const host = document.createElement('div'); document.body.append(host)
  // A render closure preserves reactive updates, including account and polling changes.
  app = createApp({ render: () => h(Comments, { ...props, onSaved: saved }) }); app.mount(host)
  await flush()
  return { props, saved }
}
async function type(text: string) { const input = document.querySelector<HTMLTextAreaElement>('textarea')!; input.value = text; input.dispatchEvent(new Event('input')); await flush() }
afterEach(() => { app?.unmount(); document.body.innerHTML = ''; vi.resetAllMocks() })

it('saves a standalone opinion with snapshot versions and shows failures without losing text', async () => {
  const { saved } = await mount()
  button('审核意见').click(); await flush()
  expect(dialog().open).toBe(true); expect(button('保存意见').disabled).toBe(true)
  await type('  请核实包装重量  ')
  mocks.patch.mockRejectedValueOnce(new Error('网络异常，请重试'))
  button('保存意见').click(); await flush()
  expect(dialog().open).toBe(true); expect(document.body.textContent).toContain('网络异常，请重试')
  expect(document.querySelector('textarea')?.value).toContain('请核实包装重量')
  mocks.patch.mockResolvedValue({ ...record(), _reviewVersion: 1, financeReviewCommentCount: 1, financeReviewLatestComment: event() })
  button('保存意见').click(); await flush()
  expect(mocks.patch).toHaveBeenLastCalledWith('/quotations/r1/finance-review', { action: 'comment', note: '请核实包装重量', _version: 2, _reviewVersion: 0 })
  expect(saved).toHaveBeenCalledWith(expect.objectContaining({ financeReviewStatus: 'pending', financeReviewLatestComment: event(), financeReviewCommentCount: 1 }))
  expect(dialog()).toBeNull()
})
it('employees see the summary, author, time and full history without editable controls', async () => {
  await mount(false, { ...record(), financeReviewCommentCount: 1, financeReviewLatestComment: event() })
  expect(document.querySelector('.comment-preview')?.textContent).toContain('请核实包装重量')
  expect(document.querySelector('.comment-preview')?.textContent).toContain('财务甲')
  mocks.get.mockResolvedValue([event(), { ...event('内部取消原因'), id: 'x', action: 'cancel' }, { ...event(''), id: 'empty', action: 'complete' }])
  button('查看审核意见 · 1').click(); await flush()
  expect(dialog().textContent).toContain('历史意见 · 1')
  expect(dialog().textContent).toContain('财务甲 · 2026/9/28 14:00:00')
  expect(document.querySelector('textarea')).toBeNull(); expect(button('保存意见')).toBeUndefined()
  expect(dialog().textContent).not.toContain('内部取消原因'); expect(mocks.patch).not.toHaveBeenCalled()
})
it('hides an empty opinion section for employees and updates from live polling', async () => {
  const { props } = await mount(false)
  expect(document.querySelector('.review-comments')).toBeNull()
  props.state = { ...record(), _reviewVersion: 1, financeReviewCommentCount: 1, financeReviewLatestComment: event() }; await flush()
  expect(button('查看审核意见 · 1')).toBeDefined()
  expect(document.querySelector('.comment-preview')?.textContent).toContain('请核实包装重量')
})
it('retains a draft but prevents silently using a changed review snapshot', async () => {
  const { props } = await mount()
  button('审核意见').click(); await flush(); await type('原建议')
  props.state = { ...record(), _reviewVersion: 1 }; await flush()
  expect(button('保存意见').disabled).toBe(true); expect(dialog().textContent).toContain('报价或审核记录已更新')
  button('取消').click(); await flush(); button('审核意见').click(); await flush()
  expect(document.querySelector('textarea')?.value).toBe('原建议'); expect(button('保存意见').disabled).toBe(false)
})
it('keeps the dialog and typed opinion through unchanged list refreshes', async () => {
  const { props } = await mount()
  button('审核意见').click(); await flush(); await type('尚未保存的建议')
  props.record = record(); props.state = record(); await flush()
  expect(dialog().open).toBe(true)
  expect(document.querySelector('textarea')?.value).toBe('尚未保存的建议')
  expect(button('保存意见').disabled).toBe(false)
})
it('retries history failures and never submits employee or archived opinions', async () => {
  const { props } = await mount()
  mocks.get.mockRejectedValueOnce(new Error('offline')); button('审核意见').click(); await flush()
  expect(dialog().textContent).toContain('审核意见加载失败')
  mocks.get.mockResolvedValue([event()]); button('重新加载意见').click(); await flush()
  expect(dialog().textContent).toContain('请核实包装重量')
  props.state = { ...record(), lifecycleState: 'archived' }; await flush()
  expect(document.querySelector('textarea')).toBeNull(); expect(button('保存意见')).toBeUndefined()
  expect(mocks.patch).not.toHaveBeenCalled()
})
it('clears an open dialog and draft on account changes', async () => {
  const { props } = await mount()
  button('审核意见').click(); await flush(); await type('未提交意见')
  props.account = 'E2'; props.canReview = false; await flush()
  expect(dialog()).toBeNull(); expect(document.body.textContent).not.toContain('未提交意见')
})
