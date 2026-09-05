import { describe, expect, it } from 'vitest'
import type { Price, SourceIssue } from './logisticsRebuild'
import { priceIssueCountries, priceRowsForIssueCountries, priceIssueRowIndexes, prioritizePriceIssueRows } from './logisticsPriceIssueScope'

const rows: Price[] = [
  { areaName: '澳大利亚', countryCode: 'AU', weightFromKg: 1, weightToKg: 2, rowKey: 'au', sourceSheet: '其他表', sourceRow: 19 },
  { areaName: '法国', countryCode: 'FR', weightFromKg: .001, weightToKg: .2, rowKey: 'fr17', sourceSheet: '敏货', sourceRow: 17 },
  { areaName: '法国', countryCode: 'FR', weightFromKg: .201, weightToKg: 4, rowKey: 'fr18', sourceSheet: '敏货', sourceRow: 18 },
  { areaName: '法国', countryCode: 'FR', weightFromKg: .401, weightToKg: 30, rowKey: 'fr19', sourceSheet: '敏货', sourceRow: 19 },
]
const issue: SourceIssue = { row: 19, sourceSheet: '敏货', field: '重量段', level: 'error', message: '重叠档位' }
describe('price correction country scope', () => {
  it('puts both overlapping French tiers first, then French context and other countries', () => {
    const countries = priceIssueCountries(rows, [issue]), indexes = priceIssueRowIndexes(rows, [issue])
    expect([...indexes]).toEqual([2, 3])
    expect(prioritizePriceIssueRows(rows, countries, indexes).map(row => row.rowKey)).toEqual(['fr18', 'fr19', 'fr17', 'au'])
    const editing = rows.map(row => ({ ...row }))
    editing[2]!.weightToKg = .4
    expect(prioritizePriceIssueRows(editing, countries, indexes).map(row => row.rowKey)).toEqual(['fr18', 'fr19', 'fr17', 'au'])
  })
  it('shows all French tiers including the overlapping counterpart, excluding Australia', () => {
    const countries = priceIssueCountries(rows, [issue, { ...issue, row: 19, sourceSheet: '其他表', level: 'warning' }])
    expect(countries).toEqual([{ key: 'FR', label: '法国' }])
    expect(priceRowsForIssueCountries(rows, countries).map(row => row.rowKey)).toEqual(['fr17', 'fr18', 'fr19'])
  })
  it('prefers an exact row key over an ambiguous source row number', () => {
    expect(priceIssueCountries(rows, [{ ...issue, sourceSheet: undefined, rowKey: 'fr19' }])).toEqual([{ key: 'FR', label: '法国' }])
  })
  it('keeps every candidate country when old issues have no sheet or row key', () => {
    expect(priceIssueCountries(rows, [{ ...issue, sourceSheet: undefined }]).map(country => country.key)).toEqual(['AU', 'FR'])
  })
  it('preserves edits when switching scope and keeps all rows when an issue cannot be located', () => {
    const full = rows.map(row => ({ ...row }))
    const filtered = priceRowsForIssueCountries(full, [{ key: 'FR' }])
    filtered[1]!.weightToKg = .4
    expect(full[2]!.weightToKg).toBe(.4)
    expect(priceRowsForIssueCountries(full, [])).toBe(full)
    expect(priceIssueCountries(full, [{ ...issue, row: 999 }])).toEqual([])
  })
})
