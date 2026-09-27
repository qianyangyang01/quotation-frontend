import { expect, it } from 'vitest'
import { normalizePurchaseRecord } from '@/data/purchaseStore'
import { pendingPurchaseInvoiceSkus, purchaseInvoicePending } from './purchaseInvoiceConfirmation'

it('blocks legacy pending invoice types even if the tax point has been populated', () => {
  for (const taxPoint of [0, .08, null]) expect(purchaseInvoicePending(normalizePurchaseRecord({ sku: 'OLD', dataSource: 'legacy_2026', invoiceType: '待确认', taxPoint }))).toBe(true)
})
it('allows explicitly confirmed zero-rate invoices and leaves new data outside this legacy rule', () => {
  for (const invoiceType of ['普票', '专票', '不开票', '收据']) expect(purchaseInvoicePending(normalizePurchaseRecord({ sku: 'OLD', dataSource: 'legacy_2026', invoiceType, taxPoint: 0 }))).toBe(false)
  expect(purchaseInvoicePending(normalizePurchaseRecord({ sku: 'NEW', dataSource: 'standard', invoiceType: '待确认', taxPoint: 0 }))).toBe(false)
})
it('finds pending bundle SKUs once, including legacy invoice aliases', () => {
  const records = [normalizePurchaseRecord({ sku: 'OLD', dataSource: 'legacy_2026', taxDifference: '待确认', taxPoint: 0 }), normalizePurchaseRecord({ sku: 'OK', dataSource: 'legacy_2026', invoiceType: '不开票', taxPoint: 0 })]
  expect(pendingPurchaseInvoiceSkus([' old ', 'OLD', 'OK', '', 'UNKNOWN'], records)).toEqual(['OLD'])
  records[0] = normalizePurchaseRecord({ ...records[0], invoiceType: '普票' })
  expect(pendingPurchaseInvoiceSkus(['OLD', 'OK'], records)).toEqual([])
})
