import masterData from './sumaoLogisticsMaster.json'
import areaData from './sumaoLogisticsAreas.json'

export interface LogisticsPriceRow {
  areaName: string; countryCode: string; etaMinDays: number; etaMaxDays: number
  prohibitedMarks: string; allowedMarks: string; maxPerimeterCm: number; maxSideCm: number
  volumeDivisor: number; weightFromKg: number; weightToKg: number; startWeightKg: number
  pricePerKg: number; minChargeWeightKg: number; firstWeightKg: number; firstWeightPrice: number
  nextWeightKg: number; nextWeightPrice: number; intervalPrice: number; registrationFee: number
  surcharge: number; fuelSurchargeRate: number; prohibitGeneralCargo: boolean; volumetric: boolean
  phoneRequired: boolean; zoneName: string; zoneExclude: boolean
}
interface AreaRule { id: number; areaCount: number; priceRowCount: number; prices: LogisticsPriceRow[] }
export interface LogisticsRelation { carrier: string; channel: string; channelCode: string; discounts: string }
export interface LogisticsRule {
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
  defaultVolumeDivisor?: number
}

const areaByRule = new Map((areaData.rules as AreaRule[]).map(rule => [rule.id, rule]))
export const logisticsRules: LogisticsRule[] = masterData.rules.map(rule => {
  const area = areaByRule.get(rule.id)
  return { ...rule, relations: rule.relations as LogisticsRelation[], areaCount: area?.areaCount ?? 0, priceRowCount: area?.priceRowCount ?? 0, prices: area?.prices ?? [] }
})
export const legacyLogisticsProviderNames = [...new Set(logisticsRules.flatMap(rule => rule.relations.map(item => item.carrier)).filter(Boolean))]
export const logisticsCarriers: string[] = []
export const logisticsChannels: Array<LogisticsRelation & { ruleId: number; ruleName: string }> = []
export const logisticsCountries: Array<{ code: string; name: string }> = []

function rebuildLogisticsIndexes() {
  const carriers = [...new Set(logisticsRules.flatMap(rule => rule.relations.map(item => item.carrier)).filter(Boolean))].sort()
  const channels = logisticsRules.flatMap(rule => rule.relations.map(item => ({ ...item, ruleId: rule.id, ruleName: rule.name })))
  const countries = [...new Map(logisticsRules.flatMap(rule => rule.prices.map(price => [price.countryCode || price.areaName, { code: price.countryCode, name: price.areaName }]))).values()]
    .sort((a, b) => a.name.localeCompare(b.name, 'zh-CN'))
  logisticsCarriers.splice(0, logisticsCarriers.length, ...carriers)
  logisticsChannels.splice(0, logisticsChannels.length, ...channels)
  logisticsCountries.splice(0, logisticsCountries.length, ...countries)
}

export function replaceLogisticsRules(rules: LogisticsRule[]) {
  logisticsRules.splice(0, logisticsRules.length, ...rules)
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
  if (country !== '澳大利亚' && country.toUpperCase() !== 'AU') return []
  return [...australiaQuoteRegions]
}

function priceMatchesCountryAndRegion(price: LogisticsPriceRow, country: string, quoteRegion = '') {
  const countryMatches = price.areaName === country || price.countryCode.toLowerCase() === country.toLowerCase()
  if (!countryMatches) return false
  if (quoteRegion) return normalizeAustraliaQuoteRegion(price.zoneName) === normalizeAustraliaQuoteRegion(quoteRegion) && !price.zoneExclude
  // 未传报价区域的旧调用继续保持原有国家级匹配；业务报价会显式传入澳大利亚区域。
  return true
}

function splitMarks(value: string) {
  return value.split(/[,，、;；|]/).map(item => item.trim()).filter(Boolean)
}
function normalizeShippingMarks(marks: string[]) {
  const normalized = marks.flatMap(mark => mark === '化妆品' ? ['非液体化妆品'] : [mark]).filter(Boolean)
  return [...new Set(normalized.length ? normalized : ['普货'])]
}
export function isPriceRowEligible(price: LogisticsPriceRow, productMarks: string[] = ['普货']) {
  const marks = normalizeShippingMarks(productMarks)
  const prohibited = new Set(splitMarks(price.prohibitedMarks))
  if (marks.some(mark => prohibited.has(mark))) return false
  if (price.prohibitGeneralCargo && marks.includes('普货')) return false
  const allowed = new Set(splitMarks(price.allowedMarks))
  return !allowed.size || marks.every(mark => mark === '普货' || allowed.has(mark))
}
export function findPriceRow(rule: LogisticsRule, country: string, weightKg: number, productMarks: string[] = ['普货'], quoteRegion = '') {
  return rule.prices.find(price => priceMatchesCountryAndRegion(price, country, quoteRegion) && weightKg > price.weightFromKg && weightKg <= price.weightToKg && isPriceRowEligible(price, productMarks))
}
export function calculateLogisticsFee(rule: LogisticsRule, country: string, weightKg: number, productMarks: string[] = ['普货'], dimensions?: ShipmentDimensions, quoteRegion = '') {
  const actualWeightKg = Math.max(0, Number(weightKg) || 0)
  const hasDimensions = Boolean(dimensions
    && dimensions.lengthCm > 0
    && dimensions.widthCm > 0
    && dimensions.heightCm > 0)
  const candidates = rule.prices.filter(price =>
    priceMatchesCountryAndRegion(price, country, quoteRegion)
    && isPriceRowEligible(price, productMarks))
  let chargeWeightKg = actualWeightKg
  let volumeWeightKg = 0
  let volumeDivisor = 0
  let price: LogisticsPriceRow | undefined
  if (hasDimensions && dimensions) {
    const volume = dimensions.lengthCm * dimensions.widthCm * dimensions.heightCm * Math.max(1, dimensions.volumeMultiplier || 1)
    price = candidates.find(candidate => {
      const divisor = candidate.volumeDivisor > 0 ? candidate.volumeDivisor : Math.max(1, dimensions.defaultVolumeDivisor || 8000)
      const volumetric = volume / divisor
      const chargeable = Math.max(actualWeightKg, volumetric)
      if (chargeable > candidate.weightFromKg && chargeable <= candidate.weightToKg) {
        chargeWeightKg = chargeable
        volumeWeightKg = volumetric
        volumeDivisor = divisor
        return true
      }
      return false
    })
  } else price = findPriceRow(rule, country, actualWeightKg, productMarks, quoteRegion)
  if (!price) return null
  let base: number
  if (price.intervalPrice > 0) base = price.intervalPrice
  else if (price.firstWeightKg > 0 && price.firstWeightPrice > 0) {
    const extraWeight = Math.max(0, chargeWeightKg - price.firstWeightKg)
    const extraUnits = price.nextWeightKg > 0 ? Math.ceil(extraWeight / price.nextWeightKg) : 0
    base = price.firstWeightPrice + extraUnits * price.nextWeightPrice
  } else base = Math.max(chargeWeightKg, price.startWeightKg, price.minChargeWeightKg) * price.pricePerKg
  // 速猫规则中的附加费属于渠道固定成本；燃油费按当前业务口径不参与报价。
  const surcharge = Math.max(0, price.surcharge || 0)
  const total = base + price.registrationFee + surcharge
  return { total: Number(total.toFixed(2)), base, surcharge, price, actualWeightKg, volumeWeightKg, chargeWeightKg, volumeDivisor }
}
