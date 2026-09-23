// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import PurchaseHistoryDialog from './PurchaseHistoryDialog.vue'
import { historyValue, historyImage, type PurchaseHistoryPage } from '@/services/purchaseHistory'

const mocks=vi.hoisted(()=>({ load:vi.fn() }))
vi.mock('@/services/purchaseHistory',async original=>({...await original<object>(),loadPurchaseHistory:mocks.load}))
let app:App
async function flush(){await Promise.resolve();await nextTick();await Promise.resolve();await nextTick()}
async function mount(){const host=document.createElement('div');document.body.append(host);app=createApp(PurchaseHistoryDialog,{sku:'BK2601977'});app.mount(host);await flush()}
const result=():PurchaseHistoryPage=>({page:0,size:10,total:11,totalPages:2,items:[{id:'1',createdAt:'2026-09-23T03:20:30Z',actorAccount:'HUANG',actorName:'小黄',sku:'BK2601977',operation:'修改资料',changes:[{field:'purchasePriceCny',label:'基准采购单价(CNY/件)',before:12,after:15},{field:'notes',label:'备注',before:'旧备注',after:null}]}]})
beforeEach(()=>mocks.load.mockResolvedValue(result()))
afterEach(()=>{app?.unmount();document.body.innerHTML='';vi.clearAllMocks()})

it('shows actual operator, Beijing timestamp and both changed values with pagination',async()=>{
  await mount()
  expect(mocks.load).toHaveBeenCalledWith('BK2601977',0)
  expect(document.body.textContent).toContain('小黄（HUANG）')
  expect(document.querySelector('time')?.textContent).toContain('11:20:30')
  const rows=document.querySelectorAll('tbody tr')
  expect(rows[0]?.textContent).toContain('1215')
  expect(rows[1]?.textContent).toContain('旧备注暂无数据')
  expect(document.body.textContent).toContain('更早的修改内容无法追溯')
  mocks.load.mockResolvedValueOnce({...result(),page:1,items:[]})
  Array.from(document.querySelectorAll('button')).find(b=>b.textContent==='下一页')!.click();await flush()
  expect(mocks.load).toHaveBeenLastCalledWith('BK2601977',1)
})

it('shows retryable failures separately from empty history',async()=>{
  mocks.load.mockRejectedValueOnce(new Error('没有访问权限'))
  await mount()
  expect(document.querySelector('[role=alert]')?.textContent).toContain('没有访问权限')
  expect(document.body.textContent).not.toContain('暂无修改记录')
  mocks.load.mockResolvedValueOnce({...result(),items:[],total:0,totalPages:0})
  Array.from(document.querySelectorAll('button')).find(b=>b.textContent==='重试')!.click();await flush()
  expect(document.body.textContent).toContain('暂无修改记录')
})

it('formats zero, percentages and image values without treating user text as markup',async()=>{
  expect(historyValue('purchasePriceCny',0)).toBe('0')
  expect(historyValue('taxPoint',.08)).toBe('8%')
  expect(historyImage('productImage','javascript:alert(1)')).toBe('')
  expect(historyImage('productImage','/api/v1/assets/aaaa-bbbb')).toBe('/api/v1/assets/aaaa-bbbb')
  const data=result();data.items[0]!.changes=[{field:'notes',label:'备注',before:null,after:'<script>alert(1)</script>'}]
  mocks.load.mockResolvedValueOnce(data);await mount()
  expect(document.querySelector('tbody script')).toBeNull()
  expect(document.querySelector('tbody')?.textContent).toContain('<script>alert(1)</script>')
})
