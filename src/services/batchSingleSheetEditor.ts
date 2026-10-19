import { quoteSheetRowKey, type CustomerQuoteSheet } from '@/data/customerQuoteSheet'
import type { CapturedSheetPrices, CustomerPriceSnapshot } from '@/data/customerQuotePrices'
import { quotationRecordQuoteSheetSource } from '@/data/quotationRecordQuoteSheet'
import type { QuotationRecord } from '@/data/quotationRecords'
import { captureQuoteRowOrder } from '@/data/quoteSheetRowOrder'
import { mapAveragePlans } from '@/data/quoteChannelAverage'
import { buildQuotationWeightSnapshot } from '@/data/quotationWeightSnapshot'
import { mergeBatchSingle, type BatchSingleDocument, type BatchSingleItem } from './batchSingleQuotation'

/** Every route has a SKU-scoped UI identity, even when all products use the same channel. */
export function batchSheetEditorSource(document: BatchSingleDocument) {
  const entries = document.items.flatMap((item, index) => quotationRecordQuoteSheetSource(item.input as QuotationRecord).rows.map(source => {
    const option = item.input.quoteOptions!.find(option => option.id === source.channelKey)! as NonNullable<BatchSingleItem['input']['quoteOptions']>[number] & { quoteSheetKey: string }
    const row = { ...source, sku: item.input.primarySku, channelKey: JSON.stringify([item.input.primarySku, option.id]) }
    return { index, option, row, key: quoteSheetRowKey(row), mergedKey: `${index}:${option.quoteSheetKey}` }
  }))
  const averageId = (index: number, id: string) => `b${index}_${id}`
  const snapshots = (system: boolean): CustomerPriceSnapshot => {
    const first = document.items[0]!.input.customerQuote!
    return {
      ...(!system ? first : {}), quantities: [...first.quantities],
      rows: entries.map(entry => {
        const item = document.items[entry.index]!.input
        const saved = system ? item.systemQuantityQuotes : item.customerQuote
        return { optionId: entry.row.channelKey, prices: first.quantities.map(q => saved?.rows.find(row => row.optionId === entry.option.id)?.prices[saved.quantities.indexOf(q)] ?? null) }
      }),
      ...(!system ? {
        hiddenOptionIds: entries.filter(entry => document.items[entry.index]!.input.customerQuote?.hiddenOptionIds?.includes(entry.option.id)).map(entry => entry.row.channelKey),
        averagePlans: document.items.flatMap((item, index) => mapAveragePlans(item.input.customerQuote?.averagePlans, id => entries.find(entry => entry.index === index && entry.option.id === id)?.row.channelKey).map(plan => ({ ...plan, id: averageId(index, plan.id) }))),
        rowOrder: document.sheet.rows.map(row => row.averageId ? `average:${averageId(Number(row.key.split(':')[0]), row.averageId)}` : `option:${entries.find(entry => entry.mergedKey === row.key)?.row.channelKey}`),
      } : {}),
    }
  }
  const initialSheet = { ...document.sheet, rows: document.sheet.rows.map(row => ({ ...row, key: row.averageId ? `average:${averageId(Number(row.key.split(':')[0]), row.averageId)}` : entries.find(entry => entry.mergedKey === row.key)?.key || row.key })) }
  return { entries, rows: entries.map(entry => entry.row), initialQuote: snapshots(false), initialSystemQuote: snapshots(true), initialSheet }
}

export function captureBatchSheet(document: BatchSingleDocument, source: ReturnType<typeof batchSheetEditorSource>, captured: CapturedSheetPrices, sheet: CustomerQuoteSheet): BatchSingleDocument {
  if (sheet.issues.length) throw new Error(sheet.issues.join('；'))
  const ownership = new Map(source.entries.map(entry => [entry.key, entry.index]))
  for (const plan of captured.averagePlans ?? []) {
    const owners = new Set(plan.members.map(member => ownership.get(member.optionId)))
    if (owners.size !== 1 || owners.has(undefined)) throw new Error('渠道平均报价只能合并同一 SKU 的渠道')
    ownership.set(`average:${plan.id}`, [...owners][0]!)
  }
  const savedKeys = new Map<string, string>()
  const items = document.items.map((item, index) => {
    const entries = source.entries.filter(entry => entry.index === index)
    const optionId = (key: string) => entries.find(entry => entry.key === key)?.option.id
    const ownedKeys = (captured.rowOrderKeys ?? []).filter(key => ownership.get(key) === index)
    const snapshot: CustomerPriceSnapshot = {
      quantityUnits: captured.quantityUnits ? { ...captured.quantityUnits } : undefined, quantities: [...captured.quantities], contact: captured.contact, photos: captured.photos, showPhotos: captured.showPhotos,
      sizeRules: captured.sizeRules, sizeRulesEnabled: captured.sizeRulesEnabled,
      hiddenOptionIds: entries.filter(entry => captured.hiddenRowKeys?.includes(entry.key)).map(entry => entry.option.id),
      rowOrder: captureQuoteRowOrder(ownedKeys, optionId),
      averagePlans: mapAveragePlans(captured.averagePlans?.filter(plan => ownership.get(`average:${plan.id}`) === index), optionId),
      rows: entries.map(entry => ({ optionId: entry.option.id, prices: [...captured.rows.find(row => row.key === entry.key)!.prices] })),
    }
    const rows = sheet.rows.filter(row => ownership.get(row.key) === index).map(row => {
      const key = row.averageId ? row.key : entries.find(entry => entry.key === row.key)!.option.quoteSheetKey
      savedKeys.set(row.key, `${index}:${key}`)
      return { ...row, key, sku: item.input.primarySku }
    })
    if (!rows.length) throw new Error(`${item.input.primarySku} 的报价行已全部隐藏，请恢复至少一行，或在处理清单中取消选入该 SKU`)
    const weight = item.input.weightSnapshot
    return { ...item, input: { ...item.input, ...(weight ? { weightSnapshot: buildQuotationWeightSnapshot(weight.items, weight.specialPackagingGrams, [...weight.quantities.map(row => row.quantity), ...captured.quantities]) } : {}), customerQuote: snapshot, sheetQuote: snapshot, systemQuantityQuotes: { quantities: [...captured.quantities], rows: entries.map(entry => ({ optionId: entry.option.id, prices: [...captured.rows.find(row => row.key === entry.key)!.systemPrices] })) } }, sheet: { ...sheet, rows } }
  })
  const result = mergeBatchSingle(items as BatchSingleItem[])
  const rowsByKey = new Map(result.sheet.rows.map(row => [row.key, row]))
  result.sheet.rows = sheet.rows.map((row, index) => ({ ...rowsByKey.get(savedKeys.get(row.key)!)!, number: index + 1 }))
  return result
}
