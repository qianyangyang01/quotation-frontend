import { describe, expect, it } from 'vitest'
import { calculateLogisticsFee, findPriceRow, type LogisticsRule } from './logistics'
import { normalizeLogisticsPriceRow } from './logisticsRepository'
import { weightLabel } from './logisticsRebuild'

const makeRule = (rows: Parameters<typeof normalizeLogisticsPriceRow>[0][]): LogisticsRule => ({
  id: 9, name: '边界测试', englishName: '', type: '专线', currency: 'CNY', published: '', status: '启用', dates: '', users: '',
  relations: [], phoneRequired: false, areaCount: 1, priceRowCount: rows.length, prices: rows.map(normalizeLogisticsPriceRow),
})
const row = { areaName: '法国', countryCode: 'FR', registrationFee: 2 }
describe('rebuild pricing safety', () => {
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
})
