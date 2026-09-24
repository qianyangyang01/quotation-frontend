import { normalizeQuoteSheetOrder, type QuoteSheetColumnKey } from './customerQuoteSheet'

const storageKey = (userId: string) => `milano.quote-sheet-column-order.v1:${encodeURIComponent(userId)}`

/** Layout belongs to the signed-in user, never to a quotation's salesperson. */
export function loadQuoteSheetColumnOrder(userId: string): QuoteSheetColumnKey[] {
  if (!userId) return normalizeQuoteSheetOrder()
  try {
    const stored: unknown = JSON.parse(localStorage.getItem(storageKey(userId)) ?? 'null')
    return normalizeQuoteSheetOrder(Array.isArray(stored) ? stored : [])
  } catch {
    return normalizeQuoteSheetOrder()
  }
}

export function saveQuoteSheetColumnOrder(userId: string, order: readonly QuoteSheetColumnKey[]): boolean {
  if (!userId) return false
  try {
    localStorage.setItem(storageKey(userId), JSON.stringify(normalizeQuoteSheetOrder(order)))
    return true
  } catch {
    return false
  }
}
