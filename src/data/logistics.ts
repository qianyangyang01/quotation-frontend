export interface LogisticsPriceRow {
  areaName: string; countryCode: string; etaMinDays: number; etaMaxDays: number
  prohibitedMarks: string; allowedMarks: string; maxPerimeterCm: number; maxSideCm: number
  volumeDivisor: number; weightFromKg: number; weightToKg: number; startWeightKg: number
  pricePerKg: number; minChargeWeightKg: number; firstWeightKg: number; firstWeightPrice: number
  nextWeightKg: number; nextWeightPrice: number; intervalPrice: number; registrationFee: number
  surcharge: number; fuelSurchargeRate: number; prohibitGeneralCargo: boolean; volumetric: boolean
  phoneRequired: boolean; zoneName: string; zoneExclude: boolean
  pricingModel?: string; weightFromInclusive?: boolean; weightToInclusive?: boolean; quoteReady?: boolean
}
export interface LogisticsRelation { carrier: string; channel: string; channelCode: string; discounts: string }
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
  const match = normalized.match(/澳大利亚([1-4])区/)
  return match ? `澳大利亚${match[1]}区` : normalized
}

export function logisticsQuoteRegions(country: string) {
  const regions = new Set<string>()
  for (const rule of logisticsRules) meaningfulZoneOptions(rule.prices.filter(price => countryMatches(price, country))).forEach(region => regions.add(region))
  return [...regions].map(region => (country === '澳大利亚' || country.toUpperCase() === 'AU') && normalizeZone(region) !== '全国统一' ? `澳大利亚${normalizeZone(region)}` : region)
}

function countryMatches(price: LogisticsPriceRow, country: string) {
  return price.areaName === country || price.countryCode.toLowerCase() === country.toLowerCase()
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
  if (normalizeZone(quoteRegion) === '全国统一') return !price.zoneName
  return !price.zoneExclude && splitZones(price.zoneName).some(zone => normalizeZone(zone) === normalizeZone(quoteRegion))
}

function splitMarks(value: unknown) {
  return String(value || '').split(/[,，、;；|]/).map(item => item.trim()).filter(Boolean)
}
function normalizeShippingMarks(marks: string[]) {
  const normalized = marks.flatMap(mark => mark === '化妆品' ? ['非液体化妆品'] : [mark]).filter(Boolean)
  return [...new Set(normalized.length ? normalized : ['普货'])]
}
export function isWeightRangePrice(price: LogisticsPriceRow) {
  return (!price.pricingModel || price.pricingModel === 'per-kg') && price.pricePerKg > 0
    && ![price.firstWeightKg, price.firstWeightPrice, price.nextWeightKg, price.nextWeightPrice, price.intervalPrice, price.surcharge].some(value => value > 0)
}
export function isPriceRowEligible(price: LogisticsPriceRow, productMarks: string[] = ['普货']) {
  if (price.quoteReady === false || !isWeightRangePrice(price)) return false
  const marks = normalizeShippingMarks(productMarks)
  const prohibited = new Set(splitMarks(price.prohibitedMarks))
  if (marks.some(mark => prohibited.has(mark))) return false
  if (price.prohibitGeneralCargo && marks.includes('普货')) return false
  const allowed = new Set(splitMarks(price.allowedMarks))
  return !allowed.size || marks.every(mark => mark === '普货' || allowed.has(mark))
}
export function findPriceRow(rule: LogisticsRule, country: string, weightKg: number, productMarks: string[] = ['普货'], quoteRegion = '') {
  const countryRows = rule.prices.filter(price => countryMatches(price, country)
    && (isWeightRangePrice(price) && (rule.billingVerified ? price.quoteReady !== false : isPriceRowEligible(price, productMarks))))
  const zoneRequired = meaningfulZoneOptions(countryRows).length > 0
  return countryRows.find(price => priceMatchesRegion(price, quoteRegion, zoneRequired) && weightMatchesPrice(price, weightKg))
}
export function weightMatchesPrice(price: Pick<LogisticsPriceRow, 'weightFromKg' | 'weightToKg' | 'weightFromInclusive' | 'weightToInclusive'>, weightKg: number) {
  return (price.weightFromInclusive ? weightKg >= price.weightFromKg : weightKg > price.weightFromKg)
    && (price.weightToInclusive === false ? weightKg < price.weightToKg : weightKg <= price.weightToKg)
}
export function calculateLogisticsFee(rule: LogisticsRule, country: string, weightKg: number, productMarks: string[] = ['普货'], dimensions?: ShipmentDimensions, quoteRegion = '') {
  void dimensions
  const actualWeightKg = Math.max(0, Number(weightKg) || 0)
  const countryRows = rule.prices.filter(price => countryMatches(price, country)
    && (isWeightRangePrice(price) && (rule.billingVerified ? price.quoteReady !== false : isPriceRowEligible(price, productMarks))))
  const zoneRequired = meaningfulZoneOptions(countryRows).length > 0
  const candidates = countryRows.filter(price => priceMatchesRegion(price, quoteRegion, zoneRequired))
  if (zoneRequired && !quoteRegion) return null
  const chargeWeightKg = actualWeightKg
  const volumeWeightKg = 0
  const volumeDivisor = 0
  const price = candidates.find(candidate => weightMatchesPrice(candidate, chargeWeightKg))
  if (!price) return null
  const base = chargeWeightKg * price.pricePerKg
  const surcharge = 0
  const total = base + (price.registrationFee || 0)
  return { total: Number(total.toFixed(2)), base, surcharge, price, actualWeightKg, volumeWeightKg, chargeWeightKg, volumeDivisor }
}
