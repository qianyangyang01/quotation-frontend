import { describe, expect, it } from 'vitest'
import { priceUpdateBatches, priceUpdateGroups, updateAmount, updateCounts, updatePercent } from './logisticsUpdateOverview'
import type { HistoryChannel, PreparedPrice, PublishedPriceVersion, RealPriceSnapshot } from './logisticsRealPriceHistory'

const point = (price: number | null, reason = ''): PreparedPrice => ({ price, rate: price, fee: 0, chargeWeight: 1, model: 'per-kg', intervalPrice: null, reason })
const channel = (id: string, overrides: Partial<HistoryChannel> = {}): HistoryChannel => ({ id, ruleId: 1, provider: '测试商', code: id, name: id, attribute: '普货', enabled: true, quoteReady: true, current: id + '-2', ...overrides })
const version = (id: string, number: number, price: number | null, overrides: Partial<PublishedPriceVersion> = {}): PublishedPriceVersion => ({ id: `${id}-${number}`, channel: id, number, status: number === 1 ? 'superseded' : 'published', publishedAt: `2026-11-0${number}T02:00:00Z`, fileName: '同名价格.xlsx', rows: [], preparedPrices: { 'DE|1': { 全国统一: point(price) } }, ...overrides })
const snapshot = (channels: HistoryChannel[], versions: PublishedPriceVersion[]): RealPriceSnapshot => ({ capturedAt: '2026-11-03T01:00:00Z', dataset: 'test', channels, versions })

describe('published price update overview', () => {
  it('groups only confirmed import IDs, never filenames or coincident timestamps, and excludes drafts', () => {
    const data = snapshot([channel('A'), channel('B'), channel('C')], [version('A', 1, 53), version('B', 1, 75), version('C', 1, 10), version('A', 2, 67, { importBatchId: 'batch' }), version('B', 2, 73, { importBatchId: 'batch' }), version('C', 2, 13), version('C', 3, 100, { status: 'draft', importBatchId: 'batch' })])
    const original = JSON.stringify(data)
    const batches = priceUpdateBatches(data, 'DE', 1, '普货/服装')
    const batch = batches.find(b => b.id === 'import:batch')!
    expect(batch.rows.map(row => row.channel.id)).toEqual(['A', 'B'])
    expect(updateAmount(batch.rows[0]!)).toBe('涨价 14.00 元/票')
    expect(updatePercent(batch.rows[0]!)).toBe('+26.4%')
    expect(updateAmount(batch.rows[1]!)).toBe('降价 2.00 元/票')
    expect(batches.some(b => b.id === 'version:C-2')).toBe(true)
    expect(JSON.stringify(data)).toBe(original)
  })
  it('compares the selected historical publication, not each channel’s latest price', () => {
    const data = snapshot([channel('A', { current: 'A-3' })], [version('A', 1, 53), version('A', 2, 67), version('A', 3, 100)])
    const row = priceUpdateBatches(data, 'DE', 1).find(b => b.id === 'version:A-2')!.rows[0]!
    expect([row.before?.price, row.after.price, row.change]).toEqual([53, 67, 14])
  })
  it('respects explicit baselines and does not bridge a missing or unpriceable version', () => {
    const data = snapshot([channel('A', { current: 'A-4' })], [version('A', 1, 10), version('A', 2, null), version('A', 3, 30, { baseVersionId: 'A-2' }), version('A', 4, 40, { baseVersionId: 'missing' })])
    const batches = priceUpdateBatches(data, 'DE', 1)
    expect(batches.find(b => b.id === 'version:A-3')!.rows[0]).toMatchObject({ change: null, status: 'unavailable' })
    expect(batches.find(b => b.id === 'version:A-4')!.rows[0]).toMatchObject({ change: null, status: 'unavailable', reason: '更新前版本未保留，无法计算差额' })
  })
  it('retains independent regions, first/add/remove and unavailable cases without inventing zero prices', () => {
    const data = snapshot([channel('A')], [version('A', 1, 5, { preparedPrices: { 'DE|1': { '一区': point(5), '二区': point(8), '五区': point(null, '重量不适用') } } }), version('A', 2, 10, { preparedPrices: { 'DE|1': { '一区': point(6), '三区': point(9), '五区': point(20) } } })])
    const rows = priceUpdateBatches(data, 'DE', 1).find(b => b.id === 'version:A-2')!.rows
    expect(rows.find(r => r.region === '一区')).toMatchObject({ change: 1, status: 'up' })
    expect(rows.find(r => r.region === '二区')).toMatchObject({ change: null, status: 'removed', after: { price: null } })
    expect(rows.find(r => r.region === '三区')).toMatchObject({ change: null, status: 'added' })
    expect(rows.find(r => r.region === '五区')).toMatchObject({ change: null, status: 'unavailable' })
    expect(priceUpdateBatches(data, 'DE', 1).find(b => b.id === 'version:A-1')!.rows.some(r => r.region === '三区')).toBe(false)
    expect(updateCounts(rows)).toMatchObject({ up: 1, down: 0, added: 1, removed: 1, unavailable: 1 })
  })
  it('shows disabled retained channels, keeps rollbacks independent and handles a zero baseline', () => {
    const data = snapshot([channel('A', { enabled: false, quoteReady: false })], [version('A', 1, 0, { importBatchId: 'old' }), version('A', 2, 3, { importBatchId: 'old', rollbackFromVersionId: 'A-1' })])
    const row = priceUpdateBatches(data, 'DE', 1)[0]!.rows[0]!
    expect(row).toMatchObject({ change: 3, percent: null, rollback: true })
    expect(priceUpdateBatches(data, 'DE', 1)[0]!.id).toBe('version:A-2')
    expect(priceUpdateBatches(data, 'DE', 1, '带电')).toEqual([])
  })
  it('uses the selected weight, includes channels outside the shortlist and rounds money before classifying changes', () => {
    const data = snapshot([channel('unlisted')], [version('unlisted', 1, 0.1, { preparedPrices: { 'DE|2': { 全国统一: point(0.1) } } }), version('unlisted', 2, 0.3, { preparedPrices: { 'DE|2': { 全国统一: point(0.3) } } })])
    expect(priceUpdateBatches(data, 'DE', 1)).toEqual([])
    expect(priceUpdateBatches(data, 'DE', 2)[0]!.rows[0]!.change).toBe(0.2)
    expect(updateAmount(priceUpdateBatches(data, 'DE', 2)[0]!.rows[0]!)).toBe('涨价 0.20 元/票')
  })
})

describe('four-hour update groups', () => {
  const imported = (id: string, time: string, operator = '物流甲') => ({ id, importedAt: `2026-11-02T${time}:00+08:00`, operator })
  function dataFor(imports: NonNullable<RealPriceSnapshot['importBatches']>): RealPriceSnapshot {
    return { ...snapshot(imports.map(item => channel(item.id)), imports.flatMap(item => [version(item.id, 1, 10), version(item.id, 2, 14, { importBatchId: item.id })])), importBatches: imports }
  }
  it('uses each operator’s first import, includes 3:59, splits exactly at 4:00 and never rolls the end forward', () => {
    const data = dataFor([imported('a', '08:00'), imported('b', '11:59'), imported('c', '12:00'), imported('d', '15:59'), imported('e', '16:00'), imported('other', '09:00', '物流乙')])
    const groups = priceUpdateGroups(data, 'DE', 1).filter(group => group.windowStartAt)
    expect(groups).toHaveLength(4)
    expect(groups.find(group => group.id === 'window:a')!.imports.map(item => item.id)).toEqual(['a', 'b'])
    expect(groups.find(group => group.id === 'window:c')!.imports.map(item => item.id)).toEqual(['c', 'd'])
    expect(groups.find(group => group.id === 'window:other')!.operator).toBe('物流乙')
    expect(groups.find(group => group.id === 'window:a')!.windowEndAt).toBe('2026-11-02T04:00:00.000Z')
  })
  it('anchors with unpublished imports and keeps membership stable across filters, refreshes and delayed publication', () => {
    const data = dataFor([imported('anchor', '08:00'), imported('visible', '11:59'), imported('next', '12:00')])
    data.versions = data.versions.filter(item => item.channel !== 'anchor')
    data.channels = data.channels.filter(item => item.id !== 'anchor')
    data.versions.find(item => item.id === 'visible-2')!.publishedAt = '2026-11-07T01:00:00Z'
    data.versions.find(item => item.id === 'visible-2')!.preparedPrices!['DE|2'] = { 全国统一: point(25) }
    const group = priceUpdateGroups(data, 'DE', 1, '普货/服装').find(item => item.id === 'window:anchor')!
    expect(group.imports.map(item => item.id)).toEqual(['anchor', 'visible'])
    expect(group.batches.map(item => item.importBatchId)).toEqual(['visible'])
    expect(priceUpdateGroups(data, 'DE', 2)[0]!.id).toBe('window:anchor')
    const nextData = { ...data, versions: [...data.versions, version('anchor', 2, 17, { importBatchId: 'anchor' })], channels: [...data.channels, channel('anchor')] }
    expect(priceUpdateGroups(nextData, 'DE', 1).find(item => item.id === 'window:anchor')!.batches).toHaveLength(2)
  })
  it('keeps each original channel update, its predecessor and batch trace when a channel is updated twice within one window', () => {
    const data = { ...snapshot([channel('A', { current: 'A-3' })], [version('A', 1, 53), version('A', 2, 67, { importBatchId: 'first' }), version('A', 3, 66, { importBatchId: 'second', baseVersionId: 'A-2' })]), importBatches: [imported('first', '08:00'), imported('second', '10:00')] }
    const group = priceUpdateGroups(data, 'DE', 1).find(item => item.id === 'window:first')!
    expect(group.rows.map(row => [row.importBatchId, row.before?.price, row.after.price, row.change])).toEqual([['first', 53, 67, 14], ['second', 67, 66, -1]])
    expect(group.batches).toHaveLength(2)
  })
  it('uses a full four-hour duration even across midnight', () => {
    const data = dataFor([{ id: 'late', importedAt: '2026-11-01T23:00:00+08:00', operator: '物流甲' }, imported('early', '02:59'), imported('boundary', '03:00')])
    expect(priceUpdateGroups(data, 'DE', 1).find(item => item.id === 'window:late')!.imports.map(item => item.id)).toEqual(['late', 'early'])
    expect(priceUpdateGroups(data, 'DE', 1).find(item => item.id === 'window:boundary')!.imports).toHaveLength(1)
  })
  it('does not merge legacy imports with missing identity or assign rollbacks to old import windows', () => {
    const data = dataFor([imported('unknown', '08:00', ''), imported('known', '09:00')])
    data.versions.push(version('known', 3, 10, { importBatchId: 'known', rollbackFromVersionId: 'known-1' }))
    const groups = priceUpdateGroups(data, 'DE', 1)
    expect(groups.find(group => group.id === 'import:unknown')!.windowStartAt).toBeNull()
    expect(groups.find(group => group.id === 'version:known-3')!.windowStartAt).toBeNull()
  })
})
