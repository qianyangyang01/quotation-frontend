// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import DateRangePicker from './DateRangePicker.vue'
let app:App
async function flush(){await nextTick();await nextTick()}
async function mount(from='',to=''){
  vi.useFakeTimers();vi.setSystemTime(new Date('2026-11-09T03:00:00Z'))
  const changed=vi.fn(),host=document.createElement('div');document.body.append(host)
  app=createApp(DateRangePicker,{from,to,onChange:changed});app.mount(host)
  document.querySelector<HTMLButtonElement>('.range-trigger')!.click();await flush();return changed
}
async function click(text:string){Array.from(document.querySelectorAll<HTMLButtonElement>('.range-panel button')).find(b=>b.textContent===text)!.click();await flush()}
async function day(value:string){document.querySelector<HTMLButtonElement>(`button[aria-label="${value}"]`)!.click();await flush()}
afterEach(()=>{app?.unmount();document.body.innerHTML='';vi.useRealTimers()})
it('selects a cross-month range and only commits on confirmation',async()=>{
  const changed=await mount();await day('2026-11-28');await day('2026-12-03')
  expect(changed).not.toHaveBeenCalled();expect(document.querySelectorAll('.calendar-days .between').length).toBe(6)
  await click('确定筛选');expect(changed).toHaveBeenCalledWith({from:'2026-11-28',to:'2026-12-03'})
  expect(document.querySelector('.range-panel')).toBeNull();expect(document.activeElement).toBe(document.querySelector('.range-trigger'))
})
it('supports same-day selection and orders reversed calendar clicks',async()=>{
  const changed=await mount();await day('2026-11-09');await day('2026-11-09');await click('确定筛选')
  expect(changed).toHaveBeenLastCalledWith({from:'2026-11-09',to:'2026-11-09'})
  document.querySelector<HTMLButtonElement>('.range-trigger')!.click();await flush()
  await day('2026-11-20');await day('2026-11-04');await click('确定筛选')
  expect(changed).toHaveBeenLastCalledWith({from:'2026-11-04',to:'2026-11-20'})
})
it.each([['今天','2026-11-09','2026-11-09'],['昨天','2026-11-08','2026-11-08'],['近7天','2026-11-03','2026-11-09'],['近30天','2026-10-11','2026-11-09'],['本月','2026-11-01','2026-11-09'],['上月','2026-10-01','2026-10-31'],['不限日期','','']])('applies shortcut %s',async(label,from,to)=>{const changed=await mount();await click(label);expect(changed).toHaveBeenCalledWith({from,to})})
it('discards draft changes when cancelled or dismissed with Escape',async()=>{
  const changed=await mount('2026-11-01','2026-11-09');await day('2026-11-20');await click('取消');expect(changed).not.toHaveBeenCalled()
  document.querySelector<HTMLButtonElement>('.range-trigger')!.click();await flush()
  expect(document.querySelector<HTMLInputElement>('[aria-label="报价开始日期"]')!.value).toBe('2026-11-01')
  document.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape'}));await flush();expect(document.querySelector('.range-panel')).toBeNull()
})
it('validates manually typed dates without clearing the applied filter',async()=>{
  const changed=await mount('2026-11-01','2026-11-09');const input=document.querySelector<HTMLInputElement>('[aria-label="报价开始日期"]')!
  input.value='2026-02-30';input.dispatchEvent(new Event('input',{bubbles:true}));await flush()
  expect(document.querySelector<HTMLButtonElement>('.range-apply')!.disabled).toBe(true)
  expect(document.querySelector('[role="alert"]')?.textContent).toContain('有效日期');expect(changed).not.toHaveBeenCalled()
  input.value='';input.dispatchEvent(new Event('input',{bubbles:true}));await flush();await click('确定筛选')
  expect(changed).toHaveBeenCalledWith({from:'',to:'2026-11-09'})
})
it('navigates years and leap months directly',async()=>{
  await mount();const year=document.querySelector<HTMLSelectElement>('[aria-label="选择年份"]')!,month=document.querySelector<HTMLSelectElement>('[aria-label="选择月份"]')!
  year.value='2024';year.dispatchEvent(new Event('change'));await flush();month.value='1';month.dispatchEvent(new Event('change'));await flush()
  expect(document.querySelector('button[aria-label="2024-02-29"]')).not.toBeNull()
  expect(document.querySelector('button[aria-label="2024-03-01"]')).not.toBeNull()
})
