import { legacyLogisticsProviderNames, logisticsChannels, logisticsCountries } from './logistics'
import { readFinanceSetting, writeFinanceSetting } from '@/services/financeSettings'

export type LogisticsTaxMode = 'exempt' | 'taxable'

export type FinanceCountryTaxSetting = {
  country: string
  fixedFeeUsd: number
  selected: boolean
  enabled: boolean
  sortOrder: number
  /** Explicitly configured in the combined editor; allows an intentional zero/disabled duty. */
  taxConfigured?: boolean
  surchargeFeeUsd?: number
  surchargeEnabled?: boolean
}

export type FinanceChannelFeeSetting = {
  country: string
  channelKey: string
  taxMode?: LogisticsTaxMode
  surchargeMode?: LogisticsTaxMode
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
  channelFees?: FinanceChannelFeeSetting[]
}

export type FinanceQuoteTaxResult = {
  included: boolean
  configured: boolean
  ratePercent: null
  fixedFeeUsd: number
  feeMode: 'exempt' | 'fixed-order' | 'missing'
  taxUsd: number
  totalUsd: number
  label: string
  surchargeUsd: number
  surchargeExempt: boolean
  surchargeEnabled: boolean
  countrySurchargeUsd: number
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
        enabled: stored?.taxConfigured === true ? stored.enabled === true : fixedFeeUsd > 0,
        ...(stored?.taxConfigured === true ? { taxConfigured: true } : {}),
        ...(stored?.surchargeFeeUsd !== undefined || stored?.surchargeEnabled !== undefined
          ? { surchargeFeeUsd: finiteNonNegative(stored.surchargeFeeUsd), surchargeEnabled: stored.surchargeEnabled === true } : {}),
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
    channelFees: (raw?.channelFees || []).filter(item => item.country && item.channelKey).map(item => ({
      country: item.country, channelKey: item.channelKey,
      ...(item.taxMode === 'exempt' || item.taxMode === 'taxable' ? { taxMode: item.taxMode } : {}),
      ...(item.surchargeMode === 'exempt' || item.surchargeMode === 'taxable' ? { surchargeMode: item.surchargeMode } : {}),
    })),
  }
}

export function loadFinanceTaxSettings(): FinanceTaxSettings {
  return normalizeFinanceTaxSettings(readFinanceSetting<FinanceTaxSettings>('tax-settings'))
}

export async function saveFinanceTaxSettings(settings: FinanceTaxSettings): Promise<FinanceTaxSettings> {
  for (const country of settings.countries.filter(item => item.selected)) {
    for (const amount of [country.fixedFeeUsd, country.surchargeFeeUsd ?? 0]) {
      if (typeof amount !== 'number' || !Number.isFinite(amount) || amount < 0 || amount > 1_000_000)
        throw new Error('税费金额须为 0 至 1,000,000 之间的有效数字')
    }
  }
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
  channelKey = '',
): FinanceQuoteTaxResult {
  const normalizedBase = Number.isFinite(Number(baseQuoteUsd)) ? Math.max(0, Number(baseQuoteUsd)) : 0
  const providerSetting = settings.providers.find(item => item.selected && item.provider.trim() === provider.trim())
  const countrySetting = settings.countries.find(item => item.selected && item.country === country)
  const override = channelKey ? settings.channelFees?.find(item => item.country === country && item.channelKey === channelKey) : undefined
  const mode = override?.taxMode ?? providerSetting?.mode
  const included = mode === 'exempt'
  const configured = included || Boolean(mode && (countrySetting?.enabled || countrySetting?.taxConfigured))
  const fixedFeeUsd = included || !countrySetting?.enabled ? 0 : finiteNonNegative(countrySetting.fixedFeeUsd)
  const taxUsd = configured ? Number(fixedFeeUsd.toFixed(2)) : 0
  const surchargeEnabled = countrySetting?.surchargeEnabled === true
  const surchargeExempt = override?.surchargeMode === 'exempt'
  const countrySurchargeUsd = finiteNonNegative(countrySetting?.surchargeFeeUsd)
  const surchargeUsd = surchargeEnabled && !surchargeExempt ? Number(countrySurchargeUsd.toFixed(2)) : 0
  const totalUsd = Number((normalizedBase + taxUsd + surchargeUsd).toFixed(2))
  return {
    included,
    configured,
    ratePercent: null,
    fixedFeeUsd,
    feeMode: !configured ? 'missing' : included ? 'exempt' : 'fixed-order',
    taxUsd,
    totalUsd,
    label: !configured ? (!mode ? '渠道关税属性待设置' : `${country || '当前国家'}关税待设置`) : included ? '免税' : `关税 $${fixedFeeUsd.toFixed(2)}/单`,
    surchargeUsd, surchargeExempt, surchargeEnabled, countrySurchargeUsd,
  }
}
