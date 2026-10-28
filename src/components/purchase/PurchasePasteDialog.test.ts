// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import PurchasePasteDialog from './PurchasePasteDialog.vue'
import { emptyPurchasePasteRow, UNIFIED_PURCHASE_PASTE_COLUMNS } from '@/data/purchasePaste'

const request = vi.hoisted(() => vi.fn())
vi.mock('@/services/http', () => ({ request }))
let app: App | undefined
const tick = async () => { await nextTick(); await nextTick(); await nextTick() }
function mount(unified = false) {
  const saved = vi.fn()
  const host = document.createElement('div'); document.body.append(host)
  app = createApp(PurchasePasteDialog, { onSaved: saved, unified }); app.mount(host)
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

it('previews and confirms the reported five-tier row without its empty trailing links, keeping every monetary field', async () => {
  mount(true)
  const source = ['', '', '', '2026.9.10', '方', '暂时现货，相似款，下单需要跟工厂确定是否现货',
    'HC2601658-1', '1500', '100厘米', '恐龙 ，兔子,小猪', '四面弹力面料+羽绒棉填充',
    '', '', '', '1', '45.6', '', '', '', '', '', '', '', '', '4', '22', '202', '否', '', '0%',
    '普票1', '家居用品', '有', '保定九赞商贸有限公司', '', 'https://detail.1688.com/offer/1048790478494.html']
  await paste([source])
  expect(cell(0, 3).value).toBe('HC2601658-1')
  expect(cell(0, 12).value).toBe('45.6')
  expect(Array.from({ length: 8 }, (_, i) => cell(0, 13 + i).value)).toEqual(Array(8).fill(''))
  expect([21, 22, 23].map(i => cell(0, i).value)).toEqual(['4', '22', '202'])
  const expectedRow = {
    sourceRow: 1, sku: 'HC2601658-1', quotationDate: '2026-09-10', quotationOwner: '方',
    notes: source[5], weightG: 1500, size: '100厘米', color: '恐龙 ，兔子,小猪',
    material: '四面弹力面料+羽绒棉填充', minOrderQty: 1, purchasePriceCny: 45.6,
    singleFreightCny: 4, freight10Cny: 22, freight100Cny: 202, freeShipping: '否', taxPoint: 0,
    invoiceType: '普票1', category: '家居用品', stockStatus: '有货', factoryInfo: '保定九赞商贸有限公司',
    sourceLink1: 'https://detail.1688.com/offer/1048790478494.html',
  }
  const serverPreview = preview('HC2601658-1')
  request.mockResolvedValueOnce(serverPreview)
  button('预览并保存 1 条').click(); await tick()
  expect(request.mock.calls[0]![0]).toBe('/purchase-products/paste/shared/preview')
  expect(JSON.parse(request.mock.calls[0]![1].body)).toEqual([expectedRow])
  request.mockResolvedValueOnce({ added: [], updated: [expectedRow], unchanged: [], skipped: [] })
  button('确认全部保存').click(); await tick()
  expect(request.mock.calls[1]![0]).toBe('/purchase-products/paste/shared/confirm')
  expect(JSON.parse(request.mock.calls[1]![1].body)).toEqual({ rows: [expectedRow], expected: [serverPreview.rows[0]!.expected] })
})

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

it('uses the five-tier endpoint and keeps exact values through preview and confirmation', async () => {
  mount(true)
  const input = emptyPurchasePasteRow(UNIFIED_PURCHASE_PASTE_COLUMNS)
  Object.assign(input, {3: 'P-1', 17: '500', 18: '4.25', 19: '1000', 20: '0', 21: '9', 22: '40', 23: '150'})
  await paste([input]); request.mockResolvedValueOnce(preview())
  button('预览并保存 1 条').click(); await tick()
  expect(request.mock.calls[0]![0]).toBe('/purchase-products/paste/shared/preview')
  const payload = JSON.parse(request.mock.calls[0]![1].body)
  expect(payload[0]).toMatchObject({tier4MinQty:500,tier4PriceCny:4.25,tier5MinQty:1000,tier5PriceCny:0,singleFreightCny:9,freight10Cny:40,freight100Cny:150})
  request.mockResolvedValueOnce({added: [], updated: [{sku:'P-1'}], unchanged:[], skipped:[]})
  button('确认全部保存').click(); await tick()
  expect(request.mock.calls[1]![0]).toBe('/purchase-products/paste/shared/confirm')
  expect(JSON.parse(request.mock.calls[1]![1].body).rows).toEqual(payload)
})

it('previews and confirms legacy updates in the unified entry with the original price rules and version', async () => {
  const saved = mount(true)
  const sku = 'FL2600389ZT'
  const input = emptyPurchasePasteRow(UNIFIED_PURCHASE_PASTE_COLUMNS)
  Object.assign(input, { 3: sku, 12: '120' })
  await paste([input])
  const expected = { sku, productId: 'legacy-product-1', version: 7, updatedAt: '2026-10-24T01:02:03Z' }
  const effective = { sku, dataSource: 'legacy_2026', legacyPriceMode: 'auto_tax',
    sourceQuotedPriceCny: 120, purchasePriceCny: 132, taxIncludedPriceCny: 132, taxPoint: .1 }
  const notices = ['2026旧数据沿用原资料类型和价格规则；本次仅更新已填写字段，不自动切换FOB来源',
    '未税采购单价 120 ×（1＋票点 0.1）＝含票价 132']
  request.mockResolvedValueOnce({ rows: [{ sku, sourceRow: 1, action: 'update', expected, effective, notices,
    changes: [{ field: 'sourceQuotedPriceCny', label: '未税采购单价(CNY/件)', before: 110, after: 120 },
      { field: 'purchasePriceCny', label: '基准采购单价(CNY/件)', before: 121, after: 132 }] }], skipped: [] })

  button('预览并保存 1 条').click(); await tick()
  expect(request).toHaveBeenCalledTimes(1)
  expect(request.mock.calls[0]![0]).toBe('/purchase-products/paste/shared/preview')
  const rows = [{ sourceRow: 1, sku, purchasePriceCny: 120 }]
  expect(JSON.parse(request.mock.calls[0]![1].body)).toEqual(rows)
  const panel = document.querySelector('[aria-label="采购粘贴变更预览"]')!
  expect(Array.from(panel.querySelectorAll('.price-notice'), notice => notice.textContent)).toEqual(notices)
  expect(Array.from(panel.querySelectorAll('tbody tr'), row => Array.from(row.querySelectorAll('th,td'), cell => cell.textContent)))
    .toEqual([['未税采购单价(CNY/件)', '110', '120'], ['基准采购单价(CNY/件)', '121', '132']])
  expect(panel.textContent).toContain('历史报价保持不变')
  expect(saved).not.toHaveBeenCalled()

  request.mockResolvedValueOnce({ added: [], updated: [effective], unchanged: [], skipped: [] })
  button('确认全部保存').click(); await tick()
  expect(request.mock.calls[1]![0]).toBe('/purchase-products/paste/shared/confirm')
  expect(JSON.parse(request.mock.calls[1]![1].body)).toEqual({ rows, expected: [expected] })
  expect(saved).toHaveBeenCalledWith({ added: 0, updated: 1, unchanged: 0, skipped: 0 })
  expect(document.querySelector('[role="status"]')!.textContent).toContain('更新已计入修改记录')
})
