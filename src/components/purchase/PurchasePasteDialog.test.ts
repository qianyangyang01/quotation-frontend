// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import PurchasePasteDialog from './PurchasePasteDialog.vue'
import { emptyPurchasePasteRow } from '@/data/purchasePaste'

const request = vi.hoisted(() => vi.fn())
vi.mock('@/services/http', () => ({ request }))
let app: App | undefined
const tick = async () => { await nextTick(); await nextTick(); await nextTick() }
function mount() {
  const saved = vi.fn()
  const host = document.createElement('div'); document.body.append(host)
  app = createApp(PurchasePasteDialog, { onSaved: saved }); app.mount(host)
  return saved
}
function button(name: string) { return Array.from(document.querySelectorAll('button')).find(b => b.textContent === name)! }
function cell(row: number, column: number) { return document.querySelector(`[data-cell="${row}:${column}"]`) as HTMLInputElement }
async function paste(rows: string[][], html = '') {
  const event = new Event('paste', { bubbles: true, cancelable: true })
  Object.defineProperty(event, 'clipboardData', { value: { getData: (type: string) => type === 'text/html' ? html : rows.map(r => r.join('\t')).join('\r\n') } })
  cell(0, 0).dispatchEvent(event); await tick()
}
function row(sku = 'P-1', price = '15') { return Object.assign(emptyPurchasePasteRow(), { 3: sku, 12: price }) }
function preview(sku = 'P-1', action = 'update', notices: string[] = []) {
  return { rows: [{ sku, sourceRow: 1, action, expected: { sku, productId: 'product-1', version: 4, updatedAt: '2026-09-24T00:00:00Z' },
    changes: action === 'unchanged' ? [] : [{ field: 'purchasePriceCny', label: '基准采购单价', before: 12, after: 15 }],
    effective: { sku, purchasePriceCny: 15 }, notices }], skipped: [] }
}
afterEach(() => { app?.unmount(); document.body.innerHTML = ''; vi.resetAllMocks(); vi.unstubAllGlobals() })

it('previews sparse patches and requires explicit confirmation with the preview version', async () => {
  const saved = mount(); await paste([row()]); request.mockResolvedValueOnce(preview())
  button('预览并保存 1 条').click(); await tick()
  expect(request).toHaveBeenCalledTimes(1)
  expect(request.mock.calls[0]![0]).toBe('/purchase-products/paste/preview')
  expect(JSON.parse(request.mock.calls[0]![1].body)).toEqual([{ sourceRow: 1, sku: 'P-1', purchasePriceCny: 15 }])
  const panel = document.querySelector('[aria-label="采购粘贴变更预览"]')!
  expect(panel.textContent).toContain('12'); expect(panel.textContent).toContain('15')
  expect(panel.textContent).toContain('更新计入修改记录')
  request.mockResolvedValueOnce({ added: [], updated: [{ sku: 'P-1', category: '图书' }], unchanged: [], skipped: [] })
  button('确认全部保存').click(); await tick()
  expect(request.mock.calls[1]![0]).toBe('/purchase-products/paste/confirm')
  expect(JSON.parse(request.mock.calls[1]![1].body).expected).toEqual([preview().rows[0]!.expected])
  expect(saved).toHaveBeenCalledWith({ added: 0, updated: 1, unchanged: 0, skipped: 0 })
  expect(document.querySelector('[role="status"]')!.textContent).toContain('更新1条')
  expect(cell(0, 3).value).toBe('')
})

it('cancel preserves the grid and edits require a fresh preview', async () => {
  mount(); await paste([row()]); request.mockResolvedValueOnce(preview())
  button('预览并保存 1 条').click(); await tick(); button('返回编辑').click(); await tick()
  expect(request).toHaveBeenCalledTimes(1); expect(cell(0, 12).value).toBe('15')
  cell(0, 12).value = '20'; cell(0, 12).dispatchEvent(new Event('input', { bubbles: true })); await tick()
  request.mockResolvedValueOnce(preview()); button('预览并保存 1 条').click(); await tick()
  expect(JSON.parse(request.mock.calls[1]![1].body)[0].purchasePriceCny).toBe(20)
})

it('retains cells on conflict or timeout and never automatically retries confirmation', async () => {
  mount(); await paste([row()])
  for (const error of [new Error('商品已变化'), new DOMException('timeout', 'TimeoutError')]) {
    request.mockResolvedValueOnce(preview()); button('预览并保存 1 条').click(); await tick()
    request.mockRejectedValueOnce(error); button('确认全部保存').click(); await tick()
    expect(cell(0, 12).value).toBe('15')
    expect(document.querySelector('[aria-label="采购粘贴变更预览"]')).toBeNull()
    expect(document.querySelector('[role="status"]')!.textContent).toContain('重新预览')
  }
  expect(request).toHaveBeenCalledTimes(4)
})

it('shows server validation for new rows and sends explicit zero without synthesizing blanks', async () => {
  mount(); const input = row(); await paste([input])
  request.mockRejectedValueOnce(new Error('第1行：请填写票点'))
  button('预览并保存 1 条').click(); await tick()
  expect(document.querySelector('[role="status"]')!.textContent).toContain('请填写票点')
  input[22] = '0%'; input[12] = '0'; await paste([input]); request.mockResolvedValueOnce(preview())
  button('预览并保存 1 条').click(); await tick()
  expect(JSON.parse(request.mock.calls[1]![1].body)[0]).toEqual({ sourceRow: 1, sku: 'P-1', purchasePriceCny: 0, taxPoint: 0 })
})

it('shows legacy final-price notices and unchanged rows without fabricating updates', async () => {
  const saved = mount(); await paste([row()]); request.mockResolvedValueOnce(preview('P-1', 'unchanged', ['最终生效采购单价 12']))
  button('预览并保存 1 条').click(); await tick()
  expect(document.querySelector('.price-notice')!.textContent).toContain('最终生效采购单价 12')
  expect(document.querySelector('.paste-preview')!.textContent).toContain('不重复生成修改记录')
  request.mockResolvedValueOnce({ added: [], updated: [], unchanged: ['P-1'], skipped: [] })
  button('确认全部保存').click(); await tick()
  expect(saved).toHaveBeenCalledWith({ added: 0, updated: 0, unchanged: 1, skipped: 0 })
  expect(button('一键复制SKU和品类').disabled).toBe(true)
})

it('keeps first duplicates, identifies skipped rows and copies confirmed additions and updates only', async () => {
  const writeText = vi.fn().mockResolvedValue(undefined); vi.stubGlobal('navigator', { clipboard: { writeText } })
  mount(); await paste([row('P-NEW'), row('P-OLD'), row('p-new', '99')])
  expect(document.querySelector('.paste-dialog')!.textContent).toContain('第3行 P-NEW')
  request.mockResolvedValueOnce({ rows: [...preview('P-NEW', 'create').rows, ...preview('P-OLD').rows], skipped: [] })
  button('预览并保存 2 条').click(); await tick()
  expect(JSON.parse(request.mock.calls[0]![1].body)).toHaveLength(2)
  request.mockResolvedValueOnce({ added: [{ sku: 'P-NEW', category: '图书' }], updated: [{ sku: 'P-OLD', category: '服装' }], unchanged: [], skipped: [] })
  button('确认全部保存').click(); await tick()
  expect(document.querySelector('[role="status"]')!.textContent).toContain('同批重复跳过1条')
  button('一键复制SKU和品类').click(); await tick()
  expect(writeText).toHaveBeenCalledWith('P-NEW\t图书\nP-OLD\t服装')
  writeText.mockRejectedValueOnce(new Error('denied')); button('一键复制SKU和品类').click(); await tick()
  expect((document.querySelector('textarea') as HTMLTextAreaElement).value).toBe('P-NEW\t图书\nP-OLD\t服装')
})

it('keeps multiline HTML aligned, filters images and rejects jagged clipboard input atomically', async () => {
  mount()
  const rows = [row('QA-1', '41.1'), row('QA-2', '27.5')]
  rows.forEach(r => { r[5] = '页数118\n尺寸253*250'; r[6] = '小熊\n小兔' })
  const html = `<table>${rows.map(r => `<tr><td><img src="x"></td><td></td><td></td>${r.map(c => `<td>${c.replace(/\n/g, '<br>')}</td>`).join('')}</tr>`).join('')}</table>`
  await paste(rows, html)
  expect(cell(1, 12).value).toBe('27.5')
  expect(document.querySelector('[role="status"]')!.textContent).toContain('已过滤前3列')
  await paste([['不可确定', '内容'], ['第二段']])
  expect(document.querySelector('[role="status"]')!.textContent).toContain('本次未写入')
  expect(cell(1, 12).value).toBe('27.5')
  request.mockResolvedValueOnce({ rows: preview('QA-1').rows.concat(preview('QA-2').rows), skipped: [] })
  button('预览并保存 2 条').click(); await tick()
  expect(JSON.parse(request.mock.calls[0]![1].body)[0]).toMatchObject({ sku: 'QA-1', size: '页数118\n尺寸253*250', color: '小熊\n小兔', purchasePriceCny: 41.1 })
})
