import { describe, expect, it } from 'vitest'
import { calculateLogisticsFee, findPriceRow, type LogisticsRule } from './logistics'
import { normalizeLogisticsPriceRow } from './logisticsRepository'
import { aggregateChangeSummary, batchComparisonSummary, buildEtaCorrections, changeImpact, completedBatchStage, diffKinds, formatTransferBytes, logisticsAdjustmentStatus, logisticsUploadError, rangeImpact, weightLabel, type Batch, type Diff, type Price } from './logisticsRebuild'

const makeRule = (rows: Parameters<typeof normalizeLogisticsPriceRow>[0][]): LogisticsRule => ({
  id: 9, name: '边界测试', englishName: '', type: '专线', currency: 'CNY', published: '', status: '启用', dates: '', users: '',
  relations: [], phoneRequired: false, areaCount: 1, priceRowCount: rows.length, prices: rows.map(normalizeLogisticsPriceRow),
})
const row = { areaName: '法国', countryCode: 'FR', registrationFee: 2 }
describe('rebuild pricing safety', () => {
  it('does not label unchanged imports as awaiting review', () => {
    const batch = (statuses: string[]) => ({ status: 'completed', payload: { results: statuses.map(status => ({ status })) } }) as Batch
    expect(completedBatchStage(batch(['unchanged']))).toBe('无需审核')
    expect(completedBatchStage(batch(['draft', 'unchanged']))).toBe('待审核')
    expect(completedBatchStage(batch(['blocked']))).toBe('存在阻断，请核对')
    expect(completedBatchStage(batch(['draft', 'blocked']))).toBe('存在阻断，请核对')
    const failedFile = batch(['unchanged']); failedFile.payload.fileReports = [{ fileName: '损坏.xlsx', status: 'failed' }]
    expect(completedBatchStage(failedFile)).toBe('存在文件解析失败或新模板待适配，请核对')
    const filtered = batch([]); filtered.payload.fileReports = [{ fileName: '首重续重.xlsx', status: 'filtered' }]
    expect(completedBatchStage(filtered)).toBe('首重续重已过滤，无需审核')
  })
  it('preserves the confirmed 200g/201g boundary through API normalization', () => {
    const rule = makeRule([{ ...row, weightFromKg: 0, weightToKg: 0.2, pricePerKg: 10 }, { ...row, weightFromKg: 0.201, weightToKg: 0.5, weightFromInclusive: true, pricePerKg: 20 }])
    expect(findPriceRow(rule, 'FR', 0.2)?.pricePerKg).toBe(10)
    expect(findPriceRow(rule, 'FR', 0.201)?.pricePerKg).toBe(20)
    expect(findPriceRow(rule, 'FR', 0.2005)).toBeUndefined()
    expect(weightLabel(rule.prices[1])).toBe('[201, 500] g')
  })
  it('does not change a strict upper limit into an inclusive limit', () => {
    const rule = makeRule([{ ...row, weightFromKg: 0, weightToKg: 0.5, weightToInclusive: false, pricePerKg: 10 }])
    expect(findPriceRow(rule, 'FR', 0.5)).toBeUndefined()
    expect(findPriceRow(rule, 'FR', 0.499)).toBeDefined()
  })
  it('never quotes pending rows and honors explicit actual-weight-only rules', () => {
    const pending = makeRule([{ ...row, weightFromKg: 0, weightToKg: 10, pricePerKg: 10, quoteReady: false }])
    expect(calculateLogisticsFee(pending, 'FR', 1)).toBeNull()
    const actual = makeRule([{ ...row, weightFromKg: 0, weightToKg: 10, pricePerKg: 10, volumetric: false, volumeDivisor: 8000 }])
    expect(calculateLogisticsFee(actual, 'FR', 1, [], { lengthCm: 40, widthCm: 30, heightCm: 20, volumeMultiplier: 2 })?.total).toBe(12)
  })
  it('rejects historical first-next rules even with verified billing', () => {
    const firstNext = makeRule([{ ...row, registrationFee: 0, weightFromKg: 0, weightToKg: 2, firstWeightKg: 0.5, firstWeightPrice: 35, nextWeightKg: 0.1, nextWeightPrice: 5 }])
    expect(calculateLogisticsFee(firstNext, 'FR', 0.8)).toBeNull()
    firstNext.billingVerified = true
    expect(calculateLogisticsFee(firstNext, 'FR', 0.801)).toBeNull()
    expect(findPriceRow(firstNext, 'FR', 0.8)).toBeUndefined()
  })
  it('aggregates overlapping change categories and keeps legacy diff compatibility', () => {
    expect(aggregateChangeSummary([
      { summary: { added: 1, price: 2, rule: 1, range: 1, removed: 0, coverageReduced: 1 } },
      { summary: { added: 0, price: 1, rule: 0, removed: 2 } },
    ])).toEqual({ added: 1, price: 3, rule: 1, range: 1, removed: 2, coverageReduced: 1 })
    const legacy = { type: 'price', changes: [], row: { ...row, weightFromKg: 0, weightToKg: 1 }, key: 'legacy' } as Diff
    expect(diffKinds(legacy)).toEqual(['price'])
    expect(diffKinds({ ...legacy, type: 'range', kinds: ['range', 'price', 'rule'] })).toEqual(['range', 'price', 'rule'])
  })
  it('describes price and weight-range impact for review', () => {
    const base = { key: 'range', type: 'range', changes: [], previous: { ...row, weightFromKg: 0, weightToKg: 0.1 }, row: { ...row, weightFromKg: 0, weightToKg: 0.2 } } as Diff
    expect(rangeImpact(base)).toBe('覆盖范围扩大')
    expect(rangeImpact({ ...base, previous: { ...row, weightFromKg: 0, weightToKg: 0.2 }, row: { ...row, weightFromKg: 0.05, weightToKg: 0.15 } })).toBe('覆盖范围缩小')
    expect(changeImpact({ field: '运费单价', kind: 'price', before: 44, after: 49, delta: 5, percentChange: 11.3636 })).toBe('CNY +5.00 · +11.36%')
  })
  it('reduces channel adjustment state to published or pending without hiding new work', () => {
    const channel = { id: 'channel-1', currentVersionId: 'published-1' }
    expect(logisticsAdjustmentStatus(channel, [{ channelId: 'channel-1', status: 'published' }])).toBe('published')
    expect(logisticsAdjustmentStatus({ ...channel, currentVersionId: null }, [])).toBe('pending')
    expect(logisticsAdjustmentStatus(channel, [{ channelId: 'channel-1', status: 'published' }, { channelId: 'channel-1', status: 'draft' }])).toBe('pending')
    expect(logisticsAdjustmentStatus(channel, [{ channelId: 'channel-1', status: 'published' }], true)).toBe('pending')
    expect(logisticsAdjustmentStatus(channel, [{ channelId: 'channel-1', status: 'published' }], false)).toBe('published')
  })
  it('explains initial imports and suspicious full replacement summaries', () => {
    expect(batchComparisonSummary({ providerName: '递四方', channelName: 'OH', status: 'draft', basePublishedVersionId: '', priceRows: 38, summary: { added: 38 } })).toContain('初次导入 38 条价格')
    expect(batchComparisonSummary({ providerName: '递四方', channelName: 'QC', status: 'draft', basePublishedVersionId: 'old', priceRows: 70, summary: { added: 70, removed: 70, price: 0, rule: 0, range: 0 } })).toContain('没有匹配上')
  })
  it('accepts a larger multi-file batch while enforcing safe per-file and batch limits', () => {
    const file = (name: string, size: number) => ({ name, size }) as File
    expect(logisticsUploadError(Array.from({ length: 4 }, (_, index) => file(`物流商${index}.xlsx`, 90 * 1024 * 1024)))).toBe('')
    expect(logisticsUploadError([file('过大.xlsx', 101 * 1024 * 1024)])).toBe('单个物流文件不能超过100MB')
    expect(logisticsUploadError(Array.from({ length: 6 }, (_, index) => file(`物流商${index}.xlsx`, 90 * 1024 * 1024)))).toBe('同一批次文件总大小不能超过500MB')
    expect(logisticsUploadError([file('错误.pdf', 1024)])).toContain('.xls')
  })
  it('formats upload sizes and speeds for progress display', () => {
    expect(formatTransferBytes(512)).toBe('512 B')
    expect(formatTransferBytes(1.5 * 1024 * 1024)).toBe('1.5 MB')
    expect(formatTransferBytes(2 * 1024 * 1024 * 1024)).toBe('2.00 GB')
  })
  it('submits one ETA correction per route and rejects incomplete ranges', () => {
    const snapshot = [{ ...row, rowKey: 'a', routeKey: 'route', weightFromKg: 0, weightToKg: 1 }, { ...row, rowKey: 'b', routeKey: 'route', weightFromKg: 1, weightToKg: 2 }] as Price[]
    const edited = snapshot.map(item => ({ ...item, etaMinDays: 7, etaMaxDays: 15 }))
    expect(buildEtaCorrections(edited, snapshot)).toEqual([{ routeKey: 'route', etaMinDays: 7, etaMaxDays: 15 }])
    expect(() => buildEtaCorrections([{ ...edited[0], etaMaxDays: 6 }], snapshot)).toThrow('时效必须填写有效的最早和最晚天数')
  })
})
