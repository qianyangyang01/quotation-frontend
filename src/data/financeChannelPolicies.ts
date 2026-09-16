import { countryIdentityMatches, sameCountryIdentity } from './countryIdentity'
import { logisticsAttributeOptions, normalizeLogisticsAttribute } from './logisticsAttributes'
import { australiaQuoteRegions, logisticsCountries, logisticsRules, normalizeAustraliaQuoteRegion, type LogisticsRelation, type LogisticsPriceRow } from './logistics'
import {
  defaultCountrySortOrder,
  defaultCountryStage,
  inferCountryContinent,
  type CountryContinent,
  type CountryStage,
} from './countryClassification'
import { readFinanceSetting, writeFinanceSetting } from '@/services/financeSettings'

export const financeLogisticsAttributeOptions = logisticsAttributeOptions
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
  quoteRegionSummary: string
}

export type FinanceUnavailableChannel = {
  legacyKey: string
  providerName: string
  channelName: string
  status: 'ambiguous' | 'unavailable'
  reason: string
  backupSha256: string
}

export type FinanceCountryChannelRule = {
  country: string
  allowedChannels: string[]
  unavailableChannels?: FinanceUnavailableChannel[]
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

export type CustomerGrade = 'S' | 'A' | 'B' | 'C' | 'D' | 'E' | 'NEW'
export function customerGradeLabel(grade: string) { return grade === 'NEW' ? '新客户' : `${grade}级客户` }
export type CustomerGradeSetting = { grade: CustomerGrade; coefficient: number; enabled: boolean }
export type FinanceExchangeRateSetting = { usdCny: number; eurUsd?: number; updatedAt: string }

export const FINANCE_COUNTRY_SETTINGS_UPDATED_EVENT = 'milano:finance-country-settings-updated'
const DEFAULT_USD_CNY_RATE = 6.75
export const COMMON_COUNTRY_LIMIT = 40

// Product categories and shipment attributes are separate business dimensions.
export const financeCategoryOptions = ['未分类', '保健品', '美妆', '化妆品', '服装', '日用品', '个护健康', '家居百货', '数码配件', '宠物用品', '袜子']

const defaultGradeSettings: CustomerGradeSetting[] = [
  { grade: 'S', coefficient: 1.12, enabled: true },
  { grade: 'A', coefficient: 1.15, enabled: true },
  { grade: 'B', coefficient: 1.18, enabled: true },
  { grade: 'C', coefficient: 1.21, enabled: true },
  { grade: 'D', coefficient: 1.25, enabled: true },
  { grade: 'E', coefficient: 1.30, enabled: true },
  { grade: 'NEW', coefficient: 1.30, enabled: false },
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
    const existingECoefficient = Number(configured.get('E')?.coefficient)
    const fallbackCoefficient = fallback.grade === 'NEW' && Number.isFinite(existingECoefficient) && existingECoefficient > 0
      ? existingECoefficient : fallback.coefficient
    return {
      grade: fallback.grade,
      coefficient: Number.isFinite(coefficient) && coefficient > 0 ? coefficient : fallbackCoefficient,
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

export function describeAustraliaQuoteRegions(prices: Array<Pick<LogisticsPriceRow, 'zoneName' | 'zoneExclude'>>) {
  const included = prices.filter(price => !price.zoneExclude)
  const hasUnzoned = included.some(price => !price.zoneName?.trim())
  const quoteRegions = [...new Set(included.flatMap(price => String(price.zoneName || '').split(/[/／、,，;；|]/)
    .map(normalizeAustraliaQuoteRegion).filter(Boolean)))].sort((a, b) => a.localeCompare(b, 'zh-CN'))
  const hasNumberedRegions = quoteRegions.some(region => (australiaQuoteRegions as readonly string[]).includes(region))
  const missingQuoteRegions = hasNumberedRegions && !hasUnzoned ? australiaQuoteRegions.filter(region => !quoteRegions.includes(region)) : []
  const quoteRegionSummary = quoteRegions.length
    ? `${hasNumberedRegions ? '已提供分区' : '原表分区'}：${quoteRegions.join('、')}${hasUnzoned ? '；另有未分区价格' : missingQuoteRegions.length ? `；未提供${missingQuoteRegions.join('、')}价格` : ''}`
    : hasUnzoned ? '原表未区分澳大利亚分区，按该渠道国家价格报价' : '原表仅有排除分区，请核对渠道说明'
  return { quoteRegions, missingQuoteRegions, quoteRegionSummary }
}

export function toggleFinanceChannelSelection(selected: string[], channelKeys: string[]) {
  const keys = new Set(channelKeys)
  if (!keys.size) return [...selected]
  const current = new Set(selected)
  return [...keys].every(key => current.has(key))
    ? selected.filter(key => !keys.has(key))
    : [...new Set([...selected, ...keys])]
}

export function channelsAvailableForCountry(country: string, attribute = '普货'): FinanceLogisticsChannelOption[] {
  void attribute
  const options = logisticsRules
    .filter(rule => rule.status === '启用' && rule.prices.some(price =>
      countryIdentityMatches(price.countryCode, price.areaName, country)))
    .flatMap(rule => rule.relations
      .filter(relation => relation.carrier && relation.channel)
      .map(relation => {
        const regionInfo = country === '澳大利亚' || country.toUpperCase() === 'AU'
          ? describeAustraliaQuoteRegions(rule.prices.filter(price => price.areaName === '澳大利亚' || price.countryCode.toUpperCase() === 'AU'))
          : { quoteRegions: [], missingQuoteRegions: [], quoteRegionSummary: '' }
        return {
          key: financeChannelKey(rule.id, relation), ruleId: rule.id, ruleName: rule.name, carrier: relation.carrier, channel: relation.channel, channelCode: relation.channelCode,
          ...regionInfo,
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

// New shipment attributes require an explicit finance policy; never inherit general cargo permissions.
const defaultPolicies: FinanceChannelPolicy[] = financeLogisticsAttributeOptions.filter(attribute => attribute !== '化妆品' && attribute !== '保健品' && attribute !== '服装').map(attribute => ({
  id: attribute,
  category: attribute,
  countryRules: [defaultCountryRule(attribute, '美国')],
  enabled: true,
  updatedAt: '2026-08-08 10:00',
}))

export function retainedFinanceCountryRule(policy: FinanceChannelPolicy | undefined, attribute: string, country: string) {
  return policy && normalizeLogisticsAttribute(policy.category) === normalizeLogisticsAttribute(attribute)
    ? policy.countryRules.find(rule => rule.country === country) : undefined
}

export function normalizePolicies(policies: FinanceChannelPolicy[]) {
  return policies.filter(policy => typeof policy.category === 'string' && policy.category.trim()).map(policy => {
    policy = { ...policy, category: normalizeLogisticsAttribute(policy.category) }
    const countryMeta = new Map(countriesAvailableForCategory(policy.category).map(country => [country.name, country]))
    return {
      ...policy,
      countryRules: policy.countryRules.filter(rule => rule.country.trim()).map(rule => {
        const stage = rule.stage === 'common' || rule.stage === 'standard' || rule.stage === 'rare'
          ? rule.stage
          : defaultCountryStage(rule.country)
        return {
          ...rule,
          stage,
          continent: countryMeta.has(rule.country) ? inferCountryContinent(countryMeta.get(rule.country)?.code) : rule.continent,
          sortOrder: Number.isFinite(Number(rule.sortOrder)) ? Number(rule.sortOrder) : defaultCountrySortOrder(rule.country, stage),
          // Stored authorization survives temporary logistics unavailability. Quotation eligibility is checked separately.
          allowedChannels: [...new Set(rule.allowedChannels)],
          unavailableChannels: [...new Map((Array.isArray(rule.unavailableChannels) ? rule.unavailableChannels : [])
            .filter(item => item && typeof item.legacyKey === 'string' && item.legacyKey.trim())
            .map(item => [item.legacyKey, {
              legacyKey: item.legacyKey.trim(),
              providerName: String(item.providerName || ''),
              channelName: String(item.channelName || ''),
              status: item.status === 'ambiguous' ? 'ambiguous' as const : 'unavailable' as const,
              reason: String(item.reason || ''),
              backupSha256: String(item.backupSha256 || ''),
            }])).values()],
        }
      }).sort((a, b) => a.sortOrder - b.sortOrder || a.country.localeCompare(b.country, 'zh-CN')),
    }
  })
}

export function loadFinanceChannelPolicies(): FinanceChannelPolicy[] {
  return normalizePolicies(readFinanceSetting<FinanceChannelPolicy[]>('channel-policies') || defaultPolicies)
}

export async function saveFinanceChannelPolicies(policies: FinanceChannelPolicy[]) {
  const normalized = normalizePolicies(policies)
  const saved = await writeFinanceSetting<FinanceChannelPolicy[]>('channel-policies', normalized)
  return normalizePolicies(saved)
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
  const eurUsd = Number(parsed?.eurUsd)
  const euro = Number.isFinite(eurUsd) && eurUsd > 0 ? { eurUsd } : {}
  if (Number.isFinite(usdCny) && usdCny > 0) return { usdCny, ...euro, updatedAt: parsed?.updatedAt || '财务维护' }
  return { usdCny: DEFAULT_USD_CNY_RATE, updatedAt: '系统默认' }
}

export async function saveFinanceExchangeRate(usdCny: number): Promise<FinanceExchangeRateSetting> {
  const setting = {
    ...loadFinanceExchangeRate(),
    usdCny: Math.max(0.0001, Number(usdCny) || DEFAULT_USD_CNY_RATE),
    updatedAt: new Date().toLocaleString('zh-CN', { hour12: false }),
  }
  await writeFinanceSetting('exchange-rate', setting)
  return setting
}

export async function saveFinanceEurUsdRate(eurUsd: number): Promise<FinanceExchangeRateSetting> {
  if (!Number.isFinite(eurUsd) || eurUsd <= 0) throw new Error('欧元兑美元汇率必须大于 0')
  const setting = { ...loadFinanceExchangeRate(), eurUsd, updatedAt: new Date().toLocaleString('zh-CN', { hour12: false }) }
  await writeFinanceSetting('exchange-rate', setting)
  return setting
}

export function customerGradeCoefficient(settings: CustomerGradeSetting[], grade: CustomerGrade) {
  return settings.find(setting => setting.grade === grade && setting.enabled)?.coefficient ?? 1
}

export function financeCountriesForCategory(policies: FinanceChannelPolicy[], attribute: string) {
  const matches = policies.filter(item => normalizeLogisticsAttribute(item.category) === normalizeLogisticsAttribute(attribute))
  const policy = matches.length === 1 && matches[0]?.enabled ? matches[0] : undefined
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
  const matches = policies.filter(item => normalizeLogisticsAttribute(item.category) === normalizeLogisticsAttribute(attribute))
  const policy = matches.length === 1 && matches[0]?.enabled ? matches[0] : undefined
  const countryRule = policy?.countryRules.find(rule => sameCountryIdentity(rule.country,country))
  const key = financeChannelKey(ruleId, relation)
  return (countryRule?.allowedChannels.includes(key) ?? false)
    && channelsAvailableForCountry(country, attribute).some(option => option.key === key)
}

/** One availability scan for a whole country's calculation, not one scan per channel. */
export function financeAllowedChannelKeys(policies: FinanceChannelPolicy[], attribute: string, country: string) {
  const normalizedAttribute = normalizeLogisticsAttribute(attribute)
  const matches = policies.filter(item => normalizeLogisticsAttribute(item.category) === normalizedAttribute)
  const policy = matches.length === 1 && matches[0]?.enabled ? matches[0] : undefined
  const rule = policy?.countryRules.find(item => sameCountryIdentity(item.country, country))
  if (!rule?.allowedChannels.length) return new Set<string>()
  const allowed = new Set(rule.allowedChannels)
  return new Set(channelsAvailableForCountry(country, attribute).map(option => option.key).filter(key => allowed.has(key)))
}
