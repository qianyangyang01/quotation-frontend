import type { QuotationRecord, QuotationRecordQuoteOption } from './quotationRecords'

export type QuotationDealDifferenceKind = 'pending' | 'lost' | 'missing' | 'equal' | 'lower' | 'higher'
export type QuotationDealDifferenceLine = {
  id: string
  label: string
  quantity: number
  unitPriceUsd: number
  systemUsd: number | null
  actualUsd: number
  differenceUsd: number | null
  percent: number | null
}

export type QuotationDealDifference = {
  kind: QuotationDealDifferenceKind
  label: string
  systemUsd: number
  actualUsd: number
  differenceUsd: number
  percent: number | null
  lines: QuotationDealDifferenceLine[]
}

function recordOptions(row: QuotationRecord) { return row.quoteOptions || [] }
function primaryOption(row: QuotationRecord) { return recordOptions(row).find(option => option.isPrimary) || recordOptions(row)[0] }
function optionLabel(option: QuotationRecordQuoteOption) { return `${option.country}${option.quoteRegion ? `（${option.quoteRegion}）` : ''} · ${option.channel}${option.carrier && option.carrier !== option.channel ? ` · ${option.carrier}` : ''}` }

function quoteTierForQuantity(row: QuotationRecord, option: QuotationRecordQuoteOption | undefined, quantity: number) {
  if (!option) return null
  if (quantity === 1) return option.quote1Usd ?? (option.isPrimary ? row.systemQuoteUsd : null)
  if (quantity === 2) return option.quote2Usd
  if (quantity === 3) return option.quote3Usd
  if (row.customQuoteQuantity && quantity === row.customQuoteQuantity) return option.quoteCustomUsd
  return null
}

export function quotationDealDifferenceLines(row: QuotationRecord): QuotationDealDifferenceLine[] {
  const lines = (row.dealLines || []).map(line => {
    const option = recordOptions(row).find(item => item.id === line.optionId)
    const systemUsd = quoteTierForQuantity(row, option, line.quantity)
    const actualUsd = Number((line.unitPriceUsd * line.quantity).toFixed(2))
    const differenceUsd = systemUsd == null ? null : Number((actualUsd - systemUsd).toFixed(2))
    const percent = systemUsd && differenceUsd != null ? Number((differenceUsd / systemUsd * 100).toFixed(1)) : null
    return { id: line.id, label: line.optionLabel, quantity: line.quantity, unitPriceUsd: line.unitPriceUsd, systemUsd, actualUsd, differenceUsd, percent }
  })
  if (!lines.length && row.status === 'won' && row.actualQuoteUsd != null) {
    const quantity = Math.max(1, Math.floor(row.dealQuantity || 1))
    const option = recordOptions(row).find(item => item.id === row.dealOptionId) || primaryOption(row)
    const systemUsd = quoteTierForQuantity(row, option, quantity) ?? (quantity === 1 ? row.systemQuoteUsd : null)
    const differenceUsd = systemUsd == null ? null : Number((row.actualQuoteUsd - systemUsd).toFixed(2))
    const percent = systemUsd && differenceUsd != null ? Number((differenceUsd / systemUsd * 100).toFixed(1)) : null
    lines.push({ id: 'legacy', label: row.dealOptionLabel || (option ? optionLabel(option) : `${row.country} · ${row.channel}`), quantity, unitPriceUsd: row.actualQuoteUsd / quantity, systemUsd, actualUsd: row.actualQuoteUsd, differenceUsd, percent })
  }
  return lines
}

export function quotationDealDifference(row: QuotationRecord): QuotationDealDifference {
  if (row.status === 'pending') return { kind: 'pending', label: '待回填', systemUsd: 0, actualUsd: 0, differenceUsd: 0, percent: null, lines: [] }
  if (row.status === 'lost') return { kind: 'lost', label: '未成交', systemUsd: 0, actualUsd: 0, differenceUsd: 0, percent: null, lines: [] }
  const lines = quotationDealDifferenceLines(row)
  if (!lines.length || lines.some(line => line.systemUsd == null)) return { kind: 'missing', label: '缺少基准', systemUsd: Number(lines.reduce((sum, line) => sum + (line.systemUsd || 0), 0).toFixed(2)), actualUsd: Number(lines.reduce((sum, line) => sum + line.actualUsd, 0).toFixed(2)), differenceUsd: 0, percent: null, lines }
  const systemUsd = Number(lines.reduce((sum, line) => sum + (line.systemUsd || 0), 0).toFixed(2))
  const actualUsd = Number(lines.reduce((sum, line) => sum + line.actualUsd, 0).toFixed(2))
  const differenceUsd = Number((actualUsd - systemUsd).toFixed(2))
  const percent = systemUsd ? Number((differenceUsd / systemUsd * 100).toFixed(1)) : null
  const kind: QuotationDealDifferenceKind = Math.abs(differenceUsd) < 0.01 ? 'equal' : differenceUsd < 0 ? 'lower' : 'higher'
  const label = kind === 'equal' ? '一致' : Math.abs(percent || 0) > 10 ? '重大偏差' : kind === 'lower' ? '成交价偏低' : '成交价偏高'
  return { kind, label, systemUsd, actualUsd, differenceUsd, percent, lines }
}

export function signedUsd(value: number | null) { return value == null ? '—' : `${value > 0 ? '+' : value < 0 ? '−' : ''}$${Math.abs(value).toFixed(2)}` }
export function signedPercent(value: number | null) { return value == null ? '—' : `${value > 0 ? '+' : ''}${value.toFixed(1)}%` }
