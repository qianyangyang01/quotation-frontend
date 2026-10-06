// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, reactive, type App } from 'vue'
import Picker from './CustomerNameInput.vue'
import { authState } from '@/data/authStore'
import { resolveCustomerOperation } from '@/data/customerOperationFees'
const api=vi.hoisted(()=>({list:vi.fn(),add:vi.fn(),rename:vi.fn(),remove:vi.fn(),use:vi.fn()}))
vi.mock('@/data/personalQuotationCustomers',()=>({listPersonalCustomers:api.list,addPersonalCustomer:api.add,renamePersonalCustomer:api.rename,removePersonalCustomer:api.remove,usePersonalCustomer:api.use}))
let app:App
const personal={id:'personal-1',name:'枕头客户',_version:0,lastUsedAt:null}
const company={id:'company-1',name:'枕头客户',feeUsd:2,enabled:true}
const flush=async()=>{for(let i=0;i<8;i++){await nextTick();await Promise.resolve()}}
const button=(label:string)=>[...document.querySelectorAll<HTMLButtonElement>('button')].find(row=>row.textContent?.trim()===label)!
const fill=async(selector:string,value:string)=>{const input=document.querySelector<HTMLInputElement>(selector)!;input.value=value;input.dispatchEvent(new Event('input'));await flush()}
async function mount(){
  const state=reactive({modelValue:company.name,selectedId:company.id,customers:[company]})
  const host=document.createElement('div');document.body.append(host)
  app=createApp({render:()=>h(Picker,{...state,'onUpdate:modelValue':(value:string)=>{state.modelValue=value;state.selectedId=''},onSelect:(id:string)=>{state.selectedId=id;state.modelValue=company.name}})});app.mount(host);await flush();return state
}
async function manage(){document.querySelector<HTMLButtonElement>('[aria-label="展开客户列表"]')!.click();await flush();document.querySelector<HTMLButtonElement>('.manage-personal')!.click();await flush()}
beforeEach(()=>{
  authState.current={id:'me',account:'ME',name:'ME',role:'employee',status:'enabled',mustChangePassword:false,passwordUpdatedAt:''}
  Object.defineProperty(HTMLDialogElement.prototype,'showModal',{configurable:true,value:function(){this.open=true}})
  Object.defineProperty(HTMLDialogElement.prototype,'close',{configurable:true,value:function(){this.open=false}})
  api.list.mockResolvedValue([personal]);api.use.mockResolvedValue({...personal,_version:1,lastUsedAt:'2026-10-06T00:00:00Z'})
})
afterEach(()=>{app?.unmount();document.body.innerHTML='';authState.current=null;vi.resetAllMocks()})
it('keeps same-named personal and company choices distinct and clears the company fee only on selection',async()=>{
  const state=await mount();document.querySelector<HTMLButtonElement>('[aria-label="展开客户列表"]')!.click();await flush()
  expect(state.selectedId).toBe(company.id)
  const options=document.querySelectorAll<HTMLButtonElement>('[role=option]');expect(options).toHaveLength(2)
  expect(options[0]!.textContent).toContain('个人 · 仅名称');expect(options[1]!.textContent).toContain('公司计费')
  options[0]!.click();await flush()
  expect(state.modelValue).toBe(company.name);expect(state.selectedId).toBe('')
  expect(resolveCustomerOperation({customers:[company]},state.selectedId,state.modelValue).feeUsd).toBe(0)
  expect(api.use).toHaveBeenCalledWith(personal)
  expect(document.querySelector('.source')?.textContent).toBe('个人')
})
it('combines adding and managing without changing the current quotation until selecting',async()=>{
  const state=await mount();await manage();expect(document.querySelector('dialog')?.textContent).toContain('我的常用客户')
  api.add.mockResolvedValue({...personal,id:'new',name:'新客户'})
  await fill('[aria-label="新增个人客户名称"]','新客户');button('＋ 添加').click();await flush()
  expect(api.add).toHaveBeenCalledWith('新客户',expect.stringContaining('personal-customer:'))
  expect(document.querySelector('dialog')).not.toBeNull();expect(state.selectedId).toBe(company.id)
  const row=[...document.querySelectorAll('tbody tr')].find(row=>row.textContent?.includes('新客户'))!
  row.querySelector<HTMLButtonElement>('button')!.click();await flush()
  expect(state.modelValue).toBe('新客户');expect(state.selectedId).toBe('');expect(document.querySelector('dialog')).toBeNull()
})
it('renames the list with a version without rewriting the current quotation',async()=>{
  const state=await mount();await manage();button('修改名称').click();await flush()
  api.rename.mockResolvedValue({...personal,name:'改名',_version:1})
  await fill('#personal-customer-name','改名');button('保存').click();await flush()
  expect(api.rename).toHaveBeenCalledWith(personal,'改名');expect(document.querySelector('tbody')?.textContent).toContain('改名')
  expect(state.modelValue).toBe(company.name);expect(state.selectedId).toBe(company.id)
})
it('confirms removal and retains the current input, and keeps failed edits recoverable',async()=>{
  const state=await mount();await manage();button('移出').click();await flush()
  expect(api.remove).not.toHaveBeenCalled();expect(document.querySelector('dialog')?.textContent).toContain('不会删除或修改历史报价')
  api.remove.mockRejectedValue(new Error('已在其他页面更新'));button('确认移出').click();await flush()
  expect(document.querySelector('[role=alert]')?.textContent).toContain('已在其他页面更新');expect(button('确认移出')).toBeDefined()
  api.list.mockResolvedValue([{...personal,_version:2}]);button('刷新名单').click();await flush()
  api.remove.mockResolvedValue(undefined);button('确认移出').click();await flush()
  expect(api.remove).toHaveBeenLastCalledWith({...personal,_version:2})
  expect(document.querySelector('tbody')?.textContent).not.toContain(personal.name);expect(state.modelValue).toBe(company.name)
})
it('refreshes the edit version after a conflict without discarding the typed name',async()=>{
  await mount();await manage();button('修改名称').click();await flush()
  await fill('#personal-customer-name','我的新名称');api.rename.mockRejectedValue(new Error('版本已变化'))
  button('保存').click();await flush()
  api.list.mockResolvedValue([{...personal,name:'其他页面的名称',_version:3}]);button('刷新名单').click();await flush()
  expect(document.querySelector<HTMLInputElement>('#personal-customer-name')?.value).toBe('我的新名称')
  api.rename.mockResolvedValue({...personal,name:'我的新名称',_version:4});button('保存').click();await flush()
  expect(api.rename).toHaveBeenLastCalledWith({...personal,name:'其他页面的名称',_version:3},'我的新名称')
})
it('handles duplicates without submitting twice and preserves input after failed creation',async()=>{
  await mount();await manage();await fill('[aria-label="新增个人客户名称"]',personal.name)
  expect(button('＋ 添加').disabled).toBe(true);expect(button('选用已有客户')).toBeDefined()
  await fill('[aria-label="新增个人客户名称"]','客户乙');api.add.mockRejectedValue(new Error('网络失败'))
  button('＋ 添加').click();await flush();const key=api.add.mock.calls[0]![1]
  expect(document.querySelector<HTMLInputElement>('[aria-label="新增个人客户名称"]')?.value).toBe('客户乙')
  button('＋ 添加').click();await flush();expect(api.add.mock.calls[1]![1]).toBe(key)
})
it('discards a previous account response and closes personal management on account switch',async()=>{
  await mount();let resolve!:(value:typeof personal[])=>void
  api.list.mockImplementation(()=>new Promise(r=>{resolve=r}));document.querySelector<HTMLButtonElement>('[aria-label="展开客户列表"]')!.click();await flush()
  authState.current={...authState.current!,id:'other',account:'OTHER'};await flush();resolve([personal]);await flush()
  expect(document.querySelector('[role=listbox]')).toBeNull()
  api.list.mockResolvedValue([]);await manage();expect(document.querySelector('tbody')?.textContent).not.toContain(personal.name)
  authState.current={...authState.current!,id:'third',account:'THIRD'};await flush();expect(document.querySelector('dialog')).toBeNull()
})
it('allows company selection and manual input even when personal list loading fails',async()=>{
  const state=await mount();api.list.mockRejectedValue(new Error('名单加载失败'));document.querySelector<HTMLButtonElement>('[aria-label="展开客户列表"]')!.click();await flush()
  expect(document.querySelector('[role=alert]')?.textContent).toContain('名单加载失败')
  document.querySelector<HTMLButtonElement>('[role=option]')!.click();await flush();expect(state.selectedId).toBe(company.id)
  await fill('[role=combobox]','临时客户');expect(state.selectedId).toBe('')
})
