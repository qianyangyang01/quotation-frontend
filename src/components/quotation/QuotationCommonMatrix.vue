<script setup lang="ts">
import { priceRegionLabel } from './priceRegionLabel'
import { sameQuotationRegion } from '@/data/logistics'
import { quoteCnyFromUsd } from '@/services/quotationMoney'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import type { QuotationCountrySummary, QuotationMatrixRow, QuotationPresetSelection, QuotationComparisonCopy } from './types'
import { hasAnyQuotationPrice } from '@/services/quotationAvailability'
import QuoteUnavailableReason from './QuoteUnavailableReason.vue'
import QuoteTaxMeta from './QuoteTaxMeta.vue'
import QuoteChannelName from './QuoteChannelName.vue'
import QuoteMinimumWeight from './QuoteMinimumWeight.vue'
import QuoteTaxLegend from './QuoteTaxLegend.vue'
import { loadCountrySearchHistory, saveCountrySearchHistory, recordCountrySearch, rankCountrySearchHistory } from '@/data/countrySearchHistory'

const props = withDefaults(defineProps<{
  userId?: string
  batchSample?: boolean
  unavailableReason?: (preset: QuotationPresetSelection, quantity?: number) => string
  active?: boolean
  autoLoadCountry?: boolean
  sourcePending?: boolean
  sourceError?: string
  ensureCountries?: (countries: string[]) => Promise<boolean>
  searchChannelCountries?: (query: string) => Promise<string[]>
  countries: QuotationCountrySummary[]
  quoteRowsForCountry: (country: string, region?: string) => QuotationMatrixRow[]
  contextKey: string
  adoptedCountry: string
  adoptedChannelKey?: string
  adoptedRule: string
  adoptedCarrier: string
  exchangeRate: number
  unitLabel?: string
  customQuantity?: number
  calculateQuantityPrice?: (row: QuotationMatrixRow, quantity: number) => number | null
  resolveSelectedRow?: (row: QuotationMatrixRow, quantities: readonly number[]) => QuotationMatrixRow | undefined
  presetSelection?: QuotationPresetSelection[]
  presetVersion?: number
}>(), { active: true })

const emit = defineEmits<{
  adopt: [row: QuotationMatrixRow]
  copy: [rows: QuotationMatrixRow[], comparison?: QuotationComparisonCopy]
  comparisonQuantitiesChange: [quantities: number[]]
  selectionChange: [rows: QuotationMatrixRow[]]
  quoteRegionChange: [payload: { country: string; region: string }]
  countryOrderChange: [countries: string[]]
}>()

const activeCountry = ref('')
const search = ref('')
const countrySearchHistory = ref(loadCountrySearchHistory(props.userId || ''))
let historyTimer: ReturnType<typeof setTimeout> | undefined
let recordedSearchCountry = ''
const frequentSearchCountries = computed(() => rankCountrySearchHistory(countrySearchHistory.value)
  .flatMap(entry => {
    const country = props.countries.find(item => item.code.toUpperCase() === entry.code)
    return country ? [country] : []
  }).slice(0, 6))

function rememberSearchedCountry(country: QuotationCountrySummary) {
  if (!props.userId || props.active === false || recordedSearchCountry === country.code) return
  recordedSearchCountry = country.code
  countrySearchHistory.value = recordCountrySearch(countrySearchHistory.value, country.code)
  saveCountrySearchHistory(props.userId, countrySearchHistory.value)
}
function rememberExactCountrySearch() {
  clearTimeout(historyTimer)
  const query = search.value.trim().toLowerCase()
  if (!query) return
  const country = props.countries.find(item => item.name.toLowerCase() === query || item.code.toLowerCase() === query)
  if (country) rememberSearchedCountry(country)
}
function searchFrequentCountry(country: QuotationCountrySummary) {
  // Only filter the existing results; do not select channels or add quotation rows.
  search.value = country.name
  rememberExactCountrySearch()
}
function clearCountrySearchHistory() {
  clearTimeout(historyTimer)
  countrySearchHistory.value = []
  saveCountrySearchHistory(props.userId || '', [])
}
function selectSearchedCountry(country: QuotationCountrySummary) {
  const query = search.value.trim().toLowerCase()
  if (query && countryMatchesQuery(country, query)) rememberSearchedCountry(country)
  void selectCountry(country.name)
}
watch(search, () => {
  clearTimeout(historyTimer)
  recordedSearchCountry = ''
  // Avoid counting unfinished input, IME composition or each keystroke.
  if (search.value.trim()) historyTimer = setTimeout(rememberExactCountrySearch, 800)
}, { flush: 'sync' })
watch(() => props.userId, userId => {
  clearTimeout(historyTimer)
  recordedSearchCountry = ''
  countrySearchHistory.value = loadCountrySearchHistory(userId || '')
})
watch(() => props.active, active => { if (active === false) clearTimeout(historyTimer) })
const searchMatches = ref<string[]>([])
const searchLoading = ref(false)
const searchError = ref('')
const countryLoading = ref(false)
const countryError = ref('')
let searchRequest = 0
let countryRequest = 0
let searchTimer: ReturnType<typeof setTimeout> | undefined
const channelSearch = ref('')
const page = ref(1)
const pageSize = ref(5)
const sortMode = ref<'recommended' | 'price' | 'speed'>('recommended')
const comparisonInput = ref('1, 2, 3')
const comparisonQuantities = ref([1, 2, 3])
const comparisonError = ref('')
const sortQuantity = ref(1)
const onlyQuotable = ref(false)
function applyComparisonQuantities() {
  const tokens = comparisonInput.value.trim().split(/[,，、;；\s]+/).filter(Boolean)
  if (tokens.length > 3) {
    comparisonError.value = '最多输入 3 个数量，请删减后再应用；当前比价结果保持不变'
    return
  }
  const values = tokens.map(Number)
  if (!values.length || tokens.some(token => !/^\d+$/.test(token)) || values.some(value => !Number.isSafeInteger(value) || value <= 0)) {
    comparisonError.value = '请输入正整数数量，用逗号分隔，例如 4, 8, 12'
    return
  }
  const quantities = [...new Set(values)]
  if (quantities.length !== comparisonQuantities.value.length || quantities.some((quantity, index) => comparisonQuantities.value[index] !== quantity)) {
    comparisonQuantities.value = quantities
    emit('comparisonQuantitiesChange', [...quantities])
  }
  if (!comparisonQuantities.value.includes(sortQuantity.value)) sortQuantity.value = comparisonQuantities.value[0]!
  comparisonInput.value = comparisonQuantities.value.join(', ')
  comparisonError.value = ''
  page.value = 1
}
function sortByQuantity(quantity: number) { sortQuantity.value = quantity; sortMode.value = 'price'; page.value = 1 }
// Reuse the source's existing 1/2/3/custom prices. Only extra quantities invoke the
// calculator, once per channel and quantity for the current reactive context.
// Sorting, pagination and filtering consume this bounded cache without repricing.
const comparisonPrices = computed(() => {
  const result = new Map<string, Map<number, { price: number | null; reason: string; overweight: boolean }>>()
  if (props.active === false || props.sourcePending || props.sourceError || countryLoading.value || countryError.value) return result
  void props.contextKey
  for (const row of rows.value) {
    const values = new Map<number, { price: number | null; reason: string; overweight: boolean }>()
    for (const quantity of comparisonQuantities.value) {
      try {
        const value = row.available === false ? null
          : quantity === 1 ? row.quote1 : quantity === 2 ? row.quote2 : quantity === 3 ? row.quote3
          : quantity === props.customQuantity ? row.quoteCustom : props.calculateQuantityPrice?.(row, quantity) ?? null
        const invalid = value != null && (!Number.isFinite(value) || value < 0)
        const price = value != null && !invalid ? value : null
        const reason = invalid ? '计价结果异常，请重新查询或核对渠道资料' : price != null ? '' : row.quantityMessages?.[String(quantity)] || props.unavailableReason?.(row, quantity) || row.availabilityMessage || '当前条件暂无可用运价'
        values.set(quantity, { price, reason, overweight: price == null && /超重|超过上限|超过渠道重量上限/.test(reason) })
      } catch {
        values.set(quantity, { price: null, reason: '计价异常，请重新查询或核对渠道资料', overweight: false })
      }
    }
    result.set(rowKey(row), values)
  }
  return result
})
function priceFor(row: QuotationMatrixRow, quantity: number) { return comparisonPrices.value.get(rowKey(row))?.get(quantity)?.price ?? null }
function reasonFor(row: QuotationMatrixRow, quantity: number) { return comparisonPrices.value.get(rowKey(row))?.get(quantity)?.reason || '当前条件暂无可用运价' }
function isOverweight(row: QuotationMatrixRow, quantity: number) { return comparisonPrices.value.get(rowKey(row))?.get(quantity)?.overweight ?? false }
function canAddRow(row: QuotationMatrixRow) { return row.available !== false && comparisonQuantities.value.some(quantity => priceFor(row, quantity) != null) }
function copyComparison() {
  const current = filteredRows.value
  emit('copy', current, { quantities: [...comparisonQuantities.value],
    prices: current.map(row => comparisonQuantities.value.map(quantity => priceFor(row, quantity))),
    reasons: current.map(row => comparisonQuantities.value.map(quantity => reasonFor(row, quantity))) })
}
const comparisonGrid = computed(() => ({ gridTemplateColumns: `minmax(260px,1.35fr) 100px repeat(${comparisonQuantities.value.length},minmax(160px,1fr)) 118px`, minWidth: `${478 + comparisonQuantities.value.length * 160}px` }))
const selectedKeys = ref<string[]>([])
const retainedRows = new Map<string, QuotationMatrixRow>()
const selectedQuantities = new Map<string, number[]>()
const draggedCountry = ref('')
const dragOverCountry = ref('')
let observedPresetVersion = -1
let pendingPreset = false

const commonCountries = computed(() => props.countries
  .filter(country => country.stage === 'common')
  .sort((a, b) => a.sortOrder - b.sortOrder || a.name.localeCompare(b.name, 'zh-CN')))

function availableRows(country: string, region?: string) {
  if (props.active === false) return []
  void props.contextKey
  return props.quoteRowsForCountry(country, region).filter(row => row.available !== false)
}
function rowKey(row: QuotationMatrixRow) { return `${row.country}|||${row.quoteRegion || ''}|||${row.channelKey || `${row.rule}|||${row.carrier}|||${row.transport}`}` }
function presetMatchesRow(preset: QuotationPresetSelection, row: QuotationMatrixRow) {
  if (preset.country !== row.country) return false
  if (preset.quoteRegion ? !sameQuotationRegion(preset.quoteRegion, row.quoteRegion) : !!row.quoteRegion && row.quoteRegion !== '全国统一') return false
  if (preset.channelKey?.trim()) return preset.channelKey.trim() === row.channelKey?.trim()
  return !!preset.rule && !!preset.carrier && !!preset.transport
    && preset.rule === row.rule && preset.carrier === row.carrier && preset.transport === row.transport
}
function applyPresetSelection() {
  const presets = props.presetSelection || []
  const allRows = presets.flatMap(preset => availableRows(preset.country, preset.quoteRegion || ''))
  if (!presets.length) {
    selectedKeys.value = []
    emit('selectionChange', [])
    pendingPreset = false
    return
  }
  const matched = presets.map(preset => {
    const row = allRows.find(candidate => presetMatchesRow(preset, candidate))
    return row || { ...preset, country: preset.country, channelKey: preset.channelKey || '',
      rule: preset.rule || '', carrier: preset.carrier || '', transport: preset.transport || preset.rule || '',
      ruleId: Number(preset.channelKey?.split('::')[0]) || 0, channelCode: preset.channelKey?.split('::')[2] || '',
      available: false, eta: '—', freight: 0, totalCostCny: 0, profitCny: 0, quoteCny: 0,
      quote1: null, quote2: null, quote3: null, quoteCustom: null, taxIncluded: false, taxConfigured: true,
      taxRatePercent: null, countryFixedTaxUsd: 0, taxFeeMode: 'no-tax', taxLabel: '',
      tax1Usd: null, tax2Usd: null, tax3Usd: null, taxCustomUsd: null } satisfies QuotationMatrixRow
  })
  for (const row of matched) { retainedRows.set(rowKey(row), row); selectedQuantities.set(rowKey(row), [...comparisonQuantities.value]) }
  selectedKeys.value = [...new Set(matched.map(rowKey))]
  emit('selectionChange', selectedQuoteRows())
  activeCountry.value = matched[0]?.country || activeCountry.value
  pendingPreset = false
}
function isSelected(row: QuotationMatrixRow) { return selectedKeys.value.includes(rowKey(row)) }
const selectedRowsCache = computed(buildSelectedQuoteRows)
function selectedQuoteRows() { return selectedRowsCache.value }
function buildSelectedQuoteRows() {
  const scopes = [...new Set(selectedKeys.value.map(key => JSON.stringify(key.split('|||').slice(0, 2))))]
  const current = scopes.flatMap(scope => { const [country, region] = JSON.parse(scope) as [string, string]; return availableRows(country, region) })
  return selectedKeys.value.flatMap(key => {
    const row = current.find(candidate => rowKey(candidate) === key)
    if (row) { retainedRows.set(key, row); return [row] }
    const old = retainedRows.get(key)
    if (old && props.resolveSelectedRow) {
      const refreshed = props.resolveSelectedRow(old, selectedQuantities.get(key) || [1, 2, 3])
      if (refreshed) { retainedRows.set(key, refreshed); return [refreshed] }
    }
    return old ? [{ ...old, available: false, quote1: null, quote2: null, quote3: null, quoteCustom: null,
      quantityMessages: Object.fromEntries([...new Set([1,2,3,props.customQuantity || 5])].map(count => [String(count), props.unavailableReason?.(old, count) || '当前条件暂无可用运价'])), availabilityMessage: props.unavailableReason?.(old) || '当前渠道或区域无法匹配，请移除后重新添加渠道' }] : []
  })
}
function isAdopted(row: QuotationMatrixRow) { return props.adoptedCountry === row.country && (props.adoptedChannelKey ? props.adoptedChannelKey === row.channelKey : props.adoptedRule === row.rule && props.adoptedCarrier === row.carrier) && (props.countries.find(country => country.name === row.country)?.selectedQuoteRegion || '') === (row.quoteRegion || '') }
function toggleSelection(row: QuotationMatrixRow) {
  const key = rowKey(row)
  retainedRows.set(key, row)
  const removing = isSelected(row)
  if (removing) selectedQuantities.delete(key)
  else selectedQuantities.set(key, [...comparisonQuantities.value])
  const removingPrimary = removing && isAdopted(row)
  selectedKeys.value = removing ? selectedKeys.value.filter(item => item !== key) : [...selectedKeys.value, key]
  const selected = selectedQuoteRows()
  emit('selectionChange', selected)
  const primaryStillSelected = selected.some(isAdopted)
  if (!removing && !primaryStillSelected) emit('adopt', row)
  else if (removingPrimary && selected[0]) emit('adopt', selected[0])
}
function removeSelection(row: QuotationMatrixRow) {
  if (isSelected(row)) toggleSelection(row)
}
defineExpose({ removeSelection })
function startCountryDrag(country: string, event: DragEvent) {
  draggedCountry.value = country
  dragOverCountry.value = ''
  event.dataTransfer?.setData('text/plain', country)
  if (event.dataTransfer) event.dataTransfer.effectAllowed = 'move'
}
function moveCountryOver(country: string, event: DragEvent) {
  if (!draggedCountry.value || draggedCountry.value === country) return
  event.preventDefault()
  dragOverCountry.value = country
  if (event.dataTransfer) event.dataTransfer.dropEffect = 'move'
}
function dropCountry(country: string, event: DragEvent) {
  event.preventDefault()
  const source = draggedCountry.value || event.dataTransfer?.getData('text/plain') || ''
  if (!source || source === country) return endCountryDrag()
  const order = commonCountries.value.map(item => item.name)
  const sourceIndex = order.indexOf(source)
  const targetIndex = order.indexOf(country)
  if (sourceIndex < 0 || targetIndex < 0) return endCountryDrag()
  order.splice(sourceIndex, 1)
  order.splice(targetIndex, 0, source)
  emit('countryOrderChange', order)
  endCountryDrag()
}
function endCountryDrag() {
  draggedCountry.value = ''
  dragOverCountry.value = ''
}
function etaRange(eta: string) {
  const values = eta.match(/\d+/g)?.map(Number) || []
  return [values[0] ?? Number.POSITIVE_INFINITY, values[1] ?? values[0] ?? Number.POSITIVE_INFINITY]
}
function fastestRow(rows: QuotationMatrixRow[]) {
  let best: QuotationMatrixRow | undefined
  for (const row of rows) {
    if (!best) { best = row; continue }
    const [start, end] = etaRange(row.eta)
    const [bestStart, bestEnd] = etaRange(best.eta)
    const order = start - bestStart || end - bestEnd || (priceFor(row, sortQuantity.value) ?? Infinity) - (priceFor(best, sortQuantity.value) ?? Infinity)
    if (order < 0) best = row
  }
  return best
}
function countryFlag(code: string) {
  if (!/^[A-Z]{2}$/i.test(code)) return '🌐'
  return [...code.toUpperCase()].map(letter => String.fromCodePoint(127397 + letter.charCodeAt(0))).join('')
}
function formatUsd(value: number | null) { return value == null ? '—' : `$${value.toFixed(2)}` }
function formatCny(value: number | null) { return value == null ? '—' : `¥${quoteCnyFromUsd(value, props.exchangeRate).toFixed(2)}` }

watch([() => props.active, commonCountries, () => props.contextKey, () => props.presetVersion], () => {
  if (props.active === false) return
  if ((props.presetVersion || 0) !== observedPresetVersion) {
    observedPresetVersion = props.presetVersion || 0
    pendingPreset = observedPresetVersion > 0
  }
  const preferred = commonCountries.value.find(country => country.name === props.adoptedCountry)?.name
  if (!props.countries.some(country => country.name === activeCountry.value)) {
    activeCountry.value = preferred || commonCountries.value[0]?.name || ''
  }
  page.value = 1
  if (pendingPreset) applyPresetSelection()
}, { immediate: true })
watch([() => props.active, () => props.contextKey, () => props.customQuantity], () => {
  if (props.active === false) return
  if (!selectedKeys.value.length) return
  const refreshed = selectedQuoteRows()
  const validKeys = new Set(refreshed.map(rowKey))
  selectedKeys.value = selectedKeys.value.filter(key => validKeys.has(key))
  emit('selectionChange', refreshed)
})

const filteredCountries = computed(() => {
  const query = search.value.trim().toLowerCase()
  if (!query) return props.countries.filter(country => country.stage === 'common' || country.name === activeCountry.value || selectedKeys.value.some(key => key.startsWith(`${country.name}|||`)))
  return props.countries.filter(country => countryMatchesQuery(country, query) || searchMatches.value.includes(country.name))
})
function countryMatchesQuery(country: QuotationCountrySummary, query: string) {
  return `${country.name} ${country.code}`.toLowerCase().includes(query)
}
async function selectCountry(country: string) {
  const request = ++countryRequest
  activeCountry.value = country
  countryLoading.value = true
  countryError.value = ''
  try {
    if (props.ensureCountries && !await props.ensureCountries([country])) throw new Error('渠道加载失败')
  } catch (error) {
    if (request === countryRequest) countryError.value = error instanceof Error && error.message !== '渠道加载失败'
      ? error.message : '渠道加载失败，请重试'
  } finally { if (request === countryRequest) countryLoading.value = false }
}
function runGlobalSearch() {
  const request = ++searchRequest
  clearTimeout(searchTimer)
  searchMatches.value = []
  searchError.value = ''
  const query = search.value.trim().toLowerCase()
  searchLoading.value = !!query && props.active !== false
  if (!searchLoading.value) return
  searchTimer = setTimeout(async () => {
    try {
      const matches = props.searchChannelCountries ? await props.searchChannelCountries(query) : props.countries
        .filter(country => availableRows(country.name).some(row => [row.carrier, row.transport, row.channelCode, row.rule].some(value => value?.toLowerCase().includes(query))))
        .map(country => country.name)
      if (request === searchRequest) searchMatches.value = matches
    } catch {
      if (request === searchRequest) searchError.value = '渠道搜索加载失败，请重试；仍可按国家名称或代码搜索。'
    } finally { if (request === searchRequest) searchLoading.value = false }
  }, 250)
}
watch([search, () => props.active, () => props.contextKey], runGlobalSearch)
onBeforeUnmount(() => { searchRequest++; countryRequest++; clearTimeout(searchTimer); clearTimeout(historyTimer) })
const rows = computed(() => {
  const current = availableRows(activeCountry.value)
  return [...current, ...selectedQuoteRows().filter(row => row.available === false && row.country === activeCountry.value)]
})
const sortedRows = computed(() => {
  const result = rows.value.slice()
  if (sortMode.value === 'price') {
    return result.sort((a, b) => {
      const [aStart, aEnd] = etaRange(a.eta)
      const [bStart, bEnd] = etaRange(b.eta)
      return (priceFor(a, sortQuantity.value) ?? Number.POSITIVE_INFINITY) - (priceFor(b, sortQuantity.value) ?? Number.POSITIVE_INFINITY)
        || aStart - bStart
        || aEnd - bEnd
        || a.transport.localeCompare(b.transport, 'zh-CN')
    })
  }
  if (sortMode.value === 'speed') {
    return result.sort((a, b) => {
      const [aStart, aEnd] = etaRange(a.eta)
      const [bStart, bEnd] = etaRange(b.eta)
      return aStart - bStart || aEnd - bEnd
        || (priceFor(a, sortQuantity.value) ?? Number.POSITIVE_INFINITY) - (priceFor(b, sortQuantity.value) ?? Number.POSITIVE_INFINITY)
        || a.transport.localeCompare(b.transport, 'zh-CN')
    })
  }
  return result
})
const lowest = computed(() => quantitySummary.value.find(item => item.quantity === sortQuantity.value)?.bestRow)
const fastest = computed(() => fastestRow(matchedRows.value.filter(row => priceFor(row, sortQuantity.value) != null)))
const channelQuery = computed(() => channelSearch.value.trim().toLowerCase())
const matchedRows = computed(() => sortedRows.value.filter(row => !channelQuery.value
  || [row.carrier, row.transport, row.channelCode, row.rule].some(value => value?.toLowerCase().includes(channelQuery.value))).filter(row => {
    const query = search.value.trim().toLowerCase()
    return !query || (activeSummary.value && countryMatchesQuery(activeSummary.value, query))
      || [row.carrier, row.transport, row.channelCode, row.rule].some(value => value?.toLowerCase().includes(query))
  }))
const filteredRows = computed(() => matchedRows.value.filter(row => !onlyQuotable.value || priceFor(row, sortQuantity.value) != null))
const quantitySummary = computed(() => comparisonQuantities.value.map(quantity => {
  let valid = 0, over = 0, missing = 0
  let minimum: number | null = null, bestRow: QuotationMatrixRow | undefined
  for (const row of matchedRows.value) {
    const item = comparisonPrices.value.get(rowKey(row))?.get(quantity)
    if (item?.price != null) {
      valid++
      if (minimum == null || item.price < minimum) { minimum = item.price; bestRow = row }
    } else if (item?.overweight) over++
    else missing++
  }
  return { quantity, minimum, bestRow, valid, over, missing }
}))
function isLowest(row: QuotationMatrixRow, quantity: number) { const price = priceFor(row, quantity); return price != null && quantitySummary.value.find(item => item.quantity === quantity)?.minimum === price }
const pageCount = computed(() => Math.max(1, Math.ceil(filteredRows.value.length / pageSize.value)))
const channelCountLabel = computed(() => channelQuery.value || search.value.trim()
  ? `匹配 ${filteredRows.value.length} / ${rows.value.length} 条渠道`
  : `共 ${rows.value.length} 条渠道`)
const pagedRows = computed(() => filteredRows.value.slice((page.value - 1) * pageSize.value, page.value * pageSize.value))
const activeSummary = computed(() => props.countries.find(country => country.name === activeCountry.value))
watch(() => props.sourcePending, pending => {
  if (!pending) return
  // A new product/source query supersedes any local country request.
  countryRequest += 1
  countryLoading.value = false
  countryError.value = ''
})
watch([() => props.active, () => props.autoLoadCountry, activeCountry, () => props.sourcePending,
  () => props.sourceError, () => activeSummary.value?.channelsLoaded], () => {
  if (!props.autoLoadCountry || props.active === false || props.sourcePending || props.sourceError
    || !activeCountry.value || activeSummary.value?.channelsLoaded !== false || countryLoading.value) return
  void selectCountry(activeCountry.value)
}, { immediate: true })
// A product query can supersede a country request. Verified parent data is the
// authority; an old local failure must not hide newly loaded channels.
watch([activeCountry, () => activeSummary.value?.channelsLoaded, () => props.sourcePending, () => props.sourceError], () => {
  if (activeSummary.value?.channelsLoaded !== true || props.sourcePending || props.sourceError) return
  countryRequest += 1
  countryError.value = ''
  countryLoading.value = false
})

watch([activeCountry, pageSize, sortMode, sortQuantity, onlyQuotable, channelQuery, search], () => { page.value = 1 })
watch(pageCount, count => { if (page.value > count) page.value = count })
</script>

<template>
  <section v-if="active !== false" class="common-matrix">
    <header class="common-head">
      <div class="common-head-actions">
        <label class="country-search">⌕<input v-model="search" type="search" aria-label="搜索全部国家或渠道" placeholder="搜索全部国家、代码或物流渠道" @keydown.enter="rememberExactCountrySearch" @blur="rememberExactCountrySearch"></label>
        <nav v-if="frequentSearchCountries.length" class="frequent-country-searches" aria-label="常搜国家">
          <span class="frequent-search-label">常搜国家</span>
          <button v-for="country in frequentSearchCountries" :key="country.code" type="button" class="frequent-country-chip" :title="`搜索${country.name}`" @click="searchFrequentCountry(country)">{{ country.name }}</button>
          <button type="button" class="clear-country-history" aria-label="清空常搜国家记录" @click="clearCountrySearchHistory">清空</button>
        </nav>
      </div>
      <slot name="toolbar-actions" />
    </header>

    <div v-if="filteredCountries.length" class="country-grid">
      <button v-for="country in filteredCountries" :key="country.name" :draggable="!search.trim() && country.stage==='common'" :class="{ active:activeCountry===country.name, dragging:draggedCountry===country.name, 'drag-over':dragOverCountry===country.name }" :title="country.stage==='common' && !search.trim() ? '按住卡片拖动排序' : '查看该国渠道'" @click="selectSearchedCountry(country)" @dragstart="startCountryDrag(country.name,$event)" @dragover="moveCountryOver(country.name,$event)" @drop="dropCountry(country.name,$event)" @dragend="endCountryDrag">
        <u v-if="country.stage==='common' && !search.trim()" aria-hidden="true">⋮⋮</u><i>{{ countryFlag(country.code) }}</i><span><b>{{ country.name }}</b><small>{{ country.code }} · {{ country.channelsLoaded === false ? '点击加载渠道' : `${country.channelCount} 条可用渠道` }}</small></span><em v-if="activeCountry===country.name">已选择</em>
      </button>
    </div>
    <div v-else-if="!searchLoading" class="empty-countries">{{ search.trim() ? '没有匹配的国家或授权渠道，请更换关键词。' : '暂未配置常用国家，可搜索全部国家或渠道。' }}</div>
    <div v-if="searchLoading" class="search-feedback" role="status">正在搜索全部渠道…</div>
    <div v-if="searchError" class="search-feedback" role="alert">{{ searchError }} <button @click="runGlobalSearch">重试搜索</button></div>
    <div v-if="sourcePending" class="search-feedback" role="status">正在读取物流规则与财务设置，请稍候…</div><div v-else-if="sourceError" class="search-feedback" role="alert">{{ sourceError }} <button v-if="activeCountry" @click="selectCountry(activeCountry)">重新加载</button></div><div v-else-if="countryLoading" class="search-feedback" role="status">正在加载 {{ activeCountry }} 的渠道…</div>
    <div v-if="countryError && !sourcePending && !sourceError" class="search-feedback" role="alert">{{ countryError }} <button @click="selectCountry(activeCountry)">重新加载</button></div>

    <template v-if="activeCountry && !sourcePending && !sourceError && !countryLoading && !countryError">
      <div class="country-summary">
        <div class="country-title"><b>{{ activeSummary?.code }}&nbsp; {{ activeCountry }}</b><span>{{ activeSummary?.quoteRegions?.length && !activeSummary.selectedQuoteRegion ? '请选择报价区域以匹配渠道' : `${rows.length} 条渠道 · ${sortQuantity}${unitLabel || '件'}可报 ${quantitySummary.find(item => item.quantity === sortQuantity)?.valid || 0} 条` }}</span><label v-if="activeSummary?.quoteRegions?.length" class="quote-region-select">报价区域<select :value="activeSummary.selectedQuoteRegion" @change="$emit('quoteRegionChange',{ country:activeCountry, region:($event.target as HTMLSelectElement).value })"><option disabled value="">请选择分区</option><option v-for="region in activeSummary.quoteRegions" :key="region" :value="region">{{ region }}</option></select></label></div>
        <div class="metric lowest"><i>¥</i><span><small>{{ batchSample ? '当前样例最低价 · ' : '' }}{{ sortQuantity }}{{ unitLabel || '件' }}最低价渠道</small><b>{{ formatUsd(lowest ? priceFor(lowest, sortQuantity) : null) }}</b><em>{{ lowest ? `${lowest.carrier}｜${lowest.transport}` : '暂无可用渠道' }}</em></span></div>
        <div class="metric fastest"><i>⚡</i><span><small>{{ sortQuantity }}{{ unitLabel || '件' }}可报价最快渠道</small><b>{{ fastest?.eta || '—' }}</b><em>{{ fastest ? `${fastest.carrier}｜${fastest.transport}` : '暂无可用渠道' }}</em></span></div>
        <button :disabled="!filteredRows.length" @click="copyComparison">▦ 复制当前国家</button>
      </div>
      <div class="sort-toolbar" aria-label="渠道排序方式">
        <div class="channel-filter">
          <label class="channel-search"><span aria-hidden="true">⌕</span><input v-model="channelSearch" type="search" aria-label="搜索物流渠道" placeholder="搜索物流商、渠道名称或编码"><button v-if="channelSearch" type="button" aria-label="清空渠道搜索" @click="channelSearch=''">清空</button></label>
          <span class="channel-match-count" role="status">{{ channelCountLabel }}</span>
        </div>
        <nav>
          <button :class="{ active:sortMode==='recommended' }" :aria-pressed="sortMode==='recommended'" @click="sortMode='recommended'">☷ 综合排序</button>
          <label class="quantity-sort-select">按 <select v-model.number="sortQuantity" aria-label="排序数量" @change="sortMode='price'"><option v-for="quantity in comparisonQuantities" :key="quantity" :value="quantity">{{ quantity }}{{ unitLabel || '件' }}</option></select></label>
          <button :class="{ active:sortMode==='price' }" :aria-pressed="sortMode==='price'" @click="sortMode='price'">¥ {{ sortQuantity }}{{ unitLabel || '件' }}价格从低到高</button>
          <button :class="{ active:sortMode==='speed' }" :aria-pressed="sortMode==='speed'" @click="sortMode='speed'">⚡ 速度从快到慢</button>
        </nav>
      </div>
      <div class="quantity-comparison-toolbar">
        <label>比价数量 <input v-model="comparisonInput" aria-label="比价数量" maxlength="80" placeholder="最多 3 个数量，逗号分隔" @keydown.enter="applyComparisonQuantities"></label>
        <button type="button" @click="applyComparisonQuantities">应用数量</button>
        <span>默认 1、2、3 {{ unitLabel || '件' }} · 最多 3 个数量 · 点击应用后计算</span>
        <label class="only-quotable"><input v-model="onlyQuotable" type="checkbox">只看 {{ sortQuantity }}{{ unitLabel || '件' }}可报价</label>
        <small v-if="comparisonError" role="alert">{{ comparisonError }}</small>
      </div>
      <div class="quantity-summary-strip"><button v-for="summary in quantitySummary" :key="summary.quantity" type="button" :class="{ active:sortQuantity===summary.quantity }" @click="sortByQuantity(summary.quantity)"><b>{{ summary.quantity }}{{ unitLabel || '件' }}最低 {{ formatUsd(summary.minimum) }}</b><span>可报价 {{ summary.valid }} · 超重 {{ summary.over }} · 其他不可报价 {{ summary.missing }}</span></button></div>
      <p v-if="batchSample" class="batch-channel-note">每行代表一条物流渠道，不代表一个 SKU；是否适用各 SKU 将在生成时逐项校验。</p>
      <QuoteTaxLegend />
      <div class="quantity-table-scroll">
      <div class="table-head" :style="comparisonGrid"><span>物流渠道</span><span>预计时效</span><button v-for="quantity in comparisonQuantities" :key="quantity" type="button" :class="{ 'quantity-column-active':sortQuantity===quantity }" @click="sortByQuantity(quantity)">{{ batchSample ? '样例' : '' }}{{ quantity }}{{ unitLabel || '件' }}总报价 {{ sortQuantity===quantity && sortMode==='price' ? '↑' : '↕' }}<small>USD / CNY · 点击排序</small></button><span>操作</span></div>
      <div v-if="pagedRows.length" class="quote-rows">
        <article v-for="row in pagedRows" :key="rowKey(row)" :style="comparisonGrid" :class="{ adopted:isAdopted(row), selected:isSelected(row) }">
          <div><span class="channel-name-line"><QuoteChannelName :row="row" /><QuoteTaxMeta :row="row" /></span><small>渠道编码：{{ row.channelCode || '—' }} · 计费规则：{{ row.rule }}<template v-if="priceRegionLabel(row)"> · {{ priceRegionLabel(row) }}</template></small><QuoteMinimumWeight :weight-kg="row.minChargeWeightKg" /></div><b>{{ row.eta }}</b>
          <span v-for="quantity in comparisonQuantities" :key="quantity" class="comparison-price" :class="{ 'quantity-column-active':sortQuantity===quantity, 'comparison-lowest':isLowest(row,quantity), 'comparison-overweight':isOverweight(row,quantity) }"><template v-if="priceFor(row,quantity)!=null"><b>{{ formatUsd(priceFor(row,quantity)) }} <em v-if="isLowest(row,quantity)">最低价</em></b><small>{{ formatCny(priceFor(row,quantity)!) }}</small></template><template v-else><b>{{ isOverweight(row,quantity) ? '超重' : '不可报价' }}</b><QuoteUnavailableReason :price="null" :message="reasonFor(row,quantity)" /></template></span>
          <div class="selection-actions"><button :disabled="!isSelected(row) && !canAddRow(row)" @click="toggleSelection(row)">{{ isSelected(row) ? (row.available === false ? '移除渠道' : batchSample ? '已选用' : '已加入') : batchSample ? '选用此渠道' : '加入报价单' }}</button><button v-if="isSelected(row) && hasAnyQuotationPrice(row)" class="primary-action" @click="$emit('adopt',row)">{{ isAdopted(row) ? '首选' : '设为首选' }}</button></div>
        </article>
      </div>
      <div v-else-if="onlyQuotable && matchedRows.length" class="empty-rows">当前筛选范围内没有 {{ sortQuantity }}{{ unitLabel || '件' }}可报价渠道，可取消“只看可报价”查看原因。</div>
      <div v-else-if="(channelQuery || search.trim()) && rows.length" class="empty-rows">当前国家没有匹配的物流渠道，请选择搜索结果中的国家、更换关键词或清空搜索。</div>
      <div v-else class="empty-rows">{{ activeSummary?.quoteRegions?.length && !activeSummary.selectedQuoteRegion ? '请先选择报价区域，再查看该分区的渠道和价格' : '当前重量和物流属性下暂无可用渠道，请检查财务授权或调整报价条件' }}</div>
      </div>
      <footer><span>{{ channelCountLabel }}</span><label>每页 <select v-model.number="pageSize"><option :value="5">5</option><option :value="10">10</option><option :value="20">20</option></select> 条</label><button :disabled="page<=1" @click="page--">上一页</button><b>{{ page }} / {{ pageCount }}</b><button :disabled="page>=pageCount" @click="page++">下一页</button></footer>
    </template>
  </section>
</template>

<style scoped>
.quantity-comparison-toolbar{display:flex;align-items:center;gap:10px;flex-wrap:wrap;padding:12px 18px;border-bottom:1px solid #e5eaed;font-size:12px}.quantity-comparison-toolbar label{display:flex;align-items:center;gap:8px;font-weight:700}.quantity-comparison-toolbar input:not([type=checkbox]){width:180px;padding:8px 10px;border:1px solid #d8e1e6;border-radius:6px;font:inherit}.quantity-comparison-toolbar>button{padding:8px 12px;border:1px solid #ff9700;border-radius:6px;background:#fff6e5;color:#9b5700;font:inherit;cursor:pointer}.quantity-comparison-toolbar>span{color:#81909b;font-size:11px}.quantity-comparison-toolbar .only-quotable{margin-left:auto;font-weight:400}.quantity-comparison-toolbar small{width:100%;color:#bf3c37}.quantity-sort-select{display:flex;align-items:center;gap:5px;font-size:11px}.quantity-sort-select select{padding:6px;border:1px solid #d8e1e6;border-radius:5px;background:#fff}.quantity-summary-strip{display:flex;gap:9px;padding:10px 18px;background:#fafcfd;flex-wrap:wrap}.quantity-summary-strip button{display:flex;gap:14px;align-items:center;padding:9px 12px;border:1px solid #e2e8ed;background:#fff;border-radius:6px;cursor:pointer}.quantity-summary-strip button.active{border-color:#ff9700;background:#fff8eb}.quantity-summary-strip b{font-size:12px;color:#188253}.quantity-summary-strip span{font-size:10px;color:#71808b}.quantity-table-scroll{overflow-x:auto}.table-head>button{display:grid;gap:4px;text-align:left;background:none;border:0;color:#526170;font-size:11px;font-weight:700;padding:8px;cursor:pointer}.quantity-table-scroll .quantity-column-active{background:#fff7e8;border-radius:5px}.quote-rows .comparison-price{padding:10px 8px;min-height:62px;align-content:center}.comparison-price>b{font-size:12px}.comparison-price em{font-size:9px;font-style:normal;background:#e8f7ed;color:#16815a;border-radius:4px;padding:3px 5px;margin-left:6px;white-space:nowrap}.comparison-price.comparison-lowest>b{color:#16815a}.comparison-price.comparison-overweight>b{color:#c43a35}.comparison-price.comparison-overweight :deep(.quote-unavailable-reason){color:#bd4944!important}.quantity-table-scroll .table-head{padding-top:6px;padding-bottom:6px}
.batch-channel-note{margin:8px 16px;color:#668296;font-size:12px}

.search-feedback{padding:10px 18px;color:#667784;font-size:12px}
.channel-filter{display:flex;align-items:center;gap:12px;flex-wrap:wrap;min-width:0}.channel-search{display:flex;align-items:center;gap:7px;width:300px;max-width:100%;box-sizing:border-box;min-height:36px;padding:0 10px;border:1px solid #d8e1e6;border-radius:7px;color:#7e8b94}.channel-search:focus-within{border-color:#ff9700;box-shadow:0 0 0 2px rgba(255,151,0,.12)}.channel-search input{flex:1;min-width:0;border:0;outline:0;background:transparent;font:inherit;font-size:11px}.channel-search button{height:26px;padding:0 5px;border:0;background:transparent;white-space:nowrap}.channel-match-count{color:#7d8992;font-size:10px;white-space:nowrap}.sort-toolbar{flex-wrap:wrap}
.common-matrix{overflow:hidden;border:1px solid #dfe6eb;border-radius:12px;background:#fff;box-shadow:0 10px 28px rgba(20,34,45,.05);color:#17232d}.common-head{display:flex;align-items:center;justify-content:space-between;gap:20px;padding:20px 22px;border-bottom:1px solid #e6ebef}.common-head p{margin:0 0 4px;color:#d97800;font-size:9px;font-weight:900;letter-spacing:.15em}.common-head h2{margin:0 0 4px;font-size:19px}.common-head span{color:#7d8992;font-size:10px}.common-head>label{display:flex;align-items:center;gap:7px;width:280px;height:36px;padding:0 10px;border:1px solid #d8e1e6;border-radius:7px;color:#7e8b94}.common-head input{min-width:0;flex:1;border:0;outline:0}.country-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:9px;padding:15px 18px;background:#f7f9fb}.country-grid button{display:flex;align-items:center;gap:9px;min-height:62px;padding:10px 12px;border:1px solid #dce4e9;border-radius:8px;background:#fff;text-align:left;cursor:pointer}.country-grid button.active{border-color:#ff9600;background:#fff8eb;box-shadow:0 0 0 2px rgba(255,150,0,.08)}.country-grid i{font-size:23px;font-style:normal}.country-grid span{display:grid;gap:3px;min-width:0}.country-grid b{font-size:12px}.country-grid small{overflow:hidden;color:#83909a;font-size:8px;text-overflow:ellipsis;white-space:nowrap}.country-grid em{margin-left:auto;color:#d87300;font-size:8px;font-style:normal;font-weight:800}.empty-countries,.empty-rows{padding:34px;text-align:center;color:#89959e;font-size:10px}.country-summary{display:grid;grid-template-columns:minmax(210px,1fr) 245px 245px auto;align-items:center;gap:10px;padding:13px 18px;border-top:1px solid #e5eaed;border-bottom:1px solid #e5eaed}.country-title{display:flex;align-items:center;gap:10px}.country-title>b{font-size:16px}.country-title span{padding:5px 9px;border-radius:13px;background:#eaf7ef;color:#188253;font-size:9px;font-weight:800}.metric{display:flex;align-items:center;gap:9px;padding:8px 11px;border-radius:8px}.metric.lowest{background:#fff3df;color:#a65b00}.metric.fastest{background:#eaf6ff;color:#126a9d}.metric>i{width:28px;height:28px;display:grid;place-items:center;border-radius:50%;background:#fff;font-style:normal;font-weight:900}.metric>span{display:grid;grid-template-columns:auto 1fr;gap:1px 8px}.metric small{grid-column:1/-1;font-size:8px}.metric b{font-size:13px}.metric em{align-self:center;overflow:hidden;max-width:135px;font-size:8px;font-style:normal;text-overflow:ellipsis;white-space:nowrap}.country-summary>button{height:35px;padding:0 12px;border:1px solid #4d9b68;border-radius:6px;background:#fff;color:#247442;font-size:9px;font-weight:800}.country-summary>button:disabled{opacity:.4}.table-head,.quote-rows article{display:grid;grid-template-columns:minmax(260px,1.5fr) .7fr repeat(3,.78fr) 80px;align-items:center;gap:12px}.table-head{padding:10px 18px;background:#f5f8fa;color:#71808b;font-size:8px}.table-head span{display:grid;gap:2px}.table-head small{font-size:7px}.quote-rows article{min-height:72px;padding:10px 18px;border-top:1px solid #edf0f2;font-size:10px}.quote-rows article.adopted{background:#fff8e9}.quote-rows article>div{display:grid;gap:3px;min-width:0}.quote-rows article>div b,.quote-rows article>div small{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.quote-rows small{color:#81909b;font-size:8px}.quote-rows article>span{display:grid;gap:2px}.quote-rows article>button{height:31px;border:0;border-radius:6px;background:#17232d;color:#fff;font-size:9px;font-weight:800}.quote-rows article.adopted>button{background:#ff9600;color:#17232d}.common-matrix>footer{display:flex;align-items:center;justify-content:flex-end;gap:8px;padding:11px 18px;border-top:1px solid #e3e9ed;color:#73808a;font-size:9px}.common-matrix>footer>span{margin-right:auto}.common-matrix>footer label{display:flex;align-items:center;gap:5px}.common-matrix>footer select,.common-matrix>footer button{height:28px;border:1px solid #d9e1e6;border-radius:5px;background:#fff;color:#566570;font-size:8px}.common-matrix>footer button:disabled{opacity:.4}@media(max-width:1100px){.country-grid{grid-template-columns:repeat(2,minmax(0,1fr))}.country-summary{grid-template-columns:1fr 1fr}.country-summary>button{justify-self:end}.table-head,.quote-rows article{min-width:900px}.common-matrix{overflow:auto}}@media(max-width:680px){.common-head{align-items:flex-start;flex-direction:column}.common-head>label{width:100%;box-sizing:border-box}.country-grid{grid-template-columns:1fr}.country-summary{grid-template-columns:1fr}.country-summary>button{justify-self:stretch}.common-matrix>footer{min-width:520px}}
.sort-toolbar{display:flex;align-items:center;justify-content:space-between;gap:14px;padding:10px 18px;border-bottom:1px solid #e5eaed;background:#fff}.sort-toolbar>span{display:grid;gap:2px}.sort-toolbar>span b{font-size:10px}.sort-toolbar>span small{color:#8a959d;font-size:8px}.sort-toolbar nav{display:flex;align-items:center;gap:7px}.sort-toolbar button{height:31px;padding:0 12px;border:1px solid #dbe3e8;border-radius:16px;background:#f7f9fa;color:#586773;font-size:9px;font-weight:800;cursor:pointer}.sort-toolbar button:hover{border-color:#efb35a;background:#fff9ee;color:#a75c00}.sort-toolbar button.active{border-color:#ff9700;background:#ff9700;color:#17232d;box-shadow:0 3px 9px rgba(255,151,0,.18)}@media(max-width:680px){.sort-toolbar{align-items:flex-start;flex-direction:column}.sort-toolbar nav{width:100%;overflow-x:auto}.sort-toolbar button{flex:0 0 auto}}
.table-head,.quote-rows article{grid-template-columns:minmax(220px,1.35fr) .58fr repeat(3,.68fr) 118px}
.quote-region-select{display:flex;align-items:center;gap:6px;margin-left:auto;padding:4px 7px;border:1px solid #ffbd63;border-radius:6px;background:#fff8eb;color:#a55a00;font-size:8px;font-weight:800}.quote-region-select select{border:0;background:transparent;color:#9b5300;font-size:9px;font-weight:850;outline:0}
.common-head-actions{display:flex;align-items:center;gap:10px}.common-head-actions label{box-sizing:border-box;display:flex;align-items:center;gap:7px;height:36px;padding:0 10px;border:1px solid #d8e1e6;border-radius:7px;color:#7e8b94;font-size:9px}.common-head-actions .country-search{width:280px}.country-search input{min-width:0;flex:1;border:0;outline:0}
.quote-rows article.selected{box-shadow:inset 3px 0 #ff9700}.selection-actions{display:grid;gap:4px}.selection-actions button{height:27px;border:0;border-radius:5px;background:#17232d;color:#fff;font-size:8px;font-weight:800;cursor:pointer}.selection-actions button:first-child{background:#ff9700;color:#17232d}.selection-actions .primary-action{height:auto;padding:2px 0;background:none;color:#7d8992}.selection-actions .primary-action:hover{color:#c66b00}
@media(max-width:680px){.common-head-actions{width:100%;align-items:stretch;flex-direction:column}.common-head-actions .country-search{width:100%}}
.country-grid button{cursor:grab;transition:border-color .15s,background .15s,box-shadow .15s,opacity .15s,transform .15s}.country-grid button:active{cursor:grabbing}.country-grid button.dragging{opacity:.42;transform:scale(.98)}.country-grid button.drag-over{border-color:#ff8f00;background:#fff2dc;box-shadow:0 0 0 3px rgba(255,143,0,.15)}.country-grid u{color:#aeb8bf;font-size:13px;line-height:1;text-decoration:none;letter-spacing:-3px}
.common-head-actions{min-width:0;max-width:100%;flex-wrap:wrap}
.common-head-actions .country-search{flex-shrink:0;max-width:100%}
.frequent-country-searches{display:flex;align-items:center;flex-wrap:wrap;gap:6px;min-width:0}
.common-head .frequent-search-label{margin-right:4px;color:#83909b;font-size:12px;white-space:nowrap}
.frequent-country-chip{max-width:160px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;min-height:28px;padding:3px 11px;border:1px solid #dce4e9;border-radius:15px;background:#f7f9fb;color:#526170;font:inherit;font-size:12px;line-height:20px;cursor:pointer}
.frequent-country-chip:hover{border-color:#a8cdbd;background:#f0faf5;color:#15835b}
.clear-country-history{min-height:28px;padding:3px 8px;border:0;background:transparent;color:#83909b;font:inherit;font-size:12px;cursor:pointer}
.clear-country-history:hover{color:#526170}
.frequent-country-chip:focus-visible,.clear-country-history:focus-visible{outline:2px solid #159b70;outline-offset:2px}
.channel-name-line{display:flex!important;flex-wrap:wrap;align-items:center;gap:7px;min-width:0}.channel-name-line>b{min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
</style>
