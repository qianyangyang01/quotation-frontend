// @vitest-environment happy-dom
import { createApp, nextTick, type App } from 'vue'
import { afterEach, expect, it, vi } from 'vitest'
import Button from './QuotationReviewButton.vue'
import { normalizeQuotationRecord } from '@/data/quotationRecords'
let app: App
afterEach(() => { app?.unmount(); document.body.innerHTML = '' })
const button = (label: string) => [...document.querySelectorAll('button')].find(el => el.textContent?.trim() === label)!
async function mount() {
  const bundleItems = ['SKU1', 'SKU2'].map(sku => ({ sku, name: sku, quantityPerSet: 1, effectiveWeightKg: 0.1, purchaseUnitPriceCny: 2, domesticFreightPerUnitCny: 0.1 }))
  const record = normalizeQuotationRecord({ id: 'r', no: 'QT', _version: 2, _reviewVersion: 1, quoteMode: 'bundle', primarySku: 'SKU1 + SKU2', bundleItems, financeReviewStatus: 'reviewing', financeReviewClaimedAccount: 'ADMIN' })!
  const host = document.createElement('div'); document.body.append(host); const action = vi.fn()
  app = createApp(Button, { record, state: record, account: 'ADMIN', busy: false, onAction: action }); app.mount(host)
  await nextTick(); button('审核完成').click(); await nextTick(); button('价格异常不可报价').click(); await nextTick()
  return action
}
it('requires explicit affected SKUs for a purchase exception on a bundle', async () => {
  const action = await mount()
  const select = document.querySelector<HTMLSelectElement>('[aria-label="异常类型"]')!
  expect(select.value).toBe(''); expect(action).not.toHaveBeenCalled()
  select.value = 'purchase-price'; select.dispatchEvent(new Event('change')); await nextTick()
  const note = document.querySelector<HTMLTextAreaElement>('[aria-label="异常说明"]')!
  note.value = '   '; note.dispatchEvent(new Event('input')); await nextTick()
  expect(button('确认价格异常').disabled).toBe(true)
  const checks = document.querySelectorAll<HTMLInputElement>('.exception-skus input')
  expect(checks).toHaveLength(2); expect([...checks].every(el => !el.checked)).toBe(true)
  checks[1]!.click(); await nextTick(); button('确认价格异常').click()
  expect(action).toHaveBeenCalledWith({ action: 'complete', financeReviewStatus: 'rejected', note: '', exceptionType: 'purchase-price', exceptionSkus: ['SKU2'] })
})
it('does not send rejection metadata when returning to approval', async () => {
  const action = await mount(); button('返回审核结果').click(); await nextTick()
  button('审核通过可报价').click()
  expect(action).toHaveBeenCalledWith({ action: 'complete', financeReviewStatus: 'approved', note: '' })
})
