import { decimal } from '@/services/quotationDecimal'
import type { FinanceQuoteTaxResult } from './financeTaxSettings'

// EU membership, not geographic Europe: https://eur-lex.europa.eu/EN/legal-content/glossary/member-states.html
const euCountries = [
  ['AT', '奥地利', 'Austria'], ['BE', '比利时', 'Belgium'], ['BG', '保加利亚', 'Bulgaria'],
  ['HR', '克罗地亚', 'Croatia'], ['CY', '塞浦路斯', 'Cyprus'], ['CZ', '捷克', 'Czechia', 'Czech Republic'],
  ['DK', '丹麦', 'Denmark'], ['EE', '爱沙尼亚', 'Estonia'], ['FI', '芬兰', 'Finland'],
  ['FR', '法国', 'France'], ['DE', '德国', 'Germany'], ['GR', '希腊', 'Greece'],
  ['HU', '匈牙利', 'Hungary'], ['IE', '爱尔兰', 'Ireland'], ['IT', '意大利', 'Italy'],
  ['LV', '拉脱维亚', 'Latvia'], ['LT', '立陶宛', 'Lithuania'], ['LU', '卢森堡', 'Luxembourg'],
  ['MT', '马耳他', 'Malta'], ['NL', '荷兰', 'Netherlands'], ['PL', '波兰', 'Poland'],
  ['PT', '葡萄牙', 'Portugal'], ['RO', '罗马尼亚', 'Romania'], ['SK', '斯洛伐克', 'Slovakia'],
  ['SI', '斯洛文尼亚', 'Slovenia'], ['ES', '西班牙', 'Spain'], ['SE', '瑞典', 'Sweden'],
]
const euNames = new Set(euCountries.flat().map(value => value.toUpperCase()))
export const EU_YUNEXPRESS_CHC_CHANNEL_CODES = [
  'C-600c364a09421e97a32f', // 云途欧洲专线（特惠带电）-CHC
  'C-f79790fa71c225481346', // 云途欧洲专线（特惠普货）-CHC
  'C-12d8524ab88e7866389a', // 云途欧洲化妆品专线-CHC
] as const

export type EuYunExpressTaxContext = { channelKey: string; weightKg?: number; eurUsd?: number; quantity?: number; unit?: '件' | '套' }
export type EuYunExpressTaxSnapshot = { rule: 'eu-yunexpress-chc-v1'; weightKg: number; eurUsd: number; taxEur: number; taxUsd: number }

export function matchesEuYunExpressTax(country: string, provider: string, key: string) {
  const parts = key.split('::')
  return euNames.has(country.trim().toUpperCase()) && /^(云途|YunExpress)$/i.test(provider.trim())
    && (parts.length === 1 || (parts.length === 3 && /^(云途|YunExpress)$/i.test(parts[1]!)))
    && EU_YUNEXPRESS_CHC_CHANNEL_CODES.some(code => code === parts.at(-1))
}

export function calculateEuYunExpressTax(country: string, provider: string, baseUsd: number, context?: EuYunExpressTaxContext): FinanceQuoteTaxResult | null {
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
