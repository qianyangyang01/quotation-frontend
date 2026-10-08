import { parsePurchaseHtmlTable } from './purchaseClipboard'
import { parsePurchaseClipboard } from './purchasePaste'

export const FOB_COLUMNS = [
  ['SKU', 'sku'], ['克重(g)', 'weightRaw'], ['起订量', 'moqRaw'], ['采购价格原文', 'priceRaw'],
  ['运费原文', 'freightRaw'], ['类别', 'category'], ['备注', 'notes'], ['报价人', 'quotationOwner'],
  ['报价日期', 'quotationDate'], ['尺码', 'size'], ['颜色', 'color'], ['材质', 'material'], ['工厂信息', 'factoryInfo'],
] as const
export type FobField = typeof FOB_COLUMNS[number][1]
export type FobPatch = Partial<Record<FobField, string>> & { sku: string }
const skuPattern = /^[A-Z][A-Z0-9._/-]{1,95}$/i
const aliases: Record<string, FobField> = {
  sku: 'sku', '克重/g': 'weightRaw', '克重(g)': 'weightRaw', '重量(g)': 'weightRaw',
  '起订量': 'moqRaw', '起订量(件)': 'moqRaw', '单价': 'priceRaw', '采购价格原文': 'priceRaw',
  '运费/试拍或议价': 'freightRaw', '运费原文': 'freightRaw', '类别': 'category', '备注': 'notes',
  '报价人': 'quotationOwner', '报价日期': 'quotationDate', '尺码': 'size', '颜色/sku': 'color',
  '颜色': 'color', '材质': 'material', '工厂信息': 'factoryInfo',
}
const normalizeHeader = (s: string) => s.toLowerCase().replace(/[\s*]/g, '').replace(/（/g, '(').replace(/）/g, ')')
// Source: 国际站批发报价.xlsx, A:AD. Q/R/T/U are deliberately not purchase tier/freight sources.
const sourceColumns: Partial<Record<number, FobField>> = {
  2: 'sku', 3: 'quotationOwner', 5: 'quotationDate', 6: 'notes', 7: 'weightRaw', 8: 'weightRaw',
  9: 'size', 10: 'color', 11: 'material', 12: 'moqRaw', 13: 'priceRaw', 15: 'freightRaw',
  18: 'category', 22: 'factoryInfo',
}

export function readFobClipboard(data: Pick<DataTransfer, 'getData'>): FobPatch[] {
  const html = parsePurchaseHtmlTable(data.getData('text/html'), 101)
  const source = html ?? parsePurchaseClipboard(data.getData('text/plain'))
  const rows = source.filter(row => row.some(cell => cell.trim()))
  if (!rows.length) throw new Error('剪贴板没有商品行，请在 Excel/WPS 中复制单元格区域')
  const header = rows[0]!.map(normalizeHeader)
  const hasHeader = header.includes('sku')
  let columns: Partial<Record<number, FobField>>
  if (hasHeader) {
    columns = {}
    header.forEach((label, i) => {
      const field = aliases[label]
      // Two weight columns are supported; the latter nonblank numeric source is preferred.
      if (field && (field === 'weightRaw' || !Object.values(columns).includes(field))) columns[i] = field
    })
    if (Object.keys(columns).length < 2) throw new Error('请同时复制SKU和需要更新字段的表头，或复制原表完整商品行')
    rows.shift()
  } else {
    const first = rows[0]!
    const offset = first.length >= 16 && skuPattern.test(first[2]?.trim() || '') ? 0
      : first.length >= 14 && skuPattern.test(first[0]?.trim() || '') ? 2 : -1
    if (offset < 0) throw new Error('未识别到国际站批发原表格式；请复制完整行、从SKU至运费列的连续区域，或带表头复制')
    columns = Object.fromEntries(Object.entries(sourceColumns).filter(([i]) => Number(i) >= offset).map(([i, field]) => [Number(i) - offset, field]))
  }
  if (!rows.length || rows.length > 100) throw new Error('每次支持1至100行商品，请分批粘贴')
  if (html == null && new Set(rows.map(row => row.length)).size > 1) throw new Error('行列边界不完整，请直接复制Excel/WPS单元格区域，不要经过聊天框或记事本')
  return rows.map((cells, i) => {
    if (!hasHeader && cells.length < (columns[2] === 'sku' ? 16 : 14)) throw new Error(`第${i + 1}行列数不完整，请重新复制`)
    const patch: FobPatch = { sku: '' }
    Object.entries(columns).forEach(([index, field]) => {
      const value = cells[Number(index)]?.trim()
      if (value && field) patch[field] = value
    })
    patch.sku = patch.sku.toUpperCase().replace(/\s+/g, '')
    if (!skuPattern.test(patch.sku)) throw new Error(`第${i + 1}行缺少有效SKU，请检查复制区域是否完整`)
    return patch
  })
}
