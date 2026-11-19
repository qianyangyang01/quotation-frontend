import { api } from './http'
import type { DashboardFilters } from '@/data/quotationAnalytics'

export type RankingState = 'pending-version' | 'unverified' | 'unmatched' | 'historical'
export interface ChannelRankingRow {
  key: string; country: string; provider: string; channel: string; uses: number; share: number
  latestAt: string; channelId: string | null; datasetId: string | null; versionId: string | null; state: RankingState
}
export interface ChannelRankingResult {
  items: ChannelRankingRow[]; totalUses: number; totalGroups: number; pendingGroups: number; unmatchedGroups: number
  topFiveShare: number; total: number; page: number; size: number; totalPages: number; generatedAt: string
  startDate: string; endDate: string
}
export function overviewDateRange(days = 30) {
  const endDate = new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date())
  const start = new Date(endDate + 'T00:00:00Z')
  start.setUTCDate(start.getUTCDate() - days + 1)
  return { startDate: start.toISOString().slice(0, 10), endDate }
}
export async function loadChannelRanking(filters: DashboardFilters, page: number, size: number, pendingOnly: boolean, signal: AbortSignal): Promise<ChannelRankingResult> {
  const query = new URLSearchParams({
    startDate: filters.startDate || '2000-01-01', endDate: filters.endDate || overviewDateRange().endDate,
    country: filters.country, salesperson: filters.salesperson, category: filters.category, keyword: filters.keyword,
    page: String(page), size: String(size), pendingOnly: String(pendingOnly),
  })
  const result = await api.get<ChannelRankingResult>(`/quotations/channel-ranking?${query}`, {
    cache: 'no-store', signal: AbortSignal.any([signal, AbortSignal.timeout(12_000)]),
  })
  if (!result || !Array.isArray(result.items) || !Number.isSafeInteger(result.totalUses) || result.totalUses < 0
    || !Number.isSafeInteger(result.total) || result.total < result.items.length
    || result.items.length > size || new Set(result.items.map(r => r.key)).size !== result.items.length
    || result.items.some(r => !Number.isSafeInteger(r.uses) || r.uses < 1 || !Number.isFinite(r.share))) {
    throw new Error('渠道排行读取不完整，请重试')
  }
  return result
}
