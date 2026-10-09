import { decimal } from './quotationDecimal'
import { ApiError } from './http'
import { loadFobRecord, type FobParsed, type FobRecord } from './fobPurchase'
import { loadPurchaseProduct, type PurchaseProductRecord } from '@/data/purchaseStore'

export type FobSourceMode = 'auto' | 'fob' | 'standard'
export type FobSmallOrderPolicy = { scope: 'single-price' | 'all'; calculation: 'before-coefficient' | 'after-coefficient' }
export const FOB_SMALL_ORDER_EXTRA_CNY = 1
export const FOB_SMALL_ORDER_POLICY: FobSmallOrderPolicy = { scope: 'single-price', calculation: 'before-coefficient' }
export interface FobQuoteProduct {
  sku: string; category: string; weight: string; source: 'fob' | 'standard'; updatedAt?: string
  parsed: FobParsed; notices: string[]
}
export function fromFobRecord(record: FobRecord): FobQuoteProduct {
  if (!record.parsed) throw new Error('FOB资料尚未完成有效解析，请在采购资料中重新预览保存')
  return { sku: record.sku, category: record.category || '', weight: record.weightRaw || '未填写', source: 'fob', updatedAt: record.updatedAt,
    parsed: record.parsed, notices: record.parsed.notices || [] }
}
export function fromStandardPurchase(record: PurchaseProductRecord): FobQuoteProduct {
  if (record.dataSource !== 'standard') throw new Error('该SKU只有旧采购资料，请先通过FOB粘贴更新维护专用资料，或补充新采购资料')
  if (record.catalogState !== 'ready' || record.skuOrigin === 'system') throw new Error('该采购商品未转正式或已停用，暂不可用于FOB报价')
  if (record.purchasePriceBasis === 'tax_included') throw new Error('该采购资料按含票价存储，无法直接作为FOB不含票采购价；请先维护FOB专用资料')
  if (!record.minOrderQty || !record.priceTiers.length) throw new Error('采购资料缺少起订量或有效采购阶梯')
  const total = record.freeShipping === '是' ? 0 : record.freight100Cny
  if (total == null || !Number.isFinite(total) || total < 0) throw new Error('新采购资料缺少100件总运费；请补充百件运费，或通过FOB粘贴更新录入更大批量运费')
  return { sku: record.sku, category: record.category, weight: record.weightG == null ? '未填写' : `${record.weightG} g`, source: 'standard', updatedAt: record._updatedAt,
    notices: ['使用新采购资料的不含票阶梯价；FOB统一按10%票点计算'], parsed: {
      minOrderQty: record.minOrderQty, orderMultiple: 1, priceTiers: record.priceTiers.map(t => ({ ...t, unit: '件' })),
      freight: { quantity: 100, totalFreightCny: total, unitFreightCny: decimal(total).div(100).toNumber(), estimated: false, basis: record.freeShipping === '是' ? '包邮' : `100件总运费 ${total} ÷ 100` },
    } }
}
export async function loadFobQuoteProduct(sku: string, source: FobSourceMode, signal?: AbortSignal): Promise<FobQuoteProduct> {
  const normalized = sku.trim().toUpperCase().replace(/\s+/g, '')
  if (!/^[A-Z0-9][A-Z0-9._/-]{0,95}$/.test(normalized)) throw new Error('请填写有效SKU')
  if (source !== 'standard') {
    try { return fromFobRecord(await loadFobRecord(normalized, signal)) }
    catch (e) { if (source === 'fob' || !(e instanceof ApiError) || e.status !== 404 || e.code !== 'FOB_PURCHASE_NOT_FOUND') throw e }
  }
  return fromStandardPurchase(await loadPurchaseProduct(normalized, signal))
}
export interface FobQuoteRow {
  minQty: number; maxQty: number | null; unit: string; purchaseCny: number; taxIncludedCny: number; freightCny: number; costCny: number
  declaredUsd: string; undeclaredUsd: string; thresholdQty: number | null
}
export function fobTierQuotes(product: FobQuoteProduct, rate: number): FobQuoteRow[] {
  if (!Number.isFinite(rate) || rate <= 0) throw new Error('美元汇率必须大于0')
  const { minOrderQty, orderMultiple, freight, priceTiers } = product.parsed
  if (!Number.isSafeInteger(minOrderQty) || minOrderQty < 1 || !Number.isSafeInteger(orderMultiple) || orderMultiple < 1) throw new Error('起订量或下单倍数无效')
  if (!Number.isFinite(freight.unitFreightCny) || freight.unitFreightCny < 0 || !priceTiers.length) throw new Error('采购价或均摊运费无效')
  return priceTiers.map((tier, i) => {
    if (!Number.isSafeInteger(tier.minQty) || tier.minQty < minOrderQty || (tier.maxQty != null && (!Number.isSafeInteger(tier.maxQty) || tier.maxQty < tier.minQty))
      || (i > 0 && (priceTiers[i - 1]!.maxQty == null || priceTiers[i - 1]!.maxQty! >= tier.minQty))
      || !Number.isFinite(tier.unitPriceCny) || tier.unitPriceCny < 0) throw new Error('采购阶梯数量区间或金额无效，请核对资料')
    const included = decimal(tier.unitPriceCny).times('1.1'), cost = included.plus(freight.unitFreightCny)
    const threshold = cost.gt(0) ? decimal(200).div(cost).ceil().toNumber() : null
    const validThreshold = threshold == null ? null : Math.ceil(Math.max(minOrderQty, tier.minQty, threshold) / orderMultiple) * orderMultiple
    return { minQty: tier.minQty, maxQty: tier.maxQty, unit: tier.unit, purchaseCny: tier.unitPriceCny, taxIncludedCny: included.toNumber(), freightCny: freight.unitFreightCny,
      costCny: cost.toNumber(), declaredUsd: cost.times('1.14').div(rate).toFixed(2), undeclaredUsd: cost.times('1.14').times('1.02').div(rate).toFixed(2),
      thresholdQty: validThreshold != null && Number.isSafeInteger(validThreshold) && (tier.maxQty == null || validThreshold <= tier.maxQty) ? validThreshold : null }
  })
}
export function fobQuantityQuote(product: FobQuoteProduct, rows: FobQuoteRow[], quantity: string, rate: number, policy: FobSmallOrderPolicy | null) {
  if (!/^\d+$/.test(quantity) || !Number.isSafeInteger(Number(quantity)) || Number(quantity) < 1) throw new Error('报价数量须为正整数')
  const count = Number(quantity), { minOrderQty, orderMultiple } = product.parsed
  if (count < minOrderQty) throw new Error(`报价数量不能低于起订量 ${minOrderQty}`)
  if (count % orderMultiple !== 0) throw new Error(`数量必须为 ${orderMultiple} 的倍数`)
  const row = rows.find(t => count >= t.minQty && (t.maxQty == null || count <= t.maxQty))
  if (!row) throw new Error('此数量没有有效采购价，请补充相应阶梯')
  if (!Number.isFinite(rate) || rate <= 0) throw new Error('美元汇率必须大于0')
  const below = decimal(row.costCny).times(count).lt(200)
  if (below && policy == null) throw new Error('不足200元的加价规则尚未确认，暂不生成此数量的最终报价')
  const extra = below && (policy?.scope === 'all' || rows.length === 1) ? FOB_SMALL_ORDER_EXTRA_CNY : 0
  const price = (factor: string) => {
    const amount = policy?.calculation === 'before-coefficient' ? decimal(row.costCny).plus(extra).times(factor) : decimal(row.costCny).times(factor).plus(extra)
    return amount.div(rate).toFixed(2)
  }
  return { quantity: count, row, extraCny: extra, declaredUsd: price('1.14'), undeclaredUsd: price('1.1628') }
}

// Customer-facing rows contain only sale prices. Split at the surcharge boundary
// so a displayed range always has one valid final price, including order multiples.
export interface FobSheetRow {
  minQty: number; maxQty: number | null; unit: string; declaredUsd: string; undeclaredUsd: string
}
export function fobSheetRows(product: FobQuoteProduct, rate: number, policy: FobSmallOrderPolicy | null): FobSheetRow[] {
  const tiers = fobTierQuotes(product, rate), multiple = product.parsed.orderMultiple
  return tiers.flatMap(row => {
    const first = Math.ceil(row.minQty / multiple) * multiple
    const last = row.maxQty == null ? null : Math.floor(row.maxQty / multiple) * multiple
    if (!Number.isSafeInteger(first) || (last != null && first > last)) return []
    const split = (policy == null || policy.scope === 'all' || tiers.length === 1)
      && row.thresholdQty != null && row.thresholdQty > first ? row.thresholdQty : null
    const ranges = split == null ? [[first, last]] : [[first, split - multiple], [split, last]]
    return ranges.map(([min, max]) => {
      const quote = fobQuantityQuote(product, tiers, String(min), rate, policy)
      return { minQty: min!, maxQty: max ?? null, unit: row.unit, declaredUsd: quote.declaredUsd, undeclaredUsd: quote.undeclaredUsd }
    })
  })
}
