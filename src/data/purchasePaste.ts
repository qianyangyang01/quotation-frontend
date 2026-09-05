import { normalizePurchaseRecord, type PurchaseProductRecord } from './purchaseStore'

// Column order is the user's 采购新模版导入 (2).xlsx, Sheet1 A1:AF1.
export const PURCHASE_PASTE_COLUMNS = [
  ['报价日期*', 'quotationDate'], ['报价人*', 'quotationOwner'], ['备注', 'notes'], ['SKU', 'sku'],
  ['克重(g)*', 'weightG'], ['尺码', 'size'], ['颜色', 'color'], ['材质', 'material'],
  ['长(cm)*', 'lengthCm'], ['宽(cm)*', 'widthCm'], ['高(cm)*', 'heightCm'], ['起订量(件)*', 'minOrderQty'],
  ['基准采购单价(CNY/件)*', 'purchasePriceCny'], ['阶梯价2起订量', 'tier2MinQty'], ['阶梯价2(CNY/件)', 'tier2PriceCny'],
  ['阶梯价3起订量', 'tier3MinQty'], ['阶梯价3(CNY/件)', 'tier3PriceCny'], ['1件总运费(CNY)', 'singleFreightCny'],
  ['10件总运费(CNY)', 'freight10Cny'], ['100件总运费(CNY)', 'freight100Cny'], ['是否包邮', 'freeShipping'],
  ['含票价(CNY/件)', 'taxIncludedPriceCny'], ['票点', 'taxPoint'], ['票类型', 'invoiceType'], ['类别', 'category'],
  ['是否有货*', 'stockStatus'], ['工厂信息', 'factoryInfo'], ['审核备注', 'auditNotes'],
  ['货源链接1', 'sourceLink1'], ['货源链接2', 'sourceLink2'], ['货源链接3', 'sourceLink3'], ['相似货源', 'similarSource'],
] as const
export const PURCHASE_PASTE_LIMIT = 100
export const emptyPurchasePasteRow = () => PURCHASE_PASTE_COLUMNS.map(() => '')

/** Excel/WPS TSV: quoted newlines, escaped quotes and empty columns retain their positions. */
export function parsePurchaseClipboard(text: string): string[][] {
  if (text.length > 1_000_000) throw new Error('粘贴内容过大，每次最多100行')
  const rows: string[][] = []; let row: string[] = []; let cell = ''; let quoted = false
  const source = text.replace(/^\uFEFF/, '')
  // WPS can separate records with CRLF but leave unquoted LF inside a cell.
  // Detect record separators before normalizing line endings, and ignore quoted CRLF.
  let scanQuoted = false; let scanCellStart = true; let crlfRows = false
  for (let i = 0; i < source.length; i++) {
    const ch = source[i]
    if (ch === '"' && (scanQuoted || scanCellStart)) {
      if (scanQuoted && source[i + 1] === '"') i++
      else scanQuoted = !scanQuoted
      scanCellStart = false
    } else if (!scanQuoted && ch === '\r' && source[i + 1] === '\n') { crlfRows = true; break }
    else scanCellStart = !scanQuoted && (ch === '\t' || ch === '\n' || ch === '\r')
  }
  for (let i = 0; i < source.length; i++) {
    const ch = source[i]
    if (ch === '"' && (quoted || cell === '')) {
      if (quoted && source[i + 1] === '"') { cell += '"'; i++ } else quoted = !quoted
    } else if (!quoted && (ch === '\t' || ch === '\r' || (ch === '\n' && !crlfRows))) {
      row.push(cell); cell = ''
      if (ch !== '\t') { rows.push(row); row = []; if (ch === '\r' && source[i + 1] === '\n') i++ }
    } else if (ch === '\r') { cell += '\n'; if (source[i + 1] === '\n') i++ }
    else cell += ch
  }
  if (quoted) throw new Error('粘贴内容中的引号未闭合，请重新复制完整单元格')
  row.push(cell); rows.push(row)
  while (rows.length && rows[rows.length - 1]!.every(value => !value.trim())) rows.pop()
  return rows
}

export function applyPurchasePaste(grid: string[][], clipboard: string[][], startRow: number, startCol: number) {
  if (startRow + clipboard.length > PURCHASE_PASTE_LIMIT) throw new Error('每次最多100行，请分批粘贴')
  if (clipboard.some(row => startCol + row.length > PURCHASE_PASTE_COLUMNS.length)) throw new Error('粘贴列数超出模板，请检查起始列；本次未写入')
  const next = grid.map(row => [...row])
  while (next.length < startRow + clipboard.length) next.push(emptyPurchasePasteRow())
  clipboard.forEach((row, r) => row.forEach((value, c) => {
    // Blank clipboard cells skip the target but never shift later columns.
    if (value.trim()) next[startRow + r]![startCol + c] = value
  }))
  return next
}

export type PurchasePasteIssue = { row: number; column: number; message: string }
const numericFields = new Set(['weightG', 'lengthCm', 'widthCm', 'heightCm', 'minOrderQty', 'purchasePriceCny', 'tier2MinQty', 'tier2PriceCny', 'tier3MinQty', 'tier3PriceCny', 'singleFreightCny', 'freight10Cny', 'freight100Cny', 'taxIncludedPriceCny', 'taxPoint'])
export function validatePurchasePaste(grid: string[][]) {
  const issues: PurchasePasteIssue[] = []; const records: PurchaseProductRecord[] = []; const seen = new Map<string, number>()
  grid.forEach((cells, row) => {
    if (cells.every(cell => !cell.trim())) return
    const data: Record<string, unknown> = { skuOrigin: 'manual', dataSource: 'standard', sourceSheet: '采购粘贴新增', sourceRow: row + 1 }
    const issue = (field: string, message: string) => issues.push({ row, column: PURCHASE_PASTE_COLUMNS.findIndex(c => c[1] === field), message })
    PURCHASE_PASTE_COLUMNS.forEach(([, field], col) => {
      const raw = (cells[col] || '').trim()
      if (!numericFields.has(field)) { data[field] = raw; return }
      if (!raw) { data[field] = null; return }
      const numeric = field === 'taxPoint' ? raw.replace(/[％%]$/, '') : raw
      const value = Number(numeric)
      if (!/^(?:\d+(?:\.\d*)?|\.\d+)$/.test(numeric) || !Number.isFinite(value) || value < 0) issue(field, '请填写有效非负数字')
      data[field] = field === 'taxPoint' && (/[％%]$/.test(raw) || value > 1) ? value / 100 : value
      if (field === 'taxPoint' && Number(data[field]) > 1) issue(field, '票点须在0%至100%之间')
    })
    const sku = String(data.sku).toUpperCase().replace(/\s+/g, '')
    data.sku = sku
    if (!/^[A-Z0-9._/-]{1,96}$/.test(sku) || /^(TESTP|TEST|DEMO|MOCK|AUTO-)/i.test(sku)) issue('sku', '请填写有效的正式SKU')
    if (sku) {
      if (seen.has(sku)) { issue('sku', `与第${seen.get(sku)! + 1}行SKU重复`); issues.push({ row: seen.get(sku)!, column: 3, message: `与第${row + 1}行SKU重复` }) } else seen.set(sku, row)
    }
    for (const field of ['weightG', 'minOrderQty']) {
      if (data[field] == null || Number(data[field]) <= 0) issue(field, '必填，须大于0')
    }
    if (data.purchasePriceCny == null) issue('purchasePriceCny', '请填写基准采购单价')
    for (const field of ['minOrderQty', 'tier2MinQty', 'tier3MinQty']) if (data[field] != null && (!Number.isSafeInteger(data[field]) || Number(data[field]) <= 0)) issue(field, '起订量须为正整数')
    for (const [qty, price, previous] of [['tier2MinQty', 'tier2PriceCny', 'minOrderQty'], ['tier3MinQty', 'tier3PriceCny', 'tier2MinQty']]) {
      if ((data[qty!] == null) !== (data[price!] == null)) issue(data[qty!] == null ? qty! : price!, '阶梯起订量与价格须一起填写')
      if (data[qty!] != null && (data[previous!] == null || Number(data[qty!]) <= Number(data[previous!]))) issue(qty!, '须大于前一档起订量，请按顺序填写')
    }
    const dimensions = ['lengthCm', 'widthCm', 'heightCm']
    if (dimensions.some(field => data[field] != null)) dimensions.forEach(field => { if (data[field] == null || Number(data[field]) <= 0) issue(field, '长宽高须一起填写且大于0；也可全部留空') })
    if (!['', '是', '否'].includes(String(data.freeShipping))) issue('freeShipping', '请填写是或否')
    if (data.freeShipping !== '是') for (const field of ['singleFreightCny', 'freight10Cny']) if (data[field] == null) issue(field, '未包邮时须填写运费；免运费请明确填0或选择包邮')
    if (data.stockStatus === '有') data.stockStatus = '有货'
    if (data.stockStatus === '无') data.stockStatus = '无货'
    if (!['', '有货', '无货', '待确认', '定制款'].includes(String(data.stockStatus))) issue('stockStatus', '请填写有/有货、无/无货、待确认或定制款')
    if (String(data.quotationDate)) {
      const date = String(data.quotationDate).replace(/[./]/g, '-'); const match = /^(\d{4})-(\d{1,2})-(\d{1,2})$/.exec(date)
      const canonical = match ? `${match[1]}-${match[2]!.padStart(2, '0')}-${match[3]!.padStart(2, '0')}` : ''
      const parsed = new Date(canonical)
      if (!canonical || Number.isNaN(parsed.getTime()) || parsed.toISOString().slice(0, 10) !== canonical) issue('quotationDate', '日期格式应为2026-09-05或2026.9.5')
      else data.quotationDate = canonical
    }
    records.push(normalizePurchaseRecord(data as Partial<PurchaseProductRecord>))
  })
  return { issues, records, canSave: records.length > 0 && records.length <= PURCHASE_PASTE_LIMIT && issues.length === 0 }
}
