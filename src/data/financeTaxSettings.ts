import { legacyLogisticsProviderNames, logisticsChannels, logisticsCountries } from './logistics'
import { readFinanceSetting, writeFinanceSetting } from '@/services/financeSettings'

export type TaxCustomerType = 'A' | 'B'
export type LogisticsTaxMode = 'exempt' | 'taxable'

export type FinanceCountryTaxSetting = {
  country: string
  aFixedFeeUsd: number
  bPerItemFeeUsd: number
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
  perItemFeeUsd: number
  customerType: TaxCustomerType
  quantity: number
  feeMode: 'exempt' | 'fixed-order' | 'per-item' | 'missing'
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
    .map((item, index) => ({ country: item.name, aFixedFeeUsd: 0, bPerItemFeeUsd: 0, selected: false, enabled: false, sortOrder: (index + 1) * 10 }))
    .sort((a, b) => a.country.localeCompare(b.country, 'zh-CN'))
}

function finiteNonNegative(value: unknown) {
  const number = Number(value)
  return Number.isFinite(number) && number >= 0 ? number : 0
}

function normalizeSettings(raw?: Partial<FinanceTaxSettings> | null): FinanceTaxSettings {
  const countryMap = new Map((raw?.countries || []).map(item => [item.country, item]))
  const providerMap = new Map((raw?.providers || []).map(item => [item.provider, item]))
  return {
    countries: countryDefaults().map(fallback => {
      const stored = countryMap.get(fallback.country)
      const legacy = stored as (typeof stored & { fixedFeeUsd?: unknown })
      const aFixedFeeUsd = finiteNonNegative(stored?.aFixedFeeUsd ?? legacy?.fixedFeeUsd)
      const bPerItemFeeUsd = finiteNonNegative(stored?.bPerItemFeeUsd)
      return {
        country: fallback.country,
        aFixedFeeUsd,
        bPerItemFeeUsd,
        selected: typeof stored?.selected === 'boolean' ? stored.selected : Boolean(stored && (stored.enabled === true || aFixedFeeUsd > 0 || bPerItemFeeUsd > 0)),
        enabled: stored?.enabled === true || aFixedFeeUsd > 0 || bPerItemFeeUsd > 0,
        sortOrder: Number.isFinite(Number(stored?.sortOrder)) ? Math.max(1, Number(stored?.sortOrder)) : fallback.sortOrder,
      }
    }).sort((a, b) => a.sortOrder - b.sortOrder || a.country.localeCompare(b.country, 'zh-CN')),
    providers: providerDefaults().map(fallback => {
      const stored = providerMap.get(fallback.provider)
      const storedMode = String(stored?.mode || '')
      const mode: LogisticsTaxMode = storedMode === 'exempt' || storedMode === 'included'
        ? 'exempt'
        : storedMode === 'taxable' || storedMode === 'not-included' || storedMode === 'fixed-rate' || storedMode === 'channel-rate'
          ? 'taxable'
          : fallback.mode
      return { ...fallback, mode, selected: typeof stored?.selected === 'boolean' ? stored.selected : fallback.selected }
    }),
    updatedAt: String(raw?.updatedAt || '尚未保存'),
  }
}

export function loadFinanceTaxSettings(): FinanceTaxSettings {
  return normalizeSettings(readFinanceSetting<FinanceTaxSettings>('tax-settings'))
}

export async function saveFinanceTaxSettings(settings: FinanceTaxSettings): Promise<FinanceTaxSettings> {
  const normalized = normalizeSettings({ ...settings, updatedAt: new Date().toLocaleString('zh-CN', { hour12: false }) })
  await writeFinanceSetting('tax-settings', normalized)
  if (typeof window !== 'undefined') window.dispatchEvent(new CustomEvent(FINANCE_TAX_SETTINGS_UPDATED_EVENT))
  return normalized
}

export function calculateFinanceQuoteTax(
  settings: FinanceTaxSettings,
  country: string,
  provider: string,
  baseQuoteUsd: number,
  customerType: TaxCustomerType = 'A',
  quantity = 1,
): FinanceQuoteTaxResult {
  const normalizedBase = Number.isFinite(Number(baseQuoteUsd)) ? Math.max(0, Number(baseQuoteUsd)) : 0
  const normalizedQuantity = Math.max(1, Math.floor(Number(quantity) || 1))
  const providerSetting = settings.providers.find(item => item.selected && item.provider.trim() === provider.trim())
  if (!providerSetting) {
    return { included: false, configured: false, ratePercent: null, fixedFeeUsd: 0, perItemFeeUsd: 0, customerType, quantity: normalizedQuantity, feeMode: 'missing', taxUsd: 0, totalUsd: normalizedBase, label: '物流商税务属性待设置' }
  }
  if (providerSetting.mode === 'exempt') {
    return { included: true, configured: true, ratePercent: null, fixedFeeUsd: 0, perItemFeeUsd: 0, customerType, quantity: normalizedQuantity, feeMode: 'exempt', taxUsd: 0, totalUsd: normalizedBase, label: '免税' }
  }

  const countrySetting = settings.countries.find(item => item.selected && item.country === country)
  if (!countrySetting?.enabled) {
    return { included: false, configured: false, ratePercent: null, fixedFeeUsd: 0, perItemFeeUsd: 0, customerType, quantity: normalizedQuantity, feeMode: 'missing', taxUsd: 0, totalUsd: normalizedBase, label: `${country || '当前国家'}客户税费待设置` }
  }

  const fixedFeeUsd = finiteNonNegative(countrySetting.aFixedFeeUsd)
  const perItemFeeUsd = finiteNonNegative(countrySetting.bPerItemFeeUsd)
  const taxUsd = Number((customerType === 'A' ? fixedFeeUsd : perItemFeeUsd * normalizedQuantity).toFixed(2))
  const totalUsd = Number((normalizedBase + taxUsd).toFixed(2))
  return {
    included: false,
    configured: true,
    ratePercent: null,
    fixedFeeUsd,
    perItemFeeUsd,
    customerType,
    quantity: normalizedQuantity,
    feeMode: customerType === 'A' ? 'fixed-order' : 'per-item',
    taxUsd,
    totalUsd,
    label: customerType === 'A'
      ? `不免税 · A类固定 $${fixedFeeUsd.toFixed(2)}/单`
      : `不免税 · B类 $${perItemFeeUsd.toFixed(2)}/件 × ${normalizedQuantity}`,
  }
}
