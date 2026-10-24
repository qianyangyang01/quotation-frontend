import { legacyUntaxedUnitPrice } from '@/data/legacyPurchasePricing'
import { selectedPurchaseTier, type PurchaseProductRecord } from '@/data/purchaseStore'
import { purchasePriceBreakdown } from './quotationCalculator'
import { decimal } from './quotationDecimal'

/** Preserve the supplier's fractional-cent quote; only derived costs round to cents. */
export function formatLegacyPurchaseUnitPrice(value: number): string {
  const amount = decimal(value)
  return amount.toFixed(Math.max(2, amount.decimalPlaces()))
}

/** Presentation only: never feed these amounts back into quotation calculations. */
export type LegacyPurchaseDisplay = {
  label: string
  unitPrice: number
  effectiveUnitPrice: number
  detail: string
  range: string
}

export function legacyPurchaseDisplay(record: PurchaseProductRecord, estimate: string): LegacyPurchaseDisplay | undefined {
  if (record.dataSource !== 'legacy_2026') return undefined
  const { index, tier } = selectedPurchaseTier(record, estimate)
  if (tier == null && record.purchasePriceCny == null) return undefined
  const quotedTier = ['阶梯价2', '阶梯价3', '阶梯价4', '阶梯价5'].includes(tier?.source ?? '')
  const untaxed = quotedTier && tier ? tier.unitPriceCny : legacyUntaxedUnitPrice(record)
  const pricing = purchasePriceBreakdown(record, estimate)
  const effective = pricing.effectiveUnitPriceCny
  const rate = record.taxPoint == null ? '票点待补充' : `票点 ${Number((record.taxPoint * 100).toFixed(6))}%`
  const range = tier ? (tier.maxQty == null ? `${tier.minQty}件起订` : `${tier.minQty}–${tier.maxQty}件`)
    : record.minOrderQty == null ? '起订量待补充' : `${record.minOrderQty}件起订`
  const missing = untaxed == null ? ' · 未税单价待确认' : ''
  const detail = record.taxPoint == null ? `${rate} · 报价前需补齐${missing}` : record.taxPoint === 0
    ? `${rate} · 报价另加1% · 计入采购成本 ¥${effective.toFixed(2)}/件${missing}`
    : `${rate} · 含票价 ¥${effective.toFixed(2)}/件（计入采购成本，不重复加点）${missing}`
  return {
    label: untaxed == null ? '含票价' : '未税单价',
    unitPrice: untaxed ?? tier?.unitPriceCny ?? record.purchasePriceCny ?? effective,
    effectiveUnitPrice: effective,
    detail,
    range: record.priceTiers.length > 1 ? `第${index + 1}档 · ${range}` : range,
  }
}
