// Apply five-cent rounding only to the final USD quotation after fees.
export function roundQuoteUsd(value: number): number {
  const scaled = Math.max(0, value) * 20
  return Math.max(0, Math.ceil(scaled - Number.EPSILON * Math.max(1, scaled) * 4)) / 20
}
export function quoteCnyFromUsd(usd: number, rate: number): number {
  const cents = Math.round(usd * 100) * rate
  return Math.round(cents + Number.EPSILON * Math.max(1, Math.abs(cents)) * 4) / 100
}
