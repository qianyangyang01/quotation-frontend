// @vitest-environment happy-dom
import { expect, it } from 'vitest'
import { readUnifiedPurchaseClipboardResult } from './purchaseClipboard'
import { applyPurchasePaste, UNIFIED_PURCHASE_PASTE_COLUMNS as columns, validatePurchasePaste } from './purchasePaste'

// Actual reported row, including all eight empty tier cells and three image cells.
const reported = '| | | | 2026.9.10 | 方 | 暂时现货，相似款，下单需要跟工厂确定是否现货 | HC2601658-1 | 1500 | 100厘米 | 恐龙 ，兔子,小猪 | 四面弹力面料+羽绒棉填充 | | | | 1 | 45.6 | | | | | | | | | 4 | 22 | 202 | 否 | | 0% | 普票1 | 家居用品 | 有 | 保定九赞商贸有限公司 | | https://detail.1688.com/offer/1048790478494.html | |'
  .split('|').slice(1, -1).map(value => value.trim())
const expected = {
  sourceRow: 1, sku: 'HC2601658-1', quotationDate: '2026-09-10', quotationOwner: '方',
  notes: '暂时现货，相似款，下单需要跟工厂确定是否现货', weightG: 1500, size: '100厘米',
  color: '恐龙 ，兔子,小猪', material: '四面弹力面料+羽绒棉填充', minOrderQty: 1,
  purchasePriceCny: 45.6, singleFreightCny: 4, freight10Cny: 22, freight100Cny: 202,
  freeShipping: '否', taxPoint: 0, invoiceType: '普票1', category: '家居用品', stockStatus: '有货',
  factoryInfo: '保定九赞商贸有限公司', sourceLink1: 'https://detail.1688.com/offer/1048790478494.html',
}
function clipboard(rows: string[][], html: boolean) {
  return { getData: (type: string) => type === 'text/html'
    ? html ? `<table>${rows.map(row => `<tr>${row.map(cell => `<td>${cell}</td>`).join('')}</tr>`).join('')}</table>` : ''
    : rows.map(row => row.join('\t')).join('\r\n') }
}

it.each([true, false])('preserves every reported field through HTML=%s clipboard, grid and sparse preview payload', html => {
  for (const images of [true, false]) for (const length of [33, 34, 35, 36]) {
    const full = [...reported.slice(3), '', '']
    const source = [...(images ? ['', '', ''] : []), ...full.slice(0, length)]
    const result = readUnifiedPurchaseClipboardResult(clipboard([source], html), 0)
    expect(result.skippedImageColumns).toBe(images)
    expect(result.rows).toEqual([full])
    const grid = applyPurchasePaste([], result.rows, 0, 0, columns)
    const validated = validatePurchasePaste(grid, true, columns)
    expect(validated.issues).toEqual([])
    expect(validated.records).toEqual([expected])
  }
})

it('pads only the suffix: existing links survive and explicit zero remains an update', () => {
  const full = [...reported.slice(3), '', '']
  const existing = columns.map(() => '')
  existing[33] = 'https://supplier.example/second'; existing[34] = 'https://supplier.example/third'
  const result = readUnifiedPurchaseClipboardResult(clipboard([full.slice(0, 33)], false), 0)
  const grid = applyPurchasePaste([existing], result.rows, 0, 0, columns)
  expect(grid[0]!.slice(33, 35)).toEqual(existing.slice(33, 35))
  expect(validatePurchasePaste(grid, true, columns).records[0]).toMatchObject({ taxPoint: 0 })
})

it.each([true, false])('rejects ambiguous old layouts, extra data and wrong start column for HTML=%s', html => {
  const full = [...reported.slice(3), '', '']
  const legacy = [...full.slice(0, 17), ...full.slice(21)]
  for (const source of [legacy, full.slice(0, 32), full.slice(0, 25)]) {
    expect(() => readUnifiedPurchaseClipboardResult(clipboard([source], html), 0)).toThrow('旧三档模板须带表头')
    expect(() => readUnifiedPurchaseClipboardResult(clipboard([['', '', '', ...source]], html), 0)).toThrow('旧三档模板须带表头')
  }
  expect(() => readUnifiedPurchaseClipboardResult(clipboard([reported], html), 1)).toThrow('报价日期')
  expect(() => readUnifiedPurchaseClipboardResult(clipboard([[...full, '意外数据']], html), 0)).toThrow()
})

it('rejects shifted interior cells and jagged text instead of guessing missing columns', () => {
  const full = [...reported.slice(3), '', '']
  const shifted = [...full.slice(0, 17), ...full.slice(18)]
  const result = readUnifiedPurchaseClipboardResult(clipboard([shifted], false), 0)
  expect(validatePurchasePaste(result.rows, true, columns).canSave).toBe(false)
  expect(() => readUnifiedPurchaseClipboardResult(clipboard([reported, reported.slice(0, -1)], false), 0)).toThrow('行列边界')
})

it('uses explicit headers for shorter or reordered selections and keeps exact field identities', () => {
  const result = readUnifiedPurchaseClipboardResult(clipboard([
    ['SKU', '100件总运费(CNY)', '基准采购单价(CNY/件)', '票点*'],
    ['HC2601658-1', '202', '45.6', '0%'],
  ], false), 0)
  expect(validatePurchasePaste(result.rows, true, columns).records).toEqual([
    { sourceRow: 1, sku: 'HC2601658-1', freight100Cny: 202, purchasePriceCny: 45.6, taxPoint: 0 },
  ])
})
