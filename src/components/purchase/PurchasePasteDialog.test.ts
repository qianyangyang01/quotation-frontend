// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import PurchasePasteDialog from './PurchasePasteDialog.vue'
import { emptyPurchasePasteRow } from '@/data/purchasePaste'

const request=vi.hoisted(()=>vi.fn().mockResolvedValue([]))
vi.mock('@/services/http',()=>({request}))
let app: App | undefined
afterEach(()=>{app?.unmount();document.body.innerHTML='';vi.clearAllMocks();vi.unstubAllGlobals()})
it('pastes multiline HTML as two products, rejects jagged fallback atomically and submits correct prices', async()=>{
  const host=document.createElement('div');document.body.append(host);app=createApp(PurchasePasteDialog);app.mount(host)
  const rows=[emptyPurchasePasteRow(),emptyPurchasePasteRow()]
  rows.forEach((r,i)=>Object.assign(r,{3:`QA-PASTE-${i+1}`,4:'850',5:'页数118\n尺寸253*250',6:'小熊\n小兔',11:'1',12:i?'27.5':'41.1',17:'6',18:'30'}))
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
  const row=emptyPurchasePasteRow();Object.assign(row,{0:'2026.9.5',3:'QA-SHARE-1',4:'850',11:'1',12:'41.1',17:'6',18:'30',24:'图书'})
  const event=new Event('paste',{bubbles:true,cancelable:true})
  Object.defineProperty(event,'clipboardData',{value:{getData:(type:string)=>type==='text/html'?`<table><tr><td><img src="x"></td><td></td><td></td>${row.map(c=>`<td>${c}</td>`).join('')}</tr></table>`:''}})
  document.querySelector('[data-cell="0:0"]')!.dispatchEvent(event);await nextTick()
  expect(document.querySelector('[role="status"]')!.textContent).toContain('已过滤前3列')
  request.mockRejectedValueOnce(new Error('保存失败'))
  button('保存新增 1 条').click();await nextTick();await nextTick()
  expect(button('一键复制SKU和品类').disabled).toBe(true)
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
