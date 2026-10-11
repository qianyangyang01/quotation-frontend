// @vitest-environment happy-dom
import { createApp, h, nextTick, reactive } from 'vue'
import { expect, it, vi } from 'vitest'
import Condition from './QuotationCondition.vue'

it('opens independent batch quoting from the mode selector without changing the ordinary calculation mode', async () => {
  const state=reactive({singleOnly:false}), openBatch=vi.fn(), change=vi.fn(), host=document.createElement('div')
  const app=createApp({render:()=>h(Condition,{...state,commissionThreshold:'1',mode:'single',skuSearch:'SKU',customerName:'客户',monthlySalesEstimate:'10',attributes:['普货'],logisticsAttribute:'普货',grades:[{grade:'S'}],grade:'S',coefficient:1.2,salesperson:'员工',onOpenBatch:openBatch,'onUpdate:mode':change})})
  app.mount(host)
  try {
    const select=host.querySelector<HTMLSelectElement>('[aria-label="报价模式"]')!
    expect([...select.options].map(o=>o.value).slice(0,3)).toEqual(['single','batch-single','bundle'])
    select.value='batch-single';select.dispatchEvent(new Event('change'));await nextTick()
    expect(openBatch).toHaveBeenCalledOnce();expect(change).not.toHaveBeenCalled();expect(select.value).toBe('single')
    select.value='bundle';select.dispatchEvent(new Event('change'));expect(change).toHaveBeenCalledWith('bundle')
    state.singleOnly=true;await nextTick();expect([...select.options].map(o=>o.value)).toEqual(['single'])
  } finally {app.unmount()}
})
