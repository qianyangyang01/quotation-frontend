import { api, ApiError } from './http'

export type RankingScope = 'all' | 'sales'
export type CompletionOrder = 'quality' | 'quantity'
export type RankingSource = 'standard' | 'fob' | 'all'
export type RankingPart = 'completion' | 'exceptions'
export type CompletionRanking = { owner: string; total: number; complete: number; pending: number; rate: number }
export type ExceptionRanking = { owner: string; count: number; abnormalQuotes: number; relatedQuotes: number; rate: number }
export type PurchaseRankings = {
  scope: RankingScope; source: RankingSource; days: number; from: string; to: string
  salesPeriod: string; salesConfigured: boolean; unmatchedSalesSkus: number; unclassified: number; undated: number
  completion: CompletionRanking[]; exceptions: ExceptionRanking[]
  completionTo?: string
}
export const loadPurchaseRankings = (scope: RankingScope, days: number, signal?: AbortSignal) =>
  api.get<PurchaseRankings>(`/purchase-rankings?${new URLSearchParams({ scope, days: String(days) })}`, { signal, cache: 'no-store' })

export async function loadPurchaseRankingPart(part: RankingPart, scope: RankingScope, days: number, refresh = false, signal?: AbortSignal) {
  const deadline = new AbortController()
  const timer = setTimeout(() => deadline.abort(new DOMException('Ranking read timed out', 'TimeoutError')), 30_000)
  const parameters = new URLSearchParams({ part, scope, days: String(part === 'completion' ? 0 : days) })
  if (refresh) parameters.set('refresh', 'true')
  try {
    return await api.get<PurchaseRankings>(`/purchase-rankings?${parameters}`, { signal: signal ? AbortSignal.any([signal, deadline.signal]) : deadline.signal, cache: 'no-store' })
  } catch (error) {
    if (deadline.signal.aborted && !signal?.aborted) throw new ApiError('排名统计读取超时，请稍后重试。', 0, 'PURCHASE_RANKINGS_TIMEOUT', 'rankings-timeout')
    throw error
  } finally { clearTimeout(timer) }
}

const cell = (value: unknown) => '"' + String(value ?? '').replace(/^\s*[=+@\-\t\r]/, match => "'" + match).replace(/"/g, '""') + '"'
const fraction = (row: CompletionRanking) => row.total ? row.complete / row.total : 0
export function completionRankings(rows: CompletionRanking[], order: CompletionOrder) {
  const compare = (a: CompletionRanking, b: CompletionRanking) => order === 'quantity'
    ? b.complete - a.complete || fraction(b) - fraction(a)
    : fraction(b) - fraction(a) || b.complete - a.complete
  const sorted = rows.filter(row => row.owner).slice().sort((a, b) => compare(a, b) || a.owner.localeCompare(b.owner, 'zh-CN'))
  let rank = 0
  return sorted.map((row, index) => {
    if (!index || compare(row, sorted[index - 1]!) !== 0) rank = index + 1
    return { ...row, rank }
  })
}
export function rankingSummary(rows: CompletionRanking[]) {
  const included = rows.filter(row => row.owner)
  const total = included.reduce((sum, row) => sum + row.total, 0)
  const complete = included.reduce((sum, row) => sum + row.complete, 0)
  return { people: included.length, total, complete, pending: total - complete, rate: total ? complete / total * 100 : 0 }
}
export function rankingsCsv(data: PurchaseRankings, order: CompletionOrder = 'quality') {
  const summary = rankingSummary(data.completion)
  const rows: unknown[][] = [
    ['统计范围', data.scope === 'sales' ? `在售 SKU（${data.salesPeriod}销量表）` : '全部采购'],
    ['人员范围', '仅当前角色为采购的账号；已确认简称、全名和账号合并；未匹配或归属冲突不计入'],
    ['排序', order === 'quantity' ? '已完整数量优先，同数量按补全率' : '补全率优先，同率按已完整数量', '指标相同并列；按未四舍五入补全率排序'],
    ['汇总', '采购人员', summary.people, '名下SKU', summary.total, '已完整', summary.complete, '待补全', summary.pending, '整体补全率', `${summary.rate.toFixed(1)}%`],
    ['异常审核时间范围', data.days ? data.from : '全部历史', data.to],
    ['资料统计更新于', data.completionTo || data.to],
    ['异常统计更新于', data.to],
    ['补全率口径', '当前资料；重量、起订量、采购价、票点、运费；FOB按原文重量及解析后的价格、运费判断'],
    ['SKU去重', '同一SKU只计一次；各来源资料均完整才算补全；归属不一致或未填写不计入'],
    ['异常口径', '正式采购价格异常，按报价ID和SKU去重；同一报价多SKU可计多项'],
    ['异常占比口径', '异常报价数 / 同期已审核关联报价数；非异常报价按当前采购人关联'],
    [order === 'quantity' ? '已完整数量排名' : '完整度排名', '采购人员', '补全率', '已完整SKU', '总SKU', '待补SKU'],
  ]
  completionRankings(data.completion, order).forEach(row => rows.push([row.rank, row.owner, `${row.rate.toFixed(1)}%`, row.complete, row.total, row.pending]))
  rows.push([], ['采购价格异常排名', '采购人员', '异常项数', '异常报价数', '关联报价数', '异常占比'])
  data.exceptions.filter(row => row.owner).forEach((row, index) => rows.push([index + 1, row.owner, row.count, row.abnormalQuotes, row.relatedQuotes, row.relatedQuotes ? `${row.rate.toFixed(1)}%` : '—']))
  rows.push([], ['历史未分类报价', data.unclassified], ['缺审核时间报价（未计入）', data.undated], ['销量SKU未匹配采购资料', data.unmatchedSalesSkus])
  return '\uFEFF' + rows.map(row => row.map(cell).join(',')).join('\r\n')
}
