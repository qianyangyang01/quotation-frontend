import { australiaQuoteRegions, logisticsCountries, logisticsRules, normalizeAustraliaQuoteRegion, type LogisticsRelation } from './logistics'
import {
  defaultCountrySortOrder,
  defaultCountryStage,
  inferCountryContinent,
  type CountryContinent,
  type CountryStage,
} from './countryClassification'
import { readFinanceSetting, writeFinanceSetting } from '@/services/financeSettings'

export const financeLogisticsAttributeOptions = ['普货', '带电', '纯电池', '液体', '粉末', '非液体化妆品', '带磁', '微敏感'] as const
export type FinanceLogisticsAttribute = string

export type FinanceLogisticsChannelOption = {
  key: string
  ruleId: number
  ruleName: string
  carrier: string
  channel: string
  channelCode: string
  quoteRegions: string[]
  missingQuoteRegions: string[]
}

export type FinanceCountryChannelRule = {
  country: string
  allowedChannels: string[]
  stage: CountryStage
  continent: CountryContinent
  sortOrder: number
}
export type FinanceCountrySetting = {
  country: string
  code: string
  stage: CountryStage
  continent: CountryContinent
  sortOrder: number
  enabled: boolean
}
export type FinanceChannelPolicy = {
  id: string
  category: FinanceLogisticsAttribute
  countryRules: FinanceCountryChannelRule[]
  enabled: boolean
  updatedAt: string
}

export type CustomerGrade = 'S' | 'A' | 'B' | 'C' | 'D' | 'E'
export type CustomerGradeSetting = { grade: CustomerGrade; coefficient: number; enabled: boolean }
export type FinanceExchangeRateSetting = { usdCny: number; updatedAt: string }

export const FINANCE_COUNTRY_SETTINGS_UPDATED_EVENT = 'milano:finance-country-settings-updated'
const DEFAULT_USD_CNY_RATE = 6.75
export const COMMON_COUNTRY_LIMIT = 40

// Kept for the purchase data category input. Finance logistics policies use the six attributes above.
export const financeCategoryOptions = ['未分类', '保健品', '美妆', '化妆品', '服装', '日用品', '个护健康', '家居百货', '数码配件', '宠物用品', '袜子']

const defaultGradeSettings: CustomerGradeSetting[] = [
  { grade: 'S', coefficient: 1.12, enabled: true },
  { grade: 'A', coefficient: 1.15, enabled: true },
  { grade: 'B', coefficient: 1.18, enabled: true },
  { grade: 'C', coefficient: 1.21, enabled: true },
  { grade: 'D', coefficient: 1.25, enabled: true },
  { grade: 'E', coefficient: 1.30, enabled: true },
]

export function normalizeCustomerGradeSettings(settings: Partial<CustomerGradeSetting>[] | undefined): CustomerGradeSetting[] {
  const configured = new Map<CustomerGrade, Partial<CustomerGradeSetting>>()
  if (Array.isArray(settings)) {
    settings.forEach(setting => {
      if (defaultGradeSettings.some(item => item.grade === setting.grade)) configured.set(setting.grade as CustomerGrade, setting)
    })
  }
  return defaultGradeSettings.map(fallback => {
    const setting = configured.get(fallback.grade)
    const coefficient = Number(setting?.coefficient)
    return {
      grade: fallback.grade,
      coefficient: Number.isFinite(coefficient) && coefficient >= 0 ? coefficient : fallback.coefficient,
      enabled: typeof setting?.enabled === 'boolean' ? setting.enabled : fallback.enabled,
    }
  })
}

export function countriesAvailableForCategory(attribute: string) {
  if (!attribute.trim()) return []
  const countries = logisticsCountries.filter(country => country.name !== '全球')
  return [...new Map(countries.map(country => [country.code || country.name, country])).values()]
}

function defaultFinanceCountrySettings(): FinanceCountrySetting[] {
  return countriesAvailableForCategory('普货').map(country => {
    const stage = defaultCountryStage(country.name)
    return {
      country: country.name,
      code: String(country.code || '').toUpperCase(),
      stage,
      continent: inferCountryContinent(country.code),
      sortOrder: defaultCountrySortOrder(country.name, stage),
      enabled: true,
    }
  })
}

export function normalizeFinanceCountrySettings(settings: Partial<FinanceCountrySetting>[]) {
  if (!logisticsCountries.length && settings.length) {
    return settings.filter(setting => setting.country).map(setting => {
      const stage = setting.stage === 'common' || setting.stage === 'standard' || setting.stage === 'rare' ? setting.stage : defaultCountryStage(String(setting.country))
      return {
        country: String(setting.country), code: String(setting.code || '').toUpperCase(), stage,
        continent: setting.continent || inferCountryContinent(setting.code),
        sortOrder: Number.isFinite(Number(setting.sortOrder)) ? Math.max(1, Number(setting.sortOrder)) : defaultCountrySortOrder(String(setting.country), stage),
        enabled: setting.enabled !== false,
      }
    }).sort((a, b) => a.sortOrder - b.sortOrder || a.country.localeCompare(b.country, 'zh-CN'))
  }
  const stored = new Map(settings.map(setting => [setting.country, setting]))
  const defaults = defaultFinanceCountrySettings()
  const normalized = defaults.map(fallback => {
    const setting = stored.get(fallback.country)
    const stage = setting?.stage === 'common' || setting?.stage === 'standard' || setting?.stage === 'rare' ? setting.stage : fallback.stage
    return {
      ...fallback,
      stage,
      continent: inferCountryContinent(fallback.code),
      sortOrder: Number.isFinite(Number(setting?.sortOrder)) ? Math.max(1, Number(setting?.sortOrder)) : defaultCountrySortOrder(fallback.country, stage),
      enabled: setting?.enabled !== false,
    }
  })
  const currentCountries = new Set(defaults.map(setting => setting.country))
  settings.filter(setting => setting.country && !currentCountries.has(String(setting.country))).forEach(setting => {
    const country = String(setting.country)
    const stage = setting.stage === 'common' || setting.stage === 'standard' || setting.stage === 'rare' ? setting.stage : defaultCountryStage(country)
    normalized.push({
      country,
      code: String(setting.code || '').toUpperCase(),
      stage,
      continent: setting.continent || inferCountryContinent(setting.code),
      sortOrder: Number.isFinite(Number(setting.sortOrder)) ? Math.max(1, Number(setting.sortOrder)) : defaultCountrySortOrder(country, stage),
      enabled: setting.enabled !== false,
    })
  })
  const common = normalized.filter(setting => setting.enabled && setting.stage === 'common').sort((a, b) => a.sortOrder - b.sortOrder)
  common.slice(COMMON_COUNTRY_LIMIT).forEach(setting => { setting.stage = 'standard'; setting.sortOrder = defaultCountrySortOrder(setting.country, 'standard') })
  return normalized.sort((a, b) => a.sortOrder - b.sortOrder || a.country.localeCompare(b.country, 'zh-CN'))
}

export function loadFinanceCountrySettings(): FinanceCountrySetting[] {
  return normalizeFinanceCountrySettings(readFinanceSetting<FinanceCountrySetting[]>('country-classification') || [])
}

export async function saveFinanceCountrySettings(settings: FinanceCountrySetting[]) {
  const normalized = normalizeFinanceCountrySettings(settings)
  await writeFinanceSetting('country-classification', normalized)
  if (typeof window !== 'undefined') window.dispatchEvent(new CustomEvent(FINANCE_COUNTRY_SETTINGS_UPDATED_EVENT))
  return normalized
}

export function financeChannelKey(ruleId: number, relation: Pick<LogisticsRelation, 'carrier' | 'channel' | 'channelCode'>) {
  return `${ruleId}::${relation.carrier}::${relation.channelCode || relation.channel}`
}

export function channelsAvailableForCountry(country: string, attribute = '普货'): FinanceLogisticsChannelOption[] {
  void attribute
  const options = logisticsRules
    .filter(rule => rule.status === '启用' && rule.prices.some(price =>
      price.areaName === country || price.countryCode.toLowerCase() === country.toLowerCase()))
    .flatMap(rule => rule.relations
      .filter(relation => relation.carrier && relation.channel)
      .map(relation => {
        const quoteRegions = country === '澳大利亚'
          ? [...new Set(rule.prices.filter(price => price.areaName === country && price.zoneName && !price.zoneExclude).map(price => normalizeAustraliaQuoteRegion(price.zoneName)))]
          : []
        return {
          key: financeChannelKey(rule.id, relation), ruleId: rule.id, ruleName: rule.name, carrier: relation.carrier, channel: relation.channel, channelCode: relation.channelCode,
          quoteRegions,
          missingQuoteRegions: country === '澳大利亚' ? australiaQuoteRegions.filter(region => !quoteRegions.includes(region)) : [],
        }
      }))
  return [...new Map(options.map(option => [option.key, option])).values()]
    .sort((a, b) => a.carrier.localeCompare(b.carrier, 'zh-CN') || a.channel.localeCompare(b.channel, 'zh-CN'))
}

function defaultCountryRule(attribute: string, country: string): FinanceCountryChannelRule {
  const countryMeta = countriesAvailableForCategory(attribute).find(item => item.name === country)
  const stage = defaultCountryStage(country)
  return {
    country,
    allowedChannels: channelsAvailableForCountry(country, attribute).map(option => option.key),
    stage,
    continent: inferCountryContinent(countryMeta?.code),
    sortOrder: defaultCountrySortOrder(country, stage),
  }
}

const defaultPolicies: FinanceChannelPolicy[] = financeLogisticsAttributeOptions.map(attribute => ({
  id: attribute,
  category: attribute,
  countryRules: [defaultCountryRule(attribute, '美国')],
  enabled: true,
  updatedAt: '2026-08-08 10:00',
}))

function normalizePolicies(policies: FinanceChannelPolicy[]) {
  return policies.filter(policy => typeof policy.category === 'string' && policy.category.trim()).map(policy => {
    const countryMeta = new Map(countriesAvailableForCategory(policy.category).map(country => [country.name, country]))
    return {
      ...policy,
      countryRules: policy.countryRules.filter(rule => countryMeta.has(rule.country)).map(rule => {
        const available = logisticsRules.length ? new Set(channelsAvailableForCountry(rule.country, policy.category).map(option => option.key)) : null
        const stage = rule.stage === 'common' || rule.stage === 'standard' || rule.stage === 'rare'
          ? rule.stage
          : defaultCountryStage(rule.country)
        return {
          ...rule,
          stage,
          continent: inferCountryContinent(countryMeta.get(rule.country)?.code),
          sortOrder: Number.isFinite(Number(rule.sortOrder)) ? Number(rule.sortOrder) : defaultCountrySortOrder(rule.country, stage),
          allowedChannels: available ? rule.allowedChannels.filter(channel => available.has(channel)) : [...rule.allowedChannels],
        }
      }).sort((a, b) => a.sortOrder - b.sortOrder || a.country.localeCompare(b.country, 'zh-CN')),
    }
  })
}

export function loadFinanceChannelPolicies(): FinanceChannelPolicy[] {
  return normalizePolicies(readFinanceSetting<FinanceChannelPolicy[]>('channel-policies') || defaultPolicies)
}

export async function saveFinanceChannelPolicies(policies: FinanceChannelPolicy[]) {
  await writeFinanceSetting('channel-policies', normalizePolicies(policies))
}

export function loadCustomerGradeSettings(): CustomerGradeSetting[] {
  return normalizeCustomerGradeSettings(readFinanceSetting<Partial<CustomerGradeSetting>[]>('customer-grades'))
}

export async function saveCustomerGradeSettings(settings: CustomerGradeSetting[]) {
  await writeFinanceSetting('customer-grades', settings)
}

export function loadFinanceExchangeRate(): FinanceExchangeRateSetting {
  const parsed = readFinanceSetting<Partial<FinanceExchangeRateSetting>>('exchange-rate')
  const usdCny = Number(parsed?.usdCny)
  if (Number.isFinite(usdCny) && usdCny > 0) return { usdCny, updatedAt: parsed?.updatedAt || '财务维护' }
  return { usdCny: DEFAULT_USD_CNY_RATE, updatedAt: '系统默认' }
}

export async function saveFinanceExchangeRate(usdCny: number): Promise<FinanceExchangeRateSetting> {
  const setting = {
    usdCny: Math.max(0.0001, Number(usdCny) || DEFAULT_USD_CNY_RATE),
    updatedAt: new Date().toLocaleString('zh-CN', { hour12: false }),
  }
  await writeFinanceSetting('exchange-rate', setting)
  return setting
}

export function customerGradeCoefficient(settings: CustomerGradeSetting[], grade: CustomerGrade) {
  return settings.find(setting => setting.grade === grade && setting.enabled)?.coefficient ?? 1
}

export function financeCountriesForCategory(policies: FinanceChannelPolicy[], attribute: string) {
  const policy = policies.find(item => item.enabled && item.category === attribute)
  const available = new Set(countriesAvailableForCategory(attribute).map(country => country.name))
  return policy?.countryRules.filter(rule => rule.allowedChannels.length && available.has(rule.country)).map(rule => rule.country) ?? []
}

export function financeCountryOptionsForCategory(policies: FinanceChannelPolicy[], attribute: string, settings = loadFinanceCountrySettings()) {
  void policies
  const available = new Set(countriesAvailableForCategory(attribute).map(country => country.name))
  return settings
    .filter(setting => setting.enabled && available.has(setting.country))
    .map(setting => ({
      name: setting.country,
      stage: setting.stage,
      continent: setting.continent,
      sortOrder: setting.sortOrder,
    }))
    .sort((a, b) => a.sortOrder - b.sortOrder || a.name.localeCompare(b.name, 'zh-CN'))
}

export function financeAllowsLogisticsChannel(
  policies: FinanceChannelPolicy[], attribute: string, country: string, ruleId: number,
  relation: Pick<LogisticsRelation, 'carrier' | 'channel' | 'channelCode'>,
) {
  const policy = policies.find(item => item.enabled && item.category === attribute)
  const countryRule = policy?.countryRules.find(rule => rule.country === country)
  const key = financeChannelKey(ruleId, relation)
  return (countryRule?.allowedChannels.includes(key) ?? false)
    && channelsAvailableForCountry(country, attribute).some(option => option.key === key)
}
