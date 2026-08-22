export type QuotationProduct = {
  id: number
  selected: boolean
  name: string
  sku: string
  supplier: string
  image: string
  physicalImage: string
  stockStatus: '有货' | '无货' | '待确认'
  /** 本次报价由业务员统一选择，不从采购资料继承。 */
  logisticsAttribute: string
  quantity: number
  purchase: number
  purchaseFreightPerUnit: number
  netWeight: number
  country: string
  channel: string
  rule: string
  manualFreight: boolean
  freight: number
  margin: number
  memberMargin: number
  profitType: 'rate' | 'fixed'
  fixedProfit: number
  weightSource: 'purchase' | 'manual'
  manualWeight: number
  volumetricEnabled: boolean
  packageLengthCm: number
  packageWidthCm: number
  packageHeightCm: number
  discountEnabled: boolean
  discountRate: number
  status: string
}

export type QuotationMode = 'single' | 'bundle'

export const quotationProductCategories = ['文胸', '内裤', '袜子', '服装', '保健品', '化妆品'] as const
export type QuotationProductCategory = (typeof quotationProductCategories)[number]

export type BundleQuoteItem = {
  id: number
  sku: string
  name: string
  supplier: string
  image: string
  stockStatus: '有货' | '无货' | '待确认'
  quantityPerSet: number
  purchaseUnitPrice: number
  customWeightKg: number | null
  purchaseFreightPerUnit: number
  weightKg: number
  status: string
}

export type QuotationMatrixRow = {
  country: string
  /** 仅用于同一国家存在独立报价区时；当前为澳大利亚 1～4 区。 */
  quoteRegion?: string
  /** 财务渠道稳定标识：规则 ID + 物流商 + 渠道代码。模板匹配以此字段为准。 */
  channelKey: string
  ruleId: number
  channelCode: string
  available?: boolean
  availabilityMessage?: string
  rule: string
  carrier: string
  transport: string
  eta: string
  freight: number
  totalCostCny: number
  profitCny: number
  quoteCny: number
  quote1: number | null
  quote2: number | null
  quote3: number | null
  quoteCustom: number | null
  /** 最终报价已经包含此处显示的税务规则。 */
  taxIncluded: boolean
  taxConfigured: boolean
  taxRatePercent: number | null
  countryFixedTaxUsd: number
  taxCustomerType: 'A' | 'B'
  taxFeeMode: 'exempt' | 'fixed-order' | 'per-item' | 'missing'
  taxPerItemFeeUsd: number
  taxLabel: string
  tax1Usd: number | null
  tax2Usd: number | null
  tax3Usd: number | null
  taxCustomUsd: number | null
}

/**
 * 报价模板只保存渠道身份，不保存本次试算价格。
 * channelKey 是首选稳定标识；旧模板没有 channelKey 时仍可按三段业务字段兼容匹配。
 */
export type QuotationPresetSelection = {
  country: string
  channelKey?: string
  rule?: string
  carrier?: string
  transport?: string
}

export type QuotationCountrySummary = {
  name: string
  code: string
  channelCount: number
  lowestQuote: number | null
  grouped: boolean
  stage: 'common' | 'standard' | 'rare'
  continent: '亚洲' | '欧洲' | '北美洲' | '南美洲' | '非洲' | '大洋洲'
  sortOrder: number
  quoteRegions?: string[]
  selectedQuoteRegion?: string
}
