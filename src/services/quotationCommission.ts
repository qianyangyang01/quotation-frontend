import { decimal } from './quotationDecimal'
import { roundQuoteUsd } from './quotationMoney'

export const COMMISSION_THRESHOLD_ERROR = '佣金阈值必须大于0且不超过1'

export function parseCommissionThreshold(value: unknown): number | null {
  if (typeof value !== 'number' && typeof value !== 'string') return null
  if (typeof value === 'string' && !/^(?:\d+(?:\.\d*)?|\.\d+)$/.test(value.trim())) return null
  const number = Number(value)
  return Number.isFinite(number) && number > 0 && number <= 1 ? number : null
}

// Apply exactly once, after the existing final USD calculation, never to a cached adjusted price.
export function applyCommissionThreshold(originalUsd: number, threshold: unknown): number | null {
  const divisor = parseCommissionThreshold(threshold)
  if (divisor == null) return null
  if (divisor === 1) return originalUsd
  const result = roundQuoteUsd(decimal(originalUsd).div(divisor).toNumber())
  return Number.isFinite(result) ? result : null
}
