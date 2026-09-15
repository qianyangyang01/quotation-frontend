import { decimal } from '@/services/quotationDecimal'
import type { FinanceQuoteTaxResult } from './financeTaxSettings'

import { isEuCountry } from './europeanUnion'

export const EU_YUNEXPRESS_CHC_CHANNEL_CODES = [
  'C-600c364a09421e97a32f', // 云途欧洲专线（特惠带电）-CHC
  'C-f79790fa71c225481346', // 云途欧洲专线（特惠普货）-CHC
  'C-12d8524ab88e7866389a', // 云途欧洲化妆品专线-CHC
] as const

export type EuYunExpressTaxContext = { channelKey: string; weightKg?: number; eurUsd?: number; usdCny?: number; quantity?: number; unit?: '件' | '套' }
export type EuYunExpressTaxSnapshot = { rule: 'eu-yunexpress-chc-v1'; weightKg: number; eurUsd: number; taxEur: number; taxUsd: number }

export function matchesEuYunExpressTax(country: string, provider: string, key: string) {
  const parts = key.split('::')
  return isEuCountry(country) && /^(云途|YunExpress)$/i.test(provider.trim())
    && (parts.length === 1 || (parts.length === 3 && /^(云途|YunExpress)$/i.test(parts[1]!)))
    && EU_YUNEXPRESS_CHC_CHANNEL_CODES.some(code => code === parts.at(-1))
}

export function calculateEuYunExpressTax(country: string, provider: string, baseUsd: number, context?: EuYunExpressTaxContext): (Omit<FinanceQuoteTaxResult, 'calculation'> & { calculation?: EuYunExpressTaxSnapshot }) | null {
  if (!context || !matchesEuYunExpressTax(country, provider, context.channelKey)) return null
  const { weightKg, eurUsd } = context
  const invalid = !Number.isFinite(eurUsd) || (eurUsd ?? 0) <= 0 ? '请财务设置欧元兑美元汇率'
    : !Number.isFinite(weightKg) || (weightKg ?? 0) <= 0 ? '欧盟关税缺少有效计费重量' : ''
  if (invalid) return { included: false, configured: false, ratePercent: null, fixedFeeUsd: 0, feeMode: 'missing', taxUsd: 0, totalUsd: baseUsd, label: invalid }
  const taxEur = decimal(weightKg!).times('1.5').plus('0.6').toNumber()
  const taxUsd = decimal(taxEur).times(eurUsd!).toDecimalPlaces(2).toNumber()
  return {
    included: false, configured: true, ratePercent: null, fixedFeeUsd: 0, feeMode: 'weight-eur', taxUsd,
    totalUsd: decimal(baseUsd).plus(taxUsd).toNumber(),
    label: `关税 $${taxUsd.toFixed(2)}/单（${Number.isInteger(context.quantity) && context.quantity! > 0 ? `${context.quantity}${context.unit || '件'} · ` : ''}计费重 ${decimal(weightKg!).times(1000).toNumber()}g）`,
    calculation: { rule: 'eu-yunexpress-chc-v1', weightKg: weightKg!, eurUsd: eurUsd!, taxEur, taxUsd },
  }
}
