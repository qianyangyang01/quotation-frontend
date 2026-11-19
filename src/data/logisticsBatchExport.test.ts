import { describe, expect, it, vi } from 'vitest'
import { batchExportFixture } from './logisticsBatchExport.fixture'
import { prepareBatchExport, type ExportPrice } from './logisticsBatchExport'
import { allCountryPriceUpdateGroups } from './logisticsUpdateOverview'
import type { RealPriceSnapshot } from './logisticsRealPriceHistory'
const group = (s: RealPriceSnapshot) => allCountryPriceUpdateGroups(s, 1).find(b => b.id === 'window:DEMO-20261118-01')!
const prepare = (s = batchExportFixture()) => prepareBatchExport(s, group(s), async () => { throw Error('Fixture must not access API') })
describe('batch Excel all-weight comparison', () => {
  it('exports every tier, addition, removal and mixed fee without changing source data', async () => {
    const s = batchExportFixture(), before = JSON.stringify(s), data = await prepare(s)
    expect(data.rows).toHaveLength(12)
    expect(data.rows.reduce((r, v) => { r[v.category]++;return r }, { up: 0, down: 0, same: 0, other: 0 })).toEqual({ up: 3, down: 3, same: 3, other: 3 })
    expect(data.rows.filter(r => r.country === '美国').map(r => r.weight)).toEqual(['1–500', '501–1000', '1001–2000'])
    expect(data.rows.find(r => r.country === '意大利')).toMatchObject({ category: 'other', status: '混合变化', rateDelta: 4, feeDelta: -2 })
    expect(data.rows.find(r => r.country === '法国')).toMatchObject({ status: '新增', before: undefined, rateDelta: null })
    expect(data.rows.find(r => r.country === '西班牙')).toMatchObject({ status: '移除', after: undefined })
    expect(JSON.stringify(s)).toBe(before)
  })
  it('includes a channel with only 5–10 kg tariffs, independently of 1 kg overview rows', async () => {
    const s = batchExportFixture();s.versions.filter(v => v.channel === 'US').forEach(v => { v.rows = [{ ...v.rows[0]!, weightFromKg: 5, weightToKg: 10 }] })
    const data = await prepare(s)
    expect(data.rows.find(r => r.country === '美国')?.weight).toBe('5001–10000')
  })
  it('aligns changed boundaries against the explicit baseline, not the latest version', async () => {
    const s = batchExportFixture(), old = s.versions.find(v => v.id === 'US-1')!, next = s.versions.find(v => v.id === 'US-2')!
    old.rows = [{ ...old.rows[0]!, weightToKg: 2, pricePerKg: 100 }]
    next.rows = [{ ...next.rows[0]!, weightToKg: 1, pricePerKg: 110 }, { ...next.rows[0]!, weightFromKg: 1, weightToKg: 2, pricePerKg: 90 }]
    s.versions.push({ ...next, id: 'US-3', number: 3, importBatchId: 'another', publishedAt: '2026-11-19T00:00:00Z', baseVersionId: next.id, rows: [{ ...next.rows[0]!, pricePerKg: 200 }] })
    const rows = (await prepare(s)).rows.filter(r => r.country === '美国')
    expect(rows.map(r => [r.weight, r.category, r.rateDelta])).toEqual([['1–1000', 'up', 10], ['1001–2000', 'down', -10]])
  })
  it('keeps both publications in a four-hour window, and never bridges a missing baseline', async () => {
    const s = batchExportFixture(), next = s.versions.find(v => v.id === 'US-2')!
    s.importBatches!.push({ id: 'second', importedAt: '2026-11-18T04:36:00Z', operator: '演示操作人' })
    s.versions.push({ ...next, id: 'US-3', number: 3, importBatchId: 'second', publishedAt: '2026-11-18T04:40:00Z', baseVersionId: next.id, rows: next.rows.map(r => ({ ...r, pricePerKg: r.pricePerKg - 10 })) })
    expect((await prepare(s)).rows.filter(r => r.country === '美国')).toHaveLength(6)
    next.baseVersionId = 'missing'
    expect((await prepare(s)).rows.filter(r => r.version.id === 'US-2').every(r => r.status === '无可比价' && !r.baseline)).toBe(true)
  })
  it('does not invent removed rows between 500 g and an inclusive 501 g lower bound', async () => {
    const s = batchExportFixture(), old = s.versions.find(v => v.id === 'US-1')!, next = s.versions.find(v => v.id === 'US-2')!
    old.rows = [{ ...old.rows[0]!, weightToKg: 2 }]
    next.rows = [{ ...next.rows[0]! }, { ...next.rows[1]!, weightFromKg: .501, weightFromInclusive: true, weightToKg: 2 }]
    const rows = (await prepare(s)).rows.filter(r => r.country === '美国')
    expect(rows.map(r => r.weight)).toEqual(['1–500', '501–2000'])
    expect(rows.some(r => r.status === '移除')).toBe(false)
  })
  it('marks minimum/rounding changes, ambiguity and currency changes separately', async () => {
    const s = batchExportFixture(), us = s.versions.find(v => v.id === 'US-2')!
    us.rows[0]!.minChargeWeightKg = .1
    us.rows.push({ ...us.rows[1]!, pricePerKg: 999 })
    const de = s.versions.find(v => v.id === 'DE-2')!;de.rows[0]!.currency = 'USD'
    const data = await prepare(s)
    expect(data.rows.some(r => r.status === '规则调整')).toBe(true)
    expect(data.rows.filter(r => r.status === '档位重叠')).toHaveLength(3)
    expect(data.rows.some(r => r.status === '币种变化')).toBe(true)
  })
  it('loads prepared versions once each, bounds concurrency, and fails closed on a missing read', async () => {
    const s = batchExportFixture(), b = group(s), raw = new Map(s.versions.map(v => [v.id, v.rows]))
    s.versions.forEach(v => { v.preparedPrices = {};v.rows = [] })
    let active = 0, max = 0
    const loader = vi.fn(async v => { active++;max = Math.max(max, active);await new Promise(r => setTimeout(r, 1));active--;return raw.get(v.id)! as ExportPrice[] })
    expect((await prepareBatchExport(s, b, loader)).rows).toHaveLength(12)
    expect(loader).toHaveBeenCalledTimes(12);expect(max).toBeLessThanOrEqual(3)
    await expect(prepareBatchExport(s, b, async () => { throw Error('missing') })).rejects.toThrow('missing')
    const controller = new AbortController();controller.abort()
    await expect(prepareBatchExport(s, b, loader, controller.signal)).rejects.toThrow()
  })
})
