import { decimal } from './quotationDecimal'

/** Display only: keep the original freight value for calculations and persistence. */
export function formatFobFreight(value: number): string {
  if (!Number.isFinite(value) || value < 0) return '—'
  const amount = decimal(value)
  const rounded = amount.toDecimalPlaces(4)
  if (amount.gt(0) && rounded.isZero()) return '<¥0.0001'
  const text = rounded.toFixed(Math.max(2, rounded.decimalPlaces()))
  return `${amount.eq(rounded) ? '' : '约'}¥${text}`
}
