// @vitest-environment happy-dom
import { createApp, h, nextTick, reactive, ref } from 'vue'
import { expect, it } from 'vitest'
import Condition from './QuotationCondition.vue'
import { parseCommissionThreshold, COMMISSION_THRESHOLD_ERROR } from '@/services/quotationCommission'

it('distinguishes pending and failed finance reads from genuinely unauthorized attributes', async () => {
  const state = reactive({ financePending: true, financeError: '', attributes: [] as string[] })
  const host = document.createElement('div')
  const app = createApp({ render: () => h(Condition, { ...state, commissionThreshold:'1', mode:'single', skuSearch:'SKU',
    customerName:'客户', monthlySalesEstimate:'10', logisticsAttribute:'普货', grades:[{grade:'S'}], grade:'S', coefficient:1.2, salesperson:'员工' }) })
  app.mount(host)
  const field = host.querySelector<HTMLSelectElement>('.logistics-field select')!
  expect(field.disabled).toBe(true)
  expect(field.textContent).toContain('财务设置正在读取')
  expect(field.textContent).not.toContain('授权')
  state.financeError = '网络超时'; await nextTick()
  expect(field.textContent).toContain('读取失败')
  state.financeError = ''; state.financePending = false; await nextTick()
  expect(field.textContent).toContain('暂无财务授权')
  state.attributes = ['普货']; await nextTick()
  expect(field.value).toBe('普货')
  expect(field.disabled).toBe(false)
  app.unmount()
})

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
