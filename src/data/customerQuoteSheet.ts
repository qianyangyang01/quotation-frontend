import type { QuotationMatrixRow } from '@/components/quotation/types'

/** Customer-facing presentation only. Never included in quotation/draft API payloads. */
export type QuoteSheetSourceRow = Pick<QuotationMatrixRow,
  'country' | 'quoteRegion' | 'channelKey' | 'ruleId' | 'channelCode' | 'rule' | 'carrier' | 'transport' | 'eta' |
  'quote1' | 'quote2' | 'quote3' | 'quoteCustom'>
export type QuoteSheetCountry = { name: string; code: string }
export type QuoteSheetEdits = { agent: string; date: string; shippingTimes: Record<string, string> }
export type CustomerQuoteSheetRow = {
  key: string; number: number; country: string; provider: string; shippingTime: string
  prices: Array<number | null>; sourceDescription: string
}
export type CustomerQuoteSheet = {
  agent: string; date: string; quantityLabels: string[]; rows: CustomerQuoteSheetRow[]; issues: string[]
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
  '中国邮政': 'China Post', '邮政': 'China Post', '中邮': 'China Post',
  '菜鸟': 'Cainiao', '菜鸟物流': 'Cainiao', '菜鸟国际': 'Cainiao',
  '联邦快递': 'FedEx', fedex: 'FedEx', '敦豪': 'DHL', dhl: 'DHL', ups: 'UPS', '联合包裹': 'UPS',
}
const fallbackCountries: Record<string, string> = {
  '美国': 'US', '英国': 'UK', '德国': 'DE', '法国': 'FR', '加拿大': 'CA', '澳大利亚': 'AU',
  '新西兰': 'NZ',
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
  return { agent, date: localQuoteDate(now), shippingTimes: {} }
}
export function reconcileQuoteSheetEdits(edits: QuoteSheetEdits, rows: QuoteSheetSourceRow[]) {
  const keys = new Set(rows.map(quoteSheetRowKey))
  return { ...edits, shippingTimes: Object.fromEntries(Object.entries(edits.shippingTimes).filter(([key]) => keys.has(key))) }
}
export function formatShippingTime(value: string) {
  const text = String(value || '').trim()
  if (!text || /^[\s—–\-~～/天]*$/.test(text) || /(?:^|\D)0\s*[-~～]\s*0(?:\D|$)/.test(text)) return '—'
  // An incomplete source interval must not turn into a made-up delivery promise.
  if (/^(?:[-—]\s*[-~～]|\d+\s*[-~～]\s*[-—])/.test(text)) return '—'
  return text.replace(/[～~–—]/g, '-').replace(/\s*-\s*/g, '-')
    .replace(/(?:个)?工作日/g, ' working days').replace(/(?:个)?自然日|天|日/g, ' days')
    .replace(/\s+/g, ' ').trim()
}
export function quoteSheetCountryCode(country: string, catalog: QuoteSheetCountry[]) {
  const code = (catalog.find(item => item.name === country || item.code.toUpperCase() === country.toUpperCase())?.code
    || fallbackCountries[country] || (/^[a-z]{2}$/i.test(country) ? country : '')).toUpperCase()
  return code === 'GB' ? 'UK' : /^[A-Z]{2}$/.test(code) ? code : ''
}
export function quoteSheetProviderName(provider: string) {
  const name = provider.trim()
  const key = name.toLowerCase().replace(/[\s._-]/g, '')
  return providerNames[key] || (/^[\x20-\x7e]+$/.test(name) ? name : '')
}
export function quoteSheetUsd(value: number | null) {
  return value == null || !Number.isFinite(value) ? '—' : `$${value.toFixed(2)}`
}
export function buildCustomerQuoteSheet(input: {
  rows: QuoteSheetSourceRow[]; countries: QuoteSheetCountry[]; edits: QuoteSheetEdits
  customQuantity: number; bundle: boolean
}): CustomerQuoteSheet {
  const issues: string[] = []
  const agent = input.edits.agent.trim()
  const date = formatQuoteDate(input.edits.date)
  if (!agent) issues.push('请填写报价单署名')
  if (!date) issues.push('请选择有效的报价日期')
  const quantities = [1, 2, 3, Math.max(1, Math.floor(input.customQuantity || 1))]
  const rows = input.rows.map((row, index) => {
    const key = quoteSheetRowKey(row)
    const country = quoteSheetCountryCode(row.country, input.countries)
    const provider = quoteSheetProviderName(row.carrier)
    if (!country) issues.push(`第 ${index + 1} 行缺少国家简称：${row.country}`)
    if (!provider) issues.push(`第 ${index + 1} 行物流商尚未配置英文名称：${row.carrier}`)
    const shippingTime = formatShippingTime(input.edits.shippingTimes[key] ?? row.eta)
    if (/[^\x20-\x7e—]/.test(shippingTime)) issues.push(`请将第 ${index + 1} 行运输时效填写为英文，例如 6-12 days`)
    return {
      key, number: index + 1, country: country || '—', provider: provider || '—', shippingTime,
      prices: [row.quote1, row.quote2, row.quote3, row.quoteCustom].map(value =>
        value != null && Number.isFinite(value) ? value : null),
      sourceDescription: [row.country, row.quoteRegion, row.carrier, row.transport, row.channelCode].filter(Boolean).join(' · '),
    }
  })
  return {
    agent, date, rows, issues,
    quantityLabels: quantities.map((quantity, index) => index === 3 && !input.customQuantity ? 'Custom' : `${quantity} ${input.bundle ? quantity === 1 ? 'set' : 'sets' : quantity === 1 ? 'pc' : 'pcs'}`),
  }
}

/** Tab-separated cells for Excel/WPS; only the customer table, including every route. */
export function customerQuoteSheetTsv(sheet: CustomerQuoteSheet) {
  if (sheet.issues.length) throw new Error(sheet.issues.join('；'))
  if (!sheet.rows.length) throw new Error('没有可复制的报价数据')
  const cell = (value: string | number) => {
    const text = String(value).replace(/[\t\r\n]+/g, ' ').trim()
    // Keep manually entered text from becoming a spreadsheet formula.
    return /^[=+@-]/.test(text) ? `'${text}` : text
  }
  return [
    ['No.', 'Country', 'Logistics Provider', 'Shipping Time', 'Processing Time', ...sheet.quantityLabels.map(label => `${label} (USD)`)],
    ...sheet.rows.map(row => [row.number, row.country, row.provider, row.shippingTime, '1-2 days', ...row.prices.map(quoteSheetUsd)]),
  ].map(row => row.map(cell).join('\t')).join('\r\n')
}
