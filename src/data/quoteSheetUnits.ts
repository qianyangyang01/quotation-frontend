/** Display labels only: never use these units to convert quantity, weight or price. */
export type QuoteSheetUnits = Record<string, string>
export const MAX_QUOTE_SHEET_UNIT_LENGTH = 24
const printable = (character: string) => character.charCodeAt(0) >= 32 && character.charCodeAt(0) !== 127
export function quoteSheetUnitInput(value: string) {
  return [...value].filter(printable).join('').slice(0, MAX_QUOTE_SHEET_UNIT_LENGTH)
}
export function quoteSheetUnit(quantity: number, bundle: boolean, custom?: string) {
  return custom?.trim() || (bundle ? quantity === 1 ? 'set' : 'sets' : quantity === 1 ? 'pc' : 'pcs')
}
export function validQuoteSheetUnits(value: unknown, quantities: readonly number[]): value is QuoteSheetUnits {
  return !!value && typeof value === 'object' && !Array.isArray(value) && Object.entries(value).every(([key, unit]) =>
    quantities.some(q => q > 0 && String(q) === key) && typeof unit === 'string' && !!unit.trim() &&
    unit.length <= MAX_QUOTE_SHEET_UNIT_LENGTH && [...unit].every(printable))
}
