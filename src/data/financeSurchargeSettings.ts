import { sumDecimal } from '@/services/quotationDecimal'
import { sameCountryIdentity } from './countryIdentity'
import type { EuYunExpressTaxContext } from './euYunExpressTax'
import { roundQuoteUsd } from '@/services/quotationMoney'
import { normalizeFinanceTaxSettings, calculateFinanceQuoteTax, type FinanceTaxSettings } from './financeTaxSettings'
import { readFinanceSetting, writeFinanceSetting } from '@/services/financeSettings'

export type FinanceSurchargeSettings = Omit<FinanceTaxSettings, 'countries'> & {
  countries: (FinanceTaxSettings['countries'][number] & { exemptChannelKeys?: string[]; providers?: FinanceTaxSettings['providers'] })[]
}
export const FINANCE_SURCHARGE_SETTINGS_UPDATED_EVENT = 'milano:finance-surcharge-settings-updated'

export function normalizeFinanceSurchargeSettings(raw?: Partial<FinanceSurchargeSettings> | null): FinanceSurchargeSettings {
  const normalized = normalizeFinanceTaxSettings(raw, false)
  normalized.providers = normalized.providers.map(provider => {
    const stored = raw?.providers?.find(row => row.provider === provider.provider)
    // Surcharge exemptions are independent of the existing tax defaults.
    return { ...provider, selected: stored?.selected === true, mode: stored?.mode === 'exempt' ? 'exempt' : 'taxable' }
  })
  return { ...normalized, countries: normalized.countries.map(country => {
    const stored = raw?.countries?.find(row => row.country === country.country)
    return { ...country, ...(Array.isArray(stored?.providers) ? { providers: stored.providers.map(row => ({ ...row, channels: [...row.channels] })) } : {}), ...(Array.isArray(stored?.exemptChannelKeys) ? { exemptChannelKeys: [...new Set(stored.exemptChannelKeys)] } : {}) }
  }) }
}

export function loadFinanceSurchargeSettings(): FinanceSurchargeSettings {
  return normalizeFinanceSurchargeSettings(readFinanceSetting<FinanceSurchargeSettings>('surcharge-settings'))
}

export async function saveFinanceSurchargeSettings(settings: FinanceSurchargeSettings) {
  for (const country of settings.countries) {
    if (typeof country.fixedFeeUsd !== 'number' || !Number.isFinite(country.fixedFeeUsd) || country.fixedFeeUsd < 0) {
      throw new Error(`${country.country}附加费必须为有效非负金额`)
    }
  }
  const normalized = normalizeFinanceSurchargeSettings({ ...settings, updatedAt: new Date().toLocaleString('zh-CN', { hour12: false }) })
  await writeFinanceSetting('surcharge-settings', normalized)
  if (typeof window !== 'undefined') window.dispatchEvent(new CustomEvent(FINANCE_SURCHARGE_SETTINGS_UPDATED_EVENT))
  return normalized
}

export function calculateFinanceQuoteFees(taxes: FinanceTaxSettings, surcharges: FinanceSurchargeSettings, country: string, provider: string, baseUsd: number, channelKey = '', context?: Omit<EuYunExpressTaxContext, 'channelKey'>) {
  const tax = calculateFinanceQuoteTax(taxes, country, provider, baseUsd, { ...context, channelKey })
  const countrySetting = surcharges.countries.find(row => row.selected && sameCountryIdentity(row.country, country))
  // Recognize aliases of one country without inheriting customs-duty country groups.
  const countrySurcharges = { ...surcharges, countries: countrySetting ? [countrySetting] : [] }
  const scoped = Array.isArray(countrySetting?.exemptChannelKeys)
  const surcharge = calculateFinanceQuoteTax(Array.isArray(countrySetting?.providers) ? { ...countrySurcharges, providers: countrySetting.providers } : scoped ? { ...countrySurcharges, providers: channelKey ? [{ provider, selected: true, channels: [], mode: countrySetting!.exemptChannelKeys!.includes(channelKey) ? 'exempt' : 'taxable' }] : [] } : countrySurcharges, country, provider, 0)
  const surchargeLabel = surcharge.feeMode === 'no-tax' ? '无附加费'
    : surcharge.feeMode === 'missing' ? '附加费渠道配置待确认'
      : surcharge.included ? '免附加费' : `附加费 $${surcharge.taxUsd.toFixed(2)}/单`
  return {
    ...tax,
    configured: tax.configured && surcharge.configured,
    totalUsd: roundQuoteUsd(sumDecimal(tax.totalUsd, surcharge.taxUsd)),
    surchargeConfigured: surcharge.configured,
    surchargeEnabled: surcharge.feeMode !== 'no-tax',
    surchargeExempt: surcharge.included,
    surchargeUsd: surcharge.taxUsd,
    surchargeLabel,
  }
}
