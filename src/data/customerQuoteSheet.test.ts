import { describe, expect, it } from 'vitest'
import {
  buildCustomerQuoteSheet, CUSTOMER_QUOTE_NOTES, formatQuoteDate, formatShippingTime,
  localQuoteDate, newQuoteSheetEdits, quoteSheetCountryCode, quoteSheetProviderName,
  quoteSheetRowKey, reconcileQuoteSheetEdits, type QuoteSheetSourceRow,
} from './customerQuoteSheet'

function source(overrides: Partial<QuoteSheetSourceRow> = {}): QuoteSheetSourceRow {
  return {
    country: '美国', carrier: '燕文', transport: '专线', channelKey: 'channel-1', ruleId: 1,
    rule: '规则', channelCode: 'YW-1', eta: '6～12 天',
    quote1: 12.8, quote2: 20.5, quote3: 28.2, quoteCustom: 43.6, ...overrides,
  }
}
const edits = () => newQuoteSheetEdits('Alex', new Date(2026, 8, 12))

describe('customer quotation presentation', () => {
  it('maps countries without changing the backend GB code or merging routes', () => {
    const countries = [{ name: '英国', code: 'GB' }, { name: '意大利', code: 'IT' }]
    expect(quoteSheetCountryCode('英国', countries)).toBe('UK')
    expect(quoteSheetCountryCode('gb', countries)).toBe('UK')
    expect(quoteSheetCountryCode('意大利', countries)).toBe('IT')
    expect(countries[0].code).toBe('GB')
    expect(['美国','英国','德国','法国','加拿大','澳大利亚'].map(name => quoteSheetCountryCode(name, [])))
      .toEqual(['US','UK','DE','FR','CA','AU'])
    const sheet = buildCustomerQuoteSheet({ rows: [source(), source({ channelKey: 'channel-2' })], countries, edits: edits(), customQuantity: 5, bundle: false })
    expect(sheet.rows).toHaveLength(2)
    expect(sheet.rows[0].key).not.toBe(sheet.rows[1].key)
  })

  it('uses existing USD quotes, preserves null prices, and supports custom and bundle quantities', () => {
    const row = source({ quote2: null, quote3: 0, quoteCustom: 999.99 })
    Object.freeze(row)
    const sheet = buildCustomerQuoteSheet({ rows: [row], countries: [], edits: edits(), customQuantity: 12, bundle: true })
    expect(sheet.quantityLabels).toEqual(['1 set', '2 sets', '3 sets', '12 sets'])
    expect(sheet.rows[0].prices).toEqual([12.8, null, 0, 999.99])
    expect(sheet.rows[0].shippingTime).toBe('6-12 days')
    expect(row.eta).toBe('6～12 天')
    expect(sheet.date).toBe('12 Sep 2026')
    expect(sheet.issues).toEqual([])
  })

  it('binds manual times to channel and region identities rather than row positions', () => {
    const one = source({ country: '澳大利亚', quoteRegion: '澳大利亚1区' })
    const two = source({ country: '澳大利亚', quoteRegion: '澳大利亚2区' })
    const draft = edits()
    draft.shippingTimes[quoteSheetRowKey(one)] = '8-15 days'
    const original = JSON.stringify([one, two])
    const sheet = buildCustomerQuoteSheet({ rows: [two, one], countries: [], edits: draft, customQuantity: 5, bundle: false })
    expect(sheet.rows.map(row => row.shippingTime)).toEqual(['6-12 days', '8-15 days'])
    expect(JSON.stringify([one, two])).toBe(original)
    expect(reconcileQuoteSheetEdits(draft, [two]).shippingTimes).toEqual({})
    expect(newQuoteSheetEdits('Other agent').shippingTimes).toEqual({})
  })

  it('does not invent missing delivery times and preserves explicit empty overrides', () => {
    for (const value of ['', '—', '-～- 天', '-～12 天', '6～- 天', '0～0 天']) expect(formatShippingTime(value)).toBe('—')
    expect(formatShippingTime('6～12 个工作日')).toBe('6-12 working days')
    const row = source()
    const draft = edits()
    draft.shippingTimes[quoteSheetRowKey(row)] = ''
    expect(buildCustomerQuoteSheet({ rows: [row], countries: [], edits: draft, customQuantity: 5, bundle: false }).rows[0].shippingTime).toBe('—')
  })

  it('reports unmapped identities instead of translating them into an unrelated provider/country', () => {
    expect(['燕文','递四方','云途','顺丰'].map(quoteSheetProviderName)).toEqual(['Yanwen','4PX','YunExpress','SF Express'])
    const sheet = buildCustomerQuoteSheet({ rows: [source({ country: '未知国家', carrier: '未配置物流商' })], countries: [], edits: edits(), customQuantity: 5, bundle: false })
    expect(sheet.issues).toHaveLength(2)
    expect(sheet.rows[0].country).toBe('—')
    expect(sheet.rows[0].provider).toBe('—')
  })

  it('uses local dates without UTC drift and rejects impossible calendar dates', () => {
    expect(localQuoteDate(new Date(2026, 8, 12, 0, 1))).toBe('2026-09-12')
    expect(formatQuoteDate('2026-02-29')).toBe('')
    expect(formatQuoteDate('2028-02-29')).toBe('29 Feb 2028')
    expect(formatQuoteDate('2026-13-01')).toBe('')
    expect(formatQuoteDate('2026-09-31')).toBe('')
  })

  it('keeps exactly the four approved terms and no added payment fees or sample disclaimer', () => {
    expect(CUSTOMER_QUOTE_NOTES).toHaveLength(4)
    expect(CUSTOMER_QUOTE_NOTES[1]).toBe('Payment methods: PayPal, Payoneer, Bank transfer, etc.')
    expect(CUSTOMER_QUOTE_NOTES.join('\n')).not.toMatch(/4%|100% payment|SAMPLE DATA/)
    expect(CUSTOMER_QUOTE_NOTES[3]).toContain('every single orders cannot be 100% guaranteed.')
  })
})
