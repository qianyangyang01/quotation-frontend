<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import FilterPanel from '@/components/finance/FilterPanel.vue'
import LogisticsCard, { type ProviderGroup } from '@/components/finance/LogisticsCard.vue'
import PurchaseDataWorkspace from '@/components/purchase/PurchaseDataWorkspace.vue'
import {
  purchaseDisplayName,
  purchaseFreightChoices,
  purchaseImportMeta,
  purchaseUnitPrice,
  savePurchaseProducts,
  type PurchaseProductRecord,
} from '@/data/purchaseStore'
import {
  COMMON_COUNTRY_LIMIT,
  channelsAvailableForCountry,
  countriesAvailableForCategory,
  financeLogisticsAttributeOptions,
  saveCustomerGradeSettings,
  saveFinanceExchangeRate,
  saveFinanceChannelPolicies,
  saveFinanceCountrySettings,
  type CustomerGradeSetting,
  type FinanceCountryChannelRule,
  type FinanceChannelPolicy,
  type FinanceCountrySetting,
  type FinanceExchangeRateSetting,
  type FinanceLogisticsAttribute,
} from '@/data/financeChannelPolicies'
import {
  defaultCountrySortOrder,
  defaultCountryStage,
  inferCountryContinent,
  type CountryStage,
} from '@/data/countryClassification'
import {
  saveFinanceTaxSettings,
  type FinanceCountryTaxSetting,
  type FinanceProviderTaxSetting,
  type FinanceTaxSettings,
  type LogisticsTaxMode,
} from '@/data/financeTaxSettings'
import { loadPublishedLogisticsManifest, loadPublishedLogisticsRuleCatalog } from '@/data/publishedLogisticsRepository'
import { ApiError } from '@/services/http'
import {
  loadFinanceSettingsWorkspace,
  readFinanceSettingsWorkspace,
  type FinanceSettingsWorkspace,
} from '@/services/financeSettingsWorkspace'

const props = defineProps<{ mode: 'products' | 'logistics' | 'members' | 'history' }>()
const showPurchaseWorkspace = computed(() => props.mode === 'products')
const search = ref('')
const notice = ref('')
const showEditor = ref(false)
const previewImage = ref('')
const previewImageAlt = ref('')
const detailProduct = ref<ProductRow | null>(null)
const logisticsTab = ref('运费规则')
const productPage = ref(1)
const productPageSize = ref(20)
const editingSourceRow = ref<number | null>(null)
const editingFinancePolicyId = ref<string | null>(null)
const financePolicies = ref<FinanceChannelPolicy[]>([])
const financeCountrySettings = ref<FinanceCountrySetting[]>([])
const customerGradeSettings = ref<CustomerGradeSetting[]>([])
const financeExchangeRate = ref<FinanceExchangeRateSetting>({ usdCny: 0, updatedAt: '' })
const financeTaxSettings = ref<FinanceTaxSettings>({ countries: [], providers: [], updatedAt: '' })
const financeSettingsLoadState = ref<'loading' | 'ready' | 'error'>(props.mode === 'members' ? 'loading' : 'ready')
const financeSettingsLoadError = ref('')
const financeLogisticsContextState = ref<'loading' | 'ready' | 'error'>('loading')
const financeLogisticsContextError = ref('')
const financeLogisticsDataEpoch = ref(0)
const financePolicySaving = ref(false)
let financeContextRequest: Promise<boolean> | null = null
let financeEditorRequestId = 0
const financeChannelCache = new Map<string, ReturnType<typeof channelsAvailableForCountry>>()
const financeCarrierCache = new Map<string, Array<{ carrier: string; channels: ReturnType<typeof channelsAvailableForCountry> }>>()
type FinanceSettingsTab = 'countries' | 'logistics' | 'grades' | 'exchange' | 'taxes'
const FINANCE_TAB_ORDER_STORAGE_KEY = 'milano.finance-settings-card-order.v1'
const defaultFinanceTabOrder: FinanceSettingsTab[] = ['countries', 'logistics', 'grades', 'exchange', 'taxes']
function loadFinanceTabOrder(): FinanceSettingsTab[] {
  if (typeof window === 'undefined') return [...defaultFinanceTabOrder]
  try {
    const stored = JSON.parse(window.localStorage.getItem(FINANCE_TAB_ORDER_STORAGE_KEY) || '[]') as FinanceSettingsTab[]
    const valid = stored.filter((item, index) => defaultFinanceTabOrder.includes(item) && stored.indexOf(item) === index)
    return [...valid, ...defaultFinanceTabOrder.filter(item => !valid.includes(item))]
  } catch {
    return [...defaultFinanceTabOrder]
  }
}
const financeSettingsTab = ref<FinanceSettingsTab>('countries')
const financeTabOrder = ref<FinanceSettingsTab[]>(loadFinanceTabOrder())
const draggedFinanceTab = ref<FinanceSettingsTab | ''>('')
const dragOverFinanceTab = ref<FinanceSettingsTab | ''>('')
const financeTaxCountrySearch = ref('')
const financeTaxProviderSearch = ref('')
const financeTaxCountryAdd = ref('')
const financeTaxProviderAdd = ref('')
const financeTaxCountryAddOpen = ref(false)
const financeTaxProviderAddOpen = ref(false)
const financeTaxCountryAddSearch = ref('')
const financeTaxProviderAddSearch = ref('')
const financeCountrySettingSearch = ref('')
const countryPickerStage = ref<CountryStage | null>(null)
const countryPickerSearch = ref('')
const countryPickerSelected = ref<string[]>([])
const draggedFinanceCountry = ref('')
const dragOverFinanceCountry = ref('')
const financeFilterSearch = ref('')
const financeFilterStatus = ref('')
const financeFilterCountry = ref('')
const financeFilterProvider = ref('')
const financeAttributeInput = ref<HTMLInputElement | null>(null)
const financeAttributePickerOpen = ref(false)
const financeAttributePickerTyping = ref(false)
const openFinanceCountryPicker = ref<number | null>(null)
const financeCountryPickerTyping = ref(false)
const financeCountrySearches = ref<string[]>([])
const expandedFinanceCountryRules = ref<number[]>([])
const reviewingLegacyCountryRules = ref<number[]>([])
const financeSelectedCarriers = ref<string[]>([])
const priorityFinanceCountryNames = ['美国', '英国', '法国', '澳大利亚']
const emptyFinancePolicyForm = () => ({ category: '普货' as FinanceLogisticsAttribute, countryRules: [] as FinanceCountryChannelRule[], enabled: true })
const financePolicyForm = ref(emptyFinancePolicyForm())
const filteredFinanceAttributeOptions = computed(() => {
  if (!financeAttributePickerTyping.value) return [...financeLogisticsAttributeOptions]
  const query = financePolicyForm.value.category.trim().toLowerCase()
  return financeLogisticsAttributeOptions.filter(attribute => !query || attribute.toLowerCase().includes(query))
})
const financeLogisticsCountries = computed(() => {
  void financeLogisticsDataEpoch.value
  return [...countriesAvailableForCategory(financePolicyForm.value.category)].sort((a, b) => {
  const aPriority = priorityFinanceCountryNames.indexOf(a.name)
  const bPriority = priorityFinanceCountryNames.indexOf(b.name)
  if (aPriority >= 0 || bPriority >= 0) return (aPriority < 0 ? Number.MAX_SAFE_INTEGER : aPriority) - (bPriority < 0 ? Number.MAX_SAFE_INTEGER : bPriority)
  return a.name.localeCompare(b.name, 'zh-CN')
})
})
const financeCountrySettingMap = computed(() => new Map(financeCountrySettings.value.map(setting => [setting.country, setting])))
function financeCountryStageDisplay(country: string) {
  const setting = financeCountrySettingMap.value.get(country)
  return setting?.enabled && setting.stage === 'common' ? '常用国家' : '未加入常用'
}
function countrySettingsForStage(stage: CountryStage) {
  const query = financeCountrySettingSearch.value.trim().toLowerCase()
  return financeCountrySettings.value
    .filter(setting => setting.enabled && setting.stage === stage && (!query || `${setting.country} ${setting.code} ${setting.continent}`.toLowerCase().includes(query)))
    .sort((a, b) => a.sortOrder - b.sortOrder || a.country.localeCompare(b.country, 'zh-CN'))
}
const commonCountrySettings = computed(() => countrySettingsForStage('common'))
const countryPickerOptions = computed(() => {
  const query = countryPickerSearch.value.trim().toLowerCase()
  return financeCountrySettings.value
    .filter(setting => !query || `${setting.country} ${setting.code} ${setting.continent}`.toLowerCase().includes(query))
    .sort((a, b) => Number(b.enabled && b.stage === countryPickerStage.value) - Number(a.enabled && a.stage === countryPickerStage.value)
      || a.country.localeCompare(b.country, 'zh-CN'))
})
const emptyProductForm = () => ({
  invoiceInfo: '', sku: '', name: '', quotationOwner: '', quotationDate: new Date().toISOString().slice(0, 10),
  notes: '', image: '', stockStatus: '待确认' as PurchaseProductRecord['stockStatus'], weightDescription: '', weightG: 0, size: '', colorSku: '', material: '',
  minOrderQty: 1, rawTierPrice: '', l6Price: '', freightTrial: '', singleFreightCny: null as number | null,
  freight10Cny: null as number | null, freight100Cny: null as number | null,
  purchasePriceCny: 0, category: '', taxIncludedPrice: '', taxPoint: '', invoiceType: '', factoryInfo: '',
  auditNotes: '', sourceLinks: ['', '', '', ''], otherNotes: '', more: '',
})
const productForm = ref(emptyProductForm())

const config = computed(() => ({
  products: ['PURCHASE DATA CENTER', '采购资料维护', '采购统一上传和维护 SKU、图片、重量及数量阶梯成本。', '新增采购资料'],
  logistics: ['LOGISTICS CONFIGURATION', '物流规则', '配置物流商、渠道、国家区域、重量限制与分段运费规则。', '新增运费规则'],
  members: ['FINANCE PRICING POLICY', '财务报价设置', '维护常用国家、客户等级、汇率和物流渠道授权。', '新增物流属性策略'],
  history: ['QUOTATION AUDIT', '报价记录', '保留每次报价的成本、汇率、物流规则、操作人和版本快照。', '导出记录'],
}[props.mode]))

type ProductRow = any
const products = ref<ProductRow[]>([])
const logistics = ref([
  { name: '云途全球专线', carrier: '云途物流', type: '专线', countries: '美国、加拿大、欧洲六国', weight: '10–30,000 g', price: '基础费 ¥16 + ¥68/1000g', status: '启用' },
  { name: '燕文航空挂号', carrier: '燕文物流', type: '挂号', countries: '全球常用国家', weight: '10–2,000 g', price: '基础费 ¥12 + ¥73/1000g + 挂号费 ¥8', status: '启用' },
  { name: '顺邮宝挂号', carrier: '顺友物流', type: '挂号', countries: '欧洲、日本', weight: '10–2,000 g', price: '基础费 ¥13.5 + ¥66/1000g + 挂号费 ¥7', status: '启用' },
  { name: '4PX全球小包', carrier: '4PX（新版）', type: '小包', countries: '全球常用国家', weight: '10–5,000 g', price: '基础费 ¥18 + ¥62/1000g + 附加费 ¥4', status: '草稿' },
])
const history = ref([
  { no: 'QT202607310018', sku: 'SKU00022968', country: '美国', member: '范国华（U000005）', rule: '云途全球专线', cost: 46.16, quote: 55.61, rate: 'USD 7.24 / EUR 7.86', operator: '管理员', time: '2026-07-31 09:48', status: '已保存' },
  { no: 'QT202607300064', sku: 'SKU00023107', country: '德国', member: '普通报价', rule: '燕文航空挂号', cost: 153.68, quote: 202.21, rate: 'USD 7.24 / EUR 7.86', operator: '管理员', time: '2026-07-30 16:20', status: '已保存' },
  { no: 'QT202607290031', sku: 'SKU00022968', country: '法国', member: '核心会员 A', rule: '顺邮宝挂号', cost: 48.40, quote: 59.02, rate: 'USD 7.23 / EUR 7.85', operator: '范国华', time: '2026-07-29 11:05', status: '已同步' },
])
const rows = computed<any[]>(() => ({ products: products.value, logistics: logistics.value, members: financePolicies.value, history: history.value }[props.mode]))
const filteredRows = computed(() => {
  const q = search.value.trim().toLowerCase()
  return q ? rows.value.filter(row => Object.values(row).some(v => String(v).toLowerCase().includes(q))) : rows.value
})
const pagedProductRows = computed(() => {
  const start = (productPage.value - 1) * productPageSize.value
  return (filteredRows.value as ProductRow[]).slice(start, start + productPageSize.value)
})
const productPageCount = computed(() => Math.max(1, Math.ceil(filteredRows.value.length / productPageSize.value)))
const completeProductCount = computed(() => products.value.filter(item => item.status === '资料完整').length)
const missingProductCount = computed(() => products.value.length - completeProductCount.value)
const tieredProductCount = computed(() => products.value.filter(item => item.priceTiers.length > 1).length)
const financePolicyCategoryCount = computed(() => new Set(financePolicies.value.filter(policy => policy.enabled).map(policy => policy.category)).size)
const financePolicyCountryCount = computed(() => new Set(financePolicies.value.filter(policy => policy.enabled).flatMap(policy => policy.countryRules.map(rule => rule.country))).size)
const financePolicyChannelCount = computed(() => financePolicies.value.filter(policy => policy.enabled).reduce((total, policy) => total + financePolicyCarrierCount(policy), 0))
const hasActiveFinanceFilters = computed(() => Boolean(financeFilterSearch.value || financeFilterStatus.value || financeFilterCountry.value || financeFilterProvider.value))
const enabledCustomerGradeCount = computed(() => customerGradeSettings.value.filter(setting => setting.enabled).length)
const configuredTaxCountryCount = computed(() => financeTaxSettings.value.countries.filter(setting => setting.selected && setting.enabled).length)
const financeSummaryCards = computed(() => {
  const cards: Record<FinanceSettingsTab, { id: FinanceSettingsTab; icon: string; label: string; value: string | number; description: string }> = {
    countries: { id: 'countries', icon: '国', label: '常用国家设置', value: financeStageCountryCount('common'), description: `最多 ${COMMON_COUNTRY_LIMIT} 个 · 与业务报价同步` },
    logistics: { id: 'logistics', icon: '物', label: '物流属性与渠道', value: financePolicyCategoryCount.value, description: `覆盖 ${financePolicyCountryCount.value} 个已授权国家` },
    taxes: { id: 'taxes', icon: '税', label: '税率设置', value: configuredTaxCountryCount.value, description: `已配置 ${configuredTaxCountryCount.value} 个国家` },
    grades: { id: 'grades', icon: '级', label: 'S–E 客户等级系数', value: enabledCustomerGradeCount.value, description: `共 6 个等级，${enabledCustomerGradeCount.value} 个已启用` },
    exchange: { id: 'exchange', icon: '汇', label: '美元汇率设置', value: financeExchangeRate.value.usdCny.toFixed(4), description: `1 USD = ${financeExchangeRate.value.usdCny.toFixed(4)} CNY` },
  }
  return financeTabOrder.value.map(id => cards[id])
})
const financeTaxCountries = computed(() => {
  const query = financeTaxCountrySearch.value.trim().toLowerCase()
  return financeTaxSettings.value.countries
    .filter(setting => setting.selected)
    .filter(setting => !query || setting.country.toLowerCase().includes(query))
    .sort((a, b) => a.sortOrder - b.sortOrder || a.country.localeCompare(b.country, 'zh-CN'))
})
const filteredTaxProviders = computed(() => {
  const query = financeTaxProviderSearch.value.trim().toLowerCase()
  return financeTaxSettings.value.providers.filter(setting => setting.selected && (!query
    || `${setting.provider} ${setting.channels.map(channel => `${channel.channel} ${channel.ruleName}`).join(' ')}`.toLowerCase().includes(query))
  )
})
const availableTaxCountries = computed(() => financeTaxSettings.value.countries.filter(setting => !setting.selected).sort((a, b) => a.country.localeCompare(b.country, 'zh-CN')))
const availableTaxProviders = computed(() => financeTaxSettings.value.providers.filter(setting => !setting.selected).sort((a, b) => a.provider.localeCompare(b.provider, 'zh-CN')))
const filteredAvailableTaxCountries = computed(() => {
  const query = financeTaxCountryAddSearch.value.trim().toLowerCase()
  return availableTaxCountries.value.filter(setting => {
    const meta = financeCountrySettingMap.value.get(setting.country)
    return !query || `${setting.country} ${meta?.code || ''}`.toLowerCase().includes(query)
  })
})
const filteredAvailableTaxProviders = computed(() => {
  const query = financeTaxProviderAddSearch.value.trim().toLowerCase()
  return availableTaxProviders.value.filter(setting => !query
    || `${setting.provider} ${setting.channels.map(channel => `${channel.channel} ${channel.ruleName}`).join(' ')}`.toLowerCase().includes(query))
})
watch(financeTaxCountryAddSearch, () => { financeTaxCountryAdd.value = filteredAvailableTaxCountries.value[0]?.country || '' })
watch(financeTaxProviderAddSearch, () => { financeTaxProviderAdd.value = filteredAvailableTaxProviders.value[0]?.provider || '' })
const financeTaxPreview = computed(() => {
  const setting = financeTaxCountries.value[0]
  if (!setting) return '请选择常用国家并设置关税'
  return `${setting.country}：关税 $${setting.fixedFeeUsd.toFixed(2)}/单`
})
function financeStageCountryCount(stage: CountryStage) {
  return financeCountrySettings.value.filter(setting => setting.enabled && setting.stage === stage).length
}
function financePolicyCarrierCount(policy: FinanceChannelPolicy) {
  return policy.countryRules.reduce((total, rule) => total + rule.allowedChannels.length, 0)
}
function financePolicyUnavailableCount(policy: FinanceChannelPolicy) {
  return policy.countryRules.reduce((total, rule) => total + (rule.unavailableChannels?.length || 0), 0)
}
const financePolicyCards = computed(() => financePolicies.value.map(policy => ({
  key: policy.id,
  policy,
  countries: policy.countryRules.map(rule => {
    const grouped = new Map<string, ProviderGroup>()
    rule.allowedChannels.forEach(channelKey => {
      const channel = financeChannelForKey(rule.country, channelKey, policy.category)
      if (!channel) return
      const group = grouped.get(channel.carrier) || { provider: channel.carrier, channels: [] }
      group.channels.push({ key: channel.key, name: channel.channel, ruleName: channel.ruleName })
      grouped.set(channel.carrier, group)
    })
    const classification = financeCountrySettingMap.value.get(rule.country)
    return { country: rule.country, stageLabel: financeCountryStageDisplay(rule.country), continent: classification?.continent || rule.continent, groups: [...grouped.values()] }
  }),
})))
const financeFilterCountryOptions = computed(() => [...new Set(financePolicyCards.value.flatMap(card => card.countries.map(item => item.country)))].sort((a, b) => a.localeCompare(b, 'zh-CN')))
const financeFilterProviderOptions = computed(() => [...new Set(financePolicyCards.value.flatMap(card => card.countries.flatMap(item => item.groups.map(group => group.provider))))].sort((a, b) => a.localeCompare(b, 'zh-CN')))
const filteredFinancePolicyCards = computed(() => {
  const query = financeFilterSearch.value.trim().toLowerCase()
  const exactAttribute = financeLogisticsAttributeOptions.find(attribute => attribute.toLowerCase() === query)
  return financePolicyCards.value.filter(card => {
    const status = card.policy.enabled ? '启用' : '停用'
    const searchable = [card.policy.category, ...card.countries.flatMap(item => [item.country, ...item.groups.flatMap(group => [group.provider, ...group.channels.flatMap(channel => [channel.name, channel.ruleName])])])].join(' ').toLowerCase()
    return (!query || (exactAttribute ? card.policy.category === exactAttribute : searchable.includes(query)))
      && (!financeFilterStatus.value || financeFilterStatus.value === status)
      && (!financeFilterCountry.value || card.countries.some(item => item.country === financeFilterCountry.value))
      && (!financeFilterProvider.value || card.countries.some(item => item.groups.some(group => group.provider === financeFilterProvider.value)))
  })
})
function resetFinanceFilters() {
  financeFilterSearch.value = ''
  financeFilterStatus.value = ''
  financeFilterCountry.value = ''
  financeFilterProvider.value = ''
}
watch([search, productPageSize], () => { productPage.value = 1 })
function toast(message: string) { notice.value = message; window.setTimeout(() => notice.value === message && (notice.value = ''), 2200) }
function purchasePriceAt(record: PurchaseProductRecord, quantity: number) {
  if (record.purchasePriceCny == null && !record.priceTiers.length) return '—'
  return `¥${purchaseUnitPrice(record, quantity).toFixed(2)}`
}
function purchaseFreightAt(record: PurchaseProductRecord, quantity: number) {
  const tier = purchaseFreightChoices(record).find(item => item.quantity === quantity)
  return tier ? `¥${tier.totalFreightCny.toFixed(2)}` : '—'
}
function purchaseFreightUnitAt(record: PurchaseProductRecord, quantity: number) {
  const tier = purchaseFreightChoices(record).find(item => item.quantity === quantity)
  return tier ? `¥${tier.unitFreightCny.toFixed(2)}/件` : '未维护'
}
function primaryAction() {
  if (props.mode === 'history') toast('报价记录已准备导出（演示模式）')
  else if (props.mode === 'products') openProductEditor()
  else if (props.mode === 'members') openFinancePolicyEditor()
  else showEditor.value = true
}
async function saveGradeSettings() {
  customerGradeSettings.value.forEach(setting => {
    setting.coefficient = Math.max(0, Number(setting.coefficient) || 1)
  })
  await saveCustomerGradeSettings(customerGradeSettings.value)
  toast('S–E 客户等级计算系数已保存')
}
async function saveExchangeRateSetting() {
  if (!Number.isFinite(financeExchangeRate.value.usdCny) || financeExchangeRate.value.usdCny <= 0) {
    toast('美元汇率必须大于 0')
    return
  }
  financeExchangeRate.value = await saveFinanceExchangeRate(financeExchangeRate.value.usdCny)
  toast(`美元汇率已保存：1 USD = ${financeExchangeRate.value.usdCny.toFixed(4)} CNY`)
}
function fixedFeeCny(fixedFeeUsd: number) {
  const usd = Math.max(0, Number(fixedFeeUsd) || 0)
  const rate = Math.max(0, Number(financeExchangeRate.value.usdCny) || 0)
  return (usd * rate).toFixed(2)
}
function changeProviderTaxMode(setting: FinanceProviderTaxSetting, mode: LogisticsTaxMode) {
  setting.mode = mode
}
function addTaxCountry() {
  const setting = financeTaxSettings.value.countries.find(item => item.country === financeTaxCountryAdd.value)
  if (!setting) return
  setting.selected = true
  financeTaxCountryAdd.value = ''
  financeTaxCountryAddSearch.value = ''
  financeTaxCountryAddOpen.value = false
  toast(`${setting.country} 已加入国家关税设置`)
}
function removeTaxCountry(setting: FinanceCountryTaxSetting) {
  setting.selected = false
  setting.enabled = false
  setting.fixedFeeUsd = 0
  toast(`${setting.country} 已移出关税设置，保存后生效`)
}
function addTaxProvider() {
  const setting = financeTaxSettings.value.providers.find(item => item.provider === financeTaxProviderAdd.value)
  if (!setting) return
  setting.selected = true
  financeTaxProviderAdd.value = ''
  financeTaxProviderAddSearch.value = ''
  financeTaxProviderAddOpen.value = false
  toast(`${setting.provider} 已加入物流商税务设置`)
}
function removeTaxProvider(setting: FinanceProviderTaxSetting) {
  setting.selected = false
  toast(`${setting.provider} 已移出税务设置，保存后将视为未配置`)
}
async function saveTaxSettings() {
  financeTaxSettings.value.countries.forEach(setting => {
    setting.fixedFeeUsd = Math.max(0, Number(setting.fixedFeeUsd) || 0)
    setting.enabled = setting.fixedFeeUsd > 0
  })
  financeTaxSettings.value = await saveFinanceTaxSettings(financeTaxSettings.value)
  toast('国家关税与物流商全局税务属性已保存')
}
function startFinanceTabDrag(tab: FinanceSettingsTab, event: DragEvent) {
  draggedFinanceTab.value = tab
  dragOverFinanceTab.value = ''
  event.dataTransfer?.setData('text/plain', tab)
  if (event.dataTransfer) event.dataTransfer.effectAllowed = 'move'
}
function moveFinanceTabOver(tab: FinanceSettingsTab, event: DragEvent) {
  if (!draggedFinanceTab.value || draggedFinanceTab.value === tab) return
  event.preventDefault()
  dragOverFinanceTab.value = tab
  if (event.dataTransfer) event.dataTransfer.dropEffect = 'move'
}
function dropFinanceTab(tab: FinanceSettingsTab, event: DragEvent) {
  event.preventDefault()
  const source = (draggedFinanceTab.value || event.dataTransfer?.getData('text/plain') || '') as FinanceSettingsTab | ''
  if (!source || source === tab || !financeTabOrder.value.includes(source)) return endFinanceTabDrag()
  const ordered = [...financeTabOrder.value]
  const sourceIndex = ordered.indexOf(source)
  const targetIndex = ordered.indexOf(tab)
  const [moved] = ordered.splice(sourceIndex, 1)
  ordered.splice(targetIndex, 0, moved)
  financeTabOrder.value = ordered
  window.localStorage.setItem(FINANCE_TAB_ORDER_STORAGE_KEY, JSON.stringify(ordered))
  toast('财务功能卡片顺序已保存')
  endFinanceTabDrag()
}
function endFinanceTabDrag() {
  draggedFinanceTab.value = ''
  dragOverFinanceTab.value = ''
}
function openCountryPicker(stage: CountryStage) {
  countryPickerStage.value = stage
  countryPickerSearch.value = ''
  countryPickerSelected.value = []
}
function toggleCountryPickerSelection(country: string) {
  countryPickerSelected.value = countryPickerSelected.value.includes(country)
    ? countryPickerSelected.value.filter(item => item !== country)
    : [...countryPickerSelected.value, country]
}
function confirmCountryPicker() {
  const stage = countryPickerStage.value
  if (!stage || !countryPickerSelected.value.length) return
  const currentCommon = financeCountrySettings.value.filter(item => item.enabled && item.stage === 'common' && !countryPickerSelected.value.includes(item.country)).length
  if (stage === 'common' && currentCommon + countryPickerSelected.value.length > COMMON_COUNTRY_LIMIT) {
    toast(`常用国家最多 ${COMMON_COUNTRY_LIMIT} 个，本次最多还能添加 ${Math.max(0, COMMON_COUNTRY_LIMIT - currentCommon)} 个`)
    return
  }
  let nextSort = Math.max(0, ...financeCountrySettings.value.filter(item => item.enabled && item.stage === stage).map(item => item.sortOrder))
  countryPickerSelected.value.forEach(country => {
    const setting = financeCountrySettings.value.find(item => item.country === country)
    if (!setting) return
    setting.enabled = true
    setting.stage = stage
    setting.sortOrder = nextSort += 10
  })
  countryPickerStage.value = null
}
function removeFinanceCountrySetting(setting: FinanceCountrySetting) {
  setting.enabled = false
  setting.stage = 'rare'
  toast(`${setting.country} 已移出常用国家，将不再显示在业务报价国家列表`)
}
function startFinanceCountryDrag(country: string, event: DragEvent) {
  draggedFinanceCountry.value = country
  dragOverFinanceCountry.value = ''
  event.dataTransfer?.setData('text/plain', country)
  if (event.dataTransfer) event.dataTransfer.effectAllowed = 'move'
}
function moveFinanceCountryOver(country: string, event: DragEvent) {
  if (!draggedFinanceCountry.value || draggedFinanceCountry.value === country) return
  event.preventDefault()
  dragOverFinanceCountry.value = country
  if (event.dataTransfer) event.dataTransfer.dropEffect = 'move'
}
function dropFinanceCountry(country: string, event: DragEvent) {
  event.preventDefault()
  const source = draggedFinanceCountry.value || event.dataTransfer?.getData('text/plain') || ''
  if (!source || source === country) return endFinanceCountryDrag()
  const ordered = financeCountrySettings.value
    .filter(setting => setting.enabled && setting.stage === 'common')
    .sort((a, b) => a.sortOrder - b.sortOrder || a.country.localeCompare(b.country, 'zh-CN'))
  const sourceIndex = ordered.findIndex(setting => setting.country === source)
  const targetIndex = ordered.findIndex(setting => setting.country === country)
  if (sourceIndex < 0 || targetIndex < 0) return endFinanceCountryDrag()
  const [moved] = ordered.splice(sourceIndex, 1)
  ordered.splice(targetIndex, 0, moved)
  ordered.forEach((setting, index) => { setting.sortOrder = (index + 1) * 10 })
  endFinanceCountryDrag()
}
function endFinanceCountryDrag() {
  draggedFinanceCountry.value = ''
  dragOverFinanceCountry.value = ''
}
async function saveFinanceCountryClassification() {
  if (financeCountrySettings.value.filter(setting => setting.enabled && setting.stage === 'common').length > COMMON_COUNTRY_LIMIT) {
    toast(`常用国家最多只能设置 ${COMMON_COUNTRY_LIMIT} 个`)
    return
  }
  financeCountrySettings.value.forEach(setting => {
    setting.continent = inferCountryContinent(setting.code)
    setting.sortOrder = Math.max(1, Number(setting.sortOrder) || 1)
  })
  financeCountrySettings.value = await saveFinanceCountrySettings(financeCountrySettings.value)
  toast('常用国家设置已保存，业务报价国家列表将同步更新')
}
async function openFinancePolicyEditor(policy?: FinanceChannelPolicy) {
  const requestId = ++financeEditorRequestId
  editingFinancePolicyId.value = policy?.id || null
  financePolicyForm.value = policy ? { category: policy.category, countryRules: policy.countryRules.map(rule => ({ ...rule, allowedChannels: [...rule.allowedChannels], unavailableChannels: [...(rule.unavailableChannels || [])] })), enabled: policy.enabled } : emptyFinancePolicyForm()
  financeCountrySearches.value = financePolicyForm.value.countryRules.map(rule => rule.country)
  financeSelectedCarriers.value = []
  financeAttributePickerOpen.value = false
  financeAttributePickerTyping.value = false
  openFinanceCountryPicker.value = null
  expandedFinanceCountryRules.value = []
  reviewingLegacyCountryRules.value = []
  showEditor.value = true
  if (!await hydrateFinanceLogisticsContext()) return
  if (requestId === financeEditorRequestId && showEditor.value) financeSelectedCarriers.value = financePolicyForm.value.countryRules.map(rule => preferredFinanceCarrier(rule))
}
function financeCountryRuleExpanded(index: number) {
  return expandedFinanceCountryRules.value.includes(index)
}
function toggleFinanceCountryRule(index: number) {
  expandedFinanceCountryRules.value = financeCountryRuleExpanded(index)
    ? expandedFinanceCountryRules.value.filter(item => item !== index)
    : [...expandedFinanceCountryRules.value, index]
}
function expandFinanceCountryRule(index: number) {
  if (!financeCountryRuleExpanded(index)) expandedFinanceCountryRules.value = [...expandedFinanceCountryRules.value, index]
}
function financeLegacyReviewExpanded(index: number) {
  return reviewingLegacyCountryRules.value.includes(index)
}
function toggleFinanceLegacyReview(index: number) {
  reviewingLegacyCountryRules.value = financeLegacyReviewExpanded(index)
    ? reviewingLegacyCountryRules.value.filter(item => item !== index)
    : [...reviewingLegacyCountryRules.value, index]
}
function openFinanceAttributePicker() {
  financeAttributePickerOpen.value = true
  financeAttributePickerTyping.value = false
}
function updateFinanceAttribute(event: Event) {
  financePolicyForm.value.category = (event.target as HTMLInputElement).value
  financeAttributePickerOpen.value = true
  financeAttributePickerTyping.value = true
}
function selectFinanceAttribute(attribute: string) {
  financePolicyForm.value.category = attribute
  financeAttributePickerOpen.value = false
  financeAttributePickerTyping.value = false
  handleFinanceCategoryChange()
}
function toggleFinanceAttributePicker() {
  financeAttributePickerOpen.value = !financeAttributePickerOpen.value
  financeAttributePickerTyping.value = false
  nextTick(() => financeAttributeInput.value?.focus())
}
function closeFinanceAttributePicker() {
  window.setTimeout(() => {
    financeAttributePickerOpen.value = false
    handleFinanceCategoryChange()
  }, 120)
}
function financeChannelsForCountry(country: string) {
  return cachedFinanceChannels(country, financePolicyForm.value.category)
}
function cachedFinanceChannels(country: string, attribute: string) {
  void financeLogisticsDataEpoch.value
  const key = `${attribute}::${country}`
  let options = financeChannelCache.get(key)
  if (!options) { options = channelsAvailableForCountry(country, attribute); financeChannelCache.set(key, options) }
  return options
}
function financeCarrierGroupsForCountry(country: string) {
  void financeLogisticsDataEpoch.value
  const key = `${financePolicyForm.value.category}::${country}`
  const cached = financeCarrierCache.get(key)
  if (cached) return cached
  const grouped = new Map<string, ReturnType<typeof financeChannelsForCountry>>()
  financeChannelsForCountry(country).forEach(option => {
    const channels = grouped.get(option.carrier) || []
    channels.push(option)
    grouped.set(option.carrier, channels)
  })
  const groups = [...grouped.entries()].map(([carrier, channels]) => ({ carrier, channels }))
  financeCarrierCache.set(key, groups)
  return groups
}
function preferredFinanceCarrier(rule: FinanceCountryChannelRule) {
  const options = financeChannelsForCountry(rule.country)
  return options.find(option => rule.allowedChannels.includes(option.key))?.carrier || options[0]?.carrier || ''
}
function selectedFinanceCarrier(index: number) {
  const rule = financePolicyForm.value.countryRules[index]
  const groups = financeCarrierGroupsForCountry(rule.country)
  const selected = financeSelectedCarriers.value[index]
  return groups.some(group => group.carrier === selected) ? selected : preferredFinanceCarrier(rule)
}
function selectFinanceCarrier(index: number, carrier: string) {
  financeSelectedCarriers.value[index] = carrier
}
function financeChannelsForSelectedCarrier(index: number) {
  const rule = financePolicyForm.value.countryRules[index]
  const carrier = selectedFinanceCarrier(index)
  return financeCarrierGroupsForCountry(rule.country).find(group => group.carrier === carrier)?.channels || []
}
function selectedFinanceCarrierChannelCount(index: number, carrier: string) {
  const selected = new Set(financePolicyForm.value.countryRules[index].allowedChannels)
  return financeCarrierGroupsForCountry(financePolicyForm.value.countryRules[index].country)
    .find(group => group.carrier === carrier)?.channels.filter(channel => selected.has(channel.key)).length || 0
}
function financeChannelForKey(country: string, key: string, attribute = financePolicyForm.value.category) {
  return cachedFinanceChannels(country, attribute).find(option => option.key === key)
}
function normalizeFinanceCarriers(index: number) {
  const rule = financePolicyForm.value.countryRules[index]
  const available = new Set(financeChannelsForCountry(rule.country).map(option => option.key))
  rule.allowedChannels = rule.allowedChannels.filter(channel => available.has(channel))
}
function financeCountryCode(country: string) {
  return financeLogisticsCountries.value.find(item => item.name === country)?.code || ''
}
function applyFinanceCountryDefaults(index: number, country: string) {
  const rule = financePolicyForm.value.countryRules[index]
  const setting = financeCountrySettingMap.value.get(country)
  const stage = setting?.stage || defaultCountryStage(country)
  rule.stage = stage
  rule.continent = setting?.continent || inferCountryContinent(financeCountryCode(country))
  rule.sortOrder = setting?.sortOrder || defaultCountrySortOrder(country, stage)
}
function filteredFinanceCountries(index: number) {
  const query = openFinanceCountryPicker.value === index && financeCountryPickerTyping.value
    ? (financeCountrySearches.value[index] || '').trim().toLowerCase()
    : ''
  return financeLogisticsCountries.value
    .filter(country => !query || country.name.toLowerCase().includes(query) || country.code?.toLowerCase().includes(query))
}
function openFinanceCountrySearch(index: number) {
  openFinanceCountryPicker.value = index
  financeCountryPickerTyping.value = false
  financeCountrySearches.value[index] = financePolicyForm.value.countryRules[index].country
}
function updateFinanceCountrySearch(index: number, event: Event) {
  const value = (event.target as HTMLInputElement).value
  financeCountrySearches.value[index] = value
  openFinanceCountryPicker.value = index
  financeCountryPickerTyping.value = true
  const rule = financePolicyForm.value.countryRules[index]
  rule.country = financeLogisticsCountries.value.some(country => country.name === value) ? value : ''
  if (rule.country) { applyFinanceCountryDefaults(index, rule.country); normalizeFinanceCarriers(index) }
}
function selectFinanceCountry(index: number, country: string) {
  financePolicyForm.value.countryRules[index].country = country
  applyFinanceCountryDefaults(index, country)
  financeCountrySearches.value[index] = country
  openFinanceCountryPicker.value = null
  financeCountryPickerTyping.value = false
  normalizeFinanceCarriers(index)
  financeSelectedCarriers.value[index] = preferredFinanceCarrier(financePolicyForm.value.countryRules[index])
  expandFinanceCountryRule(index)
}
function closeFinanceCountrySearch(index: number) {
  window.setTimeout(() => {
    if (openFinanceCountryPicker.value === index) openFinanceCountryPicker.value = null
  }, 120)
}
async function addFinanceCountryRule() {
  if (!financePolicyForm.value.category.trim()) { toast('请先设置财务品类，再匹配可发国家'); return }
  const used = new Set(financePolicyForm.value.countryRules.map(rule => rule.country))
  const country = financeLogisticsCountries.value.find(item => !used.has(item.name))?.name
  if (!country) { toast('该品类在物流规则中没有更多可发国家'); return }
  const setting = financeCountrySettingMap.value.get(country)
  const stage = setting?.stage || defaultCountryStage(country)
  financePolicyForm.value.countryRules.push({
    country,
    allowedChannels: [],
    stage,
    continent: setting?.continent || inferCountryContinent(financeLogisticsCountries.value.find(item => item.name === country)?.code),
    sortOrder: setting?.sortOrder || defaultCountrySortOrder(country, stage),
  })
  financeCountrySearches.value.push(country)
  financeSelectedCarriers.value.push(preferredFinanceCarrier(financePolicyForm.value.countryRules[financePolicyForm.value.countryRules.length - 1]))
  expandedFinanceCountryRules.value = [...expandedFinanceCountryRules.value, financePolicyForm.value.countryRules.length - 1]
  await nextTick()
  const cards = document.querySelectorAll('.finance-editor .country-rule-card')
  cards[cards.length - 1]?.scrollIntoView({ behavior: 'smooth', block: 'center' })
}
function removeFinanceCountryRule(index: number) {
  financePolicyForm.value.countryRules.splice(index, 1)
  financeCountrySearches.value.splice(index, 1)
  financeSelectedCarriers.value.splice(index, 1)
  openFinanceCountryPicker.value = null
  expandedFinanceCountryRules.value = expandedFinanceCountryRules.value
    .filter(item => item !== index)
    .map(item => item > index ? item - 1 : item)
  reviewingLegacyCountryRules.value = reviewingLegacyCountryRules.value
    .filter(item => item !== index)
    .map(item => item > index ? item - 1 : item)
}
function handleFinanceCategoryChange() {
  const availableCountries = new Set(financeLogisticsCountries.value.map(country => country.name))
  financePolicyForm.value.countryRules = financePolicyForm.value.countryRules
    .filter(rule => availableCountries.has(rule.country))
    .map(rule => ({ ...rule, allowedChannels: rule.allowedChannels.filter(channel => financeChannelsForCountry(rule.country).some(option => option.key === channel)) }))
  financeCountrySearches.value = financePolicyForm.value.countryRules.map(rule => rule.country)
  financeSelectedCarriers.value = financePolicyForm.value.countryRules.map(rule => preferredFinanceCarrier(rule))
  openFinanceCountryPicker.value = null
  expandedFinanceCountryRules.value = []
  reviewingLegacyCountryRules.value = []
}
async function saveFinancePolicy() {
  if (financePolicySaving.value) return
  financePolicySaving.value = true
  const editorRequestId = financeEditorRequestId
  try {
  if (!await hydrateFinanceLogisticsContext()) return
  if (!showEditor.value || editorRequestId !== financeEditorRequestId || props.mode !== 'members') return
  const form = financePolicyForm.value
  const category = form.category.trim()
  form.countryRules.forEach(rule => { rule.country = rule.country.trim() })
  if (!category || !form.countryRules.length) { toast('请先填写财务品类并配置国家'); return }
  const financeCountryNameSet = new Set(financeLogisticsCountries.value.map(country => country.name))
  if (form.countryRules.some(rule => !financeCountryNameSet.has(rule.country))) { toast('所选国家与当前品类的物流规则不匹配'); return }
  if (new Set(form.countryRules.map(rule => rule.country)).size !== form.countryRules.length) { toast('同一品类不能重复配置同一个国家'); return }
  if (form.countryRules.some(rule => !rule.country || (!rule.allowedChannels.length && !rule.unavailableChannels?.length))) { toast('每个国家都必须至少选择一个允许渠道，或保留待审旧渠道'); return }
  if (form.countryRules.some(rule => rule.allowedChannels.some(key => !financeChannelsForCountry(rule.country).some(option => option.key === key)))) { toast('部分已选渠道已停用或更新，请重新核对渠道后保存'); return }
  const duplicate = financePolicies.value.find(policy => policy.category === category && policy.id !== editingFinancePolicyId.value)
  if (duplicate) { toast(`${category} 已存在策略，请直接编辑该品类`); return }
  const policy: FinanceChannelPolicy = {
    id: editingFinancePolicyId.value || `${category}-${Date.now()}`,
    category: category as FinanceLogisticsAttribute,
    countryRules: form.countryRules.map(rule => ({
      ...rule,
      continent: inferCountryContinent(financeCountryCode(rule.country)),
      sortOrder: Math.max(1, Number(rule.sortOrder) || 1),
      allowedChannels: [...rule.allowedChannels],
      unavailableChannels: [...(rule.unavailableChannels || [])],
    })),
    enabled: form.enabled,
    updatedAt: new Date().toLocaleString('zh-CN', { hour12: false }),
  }
  const nextPolicies = financePolicies.value.map(item => item.id === editingFinancePolicyId.value ? policy : item)
  if (!editingFinancePolicyId.value) nextPolicies.unshift(policy)
  try {
    financePolicies.value = await saveFinanceChannelPolicies(nextPolicies)
    showEditor.value = false
    toast(`${category} 的 ${form.countryRules.length} 个国家渠道策略已保存`)
  } catch (error) {
    toast(error instanceof Error ? `保存失败：${error.message}` : '保存失败，请刷新后重试')
  }
  } finally { financePolicySaving.value = false }
}
async function removeFinancePolicy(policy: FinanceChannelPolicy) {
  const nextPolicies = financePolicies.value.filter(item => item.id !== policy.id)
  try {
    financePolicies.value = await saveFinanceChannelPolicies(nextPolicies)
    toast(`${policy.category} 品类策略已删除`)
  } catch (error) {
    toast(error instanceof Error ? `删除失败：${error.message}` : '删除失败，请刷新后重试')
  }
}
function openProductEditor(product?: ProductRow) {
  editingSourceRow.value = product?.sourceRow ?? null
  productForm.value = product ? {
    invoiceInfo: product.invoiceInfo, sku: product.sku, name: product.name, quotationOwner: product.quotationOwner,
    quotationDate: product.quotationDate, notes: product.notes, image: product.image, stockStatus: product.stockStatus,
    weightDescription: product.weightDescription, weightG: product.weightG ?? 0, size: product.size,
    colorSku: product.colorSku, material: product.material, minOrderQty: product.minOrderQty,
    rawTierPrice: product.rawTierPrice, l6Price: product.l6Price, freightTrial: product.freightTrial,
    singleFreightCny: product.singleFreightCny, freight10Cny: product.freight10Cny, freight100Cny: product.freight100Cny,
    purchasePriceCny: product.purchasePriceCny ?? 0,
    category: product.category, taxIncludedPrice: product.taxIncludedPrice, taxPoint: product.taxPoint,
    invoiceType: product.invoiceType || product.taxDifference, factoryInfo: product.factoryInfo || product.packagingInfo,
    auditNotes: product.auditNotes, sourceLinks: [...product.sourceLinks, '', '', '', ''].slice(0, 4),
    otherNotes: product.otherNotes, more: product.more,
  } : emptyProductForm()
  showEditor.value = true
}
function openProductDetail(product: ProductRow) {
  detailProduct.value = product
}
function handleProductImage(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0]
  if (!file) return
  const reader = new FileReader()
  reader.onload = () => { productForm.value.image = String(reader.result || '') }
  reader.readAsDataURL(file)
}
function openImagePreview(src: string, alt: string) {
  previewImage.value = src
  previewImageAlt.value = alt
}
function closeImagePreview() {
  previewImage.value = ''
  previewImageAlt.value = ''
}
function handlePreviewKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape' && previewImage.value) closeImagePreview()
}
function applyFinanceSettingsWorkspace(workspace: FinanceSettingsWorkspace) {
  financePolicies.value = workspace.policies
  financeCountrySettings.value = workspace.countries
  customerGradeSettings.value = workspace.customerGrades
  financeExchangeRate.value = workspace.exchangeRate
  financeTaxSettings.value = workspace.taxSettings
}
function financeSettingsErrorMessage(error: unknown) {
  if (error instanceof ApiError) return `${error.message}（请求编号：${error.requestId}）`
  return error instanceof Error ? error.message : '财务设置加载失败，请稍后重试'
}
async function hydrateFinanceSettingsWorkspace(force = false) {
  if (props.mode !== 'members') return
  financeSettingsLoadState.value = 'loading'
  financeSettingsLoadError.value = ''
  showEditor.value = false
  try {
    applyFinanceSettingsWorkspace(await loadFinanceSettingsWorkspace({ force }))
    financeSettingsLoadState.value = 'ready'
    void hydrateFinanceLogisticsContext()
  } catch (error) {
    financeSettingsLoadError.value = financeSettingsErrorMessage(error)
    financeSettingsLoadState.value = 'error'
  }
}
async function hydrateFinanceLogisticsContext() {
  if (props.mode !== 'members') return false
  if (financeContextRequest) return financeContextRequest
  financeLogisticsContextState.value = 'loading'
  financeLogisticsContextError.value = ''
  const request = (async () => {
    try {
      const { manifest } = await loadPublishedLogisticsManifest({ allowStale: false })
      const countries = manifest.countries.map(country => country.code || country.name)
      await loadPublishedLogisticsRuleCatalog(manifest.attributes, countries, { manifest })
      if (props.mode !== 'members') return false
      financeChannelCache.clear()
      financeCarrierCache.clear()
      financeLogisticsDataEpoch.value += 1
      applyFinanceSettingsWorkspace(readFinanceSettingsWorkspace())
      financeLogisticsContextState.value = 'ready'
      return true
    } catch (error) {
      financeLogisticsContextError.value = error instanceof Error ? error.message : '物流正式数据加载失败'
      financeLogisticsContextState.value = 'error'
      return false
    }
  })()
  financeContextRequest = request
  try { return await request }
  finally { if (financeContextRequest === request) financeContextRequest = null }
}

onMounted(() => {
  window.addEventListener('keydown', handlePreviewKeydown)
  void hydrateFinanceSettingsWorkspace()
})
watch(() => props.mode, mode => {
  showEditor.value = false
  if (mode === 'members') void hydrateFinanceSettingsWorkspace()
})
onBeforeUnmount(() => {
  financeEditorRequestId += 1
  showEditor.value = false
  window.removeEventListener('keydown', handlePreviewKeydown)
})
function buildPriceTiers(raw: string, basePrice: number | null, minQty: number) {
  const normalized = raw.replace(/[，；—～~]/g, match => ({ '，': ',', '；': ';', '—': '-', '～': '-', '~': '-' }[match] || match))
  const tiers: PurchaseProductRecord['priceTiers'] = []
  const addTier = (minimum: number, maximum: number | null, unitPriceCny: number, source: string) => {
    if (minimum < 1 || unitPriceCny <= 0) return
    if (!tiers.some(item => item.minQty === minimum && item.maxQty === maximum && item.unitPriceCny === unitPriceCny)) {
      tiers.push({ minQty: minimum, maxQty: maximum, unitPriceCny, source })
    }
  }
  for (const match of normalized.matchAll(/(\d+)\s*-\s*(\d+)\s*(?:件|个|套)?\s*[:：]?\s*(?:单价)?\s*[¥￥]?\s*(\d+(?:\.\d+)?)/g)) {
    addTier(Number(match[1]), Number(match[2]), Number(match[3]), '系统维护阶梯价')
  }
  for (const match of normalized.matchAll(/(\d+)\s*(?:件|个|套)?\s*(?:以上|起|及以上)\s*[:：]?\s*(?:单价)?\s*[¥￥]?\s*(\d+(?:\.\d+)?)/g)) {
    addTier(Number(match[1]), null, Number(match[2]), '系统维护阶梯价')
  }
  if (basePrice != null) {
    const nextMinimum = tiers.filter(item => item.minQty > minQty).map(item => item.minQty).sort((a, b) => a - b)[0]
    addTier(minQty, nextMinimum ? nextMinimum - 1 : null, basePrice, '报价列')
  }
  tiers.sort((a, b) => a.minQty - b.minQty)
  tiers.slice(0, -1).forEach((item, index) => {
    const nextMinimum = tiers[index + 1].minQty
    if (item.maxQty == null || item.maxQty >= nextMinimum) item.maxQty = nextMinimum - 1
  })
  return tiers
}
function saveEditor() {
  if (props.mode === 'members') { saveFinancePolicy(); return }
  if (props.mode !== 'products') { showEditor.value = false; toast('设置已保存，并将用于后续报价计算'); return }
  const form = productForm.value
  const sku = form.sku.trim().toUpperCase().replace(/\s+/g, '')
  if (!sku) { toast('请先填写 SKU'); return }
  const existing = editingSourceRow.value == null ? undefined : products.value.find(item => item.sourceRow === editingSourceRow.value)
  const duplicate = products.value.find(item => item.sku === sku && item.sourceRow !== editingSourceRow.value)
  if (duplicate) { toast(`SKU ${sku} 已存在，请核对后再保存`); return }
  const price = Number(form.purchasePriceCny) || null
  const enteredWeightG = Number(form.weightG)
  const weightG = enteredWeightG > 0 ? Math.ceil(enteredWeightG) : null
  const minOrderQty = Math.max(1, Math.floor(Number(form.minOrderQty) || 1))
  const priceTiers = existing && form.rawTierPrice.trim() === existing.rawTierPrice && price === existing.purchasePriceCny && minOrderQty === existing.minOrderQty
    ? existing.priceTiers
    : buildPriceTiers(form.rawTierPrice, price, minOrderQty)
  const record: ProductRow = {
    ...(existing || {
      sourceRow: Date.now(), quotationDate: new Date().toISOString().slice(0, 10), weightDescription: '', stockStatus: '待确认',
      priceTiers: [], rawTierPrice: '', l6Price: '', freightTrial: '', singleFreightCny: null,
      freight10Cny: null, freight100Cny: null,
      invoiceInfo: '', taxIncludedPrice: '', taxPoint: '', taxDifference: '', packagingInfo: '',
      invoiceType: '', factoryInfo: '', auditNotes: '', sourceLinks: [], otherNotes: '', more: '', shippingMarks: [], marks: '',
    }),
    sku, name: form.name.trim() || '待补充商品名称', image: form.image, stockStatus: form.stockStatus,
    invoiceInfo: form.invoiceInfo.trim(), quotationOwner: form.quotationOwner.trim(), quotationDate: form.quotationDate,
    notes: form.notes.trim(), weightDescription: form.weightDescription.trim(), weightG,
    weightKg: weightG == null ? null : weightG / 1000, size: form.size.trim() || '待补充',
    colorSku: form.colorSku.trim(), material: form.material.trim(),
    // Legacy markers are retained when editing imported records, but new
    // purchase records no longer receive or maintain a logistics attribute.
    shippingMarks: existing?.shippingMarks ? [...existing.shippingMarks] : [],
    marks: existing?.marks ?? '',
    minOrderQty, purchasePriceCny: price, priceTiers, rawTierPrice: form.rawTierPrice.trim(),
    l6Price: form.l6Price.trim(), freightTrial: form.freightTrial.trim(),
    singleFreightCny: Number(form.singleFreightCny) || null, freight10Cny: Number(form.freight10Cny) || null,
    freight100Cny: Number(form.freight100Cny) || null, category: form.category.trim() || '未分类',
    taxIncludedPrice: form.taxIncludedPrice.trim(), taxPoint: form.taxPoint.trim(),
    taxDifference: form.invoiceType.trim(), packagingInfo: form.factoryInfo.trim(),
    invoiceType: form.invoiceType.trim(), factoryInfo: form.factoryInfo.trim(), auditNotes: form.auditNotes.trim(),
    sourceLinks: form.sourceLinks.map(link => link.trim()), otherNotes: form.otherNotes.trim(), more: form.more.trim(),
    status: weightG != null && price != null && form.image ? '资料完整' : [weightG == null ? '缺少重量' : '', price == null ? '缺少采购价' : '', !form.image ? '缺少图片' : ''].filter(Boolean).join('、'),
  }
  if (existing) products.value.splice(products.value.indexOf(existing), 1, record)
  else products.value.unshift(record)
  savePurchaseProducts(products.value)
  showEditor.value = false
  toast(`${sku} 采购资料已保存`)
}
</script>

<template>
  <div class="module-app">
    <main v-if="showPurchaseWorkspace" class="page"><PurchaseDataWorkspace /></main>
    <main v-else class="page">
      <section class="heading"><div><p>{{ config[0] }}</p><h1>{{ config[1] }}</h1><span>{{ config[2] }}</span></div><button v-if="mode!=='members' || (financeSettingsLoadState==='ready' && financeSettingsTab==='logistics')" class="primary" @click="primaryAction">＋ {{ config[3] }}</button></section>

      <section v-if="mode==='members' && financeSettingsLoadState==='loading'" class="finance-load-state" role="status" aria-live="polite"><i aria-hidden="true"></i><span><b>正在加载财务设置</b><small>正在从服务器读取已保存的国家、物流、等级、汇率和税率，请稍候。</small></span></section>
      <section v-else-if="mode==='members' && financeSettingsLoadState==='error'" class="finance-load-state error" role="alert"><span><b>财务设置加载失败</b><small>{{ financeSettingsLoadError }}</small><em>为避免误用默认值，当前设置内容和保存操作已暂时隐藏。</em></span><button type="button" @click="hydrateFinanceSettingsWorkspace(true)">重新加载</button></section>

      <section v-if="mode !== 'logistics' && (mode !== 'members' || financeSettingsLoadState==='ready')" class="stats" :class="{ 'finance-stats':mode==='members' }">
        <template v-if="mode === 'products'"><div><small>已导入商品</small><b>{{ products.length }}</b><span>{{ purchaseImportMeta.sourceFile }} · {{ purchaseImportMeta.sourceSheet }}</span></div><div><small>资料完整</small><b>{{ completeProductCount }}</b><span>可直接参与SKU报价</span></div><div><small>待补充资料</small><b class="orange">{{ missingProductCount }}</b><span>缺少重量或采购价</span></div><div><small>含阶梯价格</small><b>{{ tieredProductCount }}</b><span>按业务数量自动匹配</span></div></template>
        <template v-else-if="mode === 'members'"><div v-for="card in financeSummaryCards" :key="card.id" role="button" tabindex="0" draggable="true" :class="{ active:financeSettingsTab===card.id, dragging:draggedFinanceTab===card.id, 'drag-over':dragOverFinanceTab===card.id }" title="点击切换模块；按住卡片拖动排序" @click="financeSettingsTab=card.id" @keydown.enter="financeSettingsTab=card.id" @keydown.space.prevent="financeSettingsTab=card.id" @dragstart="startFinanceTabDrag(card.id,$event)" @dragover="moveFinanceTabOver(card.id,$event)" @drop="dropFinanceTab(card.id,$event)" @dragend="endFinanceTabDrag"><em class="finance-card-drag" aria-hidden="true">⠿</em><i>{{ card.icon }}</i><section><small>{{ card.label }}</small><b>{{ card.value }}</b><span>{{ card.description }}</span></section></div></template>
        <template v-else><div><small>今日报价</small><b>18</b><span>涉及 11 个 SKU</span></div><div><small>会员报价</small><b>9</b><span>已同步 7 条</span></div><div><small>平均利润率</small><b>19.6%</b><span>处于安全区间</span></div><div><small>异常记录</small><b class="orange">1</b><span>物流规则已失效</span></div></template>
      </section>
      <section v-if="mode==='logistics'" class="subtabs"><button v-for="tab in ['物流商','物流渠道','运费规则','国家区域','重量限制','运费试算']" :key="tab" :class="{ active: logisticsTab === tab }" @click="logisticsTab = tab; toast(`已切换至${tab}`)">{{ tab }}</button></section>

      <section v-if="mode==='members' && financeSettingsLoadState==='ready' && financeSettingsTab==='countries'" class="common-country-manager">
        <header>
          <div><b>常用国家</b><span>最多{{ COMMON_COUNTRY_LIMIT }}个 · 拖动卡片调整业务报价展示顺序</span></div>
          <em>{{ financeStageCountryCount('common') }} / {{ COMMON_COUNTRY_LIMIT }}</em>
          <label>⌕<input v-model="financeCountrySettingSearch" placeholder="搜索国家"></label>
          <button class="add-country" type="button" @click="openCountryPicker('common')">＋ 添加国家</button>
          <button class="primary" type="button" @click="saveFinanceCountryClassification">保存设置</button>
        </header>
        <div class="common-country-card-grid">
          <article v-for="setting in commonCountrySettings" :key="setting.country" draggable="true" :class="{ dragging:draggedFinanceCountry===setting.country, 'drag-over':dragOverFinanceCountry===setting.country }" title="按住卡片拖动排序" @dragstart="startFinanceCountryDrag(setting.country,$event)" @dragover="moveFinanceCountryOver(setting.country,$event)" @drop="dropFinanceCountry(setting.country,$event)" @dragend="endFinanceCountryDrag">
            <i aria-hidden="true">⠿</i><strong>{{ setting.code || '—' }}</strong><span><b>{{ setting.country }}</b><small>{{ setting.continent }}</small></span>
            <button type="button" :aria-label="`移除${setting.country}`" title="移除国家" @click.stop="removeFinanceCountrySetting(setting)"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 7h16M9 7V4h6v3m3 0-1 13H7L6 7m4 4v5m4-5v5"/></svg></button>
          </article>
          <p v-if="!commonCountrySettings.length" class="country-setting-empty">没有匹配的常用国家</p>
        </div>
        <footer><span aria-hidden="true">⠿</span> 按住卡片拖动排序，保存后同步到业务报价</footer>
      </section>
      <section v-else-if="mode==='members' && financeSettingsLoadState==='ready' && financeSettingsTab==='taxes'" class="finance-tax-workspace">
        <header>
          <div><small>FINANCE TAX POLICY</small><b>税率设置</b><span>国家关税与物流商税务属性独立维护，确保报价计算清晰可追溯。</span></div>
          <aside><span>最近保存：{{ financeTaxSettings.updatedAt }}</span><button class="primary" type="button" @click="saveTaxSettings">保存并发布</button></aside>
        </header>
        <div class="finance-tax-content">
          <section class="tax-country-matrix">
            <header><div><b>国家关税</b><span>只维护实际报价国家；关税固定按整张报价单计入一次。</span></div><aside><button class="tax-add-button" type="button" :disabled="!availableTaxCountries.length" @click="financeTaxCountryAddOpen=true;financeTaxCountryAddSearch='';financeTaxCountryAdd=availableTaxCountries[0]?.country || ''">＋ 添加国家</button><label>⌕<input v-model="financeTaxCountrySearch" placeholder="搜索已添加国家"></label></aside></header>
            <div v-if="financeTaxCountryAddOpen" class="tax-add-row"><label class="tax-add-search">⌕<input v-model="financeTaxCountryAddSearch" autofocus placeholder="输入国家或代码搜索"></label><select v-model="financeTaxCountryAdd"><option v-if="!filteredAvailableTaxCountries.length" value="" disabled>没有匹配的国家</option><option v-for="setting in filteredAvailableTaxCountries" :key="setting.country" :value="setting.country">{{ setting.country }} · {{ financeCountrySettingMap.get(setting.country)?.code || '—' }}</option></select><button class="primary" type="button" :disabled="!financeTaxCountryAdd" @click="addTaxCountry">确认添加</button><button type="button" @click="financeTaxCountryAddOpen=false;financeTaxCountryAdd='';financeTaxCountryAddSearch=''">取消</button></div>
            <div class="tax-country-head"><span>国家</span><span>关税（USD/单）</span><span>状态</span><span>操作</span></div>
            <div class="tax-country-rows">
              <article v-for="setting in financeTaxCountries" :key="setting.country">
                <span><b>{{ setting.country }}</b><small>{{ financeCountrySettingMap.get(setting.country)?.code || '—' }}</small></span>
                <label><i>$</i><input v-model.number="setting.fixedFeeUsd" :aria-label="`${setting.country}关税`" type="number" min="0" step="0.01"><strong>/ 单</strong><small>≈ ¥{{ fixedFeeCny(setting.fixedFeeUsd) }}</small></label>
                <em :class="{ active:setting.fixedFeeUsd>0 }">{{ setting.fixedFeeUsd>0 ? '已启用' : '待设置' }}</em>
                <button class="tax-remove-button" type="button" :aria-label="`删除${setting.country}关税设置`" @click="removeTaxCountry(setting)">删除</button>
              </article>
              <p v-if="!financeTaxCountries.length" class="tax-empty">还没有国家关税设置，点击“添加国家”开始配置</p>
            </div>
            <footer>{{ financeTaxPreview }}</footer>
          </section>
          <section class="tax-provider-global">
            <header><div><b>物流商税务属性 <em>全局</em></b><span>统一设置一次，适用于该物流商旗下全部渠道。</span></div><aside><button class="tax-add-button" type="button" :disabled="!availableTaxProviders.length" @click="financeTaxProviderAddOpen=true;financeTaxProviderAddSearch='';financeTaxProviderAdd=availableTaxProviders[0]?.provider || ''">＋ 添加物流商</button><label>⌕<input v-model="financeTaxProviderSearch" placeholder="搜索已添加物流商"></label></aside></header>
            <div v-if="financeTaxProviderAddOpen" class="tax-add-row"><label class="tax-add-search">⌕<input v-model="financeTaxProviderAddSearch" autofocus placeholder="输入物流商或渠道名称搜索"></label><select v-model="financeTaxProviderAdd"><option v-if="!filteredAvailableTaxProviders.length" value="" disabled>没有匹配的物流商</option><option v-for="setting in filteredAvailableTaxProviders" :key="setting.provider" :value="setting.provider">{{ setting.provider }} · {{ setting.channels.length }}个渠道</option></select><button class="primary" type="button" :disabled="!financeTaxProviderAdd" @click="addTaxProvider">确认添加</button><button type="button" @click="financeTaxProviderAddOpen=false;financeTaxProviderAdd='';financeTaxProviderAddSearch=''">取消</button></div>
            <div class="tax-provider-head"><span>物流商</span><span>覆盖渠道</span><span>税务属性</span><span>操作</span></div>
            <div class="tax-provider-list-compact">
              <article v-for="setting in filteredTaxProviders" :key="setting.provider">
                <span><b>{{ setting.provider }}</b><small>{{ setting.channels.slice(0,2).map(item=>item.channel).join('、') }}{{ setting.channels.length>2?'…':'' }}</small></span>
                <span>{{ setting.channels.length }} 个渠道</span>
                <div><button type="button" :class="{ active:setting.mode==='exempt' }" @click="changeProviderTaxMode(setting,'exempt')">免税</button><button type="button" :class="{ active:setting.mode==='taxable' }" @click="changeProviderTaxMode(setting,'taxable')">不免税</button></div>
                <button class="tax-remove-button" type="button" :aria-label="`删除${setting.provider}税务设置`" @click="removeTaxProvider(setting)">删除</button>
              </article>
              <p v-if="!filteredTaxProviders.length" class="tax-empty">还没有物流商税务设置，点击“添加物流商”开始配置</p>
            </div>
            <footer>ⓘ 免税物流商不叠加国家关税；不免税物流商按上方国家固定金额计入整张报价单一次。</footer>
          </section>
        </div>
      </section>
      <section v-else-if="mode!=='members'" class="toolbar"><label><span>⌕</span><input v-model="search" placeholder="搜索当前模块数据"></label><select><option>全部状态</option><option>启用</option><option>草稿</option></select><button @click="search = ''">重置筛选</button><span>共 {{ filteredRows.length }} 条数据</span></section>

      <section v-if="mode==='members' && financeSettingsLoadState==='ready' && financeSettingsTab==='logistics'" class="finance-logistics-workspace">
        <header class="finance-logistics-heading"><div><small>ACTIVE LOGISTICS AUTHORIZATION</small><h2>物流属性与渠道授权</h2><p>引用物流模块当前正式价格；这里只维护报价可用的物流属性、国家与渠道。</p></div><aside><span><b>{{ financePolicyCategoryCount }}</b> 个物流属性</span><span><b>{{ financePolicyCountryCount }}</b> 个授权国家</span><span><b>{{ financePolicyChannelCount }}</b> 个渠道配置</span></aside></header>
        <FilterPanel v-model:search="financeFilterSearch" v-model:status="financeFilterStatus" v-model:country="financeFilterCountry" v-model:provider="financeFilterProvider" :country-options="financeFilterCountryOptions" :provider-options="financeFilterProviderOptions" :total="filteredFinancePolicyCards.length" @reset="resetFinanceFilters" />
        <div class="finance-card-list">
          <header class="finance-list-head"><span>物流属性</span><span>国家</span><span>服务商</span><span>渠道</span><span>匹配</span><span>授权状态</span><span>更新时间</span><span>操作</span></header>
          <LogisticsCard v-for="card in filteredFinancePolicyCards" :key="card.key" :attribute="card.policy.category" :countries="card.countries" :preferred-country="financeFilterCountry || card.countries.find(item => !financeFilterProvider || item.groups.some(group => group.provider === financeFilterProvider))?.country" :status="card.policy.enabled ? '启用' : '停用'" :updated-at="card.policy.updatedAt" @maintain="openFinancePolicyEditor(card.policy)" @remove="removeFinancePolicy(card.policy)" />
          <div v-if="!filteredFinancePolicyCards.length" class="finance-empty"><b>{{ hasActiveFinanceFilters ? '没有找到匹配的物流策略' : '尚未配置物流属性授权' }}</b><span>{{ hasActiveFinanceFilters ? '请调整关键词或筛选条件后重试' : '物流价格仍由物流模块维护；在这里新增报价可用的国家和渠道授权。' }}</span><button v-if="!hasActiveFinanceFilters" type="button" @click="openFinancePolicyEditor()">＋ 新增物流属性策略</button></div>
        </div>
      </section>

      <section v-else-if="mode!=='members' || (financeSettingsLoadState==='ready' && financeSettingsTab!=='countries' && financeSettingsTab!=='taxes')" class="table-card">
        <table v-if="mode === 'products'" class="product-table">
          <colgroup><col class="product-main-col"><col class="product-price-col"><col class="product-weight-col"><col class="product-freight-col"><col class="product-spec-col"><col class="product-status-col"><col class="product-actions-col"></colgroup>
          <thead><tr><th>商品 / SKU</th><th>采购价格（CNY）</th><th>重量与起订量</th><th>国内运费档位（CNY）</th><th>规格信息</th><th>资料状态</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="p in pagedProductRows" :key="`${p.sku}-${p.sourceRow}`">
              <td class="product-cell">
                <div class="item product-item">
                  <button v-if="p.image" class="image-thumb" type="button" :aria-label="`查看 ${p.sku} 商品大图`" @click="openImagePreview(p.image, p.sku)"><img :src="p.image" loading="lazy" :alt="p.sku"></button>
                  <i v-else>{{ p.sku.slice(0,1) }}</i>
                  <span><b>{{ purchaseDisplayName(p) }}</b><small>{{ p.sku }}</small><small class="product-maintainer">采购：{{ p.quotationOwner || '待补充' }} · {{ p.quotationDate || `原表第${p.sourceRow}行` }}</small></span>
                </div>
              </td>
              <td>
                <div class="purchase-price-cell">
                  <div class="purchase-price-top"><span>基准单价</span><strong>{{ p.purchasePriceCny == null ? '待补充' : `¥${p.purchasePriceCny.toFixed(2)}` }}</strong></div>
                  <div class="purchase-price-grid"><span v-for="quantity in [1, 10, 100]" :key="quantity"><small>{{ quantity }}件{{ quantity < (p.minOrderQty || 1) ? '参考' : '' }}</small><b>{{ purchasePriceAt(p, quantity) }}</b></span></div>
                </div>
              </td>
              <td>
                <div class="weight-cell"><small>商品重量</small><b>{{ p.weightG == null ? '待补充' : `${Math.ceil(p.weightG)} g` }}</b><em class="min-order">起订 {{ p.minOrderQty || 1 }} 件</em></div>
              </td>
              <td>
                <div class="freight-tier-list"><span v-for="quantity in [1, 10, 100]" :key="quantity"><b>{{ quantity }}件</b><strong>{{ purchaseFreightUnitAt(p, quantity) }}</strong><small>合计 {{ purchaseFreightAt(p, quantity) }}</small></span></div>
              </td>
              <td><div class="spec-cell"><b>{{ p.size || '待补充尺寸' }}</b><small>{{ p.material || '待补充材质' }}</small><small>{{ p.colorSku || '待补充颜色 / 子SKU' }}</small></div></td>
              <td><div class="status-cell"><em :class="{ warn:p.status !== '资料完整' }">{{ p.status }}</em><small>库存：{{ p.stockStatus }}</small><small>{{ p.status === '资料完整' ? '可直接报价' : '请补齐关键资料' }}</small></div></td>
              <td class="product-actions"><button class="link" @click="openProductDetail(p)">查看详情</button><button class="link" @click="openProductEditor(p)">编辑</button></td>
            </tr>
          </tbody>
        </table>
        <table v-else-if="mode === 'logistics'"><thead><tr><th>规则名称</th><th>物流商</th><th>类型</th><th>适用国家 / 区域</th><th>重量限制</th><th>计费方式</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-for="r in filteredRows" :key="r.name"><td><b>{{ r.name }}</b></td><td>{{ r.carrier }}</td><td><span class="tag">{{ r.type }}</span></td><td>{{ r.countries }}</td><td>{{ r.weight }}</td><td>{{ r.price }}</td><td><em :class="{ warn:r.status !== '启用' }">{{ r.status }}</em></td><td><button class="link" @click="showEditor=true">编辑</button><button class="link" @click="toast('已打开区域及条件限制')">区域/限制</button></td></tr></tbody></table>
        <table v-else-if="mode === 'members' && financeSettingsTab==='logistics'"><thead><tr><th>物流属性</th><th>支持国家及具体物流渠道</th><th>国家数量</th><th>渠道配置数</th><th>状态</th><th>更新时间</th><th>操作</th></tr></thead><tbody><tr v-for="policy in filteredRows" :key="policy.id"><td><span class="finance-tag">{{ policy.category }}</span></td><td><div class="country-policy-list"><div v-for="rule in policy.countryRules" :key="rule.country"><b>{{ rule.country }}</b><div class="carrier-list"><span v-for="channelKey in rule.allowedChannels" :key="channelKey"><template v-if="financeChannelForKey(rule.country,channelKey,policy.category)">{{ financeChannelForKey(rule.country,channelKey,policy.category)?.carrier }}｜{{ financeChannelForKey(rule.country,channelKey,policy.category)?.channel }}<em v-if="rule.country==='澳大利亚'" :class="{ warn:financeChannelForKey(rule.country,channelKey,policy.category)?.missingQuoteRegions.length }">{{ financeChannelForKey(rule.country,channelKey,policy.category)?.missingQuoteRegions.length ? `缺${financeChannelForKey(rule.country,channelKey,policy.category)?.missingQuoteRegions.join('、')}` : '覆盖1～4区' }}</em></template></span><span v-for="legacy in rule.unavailableChannels || []" :key="legacy.legacyKey" class="legacy-channel" :title="legacy.reason">待审｜{{ legacy.providerName }}｜{{ legacy.channelName }}</span></div></div></div></td><td>{{ policy.countryRules.length }} 个</td><td>{{ financePolicyCarrierCount(policy) }} 个<small v-if="financePolicyUnavailableCount(policy)" class="legacy-count">待审 {{ financePolicyUnavailableCount(policy) }} 个</small></td><td><em :class="{ warn:!policy.enabled }">{{ policy.enabled ? '启用' : '停用' }}</em></td><td>{{ policy.updatedAt }}</td><td><button class="link" @click="openFinancePolicyEditor(policy)">统一维护</button><button class="link danger" @click="removeFinancePolicy(policy)">删除</button></td></tr></tbody></table>
        <div v-else-if="mode === 'members' && financeSettingsTab==='grades'" class="grade-settings"><header><div><b>S–E 客户等级计算系数</b><small>最终报价 = 综合成本 × 客户等级系数；美元报价按财务维护的汇率换算。</small></div><button class="primary" @click="saveGradeSettings">保存等级系数</button></header><div class="grade-grid"><label v-for="setting in customerGradeSettings" :key="setting.grade"><strong>{{ setting.grade }}级客户</strong><span>计算系数</span><input v-model.number="setting.coefficient" type="number" min="0" step="0.01"><small>成本 ¥100 → 报价 ¥{{ (100 * setting.coefficient).toFixed(2) }}</small><em><input v-model="setting.enabled" type="checkbox"> 启用</em></label></div></div>
        <div v-else-if="mode === 'members' && financeSettingsTab==='exchange'" class="exchange-settings"><header><div><b>美元兑人民币汇率</b><small>业务报价、国际运费美元展示、多国家报价矩阵及 Excel 导出统一使用此汇率。</small></div><span>最近更新：{{ financeExchangeRate.updatedAt }}</span></header><section><label><span>1 美元（USD）兑换人民币（CNY）</span><div><b>1 USD =</b><input v-model.number="financeExchangeRate.usdCny" type="number" min="0.0001" step="0.0001"><strong>CNY</strong></div><small>当前示例：人民币 ¥100.00 = 美元 ${{ (100 / Math.max(0.0001, financeExchangeRate.usdCny || 0.0001)).toFixed(2) }}</small></label><aside><b>汇率使用说明</b><p>保存后新打开的业务报价立即采用新汇率；已保存的历史报价应保留当时的汇率快照。</p></aside></section><footer><button class="primary" @click="saveExchangeRateSetting">保存美元汇率</button></footer></div>
        <table v-else><thead><tr><th>报价单号 / SKU</th><th>国家</th><th>报价对象</th><th>物流规则</th><th>成本</th><th>报价</th><th>汇率快照</th><th>操作人 / 时间</th><th>状态</th></tr></thead><tbody><tr v-for="h in filteredRows" :key="h.no"><td><b>{{ h.no }}</b><small>{{ h.sku }}</small></td><td>{{ h.country }}</td><td>{{ h.member }}</td><td>{{ h.rule }}</td><td>¥ {{ h.cost.toFixed(2) }}</td><td><b class="orange">¥ {{ h.quote.toFixed(2) }}</b></td><td>{{ h.rate }}</td><td><b>{{ h.operator }}</b><small>{{ h.time }}</small></td><td><em>{{ h.status }}</em></td></tr></tbody></table>
        <div v-if="mode==='products' && filteredRows.length" class="pagination"><span>每页 <select v-model.number="productPageSize"><option :value="20">20</option><option :value="50">50</option></select> 条</span><button :disabled="productPage===1" @click="productPage--">上一页</button><b>第 {{ productPage }} / {{ productPageCount }} 页</b><button :disabled="productPage===productPageCount" @click="productPage++">下一页</button></div>
        <div v-if="!filteredRows.length" class="empty">没有找到符合条件的数据</div>
      </section>
    </main>

    <div v-if="countryPickerStage" class="mask country-picker-mask" @click.self="countryPickerStage=null">
      <section class="country-picker-dialog" role="dialog" aria-modal="true" aria-label="添加国家">
        <button class="close" type="button" @click="countryPickerStage=null">×</button>
        <header><div><small>COMMON COUNTRIES</small><h2>添加常用国家</h2><p>可按国家、代码或大洲搜索并多选；只有常用国家会显示在业务报价中。</p></div><em>已选择 {{ countryPickerSelected.length }} 个</em></header>
        <label class="country-picker-search">⌕<input v-model="countryPickerSearch" autofocus placeholder="搜索国家、代码或大洲"></label>
        <div class="country-picker-all-list">
          <label v-for="setting in countryPickerOptions" :key="setting.country" :class="{ current:setting.enabled && setting.stage===countryPickerStage, selected:countryPickerSelected.includes(setting.country) }">
            <input type="checkbox" :checked="countryPickerSelected.includes(setting.country)" :disabled="setting.enabled && setting.stage===countryPickerStage" @change="toggleCountryPickerSelection(setting.country)">
            <span><b>{{ setting.country }}</b><small>{{ setting.code }} · {{ setting.continent }}</small></span>
            <em v-if="setting.enabled && setting.stage==='common'">已是常用</em><em v-else>未加入常用</em>
          </label>
          <p v-if="!countryPickerOptions.length" class="country-setting-empty">没有找到匹配国家</p>
        </div>
        <footer><button type="button" @click="countryPickerStage=null">取消</button><button class="primary" type="button" :disabled="!countryPickerSelected.length" @click="confirmCountryPicker">添加所选国家</button></footer>
      </section>
    </div>

    <div v-if="detailProduct" class="mask detail-mask" @click.self="detailProduct=null">
      <section class="detail-card" role="dialog" aria-modal="true" :aria-label="`${detailProduct.sku} 采购资料详情`">
        <button class="close" type="button" aria-label="关闭详情" @click="detailProduct=null">×</button>
        <header class="detail-head">
          <div><small>PURCHASE SOURCE DETAIL</small><h2>采购资料完整详情</h2><p>按原 Excel 列头顺序展示 · 原表第 {{ detailProduct.sourceRow }} 行</p></div>
          <button v-if="detailProduct.image" class="detail-image" type="button" @click="openImagePreview(detailProduct.image, detailProduct.sku)"><img :src="detailProduct.image" :alt="detailProduct.sku"><span>点击查看大图</span></button>
        </header>
        <div class="detail-grid">
          <div><small>开票信息</small><p>{{ detailProduct.invoiceInfo || '—' }}</p></div>
          <div><small>SKU</small><p>{{ detailProduct.sku }}</p></div>
          <div><small>报价人</small><p>{{ detailProduct.quotationOwner || '—' }}</p></div>
          <div><small>报价日期</small><p>{{ detailProduct.quotationDate || '—' }}</p></div>
          <div class="detail-wide"><small>备注</small><p>{{ detailProduct.notes || '—' }}</p></div>
          <div><small>产品图片</small><p>{{ detailProduct.image ? '已上传，可点击上方图片查看' : '—' }}</p></div>
          <div><small>是否有货</small><p>{{ detailProduct.stockStatus }}</p></div>
          <div><small>克重/g（原始说明）</small><p>{{ detailProduct.weightDescription || '—' }}</p></div>
          <div><small>克重/g（数值）</small><p>{{ detailProduct.weightG == null ? '—' : `${Math.ceil(detailProduct.weightG)} g（克）` }}</p></div>
          <div><small>尺码</small><p>{{ detailProduct.size || '—' }}</p></div>
          <div><small>颜色/SKU</small><p>{{ detailProduct.colorSku || '—' }}</p></div>
          <div><small>材质</small><p>{{ detailProduct.material || '—' }}</p></div>
          <div><small>起订量</small><p>{{ detailProduct.minOrderQty }} 件</p></div>
          <div><small>单价（CNY／人民币）</small><p>{{ detailProduct.rawTierPrice || '—' }}</p></div>
          <div><small>L6单价（CNY／人民币）</small><p>{{ detailProduct.l6Price || '—' }}</p></div>
          <div class="detail-wide"><small>运费/试拍或议价（原始说明，CNY／人民币）</small><p>{{ detailProduct.freightTrial || '—' }}</p></div>
          <div class="detail-wide"><small>采购运费档位（CNY／人民币）</small><div class="freight-tier-view"><span><b>1件</b>合计 {{ detailProduct.singleFreightCny == null ? '—' : `¥${detailProduct.singleFreightCny.toFixed(2)}` }}<i>单件 {{ detailProduct.singleFreightCny == null ? '—' : `¥${detailProduct.singleFreightCny.toFixed(2)}` }}</i></span><span><b>10件</b>合计 {{ detailProduct.freight10Cny == null ? '—' : `¥${detailProduct.freight10Cny.toFixed(2)}` }}<i>单件 {{ detailProduct.freight10Cny == null ? '—' : `¥${(detailProduct.freight10Cny / 10).toFixed(2)}` }}</i></span><span><b>100件</b>合计 {{ detailProduct.freight100Cny == null ? '—' : `¥${detailProduct.freight100Cny.toFixed(2)}` }}<i>单件 {{ detailProduct.freight100Cny == null ? '—' : `¥${(detailProduct.freight100Cny / 100).toFixed(2)}` }}</i></span></div></div>
          <div><small>报价（CNY／人民币）</small><p>{{ detailProduct.purchasePriceCny == null ? '—' : `¥${detailProduct.purchasePriceCny}` }}</p></div>
          <div><small>含票价（CNY／人民币）</small><p>{{ detailProduct.taxIncludedPrice || '—' }}</p></div>
          <div><small>票点</small><p>{{ detailProduct.taxPoint || '—' }}</p></div>
          <div><small>票类型</small><p>{{ detailProduct.invoiceType || detailProduct.taxDifference || '—' }}</p></div>
          <div class="detail-wide"><small>工厂信息</small><p>{{ detailProduct.factoryInfo || detailProduct.packagingInfo || '—' }}</p></div>
          <div class="detail-wide"><small>审核备注</small><p>{{ detailProduct.auditNotes || '—' }}</p></div>
          <div v-for="index in 4" :key="index"><small>{{ index === 4 ? '相似货源' : `货源${index}` }}</small><p><a v-if="detailProduct.sourceLinks[index - 1]" :href="detailProduct.sourceLinks[index - 1]" target="_blank" rel="noopener noreferrer">打开货源链接</a><span v-else>—</span></p></div>
          <div class="detail-wide"><small>备注（补充）</small><p>{{ detailProduct.otherNotes || '—' }}</p></div>
          <div class="detail-wide"><small>更多</small><p>{{ detailProduct.more || '—' }}</p></div>
        </div>
        <footer><button type="button" @click="detailProduct=null">关闭</button><button class="primary" type="button" @click="openProductEditor(detailProduct); detailProduct=null">编辑资料</button></footer>
      </section>
    </div>

    <div v-if="showEditor" class="mask" @click.self="showEditor=false"><section class="editor" :class="{ 'product-editor': mode==='products', 'finance-editor': mode==='members' }"><button class="close" @click="showEditor=false">×</button><small>{{ config[0] }}</small><h2>{{ mode==='products' && editingSourceRow !== null ? '编辑采购资料' : mode==='members' && editingFinancePolicyId ? '维护物流属性策略' : config[3] }}</h2>
      <div v-if="mode==='members' && financeLogisticsContextState!=='ready'" class="finance-context-status" role="status"><span>{{ financeLogisticsContextState==='loading' ? '正在核对最新物流渠道，请稍候…' : financeLogisticsContextError }}</span><button v-if="financeLogisticsContextState==='error'" type="button" @click="hydrateFinanceLogisticsContext()">重新加载</button></div>
      <div v-if="mode==='products'" class="form product-form">
        <h3 class="section-title">采购资料字段</h3>
        <label data-source-field>开票信息<input v-model="productForm.invoiceInfo"></label>
        <label data-source-field>SKU<input v-model="productForm.sku" placeholder="必填且不可重复"></label>
        <label data-source-field>报价人<input v-model="productForm.quotationOwner"></label>
        <label data-source-field>报价日期<input v-model="productForm.quotationDate" type="date"></label>
        <label class="wide" data-source-field>备注<textarea v-model="productForm.notes" rows="3"></textarea></label>
        <label class="wide" data-source-field>产品图片<div class="image-upload-row"><img v-if="productForm.image" :src="productForm.image" alt="当前商品图片"><input type="file" accept="image/*" @change="handleProductImage"><button v-if="productForm.image" type="button" @click.prevent="productForm.image=''">移除图片</button></div></label>
        <label data-source-field>是否有货<select v-model="productForm.stockStatus"><option value="有货">有货</option><option value="无货">无货</option><option value="待确认">待确认</option></select></label>
        <label data-source-field>克重/g（原始说明）<textarea v-model="productForm.weightDescription" rows="2" placeholder="保留原表中的重量说明"></textarea></label>
        <label data-source-field>克重/g（数值）<input v-model.number="productForm.weightG" type="number" min="0" step="1"></label>
        <label data-source-field>尺码<textarea v-model="productForm.size" rows="2"></textarea></label>
        <label data-source-field>颜色/SKU<textarea v-model="productForm.colorSku" rows="2"></textarea></label>
        <label data-source-field>材质<input v-model="productForm.material"></label>
        <label data-source-field>起订量（件）<input v-model.number="productForm.minOrderQty" type="number" min="1"></label>
        <label data-source-field>单价/阶梯价原文（CNY／人民币）<textarea v-model="productForm.rawTierPrice" rows="2"></textarea></label>
        <label data-source-field>L6单价（CNY／人民币）<input v-model="productForm.l6Price"></label>
        <label class="wide" data-source-field>运费/试拍或议价（原始说明，CNY／人民币）<textarea v-model="productForm.freightTrial" rows="3"></textarea></label>
        <div class="wide source-field-control" data-source-field><span class="field-label">采购运费档位（由原表“1件运费”和试拍说明拆分）</span><div class="freight-tier-editor"><label>1件运费合计（CNY）<input v-model.number="productForm.singleFreightCny" type="number" min="0" step="0.01"><small>单件分摊：{{ productForm.singleFreightCny == null ? '—' : `¥${Number(productForm.singleFreightCny).toFixed(2)}` }}</small></label><label>10件运费合计（CNY）<input v-model.number="productForm.freight10Cny" type="number" min="0" step="0.01"><small>单件分摊：{{ productForm.freight10Cny == null ? '—' : `¥${(Number(productForm.freight10Cny) / 10).toFixed(2)}` }}</small></label><label>100件运费合计（CNY）<input v-model.number="productForm.freight100Cny" type="number" min="0" step="0.01"><small>单件分摊：{{ productForm.freight100Cny == null ? '—' : `¥${(Number(productForm.freight100Cny) / 100).toFixed(2)}` }}</small></label></div></div>
        <label data-source-field>报价（CNY／人民币）<input v-model.number="productForm.purchasePriceCny" type="number" min="0" step="0.01"></label>
        <label data-source-field>含票价（CNY／人民币）<input v-model="productForm.taxIncludedPrice"></label>
        <label data-source-field>票点<input v-model="productForm.taxPoint"></label>
        <label data-source-field>票类型<input v-model="productForm.invoiceType"></label>
        <label class="wide" data-source-field>工厂信息<textarea v-model="productForm.factoryInfo" rows="3"></textarea></label>
        <label class="wide" data-source-field>审核备注<textarea v-model="productForm.auditNotes" rows="3"></textarea></label>
        <label data-source-field>货源1<input v-model="productForm.sourceLinks[0]" type="url" placeholder="https://"></label>
        <label data-source-field>货源2<input v-model="productForm.sourceLinks[1]" type="url" placeholder="https://"></label>
        <label data-source-field>货源3<input v-model="productForm.sourceLinks[2]" type="url" placeholder="https://"></label>
        <label data-source-field>相似货源<input v-model="productForm.sourceLinks[3]" type="url" placeholder="https://"></label>
        <label class="wide" data-source-field>备注（补充）<textarea v-model="productForm.otherNotes" rows="3"></textarea></label>
        <label class="wide" data-source-field>更多<textarea v-model="productForm.more" rows="3"></textarea></label>
        <h3 class="section-title">系统辅助字段</h3>
        <label>商品名称<input v-model="productForm.name" placeholder="原表无商品名称，可在系统补充"></label>
      </div>
      <div v-else-if="mode==='logistics'" class="form"><label>规则名称<input value="新运费规则"></label><label>物流商<select><option>云途物流</option><option>燕文物流</option></select></label><label>规则类型<select><option>专线</option><option>挂号</option></select></label><label>适用国家<select><option>常用欧洲国家</option><option>北美国家</option></select></label><label>基础费<input type="number" value="0"></label><label>每 1000g 单价<input type="number" value="0"></label><label>挂号费<input type="number" value="0"></label><label>最大重量（g）<input type="number" value="2000" min="0" step="1"></label></div>
      <div v-else-if="mode==='members'" class="form finance-policy-form" :inert="financeLogisticsContextState!=='ready'">
        <label>物流属性<div class="finance-attribute-combobox"><input ref="financeAttributeInput" :value="financePolicyForm.category" autocomplete="off" placeholder="选择或输入物流属性" role="combobox" :aria-expanded="financeAttributePickerOpen" @focus="openFinanceAttributePicker" @click="openFinanceAttributePicker" @input="updateFinanceAttribute" @blur="closeFinanceAttributePicker" @keydown.esc="financeAttributePickerOpen=false"><button type="button" aria-label="展开物流属性" @mousedown.prevent="toggleFinanceAttributePicker">⌄</button><div v-if="financeAttributePickerOpen" class="finance-attribute-menu" role="listbox"><button v-for="attribute in filteredFinanceAttributeOptions" :key="attribute" type="button" :class="{ active:attribute===financePolicyForm.category }" @mousedown.prevent="selectFinanceAttribute(attribute)">{{ attribute }}</button><p v-if="!filteredFinanceAttributeOptions.length && financePolicyForm.category.trim()">按当前输入创建“{{ financePolicyForm.category.trim() }}”</p></div></div><small>点击显示已有属性，也可以直接输入新的物流属性</small></label>
        <label>状态<select v-model="financePolicyForm.enabled"><option :value="true">启用</option><option :value="false">停用</option></select></label>
        <div class="wide country-rule-editor">
          <header><div><b>支持国家与渠道</b><small>这里只维护当前物流属性允许的国家和渠道；业务报价仅展示“常用国家设置”中的国家。</small></div><button type="button" @click="addFinanceCountryRule">＋ 添加匹配国家</button></header>
          <section v-for="(rule,index) in financePolicyForm.countryRules" :key="index" class="country-rule-card">
            <div class="country-rule-head"><label class="country-search-label">支持国家（可输入搜索）<div class="country-picker"><span><b>⌕</b><input :value="financeCountrySearches[index] ?? rule.country" autocomplete="off" placeholder="输入国家名称搜索" role="combobox" :aria-expanded="openFinanceCountryPicker===index" @focus="openFinanceCountrySearch(index)" @input="updateFinanceCountrySearch(index,$event)" @blur="closeFinanceCountrySearch(index)" @keydown.esc="openFinanceCountryPicker=null"></span><div v-if="openFinanceCountryPicker===index" class="country-picker-menu" role="listbox"><button v-for="country in filteredFinanceCountries(index)" :key="country.code || country.name" type="button" :class="{ active:country.name===rule.country }" @mousedown.prevent="selectFinanceCountry(index,country.name)"><strong>{{ country.name }}</strong><small>{{ country.code }}</small></button><p v-if="!filteredFinanceCountries(index).length">没有匹配的国家</p></div></div></label><button type="button" @click="removeFinanceCountryRule(index)">移除国家</button></div>
            <div class="country-policy-classification"><b>{{ financeCountryStageDisplay(rule.country) }}</b><span>{{ financeCountrySettingMap.get(rule.country)?.continent || rule.continent }} · 已选 {{ rule.allowedChannels.length }}/{{ financeChannelsForCountry(rule.country).length }} 个渠道<span v-if="rule.unavailableChannels?.length"> · 待审旧渠道 {{ rule.unavailableChannels.length }} 个</span></span><button type="button" :aria-expanded="financeCountryRuleExpanded(index)" @click="toggleFinanceCountryRule(index)">{{ financeCountryRuleExpanded(index) ? '收起渠道 ↑' : '展开渠道 ↓' }}</button></div>
            <div v-if="financeCountryRuleExpanded(index) && rule.unavailableChannels?.length" class="legacy-review-summary"><span><b>待审旧渠道 {{ rule.unavailableChannels.length }} 个</b><small>这些旧渠道不参与当前报价，可按需核对。</small></span><button type="button" :aria-expanded="financeLegacyReviewExpanded(index)" @click="toggleFinanceLegacyReview(index)">{{ financeLegacyReviewExpanded(index) ? '收起旧渠道 ↑' : '查看旧渠道 ↓' }}</button></div>
            <div v-if="financeCountryRuleExpanded(index) && financeLegacyReviewExpanded(index) && rule.unavailableChannels?.length" class="legacy-review-list"><b>停用待审旧渠道</b><span v-for="legacy in rule.unavailableChannels" :key="legacy.legacyKey">{{ legacy.providerName }}｜{{ legacy.channelName }}<small>{{ legacy.status === 'ambiguous' ? '存在多个候选，未自动迁移' : '当前库没有可靠等价渠道' }}</small></span></div>
            <div v-if="financeCountryRuleExpanded(index) && financeChannelsForCountry(rule.country).length" class="finance-channel-cascade">
              <nav aria-label="物流商选择"><header><b>1</b><span>选择物流商</span></header><button v-for="group in financeCarrierGroupsForCountry(rule.country)" :key="group.carrier" type="button" :class="{ active:selectedFinanceCarrier(index)===group.carrier }" @click="selectFinanceCarrier(index,group.carrier)"><span>{{ group.carrier }}</span><em>{{ selectedFinanceCarrierChannelCount(index,group.carrier) }}/{{ group.channels.length }}</em></button></nav>
              <section><header><div><b>2</b><span>选择“{{ selectedFinanceCarrier(index) }}”下的渠道</span></div><small>该物流商共 {{ financeChannelsForSelectedCarrier(index).length }} 个可用渠道</small></header><div class="country-carrier-grid"><label v-for="option in financeChannelsForSelectedCarrier(index)" :key="option.key"><input v-model="rule.allowedChannels" type="checkbox" :value="option.key"><span><b>{{ option.channel }}</b><small>渠道编码：{{ option.channelCode || '—' }} · 计费规则：{{ option.ruleName }}</small><em v-if="rule.country==='澳大利亚'" :class="{ warn:option.missingQuoteRegions.length }">{{ option.missingQuoteRegions.length ? `分区不完整：缺${option.missingQuoteRegions.join('、')}` : `澳大利亚分区：${option.quoteRegions.join('、')}` }}</em><em v-else>适用国家：{{ rule.country }}</em></span></label></div></section>
            </div>
            <small v-if="financeCountryRuleExpanded(index) && !financeChannelsForCountry(rule.country).length">当前国家在启用的物流规则中暂无可配置渠道。</small>
          </section>
          <div class="add-country-bottom"><span>物流规则匹配 {{ financeLogisticsCountries.length }} 个可发国家，已选择 {{ financePolicyForm.countryRules.length }} 个</span><button type="button" @click="addFinanceCountryRule">＋ 继续添加匹配国家</button></div>
        </div>
      </div>
      <div v-else class="form"><label>规则名称<input value="新规则"></label><label>状态<select><option>启用</option><option>停用</option></select></label></div>
      <footer><button @click="showEditor=false">取消</button><button class="primary" :disabled="mode==='members' && (financeLogisticsContextState!=='ready' || financePolicySaving)" @click="saveEditor">{{ mode==='members' && financePolicySaving ? '正在保存…' : '保存设置' }}</button></footer></section></div>
    <div v-if="previewImage" class="image-preview" role="dialog" aria-modal="true" :aria-label="`${previewImageAlt} 商品图片预览`" @click.self="closeImagePreview">
      <button class="preview-close" type="button" aria-label="关闭图片预览" @click="closeImagePreview">×</button>
      <figure><img :src="previewImage" :alt="previewImageAlt"><figcaption>{{ previewImageAlt }}</figcaption></figure>
    </div>
    <Transition name="toast"><div v-if="notice" class="toast">✓ {{ notice }}</div></Transition>
  </div>
</template>

<style scoped>
.finance-context-status{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:12px 16px;margin-bottom:12px;background:#eef6ff;border:1px solid #bad8ff;border-radius:8px;color:#235886}.finance-policy-form[inert]{opacity:.55}.editor .primary:disabled{opacity:.5;cursor:wait}

:global(body){margin:0}.module-app{--o:#ff9910;--ink:#17232e;--line:#e3e8ec;min-height:100vh;background:#f4f6f8;color:var(--ink);font-family:Inter,"PingFang SC","Microsoft YaHei",sans-serif}.topbar{height:68px;display:flex;align-items:center;padding:0 4vw;background:#fff;border-bottom:1px solid var(--line);position:sticky;top:0;z-index:20}.brand{display:flex;align-items:center;gap:11px;margin-right:56px}.brand>span{width:39px;height:39px;display:grid;place-items:center;border-radius:10px;background:var(--o);font-size:21px;font-weight:950}.brand strong,.brand small,.user small,.item small,td>small{display:block}.brand small{color:#9199a2;font-size:8px;letter-spacing:.18em}.topbar nav{display:flex;align-items:center;gap:31px;height:100%}.topbar nav a{height:100%;display:flex;align-items:center;position:relative;color:#66717c;font-size:13px}.topbar nav a.active{color:var(--ink);font-weight:850}.topbar nav a.active:after{content:"";position:absolute;inset:auto 0 0;height:3px;background:var(--o)}.user{display:flex;align-items:center;gap:10px;margin-left:auto;font-size:11px}.user>span{width:35px;height:35px;display:grid;place-items:center;border-radius:50%;background:#1b2731;color:#fff}.user small{color:#929ba4}.page{width:min(1500px,94vw);margin:auto;padding:36px 0 70px}.heading{display:flex;align-items:flex-end;justify-content:space-between;margin-bottom:24px}.heading p{margin:0 0 8px;color:#dd7c00;font-size:10px;font-weight:900;letter-spacing:.2em}.heading h1{margin:0 0 7px;font-size:30px}.heading>div>span{color:#75808a;font-size:12px}.primary{height:40px;padding:0 20px;border:0;border-radius:7px;background:var(--o);font-weight:850}.stats{display:grid;grid-template-columns:repeat(4,1fr);gap:12px;margin-bottom:16px}.stats>div{padding:18px;background:#fff;border:1px solid var(--line);border-radius:8px}.stats small,.stats span{display:block;color:#89939c;font-size:9px}.stats b{display:block;margin:6px 0;font-size:23px}.orange{color:#dd7d00!important}.subtabs{display:flex;margin-bottom:16px;padding:7px;background:#fff;border:1px solid var(--line);border-radius:8px}.subtabs button{height:35px;padding:0 18px;border:0;border-radius:5px;background:none;color:#6f7a84;font-size:11px}.subtabs button.active{background:#1b2731;color:#fff}.toolbar{display:flex;align-items:center;gap:9px;margin-bottom:11px}.toolbar label{width:290px;height:39px;display:flex;align-items:center;gap:8px;padding:0 12px;background:#fff;border:1px solid #dce1e5;border-radius:7px}.toolbar input{width:100%;border:0;outline:0}.toolbar select,.toolbar>button{height:39px;border:1px solid #dce1e5;border-radius:7px;background:#fff;padding:0 12px}.toolbar>span{margin-left:auto;color:#89939c;font-size:10px}.table-card{overflow:auto;background:#fff;border:1px solid var(--line);border-radius:8px;box-shadow:0 14px 35px rgba(27,38,48,.05)}table{width:100%;border-collapse:collapse;white-space:nowrap}th{padding:13px 15px;background:#fafbfc;border-bottom:1px solid var(--line);color:#69747e;font-size:10px;text-align:left}td{padding:14px 15px;border-bottom:1px solid #edf0f2;color:#54606a;font-size:10px}td b{color:#24303a}.item{display:flex;align-items:center;gap:10px}.item i,.item img{width:42px;height:42px;display:grid;place-items:center;border-radius:7px;background:#e5eeef;color:#47787a;font-style:normal;font-weight:900;object-fit:cover}.item small,td>small{margin-top:4px;color:#939ca4}.tag{padding:4px 7px;border-radius:4px;background:#f0f3f5}em{padding:4px 8px;border-radius:999px;background:#e7f6ee;color:#218954;font-style:normal;font-size:9px}em.warn{background:#fff1d9;color:#ae7000}.link{margin-right:9px;border:0;background:none;color:#ce7500;font-size:10px}.empty{padding:70px;text-align:center;color:#919ba4}.pagination{display:flex;align-items:center;justify-content:flex-end;gap:8px;padding:12px 15px;background:#fafbfc;color:#69747e;font-size:10px}.pagination select,.pagination button{height:30px;border:1px solid #dce1e5;border-radius:5px;background:#fff;padding:0 10px}.pagination button:disabled{opacity:.45}.mask{position:fixed;inset:0;z-index:100;display:grid;place-items:center;background:rgba(17,24,31,.5);backdrop-filter:blur(4px)}.editor{position:relative;width:min(680px,92vw);max-height:86vh;overflow:auto;padding:30px;background:#fff;border-radius:12px}.close{position:absolute;right:17px;top:14px;border:0;background:none;font-size:22px}.editor>small{color:#db7d00;font-weight:800;letter-spacing:.16em}.editor h2{margin:8px 0 22px}.form{display:grid;grid-template-columns:1fr 1fr;gap:13px}.form label{display:grid;gap:6px;color:#6d7881;font-size:10px}.form label.wide{grid-column:1/-1}.form input,.form select,.form textarea{border:1px solid #dce1e5;border-radius:6px;padding:0 10px;font:inherit}.form input,.form select{height:38px}.form textarea{padding:10px;resize:vertical}.editor footer{display:flex;justify-content:flex-end;gap:9px;margin-top:24px}.editor footer>button:not(.primary){height:40px;padding:0 18px;border:1px solid #dce1e5;border-radius:7px;background:#fff}.toast{position:fixed;right:24px;bottom:24px;z-index:120;padding:13px 18px;border-radius:8px;background:#1b2630;color:#fff}.toast-enter-active,.toast-leave-active{transition:.2s}.toast-enter-from,.toast-leave-to{opacity:0;transform:translateY(8px)}
.product-editor{width:min(1080px,94vw)}.product-form{grid-template-columns:repeat(3,minmax(0,1fr))}.product-form .section-title{grid-column:1/-1;margin:10px 0 0;padding:10px 0;border-bottom:1px solid #e5eaee;color:#34434e;font-size:13px}.product-form .section-title:first-child{margin-top:-6px}.image-upload-row{display:flex;align-items:center;gap:10px;min-height:64px;padding:8px;border:1px solid #dce1e5;border-radius:6px;background:#fafbfc}.image-upload-row img{width:56px;height:56px;border-radius:6px;object-fit:cover}.image-upload-row input{height:auto;min-width:0;flex:1;padding:0;border:0}.image-upload-row button{height:30px;padding:0 10px;border:1px solid #e0e5e9;border-radius:5px;background:#fff;color:#a34f00;font-size:10px}.product-editor>footer{position:sticky;bottom:-30px;margin:24px -30px -30px;padding:16px 30px;background:#fff;border-top:1px solid #e5eaee;box-shadow:0 -10px 24px rgba(23,35,46,.05)}.detail-mask{z-index:105;padding:28px}.detail-card{position:relative;width:min(1080px,94vw);max-height:90vh;overflow:auto;padding:30px;background:#fff;border-radius:12px;box-shadow:0 24px 70px rgba(11,22,31,.28)}.detail-head{display:flex;align-items:flex-start;justify-content:space-between;gap:24px;padding:0 44px 22px 0;border-bottom:1px solid var(--line)}.detail-head small{color:#d87a00;font-size:10px;font-weight:900;letter-spacing:.16em}.detail-head h2{margin:7px 0 5px;font-size:24px}.detail-head p{margin:0;color:#89939c;font-size:11px}.detail-image{display:flex;align-items:center;gap:10px;padding:7px 12px 7px 7px;border:1px solid #dce3e8;border-radius:8px;background:#f7fafb;color:#61707b;font-size:10px;cursor:zoom-in}.detail-image img{width:58px;height:58px;border-radius:6px;object-fit:cover}.detail-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:10px;margin-top:18px}.detail-grid>div{min-width:0;padding:12px 14px;border:1px solid #e5eaee;border-radius:7px;background:#fafbfc}.detail-grid .detail-wide{grid-column:1/-1}.detail-grid small{display:block;margin-bottom:6px;color:#7d8993;font-size:9px}.detail-grid p{margin:0;overflow-wrap:anywhere;white-space:pre-wrap;color:#26343f;font-size:11px;line-height:1.65}.detail-grid a{color:#cf7500;text-decoration:underline;text-underline-offset:2px}.detail-card>footer{display:flex;justify-content:flex-end;gap:9px;margin-top:22px}.detail-card>footer>button:not(.primary){height:40px;padding:0 18px;border:1px solid #dce1e5;border-radius:7px;background:#fff}.image-thumb{width:42px;height:42px;flex:0 0 42px;padding:0;border:0;border-radius:7px;background:#e5eeef;overflow:hidden;cursor:zoom-in}.image-thumb img{width:100%;height:100%;display:block;object-fit:cover;transition:transform .18s ease}.image-thumb:hover img{transform:scale(1.06)}.image-thumb:focus-visible{outline:3px solid rgba(255,153,16,.35);outline-offset:2px}.image-preview{position:fixed;inset:0;z-index:130;display:grid;place-items:center;padding:32px;background:rgba(8,14,20,.84);backdrop-filter:blur(6px)}.image-preview figure{margin:0;max-width:min(920px,90vw);max-height:88vh;display:grid;gap:12px;justify-items:center}.image-preview img{display:block;max-width:100%;max-height:80vh;border-radius:10px;background:#fff;box-shadow:0 24px 70px rgba(0,0,0,.45);object-fit:contain}.image-preview figcaption{color:#fff;font-size:13px}.preview-close{position:fixed;right:28px;top:22px;width:42px;height:42px;border:1px solid rgba(255,255,255,.28);border-radius:50%;background:rgba(0,0,0,.35);color:#fff;font-size:28px;line-height:1;cursor:pointer}.preview-close:hover{background:rgba(255,255,255,.18)}
.product-form>.wide{grid-column:1/-1}.source-field-control{min-width:0;display:grid;gap:8px;color:#6d7881;font-size:10px}.field-label{font-weight:700;color:#45545f}.freight-tier-editor,.freight-tier-view{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px}.freight-tier-editor{padding:12px;border:1px solid #dce1e5;border-radius:7px;background:#fafbfc}.freight-tier-editor label{min-width:0;padding:12px;border-radius:6px;background:#fff;border:1px solid #e4e9ed;white-space:normal}.freight-tier-editor input{width:100%;box-sizing:border-box}.freight-tier-editor small{color:#d17400;font-weight:750}.freight-tier-view span{display:grid;gap:3px;padding:10px 12px;border-radius:6px;background:#f3f6f8;color:#4c5b66}.freight-tier-view b{color:#1e2d37}.freight-tier-view i{color:#d17400;font-style:normal;font-weight:750}
.finance-tag{display:inline-flex;padding:6px 10px;border-radius:5px;background:#fff1d7;color:#9d5a00;font-weight:850}.carrier-list{display:flex;flex-wrap:wrap;gap:5px;max-width:620px;white-space:normal}.carrier-list span{padding:4px 7px;border-radius:4px;background:#eef4f7;color:#425765}.country-policy-list{display:grid;gap:8px;min-width:520px;white-space:normal}.country-policy-list>div{display:grid;grid-template-columns:80px 1fr;gap:8px;align-items:start;padding:7px 0;border-bottom:1px dashed #e2e7ea}.country-policy-list>div:last-child{border-bottom:0}.country-policy-list b{padding-top:4px}.link.danger{color:#b64b3b}.finance-editor{width:min(960px,94vw)}.finance-policy-form{grid-template-columns:1fr 1fr}.finance-policy-form>.wide{grid-column:1/-1}.country-rule-editor{display:grid;gap:12px;padding:16px;border:1px solid #dce1e5;border-radius:8px;background:#f7f9fa;color:#4a5964;font-size:10px}.country-rule-editor>header{display:flex;align-items:center;justify-content:space-between;gap:16px;padding-bottom:2px}.country-rule-editor>header>div{display:grid;gap:3px}.country-rule-editor>header small{color:#89939c}.country-rule-editor button{height:34px;padding:0 12px;border:1px solid #dce3e8;border-radius:6px;background:#fff;color:#a85e00;font:inherit;font-weight:800;cursor:pointer}.country-rule-card{padding:14px;border:1px solid #dde4e8;border-radius:8px;background:#fff;box-shadow:0 4px 12px rgba(28,42,53,.035)}.country-rule-head{display:flex;align-items:flex-end;justify-content:space-between;gap:12px}.country-rule-head label{max-width:340px;flex:1}.country-rule-head>button{color:#a94d3e}.country-picker{position:relative}.country-picker>span{position:relative;display:block}.country-picker>span>b{position:absolute;left:11px;top:50%;z-index:1;transform:translateY(-50%);color:#7d8992;font-size:13px}.country-picker input{width:100%;box-sizing:border-box;padding-left:31px!important;padding-right:30px!important;background:#fff}.country-picker-menu{position:absolute;left:0;right:0;top:calc(100% + 5px);z-index:20;display:grid;max-height:330px;overflow:auto;padding:5px;border:1px solid #d6dee3;border-radius:7px;background:#fff;box-shadow:0 12px 28px rgba(22,35,45,.18)}.country-picker-menu button{height:31px;display:flex;align-items:center;justify-content:space-between;padding:0 9px;border:0;border-radius:4px;background:#fff;color:#35444f;font-weight:500;text-align:left}.country-picker-menu button:hover,.country-picker-menu button.active{background:#edf5ff;color:#1761b0}.country-picker-menu small{color:#9aa4ac;font-size:8px}.country-picker-menu p{margin:0;padding:13px;text-align:center;color:#909aa3}.country-carrier-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:8px;margin-top:12px}.country-carrier-grid label{display:flex!important;grid-template-columns:none!important;align-items:center;gap:8px;min-height:54px;padding:8px 10px;border:1px solid #e0e6ea;border-radius:6px;background:#fafbfc;color:#35444f;cursor:pointer;overflow-wrap:anywhere}.country-carrier-grid input{width:auto!important;height:auto!important;margin:0;flex:0 0 auto}.country-carrier-grid label>span{display:grid;gap:3px;min-width:0}.country-carrier-grid label>span>b{color:#24333e;font-size:10px}.country-carrier-grid label>span>small{color:#87939c;font-size:8px;line-height:1.4}.country-rule-card>small{display:block;margin-top:9px;color:#a36a17}.add-country-bottom{position:sticky;bottom:-16px;z-index:3;display:flex;align-items:center;justify-content:center;gap:14px;margin:0 -16px -16px;padding:13px 16px;background:rgba(247,249,250,.96);border-top:1px solid #dde4e8;box-shadow:0 -7px 16px rgba(31,45,56,.05);backdrop-filter:blur(6px)}.add-country-bottom span{color:#7f8992}.add-country-bottom button{min-width:150px;border-color:#f3b75f;background:#fff8e9;color:#a55b00}
.finance-attribute-combobox{position:relative}.finance-attribute-combobox>input{width:100%;box-sizing:border-box;padding-right:38px;background:#fff}.finance-attribute-combobox>button{position:absolute;right:1px;top:1px;width:36px;height:36px;border:0;border-left:1px solid #e1e6e9;border-radius:0 6px 6px 0;background:#fafbfc;color:#53616c;font-size:14px;cursor:pointer}.finance-attribute-combobox:focus-within>input{border-color:#ff9900;box-shadow:0 0 0 3px rgba(255,153,0,.13);outline:0}.finance-attribute-menu{position:absolute;left:0;right:0;top:calc(100% + 5px);z-index:30;display:grid;max-height:264px;overflow:auto;padding:5px;border:1px solid #d7dfe4;border-radius:7px;background:#fff;box-shadow:0 14px 30px rgba(21,34,45,.18)}.finance-attribute-menu>button{height:32px;padding:0 10px;border:0;border-radius:5px;background:#fff;color:#34434e;text-align:left;font-size:11px;cursor:pointer}.finance-attribute-menu>button:hover,.finance-attribute-menu>button.active{background:#fff1da;color:#9e5800;font-weight:800}.finance-attribute-menu>p{margin:0;padding:10px;color:#a05b00;font-size:10px;text-align:center}.finance-policy-form>label>small{color:#8a949c;font-size:9px}
.finance-tabs{gap:8px;margin-top:14px;padding:8px;background:#fffaf1;border-color:#f1d6ad}.finance-tabs button{min-width:190px;max-width:260px;border:1px solid #ffd39a;background:#fff1d8;color:#8d4d00;font-weight:800;transition:.18s ease}.finance-tabs button:hover{border-color:#ff9b18;background:#ffe3b6;color:#693800}.finance-tabs button.active{border-color:#eb8500;background:#ff9910;color:#17232e;box-shadow:0 4px 12px rgba(224,126,0,.25)}.grade-settings{padding:22px}.grade-settings>header{display:flex;align-items:center;justify-content:space-between;margin-bottom:18px}.grade-settings>header div{display:grid;gap:5px}.grade-settings>header small{color:#7f8992}.grade-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px}.grade-grid>label{display:grid;grid-template-columns:1fr auto;gap:8px 12px;padding:16px;border:1px solid #dde4e8;border-radius:8px;background:#fafbfc}.grade-grid strong{font-size:17px}.grade-grid span,.grade-grid small{color:#7f8992}.grade-grid input[type=number]{grid-column:1/-1;height:38px;border:1px solid #d9e0e5;border-radius:6px;padding:0 10px;font-size:16px;font-weight:800}.grade-grid em{display:flex;align-items:center;gap:5px;background:none;padding:0;color:#4d5b65}.grade-grid em input{width:auto;height:auto}
.finance-stats>div{height:100px;box-sizing:border-box;display:flex;align-items:center;gap:14px;padding:16px 18px;box-shadow:0 7px 22px rgba(23,35,46,.045)}.finance-stats>div>i{width:44px;height:44px;display:grid;place-items:center;flex:0 0 44px;border-radius:50%;background:#fff0d7;color:#be6900;font-size:14px;font-style:normal;font-weight:900}.finance-stats>div>section{min-width:0}.finance-stats small{color:#7d8790;font-size:10px;font-weight:700}.finance-stats b{margin:3px 0 2px;color:#17232e;font-size:26px;line-height:1.08}.finance-stats span{overflow:hidden;color:#929ba3;font-size:9px;text-overflow:ellipsis;white-space:nowrap}.finance-tabs{box-shadow:0 7px 18px rgba(24,38,50,.05)}
.finance-channel-cascade{display:grid;grid-template-columns:180px minmax(0,1fr);gap:12px;margin-top:12px;overflow:hidden;border:1px solid #e0e6ea;border-radius:8px;background:#f8fafb}.finance-channel-cascade>nav{display:flex;flex-direction:column;gap:4px;padding:10px;border-right:1px solid #e0e6ea;background:#f3f6f8}.finance-channel-cascade>nav header,.finance-channel-cascade>section>header>div{display:flex;align-items:center;gap:7px}.finance-channel-cascade header b{width:20px;height:20px;display:grid;place-items:center;padding:0;border-radius:50%;background:#ffedcf;color:#b66600}.finance-channel-cascade>nav header{padding:3px 5px 7px;color:#56656f;font-weight:800}.finance-channel-cascade>nav button{display:flex;align-items:center;justify-content:space-between;width:100%;height:auto;min-height:38px;padding:8px 9px;border-color:transparent;background:transparent;color:#4f5e68;text-align:left}.finance-channel-cascade>nav button:hover{background:#fff}.finance-channel-cascade>nav button.active{border-color:#ffad33;background:#fff7e8;color:#9d5900}.finance-channel-cascade>nav button em{font-style:normal;font-size:9px;color:#8a959d}.finance-channel-cascade>section{min-width:0;padding:12px}.finance-channel-cascade>section>header{display:flex;align-items:center;justify-content:space-between;gap:12px;color:#465660}.finance-channel-cascade>section>header small{color:#8c969e}.finance-channel-cascade .country-carrier-grid{grid-template-columns:repeat(2,minmax(0,1fr));margin-top:10px;max-height:228px;overflow:auto}.finance-channel-cascade .country-carrier-grid label{background:#fff}
.finance-logistics-workspace{overflow:hidden;padding:20px;border:1px solid #dfe6ea;border-radius:12px;background:#fff;box-shadow:0 8px 28px rgba(30,44,56,.06)}.finance-logistics-heading{display:flex;align-items:flex-end;justify-content:space-between;gap:22px;margin-bottom:16px}.finance-logistics-heading>div{display:grid;gap:4px}.finance-logistics-heading small{color:#d87900;font-size:9px;font-weight:900;letter-spacing:1.4px}.finance-logistics-heading h2{margin:0;color:#17232e;font-size:18px}.finance-logistics-heading p{margin:0;color:#7a858f;font-size:11px}.finance-logistics-heading aside{display:flex;gap:8px;flex-wrap:wrap;justify-content:flex-end}.finance-logistics-heading aside span{padding:7px 10px;border:1px solid #e5eaed;border-radius:8px;background:#f8fafb;color:#71808a;font-size:10px}.finance-logistics-heading aside b{color:#1f2f3b;font-size:13px}.finance-logistics-workspace :deep(.filter-panel){margin-bottom:18px;box-shadow:none}.finance-card-list{display:grid;gap:10px;min-width:0}.finance-list-head{display:grid;grid-template-columns:11% 12% 11% minmax(0,29%) 8% 7% 10% minmax(150px,12%);padding:0 16px;color:#77828b;font-size:10px;font-weight:800}.finance-list-head span{min-width:0;padding:0 12px}.finance-list-head span:last-child{text-align:right}.finance-empty{display:grid;justify-items:center;gap:7px;padding:58px 20px;border:1px dashed #d7dfe4;border-radius:8px;background:#fbfcfd;color:#35434e}.finance-empty:before{content:"⌕";width:42px;height:42px;display:grid;place-items:center;border-radius:50%;background:#fff0d7;color:#c66e00;font-size:22px}.finance-empty span{color:#8b959d;font-size:11px;text-align:center}.finance-empty button{height:34px;margin-top:5px;padding:0 14px;border:1px solid #ff9900;border-radius:6px;background:#fff;color:#c66e00;font-size:10px;font-weight:800}
@media(max-width:900px){.finance-logistics-heading{align-items:flex-start;flex-direction:column}.finance-logistics-heading aside{justify-content:flex-start}}
@media(max-width:900px){.finance-channel-cascade{grid-template-columns:150px minmax(0,1fr)}}
@media(max-width:1280px){.finance-list-head{grid-template-columns:10% 12% 11% minmax(0,28%) 8% 7% 10% minmax(150px,14%);padding-inline:10px}.finance-list-head span{padding-inline:8px}}
@media(max-width:900px){.product-form,.detail-grid{grid-template-columns:repeat(2,minmax(0,1fr))}.freight-tier-editor,.freight-tier-view{grid-template-columns:1fr}.country-carrier-grid{grid-template-columns:repeat(2,minmax(0,1fr))}}
@media(max-width:900px){.topbar nav{display:none}.stats{grid-template-columns:1fr 1fr}.subtabs{overflow-x:auto}.subtabs button{white-space:nowrap}.finance-list-head{display:none}}@media(max-width:620px){.brand{margin-right:0}.user div{display:none}.page{width:94vw;padding-top:22px}.heading{align-items:flex-start;gap:16px}.stats{grid-template-columns:1fr 1fr}.finance-stats>div{height:92px;padding:12px}.finance-stats>div>i{width:36px;height:36px;flex-basis:36px}.toolbar{flex-wrap:wrap}.toolbar label{width:100%}.toolbar>span{margin-left:0}.form{grid-template-columns:1fr}.country-carrier-grid{grid-template-columns:1fr}.country-rule-head{align-items:stretch;flex-direction:column}.country-rule-head label{max-width:none;width:100%}.detail-mask{padding:10px}.detail-card{padding:22px 16px}.detail-head{display:block;padding-right:28px}.detail-image{margin-top:14px}.detail-grid{grid-template-columns:1fr}.detail-grid .detail-wide{grid-column:auto}.image-preview{padding:18px}.preview-close{right:14px;top:14px}}
@media(max-width:620px){.finance-channel-cascade{grid-template-columns:1fr}.finance-channel-cascade>nav{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));border-right:0;border-bottom:1px solid #e0e6ea}.finance-channel-cascade>nav header{grid-column:1/-1}.finance-channel-cascade .country-carrier-grid{grid-template-columns:1fr}}
.exchange-settings{padding:24px}.exchange-settings>header{display:flex;align-items:flex-start;justify-content:space-between;gap:20px;padding-bottom:18px;border-bottom:1px solid #e6ebef}.exchange-settings>header div{display:grid;gap:6px}.exchange-settings>header b{font-size:17px}.exchange-settings>header small,.exchange-settings>header>span{color:#7f8992;font-size:10px}.exchange-settings>section{display:grid;grid-template-columns:minmax(360px,1fr) minmax(280px,.7fr);gap:18px;margin-top:20px}.exchange-settings>section>label,.exchange-settings aside{display:grid;gap:12px;padding:20px;border:1px solid #dde4e8;border-radius:10px;background:#fafbfc}.exchange-settings label>span{color:#596773;font-size:12px;font-weight:800}.exchange-settings label>div{display:grid;grid-template-columns:auto minmax(150px,230px) auto;align-items:center;gap:10px}.exchange-settings label>div b,.exchange-settings label>div strong{color:#34434e;font-size:13px;white-space:nowrap}.exchange-settings input{height:46px;border:1px solid #f1a33b;border-radius:7px;padding:0 13px;background:#fff;color:#17232e;font-size:22px;font-weight:900;outline:0}.exchange-settings input:focus{box-shadow:0 0 0 3px rgba(255,153,0,.16)}.exchange-settings label>small,.exchange-settings aside p{margin:0;color:#89949d;font-size:10px;line-height:1.7}.exchange-settings aside{align-content:start;background:#fffaf1;border-color:#f2d8ad}.exchange-settings aside b{color:#9b5900}.exchange-settings>footer{display:flex;justify-content:flex-end;margin-top:20px}.exchange-settings>footer button{min-width:150px}@media(max-width:900px){.exchange-settings>section{grid-template-columns:1fr}}@media(max-width:620px){.exchange-settings{padding:16px}.exchange-settings>header{flex-direction:column}.exchange-settings label>div{grid-template-columns:1fr}}
.product-table{min-width:1180px;table-layout:fixed;white-space:normal}.product-table .product-select-col{width:3%}.product-table .product-main-col{width:21%}.product-table .product-price-col{width:17%}.product-table .product-weight-col{width:12%}.product-table .product-freight-col{width:18%}.product-table .product-spec-col{width:14%}.product-table .product-status-col{width:7%}.product-table .product-actions-col{width:8%}.product-table th{padding:14px 12px;color:#61707b;font-size:10px;font-weight:850}.product-table td{padding:15px 12px;vertical-align:middle}.product-table tbody tr{transition:background .16s ease}.product-table tbody tr:hover{background:#fffaf2}.product-table .selection-cell{padding-right:4px;text-align:center}.product-table .selection-cell input{width:15px;height:15px;accent-color:#ff9910}.product-table .product-item{align-items:flex-start;gap:11px;min-width:0}.product-table .product-item>span{min-width:0}.product-table .product-item b{display:block;overflow:hidden;color:#17232e;font-size:13px;line-height:1.35;text-overflow:ellipsis;white-space:nowrap}.product-table .product-item small{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.product-table .product-maintainer{color:#8b969f;font-size:9px}.purchase-price-cell{display:grid;gap:7px;min-width:0}.purchase-price-top{display:flex;align-items:baseline;justify-content:space-between;gap:8px;padding-bottom:6px;border-bottom:1px solid #edf0f2}.purchase-price-top span{color:#87929b;font-size:9px}.purchase-price-top strong{color:#a55a00;font-size:15px;line-height:1}.purchase-price-grid,.freight-tier-list{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:5px}.purchase-price-grid>span,.freight-tier-list>span{display:grid;gap:3px;min-width:0;padding:6px 5px;border-radius:6px;background:#f6f8fa;text-align:center}.purchase-price-grid small,.freight-tier-list b{color:#7d8992;font-size:8px;font-weight:700;white-space:nowrap}.purchase-price-grid b{overflow:hidden;color:#26353f;font-size:11px;text-overflow:ellipsis;white-space:nowrap}.weight-cell{display:grid;gap:4px;align-content:center}.weight-cell>small{color:#7c8790;font-size:9px}.weight-cell>b{color:#17232e;font-size:15px;line-height:1.1}.weight-cell>span{color:#87929b;font-size:9px}.weight-cell .min-order{width:max-content;margin-top:2px;padding:3px 6px;background:#edf5f7;color:#4e6975;font-size:8px}.freight-tier-list>span{background:#fff9ef}.freight-tier-list strong{overflow:hidden;color:#a65b00;font-size:10px;text-overflow:ellipsis;white-space:nowrap}.freight-tier-list small{overflow:hidden;color:#909aa2;font-size:8px;text-overflow:ellipsis;white-space:nowrap}.spec-cell,.status-cell{display:grid;gap:4px;min-width:0}.spec-cell b,.spec-cell small{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.spec-cell b{color:#293842;font-size:10px}.spec-cell small{color:#87939c;font-size:9px}.status-cell{justify-items:start}.status-cell small{color:#909aa2;font-size:8px;white-space:nowrap}.product-actions{white-space:nowrap}.product-actions .link{display:inline-block;margin:2px 7px 2px 0;padding:3px 0;font-weight:750}@media(max-width:1280px){.product-table{min-width:1120px}.product-table td,.product-table th{padding-inline:9px}.purchase-price-grid>span,.freight-tier-list>span{padding-inline:3px}}@media(max-width:720px){.product-table{min-width:1040px}.product-table .product-main-col{width:23%}.product-table .product-price-col{width:18%}.product-table .product-freight-col{width:19%}.product-table .product-spec-col{width:12%}.product-table .product-actions-col{width:8%}}
.product-table .product-main-col{width:23%}
.finance-tabs button{flex:1;min-width:150px;max-width:none}.country-settings-workspace{display:grid;gap:14px}.country-settings-workspace>header{display:flex;align-items:center;gap:14px;padding:16px 18px;border:1px solid #e0e6ea;border-radius:10px;background:#fff;box-shadow:0 7px 20px rgba(24,38,50,.045)}.country-settings-workspace>header>div{display:grid;flex:1;gap:4px}.country-settings-workspace>header b{color:#1f303c;font-size:15px}.country-settings-workspace>header span{color:#7f8a93;font-size:10px}.country-settings-workspace>header>label{display:flex;align-items:center;gap:8px;width:min(320px,30vw);height:38px;padding:0 11px;border:1px solid #dbe2e7;border-radius:7px;color:#76838d}.country-settings-workspace>header input{min-width:0;flex:1;border:0;outline:0;background:transparent}.country-settings-workspace>header .primary{white-space:nowrap}.country-setting-columns{display:grid;grid-template-columns:minmax(360px,.85fr) minmax(440px,1.15fr);gap:14px}.country-setting-panel{min-width:0;padding:16px;border:1px solid #dee5e9;border-radius:10px;background:#fff;box-shadow:0 7px 20px rgba(24,38,50,.04)}.country-setting-panel>header{display:flex;align-items:center;gap:11px;margin-bottom:12px}.country-setting-panel>header>i{width:34px;height:34px;display:grid;place-items:center;flex:0 0 34px;border-radius:50%;background:#edf2f5;color:#536675;font-style:normal;font-weight:900}.country-setting-panel.common>header>i{background:#fff0d6;color:#ae6100}.country-setting-panel.standard>header>i{background:#eaf3ff;color:#276399}.country-setting-panel.rare>header>i{background:#f0ebff;color:#674aa0}.country-setting-panel>header>div{display:grid;flex:1;gap:3px}.country-setting-panel>header small{color:#88939c;font-size:9px}.country-setting-panel>header>em{padding:5px 9px;border-radius:999px;background:#f2f5f7;color:#52616c;font-size:10px;font-style:normal;font-weight:850}.country-setting-list{display:grid;gap:7px}.country-setting-list.scroll{max-height:470px;overflow:auto;padding-right:4px}.country-setting-list article{display:grid;grid-template-columns:minmax(135px,1fr) 74px 112px;align-items:center;gap:9px;padding:9px 10px;border:1px solid #e5eaed;border-radius:7px;background:#fafbfc}.country-setting-list article>span{display:grid;min-width:0;gap:2px}.country-setting-list article>span b{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:11px}.country-setting-list article>span small{color:#8a959e;font-size:8px}.country-setting-list article>label{display:grid;gap:3px;color:#7a8790;font-size:8px}.country-setting-list input,.country-setting-list select{box-sizing:border-box;width:100%;height:32px;border:1px solid #d8e0e5;border-radius:6px;background:#fff;padding:0 8px;color:#33434e;font-size:10px}.country-setting-panel.rare{padding-bottom:18px}.rare-country-groups{display:grid;gap:8px}.rare-country-groups details{border:1px solid #e2e7eb;border-radius:8px;background:#fafbfc;overflow:hidden}.rare-country-groups summary{display:flex;align-items:center;justify-content:space-between;padding:12px 14px;cursor:pointer}.rare-country-groups summary span{color:#87929b;font-size:9px}.rare-country-groups details[open] summary{border-bottom:1px solid #e2e7eb;background:#fff8eb}.rare-list{grid-template-columns:repeat(2,minmax(0,1fr));padding:10px}.country-policy-classification{display:flex;align-items:center;gap:9px;margin-top:11px;padding:9px 11px;border:1px solid #eadcc4;border-radius:6px;background:#fffaf1}.country-policy-classification b{padding:4px 7px;border-radius:999px;background:#ffebc8;color:#985700;font-size:9px}.country-policy-classification span{color:#7c8790;font-size:9px}@media(max-width:1050px){.country-setting-columns{grid-template-columns:1fr}.rare-list{grid-template-columns:1fr}.country-settings-workspace>header{flex-wrap:wrap}.country-settings-workspace>header>label{width:min(420px,100%)}}@media(max-width:620px){.country-settings-workspace>header{align-items:stretch;flex-direction:column}.country-settings-workspace>header>label{box-sizing:border-box;width:100%}.country-setting-list article{grid-template-columns:1fr 70px 104px}.country-setting-panel{padding:12px}}
.country-setting-panel>header>button{height:32px;padding:0 11px;border:1px solid #f1b45c;border-radius:6px;background:#fff8e9;color:#9d5900;font-size:9px;font-weight:850;white-space:nowrap;cursor:pointer}.country-setting-list article{grid-template-columns:minmax(135px,1fr) 74px 58px}.country-setting-list .remove-country{height:32px;border:1px solid #efd5d0;border-radius:6px;background:#fff;color:#b34f40;font-size:9px;font-weight:800;cursor:pointer}.country-setting-list .remove-country:hover{border-color:#d77a6c;background:#fff3f1}.country-setting-empty{margin:0;padding:28px 12px;border:1px dashed #dce3e7;border-radius:7px;color:#929ca4;font-size:10px;text-align:center}.country-picker-mask{z-index:125}.country-picker-dialog{position:relative;width:min(820px,92vw);max-height:86vh;display:grid;grid-template-rows:auto auto minmax(0,1fr) auto;padding:24px;border-radius:12px;background:#fff;box-shadow:0 24px 70px rgba(11,22,31,.3)}.country-picker-dialog>header{display:flex;align-items:flex-start;justify-content:space-between;gap:20px;padding-right:38px}.country-picker-dialog>header small{color:#c66e00;font-size:9px;font-weight:900;letter-spacing:.15em}.country-picker-dialog>header h2{margin:5px 0 4px;font-size:22px}.country-picker-dialog>header p{margin:0;color:#7f8a93;font-size:10px}.country-picker-dialog>header>em{padding:6px 10px;border-radius:999px;background:#fff0d5;color:#9f5a00;font-size:9px;font-style:normal;font-weight:850;white-space:nowrap}.country-picker-search{display:flex;align-items:center;gap:8px;height:40px;margin:16px 0 12px;padding:0 12px;border:1px solid #d8e0e5;border-radius:7px;color:#7d8992}.country-picker-search input{min-width:0;flex:1;border:0;outline:0}.country-picker-all-list{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));align-content:start;gap:7px;max-height:52vh;overflow:auto;padding-right:5px}.country-picker-all-list>label{display:grid;grid-template-columns:auto minmax(0,1fr) auto;align-items:center;gap:9px;padding:10px;border:1px solid #e2e7eb;border-radius:7px;background:#fafbfc;cursor:pointer}.country-picker-all-list>label:hover{border-color:#f1b45c;background:#fffaf1}.country-picker-all-list>label.selected{border-color:#f19a19;background:#fff5e5}.country-picker-all-list>label.current{background:#f1f4f6;cursor:default}.country-picker-all-list input{width:15px;height:15px;accent-color:#ff9910}.country-picker-all-list span{display:grid;min-width:0;gap:2px}.country-picker-all-list span b{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:11px}.country-picker-all-list span small{color:#89949c;font-size:8px}.country-picker-all-list em{padding:4px 6px;border-radius:999px;background:#edf2f5;color:#697781;font-size:8px;font-style:normal;white-space:nowrap}.country-picker-dialog>footer{display:flex;justify-content:flex-end;gap:9px;margin-top:14px;padding-top:14px;border-top:1px solid #e5eaed}.country-picker-dialog>footer>button:not(.primary){height:40px;padding:0 18px;border:1px solid #dce2e6;border-radius:7px;background:#fff}.country-picker-dialog .primary:disabled{opacity:.45;cursor:not-allowed}@media(max-width:700px){.country-picker-all-list{grid-template-columns:1fr}.country-setting-panel>header{flex-wrap:wrap}.country-setting-list article{grid-template-columns:1fr 66px 52px}}
.finance-stats>div[role=button]{position:relative;cursor:pointer;transition:border-color .18s ease,box-shadow .18s ease,transform .18s ease}.finance-stats>div[role=button]:hover{border-color:#f0b35a;box-shadow:0 10px 25px rgba(171,96,0,.1);transform:translateY(-1px)}.finance-stats>div[role=button]:focus-visible{outline:3px solid rgba(255,153,16,.25);outline-offset:2px}.finance-stats>div[role=button].active{border-color:#ed8b00;background:linear-gradient(135deg,#fffaf0,#fff);box-shadow:0 8px 24px rgba(208,117,0,.14)}.finance-stats>div[role=button].active:after{content:"";position:absolute;left:18px;right:18px;bottom:-1px;height:3px;border-radius:3px 3px 0 0;background:#ff9910}.finance-stats>div[role=button].active>i{background:#ff9910;color:#17232e}.finance-stats>div[role=button]:last-child b{font-size:20px}
.country-policy-classification>span{flex:1}.country-policy-classification>button{height:30px;padding:0 10px;border:1px solid #edbd75;border-radius:6px;background:#fff;color:#9c5900;font-size:9px;font-weight:850;white-space:nowrap;cursor:pointer}.country-policy-classification>button:hover{border-color:#ef9816;background:#fff3df}.country-rule-card:has(.country-policy-classification>button[aria-expanded=false]){padding-bottom:12px}
.country-setting-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px}.country-setting-grid>.country-setting-panel{align-self:start}.country-setting-grid .rare-list{grid-template-columns:1fr}.country-setting-scroll{max-height:470px;overflow:auto;padding-right:4px}.country-setting-panel.ungrouped>header>i{background:#edf0f2;color:#65727c}.country-setting-panel.ungrouped>header>em{background:#e9edf0;color:#53616c}.ungrouped-list article{grid-template-columns:minmax(150px,1fr) 128px}.ungrouped-list article>select{height:32px;border:1px solid #cfd9df;border-radius:6px;background:#fff;padding:0 8px;color:#42515c;font-size:10px;font-weight:750}.ungrouped-list article>select:focus{border-color:#ff9900;box-shadow:0 0 0 2px rgba(255,153,0,.12);outline:0}@media(max-width:1050px){.country-setting-grid{grid-template-columns:1fr}}@media(max-width:620px){.ungrouped-list article{grid-template-columns:1fr 112px}}
.single-country-setting{grid-template-columns:1fr}.single-country-setting .country-setting-panel{box-sizing:border-box;width:100%}.single-country-setting .country-setting-list{grid-template-columns:repeat(2,minmax(0,1fr))}@media(max-width:760px){.single-country-setting .country-setting-list{grid-template-columns:1fr}}
.country-setting-list article{grid-template-columns:minmax(135px,1fr) 58px}@media(max-width:700px){.country-setting-list article{grid-template-columns:1fr 52px}}
.common-country-manager{overflow:hidden;border:1px solid #dfe6ea;border-radius:12px;background:#fff;box-shadow:0 10px 28px rgba(24,38,50,.055)}.common-country-manager>header{display:flex;align-items:center;gap:12px;padding:18px 20px 14px}.common-country-manager>header>div{display:grid;flex:1;gap:4px}.common-country-manager>header b{color:#17232e;font-size:17px}.common-country-manager>header span{color:#7f8b94;font-size:10px}.common-country-manager>header>em{padding:6px 10px;border-radius:999px;background:#f1f4f6;color:#53616b;font-size:10px;font-style:normal;font-weight:900;white-space:nowrap}.common-country-manager>header>label{box-sizing:border-box;width:min(275px,22vw);height:40px;display:flex;align-items:center;gap:8px;padding:0 12px;border:1px solid #dbe2e7;border-radius:7px;color:#73808a}.common-country-manager>header input{min-width:0;flex:1;border:0;outline:0;background:transparent}.common-country-manager>header>button{height:40px;padding:0 15px;border-radius:7px;font-size:10px;font-weight:900;white-space:nowrap;cursor:pointer}.common-country-manager .add-country{border:1px solid #f0a33a;background:#fff;color:#a45d00}.common-country-card-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px;padding:8px 20px 18px}.common-country-card-grid article{display:grid;grid-template-columns:22px 48px minmax(0,1fr) 28px;align-items:center;gap:9px;min-height:66px;padding:8px 11px;border:1px solid #dfe5e9;border-radius:9px;background:#fff;cursor:grab;transition:border-color .15s,background .15s,box-shadow .15s,opacity .15s,transform .15s}.common-country-card-grid article:hover{border-color:#efb45d;box-shadow:0 6px 16px rgba(155,89,0,.08)}.common-country-card-grid article:active{cursor:grabbing}.common-country-card-grid article.dragging{opacity:.42;transform:scale(.98)}.common-country-card-grid article.drag-over{border-color:#f28d00;background:#fff6e8;box-shadow:0 0 0 3px rgba(242,141,0,.15)}.common-country-card-grid article>i{color:#aeb8bf;font-size:22px;font-style:normal;line-height:1}.common-country-card-grid article>strong{display:grid;place-items:center;height:38px;border-radius:8px;background:#fff0d8;color:#b56600;font-size:15px}.common-country-card-grid article>span{display:grid;min-width:0;gap:3px}.common-country-card-grid article>span b{overflow:hidden;font-size:12px;text-overflow:ellipsis;white-space:nowrap}.common-country-card-grid article>span small{color:#8b969e;font-size:9px}.common-country-card-grid article>button{width:28px;height:28px;display:grid;place-items:center;border:0;border-radius:6px;background:transparent;color:#dc5445;cursor:pointer}.common-country-card-grid article>button:hover{background:#fff0ee}.common-country-card-grid article>button svg{width:17px;height:17px;fill:none;stroke:currentColor;stroke-linecap:round;stroke-linejoin:round;stroke-width:1.8}.common-country-card-grid>.country-setting-empty{grid-column:1/-1}.common-country-manager>footer{display:flex;align-items:center;justify-content:center;gap:7px;padding:13px;border-top:1px dashed #dce3e7;color:#7f8b94;font-size:10px}.common-country-manager>footer span{color:#aab5bd;font-size:18px}@media(max-width:1180px){.common-country-card-grid{grid-template-columns:repeat(3,minmax(0,1fr))}.common-country-manager>header{flex-wrap:wrap}.common-country-manager>header>div{flex-basis:55%}.common-country-manager>header>label{width:min(340px,45vw)}}@media(max-width:820px){.common-country-card-grid{grid-template-columns:repeat(2,minmax(0,1fr))}}@media(max-width:620px){.common-country-manager>header{align-items:stretch;flex-direction:column}.common-country-manager>header>label{width:100%}.common-country-manager>header>em{align-self:flex-start}.common-country-card-grid{grid-template-columns:1fr;padding-inline:14px}}
.finance-stats{grid-template-columns:repeat(5,minmax(0,1fr))}.finance-tax-workspace{overflow:hidden;border:1px solid #dfe6ea;border-radius:12px;background:#fff;box-shadow:0 10px 28px rgba(24,38,50,.055)}.finance-tax-workspace>header{display:flex;align-items:center;justify-content:space-between;gap:18px;padding:19px 22px;border-bottom:1px solid #e5eaed}.finance-tax-workspace>header>div{display:grid;gap:4px}.finance-tax-workspace>header small{color:#d17600;font-size:9px;font-weight:900;letter-spacing:.16em}.finance-tax-workspace>header b{font-size:18px}.finance-tax-workspace>header span{color:#7e8a93;font-size:10px}.finance-tax-workspace>header>aside{display:flex;align-items:center;gap:13px}.finance-tax-workspace>header>aside span{white-space:nowrap}.finance-tax-grid{display:grid;grid-template-columns:minmax(320px,.78fr) minmax(520px,1.5fr);gap:14px;padding:16px;background:#f7f9fa}.tax-country-panel,.tax-provider-panel{overflow:hidden;border:1px solid #dfe6ea;border-radius:9px;background:#fff}.tax-country-panel>header,.tax-provider-panel>header{display:flex;align-items:center;gap:10px;padding:15px 16px;border-bottom:1px solid #e5eaed}.tax-country-panel>header>div,.tax-provider-panel>header>div{display:grid;flex:1;gap:3px}.tax-country-panel>header b,.tax-provider-panel>header b{font-size:13px}.tax-country-panel>header span,.tax-provider-panel>header span{color:#87929a;font-size:9px}.tax-country-panel>header>label,.tax-provider-panel>header>label{width:150px;height:34px;display:flex;align-items:center;gap:6px;padding:0 9px;border:1px solid #dce3e7;border-radius:6px;color:#7e8992}.tax-country-panel>header input,.tax-provider-panel>header input{min-width:0;width:100%;border:0;outline:0}.tax-country-list{display:grid;gap:7px;max-height:480px;overflow:auto;padding:12px}.tax-country-list>label{display:flex;align-items:center;gap:10px;padding:10px;border:1px solid #e5eaed;border-radius:7px;background:#fafbfc}.tax-country-list>label>span{display:grid;flex:1;gap:3px}.tax-country-list>label b{font-size:11px}.tax-country-list>label small{color:#8b969e;font-size:8px}.tax-country-list>label>div{height:33px;display:flex;align-items:center;border:1px solid #d7e0e5;border-radius:6px;background:#fff}.tax-country-list>label em{padding:0 8px;background:none;color:#bd6c00;font-size:11px;font-weight:900}.tax-country-list>label input{width:64px;border:0;outline:0;font-weight:850}.tax-country-list>label strong{padding-right:8px;color:#74818a;font-size:8px}.tax-country-list>p,.tax-empty{margin:0;padding:28px;color:#929ca4;font-size:10px;text-align:center}.tax-country-panel>footer{padding:12px 15px;border-top:1px dashed #dfe5e9;background:#fffaf1;color:#8c704c;font-size:9px;line-height:1.6}.tax-provider-list{display:grid;gap:8px;max-height:540px;overflow:auto;padding:12px}.tax-provider-list>article{overflow:hidden;border:1px solid #e1e7eb;border-radius:8px;background:#fff}.tax-provider-list>article.incomplete{border-color:#efd19d}.tax-provider-list>article>header{display:grid;grid-template-columns:minmax(145px,1fr) auto 126px 88px;align-items:center;gap:9px;padding:11px 12px}.tax-provider-list>article>header>div{display:grid;gap:3px}.tax-provider-list>article>header b{font-size:11px}.tax-provider-list>article>header span{color:#89949c;font-size:8px}.tax-provider-list>article>header>em{white-space:nowrap}.tax-provider-list select,.fixed-rate-input{box-sizing:border-box;height:33px;border:1px solid #d7e0e5;border-radius:6px;background:#fff;color:#44535e;font-size:9px}.tax-provider-list select{padding:0 8px}.fixed-rate-input{display:flex;align-items:center}.fixed-rate-input input{min-width:0;width:58px;border:0;outline:0;padding-left:8px;font-weight:850}.fixed-rate-input span{padding-right:7px;color:#6c7881!important}@media(max-width:1200px){.finance-stats{grid-template-columns:repeat(3,minmax(0,1fr))}.finance-tax-grid{grid-template-columns:1fr}.tax-country-list{grid-template-columns:repeat(2,minmax(0,1fr));max-height:320px}}@media(max-width:760px){.finance-stats{grid-template-columns:1fr 1fr}.finance-tax-workspace>header{align-items:stretch;flex-direction:column}.finance-tax-workspace>header>aside{align-items:stretch;flex-direction:column}.tax-country-panel>header,.tax-provider-panel>header{align-items:stretch;flex-direction:column}.tax-country-panel>header>label,.tax-provider-panel>header>label{box-sizing:border-box;width:100%}.tax-country-list{grid-template-columns:1fr}.tax-provider-list>article>header{grid-template-columns:1fr auto}.tax-provider-list>article>header>select,.tax-provider-list>article>header>.fixed-rate-input{width:100%;grid-column:1/-1}}
.tax-country-list>article{display:grid;grid-template-columns:20px minmax(0,1fr) auto;align-items:center;gap:10px;padding:10px;border:1px solid #e5eaed;border-radius:7px;background:#fafbfc;cursor:grab;transition:border-color .15s,background .15s,box-shadow .15s,opacity .15s,transform .15s}.tax-country-list>article:hover{border-color:#efb45d;box-shadow:0 5px 14px rgba(155,89,0,.08)}.tax-country-list>article:active{cursor:grabbing}.tax-country-list>article.dragging{opacity:.42;transform:scale(.985)}.tax-country-list>article.drag-over{border-color:#f28d00;background:#fff6e8;box-shadow:0 0 0 3px rgba(242,141,0,.14)}.tax-country-list>article.drag-disabled{cursor:default}.tax-country-list>article>i{color:#aeb8bf;font-size:19px;font-style:normal;line-height:1}.tax-country-list>article>span{display:grid;min-width:0;gap:3px}.tax-country-list>article b{font-size:11px}.tax-country-list>article small{color:#8b969e;font-size:8px}.tax-country-list>article>div{height:33px;display:flex;align-items:center;border:1px solid #d7e0e5;border-radius:6px;background:#fff}.tax-country-list>article em{padding:0 8px;background:none;color:#bd6c00;font-size:11px;font-weight:900}.tax-country-list>article input{width:64px;border:0;outline:0;font-weight:850}.tax-country-list>article strong{padding-right:8px;color:#74818a;font-size:8px}.tax-country-panel>footer{display:grid;gap:5px}.tax-country-panel>footer>b{color:#a5630d;font-size:9px}.tax-country-panel>footer>span{color:#8c704c}
.tax-country-list .tax-fee-editor{overflow:hidden}.tax-country-list .tax-fee-cny{align-self:stretch;display:flex;align-items:center;gap:3px;padding:0 8px;border-left:1px solid #e2e7ea;background:#fff8eb;color:#a65d00;font-size:10px;font-weight:900;white-space:nowrap}.tax-country-list .tax-fee-cny small{color:#a9793b;font-size:7px;font-weight:800}.tax-country-list .tax-fee-editor:focus-within{border-color:#ee970f;box-shadow:0 0 0 2px rgba(238,151,15,.12)}
.finance-stats>div[role=button]{cursor:grab}.finance-stats>div[role=button]:active{cursor:grabbing}.finance-stats>div[role=button].dragging{opacity:.42;transform:scale(.98)}.finance-stats>div[role=button].drag-over{border-color:#f28d00;background:#fff6e8;box-shadow:0 0 0 3px rgba(242,141,0,.15)}.finance-card-drag{align-self:flex-start;margin:-7px -7px 0 -9px;padding:0;background:transparent;color:#acb6bd;font-size:17px;font-style:normal;line-height:1}
.finance-stats{grid-template-columns:repeat(5,minmax(0,1fr))}
.finance-tax-content{display:grid;gap:14px;padding:16px;background:#f7f9fa}.tax-country-matrix,.tax-provider-global{overflow:hidden;border:1px solid #dfe6ea;border-radius:10px;background:#fff}.tax-country-matrix>header,.tax-provider-global>header{display:flex;align-items:center;justify-content:space-between;gap:16px;padding:16px 18px;border-bottom:1px solid #e5eaed}.tax-country-matrix>header>div,.tax-provider-global>header>div{display:grid;gap:3px}.tax-country-matrix>header b,.tax-provider-global>header b{font-size:14px}.tax-country-matrix>header span,.tax-provider-global>header span{color:#84909a;font-size:10px}.tax-country-matrix>header>aside{display:flex;align-items:center;gap:10px}.tax-country-matrix>header>aside>span{padding:6px 10px;border-radius:14px;background:#fff5e6;color:#a45d00;font-weight:800}.tax-country-matrix>header label,.tax-provider-global>header label{width:190px;height:35px;display:flex;align-items:center;gap:7px;box-sizing:border-box;padding:0 10px;border:1px solid #dbe3e8;border-radius:7px;color:#7b8892}.tax-country-matrix>header input,.tax-provider-global>header input{min-width:0;width:100%;border:0;outline:0;background:transparent}.tax-country-head,.tax-country-rows>article{min-width:760px;display:grid;grid-template-columns:minmax(180px,1.1fr) minmax(220px,1fr) minmax(250px,1.1fr) 78px;align-items:center;gap:14px}.tax-country-head{padding:10px 18px;background:#fafbfc;color:#7d8992;font-size:10px;font-weight:800}.tax-country-rows{max-height:365px;overflow:auto}.tax-country-rows>article{padding:11px 18px;border-top:1px solid #edf0f2}.tax-country-rows>article:first-child{border-top:0}.tax-country-rows>article>div{display:grid;gap:3px}.tax-country-rows>article>div b{font-size:12px}.tax-country-rows>article>div small{color:#929ca4;font-size:9px}.tax-country-rows>article label{height:36px;display:grid;grid-template-columns:auto minmax(55px,90px) auto;align-items:center;gap:6px;padding:0 9px;border:1px solid #d8e0e5;border-radius:7px;background:#fff}.tax-country-rows>article label em{color:#bd6c00;font-size:11px;font-style:normal;font-weight:900}.tax-country-rows>article label input{min-width:0;width:100%;border:0;outline:0;font-size:13px;font-weight:850}.tax-country-rows>article label span{color:#7b8790;font-size:9px;white-space:nowrap}.tax-country-rows>article label small{grid-column:1/-1;margin-top:-3px;color:#a36b1e;font-size:8px}.tax-country-rows>article>em{justify-self:start;padding:5px 9px;border-radius:14px;background:#eef1f3;color:#7c8891;font-size:9px;font-style:normal;font-weight:800}.tax-country-rows>article>em.active{background:#e9f8ef;color:#16804e}.tax-provider-cards{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:10px;max-height:380px;overflow:auto;padding:14px}.tax-provider-cards>article{display:grid;gap:11px;padding:13px;border:1px solid #e2e8eb;border-radius:9px;background:#fff}.tax-provider-cards>article>header{display:grid;gap:3px}.tax-provider-cards>article b{font-size:11px}.tax-provider-cards>article span{color:#8a959d;font-size:9px}.tax-provider-cards>article>div{display:grid;grid-template-columns:1fr 1fr;overflow:hidden;border:1px solid #dce3e7;border-radius:7px}.tax-provider-cards button{height:33px;border:0;background:#fff;color:#697681;font-size:10px;font-weight:800}.tax-provider-cards button+button{border-left:1px solid #dce3e7}.tax-provider-cards button.active{background:#fff0d8;color:#ad6200}.tax-provider-cards>article.exempt button:first-child.active{background:#e9f8ef;color:#16804e}.tax-provider-global>footer{padding:11px 16px;border-top:1px dashed #e0e6e9;background:#fffaf1;color:#8a6e49;font-size:9px}.finance-tax-content>.tax-empty{border:1px dashed #dce3e7;border-radius:8px;background:#fff}
@media(max-width:1200px){.finance-stats{grid-template-columns:repeat(3,minmax(0,1fr))}.tax-provider-cards{grid-template-columns:repeat(2,minmax(0,1fr))}}@media(max-width:760px){.finance-stats{grid-template-columns:1fr 1fr}.tax-country-matrix>header,.tax-provider-global>header{align-items:stretch;flex-direction:column}.tax-country-matrix>header>aside{align-items:stretch;flex-direction:column}.tax-country-matrix>header label,.tax-provider-global>header label{width:100%}.tax-country-matrix{overflow-x:auto}.tax-provider-cards{grid-template-columns:1fr}}
.tax-country-matrix>header>aside,.tax-provider-global>header>aside{display:flex;align-items:center;gap:9px}.tax-country-matrix>header>aside label,.tax-provider-global>header>aside label{width:205px;height:35px;display:flex;align-items:center;gap:7px;box-sizing:border-box;padding:0 10px;border:1px solid #dbe3e8;border-radius:7px;color:#7b8892}.tax-country-matrix>header>aside input,.tax-provider-global>header>aside input{min-width:0;width:100%;border:0;outline:0;background:transparent}.tax-add-button{height:35px;padding:0 13px;border:1px solid #ef920a;border-radius:7px;background:#fff;color:#b66600;font-size:10px;font-weight:850;white-space:nowrap}.tax-add-button:hover{background:#fff7ea}.tax-add-button:disabled{cursor:not-allowed;border-color:#dfe5e9;background:#f6f8f9;color:#a6b0b7}.tax-add-row{display:flex;align-items:center;gap:9px;padding:10px 18px;border-bottom:1px solid #e7ebee;background:#fffaf2}.tax-add-search{width:260px;height:35px;display:flex;align-items:center;gap:7px;box-sizing:border-box;padding:0 10px;border:1px solid #e3ad5a;border-radius:7px;background:#fff;color:#9b650f}.tax-add-search:focus-within{border-color:#ee9209;box-shadow:0 0 0 3px rgba(238,146,9,.12)}.tax-add-search input{min-width:0;width:100%;border:0;outline:0;background:transparent;color:#27343e;font-size:10px}.tax-add-row select{min-width:260px;height:35px;border:1px solid #d8e0e5;border-radius:7px;background:#fff;padding:0 10px;color:#283641}.tax-add-row button{height:35px;padding:0 14px;border:1px solid #d8e0e5;border-radius:7px;background:#fff;color:#596772;font-size:10px;font-weight:800}.tax-add-row button.primary{border-color:#ef920a;background:#ff9910;color:#17232e}.tax-add-row button:disabled{cursor:not-allowed;border-color:#dfe5e9;background:#eef1f3;color:#9da7ae}.tax-country-head,.tax-country-rows>article{grid-template-columns:minmax(160px,1fr) minmax(190px,.95fr) minmax(210px,1fr) 70px 52px}.tax-country-rows>article>span{display:grid;gap:3px}.tax-country-matrix>footer{padding:9px 18px;border-top:1px dashed #e1e6e9;background:#fffaf1;color:#8b6d45;font-size:9px}.tax-remove-button{justify-self:start;border:0;background:transparent;color:#d55345;font-size:10px;font-weight:800}.tax-remove-button:hover{text-decoration:underline}.tax-provider-head,.tax-provider-list-compact>article{display:grid;grid-template-columns:minmax(210px,1.4fr) 100px 240px 52px;align-items:center;gap:14px}.tax-provider-head{padding:10px 18px;background:#fafbfc;color:#7d8992;font-size:10px;font-weight:800}.tax-provider-list-compact{max-height:360px;overflow:auto}.tax-provider-list-compact>article{padding:11px 18px;border-top:1px solid #edf0f2}.tax-provider-list-compact>article:first-child{border-top:0}.tax-provider-list-compact>article>span:first-child{display:grid;gap:3px}.tax-provider-list-compact b{font-size:11px}.tax-provider-list-compact small{overflow:hidden;color:#8a959d;font-size:8px;text-overflow:ellipsis;white-space:nowrap}.tax-provider-list-compact>article>span:nth-child(2){color:#6f7c86;font-size:10px}.tax-provider-list-compact>article>div{display:grid;grid-template-columns:1fr 1fr;overflow:hidden;border:1px solid #dce3e7;border-radius:7px}.tax-provider-list-compact>article>div button{height:32px;border:0;background:#fff;color:#697681;font-size:10px;font-weight:800}.tax-provider-list-compact>article>div button+button{border-left:1px solid #dce3e7}.tax-provider-list-compact>article>div button.active{background:#fff0d8;color:#ad6200}.tax-provider-list-compact>article>div button:first-child.active{background:#e9f8ef;color:#16804e}
@media(max-width:760px){.tax-country-matrix>header>aside,.tax-provider-global>header>aside{align-items:stretch;flex-direction:column}.tax-country-matrix>header>aside label,.tax-provider-global>header>aside label,.tax-add-button{width:100%}.tax-add-row{align-items:stretch;flex-direction:column}.tax-add-search,.tax-add-row select,.tax-add-row button{width:100%}.tax-country-head,.tax-country-rows>article{min-width:650px}.tax-provider-global{overflow-x:auto}.tax-provider-head,.tax-provider-list-compact>article{min-width:680px}}
.tax-country-head,.tax-country-rows>article{grid-template-columns:minmax(180px,1fr) minmax(260px,1.2fr) 70px 52px}
.finance-load-state{display:flex;align-items:center;gap:14px;min-height:78px;padding:18px 20px;border:1px solid #dfe6ea;border-left:4px solid var(--o);border-radius:10px;background:#fff;box-shadow:0 10px 28px rgba(24,38,50,.05)}.finance-load-state>i{width:24px;height:24px;flex:0 0 24px;border:3px solid #ffe2b8;border-top-color:var(--o);border-radius:50%;animation:finance-load-spin .8s linear infinite}.finance-load-state>span{display:grid;gap:5px}.finance-load-state b{font-size:14px}.finance-load-state small,.finance-load-state em{padding:0;background:transparent;color:#7e8a93;font-size:10px;font-style:normal}.finance-load-state.error{border-color:#efc9c4;border-left-color:#cc5143;background:#fff8f7}.finance-load-state.error>span{flex:1}.finance-load-state.error em{color:#a35b52}.finance-load-state>button{height:36px;margin-left:auto;padding:0 14px;border:1px solid #cf796f;border-radius:7px;background:#fff;color:#a13d31;font-size:10px;font-weight:850;cursor:pointer}@keyframes finance-load-spin{to{transform:rotate(360deg)}}
.carrier-list .legacy-channel{background:#fff2dc;color:#9a5b08;border:1px dashed #dfa85c}.legacy-count{display:block;margin-top:4px;color:#a46617}.legacy-review-summary{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-top:10px;padding:9px 11px;border:1px dashed #e0ad61;border-radius:7px;background:#fff9ef;color:#91560b}.legacy-review-summary>span{display:grid;gap:2px}.legacy-review-summary small{color:#9a7b53}.legacy-review-list{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:7px;margin-top:8px;padding:10px;border:1px dashed #e0ad61;border-radius:7px;background:#fff9ef;max-height:210px;overflow:auto}.legacy-review-list>b{grid-column:1/-1;color:#91560b}.legacy-review-list>span{display:grid;gap:3px;padding:7px 9px;border-radius:5px;background:#fff;color:#684d2a}.legacy-review-list small{color:#9a7b53}
</style>
