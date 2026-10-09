// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, reactive, type App } from 'vue'
import ReviewButton from './QuotationReviewButton.vue'
import { normalizeQuotationRecord } from '@/data/quotationRecords'

let app: App
afterEach(() => { app?.unmount(); document.body.innerHTML = '' })
function mount(quoteMode: 'single' | 'fob' = 'single') {
  const record = normalizeQuotationRecord({ id: 'q1', no: 'QT-1', quoteMode, _version: 2, _reviewVersion: 1, financeReviewStatus: 'reviewing', financeReviewClaimedAccount: 'FINANCE' })!
  const props = reactive({ record, state: { ...record }, account: 'FINANCE', busy: false })
  const action = vi.fn(), reload = vi.fn()
  const host = document.createElement('div'); document.body.append(host)
  app = createApp(ReviewButton, { ...props, onAction: action, onReload: reload })
  // The record/state objects remain reactive so external polling can invalidate an open dialog.
  app.mount(host)
  return { props, action }
}
const open = () => document.querySelector<HTMLButtonElement>('.review-button')!.click()
const choose = () => document.querySelector<HTMLButtonElement>('.review-results .logistics-exempt')!

it.each(['single', 'fob'] as const)('offers the manual logistics-exempt conclusion for %s', async mode => {
  const { action } = mount(mode); open(); await nextTick()
  expect(choose().textContent).toContain('物流免审-采购已审')
  expect(document.querySelectorAll('.review-results button')).toHaveLength(mode === 'fob' ? 3 : 4)
  const note = document.querySelector<HTMLTextAreaElement>('textarea')!
  note.value = '  采购价格已核对  '; note.dispatchEvent(new Event('input')); await nextTick()
  choose().click()
  expect(action).toHaveBeenCalledExactlyOnceWith({ action: 'complete', financeReviewStatus: 'logistics-exempt', note: '采购价格已核对' })
})

it.each(['quote', 'review', 'claimant'] as const)('blocks the new conclusion after a stale %s update', async changed => {
  const { props, action } = mount(); open(); await nextTick()
  if (changed === 'quote') props.state._version = 3
  else if (changed === 'review') props.state._reviewVersion = 2
  else props.state.financeReviewClaimedAccount = 'OTHER'
  await nextTick()
  expect(document.querySelector<HTMLDialogElement>('dialog')!.open).toBe(false)
  expect(choose().disabled).toBe(true)
  choose().click(); expect(action).not.toHaveBeenCalled()
})
