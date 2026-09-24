import Decimal from 'decimal.js'
import type { QuotationRecord, QuotationRecordQuoteOption } from './quotationRecords'

export type QuoteSheetContact = { agent: string; whatsapp: string }
export type CustomerPriceSnapshot = { hiddenOptionIds?: string[]; contact?: QuoteSheetContact; quantities: number[]; rows: Array<{ optionId: string; prices: Array<number | null> }> }
export type CapturedSheetPrices = { hiddenRowKeys?: string[]; contact?: QuoteSheetContact; quantities: number[]; rows: Array<{ key: string; prices: Array<number | null>; systemPrices: Array<number | null> }> }

export function recordHiddenOptionIds(record: QuotationRecord): string[] {
  return [...(record.customerQuote?.hiddenOptionIds ?? record.sheetQuote?.hiddenOptionIds ?? [])]
}

/** A presentation-only copy. Never mutate or recalculate the historical record. */
export function recordQuoteSheetVersion(record: QuotationRecord, version: 'full' | 'visible'): QuotationRecord {
  if (version === 'full') return record
  const hidden = new Set(recordHiddenOptionIds(record))
  const quoteOptions = record.quoteOptions?.filter(option => !hidden.has(option.id))
  if (hidden.size && !quoteOptions?.length) throw new Error('所有报价行均已隐藏，请选择完整报价单')
  return { ...record, quoteOptions }
}

export function normalizeQuoteSheetContact(value: unknown): QuoteSheetContact | undefined {
  if (!value || typeof value !== 'object') return undefined
  const contact = value as QuoteSheetContact
  return typeof contact.agent === 'string' && contact.agent.length <= 40 && typeof contact.whatsapp === 'string' && contact.whatsapp.length <= 40
    ? { agent: contact.agent, whatsapp: contact.whatsapp } : undefined
}

export function savedSystemPrice(record: QuotationRecord, option: QuotationRecordQuoteOption, quantity: number) {
  const index = record.systemQuantityQuotes?.quantities.indexOf(quantity) ?? -1
  const saved = record.systemQuantityQuotes?.rows.find(row => row.optionId === option.id)
  if (saved && index >= 0) return saved.prices[index] ?? null
  if (quantity === 1) return option.quote1Usd
  if (quantity === 2) return option.quote2Usd
  if (quantity === 3) return option.quote3Usd
  return quantity === (record.customQuoteQuantity || 0) ? option.quoteCustomUsd : null
}
export function recordCustomerPrices(record: QuotationRecord): CustomerPriceSnapshot {
  const saved = record.customerQuote ?? record.sheetQuote
  if (saved) return { hiddenOptionIds: recordHiddenOptionIds(record), ...(saved.contact ? { contact: { ...saved.contact } } : {}), quantities:[...saved.quantities], rows:saved.rows.map(row=>({optionId:row.optionId,prices:[...row.prices]})) }
  const quantities = [...new Set([1,2,3,record.customQuoteQuantity || 0])]
  return { quantities, rows: (record.quoteOptions || []).map(option => ({ optionId: option.id, prices: quantities.map(q => savedSystemPrice(record, option, q)) })) }
}
export function normalizeCustomerPrices(value: unknown): CustomerPriceSnapshot | undefined {
  if (!value || typeof value !== 'object') return undefined
  const v = value as CustomerPriceSnapshot
  if (!Array.isArray(v.quantities) || !Array.isArray(v.rows) || !v.quantities.length || v.quantities.length > 10 ||
    v.quantities.some(q=>!Number.isSafeInteger(q)||q<0) || new Set(v.quantities).size !== v.quantities.length ||
    v.rows.some(row=>!row || typeof row.optionId!=='string' || !Array.isArray(row.prices) || row.prices.length!==v.quantities.length || row.prices.some(p=>p!==null && (typeof p!=='number'||!Number.isFinite(p)||p<0)))) return undefined
  const contact = normalizeQuoteSheetContact(v.contact)
  const hiddenOptionIds = Array.isArray(v.hiddenOptionIds) ? [...new Set(v.hiddenOptionIds.filter(id => typeof id === 'string' && v.rows.some(row => row.optionId === id)))] : undefined
  return { ...(hiddenOptionIds ? { hiddenOptionIds } : {}), ...(contact ? { contact } : {}), quantities:[...v.quantities], rows:v.rows.map(row=>({optionId:row.optionId,prices:[...row.prices]})) }
}
export function priceComparison(record: QuotationRecord, snapshot = recordCustomerPrices(record)) {
  return snapshot.rows.flatMap(row => {
    const option = record.quoteOptions?.find(option=>option.id===row.optionId)
    if (!option) return []
    return snapshot.quantities.map((quantity,index) => {
      const system = savedSystemPrice(record,option,quantity), customer = row.prices[index] ?? null
      const sheetIndex=record.sheetQuote?.quantities.indexOf(quantity) ?? -1
      const sheetRow=record.sheetQuote?.rows.find(item=>item.optionId===row.optionId)
      const sheet = sheetRow && sheetIndex>=0 ? sheetRow.prices[sheetIndex] : system
      const difference = system==null || customer==null ? null : new Decimal(customer).minus(system).toDecimalPlaces(2).toNumber()
      const percent = difference==null || !system ? null : new Decimal(difference).div(system).mul(100).toDecimalPlaces(2).toNumber()
      return { option, quantity, system, sheet, customer, difference, percent, changed: system!==customer, recordEdited: sheet!==customer }
    })
  })
}
export function priceComparisonLabel(record: QuotationRecord) {
  const lines=priceComparison(record), changed=lines.filter(line=>line.changed && line.system!=null && line.customer!=null).length
  const missing=lines.filter(line=>line.system==null && line.customer!=null).length
  const blank=lines.filter(line=>line.system!=null && line.customer==null).length
  return [changed ? `${changed} 项已改价` : '', missing ? `${missing} 项无系统基准` : '', blank ? `${blank} 项未报价` : ''].filter(Boolean).join(' · ') || '与系统一致'
}
export function representativePriceDifference(record: QuotationRecord) {
  const changes=priceComparison(record).filter(line=>line.changed)
    .sort((a,b)=>(a.quantity||Infinity)-(b.quantity||Infinity))
  const first=changes[0]
  if (!first) return { label:'客户报价＝系统报价', detail:'查看明细', changed:false }
  const quantity=first.quantity ? `${first.quantity}${record.quoteMode==='bundle'?'套':'件'}` : '自定义数量'
  const difference=first.difference==null ? first.system==null ? '无系统基准' : '未报价' : `${first.difference<0?'-':first.difference>0?'+':''}$${Math.abs(first.difference).toFixed(2)}`
  return { label:`${quantity} · ${difference}`, detail:first.percent==null ? '查看明细' : `${first.percent>0?'+':''}${first.percent.toFixed(2)}% · 查看明细`, changed:true,
    channel:`${first.option.country} · ${first.option.quoteRegion || ''} · ${first.option.carrier} · ${first.option.channel}` }
}
