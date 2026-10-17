import { expect, it } from 'vitest'
import { normalizeQuotationRecord } from './quotationRecords'
import { buildCategoryPerformance, filterQuotationRecords, quotationDetailsCsv, resolveRecordCategory, withDeletedPurchaseCategories } from './quotationAnalytics'

it('keeps deleted SKU historical categories in reports, filters and exports without counting it as live stock', () => {
  const record = normalizeQuotationRecord({ id:'history', no:'QT-HISTORY', primarySku:'OLD', createdAt:'2026-10-12T00:00:00Z', systemQuoteCny:100, systemQuoteUsd:14, totalCostCny:80 })!
  const before = JSON.stringify(record)
  const snapshots = [{ sku:'OLD', category:'宠物用品', createdAt:'2026-10-01T00:00:00Z', deletedAt:'2026-10-17T00:00:00Z' }]
  const purchases = withDeletedPurchaseCategories([], snapshots)
  const filters = { keyword:'', startDate:'', endDate:'', country:'', salesperson:'', category:'宠物用品' }
  expect(filterQuotationRecords([record],filters,purchases)).toEqual([record])
  expect(buildCategoryPerformance([record],purchases)).toEqual([expect.objectContaining({ category:'宠物用品', skuCount:0, quotedSkuCount:1, quotationCount:1, quoteCny:100, averagePurchasePriceCny:null })])
  expect(quotationDetailsCsv([record],purchases)).toContain('宠物用品')
  const reused = withDeletedPurchaseCategories([{sku:'OLD',category:'服装',purchasePriceCny:9}], snapshots)
  expect(resolveRecordCategory(record,new Map(reused.map(p=>[p.sku,p])))).toBe('宠物用品')
  expect(resolveRecordCategory({...record,createdAt:'2026-10-18T00:00:00Z'},new Map(reused.map(p=>[p.sku,p])))).toBe('服装')
  expect(JSON.stringify(record)).toBe(before)
})
