// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import PurchasePasteDialog from './PurchasePasteDialog.vue'
import { emptyPurchasePasteRow } from '@/data/purchasePaste'

const request=vi.hoisted(()=>vi.fn().mockResolvedValue([]))
vi.mock('@/services/http',()=>({request}))
let app: App | undefined
afterEach(()=>{app?.unmount();document.body.innerHTML='';vi.clearAllMocks()})
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
