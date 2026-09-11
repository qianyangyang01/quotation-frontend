import Decimal from 'decimal.js'

// Keep calculation settings local; never change other consumers' Decimal defaults.
export const QuoteDecimal = Decimal.clone({ precision: 40, rounding: Decimal.ROUND_HALF_UP })

export function decimal(value: number | string) {
  return new QuoteDecimal(value)
}

export function sumDecimal(...values: number[]) {
  return values.reduce((sum, value) => sum.plus(value), decimal(0)).toNumber()
}

export function productDecimal(...values: number[]) {
  return values.reduce((product, value) => product.times(value), decimal(1)).toNumber()
}

export function displayWeightGrams(weightKg: number) {
  return decimal(Number.isFinite(weightKg) ? weightKg : 0).times(1000).ceil().toNumber()
}

export function gramsToKg(grams: number) {
  return decimal(Number.isFinite(grams) ? Math.max(0, grams) : 0).div(1000).toNumber()
}
