import { decimal } from '@/services/quotationDecimal'
import type { FinanceQuoteTaxResult, FinanceTaxSettings } from './financeTaxSettings'
import { matchesEuYunExpressTax, type EuYunExpressTaxContext, type EuYunExpressTaxSnapshot } from './euYunExpressTax'
import { EU_TAX_GROUP, isEuCountry, sameTaxCountry } from './europeanUnion'
import type { EuHandlingTaxSnapshot } from './euHandlingTax'

export type ChannelTaxRule = { key: string; mode: 'fixed-order' | 'weight' | 'exempt' | 'no-tax' | 'unavailable'; amount: number; currency: 'USD' | 'CNY' | 'EUR'; perKg: number; source?: string }
export type ChannelTaxSnapshot = { rule: 'channel-tax-v1'; setting: ChannelTaxRule; country: string; weightKg: number; usdCny: number; eurUsd: number; taxUsd: number }
export type FinanceTaxCalculation = EuYunExpressTaxSnapshot | ChannelTaxSnapshot | EuHandlingTaxSnapshot

export function taxCountryKey(value: string) {
  const aliases: Record<string, string> = { 美国: 'US', 新西兰: 'NZ', 墨西哥: 'MX', 阿联酋: 'AE', 阿拉伯联合酋长国: 'AE', 沙特阿拉伯: 'SA', 哥伦比亚: 'CO', 约旦: 'JO', 摩洛哥: 'MA', 阿曼: 'OM' }
  return aliases[value.trim()] || value.trim().toUpperCase()
}
export function channelTaxCountry(settings: FinanceTaxSettings, country: string) {
  return settings.countries.find(row => row.selected && (sameTaxCountry(row.country, country) || taxCountryKey(row.country) === taxCountryKey(country)))
    ?? (isEuCountry(country) ? settings.countries.find(row => row.selected && row.country === EU_TAX_GROUP) : undefined)
}
export function validateChannelTaxRule(rule: ChannelTaxRule) {
  if (!/^\d+::[^:]+::[^:]+$/.test(rule.key)) throw new Error('渠道标识无效')
  if (!['fixed-order', 'weight', 'exempt', 'no-tax', 'unavailable'].includes(rule.mode)) throw new Error('请选择有效收费方式')
  if (!['USD', 'CNY', 'EUR'].includes(rule.currency)) throw new Error('请选择有效币种')
  for (const value of [rule.amount, rule.perKg]) if (typeof value !== 'number' || !Number.isFinite(value) || value < 0 || value > 1_000_000) throw new Error('费用须为 0 至 1000000 的有效数字')
}
export function validateCountryChannelTax(country: string, rule: ChannelTaxRule) {
  validateChannelTaxRule(rule)
  if (matchesEuYunExpressTax(country === EU_TAX_GROUP ? 'DE' : country, rule.key.split('::')[1] || '', rule.key)
    && !(rule.mode === 'weight' && rule.currency === 'EUR' && rule.amount === 0.6 && rule.perKg === 1.5)) throw new Error('欧盟三条 CHC 渠道须保留欧元重量公式')
}
export function channelTaxAmount(rule: ChannelTaxRule, context: { weightKg?: number; usdCny?: number; eurUsd?: number }) {
  validateChannelTaxRule(rule)
  if (rule.mode === 'unavailable') throw new Error('该渠道不支持当前国家')
  if (rule.mode === 'exempt' || rule.mode === 'no-tax') return 0
  if (rule.mode === 'weight' && (!Number.isFinite(context.weightKg) || (context.weightKg ?? 0) <= 0)) throw new Error('缺少含包材整单计费重量')
  let value = decimal(rule.amount).plus(rule.mode === 'weight' ? decimal(context.weightKg!).times(rule.perKg) : 0)
  if (rule.currency === 'CNY') {
    if (!Number.isFinite(context.usdCny) || (context.usdCny ?? 0) <= 0) throw new Error('请财务设置美元兑人民币汇率')
    value = value.div(context.usdCny!)
  } else if (rule.currency === 'EUR') {
    if (!Number.isFinite(context.eurUsd) || (context.eurUsd ?? 0) <= 0) throw new Error('请财务设置欧元兑美元汇率')
    value = value.times(context.eurUsd!)
  }
  return value.toDecimalPlaces(2).toNumber()
}
export function calculateChannelTax(settings: FinanceTaxSettings, country: string, baseUsd: number, context?: EuYunExpressTaxContext): FinanceQuoteTaxResult | null {
  const setting = channelTaxCountry(settings, country)
  const rule = setting?.channelRules?.find(row => row.key === context?.channelKey)
  if (!rule) return null
  try {
    const taxUsd = channelTaxAmount(rule, context || {})
    const feeMode = rule.mode === 'weight' ? 'weight-order' : rule.mode === 'unavailable' ? 'missing' : rule.mode
    return { included: rule.mode === 'exempt', configured: true, ratePercent: null, fixedFeeUsd: rule.mode === 'fixed-order' ? taxUsd : 0,
      feeMode, taxUsd, totalUsd: decimal(baseUsd).plus(taxUsd).toDecimalPlaces(2).toNumber(),
      label: rule.mode === 'exempt' ? '已含税' : rule.mode === 'no-tax' ? '无关税' : `关税 $${taxUsd.toFixed(2)}/单${rule.mode === 'weight' ? '（按整单重量）' : ''}`,
      calculation: { rule: 'channel-tax-v1', setting: { ...rule }, country: setting!.country, weightKg: context?.weightKg || 0, usdCny: context?.usdCny || 0, eurUsd: context?.eurUsd || 0, taxUsd } }
  } catch (error) {
    return { included: false, configured: false, ratePercent: null, fixedFeeUsd: 0, feeMode: 'missing', taxUsd: 0, totalUsd: baseUsd, label: error instanceof Error ? error.message : '渠道税费设置无效' }
  }
}
