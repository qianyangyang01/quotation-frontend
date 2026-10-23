// @vitest-environment happy-dom
import { expect, it, vi, afterEach } from 'vitest'
import { UNIFIED_PURCHASE_PASTE_COLUMNS as columns, PURCHASE_PASTE_COLUMNS, emptyPurchasePasteRow, validatePurchasePaste } from './purchasePaste'
import { readUnifiedPurchaseClipboardResult } from './purchaseClipboard'
import { normalizePurchaseRecord, purchaseUnitPrice, selectedPurchaseTier, loadPurchaseProduct } from './purchaseStore'
import { loadFobRecord } from '@/services/fobPurchase'
import { fromStandardPurchase, fobTierQuotes, fobQuantityQuote, loadFobQuoteProduct } from '@/services/fobQuotation'
import { purchasePriceBreakdown, normalizePurchaseTier } from '@/services/quotationCalculator'
import { ApiError } from '@/services/http'
vi.mock('@/services/fobPurchase', () => ({ loadFobRecord: vi.fn() }))
vi.mock('./purchaseStore', async original => ({ ...await original<typeof import('./purchaseStore')>(), loadPurchaseProduct: vi.fn() }))
afterEach(() => vi.resetAllMocks())
const clipboard = (rows: string[][]) => ({ getData: (type: string) => type === 'text/plain' ? rows.map(r => r.join('\t')).join('\r\n') : '' })
function source() {
  const values: Record<string, string> = { sku: 'QA-FIVE', weightG: '120', minOrderQty: '1', purchasePriceCny: '20', tier2MinQty: '10', tier2PriceCny: '18', tier3MinQty: '100', tier3PriceCny: '16', tier4MinQty: '500', tier4PriceCny: '14', tier5MinQty: '1000', tier5PriceCny: '12', singleFreightCny: '5', freight10Cny: '20', freight100Cny: '100', taxPoint: '8%' }
  return columns.map(([, field]) => values[field] ?? '')
}
it('maps five-tier source cells exactly, including freight and tax after inserted columns', () => {
  const result = readUnifiedPurchaseClipboardResult(clipboard([source()]), 0)
  const validated = validatePurchasePaste(result.rows, false, columns)
  expect(validated.issues).toEqual([])
  expect(validated.records[0]).toMatchObject({ tier4MinQty: 500, tier4PriceCny: 14, tier5MinQty: 1000, tier5PriceCny: 12, singleFreightCny: 5, freight10Cny: 20, freight100Cny: 100, taxPoint: .08 })
  expect(readUnifiedPurchaseClipboardResult(clipboard([['', '', '', ...source()]]), 0)).toEqual({ rows: [source()], skippedImageColumns: true })
})
it('maps old three-tier and reordered headers without moving freight into prices', () => {
  const old = PURCHASE_PASTE_COLUMNS.map(([, field]) => source()[columns.findIndex(c => c[1] === field)]!)
  const result = readUnifiedPurchaseClipboardResult(clipboard([PURCHASE_PASTE_COLUMNS.map(c => c[0]), old]), 0)
  expect(result.rows[0]![17]).toBe(''); expect(result.rows[0]![21]).toBe('5')
  expect(() => readUnifiedPurchaseClipboardResult(clipboard([old]), 0)).toThrow('旧三档模板须带表头')
  const reordered = readUnifiedPurchaseClipboardResult(clipboard([['SKU*', '阶梯价5(CNY/件)', '1件总运费(CNY)'], ['QA-FIVE', '12', '5']]), 0)
  expect(reordered.rows[0]![3]).toBe('QA-FIVE'); expect(reordered.rows[0]![20]).toBe('12'); expect(reordered.rows[0]![21]).toBe('5')
})
it('filters image cells and image headers while preserving multiline data and all five prices', () => {
  const row = source()
  row[2] = '暂时现货\n下单前确认'
  const cells = row.map(value => `<td>${value.replace(/\n/g, '<br>')}</td>`).join('')
  const images = '<td><img src="https://invalid.example/test.png"><svg><text>图片</text></svg></td><td><canvas>图片</canvas></td><td></td>'
  for (const headers of ['', '<tr>' + ['实物图（嵌入本格）', '实物图（嵌入本格）', '产品图片（嵌入本格）', ...columns.map(c => c[0])].map(value => `<th>${value}</th>`).join('') + '</tr>']) {
    const html = `<table>${headers}<tr>${images}${cells}</tr></table>`
    expect(readUnifiedPurchaseClipboardResult({ getData: type => type === 'text/html' ? html : '损坏的纯文本' }, 0)).toEqual({ rows: [row], skippedImageColumns: true })
  }
  expect(document.querySelector('img')).toBeNull()
  expect(readUnifiedPurchaseClipboardResult(clipboard([['[图片]', '=DISPIMG(id,1)', '=_xlfn.DISPIMG(id,1)', ...source()]]), 0)).toEqual({ rows: [source()], skippedImageColumns: true })
  expect(() => readUnifiedPurchaseClipboardResult(clipboard([['实际业务文字', '', '', ...source()]]), 0)).toThrow()
})
it.each([
  [['SKU', '未知价格'], ['QA-FIVE', '2']],
  [['SKU', 'SKU'], ['QA-FIVE', 'QA-OTHER']],
  [['SKU', '票点*'], ['QA-FIVE']],
  [['SKU', ''], ['QA-FIVE', '2']],
].map(rows => ({ rows })))('blocks ambiguous columns before applying any cells: $rows', ({ rows }) => {
  expect(() => readUnifiedPurchaseClipboardResult(clipboard(rows), 0)).toThrow()
})
it('validates missing pairs, descending quantities and zero versus blank on the last tiers', () => {
  const row = source(); row[20] = ''
  expect(validatePurchasePaste([row], false, columns).canSave).toBe(false)
  row[20] = '0'; expect(validatePurchasePaste([row], false, columns).canSave).toBe(true)
  row[19] = '500'; expect(validatePurchasePaste([row], false, columns).canSave).toBe(false)
  const sparse = emptyPurchasePasteRow(columns); sparse[3] = 'QA-FIVE'; sparse[20] = '0'
  expect(validatePurchasePaste([sparse], true, columns).records[0]).toEqual({ sku: 'QA-FIVE', sourceRow: 1, tier5PriceCny: 0 })
})
it('matches every quantity boundary and preserves distinct ordinary and FOB formulas', () => {
  const record = validatePurchasePaste([source()], false, columns).records[0]!
  for (const [q, price] of [[1,20],[9,20],[10,18],[99,18],[100,16],[499,16],[500,14],[999,14],[1000,12],[1001,12]]) expect(purchaseUnitPrice(record, q!)).toBe(price)
  expect(selectedPurchaseTier(record, 'tier4').tier?.unitPriceCny).toBe(14)
  expect(selectedPurchaseTier(record, 'tier5').tier?.unitPriceCny).toBe(12)
  expect(normalizePurchaseTier('tier5')).toBe('tier5')
  expect(purchasePriceBreakdown(record, 'tier5').effectiveUnitPriceCny).toBe(12.96)
  const product = fromStandardPurchase(record), tiers = fobTierQuotes(product, 7)
  expect(tiers).toHaveLength(5)
  expect(tiers[4]).toMatchObject({ purchaseCny: 12, taxIncludedCny: 13.2, freightCny: 1, costCny: 14.2 })
  const quote = fobQuantityQuote(product, tiers, '1000', 7, {scope:'single-price',calculation:'before-coefficient'})
  expect(quote.declaredUsd).toBe('2.31'); expect(quote.undeclaredUsd).toBe('2.36')
})
it('uses explicitly shared data before an existing FOB record and fails closed when shared freight is missing', async () => {
  const record = normalizePurchaseRecord({...validatePurchasePaste([source()], false, columns).records[0]!, purchaseUsage:'shared'})
  vi.mocked(loadPurchaseProduct).mockResolvedValue(record)
  expect((await loadFobQuoteProduct(record.sku, 'auto')).source).toBe('standard')
  expect(loadFobRecord).not.toHaveBeenCalled()
  vi.mocked(loadPurchaseProduct).mockResolvedValue({...record, freight100Cny:null})
  await expect(loadFobQuoteProduct(record.sku, 'auto')).rejects.toThrow('运费')
  expect(loadFobRecord).not.toHaveBeenCalled()
  vi.mocked(loadPurchaseProduct).mockRejectedValue(new ApiError('网络', 503, 'UNAVAILABLE', 'qa'))
  await expect(loadFobQuoteProduct(record.sku, 'auto')).rejects.toThrow('网络')
  expect(loadFobRecord).not.toHaveBeenCalled()
})
