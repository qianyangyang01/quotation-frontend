import aliases from '../../backend/src/main/resources/country-aliases.json'
const countries = new Map(aliases.flatMap(row => row.map(value => [value.toUpperCase(), row] as const)))
export function countryIdentity(value: string) {
  const key = value.trim().toUpperCase()
  return countries.get(key)?.[0] || key
}
export function sameCountryIdentity(left: string, right: string) {
  return !!left.trim() && !!right.trim() && countryIdentity(left) === countryIdentity(right)
}
export function countryIdentityMatches(code: string, name: string, country: string) {
  return sameCountryIdentity(code,country) || sameCountryIdentity(name,country)
}
