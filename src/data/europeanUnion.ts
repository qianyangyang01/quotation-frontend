import members from '../../backend/src/main/resources/eu-member-states.json'

// Shared with the server's save guard; EU membership is not geographic Europe.
export const EU_TAX_GROUP = '欧盟'
export const EU_MEMBER_STATES = members as [string, string, ...string[]][]
const countryCodes = new Map(EU_MEMBER_STATES.flatMap(row => row.map(alias => [alias.toUpperCase(), row[0]] as const)))

export function euCountryCode(country: string) {
  return countryCodes.get(country.trim().toUpperCase())
}

export function isEuCountry(country: string) { return euCountryCode(country) !== undefined }

export function sameTaxCountry(left: string, right: string) {
  return left.trim().toUpperCase() === right.trim().toUpperCase()
    || (isEuCountry(left) && euCountryCode(left) === euCountryCode(right))
}
