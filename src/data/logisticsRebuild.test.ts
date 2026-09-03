import { describe, expect, it } from 'vitest'
import { calculateLogisticsFee, findPriceRow, type LogisticsRule } from './logistics'
import { normalizeLogisticsPriceRow } from './logisticsRepository'
import { aggregateChangeSummary, changeImpact, completedBatchStage, diffKinds, rangeImpact, weightLabel, type Batch, type Diff } from './logisticsRebuild'

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
    expect(completedBatchStage(failedFile)).toBe('存在文件解析失败，请核对')
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
  it('does not add an extra continuation unit from floating point error', () => {
    const firstNext = makeRule([{ ...row, registrationFee: 0, weightFromKg: 0, weightToKg: 2, firstWeightKg: 0.5, firstWeightPrice: 35, nextWeightKg: 0.1, nextWeightPrice: 5 }])
    expect(calculateLogisticsFee(firstNext, 'FR', 0.8)?.total).toBe(50)
    expect(calculateLogisticsFee(firstNext, 'FR', 0.801)?.total).toBe(55)
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
})
