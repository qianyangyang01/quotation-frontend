// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import FobPasteDialog from './FobPasteDialog.vue'
import * as service from '@/services/fobPurchase'
vi.mock('@/services/fobPurchase', () => ({ previewFobPaste: vi.fn(), confirmFobPaste: vi.fn(), loadFobRecord: vi.fn(), loadFobHistory: vi.fn() }))
let app: App
const tick = async () => { await nextTick(); await Promise.resolve(); await nextTick() }
function mount() { const root = document.createElement('div'); document.body.appendChild(root); const saved = vi.fn(); app = createApp(FobPasteDialog,{onSaved:saved}); app.mount(root); return saved }
function button(text: string) { return Array.from(document.querySelectorAll('button')).find(b=>b.textContent === text)! }
async function paste() { const event = new Event('paste',{bubbles:true,cancelable:true}); Object.defineProperty(event,'clipboardData',{value:{getData:(type:string)=>type==='text/plain'?'SKU\t起订量\t单价\t运费原文\r\nPF2600053\t1\t16\t包邮\r\nPF2600054\t1\t30.9\t100件149':''}}); document.querySelector('.paste-target')!.dispatchEvent(event); await tick() }
function preview(canSave=true): service.FobPreview { return { rows:[{sourceRow:1,sku:'PF2600053',action:'update',expected:{sku:'PF2600053',version:1,updatedAt:'2026-10-08T00:00:00Z'},changes:[{field:'priceRaw',label:'采购价格原文',before:'15',after:'16'}],issues:canSave?[]:['采购阶梯重叠'],notices:[],effective:{sku:'PF2600053'}}],skipped:[],canSave,digest:'preview-digest'} }
afterEach(()=>{app?.unmount();document.body.innerHTML='';vi.resetAllMocks()})
it('batches pasted rows and only writes after explicit preview confirmation',async()=>{
  const saved=mount(); await paste(); expect(service.confirmFobPaste).not.toHaveBeenCalled()
  vi.mocked(service.previewFobPaste).mockResolvedValueOnce(preview());button('预览识别与变更').click();await tick()
  expect(vi.mocked(service.previewFobPaste).mock.calls[0]![0]).toHaveLength(2)
  expect(document.body.textContent).toContain('15');expect(document.body.textContent).toContain('16')
  vi.mocked(service.confirmFobPaste).mockResolvedValueOnce({added:1,updated:1,unchanged:0,skipped:0});button('确认全部保存').click();await tick()
  expect(service.confirmFobPaste).toHaveBeenCalledWith(expect.arrayContaining([{sku:'PF2600053',moqRaw:'1',priceRaw:'16',freightRaw:'包邮'}]),preview())
  expect(saved).toHaveBeenCalledWith({added:1,updated:1,unchanged:0,skipped:0});expect(document.body.textContent).toContain('更新1条')
})
it('blocks ambiguous rows and preserves editable sources after returning',async()=>{
  mount();await paste();vi.mocked(service.previewFobPaste).mockResolvedValueOnce(preview(false));button('预览识别与变更').click();await tick()
  expect(button('确认全部保存').disabled).toBe(true);button('返回编辑').click();await tick()
  expect((document.querySelector('[aria-label="第1行 采购价格原文"]') as HTMLTextAreaElement).value).toBe('16')
  expect(service.confirmFobPaste).not.toHaveBeenCalled()
})
it('requires a new preview after conflicts and never retries saving automatically',async()=>{
  mount();await paste();vi.mocked(service.previewFobPaste).mockResolvedValueOnce(preview());button('预览识别与变更').click();await tick()
  vi.mocked(service.confirmFobPaste).mockRejectedValueOnce(new Error('版本冲突'));button('确认全部保存').click();await tick()
  expect(document.querySelector('[aria-label="第1行 SKU"]')).not.toBeNull();expect(document.body.textContent).toContain('重新预览')
  expect(service.confirmFobPaste).toHaveBeenCalledTimes(1)
})
