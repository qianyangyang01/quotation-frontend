// @vitest-environment happy-dom
import { createApp, h, nextTick, ref } from 'vue'
import { expect, it } from 'vitest'
import Condition from './QuotationCondition.vue'
import { parseCommissionThreshold, COMMISSION_THRESHOLD_ERROR } from '@/services/quotationCommission'

it('keeps invalid input visible instead of replacing it with a valid default', async () => {
  const threshold = ref('1')
  const host = document.createElement('div')
  const app = createApp({ render: () => h(Condition, {
    commissionThreshold: threshold.value,
    commissionError: parseCommissionThreshold(threshold.value) == null ? COMMISSION_THRESHOLD_ERROR : '',
    'onUpdate:commissionThreshold': value => { threshold.value = value },
    mode: 'single', skuSearch: 'SKU', customerName: '客户', monthlySalesEstimate: '10', attributes: ['普货'], logisticsAttribute: '普货', grades: [{grade:'S'}], grade: 'S', coefficient: 1.2, salesperson: '员工',
  }) })
  app.mount(host)
  const field = host.querySelector<HTMLInputElement>('input[aria-label="佣金阈值"]')!
  expect(field.value).toBe('1')
  for (const value of ['0.95', '', '0', '-1', '1.01', 'bad', '1']) {
    field.value = value; field.dispatchEvent(new Event('input', {bubbles:true})); await nextTick()
    expect(threshold.value).toBe(value)
    expect(field.value).toBe(value)
    expect(field.getAttribute('aria-invalid')).toBe(String(parseCommissionThreshold(value) == null))
  }
  expect(Array.from(host.querySelectorAll('select option')).map(o => o.textContent)).toContain('阶梯价2')
  app.unmount()
})
