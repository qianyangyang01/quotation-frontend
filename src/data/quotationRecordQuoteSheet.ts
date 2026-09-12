import type { QuotationRecord } from './quotationRecords'
import type { QuoteSheetSourceRow } from './customerQuoteSheet'

/** Adapt the saved snapshot only. No current logistics lookup or price recalculation. */
export function quotationRecordQuoteSheetSource(record: QuotationRecord) {
  const options = record.quoteOptions ?? record.specifiedQuotes ?? []
  const rows: QuoteSheetSourceRow[] = options.map(option => ({
    country: String(('countryCode' in option && option.countryCode) || option.country),
    quoteRegion: option.quoteRegion,
    // The saved option id also distinguishes historical routes without channel keys.
    channelKey: 'id' in option ? String(option.id) : JSON.stringify([option.country, option.quoteRegion, option.carrier, option.channel, option.rule]),
    ruleId: 0,
    channelCode: 'channelCode' in option ? String(option.channelCode || '') : '',
    rule: option.rule, carrier: option.carrier, transport: option.channel, eta: option.eta,
    quote1: option.quote1Usd, quote2: option.quote2Usd, quote3: option.quote3Usd, quoteCustom: option.quoteCustomUsd,
  }))
  return { rows, countries: [], salesperson: record.salespersonName, customQuantity: record.customQuoteQuantity || 0, bundle: record.quoteMode === 'bundle' }
}
