import { describe, expect, it } from 'vitest'
import { completionRankings, rankingSummary, rankingsCsv, type CompletionRanking, type PurchaseRankings } from './purchaseRankings'

const row = (owner: string, complete: number, total: number): CompletionRanking => ({ owner, complete, total, pending: total - complete, rate: total ? Math.round(complete / total * 1000) / 10 : 0 })
const rows = [row('小样本', 1, 1), row('周圆圆', 324, 417), row('唐慧珍', 226, 229), row('并列', 1, 1)]
describe('purchase ranking presentation and export', () => {
  it('switches volume and quality ordering with competition ties without mutating the snapshot', () => {
    expect(completionRankings(rows, 'quantity').map(r => [r.owner, r.rank])).toEqual([['周圆圆', 1], ['唐慧珍', 2], ['并列', 3], ['小样本', 3]])
    expect(completionRankings(rows, 'quality').map(r => r.rank)).toEqual([1, 1, 3, 4])
    expect(rows[0]?.owner).toBe('小样本')
  })
  it('compares actual fractions instead of rounded display percentages and handles empty data', () => {
    expect(completionRankings([row('量大', 1998, 2000), row('更完整', 1000, 1001)], 'quality')[0]?.owner).toBe('更完整')
    expect(completionRankings([], 'quality')).toEqual([])
    expect(rankingSummary([])).toEqual({ people: 0, total: 0, complete: 0, pending: 0, rate: 0 })
  })
  it('uses weighted totals and the displayed order and ties in CSV, excluding unassigned rows', () => {
    const data: PurchaseRankings = { scope: 'all', source: 'all', days: 30, from: '', to: '', salesPeriod: '', salesConfigured: true, unmatchedSalesSkus: 0, unclassified: 0, undated: 0, completion: [...rows, row('', 900, 1000)], exceptions: [] }
    const summary = rankingSummary(data.completion)
    expect(summary.total).toBe(648); expect(summary.complete).toBe(552)
    expect(summary.rate).toBeCloseTo(85.185185)
    const csv = rankingsCsv(data, 'quantity')
    expect(csv).toContain('"1","周圆圆","77.7%","324","417","93"')
    expect(csv).toContain('"3","小样本"'); expect(csv).toContain('"3","并列"')
    expect(csv).not.toContain('"900"'); expect(csv).not.toContain('未归属采购人')
    expect(csv.indexOf('"周圆圆"')).toBeLessThan(csv.indexOf('"唐慧珍"'))
    expect(rankingsCsv({ ...data, completion: [row('=危险文本', 1, 1)] })).toContain("'=危险文本")
    const timedCsv = rankingsCsv({ ...data, completionTo: '2026-10-16T00:00:00Z', to: '2026-10-16T00:01:00Z' })
    expect(timedCsv).toContain('"资料统计更新于","2026-10-16T00:00:00Z"')
    expect(timedCsv).toContain('"异常统计更新于","2026-10-16T00:01:00Z"')
  })
})
