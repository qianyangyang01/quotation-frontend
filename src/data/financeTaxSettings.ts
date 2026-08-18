import { logisticsChannels, logisticsCountries } from './logistics'

export type LogisticsTaxMode = 'included' | 'not-included'

export type FinanceCountryTaxSetting = {
  country: string
  fixedFeeUsd: number
  enabled: boolean
  sortOrder: number
}

export type FinanceProviderChannelTax = {
  key: string
  channel: string
  ruleName: string
  ratePercent: number | null
}

export type FinanceProviderTaxSetting = {
  provider: string
  mode: LogisticsTaxMode
  fixedRatePercent: number | null
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
  ratePercent: number | null
  fixedFeeUsd: number
  taxUsd: number
  totalUsd: number
  label: string
}

const STORAGE_KEY = 'milano.finance-tax-settings.v1'
export const FINANCE_TAX_SETTINGS_UPDATED_EVENT = 'milano:finance-tax-settings-updated'

function normalizeProviderName(value: string) {
  return String(value || '').trim()
}

function defaultMode(provider: string): LogisticsTaxMode {
  if (/燕文|顺丰|递四方|4PX/i.test(provider)) return 'not-included'
  return 'included'
}

function defaultProviderRate(provider: string) {
  return /顺丰|递四方|4PX/i.test(provider) ? 27.5 : null
}

function providerDefaults(): FinanceProviderTaxSetting[] {
  const groups = new Map<string, FinanceProviderChannelTax[]>()
  logisticsChannels.forEach(item => {
    const provider = normalizeProviderName(item.carrier)
    if (!provider || !item.channel) return
    const rows = groups.get(provider) || []
    const key = `${item.ruleId}::${provider}::${item.channelCode || item.channel}`
    if (!rows.some(row => row.key === key)) rows.push({
      key,
      channel: item.channel,
      ruleName: item.ruleName,
      ratePercent: null,
    })
    groups.set(provider, rows)
  })
  return [...groups.entries()].map(([provider, channels]) => {
    const mode = defaultMode(provider)
    return {
      provider,
      mode,
      fixedRatePercent: mode === 'not-included' ? defaultProviderRate(provider) : null,
      channels: channels.sort((a, b) => a.channel.localeCompare(b.channel, 'zh-CN')),
    }
  }).sort((a, b) => a.provider.localeCompare(b.provider, 'zh-CN'))
}

function countryDefaults(): FinanceCountryTaxSetting[] {
  return logisticsCountries
    .filter(item => item.name && item.name !== '全球')
    .map((item, index) => ({ country: item.name, fixedFeeUsd: 0, enabled: false, sortOrder: (index + 1) * 10 }))
    .sort((a, b) => a.country.localeCompare(b.country, 'zh-CN'))
}

function finiteNonNegative(value: unknown) {
  const number = Number(value)
  return Number.isFinite(number) && number >= 0 ? number : 0
}

function optionalNonNegative(value: unknown) {
  if (value == null || value === '') return null
  const number = Number(value)
  return Number.isFinite(number) && number >= 0 ? number : null
}

function normalizeSettings(raw?: Partial<FinanceTaxSettings> | null): FinanceTaxSettings {
  const countryMap = new Map((raw?.countries || []).map(item => [item.country, item]))
  const providerMap = new Map((raw?.providers || []).map(item => [item.provider, item]))
  return {
    countries: countryDefaults().map(fallback => {
      const stored = countryMap.get(fallback.country)
      return {
        country: fallback.country,
        fixedFeeUsd: finiteNonNegative(stored?.fixedFeeUsd),
        enabled: stored?.enabled === true,
        sortOrder: Number.isFinite(Number(stored?.sortOrder)) ? Math.max(1, Number(stored?.sortOrder)) : fallback.sortOrder,
      }
    }).sort((a, b) => a.sortOrder - b.sortOrder || a.country.localeCompare(b.country, 'zh-CN')),
    providers: providerDefaults().map(fallback => {
      const stored = providerMap.get(fallback.provider)
      const storedMode = String(stored?.mode || '')
      const mode: LogisticsTaxMode = storedMode === 'included'
        ? 'included'
        : storedMode === 'not-included' || storedMode === 'fixed-rate' || storedMode === 'channel-rate'
          ? 'not-included'
          : fallback.mode
      const channelMap = new Map((stored?.channels || []).map(item => [item.key, item]))
      const legacyChannelRates = (stored?.channels || [])
        .map(item => optionalNonNegative(item.ratePercent))
        .filter((rate): rate is number => rate != null)
      const sameLegacyChannelRate = legacyChannelRates.length > 0 && legacyChannelRates.every(rate => rate === legacyChannelRates[0])
      const migratedRate = optionalNonNegative(stored?.fixedRatePercent)
        ?? (sameLegacyChannelRate ? legacyChannelRates[0] : null)
        ?? fallback.fixedRatePercent
      return {
        provider: fallback.provider,
        mode,
        fixedRatePercent: mode === 'not-included' ? migratedRate : null,
        channels: fallback.channels.map(channel => {
          const storedChannel = channelMap.get(channel.key)
          const rate = storedChannel?.ratePercent
          return { ...channel, ratePercent: rate == null ? null : finiteNonNegative(rate) }
        }),
      }
    }),
    updatedAt: String(raw?.updatedAt || '尚未保存'),
  }
}

export function loadFinanceTaxSettings(): FinanceTaxSettings {
  if (typeof window === 'undefined') return normalizeSettings()
  try {
    const stored = window.localStorage.getItem(STORAGE_KEY)
    if (stored) return normalizeSettings(JSON.parse(stored) as FinanceTaxSettings)
  } catch {
    // Invalid local test data falls back to live logistics provider/channel defaults.
  }
  return normalizeSettings()
}

export function saveFinanceTaxSettings(settings: FinanceTaxSettings): FinanceTaxSettings {
  const normalized = normalizeSettings({ ...settings, updatedAt: new Date().toLocaleString('zh-CN', { hour12: false }) })
  if (typeof window !== 'undefined') {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(normalized))
    window.dispatchEvent(new CustomEvent(FINANCE_TAX_SETTINGS_UPDATED_EVENT))
  }
  return normalized
}

export function financeTaxProviderIsComplete(setting: FinanceProviderTaxSetting) {
  if (setting.mode === 'included') return true
  return setting.fixedRatePercent != null && setting.fixedRatePercent >= 0
}

export function calculateFinanceQuoteTax(
  settings: FinanceTaxSettings,
  country: string,
  provider: string,
  baseQuoteUsd: number,
): FinanceQuoteTaxResult {
  const normalizedBase = Number.isFinite(Number(baseQuoteUsd)) ? Math.max(0, Number(baseQuoteUsd)) : 0
  const providerSetting = settings.providers.find(item => item.provider.trim() === provider.trim())
  if (!providerSetting || providerSetting.mode === 'included') {
    return { included: true, configured: true, ratePercent: null, fixedFeeUsd: 0, taxUsd: 0, totalUsd: normalizedBase, label: '包税' }
  }

  const countrySetting = settings.countries.find(item => item.country === country)
  const fixedFeeUsd = countrySetting?.enabled ? finiteNonNegative(countrySetting.fixedFeeUsd) : 0
  const ratePercent = optionalNonNegative(providerSetting.fixedRatePercent)
  if (ratePercent == null) {
    return {
      included: false,
      configured: false,
      ratePercent: null,
      fixedFeeUsd,
      taxUsd: 0,
      totalUsd: normalizedBase,
      label: fixedFeeUsd > 0 ? `不包税 · 税率待设置 · 固定税 $${fixedFeeUsd.toFixed(2)}` : '不包税 · 税率待设置',
    }
  }

  const taxUsd = Math.round((normalizedBase * ratePercent / 100 + fixedFeeUsd) * 100) / 100
  const totalUsd = Math.round((normalizedBase + taxUsd) * 100) / 100
  return {
    included: false,
    configured: true,
    ratePercent,
    fixedFeeUsd,
    taxUsd,
    totalUsd,
    label: fixedFeeUsd > 0
      ? `不包税 · 税率 ${ratePercent}% · 固定税 $${fixedFeeUsd.toFixed(2)}`
      : `不包税 · 税率 ${ratePercent}%`,
  }
}
