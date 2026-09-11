import { decimal } from './quotationDecimal'

// Existing snapshots may contain binary tails. Normalize only that transport
// noise; keep sub-cent values before applying the five-cent business ceiling.
export function roundQuoteUsd(value: number): number {
  return decimal(Math.max(0, value)).toSignificantDigits(15).div('0.05').ceil().times('0.05').toNumber()
}
export function quoteCnyFromUsd(usd: number, rate: number): number {
  return decimal(usd).toDecimalPlaces(2).times(rate).toDecimalPlaces(2).toNumber()
}
