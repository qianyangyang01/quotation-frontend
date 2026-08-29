export const QUALITY_OPTIONS = [
  { value: '优', label: '优：没有次品和短少' },
  { value: '良', label: '良：有次品但可退换' },
  { value: '不良', label: '不良：多次异常，不可退换单' },
] as const

export const INVOICE_TYPE_OPTIONS = ['专票', '普票', '没票'] as const
export const PRICE_LEVEL_OPTIONS = ['市场最低', '居中', '偏高'] as const
export const STOCKING_OPTIONS = ['成品备货', '半成品备货', '包材备货', '辅材备货'] as const

export type ScoreBreakdown = {
  quality: number | null
  delivery: number | null
  afterSales: number | null
  hotProduct: number | null
  freeSample: number | null
  priceLevel: number | null
  invoice: number | null
}

export type SupplierScoreInput = {
  qualityGrade: string
  deliveryTerms: string
  afterSalesAvailable: boolean | null
  hotProductRecommendation: boolean | null
  freeSample: boolean | null
  priceLevel: string
  invoiceType: string
  taxPointPercent: number | '' | null
}

export type SupplierScorePreview = {
  complete: boolean
  total: number | null
  missingItems: string[]
  breakdown: ScoreBreakdown
}

export const SCORE_ITEM_LABELS: Record<keyof ScoreBreakdown, string> = {
  quality: '质量',
  delivery: '交期',
  afterSales: '售后',
  hotProduct: '爆品推荐',
  freeSample: '免费样品',
  priceLevel: '价格水平',
  invoice: '开票及票点',
}

export function normalizeQualityGrade(value: string) {
  const normalized = value.trim()
  if (/^(?:A|A（优）|A\(优\)|优)$/i.test(normalized)) return '优'
  if (/^(?:B|B（良）|B\(良\)|良)$/i.test(normalized)) return '良'
  if (/^(?:C|C（不良）|C\(不良\)|不良)$/i.test(normalized)) return '不良'
  return normalized
}

export function normalizeInvoiceType(value: string | null | undefined) {
  const normalized = value?.trim() || ''
  return normalized === '不开票' ? '没票' : normalized
}

export function invoiceNeedsTaxPoint(value: string | null | undefined) {
  const normalized = normalizeInvoiceType(value)
  return normalized === '专票' || normalized === '普票'
}

export function taxPointDecimalForInvoice(invoiceType: string | null | undefined, percent: number | '' | null) {
  const numericPercent = numberDraft(percent)
  return invoiceNeedsTaxPoint(invoiceType) && numericPercent != null ? numericPercent / 100 : null
}

export function qualityLabel(value: string | null | undefined) {
  const normalized = normalizeQualityGrade(value || '')
  return QUALITY_OPTIONS.find((option) => option.value === normalized)?.label || normalized || '暂无数据'
}

export function validDeliveryDays(value: string) {
  return value === '' || /^(?:0|[1-9]\d*)$/.test(value)
}

export function deliveryLabel(value: string | null | undefined) {
  const normalized = value?.trim() || ''
  if (!normalized) return '暂无数据'
  return /^\d+$/.test(normalized) ? `${normalized} 天` : normalized
}

function numberDraft(value: number | '' | null) {
  return typeof value === 'number' && Number.isFinite(value) ? value : null
}

export function calculateSupplierScore(input: SupplierScoreInput): SupplierScorePreview {
  const quality = normalizeQualityGrade(input.qualityGrade)
  const delivery = input.deliveryTerms.trim()
  const invoiceType = normalizeInvoiceType(input.invoiceType)
  const taxPointPercent = numberDraft(input.taxPointPercent)

  const validDelivery = delivery !== '' && validDeliveryDays(delivery)
  const validTaxPoint = taxPointPercent != null && taxPointPercent >= 0 && taxPointPercent <= 100
  const breakdown: ScoreBreakdown = {
    quality: quality === '优' ? 30 : quality === '良' ? 20 : quality === '不良' ? 0 : null,
    delivery: validDelivery
      ? Number(delivery) === 0 ? 20 : Number(delivery) === 1 ? 15 : Number(delivery) <= 7 ? 10 : 0
      : null,
    afterSales: input.afterSalesAvailable == null ? null : input.afterSalesAvailable ? 10 : 0,
    hotProduct: input.hotProductRecommendation == null ? null : input.hotProductRecommendation ? 10 : 0,
    freeSample: input.freeSample == null ? null : input.freeSample ? 5 : 0,
    priceLevel: input.priceLevel === '市场最低' ? 10 : input.priceLevel === '居中' ? 5 : input.priceLevel === '偏高' ? 0 : null,
    invoice: invoiceType === '没票'
      ? 0
      : invoiceType === '专票' && validTaxPoint ? taxPointPercent <= 11 ? 15 : 0
        : invoiceType === '普票' && validTaxPoint ? taxPointPercent <= 1 ? 10 : 0
          : null,
  }

  const missingItems = (Object.keys(breakdown) as Array<keyof ScoreBreakdown>)
    .filter((key) => breakdown[key] == null)
    .map((key) => SCORE_ITEM_LABELS[key])
  return {
    complete: missingItems.length === 0,
    total: missingItems.length ? null : Object.values(breakdown).reduce<number>((sum, score) => sum + (score || 0), 0),
    missingItems,
    breakdown,
  }
}
