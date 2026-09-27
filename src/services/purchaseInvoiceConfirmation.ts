import type { PurchaseProductRecord } from '@/data/purchaseStore'

export const PURCHASE_INVOICE_PENDING_MESSAGE = '采购票点为待确认，不可报价。需要采购在系统里确认并修改该 SKU 的票点、票类型后，才可以报价。'

export function purchaseInvoicePending(record: PurchaseProductRecord) {
  return record.dataSource === 'legacy_2026' && record.invoiceType.trim() === '待确认'
}

export function pendingPurchaseInvoiceSkus(skus: string[], records: PurchaseProductRecord[]) {
  return [...new Set(skus.map(sku => sku.trim().toUpperCase().replace(/\s+/g, '')).filter(Boolean))]
    .filter(sku => records.some(record => record.sku === sku && purchaseInvoicePending(record)))
}
