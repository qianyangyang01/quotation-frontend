import { api } from '@/services/http'
import type { QuotationRecord } from './quotationRecords'

export type RecordLifecycle = 'active' | 'archived' | 'trashed'
export type LifecycleAction = 'archive' | 'trash' | 'restore'
export const lifecycleLabel = (state?: string) => state === 'trashed' ? '回收站' : state === 'archived' ? '已归档' : '当前记录'
export function lifecycleProtection(row: QuotationRecord) {
  if (row.status === 'won' || row.dealLines?.length || (row.dealQuantity ?? 0) > 0) return '已有成交数据，需管理员单独核实'
  if (row.financeReviewStatus === 'reviewing') return '正在审核，请审核人先完成或取消审核'
  if (row.financeReviewStatus && row.financeReviewStatus !== 'pending') return '已审核记录，需管理员单独核实'
  return ''
}
export async function changeQuotationLifecycle(action: LifecycleAction, reason: string, rows: QuotationRecord[]) {
  return api.post<{ changed: number }>('/quotations/lifecycle', {
    action, reason: reason.trim(), items: rows.map(row => ({ id: row.id, version: row._version })),
  })
}
