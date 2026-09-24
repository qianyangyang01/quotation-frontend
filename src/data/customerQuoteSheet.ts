import type { QuotationMatrixRow } from '@/components/quotation/types'
import { parseQuotePriceInput } from './quotePriceExpression'

/** Customer-facing composition of one set; keep quantities out of the underlying SKU identifiers. */
export function quoteSheetBundleSkus(items: ReadonlyArray<{ sku: string; quantityPerSet: number }>) {
  return items.filter(item => item.sku.trim()).map(item =>
    `${item.sku.trim()}${item.quantityPerSet >= 2 ? `*${item.quantityPerSet}` : ''}`)
}

/** Customer-facing source model. Only explicit numeric snapshots are captured for record saving. */
export type QuoteSheetSourceRow = Pick<QuotationMatrixRow,
  'country' | 'quoteRegion' | 'channelKey' | 'ruleId' | 'channelCode' | 'rule' | 'carrier' | 'transport' | 'eta' |
  'quote1' | 'quote2' | 'quote3' | 'quoteCustom' | 'available'>
export type QuoteSheetCountry = { name: string; code: string }
export type QuoteSheetRowEdits = { number?: string; country?: string; provider?: string; processingTime?: string; prices?: Record<string, string> }
export const QUOTE_SHEET_OPTIONAL_COLUMNS = [
  { key: 'country', label: 'Country', name: '国家', width: 215 },
  { key: 'provider', label: 'Logistics Provider', name: '物流商', width: 245 },
  { key: 'shippingTime', label: 'Shipping Time', name: '运输时效', width: 235 },
  { key: 'processingTime', label: 'Processing Time', name: '处理时间', width: 220 },
] as const
export type QuoteSheetOptionalColumn = typeof QUOTE_SHEET_OPTIONAL_COLUMNS[number]['key']
export type QuoteSheetEdits = { agent: string; date: string; shippingTimes: Record<string, string>; providerNames?: Record<string, string>; fields?: Record<string, QuoteSheetRowEdits>; title?: string; notes?: string[]; hiddenColumns?: QuoteSheetOptionalColumn[]; whatsapp?: string; columnOrder?: QuoteSheetColumnKey[] }
export const QUOTE_SHEET_COLUMN_ORDER = ['number', 'product', 'sku', 'prices', 'country', 'provider', 'shippingTime', 'processingTime'] as const
export type QuoteSheetColumnKey = typeof QUOTE_SHEET_COLUMN_ORDER[number]
export function normalizeQuoteSheetOrder(order: readonly QuoteSheetColumnKey[] = []) {
  return [...new Set([...order.filter(key => QUOTE_SHEET_COLUMN_ORDER.includes(key)), ...QUOTE_SHEET_COLUMN_ORDER])]
}
/** One ordered group per draggable header. Quantity columns always travel together. */
export function quoteSheetGroups(hiddenColumns: readonly QuoteSheetOptionalColumn[] = [], order?: readonly QuoteSheetColumnKey[], withPhotos = false) {
  const catalog = [
    ...quoteSheetColumns(hiddenColumns),
    { key: 'product' as const, label: 'Product', width: 240 },
    { key: 'prices' as const, label: 'Quote by Quantity (USD)', width: 0 },
  ]
  return normalizeQuoteSheetOrder(order).flatMap(key => {
    if (key === 'product' && !withPhotos) return []
    const column = catalog.find(column => column.key === key)
    return column ? [column] : []
  })
}
/** Shared by the accessible preview and spreadsheet clipboard. Photos are image-only. */
export function quoteSheetTextTable(sheet: CustomerQuoteSheet) {
  const groups = quoteSheetGroups(sheet.hiddenColumns, sheet.columnOrder).filter(column => column.key !== 'product')
  return [
    groups.flatMap(column => column.key === 'prices' ? sheet.quantityLabels.map(label => `${label} (USD)`) : [column.label]),
    ...sheet.rows.map(row => groups.flatMap(column => column.key === 'prices' ? row.prices.map(quoteSheetUsd) : [quoteSheetCell(row, column.key)])),
  ]
}
export function quoteSheetColumns(hiddenColumns: readonly QuoteSheetOptionalColumn[] = []) {
  return [
    { key: 'number' as const, label: 'No.', width: 85 },
    { key: 'sku' as const, label: 'SKU', width: 220 },
    ...QUOTE_SHEET_OPTIONAL_COLUMNS.filter(column => !hiddenColumns.includes(column.key)),
  ]
}
export function quoteSheetCell(row: CustomerQuoteSheetRow, key: ReturnType<typeof quoteSheetColumns>[number]['key']) {
  if (key === 'shippingTime' || key === 'processingTime') return formatShippingTime(row[key] ?? (key === 'processingTime' ? '1-2 workingdays' : '—'))
  return String(row[key] ?? '—')
}
export type QuoteSheetPriceCalculator = (row: QuoteSheetSourceRow, quantity: number) => number | null
export const MAX_QUOTE_SHEET_COLUMNS = 10
export function validQuoteSheetQuantity(value: number) { return Number.isSafeInteger(value) && value > 0 }
export type CustomerQuoteSheetRow = {
  key: string; number: number; sku?: string; country: string; provider: string; shippingTime: string; processingTime?: string
  prices: Array<number | null>; sourceDescription: string
}
export type CustomerQuoteSheet = {
  agent: string; date: string; quantityLabels: string[]; rows: CustomerQuoteSheetRow[]; issues: string[]
  tableIssues?: string[]
  priceIssues?: string[]
  hiddenColumns?: QuoteSheetOptionalColumn[]
  whatsapp?: string
  columnOrder?: QuoteSheetColumnKey[]
  title?: string; notes?: string[]
}

// The rendered, immutable notes asset contains this exact approved copy.
export const CUSTOMER_QUOTE_NOTES = [
  'All prices include product cost, shipping cost and handling cost. All quotes are all-inclusive with no hidden fees. Only the following potential extra charges that may apply: Remote area surcharge: If the destination is classified as a remote area by the logistics carrier, an extra remote-area surcharge will be charged at actual cost. Redelivery / return fee: Should delivery fail due to recipient-related reasons (e.g. undelivered, recipient absent, wrong address, refusal of receipt), all resulting redelivery or return shipping costs will be borne by you. These fees are not included in our base quotation.',
  'Payment methods: PayPal, Payoneer, Bank transfer, etc.',
  'Quotation is based on factory packaging. Any changing or upgrading on packaging will lead to an increase in costs, and the packaging will require MOQ.',
  'Shipping time in quotation list is an average based on past 30 days. Due to unpredictable factors, shipping times for every single orders cannot be 100% guaranteed.',
] as const

const providerNames: Record<string, string> = {
  '燕文': 'Yanwen', '燕文物流': 'Yanwen', '燕文物流有限公司': 'Yanwen', yanwen: 'Yanwen',
  '递四方': '4PX', '递四方物流': '4PX', '4px': '4PX',
  '云途': 'YunExpress', '云途物流': 'YunExpress', yunexpress: 'YunExpress',
  // Official brand: https://www.sfydexpress.com/ (distinct from YunExpress).
  '云速递': 'SFYD Express', '义乌市思方云递物流有限公司简称：云速递': 'SFYD Express', sfydexpress: 'SFYD Express',
  '顺丰': 'SF Express', '顺丰国际': 'SF Express', '顺丰国际快递': 'SF Express', '顺丰速运': 'SF Express', 'sfexpress': 'SF Express',
  '万邦': 'Wanb Express', '万邦速达': 'Wanb Express', '万邦物流': 'Wanb Express', 'wanbexpress': 'Wanb Express',
  '闪电猴': 'SDH Express', '闪电猴物流': 'SDH Express', sdhexpress: 'SDH Express',
  '顺友': 'SunYou', '顺友物流': 'SunYou', sunyou: 'SunYou',
  '通邮': 'TopYou', '通邮物流': 'TopYou', topyou: 'TopYou',
  '捷易通达': 'JYTD', jytd: 'JYTD',
  '极通环球': 'JITO', jito: 'JITO',
  // Readable romanized display names; these do not rename the provider's business record.
  '花海': 'Hua Hai', '花海供应链': 'Hua Hai', huahai: 'Hua Hai',
  '容鼎': 'Rongding', '容鼎供应链': 'Rongding', rongding: 'Rongding',
  '百洲': 'Baizhou', '百洲货运': 'Baizhou', baizhou: 'Baizhou',
  '中国邮政': 'China Post', '邮政': 'China Post', '中邮': 'China Post',
  '菜鸟': 'Cainiao', '菜鸟物流': 'Cainiao', '菜鸟国际': 'Cainiao',
  '联邦快递': 'FedEx', fedex: 'FedEx', '敦豪': 'DHL', dhl: 'DHL', ups: 'UPS', '联合包裹': 'UPS',
}
const fallbackCountries: Record<string, string> = {
  '美国': 'US', '英国': 'UK', '德国': 'DE', '法国': 'FR', '加拿大': 'CA', '澳大利亚': 'AU',
  '新西兰': 'NZ', '爱尔兰': 'IE',
}
const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']

export function quoteSheetRowKey(row: QuoteSheetSourceRow) {
  // JSON tuples avoid separator collisions and preserve distinct regions/channels.
  return JSON.stringify([row.country, row.quoteRegion || '', row.channelKey ||
    JSON.stringify([row.ruleId, row.rule, row.carrier, row.channelCode || row.transport])])
}
export function localQuoteDate(now = new Date()) {
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`
}
export function formatQuoteDate(value: string) {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value)
  if (!match) return ''
  const year = Number(match[1]), month = Number(match[2]), day = Number(match[3])
  const date = new Date(year, month - 1, day)
  return year >= 1000 && date.getFullYear() === year && date.getMonth() === month - 1 && date.getDate() === day
    ? `${day} ${months[month - 1]} ${year}` : ''
}
export function newQuoteSheetEdits(agent: string, now = new Date()): QuoteSheetEdits {
  return { agent, date: localQuoteDate(now), shippingTimes: {}, providerNames: {} }
}
export function reconcileQuoteSheetEdits(edits: QuoteSheetEdits, rows: QuoteSheetSourceRow[]) {
  const keys = new Set(rows.map(quoteSheetRowKey))
  const providers = new Set(rows.map(row => quoteSheetProviderKey(row.carrier)))
  return {
    ...edits,
    shippingTimes: Object.fromEntries(Object.entries(edits.shippingTimes).filter(([key]) => keys.has(key))),
    fields: Object.fromEntries(Object.entries(edits.fields || {}).filter(([key]) => keys.has(key))),
    providerNames: Object.fromEntries(Object.entries(edits.providerNames || {}).filter(([key]) => providers.has(key))),
  }
}
export function formatShippingTime(value: string) {
  const text = String(value || '').trim()
  // formatLogisticsEta emits this placeholder, and historical records preserve it.
  // It represents missing data, not customer-facing prose or a delivery promise.
  if (text === '该物流暂无时效说明') return '—'
  if (!text || /^[\s—–\-~～/天]*$/.test(text) || /(?:^|\D)0\s*[-~～]\s*0(?:\D|$)/.test(text)) return '—'
  // An incomplete source interval must not turn into a made-up delivery promise.
  if (/^(?:[-—]\s*[-~～]|\d+\s*[-~～]\s*[-—])/.test(text)) return '—'
  return text.replace(/[～~–—]/g, '-').replace(/\s*-\s*/g, '-')
    .replace(/(?:个)?工作日/g, ' working days').replace(/(?:个)?自然日|天|日/g, ' days')
    .replace(/\b(?:working\s*days?|business\s+days?|days?)\b/gi, 'workingdays')
    .replace(/\s+/g, ' ').trim()
}
export function quoteSheetCountryCode(country: string, catalog: QuoteSheetCountry[]) {
  const code = (catalog.find(item => item.name === country || item.code.toUpperCase() === country.toUpperCase())?.code
    || fallbackCountries[country] || (/^[a-z]{2}$/i.test(country) ? country : '')).toUpperCase()
  return code === 'GB' ? 'UK' : /^[A-Z]{2}$/.test(code) ? code : ''
}
const englishCountryNames = new Intl.DisplayNames(['en'], { type: 'region', style: 'long', fallback: 'none' })
export function quoteSheetCountryName(country: string, catalog: QuoteSheetCountry[]) {
  const value = country.trim()
  const code = quoteSheetCountryCode(value, catalog)
  if (code) return englishCountryNames.of(code === 'UK' ? 'GB' : code) || ''
  // Preserve an explicitly entered English name; unknown Chinese names still need a catalog mapping.
  return /^[A-Za-z][A-Za-z .,'’()&-]{2,79}$/.test(value) ? value : ''
}
export function quoteSheetProviderKey(provider: string) {
  return provider.normalize('NFKC').toLowerCase().replace(/[\s._-]/g, '')
}
export function quoteSheetProviderName(provider: string) {
  const name = provider.normalize('NFKC').trim()
  return providerNames[quoteSheetProviderKey(name)] || (/^[\x20-\x7e]+$/.test(name) ? name : '')
}
export function quoteSheetUsd(value: number | null) {
  return value == null || !Number.isFinite(value) ? '—' : `$${value.toFixed(2)}`
}
export function buildCustomerQuoteSheet(input: {
  rows: QuoteSheetSourceRow[]; countries: QuoteSheetCountry[]; edits: QuoteSheetEdits
  customQuantity: number; bundle: boolean; skus?: string[]
  quantities?: number[]; calculatePrice?: QuoteSheetPriceCalculator; legacyCustomIndex?: number
}): CustomerQuoteSheet {
  const issues: string[] = []
  const tableIssues: string[] = []
  const priceIssues: string[] = []
  const hiddenColumns = [...(input.edits.hiddenColumns ?? [])]
  const visible = (column: QuoteSheetOptionalColumn) => !hiddenColumns.includes(column)
  const sku = (input.skus ?? []).map(value => value.trim()).filter(Boolean).join('+') || '—'
  const agent = input.edits.agent.trim()
  const date = formatQuoteDate(input.edits.date)
  if (!agent) issues.push('请填写报价单署名')
  if (!date) issues.push('请选择有效的报价日期')
  const quantities = input.quantities ?? [1, 2, 3, Math.max(1, Math.floor(input.customQuantity || 1))]
  const isLegacyCustom = (quantity: number, index: number) => !input.customQuantity && quantity === 0 && index === input.legacyCustomIndex
  const validQuantities = quantities.length > 0 && quantities.length <= MAX_QUOTE_SHEET_COLUMNS && quantities.every((quantity, index) => validQuoteSheetQuantity(quantity) || isLegacyCustom(quantity, index))
  if (!validQuantities) priceIssues.push('请填写有效的正整数数量，价格列最多 10 列')
  if (input.quantities && new Set(quantities).size !== quantities.length) priceIssues.push('数量列不能重复')
  const rows = input.rows.map((row, index) => {
    const key = quoteSheetRowKey(row)
    const fields = input.edits.fields?.[key] || {}
    const country = quoteSheetCountryName(fields.country ?? row.country, input.countries)
    const manualProvider = input.edits.providerNames?.[quoteSheetProviderKey(row.carrier)]?.trim() || ''
    const provider = fields.provider === undefined ? quoteSheetProviderName(row.carrier) || (/^[\x20-\x7e]+$/.test(manualProvider) ? manualProvider : '') : fields.provider.trim()
    if (visible('country') && !country) tableIssues.push(`第 ${index + 1} 行缺少国家英文全称：${row.country}`)
    if (visible('provider') && !provider) tableIssues.push(`请在英文名补填区填写物流商“${row.carrier || '未命名'}”的英文名称`)
    if (visible('provider') && /[^\x20-\x7e]/.test(provider)) tableIssues.push(`第 ${index + 1} 行物流商请填写英文名称`)
    const shippingTime = formatShippingTime(input.edits.shippingTimes[key] ?? row.eta)
    if (visible('shippingTime') && /[^\x20-\x7e—]/.test(shippingTime)) tableIssues.push(`请将第 ${index + 1} 行运输时效填写为英文，例如 6-12 workingdays`)
    const processingTime = formatShippingTime(fields.processingTime ?? '1-2 workingdays')
    if (visible('processingTime') && /[^\x20-\x7e—]/.test(processingTime)) tableIssues.push(`第 ${index + 1} 行处理时间请填写英文`)
    const number = fields.number === undefined ? index + 1 : Number(fields.number)
    if (!validQuoteSheetQuantity(number)) tableIssues.push(`第 ${index + 1} 行序号须为正整数`)
    const sourcePrices = [row.quote1, row.quote2, row.quote3, row.quoteCustom]
    const sourceQuantities = [1, 2, 3, input.customQuantity]
    const prices = quantities.map((quantity, column) => {
      const manual = fields.prices?.[String(quantity)]
      if (manual !== undefined) {
        const parsed = parseQuotePriceInput(manual)
        if (parsed.error) priceIssues.push(`第 ${index + 1} 行 ${quantity} 数量的美元金额：${parsed.error}`)
        return parsed.value
      }
      if (!validQuantities || row.available === false) return null
      // Existing snapshot prices remain authoritative. Only additional quantities use the live calculator.
      const sourceIndex = isLegacyCustom(quantity, column) ? 3 : input.quantities ? sourceQuantities.indexOf(quantity) : column
      let value: number | null | undefined
      try { value = sourceIndex >= 0 ? sourcePrices[sourceIndex] : input.calculatePrice?.(row, quantity) }
      catch { priceIssues.push(`第 ${index + 1} 行 ${quantity} 数量计算失败，请重试`); return null }
      return value != null && Number.isFinite(value) && value >= 0 ? value : null
    })
    return {
      key, number, sku, country: country || '—', provider: provider || '—', shippingTime, processingTime, prices,
      sourceDescription: [row.country, row.quoteRegion, row.carrier, row.transport, row.channelCode].filter(Boolean).join(' · '),
    }
  })
  tableIssues.push(...priceIssues)
  return {
    hiddenColumns,
    whatsapp: input.edits.whatsapp?.trim() || '',
    columnOrder: normalizeQuoteSheetOrder(input.edits.columnOrder),
    title: input.edits.title ?? 'JerryFulfillment Quote Sheet', notes: input.edits.notes ?? [...CUSTOMER_QUOTE_NOTES],
    agent, date, rows, issues: [...issues, ...new Set(tableIssues)], tableIssues: [...new Set(tableIssues)], priceIssues: [...new Set(priceIssues)],
    quantityLabels: quantities.map((quantity, index) => isLegacyCustom(quantity, index) || (!input.quantities && index === 3 && !input.customQuantity) ? 'Custom' : `${validQuoteSheetQuantity(quantity) ? quantity : '—'} ${input.bundle ? quantity === 1 ? 'set' : 'sets' : quantity === 1 ? 'pc' : 'pcs'}`),
  }
}

/** Tab-separated cells for Excel/WPS; only the customer table, including every route. */
export function customerQuoteSheetTsv(sheet: CustomerQuoteSheet) {
  const issues = sheet.tableIssues ?? sheet.issues
  if (issues.length) throw new Error(issues.join('；'))
  if (!sheet.rows.length) throw new Error('没有可复制的报价数据')
  const cell = (value: string | number) => {
    const text = String(value).replace(/[\t\r\n]+/g, ' ').trim()
    // Keep manually entered text from becoming a spreadsheet formula.
    return /^[=+@-]/.test(text) ? `'${text}` : text
  }
  return quoteSheetTextTable(sheet).map(row => row.map(cell).join('\t')).join('\r\n')
}
