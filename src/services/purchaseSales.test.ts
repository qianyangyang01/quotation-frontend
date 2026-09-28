import { describe, expect, it } from 'vitest'
import { filterPurchaseSales, matchPurchaseSales, purchaseSalesReminders, salesCsv, type SalesData, type SalesProduct } from './purchaseSales'

const product: SalesProduct = { sku: 'SKU-1', quotationOwner: '黄', weightG: 100, taxPoint: 0, invoiceType: '没票', purchasePriceCny: 10, taxIncludedPriceCny: null, tier2PriceCny: null, tier3PriceCny: null, minOrderQty: 1, singleFreightCny: 0, dataSource: 'legacy_2026', catalogState: 'ready', sourceSheet: '原采购表', sourceRow: 20 }
const sourceRow = (sku: string) => ({ sku, sourceSku: sku, sourceRow: 2, months: [1, 2, 3], total: 6, monthlyTotal: 6, salesDifference: 0 })
const data = (products: SalesProduct[]): SalesData => ({ source: { period: '6—8月', sourceFile: 'test.xlsx', sourceSheet: '汇总', sourceSha256: '', rows: [sourceRow('SKU-1'), sourceRow('SKU-1-A')], monthlyOnlySkus: [] }, products, matchedAt: '' })
describe('sales procurement matching', () => {
  it('preserves suffix identity and does not guess a parent match for missing variants', () => {
    const rows = matchPurchaseSales(data([product]))
    expect(rows[0]!.reminders).toEqual([])
    expect(rows[1]!.product).toBeUndefined()
    expect(rows[1]!.reminders).toEqual(['未匹配'])
    const childOnly = matchPurchaseSales(data([{ ...product, sku: 'SKU-1-A' }]))
    expect(childOnly[0]!.product).toBeUndefined()
    expect(childOnly[0]!.reminders).toEqual(['未匹配'])
  })
  it('accepts explicit zero tax and free freight but reminds on null and pending confirmation', () => {
    expect(purchaseSalesReminders(product)).toEqual([])
    expect(purchaseSalesReminders({ ...product, taxPoint: null, singleFreightCny: null, weightG: 0 })).toEqual(['缺克重', '缺票点 / 待确认', '缺1件运费'])
    expect(purchaseSalesReminders({ ...product, invoiceType: '待确认' })).toEqual(['缺票点 / 待确认'])
    expect(purchaseSalesReminders({ ...product, freeShipping: '是', singleFreightCny: null })).toEqual([])
  })
  it('keeps legacy positive-price requirements and standard zero-price validity', () => {
    expect(purchaseSalesReminders({ ...product, purchasePriceCny: 0 })).toContain('缺采购价')
    expect(purchaseSalesReminders({ ...product, dataSource: 'standard', purchasePriceCny: 0 })).not.toContain('缺采购价')
  })
  it('combines owner and missing filters before pagination and refreshes after a repair', () => {
    const before = matchPurchaseSales(data([{ ...product, weightG: null, taxPoint: null }]))
    expect(filterPurchaseSales(before, '', '黄', ['缺克重', '缺票点 / 待确认'], true, false)).toHaveLength(1)
    expect(filterPurchaseSales(before, '', '杨', [], false, false)).toHaveLength(0)
    const after = matchPurchaseSales(data([{ ...product, taxPoint: null }]))
    expect(filterPurchaseSales(after, '', '黄', ['缺克重'], false, false)).toHaveLength(0)
    expect(filterPurchaseSales(after, '', '黄', ['缺票点 / 待确认'], false, false)).toHaveLength(1)
  })
  it('preserves conflicting sales totals and exports traceable safe CSV', () => {
    const input = data([product]); input.source.rows[0]!.total = 5; input.source.rows[0]!.salesDifference = -1
    input.source.rows[0]!.productName = '=unsafe()'
    const rows = matchPurchaseSales(input)
    expect(filterPurchaseSales(rows, '', '', [], false, true)).toHaveLength(1)
    const csv = salesCsv(rows)
    expect(csv).toContain('"5","6","\'-1"')
    expect(csv).toContain('"\'=unsafe()"')
    expect(csv).toContain('原采购表')
  })
})
