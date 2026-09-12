import { describe, expect, it } from 'vitest'
import { normalizeQuotationRecord, type QuotationRecordQuoteOption } from './quotationRecords'
import { quotationRecordQuoteSheetSource } from './quotationRecordQuoteSheet'
import { buildCustomerQuoteSheet, customerQuoteSheetTsv, newQuoteSheetEdits, quoteSheetRowKey } from './customerQuoteSheet'

const option = (id: string): QuotationRecordQuoteOption => ({
  id, country: '新西兰', countryCode: 'NZ', carrier: '云速递', channel: `内部渠道 ${id}`, rule: '内部规则', eta: '7～12 天',
  quote1Usd: 13, quote2Usd: null, quote3Usd: 0, quoteCustomUsd: 55.678,
})
const record = () => normalizeQuotationRecord({
  id: 'saved-1', no: 'QT-1', salespersonName: 'Alex', salespersonAccount: 'internal',
  quoteMode: 'bundle', customQuoteQuantity: 7, quoteOptions: [option('a'), option('b')],
  systemQuoteUsd: 999, actualQuoteUsd: 888, exchangeRate: 100, totalCostCny: 777,
})!

describe('saved record customer table copying', () => {
  it.each(['common', 'specified', 'template'] as const)('uses all saved routes and original USD tiers for %s mode', mode => {
    const saved = record()
    saved.matrixMode = mode
    const before = JSON.stringify(saved)
    const source = quotationRecordQuoteSheetSource(saved)
    const edits = newQuoteSheetEdits(source.salesperson)
    edits.shippingTimes[quoteSheetRowKey(source.rows[1])] = '9-15 days'
    const sheet = buildCustomerQuoteSheet({ ...source, edits })
    const lines = customerQuoteSheetTsv(sheet).split('\r\n').map(row => row.split('\t'))
    expect(lines).toEqual([
      ['No.', 'Country', 'Logistics Provider', 'Shipping Time', 'Processing Time', '1 set (USD)', '2 sets (USD)', '3 sets (USD)', '7 sets (USD)'],
      ['1', 'NZ', 'SFYD Express', '7-12 days', '1-2 days', '$13.00', '—', '$0.00', '$55.68'],
      ['2', 'NZ', 'SFYD Express', '9-15 days', '1-2 days', '$13.00', '—', '$0.00', '$55.68'],
    ])
    expect(source.rows[0].channelKey).not.toBe(source.rows[1].channelKey)
    expect(JSON.stringify(saved)).toBe(before)
    expect(customerQuoteSheetTsv(sheet)).not.toMatch(/999|888|777|internal|内部/)
  })
  it('keeps historical unknown quantities and missing tiers unknown', () => {
    const saved = normalizeQuotationRecord({ id: 'legacy', no: 'QT-old', salespersonName: 'Alex', country: '美国', carrier: '燕文', systemQuoteUsd: 123 })!
    const source = quotationRecordQuoteSheetSource(saved)
    const sheet = buildCustomerQuoteSheet({ ...source, edits: newQuoteSheetEdits(source.salesperson) })
    expect(sheet.quantityLabels.at(-1)).toBe('Custom')
    expect(sheet.rows[0].prices).toEqual([null, null, null, null])
    expect(sheet.rows[0].shippingTime).toBe('—')
  })
  it('exports the entire table even when image rendering would paginate', () => {
    const saved = record()
    saved.quoteOptions = Array.from({ length: 49 }, (_, i) => option(String(i)))
    const source = quotationRecordQuoteSheetSource(saved)
    const sheet = buildCustomerQuoteSheet({ ...source, edits: newQuoteSheetEdits('Alex') })
    expect(customerQuoteSheetTsv(sheet).split('\r\n')).toHaveLength(50)
  })
  it('prevents cell breaks and spreadsheet formulas in editable text', () => {
    const source = quotationRecordQuoteSheetSource(record())
    const edits = newQuoteSheetEdits('Alex')
    edits.shippingTimes[quoteSheetRowKey(source.rows[0])] = '=1+1\t\n'
    const sheet = buildCustomerQuoteSheet({ ...source, edits })
    expect(customerQuoteSheetTsv(sheet).split('\r\n')[1].split('\t')).toHaveLength(9)
    expect(customerQuoteSheetTsv(sheet)).toContain("'=1+1")
    sheet.tableIssues!.push('invalid source')
    expect(() => customerQuoteSheetTsv(sheet)).toThrow('invalid source')
  })
})
