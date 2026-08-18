<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { loadQuotationUser } from '@/utils/quotationSession'
import QuotationHeader from '@/components/quotation/QuotationHeader.vue'
import QuotationCondition from '@/components/quotation/QuotationCondition.vue'
import ProductInfoCard from '@/components/quotation/ProductInfoCard.vue'
import BundleProductCard from '@/components/quotation/BundleProductCard.vue'
import CostWeightPanel from '@/components/quotation/CostWeightPanel.vue'
import QuotationPreviewSave from '@/components/quotation/QuotationPreviewSave.vue'
import QuotationMatrix from '@/components/quotation/QuotationMatrix.vue'
import QuotationCommonMatrix from '@/components/quotation/QuotationCommonMatrix.vue'
import QuotationTemplateMatrix from '@/components/quotation/QuotationTemplateMatrix.vue'
import { quotationProductCategories, type BundleQuoteItem, type QuotationCountrySummary, type QuotationMatrixRow, type QuotationMode, type QuotationProduct as Product } from '@/components/quotation/types'
import { australiaQuoteRegions, calculateLogisticsFee, findPriceRow, logisticsCountries, logisticsQuoteRegions, logisticsRules } from '@/data/logistics'
import { findPurchaseProduct, loadPurchaseProducts, purchaseDisplayName, purchaseFreightChoices, purchaseUnitPrice, type PurchaseProductRecord } from '@/data/purchaseStore'
import { createQuotationRecord } from '@/data/quotationRecords'
import { calculateFinanceQuoteTax, FINANCE_TAX_SETTINGS_UPDATED_EVENT, loadFinanceTaxSettings } from '@/data/financeTaxSettings'
import { inferCountryContinent } from '@/data/countryClassification'
import {
  customerGradeCoefficient,
  countriesAvailableForCategory,
  FINANCE_COUNTRY_SETTINGS_UPDATED_EVENT,
  financeAllowsLogisticsChannel,
  financeChannelKey,
  financeCountryOptionsForCategory,
  financeLogisticsAttributeOptions,
  loadFinanceExchangeRate,
  loadCustomerGradeSettings,
  loadFinanceChannelPolicies,
  loadFinanceCountrySettings,
  saveFinanceCountrySettings,
  type CustomerGrade,
} from '@/data/financeChannelPolicies'

const displayGrams = (weightKg: number) => Math.ceil((Number.isFinite(weightKg) ? weightKg : 0) * 1000)
function ruleSupportsShipment(rule: (typeof logisticsRules)[number], country: string, weightKg: number, logisticsAttribute: string) {
  return rule.status === '启用' && Boolean(findPriceRow(rule, country, weightKg, [logisticsAttribute], quoteRegionForCountry(country)))
}
function channelsForShipment(logisticsAttribute: string, country: string, weightKg: number) {
  return [...new Set(logisticsRules
    .filter(rule => ruleSupportsShipment(rule, country, weightKg, logisticsAttribute))
    .flatMap(rule => rule.relations
      .filter(relation => financeAllowsLogisticsChannel(financePolicies, logisticsAttribute, country, rule.id, relation))
      .map(relation => relation.carrier)))]
}
function rulesForShipment(logisticsAttribute: string, carrier: string, country: string, weightKg: number) {
  return logisticsRules
    .filter(rule => ruleSupportsShipment(rule, country, weightKg, logisticsAttribute)
      && rule.relations.some(relation => relation.carrier === carrier && financeAllowsLogisticsChannel(financePolicies, logisticsAttribute, country, rule.id, relation)))
    .map(rule => rule.name)
}
const quotationUser = loadQuotationUser()
const currentSalespersonName = computed(() => quotationUser.name)
const currentSalespersonAccount = computed(() => quotationUser.account)
const selectedSalesperson = computed(() => currentSalespersonAccount.value === '—'
  ? currentSalespersonName.value
  : `${currentSalespersonName.value}（${currentSalespersonAccount.value}）`)
const currentUserAvatar = computed(() => currentSalespersonName.value.slice(0, 2).toUpperCase())
const customerGradeSettings = loadCustomerGradeSettings()
const selectedCustomerGrade = ref<CustomerGrade>('S')
const financeExchangeRate = loadFinanceExchangeRate()
const exchange = ref({ usd: financeExchangeRate.usdCny, eur: 7.86, updatedAt: financeExchangeRate.updatedAt })
const notice = ref('')
const showRule = ref(false)
const showHistory = ref(false)
const customQuoteQuantity = ref(5)
const selectedQuoteRegions = ref<Record<string, string>>({ 澳大利亚: australiaQuoteRegions[0] })
const specifiedQuoteRows = ref<QuotationMatrixRow[]>([])
const templateQuoteRows = ref<QuotationMatrixRow[]>([])
const commonQuoteRows = ref<QuotationMatrixRow[]>([])
const activeTemplateSnapshot = ref<{ id: string; name: string } | null>(null)
const quoteMatrixMode = ref<'common' | 'specified' | 'template'>('common')
const quoteMode = ref<QuotationMode>('single')
const route = useRoute()
const purchaseRecords = loadPurchaseProducts()
const financePolicies = loadFinanceChannelPolicies()
const financeCountrySettings = ref(loadFinanceCountrySettings())
const financeTaxSettings = ref(loadFinanceTaxSettings())
const quotationAttributeOptions = [...new Set([...financeLogisticsAttributeOptions, ...financePolicies.map(policy => policy.category)])]
const initialPurchaseRecord = findPurchaseProduct(purchaseRecords, String(route.query.sku || '')) || purchaseRecords[0]
const initialFreightChoice = initialPurchaseRecord ? purchaseFreightChoices(initialPurchaseRecord).find(item => item.quantity === 10) : undefined
const skuSearch = ref(initialPurchaseRecord?.sku || '')
const customerName = ref('')
const productCategory = ref(quotationProductCategories.find(category => category === initialPurchaseRecord?.category) || '')
const monthlySalesEstimate = ref('10')
function monthlySalesPurchaseQuantity(value = monthlySalesEstimate.value) {
  return value === '100+' ? 100 : value === '100' ? 10 : 1
}
function monthlySalesTierLabel(value = monthlySalesEstimate.value) {
  return value === '100+' ? '100件采购价' : value === '100' ? '10件采购价' : '1件参考价'
}
function purchasePriceForMonthlySales(record: PurchaseProductRecord) {
  return purchaseUnitPrice(record, monthlySalesPurchaseQuantity())
}
const initialLogisticsAttribute = quotationAttributeOptions[0] || '普货'
const initialCountry = financeCountryOptionsForCategory(financePolicies, initialLogisticsAttribute, financeCountrySettings.value)[0]?.name || ''
const initialWeight = initialPurchaseRecord?.weightKg || 0.001
const initialChannel = channelsForShipment(initialLogisticsAttribute, initialCountry, initialWeight)[0] || ''
const initialRule = rulesForShipment(initialLogisticsAttribute, initialChannel, initialCountry, initialWeight)[0] || ''

function quoteRegionForCountry(country: string) {
  return country === '澳大利亚' || country.toUpperCase() === 'AU'
    ? (selectedQuoteRegions.value.澳大利亚 || australiaQuoteRegions[0])
    : ''
}
function changeQuoteRegion(p: Product, payload: { country: string; region: string }) {
  if (payload.country !== '澳大利亚' || !australiaQuoteRegions.includes(payload.region as (typeof australiaQuoteRegions)[number])) return
  selectedQuoteRegions.value = { ...selectedQuoteRegions.value, 澳大利亚: payload.region }
  if (p.country === '澳大利亚') normalizeRule(p, true)
  toast(`已切换为${payload.region}，渠道与报价已按该区运费重新匹配`)
}

const products = ref<Product[]>([
  {
    id: 1, selected: true, name: initialPurchaseRecord ? purchaseDisplayName(initialPurchaseRecord) : '待查询采购商品', sku: initialPurchaseRecord?.sku || '', logisticsAttribute: initialLogisticsAttribute,
    supplier: initialPurchaseRecord?.quotationOwner || '待补充', image: initialPurchaseRecord?.image || '', stockStatus: initialPurchaseRecord?.stockStatus || '待确认', quantity: 1, purchase: initialPurchaseRecord ? purchasePriceForMonthlySales(initialPurchaseRecord) : 0,
    purchaseFreightPerUnit: initialFreightChoice?.unitFreightCny || 0,
    netWeight: initialPurchaseRecord?.weightKg || 0,
    country: initialCountry, channel: initialChannel, rule: initialRule,
    manualFreight: false, freight: 30.16, margin: 21, memberMargin: 17, profitType: 'rate', fixedProfit: 0,
    weightSource: 'purchase', manualWeight: initialPurchaseRecord?.weightKg || 0,
    volumetricEnabled: false, packageLengthCm: 0, packageWidthCm: 0, packageHeightCm: 0,
    discountEnabled: false, discountRate: 0, status: initialPurchaseRecord?.status === '资料完整' ? '采购资料已加载' : (initialPurchaseRecord?.status || '待查询'),
  },
])
let bundleItemId = 1
function bundleItemFromRecord(record?: PurchaseProductRecord): BundleQuoteItem {
  const quantityPerSet = 1
  return {
    id: bundleItemId++,
    sku: record?.sku || '',
    name: record ? purchaseDisplayName(record) : '',
    supplier: record?.quotationOwner || '',
    image: record?.image || '',
    stockStatus: record?.stockStatus || '待确认',
    quantityPerSet,
    purchaseUnitPrice: record ? purchasePriceForMonthlySales(record) : 0,
    customWeightKg: null,
    purchaseFreightPerUnit: record ? purchaseFreightChoices(record).find(item => item.quantity === 10)?.unitFreightCny || 0 : 0,
    weightKg: record?.weightKg || 0,
    status: record ? (record.status === '资料完整' ? '采购资料已加载' : record.status) : '待查询',
  }
}
const bundleItems = ref<BundleQuoteItem[]>([bundleItemFromRecord(initialPurchaseRecord)])
function normalizedBundleSets(value: number) { return Math.max(1, Math.floor(Number(value) || 1)) }
function bundlePurchaseCost(sets = 1) {
  const setCount = normalizedBundleSets(sets)
  return bundleItems.value.reduce((sum, item) => {
    const record = findPurchaseProduct(purchaseRecords, item.sku)
    const purchasePrice = record ? purchasePriceForMonthlySales(record) : item.purchaseUnitPrice
    return sum + purchasePrice * normalizedBundleSets(item.quantityPerSet) * setCount
  }, 0)
}
function bundleDomesticFreight(sets = 1) {
  const setCount = normalizedBundleSets(sets)
  return bundleItems.value.reduce((sum, item) => sum + item.purchaseFreightPerUnit * normalizedBundleSets(item.quantityPerSet) * setCount, 0)
}
function bundleGoodsWeight(sets = 1) {
  const setCount = normalizedBundleSets(sets)
  return bundleItems.value.reduce((sum, item) => {
    const weightKg = item.customWeightKg != null && Number.isFinite(Number(item.customWeightKg))
      ? Math.max(0, Number(item.customWeightKg))
      : item.weightKg
    return sum + weightKg * normalizedBundleSets(item.quantityPerSet) * setCount
  }, 0)
}
function purchaseWeight(p: Product) { return quoteMode.value === 'bundle' ? bundleGoodsWeight(1) : p.netWeight * p.quantity }
function singleActualWeight(p: Product, quantity = Math.max(1, p.quantity)) {
  const unitWeight = p.weightSource === 'manual' ? p.manualWeight : p.netWeight
  return Math.max(0, unitWeight) * Math.max(1, quantity)
}
function singleVolumeWeight(p: Product, quantity = Math.max(1, p.quantity), divisor = 8000) {
  if (!p.volumetricEnabled || p.packageLengthCm <= 0 || p.packageWidthCm <= 0 || p.packageHeightCm <= 0) return 0
  return p.packageLengthCm * p.packageWidthCm * p.packageHeightCm * Math.max(1, quantity) / Math.max(1, divisor)
}
function chargeWeight(p: Product) {
  if (quoteMode.value === 'bundle') return purchaseWeight(p)
  return Math.max(singleActualWeight(p), singleVolumeWeight(p))
}
function singleDimensions(p: Product, quantity = Math.max(1, p.quantity)) {
  if (!p.volumetricEnabled) return undefined
  return {
    lengthCm: Math.max(0, p.packageLengthCm),
    widthCm: Math.max(0, p.packageWidthCm),
    heightCm: Math.max(0, p.packageHeightCm),
    volumeMultiplier: Math.max(1, quantity),
    defaultVolumeDivisor: 8000,
  }
}
function productCost(p: Product) { return quoteMode.value === 'bundle' ? bundlePurchaseCost(1) : p.purchase * p.quantity }
function domesticFreight(p: Product) { return quoteMode.value === 'bundle' ? bundleDomesticFreight(1) : p.purchaseFreightPerUnit * p.quantity }
function totalCost(p: Product) { return quoteMode.value === 'bundle' ? bundlePurchaseCost(1) + bundleDomesticFreight(1) + p.freight : p.purchase + p.purchaseFreightPerUnit + p.freight }
function selectedGradeCoefficient() { return customerGradeCoefficient(customerGradeSettings, selectedCustomerGrade.value) }
function salePrice(p: Product) { return totalCost(p) * selectedGradeCoefficient() }
function usdPriceFromCny(cny: number) { return Math.round(cny * 100) / 100 / exchange.value.usd }
function taxResult(country: string, provider: string, baseQuoteCny: number) {
  return calculateFinanceQuoteTax(financeTaxSettings.value, country, provider, usdPriceFromCny(baseQuoteCny))
}
function finalSalePrice(p: Product) { return taxResult(p.country, p.channel, salePrice(p)).totalUsd * exchange.value.usd }
function estimatedProfit(p: Product) { return salePrice(p) - totalCost(p) }
function applyPurchaseRecord(p: Product, record: PurchaseProductRecord) {
  p.sku = record.sku
  p.name = purchaseDisplayName(record)
  p.supplier = record.quotationOwner || '待补充'
  p.image = record.image
  p.stockStatus = record.stockStatus
  p.purchase = purchasePriceForMonthlySales(record)
  p.purchaseFreightPerUnit = purchaseFreightChoices(record).find(item => item.quantity === 10)?.unitFreightCny || 0
  p.netWeight = record.weightKg || 0
  p.manualWeight = record.weightKg || 0
  p.volumetricEnabled = false
  p.packageLengthCm = 0
  p.packageWidthCm = 0
  p.packageHeightCm = 0
  p.status = record.status === '资料完整' ? '采购资料已加载' : record.status
}
function queryProduct() {
  const matches = purchaseRecords.filter(item => item.sku === skuSearch.value.trim().toUpperCase().replace(/\s+/g, ''))
  if (!matches.length) { toast(`未在采购资料中找到 SKU：${skuSearch.value}`); return }
  const p = products.value[0]
  applyPurchaseRecord(p, matches[0])
  if (!productCategory.value) productCategory.value = quotationProductCategories.find(category => category === matches[0].category) || ''
  skuSearch.value = matches[0].sku
  if (matches[0].weightKg != null && matches[0].purchasePriceCny != null) normalizeRule(p)
  else p.freight = 0
  const warning = matches[0].status === '资料完整' ? '' : `；${matches[0].status}`
  const duplicate = matches.length > 1 ? `；检测到${matches.length}条同SKU记录，当前采用第一条` : ''
  toast(`已加载 ${matches[0].sku}；是否有货：${matches[0].stockStatus}${warning}${duplicate}`)
}
function queryBundleItem(item: BundleQuoteItem) {
  const normalizedSku = item.sku.trim().toUpperCase().replace(/\s+/g, '')
  const record = findPurchaseProduct(purchaseRecords, normalizedSku)
  if (!record) { toast(`未在采购资料中找到 SKU：${item.sku}`); return }
  const duplicate = bundleItems.value.find(other => other.id !== item.id && other.sku === record.sku)
  if (duplicate) {
    duplicate.quantityPerSet += normalizedBundleSets(item.quantityPerSet)
    removeBundleItem(item.id)
    updateBundleItemQuantity(duplicate)
    toast(`${record.sku} 已存在，已合并到同一行并累加单套数量`)
    return
  }
  item.sku = record.sku
  item.name = purchaseDisplayName(record)
  item.supplier = record.quotationOwner || '待补充'
  item.image = record.image
  item.stockStatus = record.stockStatus
  item.customWeightKg = null
  item.weightKg = record.weightKg || 0
  item.purchaseFreightPerUnit = purchaseFreightChoices(record).find(choice => choice.quantity === 10)?.unitFreightCny || 0
  item.status = record.status === '资料完整' ? '采购资料已加载' : record.status
  updateBundleItemQuantity(item, false)
  toast(`已加入组合 SKU：${record.sku}；是否有货：${record.stockStatus}`)
}
function addBundleItem() {
  bundleItems.value.push(bundleItemFromRecord())
}
function removeBundleItem(id: number) {
  if (bundleItems.value.length <= 1) return
  bundleItems.value = bundleItems.value.filter(item => item.id !== id)
  normalizeRule(products.value[0], true)
}
function updateBundleItemQuantity(item: BundleQuoteItem, showToast = false) {
  item.quantityPerSet = normalizedBundleSets(item.quantityPerSet)
  const record = findPurchaseProduct(purchaseRecords, item.sku)
  if (record) item.purchaseUnitPrice = purchasePriceForMonthlySales(record)
  normalizeRule(products.value[0], true)
  if (showToast) toast(`已更新 ${item.sku} 的单套数量`)
}
function updateBundleItemWeight(item: BundleQuoteItem) {
  const rawWeight = item.customWeightKg as number | string | null
  if (rawWeight !== null && rawWeight !== '') {
    item.customWeightKg = Math.max(0, Number(rawWeight) || 0)
  } else item.customWeightKg = null
  normalizeRule(products.value[0], true)
}
function changeQuoteMode(mode: QuotationMode) {
  quoteMode.value = mode
  products.value[0].quantity = 1
  normalizeRule(products.value[0], true)
  toast(mode === 'bundle' ? '已切换为组合 SKU 报价，报价数量按“套”计算' : '已切换为单品 SKU 报价')
}
function changeMonthlySalesEstimate(p: Product, value: string) {
  monthlySalesEstimate.value = value
  const record = findPurchaseProduct(purchaseRecords, p.sku)
  if (record) p.purchase = purchasePriceForMonthlySales(record)
  bundleItems.value.forEach(item => {
    const itemRecord = findPurchaseProduct(purchaseRecords, item.sku)
    if (itemRecord) item.purchaseUnitPrice = purchasePriceForMonthlySales(itemRecord)
  })
  normalizeRule(p, true)
  toast(`已匹配${monthlySalesTierLabel()}，采购单价与报价已更新`)
}
function availableQuoteCountries(p: Product) {
  return countriesAvailableForCategory(p.logisticsAttribute).map(country => country.name)
}
function normalizeRule(p: Product, silent = false) {
  const policyCountries = availableQuoteCountries(p)
  if (!policyCountries.includes(p.country)) p.country = policyCountries[0] || ''
  if (!p.country) {
    p.channel = ''
    p.rule = ''
    p.freight = 0
    p.status = `财务未配置“${p.logisticsAttribute}”物流属性的可发国家与渠道`
    return
  }
  const best = bestLogisticsOption(p, p.country)
  if (!best) {
    p.channel = ''
    p.rule = ''
    p.freight = 0
    p.status = `财务策略无可用渠道：${p.logisticsAttribute}`
    return
  }
  p.channel = best.carrier
  p.rule = best.rule
  p.freight = best.freight
  p.manualFreight = false
  p.status = '已自动采用最低报价渠道'
  if (!silent) toast(`已自动采用最低报价渠道“${best.rule}”，报价 ¥${best.finalQuoteCny.toFixed(2)}`)
}
function calculateFreight(p: Product, silent = false) {
  if (p.manualFreight) return
  if (!p.rule) { p.freight = 0; p.status = '当前条件无可用物流规则'; return }
  const rule = logisticsRules.find(item => item.name === p.rule)
  const result = rule ? calculateLogisticsFee(
    rule,
    p.country,
    quoteMode.value === 'bundle' ? chargeWeight(p) : singleActualWeight(p),
    [p.logisticsAttribute],
    quoteMode.value === 'single' ? singleDimensions(p) : undefined,
    quoteRegionForCountry(p.country),
  ) : null
  if (!result) { p.status = '无匹配物流价'; return }
  p.freight = Number(result.total.toFixed(2))
  p.status = '已试算'
  if (!silent) toast(`${p.sku} 已按“${p.rule}”完成试算`)
}
normalizeRule(products.value[0], true)
function refreshFinanceCountrySettings(event?: Event) {
  if (event instanceof StorageEvent && event.key && event.key !== 'milano.finance-country-classification.v1') return
  financeCountrySettings.value = loadFinanceCountrySettings()
  products.value.forEach(product => normalizeRule(product, true))
}
function refreshFinanceTaxSettings(event?: Event) {
  if (event instanceof StorageEvent && event.key && event.key !== 'milano.finance-tax-settings.v1') return
  financeTaxSettings.value = loadFinanceTaxSettings()
  products.value.forEach(product => normalizeRule(product, true))
}
function reorderCommonCountries(countries: string[]) {
  const order = new Map(countries.map((country, index) => [country, (index + 1) * 10]))
  financeCountrySettings.value.forEach(setting => {
    const sortOrder = order.get(setting.country)
    if (sortOrder != null && setting.enabled && setting.stage === 'common') setting.sortOrder = sortOrder
  })
  financeCountrySettings.value = saveFinanceCountrySettings(financeCountrySettings.value)
  toast('常用国家顺序已保存')
}
onMounted(() => {
  window.addEventListener(FINANCE_COUNTRY_SETTINGS_UPDATED_EVENT, refreshFinanceCountrySettings)
  window.addEventListener(FINANCE_TAX_SETTINGS_UPDATED_EVENT, refreshFinanceTaxSettings)
  window.addEventListener('storage', refreshFinanceCountrySettings)
  window.addEventListener('storage', refreshFinanceTaxSettings)
})
onBeforeUnmount(() => {
  window.removeEventListener(FINANCE_COUNTRY_SETTINGS_UPDATED_EVENT, refreshFinanceCountrySettings)
  window.removeEventListener(FINANCE_TAX_SETTINGS_UPDATED_EVENT, refreshFinanceTaxSettings)
  window.removeEventListener('storage', refreshFinanceCountrySettings)
  window.removeEventListener('storage', refreshFinanceTaxSettings)
})
function matchedLogistics(p: Product, country = p.country) {
  return logisticsRules.flatMap(rule => {
    const relations = rule.relations.filter(relation => financeAllowsLogisticsChannel(financePolicies, p.logisticsAttribute, country, rule.id, relation))
    if (!relations.length || rule.status !== '启用') return []
    const result = calculateLogisticsFee(
      rule,
      country,
      quoteMode.value === 'bundle' ? chargeWeight(p) : singleActualWeight(p),
      [p.logisticsAttribute],
      quoteMode.value === 'single' ? singleDimensions(p) : undefined,
      quoteRegionForCountry(country),
    )
    if (!result) return []
    return relations.map(relation => ({
      channelKey: financeChannelKey(rule.id, relation),
      ruleId: rule.id,
      rule: rule.name,
      carrier: relation.carrier,
      channel: relation.channel,
      channelCode: relation.channelCode,
      freight: Number(result.total.toFixed(2)),
      baseFee: Number(result.base.toFixed(2)),
      registrationFee: result.price.registrationFee,
      eta: `${result.price.etaMinDays || '-'}～${result.price.etaMaxDays || '-'} 天`,
      weightRange: `${displayGrams(result.price.weightFromKg)}～${displayGrams(result.price.weightToKg)} g`,
    }))
  }).sort((a, b) => a.freight - b.freight)
}
function quantityCostBreakdown(p: Product, ruleName: string, quantity: number, country = p.country, provider = '') {
  const rule = logisticsRules.find(item => item.name === ruleName)
  if (!rule) return null
  const normalizedQuantity = normalizedBundleSets(quantity)
  const weightKg = quoteMode.value === 'bundle'
    ? bundleGoodsWeight(normalizedQuantity)
    : singleActualWeight(p, normalizedQuantity)
    const result = calculateLogisticsFee(rule, country, weightKg, [p.logisticsAttribute], quoteMode.value === 'single' ? singleDimensions(p, normalizedQuantity) : undefined, quoteRegionForCountry(country))
  if (!result) return null
  const freight = Number(result.total.toFixed(2))
  let cost: number
  if (quoteMode.value === 'bundle') {
    cost = bundlePurchaseCost(normalizedQuantity) + bundleDomesticFreight(normalizedQuantity) + freight
  } else {
    const record = findPurchaseProduct(purchaseRecords, p.sku)
    const purchasePrice = record ? purchasePriceForMonthlySales(record) : p.purchase
    cost = (purchasePrice + p.purchaseFreightPerUnit) * normalizedQuantity + freight
  }
  const baseQuoteCny = cost * selectedGradeCoefficient()
  const tax = taxResult(country, provider, baseQuoteCny)
  const quoteCny = tax.totalUsd * exchange.value.usd
  return { freight, cost, quoteCny, profit: baseQuoteCny - cost, quoteUsd: tax.totalUsd, tax }
}
function bestLogisticsOption(p: Product, country = p.country) {
  return matchedLogistics(p, country)
    .map(option => ({
      ...option,
      finalQuoteCny: quantityCostBreakdown(p, option.rule, quoteMode.value === 'bundle' ? 1 : Math.max(1, p.quantity), country, option.carrier)?.quoteCny ?? Number.POSITIVE_INFINITY,
    }))
    .filter(option => Number.isFinite(option.finalQuoteCny))
    .sort((a, b) => a.finalQuoteCny - b.finalQuoteCny || a.freight - b.freight)[0]
}
function excelQuoteRows(p: Product, country = p.country): QuotationMatrixRow[] {
  const quantity = Math.max(1, customQuoteQuantity.value || 1)
  const currentQuantity = quoteMode.value === 'bundle' ? 1 : Math.max(1, p.quantity)
  return matchedLogistics(p, country).map(option => {
    const quote1 = quantityCostBreakdown(p, option.rule, 1, country, option.carrier)
    const quote2 = quantityCostBreakdown(p, option.rule, 2, country, option.carrier)
    const quote3 = quantityCostBreakdown(p, option.rule, 3, country, option.carrier)
    const custom = quantityCostBreakdown(p, option.rule, quantity, country, option.carrier)
    const current = currentQuantity === 1
      ? quote1
      : currentQuantity === 2
        ? quote2
        : currentQuantity === 3
          ? quote3
          : currentQuantity === quantity
            ? custom
            : quantityCostBreakdown(p, option.rule, currentQuantity, country, option.carrier)
    const tax = custom?.tax || taxResult(country, option.carrier, 0)
    const row: QuotationMatrixRow = {
        ...option,
        country,
        quoteRegion: quoteRegionForCountry(country) || undefined,
        transport: option.channel || option.rule,
        // “采用”切换的是当前报价数量对应的路线，不能把自定义数量的运费带回主报价。
        freight: option.freight,
        totalCostCny: custom?.cost ?? 0,
        profitCny: custom?.profit ?? 0,
        quoteCny: custom?.quoteCny ?? 0,
        quote1: quote1?.quoteUsd ?? null,
        quote2: quote2?.quoteUsd ?? null,
        quote3: quote3?.quoteUsd ?? null,
        quoteCustom: custom?.quoteUsd ?? null,
        taxIncluded: tax.included,
        taxConfigured: tax.configured,
        taxRatePercent: tax.ratePercent,
        countryFixedTaxUsd: tax.fixedFeeUsd,
        taxLabel: tax.label,
        tax1Usd: quote1?.tax.taxUsd ?? null,
        tax2Usd: quote2?.tax.taxUsd ?? null,
        tax3Usd: quote3?.tax.taxUsd ?? null,
        taxCustomUsd: custom?.tax.taxUsd ?? null,
      }
    return { row, sortQuote: current?.quoteUsd ?? Number.POSITIVE_INFINITY }
  }).sort((a, b) => a.sortQuote - b.sortQuote || a.row.freight - b.row.freight)
    .map(item => item.row)
}
function quotationCountries(p: Product): QuotationCountrySummary[] {
  const settingMap = new Map(financeCountrySettings.value.map(option => [option.country, option]))
  return countriesAvailableForCategory(p.logisticsAttribute).map(country => {
    const name = country.name
    const option = settingMap.get(name)
    const common = option?.enabled && option.stage === 'common'
    // 国家选择器只需要显示可用渠道数量，不应为全球每个国家预先计算四档完整报价。
    // 用户真正选择/打开某个国家时，再由矩阵按需调用 excelQuoteRows。
    const channelCount = matchedLogistics(p, name).length
    return {
      name,
      code: String(country.code || logisticsCountries.find(item => item.name === name)?.code || '').toUpperCase(),
      channelCount,
      lowestQuote: null,
      grouped: true,
      stage: common ? 'common' as const : 'rare' as const,
      continent: option?.continent || inferCountryContinent(country.code),
      sortOrder: common ? option.sortOrder : 1000,
      quoteRegions: name === '澳大利亚' ? logisticsQuoteRegions(name) : undefined,
      selectedQuoteRegion: name === '澳大利亚' ? quoteRegionForCountry(name) : undefined,
    }
  }).sort((a, b) => Number(a.stage !== 'common') - Number(b.stage !== 'common') || a.sortOrder - b.sortOrder || a.name.localeCompare(b.name, 'zh-CN'))
}
function quoteMatrixContextKey(p: Product) {
  const bundleKey = quoteMode.value === 'bundle'
    ? bundleItems.value.map(item => `${item.sku}:${item.quantityPerSet}:${item.customWeightKg ?? item.weightKg}`).join('|')
    : `${p.sku}:${chargeWeight(p)}`
  return `${quoteMode.value}|${bundleKey}|${p.logisticsAttribute}|${selectedQuoteRegions.value.澳大利亚}|${financeTaxSettings.value.updatedAt}`
}
// 报价国家和渠道矩阵计算量很大。通过稳定的 computed/函数引用传给三个矩阵，
// 避免客户名称等无关表单字段每输入一个字符就重算全部国家、渠道和四档报价。
const activeQuotationCountries = computed(() => {
  const p = products.value[0]
  return p ? quotationCountries(p) : []
})
const activeQuoteMatrixContextKey = computed(() => {
  const p = products.value[0]
  return p ? quoteMatrixContextKey(p) : ''
})
const activeCommonCountryCount = computed(() => activeQuotationCountries.value.filter(country => country.stage === 'common').length)
function activeQuoteRowsForCountry(country: string) {
  const p = products.value[0]
  return p ? excelQuoteRows(p, country) : []
}
function matrixRowsSignature(items: QuotationMatrixRow[]) {
  return items.map(row => [
    row.country,
    row.quoteRegion,
    row.channelKey,
    row.rule,
    row.carrier,
    row.transport,
    row.quote1,
    row.quote2,
    row.quote3,
    row.quoteCustom,
    row.taxLabel,
    row.tax1Usd,
    row.tax2Usd,
    row.tax3Usd,
    row.taxCustomUsd,
  ].join('|')).join('||')
}
function updateSpecifiedQuotes(rows: QuotationMatrixRow[]) {
  if (matrixRowsSignature(specifiedQuoteRows.value) === matrixRowsSignature(rows)) return
  specifiedQuoteRows.value = rows
}
function updateCommonQuotes(rows: QuotationMatrixRow[]) {
  if (matrixRowsSignature(commonQuoteRows.value) === matrixRowsSignature(rows)) return
  commonQuoteRows.value = rows
}
function updateTemplateQuotes(rows: QuotationMatrixRow[]) {
  if (matrixRowsSignature(templateQuoteRows.value) === matrixRowsSignature(rows)) return
  templateQuoteRows.value = rows
}
function updateActiveTemplate(template: { id: string; name: string } | null) {
  activeTemplateSnapshot.value = template
}
const activeMatrixRows = computed(() => quoteMatrixMode.value === 'specified'
  ? specifiedQuoteRows.value
  : quoteMatrixMode.value === 'template'
    ? templateQuoteRows.value
    : [])
/* Legacy single-route snapshot retained for reference only; common quotations now use commonQuoteRows. */
/* eslint-disable-next-line @typescript-eslint/no-unused-vars */
function commonSavedQuoteRow(p: Product): QuotationMatrixRow | null {
  if (!p.country || !p.rule || !p.channel) return null
  const exact = excelQuoteRows(p, p.country).find(row => row.rule === p.rule && row.carrier === p.channel)
  if (exact) return exact

  // 兼容已采用渠道在财务配置刷新后暂时不再出现在矩阵中的情况：
  // 保存当次页面仍可确认的主渠道快照，而不是让整张报价单丢失方案明细。
  const rule = logisticsRules.find(item => item.name === p.rule)
  const relation = rule?.relations.find(item => item.carrier === p.channel)
  const quantity = Math.max(1, customQuoteQuantity.value || 1)
  const quote1 = quantityCostBreakdown(p, p.rule, 1, p.country, p.channel)
  const quote2 = quantityCostBreakdown(p, p.rule, 2, p.country, p.channel)
  const quote3 = quantityCostBreakdown(p, p.rule, 3, p.country, p.channel)
  const custom = quantityCostBreakdown(p, p.rule, quantity, p.country, p.channel)
  const tax = custom?.tax || taxResult(p.country, p.channel, salePrice(p))
  return {
    country: p.country,
    channelKey: rule && relation ? financeChannelKey(rule.id, relation) : `${p.rule}::${p.channel}`,
    ruleId: rule?.id || 0,
    channelCode: relation?.channelCode || '',
    rule: p.rule,
    carrier: p.channel,
    transport: relation?.channel || p.rule,
    eta: '—',
    freight: Number(p.freight || 0),
    totalCostCny: custom?.cost ?? totalCost(p),
    profitCny: custom?.profit ?? estimatedProfit(p),
    quoteCny: custom?.quoteCny ?? finalSalePrice(p),
    quote1: quote1?.quoteUsd ?? null,
    quote2: quote2?.quoteUsd ?? null,
    quote3: quote3?.quoteUsd ?? null,
    quoteCustom: custom?.quoteUsd ?? usdPriceFromCny(finalSalePrice(p)),
    taxIncluded: tax.included,
    taxConfigured: tax.configured,
    taxRatePercent: tax.ratePercent,
    countryFixedTaxUsd: tax.fixedFeeUsd,
    taxLabel: tax.label,
    tax1Usd: quote1?.tax.taxUsd ?? null,
    tax2Usd: quote2?.tax.taxUsd ?? null,
    tax3Usd: quote3?.tax.taxUsd ?? null,
    taxCustomUsd: custom?.tax.taxUsd ?? null,
  }
}
void commonSavedQuoteRow
const savedQuoteRows = computed(() => {
  const p = products.value[0]
  if (!p) return []
  if (quoteMatrixMode.value !== 'common') return activeMatrixRows.value
  return commonQuoteRows.value
})
const savedQuoteCountryCount = computed(() => new Set(savedQuoteRows.value.map(row => row.country)).size)
const matrixModeLabel = computed(() => quoteMatrixMode.value === 'common'
  ? '常用国家快速报价'
  : quoteMatrixMode.value === 'specified'
    ? '指定国家与渠道报价'
    : activeTemplateSnapshot.value?.name
      ? `我的报价模板 · ${activeTemplateSnapshot.value.name}`
      : '我的报价模板')
const showSaveValidation = ref(false)
const saveValidationIssues = computed(() => {
  const p = products.value[0]
  const issues: Array<{ key: string; label: string; message: string }> = []
  if (!customerName.value.trim()) issues.push({ key:'customerName', label:'客户名称', message:'请填写客户名称' })
  if (!productCategory.value) issues.push({ key:'productCategory', label:'产品品类', message:'请选择产品品类' })
  if (!p?.sku) issues.push({ key:'sku', label:quoteMode.value === 'bundle' ? '组合商品' : '商品 SKU', message:quoteMode.value === 'bundle' ? '请至少查询并加入一个有效 SKU' : '请输入 SKU 并查询商品' })
  if (p?.sku && (!p.rule || !p.country)) issues.push({ key:'primaryChannel', label:'首选渠道', message:'请完成物流试算并设置一条首选报价渠道' })
  if (!savedQuoteRows.value.length) issues.push({ key:'quoteChannels', label:'报价渠道', message:'请至少加入一条需要保存的报价渠道' })
  if (savedQuoteRows.value.some(row => !row.taxConfigured)) issues.push({ key:'taxPolicy', label:'税务设置', message:'存在不包税物流商尚未填写税率，请到财务设置补齐' })
  return issues
})
const displayedSaveValidationIssues = computed(() => showSaveValidation.value ? saveValidationIssues.value : [])
const displayedSaveBlockReason = computed(() => displayedSaveValidationIssues.value[0]?.message || '')
const displayedInvalidFields = computed(() => displayedSaveValidationIssues.value.map(issue => issue.key))
function attemptSave() {
  showSaveValidation.value = true
  if (saveValidationIssues.value.length) {
    toast(`暂时无法保存：还需完成 ${saveValidationIssues.value.length} 项必填内容`)
    return
  }
  save()
}
function locateValidationIssue(key: string) {
  const selector = ['customerName','productCategory','sku'].includes(key)
    ? `[data-validation-field="${key}"]`
    : key === 'taxPolicy'
      ? '.quote-preview'
      : '.matrix-mode-panel'
  const target = document.querySelector<HTMLElement>(selector)
  target?.scrollIntoView({ behavior:'smooth', block:'center' })
  window.setTimeout(() => target?.querySelector<HTMLElement>('input,select,button')?.focus(), 380)
}
async function copySpecifiedQuotes(rows: QuotationMatrixRow[]) {
  if (!rows.length) {
    toast('请先添加需要复制的指定报价渠道')
    return
  }
  const unit = quoteMode.value === 'bundle' ? '套' : '件'
  const values = [
    ['国家', '报价区域', '物流商', '运输渠道', '预计时效', '税务方式', '税率', '国家固定税费（USD）', `1${unit}（USD）`, `1${unit}（CNY）`, `2${unit}（USD）`, `2${unit}（CNY）`, `3${unit}（USD）`, `3${unit}（CNY）`, `${Math.max(1, customQuoteQuantity.value || 1)}${unit}（USD）`, `${Math.max(1, customQuoteQuantity.value || 1)}${unit}（CNY）`],
    ...rows.map(row => [
      row.country,
      row.quoteRegion || '全国统一',
      row.carrier,
      row.transport,
      row.eta,
      row.taxIncluded ? '包税' : row.taxConfigured ? '不包税' : '不包税（税率待设置）',
      row.taxRatePercent == null ? '' : `${row.taxRatePercent}%`,
      row.countryFixedTaxUsd ? row.countryFixedTaxUsd.toFixed(2) : '',
      row.quote1 == null ? '' : row.quote1.toFixed(2),
      row.quote1 == null ? '' : (row.quote1 * exchange.value.usd).toFixed(2),
      row.quote2 == null ? '' : row.quote2.toFixed(2),
      row.quote2 == null ? '' : (row.quote2 * exchange.value.usd).toFixed(2),
      row.quote3 == null ? '' : row.quote3.toFixed(2),
      row.quote3 == null ? '' : (row.quote3 * exchange.value.usd).toFixed(2),
      row.quoteCustom == null ? '' : row.quoteCustom.toFixed(2),
      row.quoteCustom == null ? '' : (row.quoteCustom * exchange.value.usd).toFixed(2),
    ]),
  ]
  const excelText = values
    .map(row => row.map(cell => String(cell).replace(/[\t\r\n]+/g, ' ')).join('\t'))
    .join('\r\n')
  try {
    await navigator.clipboard.writeText(excelText)
  } catch {
    const textarea = document.createElement('textarea')
    textarea.value = excelText
    textarea.style.position = 'fixed'
    textarea.style.opacity = '0'
    document.body.appendChild(textarea)
    textarea.select()
    document.execCommand('copy')
    textarea.remove()
  }
  const countryCount = new Set(rows.map(row => row.country)).size
  toast(`已复制 ${countryCount} 个国家、${rows.length} 条指定报价，打开 Excel 后按 Ctrl+V 粘贴`)
}
function useLogistics(p: Product, option: { country: string; quoteRegion?: string; rule: string; carrier: string; freight: number }) {
  if (option.country === '澳大利亚' && option.quoteRegion) selectedQuoteRegions.value = { ...selectedQuoteRegions.value, 澳大利亚: option.quoteRegion }
  p.country = option.country
  p.channel = option.carrier
  p.rule = option.rule
  p.freight = option.freight
  p.manualFreight = false
  p.status = '已试算'
  toast(`已采用“${option.rule}”，运费 ¥${option.freight.toFixed(2)}`)
}
function save() {
  const p = products.value[0]
  const customer = customerName.value.trim()
  const selectedMatrixRows = savedQuoteRows.value
  if (!customer) { toast('请先填写客户名称，再保存报价记录'); return }
  if (!productCategory.value) { toast('请选择产品品类，再保存报价记录'); return }
  if (!p?.sku || !p.rule || !p.country) { toast('请先查询商品并完成物流试算，再保存报价记录'); return }
  if (!selectedMatrixRows.length) { toast('请至少选择一条需要保存的报价渠道'); return }
  if (selectedMatrixRows.some(row => !row.taxConfigured)) { toast('存在不包税物流商尚未填写税率，请先到财务设置补齐'); return }
  const templateSnapshot = quoteMatrixMode.value === 'template' ? activeTemplateSnapshot.value : null
  const quoteOptions = selectedMatrixRows.map((row) => {
    const countryCode = quotationCountries(p).find(country => country.name === row.country)?.code
      || logisticsCountries.find(country => country.name === row.country)?.code
      || ''
    return {
      id: `${row.country}::${row.quoteRegion || '全国统一'}::${row.channelKey || `${row.ruleId}::${row.carrier}::${row.channelCode || row.transport}`}`,
      country: row.country,
      quoteRegion: row.quoteRegion,
      countryCode,
      carrier: row.carrier,
      channel: row.transport,
      rule: row.rule,
      eta: row.eta,
      channelKey: row.channelKey,
      ruleId: String(row.ruleId || ''),
      channelCode: row.channelCode,
      freightCny: row.freight,
      totalCostCny: row.totalCostCny,
      profitCny: row.profitCny,
      quoteCny: row.quoteCny,
      isPrimary: row.country === p.country && row.rule === p.rule && row.carrier === p.channel,
      quote1Usd: row.quote1,
      quote2Usd: row.quote2,
      quote3Usd: row.quote3,
      quoteCustomUsd: row.quoteCustom,
      taxIncluded: row.taxIncluded,
      taxConfigured: row.taxConfigured,
      taxRatePercent: row.taxRatePercent,
      countryFixedTaxUsd: row.countryFixedTaxUsd,
      taxLabel: row.taxLabel,
      tax1Usd: row.tax1Usd,
      tax2Usd: row.tax2Usd,
      tax3Usd: row.tax3Usd,
      taxCustomUsd: row.taxCustomUsd,
    }
  })
  localStorage.setItem('milano-quotation-draft', JSON.stringify({ quoteMode: quoteMode.value, quoteMatrixMode: quoteMatrixMode.value, quotationTemplate: templateSnapshot, products: products.value, bundleItems: bundleItems.value, quoteOptions, customQuoteQuantity: Math.max(1, customQuoteQuantity.value || 1), specifiedQuotes: selectedMatrixRows, exchange: exchange.value, selectedSalesperson: selectedSalesperson.value, selectedCustomerGrade: selectedCustomerGrade.value, customerName: customer, productCategory: productCategory.value, monthlySalesEstimate: monthlySalesEstimate.value }))
  const productSummary = quoteMode.value === 'bundle'
    ? bundleItems.value.filter(item => item.sku).map(item => `${item.sku} × ${item.quantityPerSet}`).join(' + ') || '组合 SKU'
    : p.name
  const record = createQuotationRecord({
    salespersonName: currentSalespersonName.value, salespersonAccount: currentSalespersonAccount.value,
    customerName: customer, quoteMode: quoteMode.value, productSummary,
    productImage: quoteMode.value === 'bundle' ? (bundleItems.value.find(item => item.image)?.image || '') : p.image,
    primarySku: quoteMode.value === 'bundle' ? bundleItems.value.filter(item => item.sku).map(item => item.sku).join('、') : p.sku,
    productCategory: productCategory.value,
    volumetricEnabled: quoteMode.value === 'single' && p.volumetricEnabled,
    packageLengthCm: quoteMode.value === 'single' && p.volumetricEnabled ? p.packageLengthCm : undefined,
    packageWidthCm: quoteMode.value === 'single' && p.volumetricEnabled ? p.packageWidthCm : undefined,
    packageHeightCm: quoteMode.value === 'single' && p.volumetricEnabled ? p.packageHeightCm : undefined,
    defaultVolumeDivisor: quoteMode.value === 'single' && p.volumetricEnabled ? 8000 : undefined,
    logisticsAttribute: p.logisticsAttribute, country: p.country, carrier: p.channel,
    channel: logisticsRules.find(rule => rule.name === p.rule)?.relations.find(relation => relation.carrier === p.channel)?.channel || p.rule,
    rule: p.rule, customerGrade: `${selectedCustomerGrade.value}级客户`, monthlySalesEstimate: monthlySalesEstimate.value, systemQuoteCny: finalSalePrice(p), systemQuoteUsd: usdPriceFromCny(finalSalePrice(p)), totalCostCny: totalCost(p), exchangeRate: exchange.value.usd,
    matrixMode: quoteMatrixMode.value,
    quotationTemplateId: templateSnapshot?.id,
    quotationTemplateName: templateSnapshot?.name,
    customQuoteQuantity: Math.max(1, customQuoteQuantity.value || 1),
    quoteOptions,
    specifiedQuotes: selectedMatrixRows.map(row => ({ country: row.country, quoteRegion: row.quoteRegion, carrier: row.carrier, channel: row.transport, rule: row.rule, eta: row.eta, quote1Usd: row.quote1, quote2Usd: row.quote2, quote3Usd: row.quote3, quoteCustomUsd: row.quoteCustom })),
  })
  toast(`报价已保存：${record.no}（1 张报价单 · ${savedQuoteCountryCount.value} 个国家 · ${selectedMatrixRows.length} 条渠道）`)
}
function toast(message: string) {
  notice.value = message
  window.setTimeout(() => { if (notice.value === message) notice.value = '' }, 2400)
}
if (initialPurchaseRecord?.status === '资料完整' && products.value[0].rule) calculateFreight(products.value[0], true)
</script>

<template>
  <div class="jerry-app">
    <header class="topbar">
      <RouterLink class="brand" to="/quotation"><span>M</span><div><strong>米莱诺报价</strong><small>MILANO PRICING ERP</small></div></RouterLink>
      <nav><RouterLink class="active" to="/quotation">业务报价</RouterLink><RouterLink to="/quotation/products">采购资料</RouterLink><RouterLink to="/quotation/logistics">物流规则</RouterLink><RouterLink to="/quotation/members">财务设置</RouterLink><RouterLink to="/quotation/my-records">我的报价记录</RouterLink><RouterLink to="/quotation/records">公司报价记录</RouterLink></nav>
      <div class="top-actions"><button class="icon-btn" aria-label="通知">●</button><span class="avatar">{{ currentUserAvatar }}</span><div><b>{{ currentSalespersonName }}</b><small>{{ currentSalespersonAccount }}</small></div></div>
    </header>

    <main class="quotation-page">
      <QuotationHeader :salesperson="selectedSalesperson" :rate="exchange.usd" :status="products[0]?.status || '待查询'" :mode-label="quoteMode === 'bundle' ? '组合 SKU 报价' : '单品 SKU 报价'" @show-rule="showRule=true" />

      <nav class="workflow" aria-label="报价流程"><span class="active"><i>1</i>填写报价条件</span><b>→</b><span><i>2</i>核对成本与重量</span><b>→</b><span><i>3</i>选择报价方式与渠道</span><b>→</b><span><i>4</i>确认并保存报价单</span></nav>

      <template v-for="p in products.slice(0,1)" :key="p.id">
        <QuotationCondition
          :mode="quoteMode" :sku-search="skuSearch" :customer-name="customerName" :product-category="productCategory" :product-categories="quotationProductCategories" :monthly-sales-estimate="monthlySalesEstimate" :attributes="quotationAttributeOptions" :logistics-attribute="p.logisticsAttribute" :invalid-fields="displayedInvalidFields"
          :grades="customerGradeSettings.filter(item=>item.enabled)" :grade="selectedCustomerGrade"
          :coefficient="selectedGradeCoefficient()" :salesperson="selectedSalesperson"
          @update:mode="changeQuoteMode"
          @update:sku-search="skuSearch=$event" @update:customer-name="customerName=$event" @update:product-category="productCategory=$event" @update:monthly-sales-estimate="changeMonthlySalesEstimate(p,$event)" @update:grade="selectedCustomerGrade=$event as CustomerGrade"
          @query="queryProduct" @update:logistics-attribute="p.logisticsAttribute=$event;normalizeRule(p)"
        />

        <section v-if="quoteMode === 'single'" class="cost-workbench">
          <ProductInfoCard :product="p" />
          <CostWeightPanel :product="p" :charge-weight="chargeWeight(p)" :product-cost="productCost(p)" :domestic-freight="domesticFreight(p)" :purchase-tier-label="monthlySalesTierLabel()" @weight-change="normalizeRule(p)" />
        </section>
        <section v-else class="cost-workbench">
          <BundleProductCard
            :items="bundleItems" :purchase-cost="bundlePurchaseCost(1)"
            :total-weight="bundleGoodsWeight(1)" :domestic-freight="bundleDomesticFreight(1)"
            @add="addBundleItem" @remove="removeBundleItem" @query="queryBundleItem"
            @quantity-change="updateBundleItemQuantity" @weight-change="updateBundleItemWeight"
          />
        </section>

        <section class="matrix-mode-switcher">
          <header><div><p>STEP 03 · QUOTATION MATRIX</p><h2>选择报价方式与渠道</h2></div><span>三种模式独立保留，模板按当前业务员账号管理</span></header>
          <nav aria-label="报价矩阵分类">
            <button :class="{ active:quoteMatrixMode==='common' }" :aria-pressed="quoteMatrixMode==='common'" @click="quoteMatrixMode='common'">
              <i>⚡</i><span><b>常用国家快速报价</b><small>日常报价 · 直接比较财务已授权的全部渠道</small></span><em>{{ activeCommonCountryCount }}个国家</em>
            </button>
            <button :class="{ active:quoteMatrixMode==='specified' }" :aria-pressed="quoteMatrixMode==='specified'" @click="quoteMatrixMode='specified'">
              <i>☷</i><span><b>指定国家与渠道报价</b><small>客户指定 · 自选国家并批量添加物流渠道</small></span><em>4国 / {{ specifiedQuoteRows.length }}渠道</em>
            </button>
            <button :class="{ active:quoteMatrixMode==='template' }" :aria-pressed="quoteMatrixMode==='template'" @click="quoteMatrixMode='template'">
              <i>▦</i><span><b>我的报价模板</b><small>个人常用组合 · 一键载入预设国家与渠道</small></span><em>{{ activeTemplateSnapshot ? `${templateQuoteRows.length}渠道` : '未应用' }}</em>
            </button>
          </nav>
        </section>

        <div v-show="quoteMatrixMode==='common'" class="matrix-mode-panel">
          <QuotationCommonMatrix
            :countries="activeQuotationCountries" :quote-rows-for-country="activeQuoteRowsForCountry" :context-key="activeQuoteMatrixContextKey"
            :adopted-country="p.country" :adopted-rule="p.rule" :adopted-carrier="p.channel" :exchange-rate="exchange.usd"
            :unit-label="quoteMode === 'bundle' ? '套' : '件'" :custom-quantity="customQuoteQuantity"
            @update:custom-quantity="customQuoteQuantity=Math.max(1,$event||1)" @selection-change="updateCommonQuotes" @country-order-change="reorderCommonCountries" @quote-region-change="changeQuoteRegion(p,$event)" @adopt="useLogistics(p,$event)" @copy="copySpecifiedQuotes"
          />
        </div>

        <div v-show="quoteMatrixMode==='specified'" class="matrix-mode-panel">
          <QuotationMatrix
            :countries="activeQuotationCountries" :quote-rows-for-country="activeQuoteRowsForCountry" :context-key="activeQuoteMatrixContextKey"
            :custom-quantity="customQuoteQuantity" :adopted-country="p.country" :adopted-rule="p.rule" :adopted-carrier="p.channel" :exchange-rate="exchange.usd"
            :unit-label="quoteMode === 'bundle' ? '套' : '件'"
            @update:custom-quantity="customQuoteQuantity=Math.max(1,$event||1)" @selection-change="updateSpecifiedQuotes" @quote-region-change="changeQuoteRegion(p,$event)" @adopt="useLogistics(p,$event)" @copy="copySpecifiedQuotes"
          />
        </div>

        <div v-show="quoteMatrixMode==='template'" class="matrix-mode-panel">
          <QuotationTemplateMatrix
            :countries="activeQuotationCountries" :quote-rows-for-country="activeQuoteRowsForCountry" :context-key="activeQuoteMatrixContextKey"
            :custom-quantity="customQuoteQuantity" :adopted-country="p.country" :adopted-rule="p.rule" :adopted-carrier="p.channel" :exchange-rate="exchange.usd"
            :owner-name="currentSalespersonName" :owner-account="currentSalespersonAccount"
            :unit-label="quoteMode === 'bundle' ? '套' : '件'"
            @update:custom-quantity="customQuoteQuantity=Math.max(1,$event||1)" @selection-change="updateTemplateQuotes" @template-change="updateActiveTemplate"
            @quote-region-change="changeQuoteRegion(p,$event)" @adopt="useLogistics(p,$event)" @copy="copySpecifiedQuotes"
          />
        </div>

        <QuotationPreviewSave
          :rows="savedQuoteRows" :matrix-mode-label="matrixModeLabel" :customer-name="customerName"
          :product-name="quoteMode === 'bundle' ? (bundleItems.filter(item=>item.sku).map(item=>item.name || item.sku).join(' + ') || '组合商品') : p.name"
          :sku="quoteMode === 'bundle' ? bundleItems.filter(item=>item.sku).map(item=>item.sku).join('、') : p.sku"
          :customer-grade="selectedCustomerGrade" :coefficient="selectedGradeCoefficient()"
          :custom-quantity="customQuoteQuantity" :unit-label="quoteMode === 'bundle' ? '套' : '件'" :exchange-rate="exchange.usd"
          :primary-country="p.country" :primary-carrier="p.channel" :primary-rule="p.rule"
          :primary-cny-price="finalSalePrice(p)" :primary-usd-price="usdPriceFromCny(finalSalePrice(p))"
          :block-reason="displayedSaveBlockReason" :validation-issues="displayedSaveValidationIssues" @copy="copySpecifiedQuotes" @locate-issue="locateValidationIssue" @save="attemptSave"
        />
      </template>
    </main>

    <div v-if="showRule || showHistory" class="modal-mask" @click.self="showRule = showHistory = false">
      <section class="modal">
        <button class="modal-close" @click="showRule = showHistory = false">×</button>
        <template v-if="showRule">
          <small>CALCULATION RULE</small><h2>报价计算规则</h2>
          <ol><li><b>物流属性</b><span>业务员在本次报价中统一选择普货、带电、纯电池、液体、粉末、非液体化妆品、带磁或微敏感；系统再匹配财务授权的国家与渠道</span></li><li><b>计费重量</b><span>前台统一以整克（g）展示和输入；单品采用采购资料重量或业务员指定重量，组合 SKU 采用各商品重量合计，不计包裹重量</span></li><li><b>采购成本</b><span>根据商品数量匹配采购资料中的阶梯采购单价；国内采购运费引用10件运费的单件分摊金额</span></li><li><b>物流运费</b><span>计费重量按物流规则的重量费、挂号费及特殊费用计算，本期不自动拆分多个包裹</span></li><li><b>最终报价</b><span>综合成本 × 财务维护的 S–E 客户等级计算系数</span></li></ol>
          <p class="modal-tip">美元价格按保存报价时的汇率快照换算，历史报价不会随新汇率自动改变。</p>
        </template>
        <template v-else>
          <small>QUOTATION HISTORY</small><h2>最近报价记录</h2>
          <div class="history"><p><b>SKU00022968 · 美国</b><span>¥58.43</span><small>管理员 · 今天 09:48</small></p><p><b>SKU00023107 · 德国</b><span>¥212.74</span><small>管理员 · 昨天 16:20</small></p><p><b>SKU00022968 · 法国</b><span>¥61.20</span><small>范国华 · 07-29 11:05</small></p></div>
        </template>
      </section>
    </div>
    <Transition name="toast"><div v-if="notice" class="toast">✓ {{ notice }}</div></Transition>
  </div>
</template>

<style scoped>
:global(body){margin:0}.jerry-app{--orange:#ff9d16;--ink:#17212b;--muted:#77818c;--line:#e4e8eb;min-height:100vh;background:#f4f6f8;color:var(--ink);font-family:Inter,"PingFang SC","Microsoft YaHei",sans-serif}.topbar{height:68px;display:flex;align-items:center;padding:0 4vw;background:#fff;border-bottom:1px solid var(--line);position:sticky;top:0;z-index:20}.brand{display:flex;align-items:center;gap:11px;margin-right:56px}.brand>span{width:39px;height:39px;display:grid;place-items:center;border-radius:10px;background:var(--orange);color:#171b20;font-size:21px;font-weight:950}.brand strong,.brand small,.top-actions small{display:block}.brand strong{font-size:17px}.brand small{font-size:8px;letter-spacing:.18em;color:#9199a2}.topbar nav{display:flex;align-items:center;gap:32px;height:100%}.topbar nav a{height:100%;display:flex;align-items:center;position:relative;color:#66717c;font-size:13px}.topbar nav a.active{color:var(--ink);font-weight:850}.topbar nav a.active:after{content:"";position:absolute;left:0;right:0;bottom:0;height:3px;background:var(--orange)}.top-actions{margin-left:auto;display:flex;align-items:center;gap:10px;font-size:12px}.top-actions small{color:#949da6}.icon-btn{border:0;background:none;color:var(--orange)}.avatar{width:35px;height:35px;display:grid;place-items:center;border-radius:50%;background:#1b2630;color:#fff;font-size:10px}.page{width:min(1560px,94vw);margin:auto;padding:34px 0 70px}.hero{display:flex;align-items:flex-end;justify-content:space-between;margin-bottom:24px}.hero p{margin:0 0 8px;color:#dc7b00;font-size:10px;font-weight:900;letter-spacing:.2em}.hero h1{margin:0 0 7px;font-size:30px}.hero>div>span{color:var(--muted);font-size:13px}.hero-stats{display:flex;align-items:center;gap:24px}.hero-stats>div{display:grid;gap:4px}.hero-stats small{color:#98a0a8;font-size:9px}.hero-stats b{font-size:12px}.hero-stats em{color:#9da5ad;font-size:9px;font-style:normal}.primary,.dark{height:40px;padding:0 20px;border:0;border-radius:7px;background:var(--orange);font-weight:850}.notice{display:grid;grid-template-columns:30px 1fr auto;gap:12px;align-items:center;padding:15px 18px;background:#fff8e9;border:1px solid #f2ddb8;border-radius:8px}.notice-icon{width:25px;height:25px;display:grid;place-items:center;border-radius:50%;background:var(--orange);font-weight:900}.notice b{font-size:12px}.notice p{display:inline;margin-left:12px;color:#776f61;font-size:11px}.notice button{border:0;background:none;color:#b36900;font-size:11px}.member-bar{display:flex;align-items:center;gap:18px;margin-top:14px;padding:12px 16px;background:#fff;border:1px solid var(--line);border-radius:8px}.member-bar>div{display:flex;align-items:center;gap:10px}.member-bar small{color:#929ba4}.member-bar select{min-width:190px;height:34px;border:1px solid #dce1e5;border-radius:6px;padding:0 10px}.member-bar>span{color:#87919b;font-size:11px}.member-bar .outline:first-of-type{margin-left:auto}.outline,.soft{height:36px;border:1px solid #dce1e5;border-radius:6px;background:#fff;padding:0 13px;font-size:11px}.toolbar{display:flex;align-items:center;gap:9px;margin:20px 0 11px}.search{width:270px;height:39px;display:flex;align-items:center;gap:8px;padding:0 12px;background:#fff;border:1px solid #dce1e5;border-radius:7px}.search input{width:100%;border:0;outline:0;font-size:11px}.toolbar>select{height:39px;border:1px solid #dce1e5;border-radius:7px;background:#fff;padding:0 12px}.batch{height:39px;display:flex;align-items:center;background:#fff;border:1px solid #dce1e5;border-radius:7px;overflow:hidden;font-size:10px}.batch>span{padding:0 10px;color:#737d86}.batch input{width:42px;height:100%;border:0;border-left:1px solid #e5e8eb;text-align:center;outline:0}.batch i{font-style:normal}.batch button{height:100%;border:0;margin-left:6px;background:#f0f3f5;font-size:10px}.toolbar .dark{margin-left:auto;background:#1b2630;color:#fff}.quote-list{background:#fff;border:1px solid var(--line);border-radius:9px;overflow:hidden;box-shadow:0 14px 35px rgba(27,38,48,.05)}.list-head{height:47px;display:flex;align-items:center;gap:18px;padding:0 18px;background:#fafbfc;border-bottom:1px solid var(--line);color:#69747e;font-size:11px}.list-head label{color:#26313a;font-weight:700}.list-head button{margin-left:auto;border:0;background:none;color:#c97600}.list-head div{margin-left:6px}.list-head div i,.status i{width:7px;height:7px;display:inline-block;border-radius:50%;background:#23ad6a;margin-right:7px}.product{border-left:3px solid transparent;border-bottom:1px solid var(--line)}.product.selected{border-left-color:var(--orange)}.product-head{min-height:70px;display:grid;grid-template-columns:18px 42px minmax(260px,1fr) auto auto auto;gap:12px;align-items:center;padding:10px 16px}.thumb{width:40px;height:40px;display:grid;place-items:center;overflow:hidden;border-radius:8px;background:linear-gradient(135deg,#eaf0f2,#cddfe0);color:#447374;font-weight:900}.thumb img{width:100%;height:100%;object-fit:cover}.product-name>input{width:100%;border:0;outline:0;background:transparent;font-weight:800}.product-name p{display:flex;gap:7px;margin:6px 0 0}.product-name code,.product-name p span{padding:3px 7px;border-radius:4px;background:#f1f3f5;color:#76818a;font-size:9px}.status{font-size:10px;color:#218a59}.status.warning{color:#b97400}.status.warning i{background:#e6a11c}.plain{height:31px;border:1px solid #e0e4e7;border-radius:5px;background:#fff;color:#66727c;font-size:10px}.remove{border:0;background:none;color:#9aa2a9;font-size:20px}.pricing{display:grid;grid-template-columns:1fr 1fr 276px;margin:0 16px 16px;border:1px solid var(--line);border-radius:7px;overflow:hidden}.panel{padding:16px}.panel+.panel{border-left:1px solid var(--line)}.panel h3{display:flex;align-items:center;gap:8px;margin:0 0 13px;font-size:11px}.panel h3 b{width:22px;height:22px;display:grid;place-items:center;border-radius:5px;background:#eef1f3;color:#6d7881;font-size:9px}.field-grid{display:grid;gap:9px}.field-grid.three{grid-template-columns:repeat(3,1fr)}.field-grid.two{grid-template-columns:repeat(2,1fr)}.field-grid .wide{grid-column:1/-1}.field-grid label{display:grid;gap:5px;color:#7b858e;font-size:9px}.field-grid input,.field-grid select{width:100%;height:33px;border:1px solid #dde2e5;border-radius:5px;padding:0 8px;background:#fff;color:#263039;font-size:11px;outline:0}.field-grid input:focus,.field-grid select:focus{border-color:#f2a029;box-shadow:0 0 0 3px rgba(255,157,22,.1)}.suffix{display:flex}.suffix input{border-radius:5px 0 0 5px}.suffix span{width:31px;height:33px;display:grid;place-items:center;border:1px solid #dde2e5;border-left:0;border-radius:0 5px 5px 0;background:#f4f6f7}.weight-summary{display:flex;align-items:center;gap:9px;margin-top:11px;padding:8px 10px;background:#f7f9fa;border-radius:5px;font-size:9px}.weight-summary b{font-size:12px}.weight-summary small{margin-left:auto;color:#98a0a8}.freight-row{display:flex;align-items:center;justify-content:space-between;margin-top:10px;padding-top:10px;border-top:1px dashed #dfe3e6;font-size:9px}.freight-row>div{display:flex;align-items:center}.freight-row>div span{margin-right:7px;color:#727d86}.freight-row>div input{width:70px;height:31px;border:1px solid #dce1e5;padding:0 7px}.freight-row button{height:31px;border:0;background:#1b2630;color:#fff;font-size:9px}.result{padding:18px;background:#1b2630;color:#fff}.result header{display:flex;justify-content:space-between}.result header span{font-size:10px}.result header small{max-width:145px;color:#9fa9b2;font-size:8px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.result>strong{display:block;margin:7px 0 14px;color:#ffad38;font-size:24px}.currencies{display:grid;grid-template-columns:1fr 1fr;gap:6px}.currencies p{display:grid;gap:2px;margin:0;padding:8px;background:#27333e;border-radius:5px}.currencies span{color:#9fa9b2;font-size:8px}.currencies b{font-size:11px}.result dl{margin:12px 0 0;padding:10px 0;border-top:1px solid #37434d;border-bottom:1px solid #37434d}.result dl div{display:flex;justify-content:space-between;margin:4px 0}.result dt{color:#9fa9b2;font-size:9px}.result dd{margin:0;font-size:10px}.green{color:#58d896}.result footer{margin-top:12px}.result footer>div{display:flex;align-items:center;justify-content:space-between;color:#aab2ba;font-size:9px}.member-margin{display:flex}.member-margin input{width:35px;height:25px;border:0;border-radius:4px 0 0 4px;text-align:center}.member-margin i{width:22px;height:25px;display:grid;place-items:center;background:#3a4650;font-style:normal}.result footer p{display:flex;justify-content:space-between;margin:10px 0 0;color:#b9c0c6;font-size:9px}.result footer p b{color:#fff;font-size:13px}.empty{padding:70px;text-align:center;color:#929ba4}.modal-mask{position:fixed;inset:0;z-index:100;display:grid;place-items:center;background:rgba(18,25,32,.5);backdrop-filter:blur(4px)}.modal{position:relative;width:min(540px,92vw);padding:32px;background:#fff;border-radius:12px;box-shadow:0 24px 70px rgba(0,0,0,.25)}.modal-close{position:absolute;right:18px;top:16px;border:0;background:none;font-size:22px}.modal>small{color:#da7b00;font-weight:800;letter-spacing:.18em}.modal h2{margin:8px 0 22px}.modal ol{display:grid;gap:10px;padding:0;list-style:none}.modal li{display:grid;grid-template-columns:90px 1fr;padding:12px;background:#f6f8f9;border-radius:6px;font-size:11px}.modal li span{color:#6e7881}.modal-tip{padding:12px;background:#fff7e8;color:#746b5d;font-size:10px;line-height:1.6}.history p{display:grid;grid-template-columns:1fr auto;gap:5px;padding:12px 0;margin:0;border-bottom:1px solid var(--line)}.history span{color:#d97900;font-weight:800}.history small{grid-column:1/-1;color:#929ba4}.toast{position:fixed;right:24px;bottom:24px;z-index:120;padding:13px 18px;border-radius:8px;background:#1b2630;color:#fff;box-shadow:0 12px 30px rgba(0,0,0,.2);font-size:11px}.toast-enter-active,.toast-leave-active{transition:.2s}.toast-enter-from,.toast-leave-to{opacity:0;transform:translateY(8px)}
@media(max-width:1100px){.topbar nav{display:none}.pricing{grid-template-columns:1fr 1fr}.result{grid-column:1/-1}.hero{align-items:flex-start;gap:20px}.hero-stats{flex-wrap:wrap;justify-content:flex-end}.member-bar>span{display:none}}
@media(max-width:760px){.page{width:94vw;padding-top:22px}.top-actions>div{display:none}.brand{margin-right:0}.hero{display:block}.hero-stats{margin-top:16px;justify-content:flex-start}.hero-stats>div{display:none}.notice{grid-template-columns:30px 1fr}.notice button{grid-column:2;text-align:left}.member-bar{flex-wrap:wrap}.member-bar .outline:first-of-type{margin-left:0}.toolbar{flex-wrap:wrap}.search{width:100%}.toolbar .dark{margin-left:0}.product-head{grid-template-columns:18px 38px 1fr auto}.status{grid-column:3}.plain{grid-column:3}.pricing{grid-template-columns:1fr}.panel+.panel{border-left:0;border-top:1px solid var(--line)}.result{grid-column:auto}.field-grid.three,.field-grid.two{grid-template-columns:repeat(2,1fr)}.list-head button{display:none}.list-head div{margin-left:auto}.weight-summary small{display:none}}
.toolbar-hint{margin-left:12px;color:#7f8992;font-size:11px}.product-head{grid-template-columns:42px minmax(260px,1fr) auto}.product-name code input{width:100px;border:0;background:transparent;color:#76818a;font:inherit}.logistics-match{margin:0 16px 18px;border:1px solid #e2e7ea;border-radius:7px;overflow:hidden}.logistics-match>header{display:flex;align-items:center;justify-content:space-between;padding:14px 16px;background:#fafbfc;border-bottom:1px solid #e5e9ec}.logistics-match h3{margin:3px 0 0;font-size:14px}.logistics-match small{color:#dc7b00;font-size:8px;font-weight:900;letter-spacing:.16em}.logistics-match>header>span{color:#7b858e;font-size:10px}.match-table{overflow:auto}.match-table table{width:100%;border-collapse:collapse}.match-table th,.match-table td{padding:10px 12px;border-bottom:1px solid #edf0f2;text-align:left;font-size:10px;white-space:nowrap}.match-table th{background:#fff;color:#7a858e}.match-table tr.adopted{background:#fff8e9}.match-table strong{color:#d87900}.match-table button{height:28px;padding:0 12px;border:0;border-radius:5px;background:#1b2630;color:#fff;font-size:9px}.match-table tr.adopted button{background:#ff9d16;color:#17212b}.rank{width:22px;height:22px;display:grid;place-items:center;border-radius:50%;background:#eef1f3}.match-table tr:first-child .rank{background:#ff9d16;color:#17212b}.no-match{padding:30px;text-align:center;color:#8a949d;font-size:11px}
.source-note{margin:-4px 0 10px;padding:7px 9px;border-radius:5px;background:#eef7f9;color:#52727a;font-size:9px}.field-grid input:disabled,.field-grid select:disabled{background:#f3f5f6;color:#65717b;cursor:not-allowed}
.match-pagination{display:flex;align-items:center;justify-content:flex-end;gap:5px;padding:10px 12px;background:#fafbfc;border-top:1px solid #e5e9ec}.match-pagination span{margin:0 6px;color:#7b858e;font-size:9px}.match-pagination select{height:28px;border:1px solid #dce1e5;border-radius:4px;background:#fff}.match-pagination button{min-width:30px;height:28px;padding:0 8px;border:1px solid #dce1e5;border-radius:4px;background:#fff;color:#56616b;font-size:9px}.match-pagination button.active{border-color:#ff9d16;background:#ff9d16;color:#17212b;font-weight:800}.match-pagination button:disabled{opacity:.4;cursor:not-allowed}
.match-actions{display:flex;align-items:center;gap:10px}.match-actions>span{color:#7b858e;font-size:10px}.match-actions label{display:flex;align-items:center;gap:5px;color:#58636d;font-size:10px}.match-actions input{width:58px;height:30px;border:1px solid #cfd8cf;border-radius:4px;padding:0 7px;background:#fff;text-align:center}.match-actions button{height:31px;padding:0 14px;border:0;border-radius:5px;background:#2f6f3e;color:#fff;font-size:10px;font-weight:800}.excel-matrix table{table-layout:fixed}.excel-matrix th{border:1px solid #aebda8;background:#cfe3c1;color:#1e2c1c;text-align:center;font-size:11px}.excel-matrix td{border:1px solid #d5ddd2;text-align:center;font-size:11px}.excel-matrix th:nth-child(1){width:12%}.excel-matrix th:nth-child(2){width:34%}.excel-matrix th:nth-child(n+3):nth-child(-n+6){width:11%}.excel-matrix th:last-child{width:10%}.excel-matrix td:nth-child(2){text-align:left;white-space:normal}.excel-matrix td:nth-child(2) b,.excel-matrix td:nth-child(2) small{display:block}.excel-matrix td:nth-child(2) small{margin-top:3px;color:#8b958d;font-size:8px}.excel-matrix td:nth-child(n+3):nth-child(-n+6){font-weight:800;font-variant-numeric:tabular-nums}.excel-matrix .custom-price{background:#eff8e8;color:#236332}.excel-matrix tr.adopted{background:#fff7df}
.result{background:#17232d;color:#f5f8fa;border-left:1px solid #0f1921}.result header span{color:#f3f7fa;font-weight:800}.result header small{color:#a7b5c0}.result>strong{color:#ffab32;font-size:28px}.currencies{grid-template-columns:1fr}.currencies p{padding:11px 12px;background:#263743;border:1px solid #405765;box-shadow:inset 3px 0 #2f9ed1}.currencies span{color:#b7c7d1;font-size:9px}.currencies b{color:#ffffff;font-size:22px;font-weight:900;letter-spacing:.02em}.result dl{border-color:#34434e}.result dt{color:#a5b2bc}.result dd{color:#ffffff;font-weight:800}.result .green{color:#54d698}.result footer>div{color:#adb9c2}.result footer p{color:#b4c0c8}.result footer p b{color:#ffffff}.member-margin input{background:#eef3f6;color:#24333e}.member-margin i{background:#354652;color:#dbe5eb}
.member-bar{flex-wrap:wrap}.member-bar>div+div{padding-left:18px;border-left:1px solid #e1e6e9}.original-price{margin:-8px 0 10px;color:#aebbc4;font-size:9px;text-decoration:none}.result>strong+.original-price{display:block}.result>strong+.original-price::first-line{color:#aebbc4}
.fee-detail{color:#455560}.fee-detail small{display:block;margin-top:3px;color:#87939c;font-size:8px}
.product-name p{flex-wrap:wrap}.product-name p .shipping-mark{background:#fff0d8;color:#a35e00;font-weight:800}
.quote-conditions{margin:14px 0 12px;padding:15px 16px;border:1px solid #e0e5e9;border-top:3px solid var(--orange);border-radius:9px;background:#fff;box-shadow:0 8px 24px rgba(25,38,49,.045)}.quote-conditions>header{display:flex;align-items:center;justify-content:space-between;gap:18px;margin-bottom:13px;padding-bottom:11px;border-bottom:1px solid #edf0f2}.quote-conditions>header>div{display:grid;gap:3px}.quote-conditions>header b{font-size:13px}.quote-conditions>header span{color:#8b959e;font-size:9px}.quote-conditions>header p{display:flex;align-items:center;gap:8px;margin:0}.quote-conditions>header p strong{font-size:11px}.quote-conditions>header p small{padding:3px 7px;border-radius:10px;background:#eef6f1;color:#258155;font-size:8px}.condition-grid{display:grid;grid-template-columns:minmax(250px,1.35fr) repeat(4,minmax(145px,1fr));gap:10px}.condition-grid>label{display:grid;align-content:start;gap:5px;min-width:0;color:#69757f;font-size:9px}.condition-grid select,.condition-grid input{width:100%;height:36px;box-sizing:border-box;border:1px solid #d9e0e5;border-radius:6px;background:#fff;padding:0 9px;color:#26323b;font-size:11px;outline:0}.condition-grid select:focus,.condition-grid input:focus{border-color:#f2a029;box-shadow:0 0 0 3px rgba(255,157,22,.1)}.condition-grid label>small{color:#a16a1c;font-size:8px}.sku-condition>div{position:relative;display:flex}.sku-condition i{position:absolute;left:10px;top:50%;transform:translateY(-50%);color:#73808a;font-style:normal}.sku-condition input{padding-left:29px}.condition-actions{display:flex;align-items:center;justify-content:flex-end;gap:15px;margin-top:13px;padding-top:12px;border-top:1px solid #edf0f2}.condition-actions span{color:#8b959e;font-size:9px}.condition-actions button{height:36px;min-width:110px;background:#1b2630;color:#fff;font-size:10px}@media(max-width:1180px){.condition-grid{grid-template-columns:repeat(3,minmax(0,1fr))}.sku-condition{grid-column:span 2}}@media(max-width:760px){.quote-conditions>header{align-items:flex-start;flex-direction:column}.quote-conditions>header p{flex-wrap:wrap}.condition-grid{grid-template-columns:1fr 1fr}.sku-condition{grid-column:1/-1}.condition-actions{justify-content:space-between}}@media(max-width:520px){.condition-grid{grid-template-columns:1fr}.sku-condition{grid-column:auto}.condition-actions{align-items:stretch;flex-direction:column}.condition-actions button{width:100%}}
.quotation-page{width:min(1440px,94vw);display:grid;gap:24px;margin:0 auto;padding:28px 0 72px}.workflow{display:flex;align-items:center;justify-content:center;gap:13px;padding:12px 18px;border:1px solid #e4e9ee;border-radius:10px;background:#fff;box-shadow:0 5px 18px rgba(17,24,39,.035);color:#78848e;font-size:11px}.workflow span{display:flex;align-items:center;gap:7px;white-space:nowrap}.workflow span i{width:22px;height:22px;display:grid;place-items:center;border-radius:50%;background:#eef2f5;color:#6e7a84;font-size:9px;font-style:normal;font-weight:800}.workflow .active{color:#9e5c00;font-weight:800}.workflow .active i{background:#ff9900;color:#17212b}.workflow b{color:#c5cdd4;font-weight:400}.cost-workbench{display:grid;gap:24px;min-width:0}.modal{font-size:13px}.toast{font-size:12px}@media(max-width:1180px){.workflow{justify-content:flex-start;overflow-x:auto}}@media(max-width:680px){.quotation-page{width:94vw;gap:16px;padding-top:18px}.workflow{display:none}}
.matrix-mode-switcher{overflow:hidden;border:1px solid #dfe6eb;border-radius:12px;background:#fff;box-shadow:0 10px 28px rgba(20,34,45,.05)}.matrix-mode-switcher>header{display:flex;align-items:center;justify-content:space-between;gap:18px;padding:18px 21px;border-bottom:1px solid #e5eaee}.matrix-mode-switcher>header p{margin:0 0 4px;color:#d97800;font-size:9px;font-weight:900;letter-spacing:.15em}.matrix-mode-switcher>header h2{margin:0;font-size:19px}.matrix-mode-switcher>header>span{color:#77848e;font-size:10px}.matrix-mode-switcher>nav{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:10px;padding:14px 18px;background:#f7f9fb}.matrix-mode-switcher>nav button{display:flex;align-items:center;gap:11px;min-height:66px;padding:11px 13px;border:1px solid #dce4e9;border-radius:9px;background:#fff;color:#17232d;text-align:left;cursor:pointer}.matrix-mode-switcher>nav button.active{border-color:#ff9700;background:#fff6e8;box-shadow:inset 3px 0 #ff9700,0 0 0 2px rgba(255,151,0,.06)}.matrix-mode-switcher>nav button>i{width:36px;height:36px;display:grid;place-items:center;flex:0 0 36px;border-radius:9px;background:#f0f3f5;color:#d77900;font-size:16px;font-style:normal}.matrix-mode-switcher>nav button.active>i{background:#ffedd0}.matrix-mode-switcher>nav button>span{display:grid;gap:3px;min-width:0}.matrix-mode-switcher>nav button b{font-size:13px}.matrix-mode-switcher>nav button small{overflow:hidden;color:#77848e;font-size:9px;text-overflow:ellipsis;white-space:nowrap}.matrix-mode-switcher>nav button em{margin-left:auto;padding:5px 8px;border-radius:12px;background:#f0f3f5;color:#65737e;font-size:9px;font-style:normal;white-space:nowrap}.matrix-mode-switcher>nav button.active em{background:#ff9700;color:#fff}.matrix-mode-panel{min-width:0}@media(max-width:1000px){.matrix-mode-switcher>nav{grid-template-columns:1fr 1fr}}@media(max-width:760px){.matrix-mode-switcher>header{align-items:flex-start;flex-direction:column}.matrix-mode-switcher>nav{grid-template-columns:1fr}.matrix-mode-switcher>nav button small{white-space:normal}}
</style>
