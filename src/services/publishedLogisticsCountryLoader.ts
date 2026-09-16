import { countryIdentity, countryIdentityMatches } from '@/data/countryIdentity'
import type { LogisticsRule } from '@/data/logistics'
import { loadPublishedLogisticsManifest, loadPublishedLogisticsRules } from '@/data/publishedLogisticsRepository'

export type CountryRulesSnapshot = {
  attribute: string
  revision: string
  countries: string[]
  rules: LogisticsRule[]
}

function uniqueCountries(countries: string[]) {
  const seen = new Set<string>()
  return countries.filter(country => {
    const identity = countryIdentity(country)
    if (!identity || seen.has(identity)) return false
    seen.add(identity)
    return true
  })
}

/** Merge only within one verified publication snapshot; never mix version prices. */
export function mergeCountryRules(previous: LogisticsRule[], incoming: LogisticsRule[], countries: string[]) {
  const replacements = new Map(incoming.map(rule => [rule.id, rule]))
  const merged = previous.map(rule => {
    const next = replacements.get(rule.id)
    if (next && (rule.logisticsChannelId !== next.logisticsChannelId || rule.logisticsVersionId !== next.logisticsVersionId)) {
      throw new Error('物流渠道版本已变化，请重新加载')
    }
    replacements.delete(rule.id)
    const retained = rule.prices.filter(row => !countries.some(country => countryIdentityMatches(row.countryCode, row.areaName, country)))
    if (!next && retained.length === rule.prices.length) return rule
    const prices = [...retained, ...(next?.prices || [])]
    return { ...(next || rule), prices, priceRowCount: prices.length,
      areaCount: new Set(prices.map(row => countryIdentity(row.countryCode || row.areaName))).size,
      phoneRequired: prices.some(row => row.phoneRequired) }
  })
  return [...merged, ...replacements.values()].filter(rule => rule.prices.length).sort((a, b) => a.id - b.id)
}

export async function loadAdditionalCountryRules(snapshot: CountryRulesSnapshot, requested: string[], signal: AbortSignal) {
  const manifestResult = await loadPublishedLogisticsManifest({ signal, allowStale: false })
  signal.throwIfAborted()
  if (!manifestResult.verified) throw new Error('无法确认最新物流版本，请重试')
  const countries = uniqueCountries([...snapshot.countries, ...requested])
  const sameRevision = snapshot.revision === manifestResult.manifest.revision
  const loaded = new Set(snapshot.countries.map(countryIdentity))
  const query = sameRevision ? countries.filter(country => !loaded.has(countryIdentity(country))) : countries
  if (!query.length) return { ...snapshot, countries, verified: true }
  const result = await loadPublishedLogisticsRules({ attribute: snapshot.attribute, countries: query },
    { apply: false, signal, manifestResult })
  signal.throwIfAborted()
  if (!result.verified || result.revision !== manifestResult.manifest.revision) throw new Error('物流版本已变化，请重试')
  return { ...snapshot, countries, revision: result.revision, verified: true,
    rules: sameRevision ? mergeCountryRules(snapshot.rules, result.rules, query) : result.rules }
}
