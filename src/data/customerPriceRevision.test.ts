import { describe, expect, it } from 'vitest'
import { customerPriceRevision } from './customerPriceRevision'
import { normalizeQuotationRecord } from './quotationRecords'

const record = () => normalizeQuotationRecord({ id: 'history-test', no: 'QT-history-test', quoteOptions: [
  { id: 'a', country: '美国', quoteRegion: '东部', carrier: '燕文', channel: '专线A', rule: '', eta: '', quote1Usd: 2, quote2Usd: 3, quote3Usd: null, quoteCustomUsd: 5 },
  { id: 'b', country: '美国', quoteRegion: '西部', carrier: '燕文', channel: '专线B', rule: '', eta: '', quote1Usd: 2, quote2Usd: 5, quote3Usd: null, quoteCustomUsd: 5 },
] })!
const snapshot = (prices: (number | null)[], quantities = [1, 2, 4], optionId = 'a') => JSON.stringify({ quantities, rows: [{ optionId, prices }] })

describe('readable customer price audit', () => {
  it('shows only changes to the previous customer price, independent of JSON property and quantity order', () => {
    const before = snapshot([1.8, 2.7, 4.6])
    const after = JSON.stringify({ rows: [{ prices: [4.6, 3, 2.6], optionId: 'a' }], quantities: [4, 1, 2] })
    expect(customerPriceRevision(record(), before, after).groups).toEqual([{ optionId: 'a', route: '美国 · 东部 · 燕文 · 专线A', changes: [
      { quantity: 1, label: '1件', before: '$1.80', after: '$3.00' },
      { quantity: 2, label: '2件', before: '$2.70', after: '$2.60' },
    ] }])
  })
  it('does not confuse reordering with a price edit', () => {
    expect(customerPriceRevision(record(), snapshot([1.8, 2.7, 4.6]), snapshot([4.6, 1.8, 2.7], [4, 1, 2]))).toEqual({ groups: [], note: '本次未改变客户报价金额（仅调整排列或保存格式）。' })
  })
  it('distinguishes blank and zero amounts from added and removed columns', () => {
    const result = customerPriceRevision(record(), snapshot([1.8, null, 4.6]), snapshot([null, 0, null], [1, 2, 8]))
    expect(result.groups[0].changes).toEqual([
      { quantity: 1, label: '1件', before: '$1.80', after: '未报价' },
      { quantity: 2, label: '2件', before: '未报价', after: '$0.00' },
      { quantity: 4, label: '4件', before: '$4.60', after: '已移除' },
      { quantity: 8, label: '8件', before: '未设置', after: '未报价' },
    ])
  })
  it('matches multiple routes with the same provider by ID and supports bundle units', () => {
    const r = record(); r.quoteMode = 'bundle'
    const before = JSON.stringify({ quantities: [2], rows: [{ optionId: 'a', prices: [3] }, { optionId: 'b', prices: [5] }] })
    const after = JSON.stringify({ quantities: [2], rows: [{ optionId: 'b', prices: [4] }, { optionId: 'a', prices: [3] }] })
    const result = customerPriceRevision(r, before, after)
    expect(result.groups).toHaveLength(1)
    expect(result.groups[0]).toMatchObject({ optionId: 'b', route: '美国 · 西部 · 燕文 · 专线B', changes: [{ label: '2套', before: '$5.00', after: '$4.00' }] })
  })
  it.each(['', 'not-json', '{"rows":[]}', snapshot([1], [1, 1]), '{"quantities":[1],"rows":[{"optionId":"a","prices":[1]},{"optionId":"a","prices":[2]}]}'])('shows a safe explanation for incomplete historical values: %s', text => {
    const result = customerPriceRevision(record(), text, snapshot([1.8, 2.6, 4.6]))
    expect(result.groups).toEqual([])
    expect(result.note).toContain('无法还原修改前后金额')
    expect(result.note).not.toContain('{')
  })
  it('retains changes for removed routes without displaying an internal channel ID', () => {
    const result = customerPriceRevision(record(), snapshot([2], [0], 'removed-internal-id'), snapshot([3], [0], 'removed-internal-id'))
    expect(result.groups[0].route).toBe('历史渠道 1（当前记录已无渠道名称）')
    expect(result.groups[0].changes[0]).toMatchObject({ label: '自定义数量', before: '$2.00', after: '$3.00' })
  })
})
