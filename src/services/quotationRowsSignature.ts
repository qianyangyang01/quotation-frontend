import type { QuotationMatrixRow } from '@/components/quotation/types'

/** Compare the complete quote payload, independently of object property insertion order. */
export function quotationRowsSignature(rows: readonly QuotationMatrixRow[]): string {
  return JSON.stringify(rows, (_key, value: unknown) => {
    if (value !== null && typeof value === 'object' && !Array.isArray(value)) {
      const object = value as Record<string, unknown>
      return Object.fromEntries(Object.keys(object).sort().map(key => [key, object[key]]))
    }
    return value
  })
}