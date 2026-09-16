import { countryIdentity } from './countryIdentity'
import { EU_MEMBER_STATES, EU_TAX_GROUP } from './europeanUnion'
import { logisticsCountries } from './logistics'

const englishNames = new Intl.DisplayNames(['en'], { type: 'region' })

export function financeCountryIdentity(value: string) {
  const identity = countryIdentity(value)
  const eu = EU_MEMBER_STATES.find(row => row.some(alias => countryIdentity(alias) === identity))
  const entry = logisticsCountries.find(row => countryIdentity(row.name) === identity || countryIdentity(row.code) === identity)
  return eu?.[0] || entry?.code || identity
}

export function matchesFinanceCountry(value: string, query: string) {
  const needle = query.trim().toLowerCase()
  if (!needle) return true
  const code = financeCountryIdentity(value)
  // Two-letter input is a country code, not a substring of Croatia / European Union.
  if (/^[a-z]{2}$/.test(needle)) return code.toLowerCase() === needle || (value === EU_TAX_GROUP && needle === 'eu')
  const entry = logisticsCountries.find(row => row.code === code)
  const eu = EU_MEMBER_STATES.find(row => row[0] === code)
  const terms = [value, code, entry?.name, ...(eu || []),
    /^[A-Z]{2}$/.test(code) ? englishNames.of(code) : '',
    value === EU_TAX_GROUP ? '欧盟（27国） EU European Union' : '',
  ]
  return terms.some(term => term?.toLowerCase().includes(needle))
}
