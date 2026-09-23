import { api } from '@/services/http'
import { normalizeQuotationRecord, type QuotationRecord } from '@/data/quotationRecords'

export async function loadAnalyticsRecords(signal?: AbortSignal): Promise<QuotationRecord[]> {
  const response = await api.get<{ items: QuotationRecord[]; total: number }>('/quotations/analytics-records', {
    cache: 'no-store', signal: signal ? AbortSignal.any([signal, AbortSignal.timeout(20_000)]) : AbortSignal.timeout(20_000),
  })
  if (!Array.isArray(response.items) || !Number.isSafeInteger(response.total) || response.items.length !== response.total) {
    throw new Error('报价统计读取不完整，请重试')
  }
  const records = response.items.map(normalizeQuotationRecord)
  if (records.some(row => !row) || new Set(records.map(row => row?.id)).size !== response.total) {
    throw new Error('报价统计读取不完整，请重试')
  }
  return records as QuotationRecord[]
}
