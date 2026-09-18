// @vitest-environment happy-dom
import { createApp, nextTick, ref, computed, h } from 'vue'
import { expect, it } from 'vitest'
import SpecialPackagingInput from './SpecialPackagingInput.vue'
import QuotationWeightTrace from './QuotationWeightTrace.vue'
import { parseSpecialPackagingGrams, SPECIAL_PACKAGING_ERROR, buildQuotationWeightSnapshot } from '@/data/quotationWeightSnapshot'

it('keeps invalid input visible with an inline error and permits clearing to zero', async () => {
  const value=ref('10'), error=computed(()=>parseSpecialPackagingGrams(value.value)===null?SPECIAL_PACKAGING_ERROR:'')
  const host=document.createElement('div')
  const app=createApp({setup:()=>()=>h(SpecialPackagingInput,{modelValue:value.value,error:error.value,'onUpdate:modelValue':v=>value.value=v})})
  app.mount(host)
  try {
    const field=host.querySelector('input')!
    for (const text of ['-1','1.2','bad','100001','']) {
      field.value=text;field.dispatchEvent(new Event('input'));await nextTick()
      expect(value.value).toBe(text)
      expect(field.getAttribute('aria-invalid')).toBe(text===''?'false':'true')
      expect(!!host.querySelector('[role=alert]')).toBe(text!=='')
    }
    expect(host.textContent).toContain('整票只加一次')
  } finally { app.unmount() }
})
it.each([true,false])('shows the saved basis only when a snapshot exists: %s', present => {
  const host=document.createElement('div')
  const snapshot=buildQuotationWeightSnapshot([{sku:'A',quantityPerSet:1,baseWeightKg:.14}],10,[1,2,5])
  const app=createApp(QuotationWeightTrace,{snapshot:present?snapshot:undefined})
  app.mount(host)
  try {
    if(present) {
      expect([...host.querySelectorAll('tbody tr')].map(row=>[...row.querySelectorAll('td')].map(cell=>cell.textContent)))
        .toEqual([['1件','140','3','10','153'],['2件','280','6','10','296'],['5件','700','15','10','725']])
    } else expect(host.textContent).toContain('不按当前规则回算')
  } finally { app.unmount() }
})
