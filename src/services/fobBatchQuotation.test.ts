import { describe, expect, it } from 'vitest'
import { parseFobBatch, fobBatchIssue, fobBatchSnapshot, fobBatchLookupError, fobBatchQuantity } from './fobBatchQuotation'
import { FOB_SMALL_ORDER_POLICY, type FobQuoteProduct } from './fobQuotation'
import { ApiError } from './http'
import { fobSheetPrintHtml, fobSheetWorkbook } from './fobBatchExports'
import JSZip from 'jszip'
export const batchProduct = (sku = 'PF2600274'): FobQuoteProduct => ({ sku, source: 'fob', category: '日用品', weight: '50g', updatedAt: '2026-10-11T00:00:00Z', notices: [], parsed: { minOrderQty: 3, orderMultiple: 1, priceTiers: [{ minQty: 3, maxQty: null, unitPriceCny: 3.9, unit: '件' }], freight: { quantity: 100, totalFreightCny: 17, unitFreightCny: .17, basis: '100件运费17÷100', estimated: false } } })
const meta = { title: 'JerryFulfillment Quote Sheet', agent: '业务员', date: '2026-10-11', whatsapp: '' }
function ready(text: string) { return parseFobBatch(text).map(l => ({ ...l, product: batchProduct(l.sku) })) }
describe('FOB batch paste and final pricing', () => {
  it('uses a valid quantity when the first tier cannot satisfy order multiples', () => {
    const product=batchProduct();product.parsed.orderMultiple=10
    product.parsed.priceTiers=[{minQty:3,maxQty:5,unitPriceCny:4,unit:'件'},{minQty:20,maxQty:null,unitPriceCny:3.9,unit:'件'}]
    expect(fobBatchQuantity(product,'','tiers')).toBe(20)
    expect(fobBatchQuantity(product,'30','quantity')).toBe(30)
  })
  it('preserves source line numbers, missing quantities and identifiers; normalizes separators', () => {
    expect(parseFobBatch('\n pf2600274  3\nPF2600166，40\r\nZS2600437\nX\t50').map(l => [l.id,l.sku,l.quantity])).toEqual([[2,'PF2600274','3'],[3,'PF2600166','40'],[4,'ZS2600437',''],[5,'X','50']])
    expect(parseFobBatch('ABC 10 pcs')[0]!.formatError).toContain('无法识别')
    expect(parseFobBatch('123456')[0]!.quantity).toBe('')
    expect(() => parseFobBatch(Array.from({length:101},(_,i)=>`S${i}`).join('\n'))).toThrow('未截断')
  })
  it('distinguishes missing, malformed and constrained quantities and allows quantity-less tiers', () => {
    for (const [quantity,message] of [['','请填写'],['0','正整数'],['-1','正整数'],['1.2','正整数'],['2','起订量'],['abc','正整数']]) {
      const rows=ready(`SKU ${quantity}`);expect(fobBatchIssue(rows[0]!,rows,'quantity',6.7,FOB_SMALL_ORDER_POLICY)).toContain(message)
    }
    const rows=ready('SKU');expect(fobBatchIssue(rows[0]!,rows,'tiers',6.7,FOB_SMALL_ORDER_POLICY)).toBe('')
    rows[0]!.quantity='4';rows[0]!.product.parsed.orderMultiple=3;expect(fobBatchIssue(rows[0]!,rows,'quantity',6.7,FOB_SMALL_ORDER_POLICY)).toContain('倍数')
  })
  it('does not silently merge duplicates or different quantity requests', () => {
    const rows=ready('SKU 3\nSKU 3\nSKU 45')
    expect(fobBatchIssue(rows[1]!,rows,'quantity',6.7,FOB_SMALL_ORDER_POLICY)).toContain('重复')
    expect(fobBatchIssue(rows[2]!,rows,'quantity',6.7,FOB_SMALL_ORDER_POLICY)).toBe('')
    rows[0]!.selected=false;expect(fobBatchIssue(rows[1]!,rows,'quantity',6.7,FOB_SMALL_ORDER_POLICY)).toBe('')
  })
  it('creates one immutable twenty-item snapshot using final prices, including threshold splits', () => {
    const rows=ready(Array.from({length:20},(_,i)=>`SKU${i} 3`).join('\n')), before=JSON.stringify(rows)
    const batch=fobBatchSnapshot(rows,'quantity',6.7,FOB_SMALL_ORDER_POLICY,meta)
    expect(batch.schemaVersion).toBe(2);expect(batch.items).toHaveLength(20);expect(batch.sheet.rows).toHaveLength(20)
    expect(batch.sheet.rows[0]!.prices).toEqual([.93,.95]);expect(new Set(batch.sheet.rows.map(r=>r.key)).size).toBe(20)
    expect(JSON.stringify(rows)).toBe(before)
    const tiers=fobBatchSnapshot(ready('SKU'),'tiers',6.7,FOB_SMALL_ORDER_POLICY,meta)
    expect(tiers.sheet.rows.map(r=>[r.quantityRange,r.prices])).toEqual([['3–44 pcs',[.93,.95]],['45+ pcs',[.76,.77]]])
  })
  it('never represents a timeout or permission problem as not-found', () => {
    expect(fobBatchLookupError(new ApiError('missing',404,'NOT_FOUND','x'))).toContain('未找到')
    expect(fobBatchLookupError(new DOMException('timeout','TimeoutError'))).toContain('不代表SKU不存在')
    expect(fobBatchLookupError(new ApiError('bad',403,'DENIED','x'))).toContain('权限')
  })
  it('exports only customer prices and escapes untrusted text in print and spreadsheet output', async () => {
    const batch=fobBatchSnapshot(ready('SKU 3'),'quantity',6.7,FOB_SMALL_ORDER_POLICY,{...meta,title:'<script>alert(1)</script>'})
    const html=fobSheetPrintHtml(batch.sheet);expect(html).not.toContain('<script>');expect(html).not.toContain('purchaseCny');expect(html).toContain('0.93')
    const blob=await fobSheetWorkbook(batch.sheet),zip=await JSZip.loadAsync(await blob.arrayBuffer()),sheet=await zip.file('xl/worksheets/sheet1.xml')!.async('string')
    expect(sheet).toContain('t="inlineStr"');expect(sheet).toContain('&lt;script&gt;');expect(sheet).not.toContain('purchaseCny');expect(sheet).toContain('0.93')
  })
})
