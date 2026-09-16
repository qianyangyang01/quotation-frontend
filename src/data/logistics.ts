import { countryIdentity, countryIdentityMatches } from './countryIdentity'
import { decimal } from '@/services/quotationDecimal'
import { normalizeLogisticsAttribute } from './logisticsAttributes'
export interface LogisticsPriceRow {
  areaName: string; countryCode: string; etaMinDays: number; etaMaxDays: number; etaStatus?: string
  prohibitedMarks: string; allowedMarks: string; maxPerimeterCm: number; maxSideCm: number
  volumeDivisor: number; weightFromKg: number; weightToKg: number; startWeightKg: number
  pricePerKg: number; minChargeWeightKg: number; firstWeightKg: number; firstWeightPrice: number
  nextWeightKg: number; nextWeightPrice: number; intervalPrice: number; registrationFee: number
  surcharge: number; fuelSurchargeRate: number; prohibitGeneralCargo: boolean; volumetric: boolean
  phoneRequired: boolean; zoneName: string; zoneExclude: boolean
  pricingModel?: string; weightFromInclusive?: boolean; weightToInclusive?: boolean; quoteReady?: boolean
}
export interface LogisticsRelation { carrier: string; channel: string; channelCode: string; discounts: string }
export function formatLogisticsEta(row: { etaMinDays?: number; etaMaxDays?: number; etaStatus?: string }) {
  const min = Number(row.etaMinDays), max = Number(row.etaMaxDays)
  if ((row.etaStatus && row.etaStatus !== 'ready') || !Number.isFinite(min) || !Number.isFinite(max) || min <= 0 || max < min) return '该物流暂无时效说明'
  return `${min}～${max} 天`
}
export interface LogisticsRule {
  logisticsChannelId?: string; logisticsVersionId?: string; billingVerified?: boolean
  id: number; name: string; englishName: string; type: string; currency: string; published: string
  status: string; dates: string; users: string; relations: LogisticsRelation[]; phoneRequired: boolean
  areaCount: number; priceRowCount: number; prices: LogisticsPriceRow[]
}

export interface ShipmentDimensions {
  lengthCm: number
  widthCm: number
  heightCm: number
  /** 同规格商品合并为一个包裹时，体积按件数累计。 */
  volumeMultiplier?: number
  /** 报价人员本次指定的体积重除数；存在时优先于渠道默认值。 */
  volumeDivisor?: number
  defaultVolumeDivisor?: number
}

// 生产运行时只接受后端已发布物流版本。旧速猫JSON仍作为迁移素材保留在仓库，
// 不再打入业务页面首屏，也不会在接口加载前短暂参与报价。
export const logisticsRules: LogisticsRule[] = []
export const legacyLogisticsProviderNames: string[] = []
export const logisticsCarriers: string[] = []
export const logisticsChannels: Array<LogisticsRelation & { ruleId: number; ruleName: string }> = []
export const logisticsCountries: Array<{ code: string; name: string }> = []
let publishedCountryCatalog: Array<{ code: string; name: string }> = []
let indexedRules = new WeakMap<LogisticsRule, Map<string, LogisticsPriceRow[]>>()
let eligibleRows = new WeakMap<LogisticsRule, Map<string, { rows: LogisticsPriceRow[]; zoneRequired: boolean }>>()
const regionIndex = new Map<string, string[]>()
const ruleNameIndex = new Map<string, LogisticsRule>()
const ruleIdIndex = new Map<number, LogisticsRule>()

export function logisticsRuleByName(name: string) { return ruleNameIndex.get(name) }
export function logisticsRuleById(id: number) { return ruleIdIndex.get(id) }

/** Stable channel identity is authoritative; never substitute a same-name route. */
export function logisticsRuleForChannel(name: string, channelKey = '') {
  if (!channelKey) return logisticsRuleByName(name)
  const rule = logisticsRuleById(Number(channelKey.split('::')[0]))
  return rule?.relations.some(relation => `${rule.id}::${relation.carrier}::${relation.channelCode}` === channelKey) ? rule : undefined
}

function countryRowsForRule(rule: LogisticsRule, country: string) {
  const index = indexedRules.get(rule)
  return index ? (index.get(countryIdentity(country).toLowerCase()) || []).filter(row => countryMatches(row, country))
    : rule.prices.filter(row => countryMatches(row, country))
}

function eligibleCountryRows(rule: LogisticsRule, country: string, marks: string[]) {
  const cache = eligibleRows.get(rule)
  const key = JSON.stringify([country, rule.billingVerified, marks])
  const cached = cache?.get(key)
  if (cached) return cached
  const rows = countryRowsForRule(rule, country).filter(price =>
    isWeightRangePrice(price) && (rule.billingVerified ? price.quoteReady !== false : isPriceRowEligible(price, marks)))
  const result = { rows, zoneRequired: meaningfulZoneOptions(rows).length > 0 }
  cache?.set(key, result)
  return result
}

function rebuildLogisticsIndexes() {
  const carriers = [...new Set(logisticsRules.flatMap(rule => rule.relations.map(item => item.carrier)).filter(Boolean))].sort()
  const channels = logisticsRules.flatMap(rule => rule.relations.map(item => ({ ...item, ruleId: rule.id, ruleName: rule.name })))
  const countries = (publishedCountryCatalog.length
    ? publishedCountryCatalog
    : [...new Map(logisticsRules.flatMap(rule => rule.prices.map(price => [price.countryCode || price.areaName, { code: price.countryCode, name: price.areaName }]))).values()])
    .sort((a, b) => a.name.localeCompare(b.name, 'zh-CN'))
  logisticsCarriers.splice(0, logisticsCarriers.length, ...carriers)
  logisticsChannels.splice(0, logisticsChannels.length, ...channels)
  logisticsCountries.splice(0, logisticsCountries.length, ...countries)
}

export function replaceLogisticsRules(rules: LogisticsRule[]) {
  logisticsRules.splice(0, logisticsRules.length, ...rules)
  indexedRules = new WeakMap()
  eligibleRows = new WeakMap()
  regionIndex.clear()
  ruleNameIndex.clear()
  ruleIdIndex.clear()
  for (const rule of rules) {
    const rowsByCountry = new Map<string, LogisticsPriceRow[]>()
    for (const row of rule.prices) {
      for (const key of new Set([row.areaName.toLowerCase(), row.countryCode.toLowerCase(), countryIdentity(row.areaName).toLowerCase()])) {
        const bucket = rowsByCountry.get(key) || []
        bucket.push(row)
        rowsByCountry.set(key, bucket)
      }
    }
    indexedRules.set(rule, rowsByCountry)
    eligibleRows.set(rule, new Map())
    if (!ruleNameIndex.has(rule.name)) ruleNameIndex.set(rule.name, rule)
    ruleIdIndex.set(rule.id, rule)
  }
  rebuildLogisticsIndexes()
}

export function replaceLogisticsCountryCatalog(countries: Array<{ code: string; name: string }>) {
  publishedCountryCatalog = [...new Map(countries
    .filter(country => country.name)
    .map(country => [country.code || country.name, { code: String(country.code || '').toUpperCase(), name: country.name }])).values()]
  rebuildLogisticsIndexes()
}

rebuildLogisticsIndexes()
export const australiaQuoteRegions = ['澳大利亚1区', '澳大利亚2区', '澳大利亚3区', '澳大利亚4区'] as const
export function normalizeAustraliaQuoteRegion(value: string) {
  const normalized = String(value || '').replace(/[（）()\s]/g, '')
    .replace('一区', '1区').replace('二区', '2区').replace('三区', '3区').replace('四区', '4区')
  const match = normalized.match(/^(?:澳大利亚)?([1-4])区$/)
  return match ? `澳大利亚${match[1]}区` : normalized
}

export function logisticsQuoteRegions(country: string) {
  const cached = regionIndex.get(country)
  if (cached) return cached
  if (!isAustraliaQuoteCountry(country)) {
    const options = logisticsRules.flatMap(rule => {
      const rows = countryRowsForRule(rule, country).filter(row => !row.zoneExclude)
      const zones = [...new Set(rows.flatMap(row => row.zoneName ? splitZones(row.zoneName) : ['']))]
      const ordinary = zones.filter(isOrdinaryQuoteRegion)
      const shown = !isCanada(country) && ordinary.length ? ordinary : zones
      return shown.map(zone => zone ? `${channelRegionPrefix(rule)}${isCanada(country) ? normalizeCanadaRegion(zone) : zone}` : '全国统一')
    })
    const hasZones = options.some(option => option !== '全国统一')
    const result = hasZones ? [...new Set(options)].sort((a, b) =>
      Number(b === '全国统一') - Number(a === '全国统一') || a.localeCompare(b, 'zh-CN', { numeric: true })) : []
    regionIndex.set(country, result)
    return result
  }
  const regions = new Set<string>()
  for (const rule of logisticsRules) meaningfulZoneOptions(countryRowsForRule(rule, country)).forEach(region => regions.add(region))
  const result = [...regions].map(region => (country === '澳大利亚' || country.toUpperCase() === 'AU') && normalizeZone(region) !== '全国统一' ? `澳大利亚${normalizeZone(region)}` : region)
  regionIndex.set(country, result)
  return result
}

function isCanada(country: string) { return country === '加拿大' || country.toUpperCase() === 'CA' }
export function isAustraliaQuoteCountry(country: string) { return country === '澳大利亚' || country.toUpperCase() === 'AU' }
function isOrdinaryQuoteRegion(region: string) {
  return ['', '全国统一', '普通区域', '普通地区', '普通区', '非偏远', '非偏远地区', '非偏远区域', '非偏远区'].includes(region.trim())
}
function normalizeCanadaRegion(region: string) {
  return region.replace(/^加拿大/, '').replace(/[一二三四五六七八九]/g, digit => String('一二三四五六七八九'.indexOf(digit) + 1))
}
function channelRegionPrefix(rule: LogisticsRule) {
  const providers = [...new Set(rule.relations.map(relation => relation.carrier))].join('、')
  return `${providers}｜${rule.name}｜`
}
/** UI region labels are scoped to a channel; persisted billing uses that channel's original zone. */
export function billingQuoteRegion(rule: LogisticsRule, country: string, region = ''): string | null {
  if (isAustraliaQuoteCountry(country)) return region
  const rows = countryRowsForRule(rule, country)
  if (!region) return isCanada(country) && rows.some(row => row.zoneName) ? null : ''
  if (region === '全国统一') return rows.some(row => !row.zoneName) ? '全国统一' : null
  const prefix = channelRegionPrefix(rule)
  if (!region.startsWith(prefix)) return region.includes('｜') || isCanada(country) ? null : region
  const selected = region.slice(prefix.length)
  return rows.flatMap(row => splitZones(row.zoneName)).find(zone => (isCanada(country) ? normalizeCanadaRegion(zone) : zone) === selected) ?? null
}

/** A legacy template is matched only after its channel identity has matched. */
export function sameQuotationRegion(saved = '', current = '') {
  return saved === current || (current.includes('｜') && !saved.includes('｜') && normalizeCanadaRegion(saved) === normalizeCanadaRegion(current.split('｜').at(-1) || ''))
}

function countryMatches(price: LogisticsPriceRow, country: string) {
  return countryIdentityMatches(price.countryCode,price.areaName,country)
}
function splitZones(value: string) { return String(value || '').split(/[/／、,，;；|]/).map(item => item.trim()).filter(Boolean) }
function normalizeZone(value: string) { return String(value || '').replace(/[（）()\s]/g, '').replace(/^澳大利亚/, '').replace('一区', '1区').replace('二区', '2区').replace('三区', '3区').replace('四区', '4区') }
function meaningfulZoneOptions(rows: LogisticsPriceRow[]) {
  const zones = new Set(rows.flatMap(row => splitZones(row.zoneName)))
  const hasUnzoned = rows.some(row => !row.zoneName)
  if (zones.size <= 1 && !hasUnzoned) return []
  return [...(hasUnzoned && zones.size ? ['全国统一'] : []), ...zones]
}
function priceMatchesRegion(price: LogisticsPriceRow, quoteRegion: string, required: boolean) {
  if (!required) return true
  if (!quoteRegion) return false
  if (normalizeZone(quoteRegion) === '全国统一') return !price.zoneName || price.zoneName === '全国统一'
  return !price.zoneExclude && splitZones(price.zoneName).some(zone => normalizeZone(zone) === normalizeZone(quoteRegion))
}

function splitMarks(value: unknown) {
  return String(value || '').split(/[,，、;；|]/).map(item => item.trim()).filter(Boolean)
}
function normalizeShippingMarks(marks: string[]) {
  // Cosmetics is an independent shipment attribute, not an alias for non-liquid cosmetics.
  const normalized = marks.map(mark => mark.trim()).filter(Boolean)
  return [...new Set(normalized.length ? normalized : ['普货'])]
}
export function isWeightRangePrice(price: LogisticsPriceRow) {
  const minimum = Math.max(price.minChargeWeightKg ?? 0, price.startWeightKg ?? 0)
  return (!price.pricingModel || price.pricingModel === 'per-kg') && price.pricePerKg > 0
    && [price.minChargeWeightKg ?? 0, price.startWeightKg ?? 0].every(value => Number.isFinite(value) && value >= 0)
    && Number.isFinite(minimum) && minimum >= 0
    && (minimum === 0 || (price.weightToInclusive === false ? minimum < price.weightToKg : minimum <= price.weightToKg))
    && ![price.firstWeightKg, price.firstWeightPrice, price.nextWeightKg, price.nextWeightPrice, price.intervalPrice, price.surcharge].some(value => value > 0)
}
export function isPriceRowEligible(price: LogisticsPriceRow, productMarks: string[] = ['普货']) {
  productMarks = productMarks.map(normalizeLogisticsAttribute)
  if (price.quoteReady === false || !isWeightRangePrice(price)) return false
  const marks = normalizeShippingMarks(productMarks)
  const prohibited = new Set(splitMarks(price.prohibitedMarks).map(normalizeLogisticsAttribute))
  if (marks.some(mark => prohibited.has(mark))) return false
  if (price.prohibitGeneralCargo && marks.includes('普货')) return false
  const allowed = new Set(splitMarks(price.allowedMarks).map(normalizeLogisticsAttribute))
  return !allowed.size || marks.every(mark => mark === '普货' || allowed.has(mark))
}
export function findPriceRow(rule: LogisticsRule, country: string, weightKg: number, productMarks: string[] = ['普货'], quoteRegion = '') {
  const resolved = billingQuoteRegion(rule, country, quoteRegion)
  if (resolved === null) return undefined
  quoteRegion = resolved
  const { rows: countryRows, zoneRequired } = eligibleCountryRows(rule, country, productMarks)
  return selectBillingPrice(countryRows.filter(price => priceMatchesRegion(price, quoteRegion, zoneRequired)), weightKg)
}
export function weightMatchesPrice(price: Pick<LogisticsPriceRow, 'weightFromKg' | 'weightToKg' | 'weightFromInclusive' | 'weightToInclusive'> & Partial<Pick<LogisticsPriceRow, 'minChargeWeightKg' | 'startWeightKg'>>, weightKg: number) {
  if (!Number.isFinite(weightKg) || weightKg <= 0) return false
  const charged = Math.max(weightKg, price.minChargeWeightKg ?? 0, price.startWeightKg ?? 0)
  return (price.weightFromInclusive ? charged >= price.weightFromKg : charged > price.weightFromKg)
    && (price.weightToInclusive === false ? charged < price.weightToKg : charged <= price.weightToKg)
}
function selectBillingPrice(prices: LogisticsPriceRow[], weightKg: number) {
  const matches = prices.filter(price => weightMatchesPrice(price, weightKg))
  const standards = new Set(matches.map(price => [Math.max(price.minChargeWeightKg || 0, price.startWeightKg || 0), price.pricePerKg, price.registrationFee || 0].join('|')))
  return standards.size === 1 ? matches[0] : undefined
}
/** Explain a missing quote using the same eligible rows and boundaries as billing. */
export function logisticsUnavailableReason(rule: LogisticsRule | undefined, country: string, weightKg: number,
  productMarks: string[] = ['普货'], quoteRegion = '', quantity = 1, unit = '件'): string {
  if (!rule || rule.status !== '启用') return '渠道停用或资料不存在'
  if (!Number.isFinite(weightKg) || weightKg <= 0) return '当前条件暂无可用运价'
  const raw = countryRowsForRule(rule, country)
  if (!raw.length) return '该国家暂无可用运价'
  const resolved = billingQuoteRegion(rule, country, quoteRegion)
  if (resolved === null) return quoteRegion ? '该区域不支持' : '请先选择报价区域'
  const { rows, zoneRequired } = eligibleCountryRows(rule, country, productMarks)
  if (zoneRequired && !resolved) return '请先选择报价区域'
  const scoped = rows.filter(row => priceMatchesRegion(row, resolved, zoneRequired))
  if (!scoped.length) {
    const rawScoped = raw.filter(row => priceMatchesRegion(row, resolved, meaningfulZoneOptions(raw).length > 0))
    if (!rawScoped.length) return '该区域不支持'
    const supported = rawScoped.filter(row => isWeightRangePrice(row) && row.quoteReady !== false)
    if (!supported.length) return '计费规则暂不支持'
    if (!rule.billingVerified && !supported.some(row => isPriceRowEligible(row, productMarks))) return '该商品属性不支持'
    return '当前条件暂无可用运价'
  }
  if (selectBillingPrice(scoped, weightKg)) return ''
  if (scoped.some(row => weightMatchesPrice(row, weightKg))) return '同一重量匹配多个计费标准，请核对物流规则'
  const max = Math.max(...scoped.map(row => row.weightToKg))
  if (weightKg > max) return `${quantity}${unit}含包材重量${weightKg.toFixed(3)}kg，超过上限${max}kg`
  return '该重量段暂无运价'
}
export function calculateLogisticsFee(rule: LogisticsRule, country: string, weightKg: number, productMarks: string[] = ['普货'], dimensions?: ShipmentDimensions, quoteRegion = '') {
  const resolved = billingQuoteRegion(rule, country, quoteRegion)
  if (resolved === null) return null
  quoteRegion = resolved
  void dimensions
  const actualWeightKg = Math.max(0, Number(weightKg) || 0)
  const { rows: countryRows, zoneRequired } = eligibleCountryRows(rule, country, productMarks)
  if (zoneRequired && !quoteRegion) return null
  const volumeWeightKg = 0
  const volumeDivisor = 0
  // Each route uses its own explicit minimum before looking up its billing tier.
  const price = selectBillingPrice(countryRows.filter(candidate => priceMatchesRegion(candidate, quoteRegion, zoneRequired)), actualWeightKg)
  if (!price) return null
  const minChargeWeightKg = Math.max(price.minChargeWeightKg || 0, price.startWeightKg || 0)
  const chargeWeightKg = Math.max(actualWeightKg, minChargeWeightKg)
  const base = decimal(chargeWeightKg).times(price.pricePerKg).toNumber()
  const surcharge = 0
  const total = decimal(base).plus(price.registrationFee || 0).toDecimalPlaces(2).toNumber()
  return { total: Number(total.toFixed(2)), base, surcharge, price, actualWeightKg, minChargeWeightKg, volumeWeightKg, chargeWeightKg, volumeDivisor }
}
