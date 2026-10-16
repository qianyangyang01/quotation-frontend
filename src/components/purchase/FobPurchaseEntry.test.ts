// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import FobPurchaseEntry from './FobPurchaseEntry.vue'
import * as service from '@/services/fobPurchase'
vi.mock('@/services/fobPurchase', () => ({ previewFobPaste: vi.fn(), confirmFobPaste: vi.fn(), readFobWorkbook: vi.fn(), uploadFobImage: vi.fn() }))
let app: App
const tick = async () => { await nextTick(); await Promise.resolve(); await nextTick(); await Promise.resolve(); await nextTick() }
function mount() { const host = document.createElement('div'); document.body.append(host); const saved = vi.fn(), busy = vi.fn(); app = createApp(FobPurchaseEntry, { active: true, onSaved: saved, onBusy: busy }); app.mount(host); return { saved, busy } }
const button = (text: string) => Array.from(document.querySelectorAll('button')).find(b => b.textContent?.trim() === text)!
async function input(label: string, value: string) { const element = document.querySelector<HTMLInputElement>(`[aria-label="${label}"]`)!; element.value = value; element.dispatchEvent(new Event('input', { bubbles: true })); await tick() }
const preview = (canSave = true): service.FobPreview => ({ rows: [{ sourceRow: 1, sku: 'PF2600100', action: 'create', expected: { sku: 'PF2600100', version: null, updatedAt: null }, changes: [], issues: canSave ? [] : ['价格有误'], notices: [], effective: { sku: 'PF2600100' } }], skipped: [], canSave, digest: 'digest' })
async function fill() { await input('FOB SKU','PF2600100'); await input('FOB 起订量','100'); await input('第1档数量起点','100'); await input('第1档未税单价','12.5'); await input('FOB 国内总运费','35') }
afterEach(() => { app?.unmount(); document.body.innerHTML = ''; vi.resetAllMocks() })
it('includes uploaded image references in preview without writing a purchase record early', async () => {
  mount(); await fill()
  const url='/api/v1/assets/11111111-1111-4111-8111-111111111111'
  vi.mocked(service.uploadFobImage).mockResolvedValue({url})
  const element=document.querySelector<HTMLInputElement>('[aria-label="FOB 产品图片"]')!
  Object.defineProperty(element,'files',{value:[new File(['png'],'product.png',{type:'image/png'})]})
  element.dispatchEvent(new Event('change')); await tick()
  expect(document.querySelector('img')?.getAttribute('src')).toBe(url)
  expect(service.confirmFobPaste).not.toHaveBeenCalled()
  vi.mocked(service.previewFobPaste).mockResolvedValue(preview())
  button('预览并保存 FOB 资料').click(); await tick()
  expect(service.previewFobPaste).toHaveBeenCalledWith([expect.objectContaining({productImage:url})])
})
it('manual entry never writes until preview confirmation, and locks controls while saving', async () => {
  const { saved, busy } = mount(); await fill()
  vi.mocked(service.previewFobPaste).mockResolvedValue(preview())
  button('预览并保存 FOB 资料').click(); await tick()
  expect(service.confirmFobPaste).not.toHaveBeenCalled()
  let complete!: (value: { added: number; updated: number; unchanged: number; skipped: number }) => void
  vi.mocked(service.confirmFobPaste).mockReturnValue(new Promise(resolve => { complete = resolve }))
  button('确认保存 FOB 资料').click(); await tick()
  expect(button('手工填写').disabled).toBe(true); expect(busy).toHaveBeenLastCalledWith(true)
  complete({ added:1, updated:0, unchanged:0, skipped:0 }); await tick()
  expect(saved).toHaveBeenCalledOnce()
  expect(service.confirmFobPaste).toHaveBeenCalledWith([expect.objectContaining({ sku:'PF2600100', moqRaw:'100件起订;1的倍数下单', priceRaw:'100件起单价12.5', freightRaw:'100件运费35' })], preview())
})
it('preserves manual input on conflict and requires a fresh preview', async () => {
  mount(); await fill(); vi.mocked(service.previewFobPaste).mockResolvedValue(preview())
  button('预览并保存 FOB 资料').click(); await tick()
  vi.mocked(service.confirmFobPaste).mockRejectedValue(new Error('版本冲突'))
  button('确认保存 FOB 资料').click(); await tick()
  expect((document.querySelector('[aria-label="FOB SKU"]') as HTMLInputElement).value).toBe('PF2600100')
  expect(document.body.textContent).toContain('重新预览')
  expect(service.confirmFobPaste).toHaveBeenCalledTimes(1)
})
it('reads a workbook without saving, previews all rows and blocks invalid results', async () => {
  mount(); button('Excel 文件导入').click(); await tick()
  vi.mocked(service.readFobWorkbook).mockResolvedValue({ rows:[{ sku:'PF2600100', priceRaw:'12' }], locations:[{sheet:'采购表',row:3}], notices:['图片不导入'] })
  const element = document.querySelector<HTMLInputElement>('[aria-label="选择FOB Excel文件"]')!
  Object.defineProperty(element, 'files', { value:[new File(['xlsx'],'采购表.xlsx')], configurable:true })
  element.dispatchEvent(new Event('change')); await tick()
  expect(service.confirmFobPaste).not.toHaveBeenCalled()
  vi.mocked(service.previewFobPaste).mockResolvedValue(preview(false))
  button('预览并保存 FOB 资料').click(); await tick()
  expect(service.previewFobPaste).toHaveBeenCalledWith([{sku:'PF2600100',priceRaw:'12'}])
  expect(document.body.textContent).toContain('Excel 第3行')
  expect(button('确认保存 FOB 资料').disabled).toBe(true)
})
