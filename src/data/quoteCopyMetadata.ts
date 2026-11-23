import { customerGradeDisplayLabel } from './financeChannelPolicies'
import type { QuotationRecord } from './quotationRecords'

/** Internal spreadsheet columns only; never change prices or persisted records. */
export type QuoteCopyMetadata = {
  salesperson?: string
  date?: string
  customerName?: string
  customerGrade?: string
  commissionThreshold?: number | null
}

export function recordQuoteCopyMetadata(record: Pick<QuotationRecord, 'salespersonName' | 'customerName' | 'customerGrade' | 'commissionThreshold'> & { createdAt?: string }): QuoteCopyMetadata {
  const date = record.createdAt ? new Date(record.createdAt) : undefined
  return {
    salesperson: record.salespersonName,
    // Saved timestamps represent instants; display the business date in China.
    date: date && Number.isFinite(date.getTime()) ? new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).format(date) : undefined,
    customerName: record.customerName,
    customerGrade: customerGradeDisplayLabel(record.customerGrade),
    commissionThreshold: record.commissionThreshold,
  }
}
