import JSZip from 'jszip'

export const LOGISTICS_WORKBOOK_HEADERS = [
  '区域名称', '国家简码', '时效最早天数', '时效最晚天数', '禁运商品', '允许商品标记', '三边之和', '三边最大长度',
  '计泡系数', '最小长度', '最大长度', '最小宽度', '最大宽度', '最小侧面积', '最大侧面积', '起始重量', '截止重量',
  '起重', '运费单价', '最小计重', '首重', '首重价', '续重', '续重单价', '区间运费', '挂号费', '附加费',
  '燃油附加费率', '特殊品含量', '是否计抛', '是否禁止普货', '电话是否必需', '分区名称', '分区邮编前缀',
  '分区邮编', '分区城市', '分区省州', '排除',
] as const

export type LogisticsRateRow = {
  weightFromInclusive?: boolean; weightToInclusive?: boolean; quoteReady?: boolean
  sourceRow: number
  areaName: string; countryCode: string; etaMinDays: number; etaMaxDays: number
  prohibitedMarks: string; allowedMarks: string; maxPerimeterCm: number; maxSideCm: number
  volumeDivisor: number; minLengthCm: number; maxLengthCm: number; minWidthCm: number; maxWidthCm: number
  minSideAreaCm2: number; maxSideAreaCm2: number; weightFromKg: number; weightToKg: number
  startWeightKg: number; pricePerKg: number; minChargeWeightKg: number; firstWeightKg: number; firstWeightPrice: number
  nextWeightKg: number; nextWeightPrice: number; intervalPrice: number; registrationFee: number; surcharge: number
  fuelSurchargeRate: number; specialGoodsContent: string; volumetric: boolean; prohibitGeneralCargo: boolean; phoneRequired: boolean
  zoneName: string; zonePostalPrefix: string; zonePostalCode: string; zoneCity: string; zoneState: string; zoneExclude: boolean
  rowKey: string
}

export type LogisticsImportIssue = { row: number; field: string; message: string; level: 'error' | 'warning' }
export type LogisticsDiffField = { field: string; before: string | number | boolean; after: string | number | boolean; price: boolean }
export type LogisticsDiffRow = {
  key: string; type: 'added' | 'price' | 'rule' | 'removed' | 'unchanged'; risk: boolean
  row: LogisticsRateRow; previous?: LogisticsRateRow; changes: LogisticsDiffField[]; maxPercentChange: number | null
}
export type LogisticsDiffSummary = { added: number; price: number; rule: number; removed: number; unchanged: number; highRisk: number }
export type LogisticsImportPreview = {
  fileName: string; sourceHash: string; rows: LogisticsRateRow[]; issues: LogisticsImportIssue[]
  validRows: number; errors: number; warnings: number; diffRows: LogisticsDiffRow[]; summary: LogisticsDiffSummary
}

const PRICE_FIELDS: Array<[keyof LogisticsRateRow, string]> = [
  ['pricePerKg', '运费单价'], ['firstWeightPrice', '首重价'], ['nextWeightPrice', '续重单价'], ['intervalPrice', '区间运费'],
  ['registrationFee', '挂号费'], ['surcharge', '附加费'], ['fuelSurchargeRate', '燃油附加费率'],
]
const RULE_FIELDS: Array<[keyof LogisticsRateRow, string]> = [
  ['etaMinDays', '时效最早天数'], ['etaMaxDays', '时效最晚天数'], ['prohibitedMarks', '禁运商品'], ['allowedMarks', '允许商品标记'],
  ['maxPerimeterCm', '三边之和'], ['maxSideCm', '三边最大长度'], ['volumeDivisor', '计泡系数'], ['minLengthCm', '最小长度'],
  ['maxLengthCm', '最大长度'], ['minWidthCm', '最小宽度'], ['maxWidthCm', '最大宽度'], ['minSideAreaCm2', '最小侧面积'],
  ['maxSideAreaCm2', '最大侧面积'], ['startWeightKg', '起重'], ['minChargeWeightKg', '最小计重'], ['firstWeightKg', '首重'],
  ['nextWeightKg', '续重'], ['specialGoodsContent', '特殊品含量'], ['volumetric', '是否计抛'], ['prohibitGeneralCargo', '是否禁止普货'],
  ['phoneRequired', '电话是否必需'], ['zoneExclude', '排除'],
]

function xml(text: string) {
  const document = new DOMParser().parseFromString(text, 'application/xml')
  if (document.querySelector('parsererror')) throw new Error('Excel 文件结构无法解析')
  return document
}
function elements(root: Document | Element, localName: string) { return [...root.getElementsByTagName('*')].filter(item => item.localName === localName) }
function columnIndex(reference: string) {
  const letters = reference.match(/^[A-Z]+/)?.[0] || ''
  return [...letters].reduce((total, letter) => total * 26 + letter.charCodeAt(0) - 64, 0) - 1
}
async function requiredText(zip: JSZip, path: string) {
  const file = zip.file(path)
  if (!file) throw new Error(`Excel 文件缺少必要内容：${path}`)
  return file.async('text')
}
function resolvePath(base: string, target: string) {
  if (target.startsWith('/')) return target.slice(1)
  const parts = base.split('/'); parts.pop()
  target.replace(/\\/g, '/').split('/').forEach(part => { if (part === '..') parts.pop(); else if (part && part !== '.') parts.push(part) })
  return parts.join('/')
}
function relationTarget(document: Document, id: string) {
  return elements(document, 'Relationship').find(item => item.getAttribute('Id') === id)?.getAttribute('Target') || ''
}
async function firstSheetPath(zip: JSZip) {
  const workbook = xml(await requiredText(zip, 'xl/workbook.xml'))
  const relationships = xml(await requiredText(zip, 'xl/_rels/workbook.xml.rels'))
  const sheet = elements(workbook, 'sheet')[0]
  if (!sheet) throw new Error('Excel 中没有可读取的工作表')
  const relationId = sheet.getAttribute('r:id') || [...sheet.attributes].find(item => item.localName === 'id')?.value || ''
  const target = relationTarget(relationships, relationId)
  if (!target) throw new Error('无法定位 Excel 工作表')
  return resolvePath('xl/workbook.xml', target)
}
async function readRows(zip: JSZip, sheetPath: string) {
  const sharedFile = zip.file('xl/sharedStrings.xml')
  const shared = sharedFile ? elements(xml(await sharedFile.async('text')), 'si').map(item => item.textContent || '') : []
  const sheet = xml(await requiredText(zip, sheetPath))
  const rows = new Map<number, unknown[]>()
  const formulas = new Map<string, string>()
  elements(sheet, 'c').forEach(cell => {
    const ref = cell.getAttribute('r') || ''
    const rowNumber = Number(ref.match(/\d+$/)?.[0] || 0)
    if (!rowNumber) return
    const col = columnIndex(ref)
    const type = cell.getAttribute('t') || ''
    const raw = elements(cell, 'v')[0]?.textContent ?? ''
    const inline = elements(cell, 'is')[0]?.textContent ?? ''
    const formula = elements(cell, 'f')[0]?.textContent?.trim() || ''
    const value: unknown = type === 's' ? shared[Number(raw)] ?? '' : type === 'inlineStr' ? inline : type === 'b' ? raw === '1' : raw === '' ? '' : Number.isFinite(Number(raw)) ? Number(raw) : raw
    if ((value === '' || value == null) && formula) formulas.set(ref, formula.replace(/^=/, ''))
    const row = rows.get(rowNumber) || []
    row[col] = value
    rows.set(rowNumber, row)
  })
  formulas.forEach((formula, ref) => {
    const target = formula.match(/^\$?([A-Z]+)\$?(\d+)$/)
    if (!target) return
    const rowNumber = Number(ref.match(/\d+$/)?.[0] || 0)
    const col = columnIndex(ref)
    const sourceRow = rows.get(Number(target[2])) || []
    const source = sourceRow[columnIndex(target[1])]
    if (source !== '' && source != null) (rows.get(rowNumber) || [])[col] = source
  })
  return rows
}
function text(value: unknown) { return String(value ?? '').trim() }
function number(value: unknown) {
  if (value === '' || value == null) return 0
  const parsed = Number(String(value).replace(/,/g, '').trim())
  return Number.isFinite(parsed) ? parsed : Number.NaN
}
function flag(value: unknown) { return /^(是|1|true|yes)$/i.test(text(value)) }
function rowIdentity(row: Omit<LogisticsRateRow, 'rowKey'>) {
  return [row.countryCode, row.areaName, row.zoneName, row.zonePostalPrefix, row.zonePostalCode, row.zoneCity, row.zoneState, row.weightFromKg, row.weightToKg].map(value => String(value).trim().toLowerCase()).join('|')
}
function parseRateRow(values: unknown[], sourceRow: number, issues: LogisticsImportIssue[], previous: LogisticsRateRow | null) {
  const getText = (index: number) => text(values[index])
  const getNumber = (index: number, field: string) => {
    const result = number(values[index])
    if (Number.isNaN(result)) { issues.push({ row: sourceRow, field, message: `“${text(values[index])}”不是有效数字`, level: 'error' }); return 0 }
    return result
  }
  const areaName = getText(0) || previous?.areaName || ''
  const countryCode = (getText(1) || previous?.countryCode || '').toUpperCase()
  const base = {
    sourceRow, areaName, countryCode, etaMinDays: getNumber(2, '时效最早天数'), etaMaxDays: getNumber(3, '时效最晚天数'),
    prohibitedMarks: getText(4), allowedMarks: getText(5), maxPerimeterCm: getNumber(6, '三边之和'), maxSideCm: getNumber(7, '三边最大长度'),
    volumeDivisor: getNumber(8, '计泡系数'), minLengthCm: getNumber(9, '最小长度'), maxLengthCm: getNumber(10, '最大长度'),
    minWidthCm: getNumber(11, '最小宽度'), maxWidthCm: getNumber(12, '最大宽度'), minSideAreaCm2: getNumber(13, '最小侧面积'),
    maxSideAreaCm2: getNumber(14, '最大侧面积'), weightFromKg: getNumber(15, '起始重量'), weightToKg: getNumber(16, '截止重量'),
    startWeightKg: getNumber(17, '起重'), pricePerKg: getNumber(18, '运费单价'), minChargeWeightKg: getNumber(19, '最小计重'),
    firstWeightKg: getNumber(20, '首重'), firstWeightPrice: getNumber(21, '首重价'), nextWeightKg: getNumber(22, '续重'),
    nextWeightPrice: getNumber(23, '续重单价'), intervalPrice: getNumber(24, '区间运费'), registrationFee: getNumber(25, '挂号费'),
    surcharge: getNumber(26, '附加费'), fuelSurchargeRate: getNumber(27, '燃油附加费率'), specialGoodsContent: getText(28),
    volumetric: flag(values[29]) || getNumber(8, '计泡系数') > 0, prohibitGeneralCargo: flag(values[30]), phoneRequired: flag(values[31]),
    zoneName: getText(32), zonePostalPrefix: getText(33), zonePostalCode: getText(34), zoneCity: getText(35), zoneState: getText(36), zoneExclude: flag(values[37]),
  }
  if (!base.areaName) issues.push({ row: sourceRow, field: '区域名称', message: '区域名称不能为空', level: 'error' })
  if (!base.countryCode) issues.push({ row: sourceRow, field: '国家简码', message: '国家简码不能为空', level: 'error' })
  if (base.countryCode && !/^[A-Z0-9-]{2,10}$/.test(base.countryCode)) issues.push({ row: sourceRow, field: '国家简码', message: `国家简码“${base.countryCode}”格式异常`, level: 'warning' })
  if (base.etaMaxDays && base.etaMinDays > base.etaMaxDays) issues.push({ row: sourceRow, field: '预计时效', message: '最早天数不能大于最晚天数', level: 'error' })
  if (base.weightToKg <= base.weightFromKg) issues.push({ row: sourceRow, field: '重量区间', message: '截止重量必须大于起始重量', level: 'error' })
  if (!(base.pricePerKg > 0 || base.intervalPrice > 0 || (base.firstWeightKg > 0 && base.firstWeightPrice > 0))) issues.push({ row: sourceRow, field: '计费价格', message: '未填写有效的运费单价、区间运费或首重价格', level: 'error' })
  return { ...base, rowKey: rowIdentity(base) } as LogisticsRateRow
}
function same(a: unknown, b: unknown) { return typeof a === 'number' || typeof b === 'number' ? Math.abs(Number(a || 0) - Number(b || 0)) < 1e-9 : a === b }
function legacyComparableIdentity(row: LogisticsRateRow) {
  return [row.countryCode, row.areaName, row.zoneName, row.weightFromKg, row.weightToKg]
    .map(value => String(value || '').trim().toLowerCase()).join('|')
}
export function compareLogisticsRows(rows: LogisticsRateRow[], previousRows: LogisticsRateRow[] = []) {
  const oldMap = new Map(previousRows.map(row => [row.rowKey, row]))
  const oldFallbackGroups = new Map<string, LogisticsRateRow[]>()
  const nextFallbackCounts = new Map<string, number>()
  previousRows.forEach(row => { const key = legacyComparableIdentity(row); oldFallbackGroups.set(key, [...(oldFallbackGroups.get(key) || []), row]) })
  rows.forEach(row => { const key = legacyComparableIdentity(row); nextFallbackCounts.set(key, (nextFallbackCounts.get(key) || 0) + 1) })
  const matchedPrevious = new Set<LogisticsRateRow>()
  const diffRows: LogisticsDiffRow[] = rows.map(row => {
    let previous = oldMap.get(row.rowKey)
    if (!previous) {
      const fallbackKey = legacyComparableIdentity(row)
      const candidates = oldFallbackGroups.get(fallbackKey) || []
      // legacy-v1 did not retain postal/city/state fields. Only use the reduced identity when it is unique on both sides.
      if (candidates.length === 1 && nextFallbackCounts.get(fallbackKey) === 1) previous = candidates[0]
    }
    if (!previous) return { key: row.rowKey, type: 'added', risk: false, row, changes: [], maxPercentChange: null }
    matchedPrevious.add(previous)
    const priceChanges = PRICE_FIELDS.filter(([key]) => !same(previous[key], row[key])).map(([key, field]) => ({ field, before: previous[key] as number, after: row[key] as number, price: true }))
    const ruleChanges = RULE_FIELDS.filter(([key]) => !same(previous[key], row[key])).map(([key, field]) => ({ field, before: previous[key] as string | number | boolean, after: row[key] as string | number | boolean, price: false }))
    const changes = [...priceChanges, ...ruleChanges]
    const percentages = priceChanges.map(change => Number(change.before) === 0 ? (Number(change.after) > 0 ? 100 : 0) : Math.abs((Number(change.after) - Number(change.before)) / Number(change.before) * 100))
    const maxPercentChange = percentages.length ? Math.max(...percentages) : null
    return { key: row.rowKey, type: priceChanges.length ? 'price' : ruleChanges.length ? 'rule' : 'unchanged', risk: maxPercentChange != null && maxPercentChange > 10, row, previous, changes, maxPercentChange }
  })
  previousRows.filter(row => !matchedPrevious.has(row)).forEach(row => diffRows.push({ key: row.rowKey, type: 'removed', risk: false, row, previous: row, changes: [], maxPercentChange: null }))
  const summary = diffRows.reduce<LogisticsDiffSummary>((result, row) => { result[row.type] += 1; if (row.risk) result.highRisk += 1; return result }, { added: 0, price: 0, rule: 0, removed: 0, unchanged: 0, highRisk: 0 })
  return { diffRows, summary }
}
async function sha256(buffer: ArrayBuffer) {
  const digest = await crypto.subtle.digest('SHA-256', buffer)
  return [...new Uint8Array(digest)].map(byte => byte.toString(16).padStart(2, '0')).join('')
}
export async function parseLogisticsWorkbook(file: File, previousRows: LogisticsRateRow[] = []): Promise<LogisticsImportPreview> {
  if (!file.name.toLowerCase().endsWith('.xlsx')) throw new Error('请选择 .xlsx 格式的物流标准模板')
  const buffer = await file.arrayBuffer()
  const zip = await JSZip.loadAsync(buffer)
  const rows = await readRows(zip, await firstSheetPath(zip))
  const headers = LOGISTICS_WORKBOOK_HEADERS.map((_, index) => text(rows.get(3)?.[index]))
  const mismatches = LOGISTICS_WORKBOOK_HEADERS.filter((header, index) => headers[index] !== header)
  if (mismatches.length) throw new Error(`模板列头不匹配：${mismatches.slice(0, 4).join('、')}${mismatches.length > 4 ? '等' : ''}`)
  const issues: LogisticsImportIssue[] = []
  const parsed: LogisticsRateRow[] = []
  let previous: LogisticsRateRow | null = null
  const maxRow = Math.max(0, ...rows.keys())
  for (let rowNumber = 4; rowNumber <= maxRow; rowNumber += 1) {
    const values = rows.get(rowNumber) || []
    if (!values.slice(0, 38).some(value => value !== '' && value != null)) continue
    const beforeIssues = issues.length
    const row = parseRateRow(values, rowNumber, issues, previous)
    previous = row
    if (!issues.slice(beforeIssues).some(issue => issue.level === 'error')) parsed.push(row)
  }
  if (!parsed.length) throw new Error('模板中没有可导入的有效价格行')
  const seen = new Map<string, number>()
  const uniqueRows = parsed.filter(row => {
    const first = seen.get(row.rowKey)
    if (first != null) { issues.push({ row: row.sourceRow, field: '价格区间', message: `与第${first}行的国家、分区和重量区间重复，已跳过`, level: 'error' }); return false }
    seen.set(row.rowKey, row.sourceRow); return true
  })
  const groups = new Map<string, LogisticsRateRow[]>()
  uniqueRows.forEach(row => { const key = [row.countryCode, row.zoneName, row.zonePostalPrefix, row.zonePostalCode, row.zoneCity, row.zoneState].join('|'); groups.set(key, [...(groups.get(key) || []), row]) })
  groups.forEach(group => group.sort((a, b) => a.weightFromKg - b.weightFromKg).forEach((row, index) => {
    const previousRow = group[index - 1]
    if (previousRow && row.weightFromKg < previousRow.weightToKg) issues.push({ row: row.sourceRow, field: '重量区间', message: `与第${previousRow.sourceRow}行存在重量区间重叠，请复核`, level: 'warning' })
  }))
  const comparison = compareLogisticsRows(uniqueRows, previousRows)
  return {
    fileName: file.name, sourceHash: await sha256(buffer), rows: uniqueRows, issues,
    validRows: uniqueRows.length, errors: issues.filter(issue => issue.level === 'error').length, warnings: issues.filter(issue => issue.level === 'warning').length,
    ...comparison,
  }
}
