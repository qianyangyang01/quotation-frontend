// @vitest-environment happy-dom
import { createApp, h, reactive, nextTick } from 'vue'
import { expect, it, vi } from 'vitest'
import Condition from './QuotationCondition.vue'
import type { QuotationMode } from '@/data/quotationModes'

it.each(['womens-single', 'womens-bundle'] as const)('restores %s into a normal mode and womens customer selection', async mode => {
  const state = reactive({ mode: mode as QuotationMode, lockMode: false, singleOnly: false })
  const change = vi.fn(), grade = vi.fn(), batch = vi.fn(), host = document.createElement('div')
  const app = createApp({ render: () => h(Condition, { ...state, commissionThreshold: '1', skuSearch: 'SKU', customerName: '客户', monthlySalesEstimate: '10', attributes: ['普货'], logisticsAttribute: '普货', grades: [{grade:'S'},{grade:'NEW'}], grade:'NEW', coefficient:0, salesperson:'员工', 'onUpdate:mode':change, 'onUpdate:grade':grade, onOpenBatch:batch }) })
  app.mount(host)
  try {
    const modeSelect = host.querySelector<HTMLSelectElement>('[aria-label="报价模式"]')!
    const gradeSelect = host.querySelector<HTMLSelectElement>('[aria-label="客户等级"]')!
    expect(modeSelect.value).toBe(mode === 'womens-single' ? 'single' : 'bundle')
    expect(gradeSelect.value).toBe('WOMENS')
    expect([...modeSelect.options].some(o => o.value.startsWith('womens'))).toBe(false)
    expect([...gradeSelect.options].find(o => o.value === 'WOMENS')?.textContent).toBe('女装客户')
    modeSelect.value='batch-single';modeSelect.dispatchEvent(new Event('change'))
    expect(batch).toHaveBeenCalledOnce()
    expect(modeSelect.value).toBe(mode === 'womens-single' ? 'single' : 'bundle')
    gradeSelect.value='NEW';gradeSelect.dispatchEvent(new Event('change'))
    expect(grade).toHaveBeenCalledWith('NEW')
    state.lockMode=true;await nextTick()
    expect(gradeSelect.options[0]!.disabled).toBe(true)
    state.lockMode=false;state.mode='freight-trial';await nextTick()
    expect([...gradeSelect.options].some(o => o.value==='WOMENS')).toBe(false)
    state.mode='single';state.singleOnly=true;await nextTick()
    expect([...gradeSelect.options].some(o => o.value==='WOMENS')).toBe(false)
  } finally { app.unmount() }
})
