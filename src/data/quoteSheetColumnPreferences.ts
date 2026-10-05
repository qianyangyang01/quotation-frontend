import { normalizeQuoteSheetOrder, type QuoteSheetColumnKey } from './customerQuoteSheet'

const storageKey = (userId: string) => `milano.quote-sheet-column-order.v1:${encodeURIComponent(userId)}`
const zonesKey = (userId: string) => `milano.quote-sheet-australia-zones.v1:${encodeURIComponent(userId)}`
export function loadQuoteSheetAustraliaZones(userId: string): boolean {
  try { return !userId || localStorage.getItem(zonesKey(userId)) !== 'false' } catch { return true }
}
export function saveQuoteSheetAustraliaZones(userId: string, visible: boolean): boolean {
  if (!userId) return false
  try { localStorage.setItem(zonesKey(userId), String(visible)); return true } catch { return false }
}

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
