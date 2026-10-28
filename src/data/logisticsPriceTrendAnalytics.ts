/** Read-only price history calculations. No quotation or publication writes. */
export interface TrendRateVersion {
  date: string
  rate: number | null
  fee: number
}

export interface PriceTrendPreviewChannel {
  name: string
  cargo: string
  old: number
  now: number
  fee: number
  rates: number[]
  history: TrendRateVersion[]
}

const DAY = 86_400_000
export function calendarDay(value: string): number {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return NaN
  const time = Date.parse(`${value}T00:00:00Z`)
  return Number.isFinite(time) && new Date(time).toISOString().slice(0, 10) === value ? time : NaN
}

export function weightedPricePeriod(history: readonly TrendRateVersion[], weight: number, from: string, through: string) {
  const start = calendarDay(from), end = calendarDay(through) + DAY
  if (!Number.isFinite(start + end + weight) || weight <= 0 || end <= start) return null
  const versions = history.map(v => ({ ...v, time: calendarDay(v.date) })).sort((a, b) => a.time - b.time)
  if (versions.some((v, i) => !Number.isFinite(v.time) || (i > 0 && v.time === versions[i - 1]!.time))) return null
  const amount = (v: TrendRateVersion) => v.rate !== null && v.rate >= 0 && v.fee >= 0 && Number.isFinite(v.rate + v.fee) ? v.rate * weight + v.fee : null
  const baseVersion = versions.filter(v => v.time <= start).at(-1)
  const base = baseVersion ? amount(baseVersion) : null
  if (base === null) return null
  let sum = 0, covered = 0
  for (let i = 0; i < versions.length; i++) {
    const v = versions[i]!
    const days = (Math.min(end, versions[i + 1]?.time ?? end) - Math.max(start, v.time)) / DAY
    if (days <= 0) continue
    const price = amount(v)
    if (price === null) return null
    sum += price * days
    covered += days
  }
  const days = (end - start) / DAY
  if (covered !== days) return null
  const avg = sum / days, change = avg - base
  return { avg, base, change, percent: base === 0 ? null : change / base * 100, days }
}
