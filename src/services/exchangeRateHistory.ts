import { api } from './http'
export type ExchangeCurrency = 'usdCny' | 'eurUsd'
export type ExchangeHistoryEntry = { id: string; modifiedAt: string; actorAccount: string; actorName: string; currency: ExchangeCurrency; before: number | null; after: number | null }
export type ExchangeHistoryLatest = Partial<Record<ExchangeCurrency, ExchangeHistoryEntry>>
export type ExchangeHistoryPage = { items: ExchangeHistoryEntry[]; total: number; page: number; size: number; latest: ExchangeHistoryLatest }
export const loadExchangeRateHistory = (currency: ExchangeCurrency | 'all', page: number, signal: AbortSignal) => api.get<ExchangeHistoryPage>(`/finance-settings/exchange-rate/history?currency=${currency}&page=${page}`, { signal, cache: 'no-store' })
export function exchangeHistoryTime(value?: string) {
  if (!value) return '暂无记录'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false }).format(date)
}
