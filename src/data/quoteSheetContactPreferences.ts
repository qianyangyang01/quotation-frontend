export type QuoteSheetContactPreferences = { agent?: string; whatsapp?: string }
const storageKey = (userId: string) => `milano.quote-sheet-contact.v1:${encodeURIComponent(userId)}`

/** Defaults belong to the signed-in user, including when viewing another user's record. */
export function loadQuoteSheetContact(userId: string): QuoteSheetContactPreferences {
  if (!userId) return {}
  try {
    const stored: unknown = JSON.parse(localStorage.getItem(storageKey(userId)) ?? 'null')
    if (!stored || typeof stored !== 'object') return {}
    const values = stored as QuoteSheetContactPreferences
    return {
      ...(typeof values.agent === 'string' ? { agent: values.agent.slice(0, 40) } : {}),
      ...(typeof values.whatsapp === 'string' ? { whatsapp: values.whatsapp.slice(0, 40) } : {}),
    }
  } catch { return {} }
}

export function saveQuoteSheetContact(userId: string, values: QuoteSheetContactPreferences): boolean {
  if (!userId) return false
  try {
    localStorage.setItem(storageKey(userId), JSON.stringify({ ...loadQuoteSheetContact(userId), ...values }))
    return true
  } catch { return false }
}
