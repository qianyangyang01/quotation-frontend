import { decimal } from '@/services/quotationDecimal'
import { financeSettingVersions, readFinanceSetting } from '@/services/financeSettings'

export interface WomensPriceTier { fromUsd: number; toUsd: number | null; coefficient: number }
export interface WomensPricingPolicy { schemaVersion: 1; basis: 'total-cost-usd'; enabled: boolean; tiers: WomensPriceTier[] }
export interface WomensPricingSnapshot extends WomensPricingPolicy { version: number }
export interface WomensPricingSample extends WomensPriceTier { quantity: number; costCny: number; baseQuoteCny: number }
export const WOMENS_PRICING_UPDATED = 'quotation:womens-pricing-updated'
export const WOMENS_PRICING_UNAVAILABLE = '女装报价系数尚未发布、已停用或配置无效，请联系财务确认'
export function defaultWomensPricing(): WomensPricingPolicy {
  return { schemaVersion: 1, basis: 'total-cost-usd', enabled: true, tiers: [
    { fromUsd: 0, toUsd: 20, coefficient: 1.27 }, { fromUsd: 20, toUsd: 30, coefficient: 1.25 },
    { fromUsd: 30, toUsd: 50, coefficient: 1.23 }, { fromUsd: 50, toUsd: null, coefficient: 1.22 },
  ] }
}
export function womensPricingError(raw: unknown): string {
  if (!raw || typeof raw !== 'object') return '缺少女装系数配置'
  const p = raw as WomensPricingPolicy
  if (p.schemaVersion !== 1 || p.basis !== 'total-cost-usd' || typeof p.enabled !== 'boolean') return '女装系数计价口径无效'
  if (!Array.isArray(p.tiers) || !p.tiers.length || p.tiers.length > 12) return '金额档位须为1至12档'
  const valid = (n: unknown, max: number) => typeof n === 'number' && Number.isFinite(n) && n >= 0 && n <= max && decimal(n).decimalPlaces() <= 6
  let lower = 0
  for (const [i, t] of p.tiers.entries()) {
    if (!t || !valid(t.fromUsd, 1_000_000) || t.fromUsd !== lower || !valid(t.coefficient, 10) || t.coefficient <= 0) return '区间必须从0起连续，系数须大于0且不超过10，最多6位小数'
    if (i === p.tiers.length - 1) { if (t.toUsd !== null) return '最后一档必须无上限' }
    else if (!valid(t.toUsd, 1_000_000) || t.toUsd! <= t.fromUsd) return '区间上限必须大于下限，不可重叠或留空'
    lower = t.toUsd ?? lower
  }
  return ''
}
export function loadWomensPricing(): WomensPricingSnapshot | null {
  const value = readFinanceSetting<WomensPricingPolicy>('womens-pricing')
  const version = financeSettingVersions()['womens-pricing'] ?? -1
  return value && !womensPricingError(value) && version >= 0 ? { ...structuredClone(value), version } : null
}
/** Compare CNY against USD boundary × exchange rate; no rounded USD can choose the wrong band. */
export function womensPricingSample(policy: WomensPricingPolicy, costCny: number, exchangeRate: number, quantity = 1): WomensPricingSample {
  if (womensPricingError(policy) || !policy.enabled) throw new Error(WOMENS_PRICING_UNAVAILABLE)
  if (!Number.isFinite(costCny) || costCny < 0 || !Number.isFinite(exchangeRate) || exchangeRate <= 0 || !Number.isSafeInteger(quantity) || quantity < 1) throw new Error('女装计价成本、汇率或数量无效')
  const cost = decimal(costCny), rate = decimal(exchangeRate)
  const tier = policy.tiers.find(t => cost.gte(decimal(t.fromUsd).times(rate)) && (t.toUsd == null || cost.lt(decimal(t.toUsd).times(rate))))!
  return { ...tier, quantity, costCny, baseQuoteCny: cost.times(tier.coefficient).toNumber() }
}
export function womensTierLabel(t: WomensPriceTier) { return t.toUsd == null ? `≥ $${t.fromUsd}` : `$${t.fromUsd} ≤ 金额 < $${t.toUsd}` }
export function womensPolicyLabel(p: WomensPricingPolicy) { return p.tiers.map(t => `${womensTierLabel(t)}：${t.coefficient}`).join('；') }
