// @vitest-environment happy-dom
import { afterEach, expect, it } from 'vitest'
import { buildCustomerQuoteSheet, customerQuoteSheetTsv, newQuoteSheetEdits, type QuoteSheetSourceRow } from './customerQuoteSheet'
import { loadQuoteSheetAustraliaZones, saveQuoteSheetAustraliaZones } from './quoteSheetColumnPreferences'
import { normalizeCustomerPrices, recordCustomerPrices } from './customerQuotePrices'
import { quotationRecordQuoteSheetSource } from './quotationRecordQuoteSheet'
import type { QuotationRecord } from './quotationRecords'

const assetId = '11111111-2222-3333-4444-555555555555'
const snapshot = { quantities: [1], rows: [{ optionId: 'a', prices: [10] }], photos: [{ assetId, name: 'product.png' }], showPhotos: false }
afterEach(() => localStorage.clear())
it('hides only Australian zone labels across country formats without altering rows or prices', () => {
  const source = ['澳大利亚', 'AU', 'Australia', '加拿大'].map((country, i) => ({ country, quoteRegion: i===3 ? '偏远地区' : '澳大利亚3区', carrier: '4PX', channelKey: String(i), ruleId:i, rule:'', channelCode:'', transport:'', eta:'5-8 days', quote1:10, quote2:20, quote3:30, quoteCustom:50 })) satisfies QuoteSheetSourceRow[]
  for (const countryFormat of ['name', 'code'] as const) {
    const input = { rows: source, countries: [], edits: {...newQuoteSheetEdits('QA'), countryFormat}, customQuantity:5, bundle:false }
    const shown = buildCustomerQuoteSheet(input)
    const hidden = buildCustomerQuoteSheet({...input, edits:{...input.edits, showAustraliaZones:false}})
    expect(shown.rows.slice(0,3).map(row=>row.region)).toEqual(['Zone 3','Zone 3','Zone 3'])
    expect(hidden.rows.slice(0,3).map(row=>row.region)).toEqual(['','',''])
    expect(hidden.rows[3]!.region).toBe(shown.rows[3]!.region)
    expect(hidden.rows.map(row=>row.prices)).toEqual(shown.rows.map(row=>row.prices))
    expect(customerQuoteSheetTsv(hidden)).not.toContain('Zone 3')
    expect(buildCustomerQuoteSheet(input)).toEqual(shown)
  }
})
it('remembers the zone choice separately for each signed-in user', () => {
  expect(loadQuoteSheetAustraliaZones('a')).toBe(true)
  expect(saveQuoteSheetAustraliaZones('a',false)).toBe(true)
  expect(loadQuoteSheetAustraliaZones('a')).toBe(false)
  expect(loadQuoteSheetAustraliaZones('b')).toBe(true)
})
it('round trips saved photo references and display choice without retaining blob URLs', () => {
  expect(normalizeCustomerPrices(snapshot)).toMatchObject(snapshot)
  expect(normalizeCustomerPrices({...snapshot,photos:[{assetId:'blob:local',name:'x'}]})).toBeUndefined()
  const record = {customerQuote:snapshot,quoteOptions:[],primarySku:'A',productImage:`/api/v1/assets/${assetId}`} as unknown as QuotationRecord
  expect(recordCustomerPrices(record)).toMatchObject(snapshot)
  expect(quotationRecordQuoteSheetSource(record).initialPhotos).toEqual(snapshot.photos)
  expect(quotationRecordQuoteSheetSource({...record,customerQuote:{...snapshot,photos:[]}}).initialPhotos).toEqual([])
  expect(quotationRecordQuoteSheetSource({...record,customerQuote:undefined}).initialPhotos).toEqual([{assetId,name:'商品图片'}])
})
