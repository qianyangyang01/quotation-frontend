import { describe, expect, it } from 'vitest'
import { mergeBatchSingle, parseBatchSingleSkus, type BatchSingleItem } from './batchSingleQuotation'

export function batchItem(sku: string, price = 5): BatchSingleItem {
  return {
    input: { quoteMode: 'single', primarySku: sku, customerName: '客户A', customerGrade: 'S级客户', exchangeRate: 6.7, commissionThreshold: 1,
      customerQuote: { quantities: [1,2,3], rows: [{ optionId: 'same-channel', prices: [price, price*2, price*3] }] } },
    conditions: { customerName: '客户A', skuSearch: sku },
    sheet: { agent: 'QA', date: '11 Oct 2026', quantityLabels: ['1 pc','2 pcs','3 pcs'], issues: [], rows: [{ key: 'same-channel', number: 1, sku, country: 'United States', provider: '4PX', shippingTime: '7-12 working days', prices: [price, price*2, price*3], sourceDescription: '' }] },
  } as unknown as BatchSingleItem // Minimal fixture: merge does not consume calculation-only fields.
}
describe('independent batch single quotation', () => {
  it('parses Excel lines and flags duplicates without silently discarding input', () => {
    const result = parseBatchSingleSkus('\uFEFF sku-a\r\nSKU-B\n sku-a\nSKU-C\t20')
    expect(result).toHaveLength(4)
    expect(result.map(r => r.selected)).toEqual([true,true,false,false])
    expect(result[2]!.error).toContain('重复')
    expect(() => parseBatchSingleSkus(Array.from({length:51}, (_,i)=>`SKU-${i}`).join('\n'))).toThrow('50')
  })
  it('puts independent SKU prices in one sheet without colliding channel identities or mutating inputs', () => {
    const items = [batchItem('A', 5),batchItem('B', 9)], before = JSON.stringify(items)
    const result = mergeBatchSingle(items)
    expect(result.items).toHaveLength(2)
    expect(result.sheet.rows.map(r => [r.key,r.number,r.sku,r.prices[0]])).toEqual([['0:same-channel',1,'A',5],['1:same-channel',2,'B',9]])
    result.sheet.rows[0]!.prices[0] = 99
    expect(JSON.stringify(items)).toBe(before)
  })
  it('blocks mixed modes, customers, quantities, duplicates and invalid sheets', () => {
    expect(() => mergeBatchSingle([])).toThrow()
    expect(() => mergeBatchSingle([batchItem('A'),batchItem('A')])).toThrow('重复')
    for (const modify of [(i:BatchSingleItem)=>i.input.quoteMode='bundle', (i:BatchSingleItem)=>i.input.customerName='B', (i:BatchSingleItem)=>i.input.exchangeRate=7, (i:BatchSingleItem)=>i.input.customerQuote!.quantities=[1,2,4], (i:BatchSingleItem)=>i.sheet.issues=['未完成']]) {
      const items=[batchItem('A'),batchItem('B')]; modify(items[1]!); expect(() => mergeBatchSingle(items)).toThrow()
    }
  })
  it('retains the last SKU at the 50 SKU limit', () => {
    const result=mergeBatchSingle(Array.from({length:50},(_,i)=>batchItem('SKU'+i, i+1)))
    expect(result.sheet.rows[49]).toMatchObject({sku:'SKU49',number:50,prices:[50,100,150]})
  })
})
