import { legacyLogisticsProviderNames, logisticsChannels, logisticsCountries } from './logistics'
import { readFinanceSetting, writeFinanceSetting } from '@/services/financeSettings'

export type LogisticsTaxMode = 'exempt' | 'taxable'

export type FinanceCountryTaxSetting = {
  country: string
  fixedFeeUsd: number
  selected: boolean
  enabled: boolean
  sortOrder: number
}

export type FinanceProviderChannelTax = {
  key: string
  channel: string
  ruleName: string
}

export type FinanceProviderTaxSetting = {
  provider: string
  mode: LogisticsTaxMode
  selected: boolean
  channels: FinanceProviderChannelTax[]
}

export type FinanceTaxSettings = {
  countries: FinanceCountryTaxSetting[]
  providers: FinanceProviderTaxSetting[]
  updatedAt: string
}

export type FinanceQuoteTaxResult = {
  included: boolean
  configured: boolean
  ratePercent: null
  fixedFeeUsd: number
  feeMode: 'no-tax' | 'exempt' | 'fixed-order' | 'missing'
  taxUsd: number
  totalUsd: number
  label: string
}

export const FINANCE_TAX_SETTINGS_UPDATED_EVENT = 'milano:finance-tax-settings-updated'

function normalizeProviderName(value: string) {
  return String(value || '').trim()
}

function defaultMode(provider: string): LogisticsTaxMode {
  return /燕文|顺丰|递四方|4PX/i.test(provider) ? 'taxable' : 'exempt'
}

function providerDefaults(): FinanceProviderTaxSetting[] {
  const groups = new Map<string, FinanceProviderChannelTax[]>()
  logisticsChannels.forEach(item => {
    const provider = normalizeProviderName(item.carrier)
    if (!provider || !item.channel) return
    const rows = groups.get(provider) || []
    const key = `${item.ruleId}::${provider}::${item.channelCode || item.channel}`
    if (!rows.some(row => row.key === key)) rows.push({ key, channel: item.channel, ruleName: item.ruleName })
    groups.set(provider, rows)
  })
  return [...groups.entries()].map(([provider, channels]) => ({
    provider,
    mode: defaultMode(provider),
    selected: legacyLogisticsProviderNames.includes(provider),
    channels: channels.sort((a, b) => a.channel.localeCompare(b.channel, 'zh-CN')),
  })).sort((a, b) => a.provider.localeCompare(b.provider, 'zh-CN'))
}

function countryDefaults(): FinanceCountryTaxSetting[] {
  return logisticsCountries
    .filter(item => item.name && item.name !== '全球')
    .map((item, index) => ({ country: item.name, fixedFeeUsd: 0, selected: false, enabled: false, sortOrder: (index + 1) * 10 }))
    .sort((a, b) => a.country.localeCompare(b.country, 'zh-CN'))
}

function finiteNonNegative(value: unknown) {
  const number = Number(value)
  return Number.isFinite(number) && number >= 0 ? number : 0
}

export function normalizeFinanceTaxSettings(raw?: Partial<FinanceTaxSettings> | null): FinanceTaxSettings {
  const countryMap = new Map((raw?.countries || []).map(item => [item.country, item]))
  const providerMap = new Map((raw?.providers || []).map(item => [item.provider, item]))
  const countryFallbacks = countryDefaults()
  const providerFallbacks = providerDefaults()
  const countries = [...new Set([...countryFallbacks.map(item => item.country), ...countryMap.keys()])]
  const providers = [...new Set([...providerFallbacks.map(item => item.provider), ...providerMap.keys()])]
  return {
    countries: countries.map(country => {
      const fallback = countryFallbacks.find(item => item.country === country)
        || { country, fixedFeeUsd: 0, selected: false, enabled: false, sortOrder: 10_000 }
      const stored = countryMap.get(fallback.country)
      const legacy = stored as (typeof stored & { aFixedFeeUsd?: unknown })
      const fixedFeeUsd = finiteNonNegative(stored?.fixedFeeUsd ?? legacy?.aFixedFeeUsd)
      return {
        country: fallback.country,
        fixedFeeUsd,
        selected: typeof stored?.selected === 'boolean' ? stored.selected : Boolean(stored && (stored.enabled === true || fixedFeeUsd > 0)),
        enabled: stored?.enabled !== false && fixedFeeUsd > 0,
        sortOrder: Number.isFinite(Number(stored?.sortOrder)) ? Math.max(1, Number(stored?.sortOrder)) : fallback.sortOrder,
      }
    }).sort((a, b) => a.sortOrder - b.sortOrder || a.country.localeCompare(b.country, 'zh-CN')),
    providers: providers.map(provider => {
      const stored = providerMap.get(provider)
      const fallback = providerFallbacks.find(item => item.provider === provider)
        || { provider, mode: defaultMode(provider), selected: false, channels: stored?.channels || [] }
      const storedMode = String(stored?.mode || '')
      const mode: LogisticsTaxMode = storedMode === 'exempt' || storedMode === 'included'
        ? 'exempt'
        : storedMode === 'taxable' || storedMode === 'not-included' || storedMode === 'fixed-rate' || storedMode === 'channel-rate'
          ? 'taxable'
          : fallback.mode
      return { ...fallback, channels: stored?.channels || fallback.channels, mode, selected: typeof stored?.selected === 'boolean' ? stored.selected : fallback.selected }
    }).sort((a, b) => a.provider.localeCompare(b.provider, 'zh-CN')),
    updatedAt: String(raw?.updatedAt || '尚未保存'),
  }
}

export function loadFinanceTaxSettings(): FinanceTaxSettings {
  return normalizeFinanceTaxSettings(readFinanceSetting<FinanceTaxSettings>('tax-settings'))
}

export async function saveFinanceTaxSettings(settings: FinanceTaxSettings): Promise<FinanceTaxSettings> {
  const normalized = normalizeFinanceTaxSettings({ ...settings, updatedAt: new Date().toLocaleString('zh-CN', { hour12: false }) })
  await writeFinanceSetting('tax-settings', normalized)
  if (typeof window !== 'undefined') window.dispatchEvent(new CustomEvent(FINANCE_TAX_SETTINGS_UPDATED_EVENT))
  return normalized
}

export function calculateFinanceQuoteTax(
  settings: FinanceTaxSettings,
  country: string,
  provider: string,
  baseQuoteUsd: number,
): FinanceQuoteTaxResult {
  const normalizedBase = Number.isFinite(Number(baseQuoteUsd)) ? Math.max(0, Number(baseQuoteUsd)) : 0
  const countrySetting = settings.countries.find(item => item.selected && item.country === country)
  if (!countrySetting?.enabled || finiteNonNegative(countrySetting.fixedFeeUsd) === 0) {
    return { included: false, configured: true, ratePercent: null, fixedFeeUsd: 0, feeMode: 'no-tax', taxUsd: 0, totalUsd: normalizedBase, label: '无关税' }
  }
  const providerSetting = settings.providers.find(item => item.selected && item.provider.trim() === provider.trim())
  if (!providerSetting) {
    return { included: false, configured: false, ratePercent: null, fixedFeeUsd: 0, feeMode: 'missing', taxUsd: 0, totalUsd: normalizedBase, label: '物流商税务属性待设置' }
  }
  if (providerSetting.mode === 'exempt') {
    return { included: true, configured: true, ratePercent: null, fixedFeeUsd: 0, feeMode: 'exempt', taxUsd: 0, totalUsd: normalizedBase, label: '免税' }
  }

  const fixedFeeUsd = finiteNonNegative(countrySetting.fixedFeeUsd)
  const taxUsd = Number(fixedFeeUsd.toFixed(2))
  const totalUsd = Number((normalizedBase + taxUsd).toFixed(2))
  return {
    included: false,
    configured: true,
    ratePercent: null,
    fixedFeeUsd,
    feeMode: 'fixed-order',
    taxUsd,
    totalUsd,
    label: `关税 $${fixedFeeUsd.toFixed(2)}/单`,
  }
}
