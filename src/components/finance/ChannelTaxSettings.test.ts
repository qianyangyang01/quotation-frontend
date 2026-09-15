// @vitest-environment happy-dom
import { createApp, h, nextTick, ref } from 'vue'
import { expect, it, vi } from 'vitest'
vi.mock('@/data/financeChannelPolicies',()=>({channelsAvailableForCountry:(country:string)=>Array.from({length:25},(_,i)=>({key:`${i+1}::燕文::C-${i}`,carrier:'燕文',channel:`${country}渠道${i+1}`,ruleName:'渠道'}))}))
import Component from './ChannelTaxSettings.vue'
import { normalizeFinanceTaxSettings } from '@/data/financeTaxSettings'
it('shows imported money to two decimals without changing untouched values, and saves edited cents', async()=>{
  const original = 3.58208955223881
  const value=ref(normalizeFinanceTaxSettings({countries:[{country:'美国',selected:true,enabled:true,fixedFeeUsd:0,sortOrder:1,channelRules:[{key:'1::燕文::C-0',mode:'fixed-order',amount:original,perKg:0,currency:'USD'}]}]}))
  const host=document.createElement('div');document.body.append(host)
  const app=createApp({render:()=>h(Component,{modelValue:value.value,'onUpdate:modelValue':v=>value.value=v,exchange:{usdCny:6.7},saving:false})});app.mount(host)
  const rule=()=>value.value.countries.find(row=>row.country==='美国')!.channelRules![0]!
  const edit=async()=>{host.querySelector<HTMLButtonElement>('.text-button')!.click();await nextTick()}
  const apply=async()=>{[...host.querySelectorAll<HTMLButtonElement>('button')].find(b=>b.textContent==='应用到所选渠道')!.click();await nextTick()}
  try {
    await edit()
    expect(host.querySelector<HTMLInputElement>('[aria-label=原币金额]')!.value).toBe('3.58')
    await apply(); expect(rule().amount).toBe(original)
    await edit()
    const input=host.querySelector<HTMLInputElement>('[aria-label=原币金额]')!
    input.value='3.596';input.dispatchEvent(new Event('input'));input.dispatchEvent(new Event('blur'));await nextTick()
    expect(input.value).toBe('3.60')
    await apply();expect(rule().amount).toBe(3.6)
    await edit();expect(host.querySelector<HTMLInputElement>('[aria-label=原币金额]')!.value).toBe('3.60')
  } finally {app.unmount();host.remove()}
})
it('selects every page, applies USD 0.3 per order, preserves hidden rules and isolates countries', async()=>{
  const value=ref(normalizeFinanceTaxSettings({countries:['美国','新西兰'].map(country=>({country,selected:true,enabled:true,fixedFeeUsd:0,sortOrder:1,channelRules:[{key:'99::停用::C-hidden',mode:'no-tax',amount:0,perKg:0,currency:'USD'}]}))}))
  const host=document.createElement('div');document.body.append(host)
  const app=createApp({render:()=>h(Component,{modelValue:value.value,'onUpdate:modelValue':v=>value.value=v,exchange:{usdCny:6.7,eurUsd:1.16},saving:false})});app.mount(host)
  const click=async(text:string)=>{[...host.querySelectorAll<HTMLButtonElement>('button')].find(b=>b.textContent?.trim()===text)!.click();await nextTick()}
  try {
    await click('美国'); await click('全选全部渠道（25）'); expect(host.textContent).toContain('已选 25 个')
    await click('批量设置'); expect((host.querySelector('[aria-label=费用币种]') as HTMLSelectElement).value).toBe('USD')
    await click('应用到所选渠道'); const usa=value.value.countries.find(row=>row.country==='美国')!
    expect(usa.channelRules).toHaveLength(26); expect(usa.channelRules?.filter(row=>row.amount===0.3)).toHaveLength(25)
    expect(value.value.countries.find(row=>row.country==='新西兰')!.channelRules).toHaveLength(1)
    await click('新西兰'); expect(host.textContent).toContain('已选 0 个'); expect(host.querySelector('.editor')).toBeNull()
    await click('全选全部渠道（25）'); await click('批量设置')
    const amount=host.querySelector<HTMLInputElement>('[aria-label=原币金额]')!;amount.value='-1';amount.dispatchEvent(new Event('input'));await nextTick();await click('应用到所选渠道')
    expect(host.querySelector('[role=alert]')?.textContent).toContain('有效数字'); expect(value.value.countries.find(row=>row.country==='新西兰')!.channelRules).toHaveLength(1)
  } finally { app.unmount();host.remove() }
})
