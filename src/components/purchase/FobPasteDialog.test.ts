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
it('filters pictures from complete pasted rows and submits only source text for tier parsing',async()=>{
  mount()
  const event = new Event('paste',{bubbles:true,cancelable:true})
  const html = '<table><tr><td>实物图</td><td>产品图片</td><td>SKU</td><td>起订量</td><td>单价</td><td>运费原文</td></tr><tr><td><img src="file:///D:/sample.png"></td><td><img src="https://example.com/product.png"></td><td>PF2600049</td><td>1</td><td>单件：23.99单价<br>100起单价19.5<br>300单价19.5<br>500单价19<br>1000单价19</td><td>一件运费：4\t10件运费：11\t100件预拍运费：74</td></tr></table>'
  Object.defineProperty(event,'clipboardData',{value:{getData:(type:string)=>type==='text/html'?html:'',files:[new File(['picture'],'picture.png',{type:'image/png'})]}})
  document.querySelector('.paste-target')!.dispatchEvent(event);await tick()
  expect(document.body.textContent).toContain('自动过滤图片')
  expect(document.querySelectorAll('img')).toHaveLength(0)
  vi.mocked(service.previewFobPaste).mockResolvedValueOnce(preview());button('预览识别与变更').click();await tick()
  expect(service.previewFobPaste).toHaveBeenCalledWith([{sku:'PF2600049',moqRaw:'1',priceRaw:'单件：23.99单价\n100起单价19.5\n300单价19.5\n500单价19\n1000单价19',freightRaw:'一件运费：4\t10件运费：11\t100件预拍运费：74'}])
  expect(service.confirmFobPaste).not.toHaveBeenCalled()
})
