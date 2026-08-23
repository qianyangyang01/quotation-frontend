import JSZip from 'jszip'
import { normalizePurchaseRecord, type PurchaseProductRecord, type PurchaseStockStatus } from './purchaseStore'

export const PURCHASE_WORKBOOK_HEADERS = [
  'SKU*', '类别*', '产品图片（嵌入本格）', '实物图（嵌入本格）', '报价人*', '报价日期*', '尺码', '颜色',
  '克重(g)*', '长(cm)*', '宽(cm)*', '高(cm)*', '起订量(件)*', '基准采购单价(CNY/件)*',
  '阶梯价2起订量', '阶梯价2(CNY/件)', '阶梯价3起订量', '阶梯价3(CNY/件)',
  '1件总运费(CNY)', '10件总运费(CNY)', '100件总运费(CNY)', '是否包邮', '含票价(CNY/件)', '票类型',
  '是否有货*', '备注', '工厂信息', '货源链接1', '货源链接2', '货源链接3', '相似货源', '审核备注',
] as const

export type PurchaseImportIssue = { row: number; field: string; message: string; level: 'error' | 'warning' | 'skipped' }
export type PurchaseImportPreview = {
  fileName: string; records: PurchaseProductRecord[]; issues: PurchaseImportIssue[]
  totalRows: number; added: number; updated: number; generatedSku: number; productImages: number; physicalImages: number; skipped: number
  errorCount: number; warningCount: number; canConfirm: boolean
}

const RELATIONSHIP_NS = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships'

function xml(text: string) {
  const document = new DOMParser().parseFromString(text, 'application/xml')
  if (document.querySelector('parsererror')) throw new Error('Excel 文件中的 XML 结构无法解析')
  return document
}

function elements(root: Document | Element, localName: string) {
  return [...root.getElementsByTagName('*')].filter(item => item.localName === localName)
}

function resolvePath(base: string, target: string) {
  if (target.startsWith('/')) return target.slice(1)
  const parts = base.split('/')
  parts.pop()
  target.replace(/\\/g, '/').split('/').forEach(part => {
    if (!part || part === '.') return
    if (part === '..') parts.pop()
    else parts.push(part)
  })
  return parts.join('/')
}

function relationshipPath(partPath: string) {
  const parts = partPath.split('/')
  const file = parts.pop() || ''
  return [...parts, '_rels', `${file}.rels`].join('/')
}

async function requiredText(zip: JSZip, path: string) {
  const file = zip.file(path)
  if (!file) throw new Error(`Excel 文件缺少必要内容：${path}`)
  return file.async('text')
}

function relationTarget(document: Document, id: string) {
  const relation = elements(document, 'Relationship').find(item => item.getAttribute('Id') === id)
  return relation?.getAttribute('Target') || ''
}

function columnIndex(reference: string) {
  const letters = reference.match(/^[A-Z]+/)?.[0] || ''
  return [...letters].reduce((total, letter) => total * 26 + letter.charCodeAt(0) - 64, 0) - 1
}

function rowIndex(reference: string) { return Number(reference.match(/\d+$/)?.[0] || 0) }

function dataUrl(bytes: Uint8Array, mime: string) {
  let binary = ''
  const chunk = 0x8000
  for (let index = 0; index < bytes.length; index += chunk) binary += String.fromCharCode(...bytes.subarray(index, index + chunk))
  return `data:${mime};base64,${btoa(binary)}`
}

function imageMime(path: string) {
  const extension = path.split('.').pop()?.toLowerCase()
  return extension === 'jpg' || extension === 'jpeg' ? 'image/jpeg' : extension === 'gif' ? 'image/gif' : extension === 'webp' ? 'image/webp' : 'image/png'
}

function excelDate(value: number) {
  const date = new Date(Date.UTC(1899, 11, 30) + value * 86400000)
  return Number.isNaN(date.getTime()) ? '' : date.toISOString().slice(0, 10)
}

function normalizeDate(value: unknown, row: number, issues: PurchaseImportIssue[]) {
  if (value === '' || value == null) return ''
  if (typeof value === 'number') return excelDate(value)
  const text = String(value).trim()
  const normalized = text.replace(/[/.]/g, '-')
  const date = new Date(normalized)
  if (Number.isNaN(date.getTime())) {
    issues.push({ row, field: '报价日期*', message: `无法识别日期“${text}”，已留空`, level: 'warning' })
    return ''
  }
  return new Date(Date.UTC(date.getFullYear(), date.getMonth(), date.getDate())).toISOString().slice(0, 10)
}

function numeric(value: unknown, row: number, field: string, issues: PurchaseImportIssue[], integer = false) {
  if (value === '' || value == null) return null
  const text = String(value).trim()
  if (!/^-?\d+(?:\.\d+)?$/.test(text)) {
    issues.push({ row, field, message: `“${text}”不是有效数字，已留空`, level: 'warning' })
    return null
  }
  const result = Number(text)
  if (!Number.isFinite(result) || result < 0) {
    issues.push({ row, field, message: `数值“${text}”无效，已留空`, level: 'warning' })
    return null
  }
  return integer ? Math.floor(result) : result
}

function choice<T extends string>(value: unknown, allowed: readonly T[], row: number, field: string, issues: PurchaseImportIssue[]) {
  const text = String(value ?? '').trim()
  if (!text) return ''
  if (allowed.includes(text as T)) return text as T
  issues.push({ row, field, message: `“${text}”不在可选值中，已留空`, level: 'warning' })
  return ''
}

function warnUrl(value: string, row: number, field: string, issues: PurchaseImportIssue[]) {
  if (!value) return
  try { new URL(value) } catch { issues.push({ row, field, message: `链接格式异常，将按文本保存`, level: 'warning' }) }
}

function generatedSku(row: number, now: Date) {
  const pad = (value: number) => String(value).padStart(2, '0')
  return `AUTO-${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}-${pad(now.getHours())}${pad(now.getMinutes())}${pad(now.getSeconds())}-R${row}`
}

async function workbookSheet(zip: JSZip) {
  const workbook = xml(await requiredText(zip, 'xl/workbook.xml'))
  const relationships = xml(await requiredText(zip, 'xl/_rels/workbook.xml.rels'))
  const sheet = elements(workbook, 'sheet').find(item => item.getAttribute('name') === '采购产品导入')
  if (!sheet) throw new Error('没有找到工作表“采购产品导入”')
  const relationId = sheet.getAttributeNS(RELATIONSHIP_NS, 'id') || sheet.getAttribute('r:id') || ''
  const target = relationTarget(relationships, relationId)
  if (!target) throw new Error('无法定位工作表“采购产品导入”')
  return resolvePath('xl/workbook.xml', target)
}

async function readCells(zip: JSZip, sheetPath: string) {
  const sharedStringsFile = zip.file('xl/sharedStrings.xml')
  const sharedStrings = sharedStringsFile ? elements(xml(await sharedStringsFile.async('text')), 'si').map(item => item.textContent || '') : []
  const sheet = xml(await requiredText(zip, sheetPath))
  const rows = new Map<number, unknown[]>()
  for (const cell of elements(sheet, 'c')) {
    const reference = cell.getAttribute('r') || ''
    const row = rowIndex(reference)
    const column = columnIndex(reference)
    if (row < 1 || column < 0) continue
    const type = cell.getAttribute('t') || ''
    const raw = type === 'inlineStr' ? elements(cell, 'is')[0]?.textContent || '' : elements(cell, 'v')[0]?.textContent || ''
    const value: unknown = type === 's' ? sharedStrings[Number(raw)] ?? '' : type === 'b' ? raw === '1' : type === 'str' || type === 'inlineStr' ? raw : raw === '' ? '' : Number(raw)
    const values = rows.get(row) || []
    values[column] = value
    rows.set(row, values)
  }
  return { sheet, rows }
}

async function readImages(zip: JSZip, sheetPath: string, sheet: Document) {
  const images = new Map<string, string>()
  const drawingNode = elements(sheet, 'drawing')[0]
  const drawingId = drawingNode?.getAttributeNS(RELATIONSHIP_NS, 'id') || drawingNode?.getAttribute('r:id') || ''
  const sheetRelationsFile = zip.file(relationshipPath(sheetPath))
  if (!drawingId || !sheetRelationsFile) return images
  const sheetRelationships = xml(await sheetRelationsFile.async('text'))
  const drawingTarget = relationTarget(sheetRelationships, drawingId)
  if (!drawingTarget) return images
  const drawingPath = resolvePath(sheetPath, drawingTarget)
  const drawing = xml(await requiredText(zip, drawingPath))
  const drawingRelationships = xml(await requiredText(zip, relationshipPath(drawingPath)))
  const anchors = [...elements(drawing, 'oneCellAnchor'), ...elements(drawing, 'twoCellAnchor')]
  for (const anchor of anchors) {
    const from = [...anchor.children].find(item => item.localName === 'from')
    const column = Number([...from?.children || []].find(item => item.localName === 'col')?.textContent || -1)
    const row = Number([...from?.children || []].find(item => item.localName === 'row')?.textContent || -1) + 1
    if ((column !== 2 && column !== 3) || row < 2) continue
    const blip = elements(anchor, 'blip')[0]
    const embed = blip?.getAttributeNS(RELATIONSHIP_NS, 'embed') || blip?.getAttribute('r:embed') || ''
    const target = relationTarget(drawingRelationships, embed)
    if (!target) continue
    const mediaPath = resolvePath(drawingPath, target)
    const media = zip.file(mediaPath)
    if (!media) continue
    images.set(`${row}:${column}`, dataUrl(await media.async('uint8array'), imageMime(mediaPath)))
  }
  return images
}

export async function parsePurchaseWorkbook(file: File, existing: PurchaseProductRecord[]): Promise<PurchaseImportPreview> {
  if (!file.name.toLowerCase().endsWith('.xlsx')) throw new Error('请选择 .xlsx 格式的采购模板')
  if (file.size > 30 * 1024 * 1024) throw new Error('Excel 文件不能超过 30MB')
  const zip = await JSZip.loadAsync(await file.arrayBuffer())
  const sheetPath = await workbookSheet(zip)
  const { sheet, rows } = await readCells(zip, sheetPath)
  const headers = rows.get(1) || []
  const columnName = (index: number) => {
    let result = ''
    for (let value = index + 1; value > 0; value = Math.floor((value - 1) / 26)) result = String.fromCharCode(65 + (value - 1) % 26) + result
    return result
  }
  const mismatch = PURCHASE_WORKBOOK_HEADERS.map((header, index) => String(headers[index] ?? '').trim() === header ? '' : `${columnName(index)}列应为“${header}”`).filter(Boolean)
  if (mismatch.length) throw new Error(`模板列头不匹配：${mismatch.slice(0, 4).join('；')}${mismatch.length > 4 ? '…' : ''}`)
  const images = await readImages(zip, sheetPath, sheet)
  const issues: PurchaseImportIssue[] = []
  const records: PurchaseProductRecord[] = []
  const seen = new Set<string>()
  const existingSkus = new Set(existing.map(item => item.sku))
  const now = new Date()
  const maximumRow = Math.max(1, ...rows.keys(), ...[...images.keys()].map(key => Number(key.split(':')[0])))
  let skipped = 0
  for (let row = 2; row <= maximumRow; row += 1) {
    const values = rows.get(row) || []
    const productImage = images.get(`${row}:2`) || ''
    const physicalImage = images.get(`${row}:3`) || ''
    if (![...values, productImage, physicalImage].some(value => String(value ?? '').trim())) continue
    let sku = String(values[0] ?? '').trim().toUpperCase().replace(/\s+/g, '')
    let skuOrigin: PurchaseProductRecord['skuOrigin'] = 'imported'
    const recordWarnings: string[] = []
    if (!sku) {
      sku = generatedSku(row, now)
      skuOrigin = 'system'
      const message = `SKU 为空，已生成 ${sku}，必须修改后才能参与报价`
      issues.push({ row, field: 'SKU*', message, level: 'warning' })
      recordWarnings.push(message)
    }
    if (seen.has(sku)) {
      issues.push({ row, field: 'SKU*', message: `同一文件内 SKU ${sku} 重复，本行已跳过`, level: 'skipped' })
      skipped += 1
      continue
    }
    seen.add(sku)
    const readNumber = (index: number, field: string, integer = false) => numeric(values[index], row, field, issues, integer)
    const sourceLink1 = String(values[27] ?? '').trim()
    const sourceLink2 = String(values[28] ?? '').trim()
    const sourceLink3 = String(values[29] ?? '').trim()
    const similarSource = String(values[30] ?? '').trim()
    warnUrl(sourceLink1, row, '货源链接1', issues); warnUrl(sourceLink2, row, '货源链接2', issues); warnUrl(sourceLink3, row, '货源链接3', issues); warnUrl(similarSource, row, '相似货源', issues)
    const issueStart = issues.length
    const record = normalizePurchaseRecord({
      sourceRow: row, sku, skuOrigin, category: String(values[1] ?? '').trim(), productImage, physicalImage,
      quotationOwner: String(values[4] ?? '').trim(), quotationDate: normalizeDate(values[5], row, issues), size: String(values[6] ?? '').trim(), color: String(values[7] ?? '').trim(),
      weightG: readNumber(8, '克重(g)*'), lengthCm: readNumber(9, '长(cm)*'), widthCm: readNumber(10, '宽(cm)*'), heightCm: readNumber(11, '高(cm)*'),
      minOrderQty: readNumber(12, '起订量(件)*', true), purchasePriceCny: readNumber(13, '基准采购单价(CNY/件)*'),
      tier2MinQty: readNumber(14, '阶梯价2起订量', true), tier2PriceCny: readNumber(15, '阶梯价2(CNY/件)'),
      tier3MinQty: readNumber(16, '阶梯价3起订量', true), tier3PriceCny: readNumber(17, '阶梯价3(CNY/件)'),
      singleFreightCny: readNumber(18, '1件总运费(CNY)'), freight10Cny: readNumber(19, '10件总运费(CNY)'), freight100Cny: readNumber(20, '100件总运费(CNY)'),
      freeShipping: choice(values[21], ['是', '否'] as const, row, '是否包邮', issues), taxIncludedPriceCny: readNumber(22, '含票价(CNY/件)'),
      invoiceType: choice(values[23], ['普票1%', '普票3%', '普票6%', '专票13%', '增值税专用发票', '增值税普通发票', '收据', '不开票'] as const, row, '票类型', issues),
      stockStatus: choice(values[24], ['有货', '无货', '待确认'] as const, row, '是否有货*', issues) as PurchaseStockStatus,
      notes: String(values[25] ?? '').trim(), factoryInfo: String(values[26] ?? '').trim(), sourceLink1, sourceLink2, sourceLink3, similarSource,
      auditNotes: String(values[31] ?? '').trim(), importWarnings: recordWarnings,
    })
    record.importWarnings.push(...issues.slice(issueStart).map(issue => issue.message))
    records.push(record)
  }
  const errorCount = issues.filter(issue => issue.level === 'error' || issue.level === 'skipped').length
  const warningCount = issues.filter(issue => issue.level === 'warning').length
  return {
    fileName: file.name, records, issues, totalRows: records.length + skipped,
    added: records.filter(item => !existingSkus.has(item.sku)).length,
    updated: records.filter(item => existingSkus.has(item.sku)).length,
    generatedSku: records.filter(item => item.skuOrigin === 'system').length,
    productImages: records.filter(item => item.productImage).length,
    physicalImages: records.filter(item => item.physicalImage).length,
    skipped, errorCount, warningCount, canConfirm: errorCount === 0,
  }
}
