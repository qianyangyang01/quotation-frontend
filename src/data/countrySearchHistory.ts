export type CountrySearchEntry = { code: string; count: number; lastSearchedAt: number }
const storageKey = (userId: string) => `milano.country-search-history.v1:${encodeURIComponent(userId)}`

export function loadCountrySearchHistory(userId: string): CountrySearchEntry[] {
  if (!userId) return []
  try {
    const stored: unknown = JSON.parse(localStorage.getItem(storageKey(userId)) ?? '[]')
    if (!Array.isArray(stored)) return []
    const seen = new Set<string>()
    return stored.filter((entry): entry is CountrySearchEntry => {
      if (!entry || typeof entry !== 'object' || typeof entry.code !== 'string'
        || !/^[A-Z]{2}$/.test(entry.code) || !Number.isSafeInteger(entry.count) || entry.count < 1
        || !Number.isSafeInteger(entry.lastSearchedAt) || entry.lastSearchedAt < 0 || seen.has(entry.code)) return false
      seen.add(entry.code)
      return true
    })
  } catch { return [] }
}

export function saveCountrySearchHistory(userId: string, entries: CountrySearchEntry[]) {
  if (!userId) return
  try {
    if (entries.length) localStorage.setItem(storageKey(userId), JSON.stringify(entries))
    else localStorage.removeItem(storageKey(userId))
  } catch { /* Search remains usable when browser storage is unavailable. */ }
}

export function recordCountrySearch(entries: CountrySearchEntry[], code: string, now = Date.now()): CountrySearchEntry[] {
  const normalized = code.trim().toUpperCase()
  if (!/^[A-Z]{2}$/.test(normalized)) return entries
  const previous = entries.find(entry => entry.code === normalized)
  return [...entries.filter(entry => entry.code !== normalized), {
    code: normalized,
    count: Math.min((previous?.count ?? 0) + 1, Number.MAX_SAFE_INTEGER),
    lastSearchedAt: now,
  }]
}

export function rankCountrySearchHistory(entries: CountrySearchEntry[]): CountrySearchEntry[] {
  return entries.slice().sort((a, b) => b.count - a.count || b.lastSearchedAt - a.lastSearchedAt || a.code.localeCompare(b.code))
}
