import { parsePurchaseHtmlTable, readUnifiedPurchaseClipboardResult } from './purchaseClipboard'
import { PURCHASE_PASTE_COLUMNS, UNIFIED_PURCHASE_PASTE_COLUMNS, SPECIAL_PURCHASE_PASTE_COLUMNS, SPECIAL_UNIFIED_PURCHASE_PASTE_COLUMNS, PURCHASE_PASTE_LIMIT, parsePurchaseClipboard, validatePurchasePaste, type PurchasePasteColumns } from './purchasePaste'

const layoutMessage = '无法唯一识别这段数据的列序，请连同表头复制一次；本次未写入。'
const imageOrBlank = (value: string) => !value.trim() || /^(?:\[?图片\]?|\[?image\]?|=?_?xlfn\.DISPIMG\([\s\S]*\)|=?DISPIMG\([\s\S]*\))$/i.test(value.trim())
const trimSuffix = (row: string[]) => {
  const copy = [...row]
  while (copy.length && !copy[copy.length - 1]!.trim()) copy.pop()
  return copy
}

/** Recognize standard layouts only when every nonblank field has one meaning.
 * Never choose a layout by price magnitude, selected account, or a saved default. */
export function readAutoPurchaseClipboardResult(data: Pick<DataTransfer, 'getData'>, startColumn: number, columns: PurchasePasteColumns) {
  const html = parsePurchaseHtmlTable(data.getData('text/html'), PURCHASE_PASTE_LIMIT + 1, SPECIAL_UNIFIED_PURCHASE_PASTE_COLUMNS.length + 4, true)
  const source = html ?? parsePurchaseClipboard(data.getData('text/plain'))
  const hasHeader = source[0]?.some(value => value.normalize('NFKC').replace(/[\s*]/g, '').toLowerCase() === 'sku')
  if (hasHeader) {
    // Rectangularize only the empty suffix, including omitted trailing links.
    const width = Math.max(...source.map(row => trimSuffix(row).length))
    const rows = source.map(row => Array.from({ length: width }, (_, i) => row[i] || ''))
    const text = rows.map(row => row.map(value => `"${value.replace(/"/g, '""')}"`).join('\t')).join('\r\n')
    return readUnifiedPurchaseClipboardResult({ getData: type => type === 'text/plain' ? text : '' }, startColumn, { columns, sourceColumns: columns })
  }
  const rows = source.map(trimSuffix)
  while (rows.length && !rows[rows.length - 1]!.length) rows.pop()
  if (rows.length > PURCHASE_PASTE_LIMIT) throw new Error('每次最多100行，请分批粘贴')
  if (!rows.some(row => row.length)) return { rows: [], skippedImageColumns: false }
  if (startColumn > 0) {
    if (rows.some(row => row.length + startColumn > columns.length)) throw new Error('整行复制请从“报价日期”列粘贴；本次未写入')
    return { rows, skippedImageColumns: false }
  }
  const results = new Map<string, { rows: string[][]; skippedImageColumns: boolean }>()
  const layouts = [PURCHASE_PASTE_COLUMNS, SPECIAL_PURCHASE_PASTE_COLUMNS, UNIFIED_PURCHASE_PASTE_COLUMNS, SPECIAL_UNIFIED_PURCHASE_PASTE_COLUMNS]
    .filter(layout => layout.every(([, field]) => columns.some(c => c[1] === field)))
  for (const offset of [0, 3]) {
    if (offset && !rows.every(row => !row.length || (row.length >= 7 && row.slice(0, 3).every(imageOrBlank)))) continue
    const cells = rows.map(row => row.slice(offset))
    for (const layout of layouts) {
      if (cells.some(row => row.length > layout.length)) continue
      const mapped = cells.map(row => columns.map(([, field]) => row[layout.findIndex(c => c[1] === field)] || ''))
      // Validate each row separately: duplicate SKUs must not hide a malformed row.
      if (mapped.some(row => row.some(value => value.trim()) && validatePurchasePaste([row], true, columns).issues.length)) continue
      // Free-shipping inference is not layout evidence: inspect the source flag.
      // Link columns also anchor the suffix so stock/factory/category cannot be
      // silently accepted as links after choosing the wrong invoice layout.
      if (mapped.some(row => columns.some(([, field], i) => {
        const value = row[i]!.trim()
        if (!value) return false
        if (field === 'freeShipping') return !['是', '否'].includes(value)
        if (field === 'invoiceType' || field === 'specialInvoiceType') return /^\d+(?:\.\d+)?[%％]?$/.test(value)
        if (/^sourceLink[123]$/.test(field) || field === 'similarSource') return !/^(?:https?:\/\/|www\.)\S+$/i.test(value)
        return false
      }))) continue
      results.set(JSON.stringify(mapped), { rows: mapped, skippedImageColumns: offset === 3 })
    }
  }
  if (results.size !== 1) throw new Error(layoutMessage)
  return [...results.values()][0]!
}
