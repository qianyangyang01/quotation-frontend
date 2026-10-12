import { request } from './http'
import { normalizeQuotationRecord, type QuotationRecord } from '@/data/quotationRecords'
import { type CustomerQuoteSheet, customerQuoteSheetHtml, customerQuoteSheetTsv } from '@/data/customerQuoteSheet'
import { quotationRecordCopyLayout } from '@/data/quotationRecordCopyLayout'
import { quotationRecordQuoteOnlyLayout } from '@/data/quotationRecordQuoteOnlyLayout'

export type BatchCopyKind = 'quote' | 'full' | 'image'
export async function loadQuotationBatchRecords(record: QuotationRecord, purpose: 'copy' | 'trash' = 'copy'): Promise<QuotationRecord[]> {
  const raw = await request<Partial<QuotationRecord>[]>(`/quotations/${encodeURIComponent(record.id)}/batch-records`, { signal: AbortSignal.timeout(30000) })
  const records = raw.map(normalizeQuotationRecord)
  const batch = record.quotationBatch
  if (!batch || records.length !== batch.count || records.some(row => !row || row.quotationBatch?.id !== batch.id || row.quoteMode !== record.quoteMode || row.quotationBatch.count !== batch.count || !['active', 'archived'].includes(row.lifecycleState ?? 'active'))
    || new Set(records.map(row => row?.id)).size !== batch.count || !records.some(row => row?.id === record.id)
    || new Set(records.map(row => row?.quotationBatch?.index)).size !== batch.count
    || records.some(row => !Number.isInteger(row?.quotationBatch?.index) || row!.quotationBatch!.index < 1 || row!.quotationBatch!.index > batch.count)) {
    throw new Error(purpose === 'copy' ? '批次记录不完整或已变化，未复制任何数据，请刷新后重试' : '批次记录不完整或已变化，本次未移入任何记录，请刷新后重试')
  }
  return (records as QuotationRecord[]).sort((a, b) => a.quotationBatch!.index - b.quotationBatch!.index)
}

/** Union quantity columns by label so later per-record edits never misalign prices. */
export function mergeSavedBatchSheets(sheets: CustomerQuoteSheet[]): CustomerQuoteSheet {
  const first = sheets[0]
  if (!first) throw new Error('本批没有可复制的报价记录')
  const invalid = sheets.find(sheet => !sheet.rows.length || sheet.issues.length)
  if (invalid) throw new Error(`本批报价单无法生成${invalid.rows[0]?.sku ? `（${invalid.rows[0].sku}）` : ''}：${invalid.issues.join('；') || '没有可显示的报价明细'}`)
  if (sheets.some(sheet => (sheet.sizeRules ?? '') !== (first.sizeRules ?? ''))) throw new Error('本批尺码规则不同，请分别复制各条报价，避免合并后规则失真')
  const labels = [...new Set(sheets.flatMap(sheet => sheet.quantityLabels))]
  if (labels.length > 10) throw new Error('本批数量档超过10列，请分别复制各条报价图片')
  return { ...first, quantityLabels: labels,
    hiddenColumns: (first.hiddenColumns ?? []).filter(column => sheets.every(sheet => sheet.hiddenColumns?.includes(column))),
    notes: [...new Set(sheets.flatMap(sheet => sheet.notes ?? []))],
    rows: sheets.flatMap((sheet, index) => sheet.rows.map(row => ({ ...row, key: `${index}:${row.key}`, prices: labels.map(label => row.prices[sheet.quantityLabels.indexOf(label)] ?? null) }))).map((row, index) => ({ ...row, number: index + 1 })),
  }
}

export function batchRecordCopyLayout(records: QuotationRecord[], kind: 'quote' | 'full', version: 'full' | 'visible', permissions: { includeCost: boolean; includeDetails: boolean }) {
  if (records[0]?.quoteMode === 'fob') {
    const sheet = mergeSavedBatchSheets(records.map(record => {
      if (!record.fob?.sheet) throw new Error('本批存在缺少报价单快照的FOB记录')
      return record.fob.sheet
    }))
    return { text: customerQuoteSheetTsv(sheet), html: customerQuoteSheetHtml(sheet) }
  }
  const layouts = records.map(record => kind === 'quote' ? quotationRecordQuoteOnlyLayout(record, version, permissions) : quotationRecordCopyLayout(record, version, permissions))
  const fragments = layouts.map(layout => layout.html.split('<!--StartFragment-->')[1]?.split('<!--EndFragment-->')[0] ?? layout.html)
  return { text: layouts.map(layout => layout.text).join('\n\n'), html: `<html><head><meta charset="utf-8"></head><body><!--StartFragment-->${fragments.join('<br>')}<!--EndFragment--></body></html>` }
}
