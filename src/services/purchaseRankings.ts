import { api } from './http'

export type RankingScope = 'all' | 'sales'
export type RankingSource = 'standard' | 'fob' | 'all'
export type CompletionRanking = { owner: string; total: number; complete: number; pending: number; rate: number }
export type ExceptionRanking = { owner: string; count: number; abnormalQuotes: number; relatedQuotes: number; rate: number }
export type PurchaseRankings = {
  scope: RankingScope; source: RankingSource; days: number; from: string; to: string
  salesPeriod: string; salesConfigured: boolean; unmatchedSalesSkus: number; unclassified: number; undated: number
  completion: CompletionRanking[]; exceptions: ExceptionRanking[]
}
export const loadPurchaseRankings = (scope: RankingScope, days: number, signal?: AbortSignal) =>
  api.get<PurchaseRankings>(`/purchase-rankings?${new URLSearchParams({ scope, days: String(days) })}`, { signal, cache: 'no-store' })

const cell = (value: unknown) => '"' + String(value ?? '').replace(/^\s*[=+@\-\t\r]/, match => "'" + match).replace(/"/g, '""') + '"'
export function rankingsCsv(data: PurchaseRankings) {
  const rows: unknown[][] = [
    ['统计范围', data.scope === 'sales' ? `在售 SKU（${data.salesPeriod}销量表）` : '全部采购'],
    ['异常审核时间范围', data.days ? data.from : '全部历史', data.to],
    ['补全率口径', '当前资料；重量、起订量、采购价、票点、运费；FOB按原文重量及解析后的价格、运费判断'],
    ['SKU去重', '同一SKU只计一次；各来源资料均完整才算补全；归属不一致或未填写人员单列'],
    ['异常口径', '正式采购价格异常，按报价ID和SKU去重；同一报价多SKU可计多项'],
    ['异常占比口径', '异常报价数 / 同期已审核关联报价数；非异常报价按当前采购人关联'],
    ['资料补全率排名', '采购人员', '补全率', '已完整SKU', '总SKU', '待补SKU'],
  ]
  let rank = 0
  data.completion.forEach(row => rows.push([row.owner ? ++rank : '单列', row.owner || '未归属采购人', `${row.rate.toFixed(1)}%`, row.complete, row.total, row.pending]))
  rows.push([], ['采购价格异常排名', '采购人员', '异常项数', '异常报价数', '关联报价数', '异常占比'])
  rank = 0
  data.exceptions.forEach(row => rows.push([row.owner ? ++rank : '单列', row.owner || '待归属', row.count, row.abnormalQuotes, row.relatedQuotes, `${row.rate.toFixed(1)}%`]))
  rows.push([], ['历史未分类报价', data.unclassified], ['缺审核时间报价（未计入）', data.undated], ['销量SKU未匹配采购资料', data.unmatchedSalesSkus])
  return '\uFEFF' + rows.map(row => row.map(cell).join(',')).join('\r\n')
}
