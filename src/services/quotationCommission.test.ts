import { describe, expect, it } from 'vitest'
import { applyCommissionThreshold, parseCommissionThreshold } from './quotationCommission'
import { quoteCnyFromUsd } from './quotationMoney'

describe('commission applied to final quotation', () => {
  it.each([[6, .95, 6.35], [5.7, .95, 6], [6.05, 1, 6.05], [0, .95, 0], [6.01, 1, 6.01], [1, .8, 1.25]])('%s / %s => %s', (price, divisor, expected) => {
    expect(applyCommissionThreshold(price, divisor)).toBe(expected)
  })
  it.each(['', ' ', 'abc', 'NaN', 'Infinity', '0x1', 0, -1, 1.01, NaN, Infinity, null, undefined, false])('rejects invalid threshold %s', value => {
    expect(parseCommissionThreshold(value)).toBeNull()
    expect(applyCommissionThreshold(6, value)).toBeNull()
  })
  it('accepts decimal entry and converts adjusted USD to CNY', () => {
    expect(parseCommissionThreshold('0.95')).toBe(.95)
    expect(quoteCnyFromUsd(applyCommissionThreshold(6, '.95')!, 6.7)).toBe(42.55)
    for (let i = 0; i < 3; i++) expect(applyCommissionThreshold(6, .95)).toBe(6.35)
  })
})
