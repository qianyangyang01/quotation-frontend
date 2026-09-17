import { api } from './http'
import type { FinanceSettingVersions } from './financeSettings'

export interface QuotationSyncSnapshot {
  purchaseVersions: Record<string, string | null>
  logisticsRevision: string
  financeVersions?: FinanceSettingVersions
}
export function checkSelectedLogistics(body: unknown, signal?: AbortSignal) {
  return api.get<{ revision: string }>('/quotation-sync/logistics', {
    method: 'POST', body: JSON.stringify(body), signal: signal || AbortSignal.timeout(15000), cache: 'no-store',
  })
}
export function loadQuotationSync(skus: string[], signal?: AbortSignal) {
  const params = new URLSearchParams()
  ;[...new Set(skus.filter(Boolean))].sort().forEach(sku => params.append('sku', sku))
  return api.get<QuotationSyncSnapshot>(`/quotation-sync?${params}`, { signal: signal || AbortSignal.timeout(15000), cache: 'no-store' })
}
export function purchaseRevision(record: { _version?: number; _updatedAt?: string } | undefined) {
  return record?._version != null && record._updatedAt ? `${record._version}:${record._updatedAt}` : null
}

/** No overlapping requests. Hidden tabs pause; returning online/focus checks immediately. */
export function startQuotationSync(check: (signal: AbortSignal) => Promise<void>, failed: (error: unknown) => void) {
  let stopped = false
  let controller: AbortController | null = null
  let timer: ReturnType<typeof setTimeout> | undefined
  let failures = 0
  const run = async () => {
    if (stopped || controller || document.hidden) return
    clearTimeout(timer)
    const current = new AbortController()
    controller = current
    const timeout = setTimeout(() => current.abort(), 15000)
    try { await check(current.signal); if (!current.signal.aborted) failures = 0 }
    catch (error) { if (!stopped && !document.hidden) { failures++; failed(error) } }
    finally {
      clearTimeout(timeout)
      controller = null
      if (!stopped && !document.hidden) timer = setTimeout(() => void run(), Math.min(30000, 5000 * 2 ** failures))
    }
  }
  const wake = () => {
    clearTimeout(timer)
    if (document.hidden) controller?.abort()
    else void run()
  }
  window.addEventListener('focus', wake)
  window.addEventListener('online', wake)
  document.addEventListener('visibilitychange', wake)
  void run()
  return () => {
    stopped = true; clearTimeout(timer); controller?.abort()
    window.removeEventListener('focus', wake)
    window.removeEventListener('online', wake)
    document.removeEventListener('visibilitychange', wake)
  }
}
