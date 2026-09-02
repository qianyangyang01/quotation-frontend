import { describe, expect, it } from 'vitest'
import { clampLogisticsPage, logisticsPageFromQuery, logisticsPageNumbers, logisticsPageQuery, logisticsPageSize } from './logisticsPagination'

describe('logistics pagination', () => {
  it('keeps a five-page window around the current page', () => {
    expect(logisticsPageNumbers(0, 10)).toEqual([1, 2, 3, 4, 5])
    expect(logisticsPageNumbers(5, 10)).toEqual([4, 5, 6, 7, 8])
    expect(logisticsPageNumbers(9, 10)).toEqual([6, 7, 8, 9, 10])
  })

  it('handles short and empty result sets', () => {
    expect(logisticsPageNumbers(0, 0)).toEqual([1])
    expect(logisticsPageNumbers(1, 3)).toEqual([1, 2, 3])
    expect(clampLogisticsPage(9, 3)).toBe(2)
    expect(clampLogisticsPage(-1, 0)).toBe(0)
  })

  it('accepts only the 20, 50 and 100 page sizes', () => {
    expect([20, 50, 100].map(logisticsPageSize)).toEqual([20, 50, 100])
    expect(logisticsPageSize(10)).toBe(20)
    expect(logisticsPageSize('invalid')).toBe(20)
  })

  it('round-trips the one-based URL page without changing the API page', () => {
    expect(logisticsPageFromQuery('7')).toBe(6)
    expect(logisticsPageFromQuery('0')).toBe(0)
    expect(logisticsPageQuery(6, 50)).toEqual({ page: '7', size: '50' })
  })
})
