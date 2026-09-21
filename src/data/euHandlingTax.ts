import { decimal } from '@/services/quotationDecimal'
import { channelTaxAmount, type ChannelTaxRule } from './channelTaxRules'
import { EU_TAX_GROUP, isEuCountry, sameTaxCountry } from './europeanUnion'
import { calculateFinanceQuoteTax, type FinanceQuoteTaxResult, type FinanceTaxSettings } from './financeTaxSettings'
import type { EuYunExpressTaxContext } from './euYunExpressTax'

export type EuHandlingTaxSnapshot = {
  rule: 'eu-handling-v1'
  country: string
  channelKey: string
  weightKg: number
  euTaxUsd: number
  handlingFeeUsd: number
  taxUsd: number
}

// Opt-in only: keep the old country duty and the new processing fee separate.
export function calculateEuHandlingTax(settings: FinanceTaxSettings, country: string, provider: string, baseUsd: number, context?: EuYunExpressTaxContext): FinanceQuoteTaxResult | null {
  const local = settings.countries.find(row => row.selected && sameTaxCountry(row.country, country))
  if (!isEuCountry(country) || local?.euTaxMode !== 'add-handling') return null
  const missing = (label: string): FinanceQuoteTaxResult => ({ included: false, configured: false, ratePercent: null, fixedFeeUsd: 0, feeMode: 'missing', taxUsd: 0, totalUsd: baseUsd, label })
  if (!settings.countries.some(row => row.selected && row.country === EU_TAX_GROUP)) return missing('请先设置欧盟关税，再叠加本国处理费')
  const eu = calculateFinanceQuoteTax({ ...settings, countries: settings.countries.filter(row => !sameTaxCountry(row.country, country)) }, country, provider, 0, context)
  if (!eu.configured) return missing(`欧盟关税：${eu.label}`)
  const handling: ChannelTaxRule | undefined = local.handlingRules?.find(row => row.key === context?.channelKey)
  try {
    const handlingFeeUsd = handling ? channelTaxAmount(handling, context || {}) : 0
    const taxUsd = decimal(eu.taxUsd).plus(handlingFeeUsd).toDecimalPlaces(2).toNumber()
    const weighted = ['weight-order', 'weight-eur'].includes(eu.feeMode) || handling?.mode === 'weight'
    const included = eu.included && handling?.mode === 'exempt'
    return {
      included, configured: true, ratePercent: null,
      fixedFeeUsd: weighted ? 0 : taxUsd, feeMode: included ? 'exempt' : weighted ? 'weight-order' : 'fixed-order', taxUsd,
      totalUsd: decimal(baseUsd).plus(taxUsd).toDecimalPlaces(2).toNumber(),
      label: included ? '已含税' : `欧盟关税 $${eu.taxUsd.toFixed(2)} + 本国处理费 $${handlingFeeUsd.toFixed(2)} = $${taxUsd.toFixed(2)}/单${weighted ? '（按整单重量）' : ''}`,
      calculation: { rule: 'eu-handling-v1', country: local.country, channelKey: context?.channelKey || '', weightKg: context?.weightKg || 0, euTaxUsd: eu.taxUsd, handlingFeeUsd, taxUsd },
    }
  } catch (error) { return missing(error instanceof Error ? error.message : '本国处理费设置无效') }
}
