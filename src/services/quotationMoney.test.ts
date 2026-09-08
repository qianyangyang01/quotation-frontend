import { describe, expect, it } from 'vitest'
import { roundQuoteUsd, quoteCnyFromUsd } from './quotationMoney'
describe('final quote currency policy', () => {
  it.each([[6.01,6.05],[6.04,6.05],[6.05,6.05],[6.06,6.10],[6.09,6.10],[6.99,7],[7,7],[0,0],[6.050000000000001,6.05]])('rounds %s to %s', (input, expected) => expect(roundQuoteUsd(input)).toBe(expected))
  it('converts the adjusted USD using decimal half-up cents', () => {
    expect(quoteCnyFromUsd(roundQuoteUsd(6.01),6.7)).toBe(40.54)
    expect(quoteCnyFromUsd(6.10,6.7)).toBe(40.87)
  })
})
