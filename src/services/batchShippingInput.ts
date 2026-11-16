import { manualPricingInput } from '@/data/quotationModes'
import { normalizeShippingNote, SHIPPING_NOTE_MAX_LENGTH } from '@/data/shippingQuotationNote'
import type { BatchSingleItem } from './batchSingleQuotation'

export interface ShippingBatchEntry {
  id: string
  weight: string
  note: string
  selected: boolean
  error?: string
  item?: BatchSingleItem
}
export function shippingEntryError(entry: Pick<ShippingBatchEntry, 'weight' | 'note'>) {
  if (!manualPricingInput('shipping-only', 0, entry.weight)) return '重量须大于0且不超过1000000克，最多3位小数'
  if (normalizeShippingNote(entry.note).length > SHIPPING_NOTE_MAX_LENGTH) return '备注不能超过200字'
  return ''
}
/** Excel columns: weight then optional note. Never deduplicate equal weights or notes. */
export function parseShippingPaste(text: string): Array<Pick<ShippingBatchEntry, 'weight' | 'note'>> {
  if (text.length > 30000) throw new Error('粘贴内容过长，每批最多50条')
  const rows = text.replace(/^\uFEFF/, '').split(/\r?\n/).filter(line => line.trim())
  if (!rows.length || rows.length > 50) throw new Error('请粘贴1至50行，未截断数据')
  return rows.map((line, index) => {
    const cells = line.split('\t')
    if (cells.length > 2 && cells.slice(2).some(cell => cell.trim())) throw new Error(`第${index + 1}行列数过多，请仅粘贴重量、备注两列`)
    const entry = { weight: cells[0]!.trim(), note: normalizeShippingNote(cells[1]) }
    const error = shippingEntryError(entry)
    if (error) throw new Error(`第${index + 1}行：${error}`)
    return entry
  })
}
