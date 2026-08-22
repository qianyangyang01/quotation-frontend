export type QuotationRecordStatus = 'pending' | 'won' | 'lost'

export interface QuotationRecordSpecifiedQuote {
  country: string
  quoteRegion?: string
  carrier: string
  channel: string
  rule: string
  eta: string
  quote1Usd: number | null
  quote2Usd: number | null
  quote3Usd: number | null
  quoteCustomUsd: number | null
}

export interface QuotationRecordQuoteOption {
  id: string
  country: string
  countryCode?: string
  quoteRegion?: string
  carrier: string
  channel: string
  rule: string
  eta: string
  channelKey?: string
  ruleId?: string
  channelCode?: string
  freightCny?: number
  totalCostCny?: number
  profitCny?: number
  quoteCny?: number
  isPrimary?: boolean
  quote1Usd: number | null
  quote2Usd: number | null
  quote3Usd: number | null
  quoteCustomUsd: number | null
  taxIncluded?: boolean
  taxConfigured?: boolean
  taxRatePercent?: number | null
  countryFixedTaxUsd?: number
  taxCustomerType?: 'A' | 'B'
  taxFeeMode?: 'exempt' | 'fixed-order' | 'per-item' | 'missing'
  taxPerItemFeeUsd?: number
  taxLabel?: string
  tax1Usd?: number | null
  tax2Usd?: number | null
  tax3Usd?: number | null
  taxCustomUsd?: number | null
}

export interface QuotationRecordDealLine {
  id: string
  optionId: string
  optionLabel: string
  country: string
  carrier: string
  channel: string
  unitPriceUsd: number
  quantity: number
  amountUsd: number
}

export type QuotationRecordEditableField = 'status' | 'actualQuoteUsd' | 'actualQuoteCny' | 'dealQuantity' | 'closedAt' | 'note' | 'dealOptionLabel' | 'dealLines'

export interface QuotationRecordRevision {
  id: string
  changedAt: string
  editorName: string
  editorAccount: string
  field: QuotationRecordEditableField
  fieldLabel: string
  before: string
  after: string
}

export interface QuotationRecordEditor {
  name: string
  account: string
}

export interface QuotationRecord {
  id: string; no: string; salespersonName: string; salespersonAccount: string; customerName: string
  quoteMode: 'single' | 'bundle'; productSummary: string; productImage?: string; primarySku: string; productCategory?: string; logisticsAttribute: string
  volumetricEnabled?: boolean; packageLengthCm?: number; packageWidthCm?: number; packageHeightCm?: number; defaultVolumeDivisor?: number
  country: string; carrier: string; channel: string; rule: string; customerGrade: string; taxCustomerType?: 'A' | 'B'; monthlySalesEstimate?: string
  systemQuoteCny: number; systemQuoteUsd: number; totalCostCny: number; exchangeRate: number
  matrixMode?: 'common' | 'specified' | 'template'; quotationTemplateId?: string; quotationTemplateName?: string
  specifiedQuotes?: QuotationRecordSpecifiedQuote[]
  quoteOptions?: QuotationRecordQuoteOption[]; customQuoteQuantity?: number; dealOptionId?: string; dealOptionLabel?: string; dealLines?: QuotationRecordDealLine[]
  status: QuotationRecordStatus; actualQuoteUsd?: number; actualQuoteCny?: number; dealQuantity?: number
  closedAt?: string; note?: string; createdAt: string; updatedAt: string; revisions: QuotationRecordRevision[]
}

export type QuotationRecordUpdate = Partial<Pick<QuotationRecord, QuotationRecordEditableField | 'dealOptionId'>>

const KEY = 'milano.quotation.records.v1'
const n = (value: unknown) => Number.isFinite(Number(value)) ? Number(value) : 0
const optionalN = (value: unknown) => value == null || value === '' ? null : n(value)
const editableFields: QuotationRecordEditableField[] = ['status', 'actualQuoteUsd', 'actualQuoteCny', 'dealQuantity', 'closedAt', 'note', 'dealOptionLabel']
const fieldLabels: Record<QuotationRecordEditableField, string> = {
  status: '处理状态',
  actualQuoteUsd: '客户最终报价（USD）',
  actualQuoteCny: '客户最终报价（CNY）',
  dealQuantity: '成交数量',
  closedAt: '处理日期',
  note: '备注 / 未成交原因',
  dealOptionLabel: '成交渠道',
  dealLines: '成交方案明细',
}
const statusLabels: Record<QuotationRecordStatus, string> = { pending: '待处理', won: '已成交', lost: '未成交' }

function isEditableField(value: unknown): value is QuotationRecordEditableField {
  return typeof value === 'string' && editableFields.includes(value as QuotationRecordEditableField)
}

function revisionValue(field: QuotationRecordEditableField, value: unknown) {
  if (value == null || value === '') return '—'
  if (field === 'status' && (value === 'pending' || value === 'won' || value === 'lost')) return statusLabels[value]
  if (field === 'actualQuoteUsd') return `$${n(value).toFixed(2)}`
  if (field === 'actualQuoteCny') return `¥${n(value).toFixed(2)}`
  if (field === 'dealLines') {
    const lines = Array.isArray(value) ? value as QuotationRecordDealLine[] : []
    if (!lines.length) return '—'
    return lines.map(line => `${line.optionLabel} $${n(line.unitPriceUsd).toFixed(2)} × ${n(line.quantity)}`).join('；')
  }
  return String(value)
}

function normalizeRevisions(value: unknown): QuotationRecordRevision[] {
  if (!Array.isArray(value)) return []
  return value.map((raw, index) => {
    const field = raw?.field
    if (!isEditableField(field)) return null
    const changedAt = String(raw?.changedAt || '')
    return {
      id: String(raw?.id || `${changedAt || 'revision'}-${field}-${index}`),
      changedAt,
      editorName: String(raw?.editorName || '—'),
      editorAccount: String(raw?.editorAccount || '—'),
      field,
      fieldLabel: String(raw?.fieldLabel || fieldLabels[field]),
      before: String(raw?.before ?? '—'),
      after: String(raw?.after ?? '—'),
    }
  }).filter((revision): revision is QuotationRecordRevision => revision !== null)
}
function normalizeSpecifiedQuotes(value: unknown): QuotationRecordSpecifiedQuote[] {
  if (!Array.isArray(value)) return []
  return value.map(raw => ({
    country: String(raw?.country || '—'), quoteRegion: optionalText(raw?.quoteRegion), carrier: String(raw?.carrier || '—'), channel: String(raw?.channel || '—'), rule: String(raw?.rule || '—'), eta: String(raw?.eta || '—'),
    quote1Usd: optionalN(raw?.quote1Usd), quote2Usd: optionalN(raw?.quote2Usd), quote3Usd: optionalN(raw?.quote3Usd), quoteCustomUsd: optionalN(raw?.quoteCustomUsd),
  }))
}
function optionalText(value: unknown) {
  const text = String(value ?? '').trim()
  return text || undefined
}
function optionalNumber(value: unknown) {
  if (value == null || value === '' || !Number.isFinite(Number(value))) return undefined
  return Number(value)
}
function optionMatchesPrimary(raw: Partial<QuotationRecord>, option: Pick<QuotationRecordQuoteOption, 'country' | 'carrier' | 'channel' | 'rule'>) {
  return option.country === String(raw.country || '—')
    && option.carrier === String(raw.carrier || '—')
    && option.channel === String(raw.channel || '—')
    && option.rule === String(raw.rule || '—')
}
function normalizeQuoteOptions(value: unknown, recordId: string, rawRecord: Partial<QuotationRecord>): QuotationRecordQuoteOption[] {
  if (!Array.isArray(value)) return []
  return value.map((raw, index) => {
    const option: QuotationRecordQuoteOption = {
      id: String(raw?.id || `legacy-${recordId}-${index}`),
      country: String(raw?.country || '—'),
      carrier: String(raw?.carrier || '—'),
      channel: String(raw?.channel || '—'),
      rule: String(raw?.rule || '—'),
      eta: String(raw?.eta || '—'),
      quote1Usd: optionalN(raw?.quote1Usd),
      quote2Usd: optionalN(raw?.quote2Usd),
      quote3Usd: optionalN(raw?.quote3Usd),
      quoteCustomUsd: optionalN(raw?.quoteCustomUsd),
    }
    option.countryCode = optionalText(raw?.countryCode)
    option.quoteRegion = optionalText(raw?.quoteRegion)
    option.channelKey = optionalText(raw?.channelKey)
    option.ruleId = optionalText(raw?.ruleId)
    option.channelCode = optionalText(raw?.channelCode)
    option.freightCny = optionalNumber(raw?.freightCny)
    option.totalCostCny = optionalNumber(raw?.totalCostCny)
    option.profitCny = optionalNumber(raw?.profitCny)
    option.quoteCny = optionalNumber(raw?.quoteCny)
    option.isPrimary = typeof raw?.isPrimary === 'boolean' ? raw.isPrimary : optionMatchesPrimary(rawRecord, option)
    option.taxIncluded = typeof raw?.taxIncluded === 'boolean' ? raw.taxIncluded : undefined
    option.taxConfigured = typeof raw?.taxConfigured === 'boolean' ? raw.taxConfigured : undefined
    option.taxRatePercent = raw?.taxRatePercent == null ? null : optionalN(raw.taxRatePercent)
    option.countryFixedTaxUsd = optionalNumber(raw?.countryFixedTaxUsd)
    option.taxCustomerType = raw?.taxCustomerType === 'B' ? 'B' : raw?.taxCustomerType === 'A' ? 'A' : undefined
    option.taxFeeMode = raw?.taxFeeMode === 'exempt' || raw?.taxFeeMode === 'fixed-order' || raw?.taxFeeMode === 'per-item' || raw?.taxFeeMode === 'missing' ? raw.taxFeeMode : undefined
    option.taxPerItemFeeUsd = optionalNumber(raw?.taxPerItemFeeUsd)
    option.taxLabel = optionalText(raw?.taxLabel)
    option.tax1Usd = raw?.tax1Usd == null ? null : optionalN(raw.tax1Usd)
    option.tax2Usd = raw?.tax2Usd == null ? null : optionalN(raw.tax2Usd)
    option.tax3Usd = raw?.tax3Usd == null ? null : optionalN(raw.tax3Usd)
    option.taxCustomUsd = raw?.taxCustomUsd == null ? null : optionalN(raw.taxCustomUsd)
    return option
  })
}
function quoteOptionsFromSpecifiedQuotes(quotes: QuotationRecordSpecifiedQuote[], recordId: string, raw: Partial<QuotationRecord>) {
  return normalizeQuoteOptions(quotes, recordId, raw)
}
function legacyPrimaryOption(raw: Partial<QuotationRecord>, recordId: string): QuotationRecordQuoteOption {
  return {
    id: `legacy-${recordId}-primary`,
    country: String(raw.country || '—'),
    carrier: String(raw.carrier || '—'),
    channel: String(raw.channel || '—'),
    rule: String(raw.rule || '—'),
    eta: '—',
    quoteCny: optionalNumber(raw.systemQuoteCny),
    isPrimary: true,
    quote1Usd: null,
    quote2Usd: null,
    quote3Usd: null,
    quoteCustomUsd: null,
  }
}
function specifiedQuotesFromOptions(options: QuotationRecordQuoteOption[]): QuotationRecordSpecifiedQuote[] {
  return options.map(option => ({
    country: option.country,
    quoteRegion: option.quoteRegion,
    carrier: option.carrier,
    channel: option.channel,
    rule: option.rule,
    eta: option.eta,
    quote1Usd: option.quote1Usd,
    quote2Usd: option.quote2Usd,
    quote3Usd: option.quote3Usd,
    quoteCustomUsd: option.quoteCustomUsd,
  }))
}
function normalizeDealLines(value: unknown, options: QuotationRecordQuoteOption[], raw: Partial<QuotationRecord>): QuotationRecordDealLine[] {
  if (Array.isArray(value)) return value.map((line, index) => {
    const optionId = String(line?.optionId || '')
    const option = options.find(item => item.id === optionId)
    const unitPriceUsd = Math.max(0, n(line?.unitPriceUsd))
    const quantity = Math.max(1, Math.floor(n(line?.quantity)))
    return {
      id: String(line?.id || `deal-${optionId || index}`),
      optionId,
      optionLabel: String(line?.optionLabel || (option ? `${option.country} · ${option.channel}` : '—')),
      country: String(line?.country || option?.country || '—'),
      carrier: String(line?.carrier || option?.carrier || '—'),
      channel: String(line?.channel || option?.channel || '—'),
      unitPriceUsd,
      quantity,
      amountUsd: Number((unitPriceUsd * quantity).toFixed(2)),
    }
  }).filter(line => line.optionId && line.unitPriceUsd > 0 && line.quantity > 0)
  const legacyOptionId = optionalText(raw.dealOptionId)
  const legacyPrice = optionalNumber(raw.actualQuoteUsd)
  const legacyQuantity = optionalNumber(raw.dealQuantity)
  if (!legacyOptionId || !legacyPrice || !legacyQuantity) return []
  const option = options.find(item => item.id === legacyOptionId)
  return [{
    id: `deal-${legacyOptionId}`,
    optionId: legacyOptionId,
    optionLabel: String(raw.dealOptionLabel || (option ? `${option.country} · ${option.channel}` : '—')),
    country: option?.country || String(raw.country || '—'),
    carrier: option?.carrier || String(raw.carrier || '—'),
    channel: option?.channel || String(raw.channel || '—'),
    unitPriceUsd: legacyPrice,
    quantity: Math.max(1, Math.floor(legacyQuantity)),
    amountUsd: Number((legacyPrice * Math.max(1, Math.floor(legacyQuantity))).toFixed(2)),
  }]
}
function normalize(raw: Partial<QuotationRecord>): QuotationRecord | null {
  if (!raw.id || !raw.no) return null
  const recordId = String(raw.id)
  const legacyQuotes = normalizeSpecifiedQuotes(raw.specifiedQuotes)
  const quoteOptions = Array.isArray(raw.quoteOptions)
    ? normalizeQuoteOptions(raw.quoteOptions, recordId, raw)
    : legacyQuotes.length
      ? quoteOptionsFromSpecifiedQuotes(legacyQuotes, recordId, raw)
      : [legacyPrimaryOption(raw, recordId)]
  const specifiedQuotes = Array.isArray(raw.quoteOptions) ? specifiedQuotesFromOptions(quoteOptions) : legacyQuotes
  const dealLines = normalizeDealLines(raw.dealLines, quoteOptions, raw)
  return { id: recordId, no: String(raw.no), salespersonName: String(raw.salespersonName || '报价专员'), salespersonAccount: String(raw.salespersonAccount || '—'), customerName: String(raw.customerName || '未填写客户'), quoteMode: raw.quoteMode === 'bundle' ? 'bundle' : 'single', productSummary: String(raw.productSummary || '—'), productImage: raw.productImage ? String(raw.productImage) : undefined, primarySku: String(raw.primarySku || '—'), productCategory: raw.productCategory ? String(raw.productCategory) : undefined, logisticsAttribute: String(raw.logisticsAttribute || '—'), volumetricEnabled: raw.volumetricEnabled === true, packageLengthCm: optionalNumber(raw.packageLengthCm), packageWidthCm: optionalNumber(raw.packageWidthCm), packageHeightCm: optionalNumber(raw.packageHeightCm), defaultVolumeDivisor: raw.defaultVolumeDivisor == null ? undefined : Math.max(1, n(raw.defaultVolumeDivisor)), country: String(raw.country || '—'), carrier: String(raw.carrier || '—'), channel: String(raw.channel || '—'), rule: String(raw.rule || '—'), customerGrade: String(raw.customerGrade || '—'), taxCustomerType: raw.taxCustomerType === 'B' ? 'B' : raw.taxCustomerType === 'A' ? 'A' : undefined, monthlySalesEstimate: raw.monthlySalesEstimate ? String(raw.monthlySalesEstimate) : undefined, matrixMode: raw.matrixMode === 'specified' || raw.matrixMode === 'template' ? raw.matrixMode : 'common', quotationTemplateId: raw.quotationTemplateId ? String(raw.quotationTemplateId) : undefined, quotationTemplateName: raw.quotationTemplateName ? String(raw.quotationTemplateName) : undefined, specifiedQuotes, quoteOptions, customQuoteQuantity: raw.customQuoteQuantity == null ? undefined : Math.max(1, Math.floor(n(raw.customQuoteQuantity))), dealOptionId: optionalText(raw.dealOptionId), dealOptionLabel: optionalText(raw.dealOptionLabel), dealLines, systemQuoteCny: n(raw.systemQuoteCny), systemQuoteUsd: n(raw.systemQuoteUsd), totalCostCny: n(raw.totalCostCny), exchangeRate: n(raw.exchangeRate), status: raw.status === 'won' || raw.status === 'lost' ? raw.status : 'pending', actualQuoteUsd: raw.actualQuoteUsd == null ? undefined : n(raw.actualQuoteUsd), actualQuoteCny: raw.actualQuoteCny == null ? undefined : n(raw.actualQuoteCny), dealQuantity: raw.dealQuantity == null ? undefined : n(raw.dealQuantity), closedAt: raw.closedAt, note: raw.note, createdAt: String(raw.createdAt || new Date().toISOString()), updatedAt: String(raw.updatedAt || raw.createdAt || new Date().toISOString()), revisions: normalizeRevisions(raw.revisions) }
}
export function loadQuotationRecords() { try { const rows = JSON.parse(localStorage.getItem(KEY) || '[]'); return Array.isArray(rows) ? rows.map(normalize).filter((row): row is QuotationRecord => !!row).sort((a, b) => b.createdAt.localeCompare(a.createdAt)) : [] } catch { return [] } }
export function saveQuotationRecords(rows: QuotationRecord[]) { localStorage.setItem(KEY, JSON.stringify(rows)) }
export function createQuotationRecord(input: Omit<QuotationRecord, 'id' | 'no' | 'status' | 'createdAt' | 'updatedAt' | 'revisions'>) { const now = new Date().toISOString(); const raw: QuotationRecord = { ...input, id: `${Date.now()}-${Math.random().toString(36).slice(2, 7)}`, no: `QT${now.slice(0, 10).replace(/-/g, '')}${String(Date.now()).slice(-5)}`, status: 'pending', createdAt: now, updatedAt: now, revisions: [] }; const record = normalize(raw)!; const rows = loadQuotationRecords(); rows.unshift(record); saveQuotationRecords(rows); return record }
export function updateQuotationRecord(id: string, patch: QuotationRecordUpdate, editor?: QuotationRecordEditor) {
  const rows = loadQuotationRecords()
  const i = rows.findIndex(row => row.id === id)
  if (i < 0) return null

  const current = rows[i]
  const editablePatch: QuotationRecordUpdate = {}
  if (Object.prototype.hasOwnProperty.call(patch, 'status') && (patch.status === 'pending' || patch.status === 'won' || patch.status === 'lost')) editablePatch.status = patch.status
  if (Object.prototype.hasOwnProperty.call(patch, 'actualQuoteUsd')) editablePatch.actualQuoteUsd = patch.actualQuoteUsd
  if (Object.prototype.hasOwnProperty.call(patch, 'actualQuoteCny')) editablePatch.actualQuoteCny = patch.actualQuoteCny
  if (Object.prototype.hasOwnProperty.call(patch, 'dealQuantity')) editablePatch.dealQuantity = patch.dealQuantity
  if (Object.prototype.hasOwnProperty.call(patch, 'closedAt')) editablePatch.closedAt = patch.closedAt
  if (Object.prototype.hasOwnProperty.call(patch, 'note')) editablePatch.note = patch.note
  if (Object.prototype.hasOwnProperty.call(patch, 'dealOptionId')) editablePatch.dealOptionId = optionalText(patch.dealOptionId)
  if (Object.prototype.hasOwnProperty.call(patch, 'dealOptionLabel')) editablePatch.dealOptionLabel = optionalText(patch.dealOptionLabel)
  if (Object.prototype.hasOwnProperty.call(patch, 'dealLines')) editablePatch.dealLines = Array.isArray(patch.dealLines) ? patch.dealLines : []

  const candidate = normalize({ ...current, ...editablePatch, id, updatedAt: current.updatedAt, revisions: current.revisions })!
  const changedFields = editableFields.filter(field => field === 'dealLines'
    ? JSON.stringify(current.dealLines || []) !== JSON.stringify(candidate.dealLines || [])
    : !Object.is(current[field], candidate[field]))
  const dealOptionIdChanged = !Object.is(current.dealOptionId, candidate.dealOptionId)
  if (!changedFields.length && !dealOptionIdChanged) return current

  const changedAt = new Date().toISOString()
  const editorName = editor?.name?.trim() || current.salespersonName || '—'
  const editorAccount = editor?.account?.trim() || current.salespersonAccount || '—'
  const revisions = changedFields.map((field, index): QuotationRecordRevision => ({
    id: `${changedAt}-${field}-${index}`,
    changedAt,
    editorName,
    editorAccount,
    field,
    fieldLabel: fieldLabels[field],
    before: revisionValue(field, current[field]),
    after: revisionValue(field, candidate[field]),
  }))

  rows[i] = { ...candidate, updatedAt: changedAt, revisions: [...current.revisions, ...revisions] }
  saveQuotationRecords(rows)
  return rows[i]
}
