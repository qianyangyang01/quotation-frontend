import { normalizeFinanceTaxSettings, calculateFinanceQuoteTax, type FinanceTaxSettings } from './financeTaxSettings'
import { readFinanceSetting, writeFinanceSetting } from '@/services/financeSettings'

export type FinanceSurchargeSettings = FinanceTaxSettings
export const FINANCE_SURCHARGE_SETTINGS_UPDATED_EVENT = 'milano:finance-surcharge-settings-updated'

export function normalizeFinanceSurchargeSettings(raw?: Partial<FinanceSurchargeSettings> | null): FinanceSurchargeSettings {
  const normalized = normalizeFinanceTaxSettings(raw)
  normalized.providers = normalized.providers.map(provider => {
    const stored = raw?.providers?.find(row => row.provider === provider.provider)
    // Surcharge exemptions are independent of the existing tax defaults.
    return { ...provider, selected: stored?.selected === true, mode: stored?.mode === 'exempt' ? 'exempt' : 'taxable' }
  })
  return normalized
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

export function calculateFinanceQuoteFees(taxes: FinanceTaxSettings, surcharges: FinanceSurchargeSettings, country: string, provider: string, baseUsd: number) {
  const tax = calculateFinanceQuoteTax(taxes, country, provider, baseUsd)
  const surcharge = calculateFinanceQuoteTax(surcharges, country, provider, 0)
  const surchargeLabel = surcharge.feeMode === 'no-tax' ? '无附加费'
    : surcharge.feeMode === 'missing' ? '物流商附加费属性待设置'
      : surcharge.included ? '免附加费' : `附加费 $${surcharge.taxUsd.toFixed(2)}/单`
  return {
    ...tax,
    configured: tax.configured && surcharge.configured,
    totalUsd: Number((tax.totalUsd + surcharge.taxUsd).toFixed(2)),
    surchargeConfigured: surcharge.configured,
    surchargeEnabled: surcharge.feeMode !== 'no-tax',
    surchargeExempt: surcharge.included,
    surchargeUsd: surcharge.taxUsd,
    surchargeLabel,
  }
}
