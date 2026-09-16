// @vitest-environment happy-dom
import { createApp, h, nextTick, reactive } from 'vue'
import { expect, it } from 'vitest'
import Picker from './CustomerNameInput.vue'
import Conditions from './QuotationCondition.vue'
it('browses all enabled zero-fee customers without filtering by a restored free-text name', async () => {
  const host = document.createElement('div'); document.body.append(host)
  const state = reactive({ modelValue: '测试1', selectedId: '', customers: [{ id: 'bk', name: 'BK', feeUsd: 0, enabled: true }, { id: 'ck', name: 'CK', feeUsd: 0, enabled: true }, { id: 'off', name: '停用', feeUsd: 1, enabled: false }] })
  const app = createApp({ render: () => h(Picker, { ...state, 'onUpdate:modelValue': (name: string) => { state.modelValue = name; state.selectedId = '' }, onSelect: (id: string) => { state.selectedId = id; state.modelValue = state.customers.find(row => row.id === id)!.name } }) }); app.mount(host)
  const options = () => Array.from(host.querySelectorAll('[role=option]')).map(el => el.textContent)
  try {
    const toggle = host.querySelector<HTMLButtonElement>('[aria-label="展开客户列表"]')!
    toggle.click(); await nextTick()
    expect(options()).toEqual(['BK$0.00/单', 'CK$0.00/单'])
    expect(state.modelValue).toBe('测试1'); expect(state.selectedId).toBe('')
    const input = host.querySelector('input')!
    input.value = ' c '; input.dispatchEvent(new Event('input')); await nextTick()
    expect(options()).toEqual(['CK$0.00/单'])
    input.value = '没有匹配'; input.dispatchEvent(new Event('input')); await nextTick()
    expect(options()).toEqual([])
    toggle.click(); await nextTick()
    expect(options()).toEqual(['BK$0.00/单', 'CK$0.00/单'])
    expect(state.modelValue).toBe('没有匹配'); expect(state.selectedId).toBe('')
    host.querySelector<HTMLButtonElement>('[role=option]')!.click(); await nextTick()
    expect(state.modelValue).toBe('BK'); expect(state.selectedId).toBe('bk')
    host.querySelector<HTMLButtonElement>('.manual')!.click(); await nextTick()
    expect(state.selectedId).toBe(''); expect(state.modelValue).toBe('BK')
    toggle.click(); await nextTick()
    expect(options()).toHaveLength(2)
  } finally { app.unmount(); host.remove() }
})
it('opens the complete list with the keyboard and clears stale searches after closing', async () => {
  const host = document.createElement('div'); document.body.append(host)
  const state = reactive({ modelValue: '旧客户', selectedId: '', customers: [{ id: 'bk', name: 'BK', feeUsd: 0, enabled: true }, { id: 'ck', name: 'CK', feeUsd: 2, enabled: true }] })
  const app = createApp({ render: () => h(Picker, { ...state, 'onUpdate:modelValue': (name: string) => { state.modelValue = name; state.selectedId = '' }, onSelect: (id: string) => { state.selectedId = id; state.modelValue = state.customers.find(row => row.id === id)!.name } }) }); app.mount(host)
  try {
    const input = host.querySelector('input')!
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown' })); await nextTick()
    expect(host.querySelectorAll('[role=option]')).toHaveLength(2)
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown' })); await nextTick()
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' })); await nextTick()
    expect(state.selectedId).toBe('ck')
    input.value = '不匹配'; input.dispatchEvent(new Event('input')); await nextTick()
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' })); await nextTick()
    expect(state.selectedId).toBe('')
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown' })); await nextTick()
    expect(host.querySelectorAll('[role=option]')).toHaveLength(2)
    expect(state.modelValue).toBe('不匹配')
  } finally { app.unmount(); host.remove() }
})
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
