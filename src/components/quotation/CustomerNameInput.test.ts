// @vitest-environment happy-dom
import { createApp, h, nextTick, reactive } from 'vue'
import { expect, it } from 'vitest'
import Picker from './CustomerNameInput.vue'
import Conditions from './QuotationCondition.vue'
it('distinguishes choosing a configured client from typing an identical name and supports returning to free text', async () => {
  const host = document.createElement('div'); document.body.append(host)
  const state = reactive({ modelValue: '', selectedId: '', customers: [{ id: 'a', name: '客户甲', feeUsd: 2, enabled: true }, { id: 'b', name: '禁用', feeUsd: 3, enabled: false }] })
  const app = createApp({ render: () => h(Picker, { ...state, 'onUpdate:modelValue': (name: string) => { state.modelValue = name; state.selectedId = '' }, onSelect: (id: string) => { state.selectedId = id; state.modelValue = state.customers.find(row => row.id === id)!.name } }) }); app.mount(host)
  try {
    const input = host.querySelector('input')!
    input.value = '客户甲'; input.dispatchEvent(new Event('input')); await nextTick()
    expect(state.selectedId).toBe(''); expect(host.textContent).not.toContain('禁用')
    input.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter'})); await nextTick()
    expect(state.selectedId).toBe('')
    host.querySelector<HTMLButtonElement>('[role=option]')!.click(); await nextTick()
    expect(state.selectedId).toBe('a'); expect(state.modelValue).toBe('客户甲')
    host.querySelector<HTMLButtonElement>('.manual')!.click(); await nextTick()
    expect(state.selectedId).toBe(''); expect(state.modelValue).toBe('客户甲')
    input.value = ''; input.dispatchEvent(new Event('input')); await nextTick()
    expect(host.querySelector('[role=option]')).not.toBeNull()
  } finally { app.unmount(); host.remove() }
})
it('shows no manual product category control for either single or bundle conditions', () => {
  for (const mode of ['single', 'bundle'] as const) {
    const host = document.createElement('div')
    const app = createApp({ render: () => h(Conditions, { mode, skuSearch: '', customerName: '', monthlySalesEstimate: '10', attributes: ['普货'], logisticsAttribute: '普货', grades: [{ grade: 'S' }], grade: 'S', coefficient: 1.2, salesperson: '测试' }) }); app.mount(host)
    expect(host.querySelector('[data-validation-field=productCategory]')).toBeNull()
    expect(host.querySelector('[role=combobox]')).not.toBeNull()
    app.unmount()
  }
})
