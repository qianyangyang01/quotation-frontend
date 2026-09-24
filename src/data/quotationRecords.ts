import { normalizeQuotationWeightSnapshot, type QuotationWeightSnapshot } from './quotationWeightSnapshot'
import type { CustomerOperationSnapshot } from './customerOperationFees'
import type { FinanceSettingVersions } from '@/services/financeSettings'
import type { FinanceTaxCalculation } from './channelTaxRules'
import { normalizeCustomerPrices, type CustomerPriceSnapshot } from './customerQuotePrices'
import { api, idempotencyKey } from '@/services/http'

export type FinanceReviewStatus = 'pending' | 'reviewing' | 'approved' | 'rejected'
export const financeReviewLabel = (value?: string) => value === 'reviewing' ? '审核中' : value === 'approved' ? '审核通过' : value === 'rejected' ? '价格异常' : '待审核'

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
  available?: boolean
  availabilityMessage?: string
  quantityMessages?: Record<string,string>
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
  logisticsChannelId?: string
  logisticsVersionId?: string
  logisticsSamples?: { quantity: number; input: { weightKg: number }; total?: number | null }[]
  logisticsInput?: { country: string; baseWeightKg?: number; packagingWeightKg?: number; standardPackagingWeightKg?: number; specialPackagingWeightKg?: number; quantity?: number; weightKg: number; marks: string[]; dimensions?: { lengthCm: number; widthCm: number; heightCm: number; volumeMultiplier?: number } }
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
  taxFeeMode?: 'no-tax' | 'exempt' | 'fixed-order' | 'per-item' | 'weight-eur' | 'weight-order' | 'missing'
  taxCalculations?: Record<string, FinanceTaxCalculation>
  taxPerItemFeeUsd?: number
  surchargeEnabled?: boolean
  surchargeConfigured?: boolean
  surchargeExempt?: boolean
  surchargeUsd?: number
  surchargeLabel?: string
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

export interface QuotationRecordBundleItem {
  sku: string
  name: string
  quantityPerSet: number
  effectiveWeightKg: number
  purchaseBaseUnitPriceCny?: number
  purchaseInvoiceType?: string
  purchaseInvoiceRatePercent?: number
  purchaseInvoiceTaxApplied?: boolean
  purchaseUnitPriceCny: number
  domesticFreightPerUnitCny: number
}

export type QuotationRecordEditableField = 'quoteRevision' | 'lifecycleState' | 'financeReviewStatus' | 'status' | 'actualQuoteUsd' | 'actualQuoteCny' | 'dealQuantity' | 'closedAt' | 'note' | 'dealOptionLabel' | 'dealLines' | 'customerQuote' | 'quoteConfirmed'

export interface QuotationRecordRevision {
  id: string
  changedAt: string
  editorName: string
  editorAccount: string
  field: QuotationRecordEditableField
  fieldLabel: string
  reason?: string
  before: string
  after: string
}

export interface QuotationRecordEditor {
  name: string
  account: string
}

export interface QuotationRecord {
  _reviewVersion?: number
  financeReviewClaimedAccount?: string
  financeReviewClaimedBy?: string
  financeReviewStartedAt?: string
  financeReviewNote?: string
  financeReviewStatus?: FinanceReviewStatus
  financeReviewedAt?: string; financeReviewedBy?: string; financeReviewedAccount?: string
  financeVersions?: FinanceSettingVersions
  weightSnapshot?: QuotationWeightSnapshot
  commissionThreshold?: number | null
  customerOperation?: CustomerOperationSnapshot
  quoteConfirmed?: boolean; quoteConfirmedAt?: string; quoteConfirmedBy?: string
  systemQuantityQuotes?: CustomerPriceSnapshot; sheetQuote?: CustomerPriceSnapshot; customerQuote?: CustomerPriceSnapshot
  purchaseVersions?: Record<string, string>
  logisticsSyncScope?: 'selected'
  lifecycleState?: 'active' | 'archived' | 'trashed' | 'withdrawn'
  lifecyclePreviousState?: 'active' | 'archived'
  lifecycleChangedAt?: string; lifecycleChangedBy?: string; lifecycleChangedAccount?: string; lifecycleReason?: string
  id: string; no: string; _version?: number; salespersonName: string; salespersonAccount: string; customerName: string
  quoteMode: 'single' | 'bundle'; productSummary: string; productImage?: string; primarySku: string; bundleItems?: QuotationRecordBundleItem[]; productCategory?: string; logisticsAttribute: string
  purchaseBaseUnitPriceCny?: number; purchaseInvoiceType?: string; purchaseInvoiceRatePercent?: number; purchaseInvoiceTaxApplied?: boolean; purchaseUnitPriceCny?: number
  domesticFreightPerUnitCny?: number
  volumetricEnabled?: boolean; packageLengthCm?: number; packageWidthCm?: number; packageHeightCm?: number; defaultVolumeDivisor?: number
  country: string; carrier: string; channel: string; rule: string; customerGrade: string; taxCustomerType?: 'A' | 'B'; monthlySalesEstimate?: string
  systemQuoteCny: number; systemQuoteUsd: number; totalCostCny: number; exchangeRate: number
  matrixMode?: 'common' | 'specified' | 'template'; quotationTemplateId?: string; quotationTemplateName?: string
  specifiedQuotes?: QuotationRecordSpecifiedQuote[]
  logisticsRevision?: string; quoteOptions?: QuotationRecordQuoteOption[]; customQuoteQuantity?: number; dealOptionId?: string; dealOptionLabel?: string; dealLines?: QuotationRecordDealLine[]
  status: QuotationRecordStatus; actualQuoteUsd?: number; actualQuoteCny?: number; dealQuantity?: number
  closedAt?: string; note?: string; createdAt: string; updatedAt: string; revisions: QuotationRecordRevision[]
}

export type QuotationRecordUpdate = Partial<Pick<QuotationRecord, Exclude<QuotationRecordEditableField, 'financeReviewStatus' | 'lifecycleState' | 'quoteRevision'> | 'dealOptionId'>>

const n = (value: unknown) => Number.isFinite(Number(value)) ? Number(value) : 0
const optionalN = (value: unknown) => value == null || value === '' ? null : n(value)
const editableFields: QuotationRecordEditableField[] = ['quoteRevision', 'lifecycleState', 'financeReviewStatus', 'status', 'actualQuoteUsd', 'actualQuoteCny', 'dealQuantity', 'closedAt', 'note', 'dealOptionLabel', 'dealLines', 'customerQuote', 'quoteConfirmed']
const fieldLabels: Record<QuotationRecordEditableField, string> = {
  quoteRevision: '撤回重新编辑',
  lifecycleState: '记录分类',
  financeReviewStatus: '财务审核',
  customerQuote: '客户报价', quoteConfirmed: '报价确认',
  status: '处理状态',
  actualQuoteUsd: '客户最终报价（USD）',
  actualQuoteCny: '客户最终报价（CNY）',
  dealQuantity: '成交数量',
  closedAt: '处理日期',
  note: '备注 / 未成交原因',
  dealOptionLabel: '成交渠道',
  dealLines: '成交方案明细',
}
function isEditableField(value: unknown): value is QuotationRecordEditableField {
  return typeof value === 'string' && editableFields.includes(value as QuotationRecordEditableField)
}

function revisionText(value: unknown) { return value == null ? '—' : typeof value === 'object' ? JSON.stringify(value) : String(value) }
function normalizeRevisions(value: unknown): QuotationRecordRevision[] {
  if (!Array.isArray(value)) return []
  return value.map((raw, index): QuotationRecordRevision | null => {
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
      reason: typeof raw?.reason === 'string' ? raw.reason : undefined,
      before: revisionText(raw?.before),
      after: revisionText(raw?.after),
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
      available: raw?.available === false ? false : undefined,
      availabilityMessage: optionalText(raw?.availabilityMessage),
      quantityMessages: raw?.quantityMessages,
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
    option.logisticsChannelId = optionalText(raw?.logisticsChannelId)
    option.logisticsVersionId = optionalText(raw?.logisticsVersionId)
    option.logisticsInput = raw?.logisticsInput
    option.logisticsSamples = Array.isArray(raw?.logisticsSamples) ? raw.logisticsSamples : undefined
    option.totalCostCny = optionalNumber(raw?.totalCostCny)
    option.profitCny = optionalNumber(raw?.profitCny)
    option.quoteCny = optionalNumber(raw?.quoteCny)
    option.isPrimary = typeof raw?.isPrimary === 'boolean' ? raw.isPrimary : optionMatchesPrimary(rawRecord, option)
    option.taxIncluded = typeof raw?.taxIncluded === 'boolean' ? raw.taxIncluded : undefined
    option.taxConfigured = typeof raw?.taxConfigured === 'boolean' ? raw.taxConfigured : undefined
    option.taxRatePercent = raw?.taxRatePercent == null ? null : optionalN(raw.taxRatePercent)
    option.countryFixedTaxUsd = optionalNumber(raw?.countryFixedTaxUsd)
    option.taxCustomerType = raw?.taxCustomerType === 'B' ? 'B' : raw?.taxCustomerType === 'A' ? 'A' : undefined
    option.taxFeeMode = raw?.taxFeeMode === 'no-tax' || raw?.taxFeeMode === 'exempt' || raw?.taxFeeMode === 'fixed-order' || raw?.taxFeeMode === 'per-item' || raw?.taxFeeMode === 'weight-eur' || raw?.taxFeeMode === 'weight-order' || raw?.taxFeeMode === 'missing' ? raw.taxFeeMode : undefined
    option.taxCalculations = raw?.taxCalculations
    option.taxPerItemFeeUsd = optionalNumber(raw?.taxPerItemFeeUsd)
    option.surchargeEnabled = typeof raw?.surchargeEnabled === 'boolean' ? raw.surchargeEnabled : undefined
    option.surchargeConfigured = typeof raw?.surchargeConfigured === 'boolean' ? raw.surchargeConfigured : undefined
    option.surchargeExempt = typeof raw?.surchargeExempt === 'boolean' ? raw.surchargeExempt : undefined
    option.surchargeUsd = optionalNumber(raw?.surchargeUsd)
    option.surchargeLabel = optionalText(raw?.surchargeLabel)
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
function normalizeBundleItems(value: unknown): QuotationRecordBundleItem[] {
  if (!Array.isArray(value)) return []
  return value.map(raw => ({
    sku: String(raw?.sku || '').trim().toUpperCase(),
    name: String(raw?.name || '').trim(),
    quantityPerSet: Math.max(1, Math.floor(n(raw?.quantityPerSet))),
    effectiveWeightKg: Math.max(0, n(raw?.effectiveWeightKg)),
    ...(raw?.purchaseBaseUnitPriceCny == null ? {} : { purchaseBaseUnitPriceCny: optionalNumber(raw.purchaseBaseUnitPriceCny) }),
    ...(raw?.purchaseInvoiceType == null ? {} : { purchaseInvoiceType: optionalText(raw.purchaseInvoiceType) }),
    ...(raw?.purchaseInvoiceRatePercent == null ? {} : { purchaseInvoiceRatePercent: optionalNumber(raw.purchaseInvoiceRatePercent) }),
    ...(typeof raw?.purchaseInvoiceTaxApplied === 'boolean' ? { purchaseInvoiceTaxApplied: raw.purchaseInvoiceTaxApplied } : {}),
    purchaseUnitPriceCny: Math.max(0, n(raw?.purchaseUnitPriceCny)),
    domesticFreightPerUnitCny: Math.max(0, n(raw?.domesticFreightPerUnitCny)),
  })).filter(item => item.sku)
}
export function normalizeQuotationRecord(raw: Partial<QuotationRecord>): QuotationRecord | null {
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
  const bundleItems = normalizeBundleItems(raw.bundleItems)
  return { lifecycleState: raw.lifecycleState === 'withdrawn' || raw.lifecycleState === 'archived' || raw.lifecycleState === 'trashed' ? raw.lifecycleState : 'active', lifecyclePreviousState: raw.lifecyclePreviousState === 'archived' ? 'archived' : 'active', lifecycleChangedAt: optionalText(raw.lifecycleChangedAt), lifecycleChangedBy: optionalText(raw.lifecycleChangedBy), lifecycleChangedAccount: optionalText(raw.lifecycleChangedAccount), lifecycleReason: optionalText(raw.lifecycleReason), _reviewVersion: raw._reviewVersion ?? 0, financeReviewClaimedAccount: optionalText(raw.financeReviewClaimedAccount), financeReviewClaimedBy: optionalText(raw.financeReviewClaimedBy), financeReviewStartedAt: optionalText(raw.financeReviewStartedAt), financeReviewNote: optionalText(raw.financeReviewNote), financeReviewStatus: raw.financeReviewStatus === 'reviewing' || raw.financeReviewStatus === 'approved' || raw.financeReviewStatus === 'rejected' ? raw.financeReviewStatus : 'pending', financeReviewedAt: optionalText(raw.financeReviewedAt), financeReviewedBy: optionalText(raw.financeReviewedBy), financeReviewedAccount: optionalText(raw.financeReviewedAccount), weightSnapshot: normalizeQuotationWeightSnapshot(raw.weightSnapshot), customerOperation: raw.customerOperation ? { id: String(raw.customerOperation.id), name: String(raw.customerOperation.name), feeUsd: n(raw.customerOperation.feeUsd), ...(raw.customerOperation.feesByQuantityUsd !== undefined ? { feesByQuantityUsd: { ...raw.customerOperation.feesByQuantityUsd } } : {}) } : undefined, quoteConfirmed: raw.quoteConfirmed === true, quoteConfirmedAt: optionalText(raw.quoteConfirmedAt), quoteConfirmedBy: optionalText(raw.quoteConfirmedBy), systemQuantityQuotes: normalizeCustomerPrices(raw.systemQuantityQuotes), sheetQuote: normalizeCustomerPrices(raw.sheetQuote), customerQuote: normalizeCustomerPrices(raw.customerQuote), id: recordId, no: String(raw.no), _version: raw._version == null ? undefined : n(raw._version), salespersonName: String(raw.salespersonName || '报价专员'), salespersonAccount: String(raw.salespersonAccount || '—'), customerName: String(raw.customerName || '未填写客户'), quoteMode: raw.quoteMode === 'bundle' ? 'bundle' : 'single', productSummary: String(raw.productSummary || '—'), productImage: raw.productImage ? String(raw.productImage) : undefined, primarySku: String(raw.primarySku || '—'), bundleItems: bundleItems.length ? bundleItems : undefined, productCategory: raw.productCategory ? String(raw.productCategory) : undefined, logisticsAttribute: String(raw.logisticsAttribute || '—'), purchaseBaseUnitPriceCny: optionalNumber(raw.purchaseBaseUnitPriceCny), purchaseInvoiceType: optionalText(raw.purchaseInvoiceType), purchaseInvoiceRatePercent: optionalNumber(raw.purchaseInvoiceRatePercent), purchaseInvoiceTaxApplied: typeof raw.purchaseInvoiceTaxApplied === 'boolean' ? raw.purchaseInvoiceTaxApplied : undefined, purchaseUnitPriceCny: optionalNumber(raw.purchaseUnitPriceCny), domesticFreightPerUnitCny: optionalNumber(raw.domesticFreightPerUnitCny), volumetricEnabled: raw.volumetricEnabled === true, packageLengthCm: optionalNumber(raw.packageLengthCm), packageWidthCm: optionalNumber(raw.packageWidthCm), packageHeightCm: optionalNumber(raw.packageHeightCm), defaultVolumeDivisor: raw.defaultVolumeDivisor == null ? undefined : Math.max(1, n(raw.defaultVolumeDivisor)), country: String(raw.country || '—'), carrier: String(raw.carrier || '—'), channel: String(raw.channel || '—'), rule: String(raw.rule || '—'), customerGrade: String(raw.customerGrade || '—'), taxCustomerType: raw.taxCustomerType === 'B' ? 'B' : raw.taxCustomerType === 'A' ? 'A' : undefined, commissionThreshold: raw.commissionThreshold === undefined ? 1 : Number(raw.commissionThreshold), monthlySalesEstimate: raw.monthlySalesEstimate ? String(raw.monthlySalesEstimate) : undefined, matrixMode: raw.matrixMode === 'specified' || raw.matrixMode === 'template' ? raw.matrixMode : 'common', quotationTemplateId: raw.quotationTemplateId ? String(raw.quotationTemplateId) : undefined, quotationTemplateName: raw.quotationTemplateName ? String(raw.quotationTemplateName) : undefined, specifiedQuotes, quoteOptions, customQuoteQuantity: raw.customQuoteQuantity == null ? undefined : Math.max(1, Math.floor(n(raw.customQuoteQuantity))), dealOptionId: optionalText(raw.dealOptionId), dealOptionLabel: optionalText(raw.dealOptionLabel), dealLines, systemQuoteCny: n(raw.systemQuoteCny), systemQuoteUsd: n(raw.systemQuoteUsd), totalCostCny: n(raw.totalCostCny), exchangeRate: n(raw.exchangeRate), status: raw.status === 'won' || raw.status === 'lost' ? raw.status : 'pending', actualQuoteUsd: raw.actualQuoteUsd == null ? undefined : n(raw.actualQuoteUsd), actualQuoteCny: raw.actualQuoteCny == null ? undefined : n(raw.actualQuoteCny), dealQuantity: raw.dealQuantity == null ? undefined : n(raw.dealQuantity), closedAt: raw.closedAt, note: raw.note, createdAt: String(raw.createdAt || new Date().toISOString()), updatedAt: String(raw.updatedAt || raw.createdAt || new Date().toISOString()), revisions: normalizeRevisions(raw.revisions) }
}
export async function loadQuotationRecords(scope: 'mine' | 'company' = 'company') {
  type Page = { items: QuotationRecord[]; total: number; totalPages: number }
  // Analytics must not silently stop at the API's 100-row page limit.
  // Retry one unstable traversal; never publish partial totals as complete data.
  for (let attempt = 0; attempt < 2; attempt++) {
    const first = await api.get<Page>(`/quotations?scope=${scope}&size=100&page=0`)
    const rows = [...first.items]
    let stable = true
    for (let page = 1; page < first.totalPages; page++) {
      const next = await api.get<Page>(`/quotations?scope=${scope}&size=100&page=${page}`)
      if (next.total !== first.total || next.totalPages !== first.totalPages || !next.items.length) { stable = false; break }
      rows.push(...next.items)
    }
    if (stable && rows.length === first.total && new Set(rows.map(row => row.id)).size === rows.length) {
      return rows.map(normalizeQuotationRecord).filter((row): row is QuotationRecord => !!row).sort((a, b) => b.createdAt.localeCompare(a.createdAt))
    }
  }
  throw new Error('报价记录正在变化，完整统计尚未读取成功，请刷新重试')
}
export async function saveQuotationRecords(_rows: QuotationRecord[]) { throw new Error('报价记录必须通过独立报价 API 创建或修改') }
export async function createQuotationRecord(input: Omit<QuotationRecord, 'id' | 'no' | 'status' | 'createdAt' | 'updatedAt' | 'revisions'>) {
  const raw = await api.post<QuotationRecord>('/quotations', input, idempotencyKey('quotation-create'))
  return normalizeQuotationRecord(raw)!
}
export async function updateQuotationRecord(id: string, patch: QuotationRecordUpdate, expectedVersion?: number) {
  const raw = await api.patch<QuotationRecord>(`/quotations/${id}`, { ...patch, _version: expectedVersion })
  return normalizeQuotationRecord(raw)
}

export interface ReviewAction { action:'claim'|'cancel'|'release'|'complete'; financeReviewStatus?:'approved'|'rejected'; note?:string }
export interface QuotationReviewEvent { id:string; action:string; before:string; after:string; actorAccount:string; actorName:string; at:string; note:string; quoteVersion:number }
export async function loadQuotationReviewHistory(id:string):Promise<QuotationReviewEvent[]> { return api.get('/quotations/'+encodeURIComponent(id)+'/review-history') }
export async function reviewQuotationRecord(id: string, action: ReviewAction, version?: number, reviewVersion=0) {
  return normalizeQuotationRecord(await api.patch<QuotationRecord>('/quotations/'+encodeURIComponent(id)+'/finance-review', { ...action, _version: version, _reviewVersion: reviewVersion }))!
}
export type QuotationReviewState = Pick<QuotationRecord, 'id' | 'lifecycleState' | '_version' | '_reviewVersion' | 'financeReviewClaimedAccount' | 'financeReviewClaimedBy' | 'financeReviewStartedAt' | 'financeReviewNote' | 'financeReviewStatus' | 'financeReviewedAt' | 'financeReviewedBy' | 'financeReviewedAccount'>
export async function loadQuotationReviewStates(ids: string[], signal?: AbortSignal): Promise<QuotationReviewState[]> {
  if (!ids.length) return []
  return api.get('/quotations/review-status?'+new URLSearchParams({ ids: ids.join(',') }), { signal, cache: 'no-store' })
}
