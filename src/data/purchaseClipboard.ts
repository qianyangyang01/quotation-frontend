import { PURCHASE_PASTE_COLUMNS, PURCHASE_PASTE_LIMIT, parsePurchaseClipboard } from './purchasePaste'

const MAX_CLIPBOARD_LENGTH = 2_000_000
const ambiguousMessage = '复制内容的行列边界不完整，可能含单元格内换行。请直接在Excel/WPS中选择单元格区域复制（不要进入单元格编辑，也不要经过聊天框或记事本）；本次未写入。'

function cellText(node: Node): string {
  if (node.nodeType === 3) return node.textContent || ''
  if (node.nodeType !== 1) return ''
  const element = node as Element
  if (['SCRIPT', 'STYLE', 'IFRAME', 'OBJECT', 'TEMPLATE', 'IMG', 'SVG', 'CANVAS'].includes(element.tagName)) return ''
  if (element.tagName === 'BR') return '\n'
  const content = Array.from(element.childNodes, cellText).join('')
  return ['P', 'DIV', 'LI'].includes(element.tagName) ? `${content}\n` : content
}

/** Parse into an inert template; copied markup is never mounted or executed. */
export function parsePurchaseHtmlTable(html: string): string[][] | null {
  if (!html.trim()) return null
  if (html.length > MAX_CLIPBOARD_LENGTH) throw new Error('粘贴内容过大，每次最多100行')
  const template = document.createElement('template')
  template.innerHTML = html
  const tables = Array.from(template.content.querySelectorAll('table')).filter(table => !table.parentElement?.closest('table'))
  if (!tables.length) return null
  if (tables.length !== 1 || tables[0]!.querySelector('table')) throw new Error('请每次复制一个连续的表格区域；本次未写入')
  const table = tables[0]!
  const sourceRows = Array.from(table.rows)
  if (sourceRows.length > PURCHASE_PASTE_LIMIT) throw new Error('每次最多100行，请分批复制')
  const rows = sourceRows.map(tr => {
    const cells = Array.from(tr.cells)
    const values: string[] = []
    for (const cell of cells) {
      const value = Array.from(cell.childNodes, cellText).join('').replace(/\r\n?/g, '\n').replace(/\u00a0/g, ' ').trim()
      // Excel whole-row copies may collapse unused trailing columns into one blank cell.
      if (values.length >= PURCHASE_PASTE_COLUMNS.length + 3 && !value) continue
      // Merged cells cannot be assigned to independent product columns safely.
      if (cell.rowSpan !== 1 || cell.colSpan !== 1) throw new Error('复制区域含合并单元格，请取消合并后重新复制；本次未写入')
      values.push(value)
      if (values.length > PURCHASE_PASTE_COLUMNS.length + 3) throw new Error('采购字段之后还有非空列，请检查复制范围；本次未写入')
    }
    return values
  })
  while (rows.length && rows[rows.length - 1]!.every(value => !value.trim())) rows.pop()
  return rows
}

/** Prefer actual cells, as text/plain may lose the quotes around multiline Excel cells. */
export function readPurchaseClipboardResult(data: Pick<DataTransfer, 'getData'>, startColumn: number): { rows: string[][]; skippedImageColumns: boolean } {
  const htmlRows = parsePurchaseHtmlTable(data.getData('text/html'))
  let rows = htmlRows ?? parsePurchaseClipboard(data.getData('text/plain'))
  // Ignore only empty cells beyond the known source layout, never internal empty columns.
  rows = rows.map(row => {
    const copy = [...row]
    while (copy.length > 35 && !copy[copy.length - 1]!.trim()) copy.pop()
    return copy
  })
  const source = rows.filter(row => row.some(value => value.trim()))
  const imageOrBlank = (value: string) => !value.trim() || /^(?:\[?图片\]?|\[?image\]?|=?_?xlfn\.DISPIMG\([\s\S]*\)|=?DISPIMG\([\s\S]*\))$/i.test(value.trim())
  const imagePrefix = source.length > 0 && source.every(row => row.length >= 16 && row.slice(0, 3).every(imageOrBlank))
  // The complete source has 3 image columns + 32 fields. For a shorter range, require
  // the date/SKU/weight anchors as well, so optional blank business columns never shift.
  const skippedImageColumns = imagePrefix && source.every(row => (row.length >= 35 && (!row[3]!.trim() || /^\d{4}[./-]\d{1,2}[./-]\d{1,2}$/.test(row[3]!.trim()))) || (
    /^\d{4}[./-]\d{1,2}[./-]\d{1,2}$/.test(row[3]!.trim()) &&
    /^[A-Z0-9._/-]{1,96}$/i.test(row[6]!.trim()) && /^\d+(?:\.\d+)?$/.test(row[7]!.trim())
  ))
  if (skippedImageColumns) {
    if (startColumn !== 0) throw new Error('整行复制含前3列图片，请点击“报价日期”列后粘贴；本次未写入')
    rows = rows.map(row => row.slice(3))
  }
  if (rows.some(row => row.length + startColumn > PURCHASE_PASTE_COLUMNS.length)) throw new Error('粘贴列数超出模板，请确认前3列仅为图片或空白，并从“报价日期”列粘贴；本次未写入')
  if (htmlRows != null) return { rows, skippedImageColumns }
  const populated = rows.filter(row => row.some(value => value.trim()))
  if (populated.length > PURCHASE_PASTE_LIMIT) throw new Error('每次最多100行，请分批粘贴')
  // A rectangle copied as TSV has the same column count on each non-empty row.
  // Do not guess how to join jagged rows: that could move a real price to another product.
  if (new Set(populated.map(row => row.length)).size > 1) throw new Error(ambiguousMessage)
  // Full product rows must retain a SKU. Split multiline text can also form equal-width fragments.
  const skuColumn = PURCHASE_PASTE_COLUMNS.findIndex(column => column[1] === 'sku') - startColumn
  if (populated.length > 1 && populated[0]!.length >= 12 && skuColumn >= 0 && populated.some(row => !row[skuColumn]?.trim())) throw new Error(ambiguousMessage)
  return { rows, skippedImageColumns }
}

export function readPurchaseClipboard(data: Pick<DataTransfer, 'getData'>, startColumn: number): string[][] {
  return readPurchaseClipboardResult(data, startColumn).rows
}
