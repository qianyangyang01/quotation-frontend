import { describe, expect, it } from 'vitest'
import { weightedPricePeriod } from './logisticsPriceTrendAnalytics'

describe('weighted price history', () => {
  it('includes the end date, carries the prior price, and excludes later updates', () => {
    const result = weightedPricePeriod([
      { date: '2024-12-30', rate: 20, fee: 10 },
      { date: '2025-01-03', rate: 40, fee: 10 },
      { date: '2025-01-04', rate: 900, fee: 10 },
    ], 0.5, '2025-01-01', '2025-01-03')!
    expect(result.days).toBe(3)
    expect(result.base).toBe(20)
    expect(result.avg).toBeCloseTo(70 / 3)
    expect(result.percent).toBeCloseTo(100 / 6)
  })
  it('uses all 639 calendar days from last New Year through October 1', () => {
    const history = [{ date: '2025-01-01', rate: 10, fee: 5 }, { date: '2026-10-01', rate: 20, fee: 5 }]
    expect(weightedPricePeriod(history, 1, '2025-01-01', '2026-10-01')).toMatchObject({ days: 639, base: 15 })
    expect(weightedPricePeriod(history, 1, '2025-01-01', '2026-10-01')!.avg).toBeCloseTo((638 * 15 + 25) / 639)
  })
  it('does not invent prices before coverage begins or across an unavailable interval', () => {
    expect(weightedPricePeriod([{ date: '2025-02-01', rate: 10, fee: 5 }], 1, '2025-01-01', '2025-03-01')).toBeNull()
    expect(weightedPricePeriod([{ date: '2025-01-01', rate: 10, fee: 5 }, { date: '2025-01-03', rate: null, fee: 0 }, { date: '2025-01-04', rate: 20, fee: 5 }], 1, '2025-01-01', '2025-01-05')).toBeNull()
  })
  it('rejects ambiguous same-day versions and invalid dates', () => {
    expect(weightedPricePeriod([{ date: '2025-02-30', rate: 10, fee: 5 }], 1, '2025-02-01', '2025-03-01')).toBeNull()
    expect(weightedPricePeriod([{ date: '2025-01-01', rate: 10, fee: 5 }, { date: '2025-01-01', rate: 20, fee: 5 }], 1, '2025-01-01', '2025-01-02')).toBeNull()
  })
  it('handles leap days and a zero baseline without an infinite percentage', () => {
    expect(weightedPricePeriod([{ date: '2024-02-28', rate: 0, fee: 0 }], 1, '2024-02-28', '2024-03-01')).toMatchObject({ days: 3, avg: 0, percent: null })
  })
})
