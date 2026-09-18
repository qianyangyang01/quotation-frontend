// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import PurchasePasteDialog from './PurchasePasteDialog.vue'
import { emptyPurchasePasteRow } from '@/data/purchasePaste'

const request=vi.hoisted(()=>vi.fn().mockResolvedValue([]))
vi.mock('@/services/http',()=>({request}))
let app: App | undefined
it('blocks a missing tax point until the user explicitly enters zero', async()=>{
  const host=document.createElement('div');document.body.append(host);app=createApp(PurchasePasteDialog);app.mount(host)
  const row=emptyPurchasePasteRow();Object.assign(row,{3:'P-TAX',4:'50',11:'1',12:'9',17:'0',18:'0'})
  const event=new Event('paste',{bubbles:true,cancelable:true})
  Object.defineProperty(event,'clipboardData',{value:{getData:(type:string)=>type==='text/html'?'':row.join('\t')}})
  document.querySelector('[data-cell="0:0"]')!.dispatchEvent(event);await nextTick()
  const save=Array.from(document.querySelectorAll('button')).find(b=>b.textContent?.startsWith('保存新增'))!
  expect(save.disabled).toBe(true)
  save.click();expect(request).not.toHaveBeenCalled()
  const tax=document.querySelector('[data-cell="0:22"]') as HTMLInputElement
  tax.value='0%';tax.dispatchEvent(new Event('input',{bubbles:true}));await nextTick()
  expect(save.disabled).toBe(false)
  save.click();await nextTick();await nextTick()
  expect(JSON.parse(request.mock.calls[0]![1].body)[0].taxPoint).toBe(0)
})
afterEach(()=>{app?.unmount();document.body.innerHTML='';vi.clearAllMocks();vi.unstubAllGlobals()})
it('pastes multiline HTML as two products, rejects jagged fallback atomically and submits correct prices', async()=>{
  const host=document.createElement('div');document.body.append(host);app=createApp(PurchasePasteDialog);app.mount(host)
  const rows=[emptyPurchasePasteRow(),emptyPurchasePasteRow()]
  rows.forEach((r,i)=>Object.assign(r,{3:`QA-PASTE-${i+1}`,4:'850',5:'页数118\n尺寸253*250',6:'小熊\n小兔',11:'1',12:i?'27.5':'41.1',17:'6',18:'30',22:'8%'}))
  function paste(text: string,html='') {
    const event=new Event('paste',{bubbles:true,cancelable:true});Object.defineProperty(event,'clipboardData',{value:{getData:(type:string)=>type==='text/html'?html:text}})
    document.querySelector('[data-cell="0:0"]')!.dispatchEvent(event)
  }
  const html=`<table>${rows.map(r=>`<tr>${r.map(c=>`<td>${c.replace(/\n/g,'<br>')}</td>`).join('')}</tr>`).join('')}</table>`
  paste(rows.map(r=>r.join('\t')).join('\n'),html);await nextTick()
  const save=Array.from(document.querySelectorAll('button')).find(b=>b.textContent==='保存新增 2 条')!
  expect(save.disabled).toBe(false)
  expect((document.querySelector('[data-cell="1:12"]') as HTMLInputElement).value).toBe('27.5')
  expect(document.querySelector('[role="status"]')!.textContent).toContain('已粘贴2行')
  paste('不可确定\t内容\n第二段');await nextTick()
  expect(document.querySelector('[role="status"]')!.textContent).toContain('本次未写入')
  expect((document.querySelector('[data-cell="1:12"]') as HTMLInputElement).value).toBe('27.5')
  save.click();await nextTick();await nextTick()
  expect(request).toHaveBeenCalledTimes(1)
  const saved=JSON.parse(request.mock.calls[0]![1].body)
  expect(saved).toHaveLength(2)
  expect(saved[0]).toMatchObject({sku:'QA-PASTE-1',size:'页数118\n尺寸253*250',color:'小熊\n小兔',purchasePriceCny:41.1,singleFreightCny:6,freight10Cny:30})
  expect(saved[1].purchasePriceCny).toBe(27.5)
})

it('keeps successfully saved SKU/category available for copying and supports clipboard denial', async()=>{
  const writeText=vi.fn().mockResolvedValue(undefined)
  vi.stubGlobal('navigator',{clipboard:{writeText}})
  const host=document.createElement('div');document.body.append(host);app=createApp(PurchasePasteDialog);app.mount(host)
  const button=(name:string)=>Array.from(document.querySelectorAll('button')).find(b=>b.textContent===name)!
  expect(button('一键复制SKU和品类').disabled).toBe(true)
  const row=emptyPurchasePasteRow();Object.assign(row,{0:'2026.9.5',3:'QA-SHARE-1',4:'850',11:'1',12:'41.1',17:'6',18:'30',22:'8%',24:'图书'})
  const event=new Event('paste',{bubbles:true,cancelable:true})
  Object.defineProperty(event,'clipboardData',{value:{getData:(type:string)=>type==='text/html'?`<table><tr><td><img src="x"></td><td></td><td></td>${row.map(c=>`<td>${c}</td>`).join('')}</tr></table>`:''}})
  document.querySelector('[data-cell="0:0"]')!.dispatchEvent(event);await nextTick()
  expect(document.querySelector('[role="status"]')!.textContent).toContain('已过滤前3列')
  request.mockRejectedValueOnce(new Error('保存失败'))
  button('保存新增 1 条').click();await nextTick();await nextTick()
  expect(button('一键复制SKU和品类').disabled).toBe(true)
  request.mockResolvedValueOnce([{sku:'QA-SHARE-1',category:'图书'}])
  button('保存新增 1 条').click();await nextTick();await nextTick()
  expect(document.querySelector('[role="dialog"]')).not.toBeNull()
  expect(button('一键复制SKU和品类').disabled).toBe(false)
  expect(button('保存新增 0 条').disabled).toBe(true)
  button('一键复制SKU和品类').click();await nextTick();await nextTick()
  expect(writeText).toHaveBeenCalledWith('QA-SHARE-1\t图书')
  writeText.mockRejectedValueOnce(new Error('denied'))
  button('一键复制SKU和品类').click();await nextTick();await nextTick()
  expect((document.querySelector('textarea') as HTMLTextAreaElement).value).toBe('QA-SHARE-1\t图书')
  expect(document.querySelector('[role="status"]')!.textContent).toContain('Ctrl + C')
})


it('counts batch and database duplicates and copies only returned additions', async()=>{
  const writeText=vi.fn().mockResolvedValue(undefined);vi.stubGlobal('navigator',{clipboard:{writeText}})
  const host=document.createElement('div');document.body.append(host);app=createApp(PurchasePasteDialog);app.mount(host)
  const button=(name:string)=>Array.from(document.querySelectorAll('button')).find(b=>b.textContent===name)!
  const paste=async()=>{
    const rows=['P-NEW','P-OLD','p-new'].map(sku=>{const row=emptyPurchasePasteRow();Object.assign(row,{3:sku,4:'50',11:'1',12:'9',17:'0',18:'0',22:'0%'});return row.join('\t')})
    const event=new Event('paste',{bubbles:true,cancelable:true});Object.defineProperty(event,'clipboardData',{value:{getData:(type:string)=>type==='text/html'?'':rows.join('\r\n')}})
    document.querySelector('[data-cell="0:0"]')!.dispatchEvent(event);await nextTick()
  }
  await paste();request.mockResolvedValueOnce([{sku:'P-NEW',category:'图书'}]);button('保存新增 2 条').click();await nextTick();await nextTick()
  expect(JSON.parse(request.mock.calls[0]![1].body)).toHaveLength(2)
  expect(document.querySelector('[role="status"]')!.textContent).toContain('已成功新增1条，自动跳过2条')
  expect(document.querySelector('[role="status"]')!.textContent).toContain('P-OLD')
  button('一键复制SKU和品类').click();await nextTick();await nextTick();expect(writeText).toHaveBeenCalledWith('P-NEW\t图书')
  await paste();request.mockResolvedValueOnce([]);button('保存新增 2 条').click();await nextTick();await nextTick()
  expect(document.querySelector('[role="status"]')!.textContent).toContain('已成功新增0条，自动跳过3条')
  expect(button('一键复制SKU和品类').disabled).toBe(true)
})
