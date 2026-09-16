import { describe, expect, it } from 'vitest'
import type { Price, SourceIssue } from './logisticsRebuild'
import { collectPriceReviewIssues, priceIssueCountries, priceRowsForIssueCountries, priceIssueRowIndexes, prioritizePriceIssueRows, rowsForPriceIssue, priceIssueLocationLabel } from './logisticsPriceIssueScope'

const rows: Price[] = [
  { areaName: '澳大利亚', countryCode: 'AU', weightFromKg: 1, weightToKg: 2, rowKey: 'au', sourceSheet: '其他表', sourceRow: 19 },
  { areaName: '法国', countryCode: 'FR', weightFromKg: .001, weightToKg: .2, rowKey: 'fr17', sourceSheet: '敏货', sourceRow: 17 },
  { areaName: '法国', countryCode: 'FR', weightFromKg: .201, weightToKg: 4, rowKey: 'fr18', sourceSheet: '敏货', sourceRow: 18 },
  { areaName: '法国', countryCode: 'FR', weightFromKg: .401, weightToKg: 30, rowKey: 'fr19', sourceSheet: '敏货', sourceRow: 19 },
]
const issue: SourceIssue = { row: 19, sourceSheet: '敏货', field: '重量段', level: 'error', message: '重叠档位' }
describe('price correction country scope', () => {
  it('counts row and source issues together without counting their channel summaries again', () => {
    const uk = [10, 11, 12].map(n => ({ areaName: '英国', countryCode: 'GB', rowKey: 'uk' + n, sourceSheet: '敏货', sourceRow: n, weightFromKg: 0, weightToKg: 1, blockingReason: '公斤价计费结构不完整' }))
    const combined = collectPriceReviewIssues([...rows, ...uk], [issue], ['公斤价计费结构不完整'])
    expect(combined).toHaveLength(4)
    expect(combined.filter(item => item.field === '渠道规则')).toEqual([])
    expect(combined.flatMap(item => rowsForPriceIssue([...rows, ...uk], item)).map(row => row.rowKey)).toEqual(['fr19', 'uk10', 'uk11', 'uk12'])
    expect(collectPriceReviewIssues(rows, [issue], [])).toEqual([issue])
    expect(uk.every(row => row.blockingReason === '公斤价计费结构不完整')).toBe(true)
  })
  it('deduplicates each row blocker independently while retaining unlocated channel blockers and warnings', () => {
    const row = { ...rows[3]!, blockingReason: '重叠档位；计价错误' }
    const warning: SourceIssue = { ...issue, field: '时效', level: 'warning', message: '时效提醒' }
    const combined = collectPriceReviewIssues([row], [issue, warning], ['重叠档位', '计价错误', '整表需核对'])
    expect(combined.map(item => item.message)).toEqual(['重叠档位', '时效提醒', '计价错误', '整表需核对'])
    expect(combined.filter(item => item.level === 'error')).toHaveLength(3)
  })
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
  it('does not mark ambiguous same-number rows in different sheets as confirmed errors', () => {
    expect(priceIssueCountries(rows, [{ ...issue, sourceSheet: undefined }])).toEqual([])
  })
  it('locates structured multi-row issues and retains source positions for rows omitted by parsing', () => {
    const multi = { ...issue, row: 0, sourceRows: [17, 18, 999], message: '未完整识别价格' }
    expect(rowsForPriceIssue(rows, multi).map(row => row.rowKey)).toEqual(['fr17', 'fr18'])
    expect(priceIssueLocationLabel(rows, multi)).toContain('Sheet「敏货」· 第 999 行')
    expect(priceIssueLocationLabel(rows, { ...issue, row: 0, message: '整表问题' })).toContain('工作表级问题')
  })
  it('recovers legacy raw row lists without mutating the draft or inventing a matching price row', () => {
    const old = { ...issue, row: 0, message: '存在未完整识别的价格区域；原始行号：[18, 999]' }
    expect(rowsForPriceIssue(rows, old).map(row => row.rowKey)).toEqual(['fr18'])
    expect(old.row).toBe(0)
    expect(priceIssueLocationLabel(rows, old)).toContain('999')
  })
  it('shows both sides of cross-sheet errors and avoids attaching unrelated blockers to them', () => {
    const cross = { ...issue, relatedSourceSheet: '其他表', relatedSourceRow: 19, message: '跨表问题' }
    expect(rowsForPriceIssue(rows, cross).map(row => row.rowKey)).toEqual(['au', 'fr19'])
    const blocked = rows.map(row => ({ ...row, ...(row.rowKey === 'au' ? { blockingReason: '其他问题' } : {}) }))
    expect([...priceIssueRowIndexes(blocked, [issue], false)]).toEqual([2, 3])
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
