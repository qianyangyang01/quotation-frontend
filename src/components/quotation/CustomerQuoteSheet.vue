<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import {
  buildCustomerQuoteSheet, CUSTOMER_QUOTE_NOTES, formatShippingTime, newQuoteSheetEdits,
  quoteSheetRowKey, reconcileQuoteSheetEdits, quoteSheetProviderKey, quoteSheetProviderName,
  MAX_QUOTE_SHEET_COLUMNS, validQuoteSheetQuantity, QUOTE_SHEET_OPTIONAL_COLUMNS, quoteSheetGroups, quoteSheetTextTable, normalizeQuoteSheetOrder,
  type QuoteSheetOptionalColumn, type QuoteSheetColumnKey, type QuoteSheetEdits,
  formatQuoteSheetCountry, type QuoteSheetCountryFormat,
  type QuoteSheetCountry, type QuoteSheetSourceRow, type QuoteSheetPriceCalculator, type QuoteSheetRowEdits,
} from '@/data/customerQuoteSheet'
import { copyQuoteSheetImage, preloadQuoteSheetAssets, renderCustomerQuoteSheet, type QuoteSheetImage } from '@/services/customerQuoteSheetRenderer'
import { copyQuoteSheetData } from '@/services/customerQuoteSheetClipboard'
import QuoteBackToTop from './QuoteBackToTop.vue'
import QuoteAveragePanel from './QuoteAveragePanel.vue'
import { applyAveragePlans, cloneAveragePlans, mapAveragePlans, type AveragePlan } from '@/data/quoteChannelAverage'
import type { CustomerPriceSnapshot, CapturedSheetPrices } from '@/data/customerQuotePrices'
import { releaseQuotePhotos, MAX_QUOTE_PHOTOS, type QuoteLocalPhoto } from '@/services/quoteLocalPhotos'
import QuotePhotoPicker from './QuotePhotoPicker.vue'
import { MAX_PRICE_EXPRESSION_LENGTH, parseQuotePriceInput } from '@/data/quotePriceExpression'
import { currentAuthUser } from '@/data/authStore'
import { loadQuoteSheetColumnOrder, saveQuoteSheetColumnOrder } from '@/data/quoteSheetColumnPreferences'
import { loadQuoteSheetContact, saveQuoteSheetContact } from '@/data/quoteSheetContactPreferences'

const props = defineProps<{
  rows: QuoteSheetSourceRow[]; countries: QuoteSheetCountry[]; salesperson: string
  contextKey: string; customQuantity: number; bundle: boolean; sourcePending: boolean
  calculatePrice?: QuoteSheetPriceCalculator
  resetKey?: string
  initialQuote?: CustomerPriceSnapshot
  initialSystemQuote?: CustomerPriceSnapshot
  recordMode?: boolean
  canRemoveRows?: boolean
  removalDisabled?: boolean
  showAllRows?: boolean
  skus?: string[]
}>()
const emit = defineEmits<{ removeRow: [key: string] }>()
const layoutUserId = computed(() => currentAuthUser.value.id)
function initialEdits() {
  const contact = props.recordMode ? props.initialQuote?.contact : loadQuoteSheetContact(layoutUserId.value)
  return { ...newQuoteSheetEdits(props.salesperson), whatsapp: '', ...contact, columnOrder: loadQuoteSheetColumnOrder(layoutUserId.value) }
}
const edits = ref<QuoteSheetEdits>(initialEdits())
const layoutSaveState = ref<'saved' | 'failed' | ''>('')
const contactSaveFailed = ref(false)
function rememberContact(field: 'agent' | 'whatsapp', event: Event) {
  const value = (event.target as HTMLInputElement).value.slice(0, 40)
  edits.value[field] = value
  contactSaveFailed.value = !saveQuoteSheetContact(layoutUserId.value, { [field]: value })
}
watch(layoutUserId, userId => {
  edits.value.columnOrder = loadQuoteSheetColumnOrder(userId)
  if (!props.recordMode) {
    const contact = loadQuoteSheetContact(userId)
    edits.value.agent = contact.agent ?? props.salesperson
    edits.value.whatsapp = contact.whatsapp ?? ''
  }
  contactSaveFailed.value = false
  layoutSaveState.value = ''
}, { flush: 'sync' })
onMounted(() => { void preloadQuoteSheetAssets().catch(() => undefined) })
let columnId = 0
function initialColumns() {
  if (props.initialQuote) return props.initialQuote.quantities.map(quantity=>({ id:++columnId, quantity:quantity===0?'':String(quantity), legacyCustom:quantity===0 }))
  const result = [...new Set([1, 2, 3, props.customQuantity || 1])].map(quantity => ({ id: ++columnId, quantity: String(quantity), legacyCustom: false }))
  if (!props.customQuantity && props.rows.some(row => row.quoteCustom != null)) result.push({ id: ++columnId, quantity: '', legacyCustom: true })
  return result
}
const columns = ref(initialColumns())
function loadSavedPrices() {
  for (const saved of props.initialQuote?.rows || []) {
    const source = props.rows.find(row=>row.channelKey===saved.optionId)
    if (!source) continue
    edits.value.fields ||= {}
    edits.value.fields[quoteSheetRowKey(source)] = { prices:Object.fromEntries(props.initialQuote!.quantities.map((q,index)=>[String(q), saved.prices[index]==null ? '' : saved.prices[index]!.toFixed(2)])) }
  }
}
loadSavedPrices()
const editorScroll = ref<HTMLElement>()
const draggedColumn = ref<number | null>(null)
const dropTarget = ref<number | null>(null)
const draggedGroup = ref<QuoteSheetColumnKey | null>(null)
const groupDropTarget = ref<QuoteSheetColumnKey | null>(null)
const quantities = computed(() => columns.value.map(column => Number(column.quantity)))
const editing = ref(true)
const rendering = ref(false)
const copying = ref(false)
const message = ref('')
const failed = ref(false)
const images = shallowRef<Array<QuoteSheetImage & { url: string }>>([])
const activeImage = ref(0)
let generation = 0
let previousContext = props.contextKey
let previousResetKey = props.resetKey ?? props.contextKey
let disposed = false
const photos = shallowRef<QuoteLocalPhoto[]>([])
const showPhotos = ref(true)
const photoPickerOpen = ref(false)
const hasPhotos = computed(() => showPhotos.value && photos.value.length > 0)
function clearPhotos() {
  photoPickerOpen.value = false
  releaseQuotePhotos(photos.value)
  photos.value = []
  showPhotos.value = true
  invalidate()
}
function confirmPhotos(selected: QuoteLocalPhoto[]) {
  releaseQuotePhotos(photos.value.filter(photo => !selected.includes(photo)))
  photos.value = selected
  showPhotos.value = true
  photoPickerOpen.value = false
  invalidate()
}
// Keep local photos out of sheet edits and all persisted snapshots. Product/account/record resets clear them.
watch(() => JSON.stringify([props.skus, props.resetKey ?? props.contextKey, props.bundle]), clearPhotos, { flush: 'sync' })
watch(showPhotos, invalidate, { flush: 'sync' })
// Also clear before an external navigation is placed in the browser back/forward cache.
window.addEventListener('pagehide', clearPhotos)

// Persist visibility separately; saved prices always include every selected route.
function initialHiddenRows() {
  const ids = new Set(props.initialQuote?.hiddenOptionIds ?? [])
  return new Set(props.rows.filter(row => row.channelKey && ids.has(row.channelKey)).map(quoteSheetRowKey))
}
const hiddenRowKeys = ref(initialHiddenRows())
watch(() => JSON.stringify(props.initialQuote?.hiddenOptionIds), () => { hiddenRowKeys.value = initialHiddenRows() })
const showHiddenRows = ref(false)
const rowOrder = ref<string[]>([])
const draggedRow = ref<string | null>(null)
const rowDropTarget = ref<string | null>(null)
const rowControlsDisabled = computed(() => copying.value || props.removalDisabled || props.sourcePending)
const orderedSourceRows = computed(() => {
  const order = new Map(rowOrder.value.map((key, index) => [key, index]))
  return [...props.rows].sort((a, b) => (order.get(quoteSheetRowKey(a)) ?? Infinity) - (order.get(quoteSheetRowKey(b)) ?? Infinity))
})
const visibleSourceRows = computed(() => props.showAllRows ? orderedSourceRows.value : orderedSourceRows.value.filter(row => !hiddenRowKeys.value.has(quoteSheetRowKey(row))))
function moveRow(key: string, target: string) {
  if (rowControlsDisabled.value || key === target) return
  const keys = orderedRowsSheet.value.rows.map(row => row.key)
  const from = keys.indexOf(key), to = keys.indexOf(target)
  if (from < 0 || to < 0) return
  keys.splice(from, 1)
  keys.splice(to, 0, key)
  rowOrder.value = keys
  nextTick(() => [...(editorScroll.value?.querySelectorAll<HTMLButtonElement>('[data-row-handle]') ?? [])].find(button => button.dataset.rowHandle === key)?.focus())
}
function moveRowByKey(key: string, direction: number) {
  const keys = editorSheet.value.rows.map(row => row.key)
  const target = keys[keys.indexOf(key) + direction]
  if (target) moveRow(key, target)
}
function endRowDrag() { draggedRow.value = null; rowDropTarget.value = null }
function startRowDrag(key: string, event: DragEvent) {
  if (rowControlsDisabled.value) { event.preventDefault(); return }
  endColumnDrag()
  draggedRow.value = key
  if (event.dataTransfer) { event.dataTransfer.effectAllowed = 'move'; event.dataTransfer.setData('text/plain', key) }
}
function dragOverRow(key: string, event: DragEvent) {
  if (!draggedRow.value || rowControlsDisabled.value) return
  event.preventDefault()
  rowDropTarget.value = key
  if (event.dataTransfer) event.dataTransfer.dropEffect = 'move'
  if (event.clientY < 100) window.scrollBy(0, -24)
  else if (event.clientY > window.innerHeight - 80) window.scrollBy(0, 24)
}
function dropRow(key: string) {
  if (draggedRow.value) moveRow(draggedRow.value, key)
  endRowDrag()
}
function removeRow(key: string) {
  if (!props.canRemoveRows || props.recordMode || rowControlsDisabled.value) return
  emit('removeRow', key)
}
function buildSheet(rows: QuoteSheetSourceRow[]) { return buildCustomerQuoteSheet({
  rows, countries: props.countries, edits: edits.value, skus: props.skus,
  customQuantity: props.customQuantity, bundle: props.bundle,
  quantities: quantities.value, calculatePrice: props.sourcePending ? undefined : cachedSystemPrice,
  legacyCustomIndex: columns.value.findIndex(column => column.legacyCustom),
}) }
function loadAveragePlans() {
  return mapAveragePlans(props.initialQuote?.averagePlans, id => { const row = props.rows.find(row => row.channelKey === id); return row ? quoteSheetRowKey(row) : id })
}
const averagePlans = ref<AveragePlan[]>(loadAveragePlans())
const averageOpen = ref(averagePlans.value.length > 0)
const averagePanelAnchor = ref<HTMLElement>()
async function toggleAveragePanel() {
  averageOpen.value = !averageOpen.value
  if (averageOpen.value) {
    await nextTick()
    averagePanelAnchor.value?.scrollIntoView?.({ block: 'nearest', behavior: 'smooth' })
  }
}
const averageNotice = ref('')
const averageInputs = ref<Record<string, string>>({})
const averagePriceErrors = ref<Record<string, string>>({})
function withAverageErrors(value: ReturnType<typeof applyAveragePlans>) {
  const errors = Object.values(averagePriceErrors.value)
  return { ...value, issues: [...value.issues, ...errors], tableIssues: [...(value.tableIssues ?? []), ...errors], priceIssues: [...(value.priceIssues ?? []), ...errors] }
}
const systemSheet = computed(() => buildCustomerQuoteSheet({ rows: props.rows, countries: props.countries, edits: newQuoteSheetEdits(props.salesperson), skus: props.skus,
  customQuantity: props.customQuantity, bundle: props.bundle, quantities: quantities.value,
  calculatePrice: props.recordMode ? (row, quantity) => {
    const saved = props.initialSystemQuote
    return saved?.rows.find(r => r.optionId === row.channelKey)?.prices[saved.quantities.indexOf(quantity)] ?? null
  } : props.sourcePending ? undefined : props.calculatePrice, legacyCustomIndex: columns.value.findIndex(column => column.legacyCustom) }))
// Table, preview, hidden rows and averages share one calculation per source/quantity.
// Presentation edits and customer overrides must not rerun logistics pricing.
const systemPriceRows = computed(() => new Map(systemSheet.value.rows.map(row => [row.key, row.prices])))
function cachedSystemPrice(row: QuoteSheetSourceRow, quantity: number) {
  return systemPriceRows.value.get(quoteSheetRowKey(row))?.[quantities.value.indexOf(quantity)] ?? null
}
function addAverage(plan: AveragePlan) {
  const index = averagePlans.value.findIndex(p => p.id === plan.id)
  if (index < 0 && averagePlans.value.length >= 20) { averageNotice.value = '每张报价单最多 20 个综合方案'; return }
  if (index < 0) averagePlans.value.push(plan)
  else averagePlans.value.splice(index, 1, plan)
  for (const key of Object.keys(averageInputs.value)) if (key.startsWith(plan.id + ':')) delete averageInputs.value[key]
  for (const key of Object.keys(averagePriceErrors.value)) if (key.startsWith(plan.id + ':')) delete averagePriceErrors.value[key]
  averageNotice.value = ''
  invalidate()
}
function removeAverage(id: string) { for (const key of Object.keys(averagePriceErrors.value)) if (key.startsWith(id + ':')) delete averagePriceErrors.value[key]; averagePlans.value = averagePlans.value.filter(plan => plan.id !== id); invalidate() }
function sourceFor(key: string) { return props.rows.find(row => quoteSheetRowKey(row) === key)! }
function compactSourceLabel(key: string) {
  const source = sourceFor(key)
  return [source.transport || source.rule, source.quoteRegion === '全国统一' ? '' : source.quoteRegion].filter(Boolean).join(' · ')
}
function changeAveragePrice(id: string, quantity: number, event: Event) {
  const plan = averagePlans.value.find(plan => plan.id === id)
  const raw = (event.target as HTMLInputElement).value
  averageInputs.value[id + ':' + quantity] = raw
  const parsed = parseQuotePriceInput(raw)
  if (!plan) return
  const key = id + ':' + quantity
  if (parsed.error) { averagePriceErrors.value[key] = `综合报价 ${quantity} 数量：${parsed.error}`; averageNotice.value = parsed.error; invalidate(); return }
  delete averagePriceErrors.value[key]
  const index = plan.quantities.indexOf(quantity)
  if (index >= 0) plan.prices[index] = parsed.value
  averageNotice.value = ''
}
watch(() => JSON.stringify([layoutUserId.value, props.resetKey ?? props.contextKey, props.bundle, props.skus]), () => {
  averageInputs.value = {}; averagePriceErrors.value = {}; averagePlans.value = loadAveragePlans(); averageOpen.value = averagePlans.value.length > 0; averageNotice.value = ''
})
watch(() => JSON.stringify(props.initialQuote?.averagePlans), () => { averageInputs.value = {}; averagePriceErrors.value = {}; averagePlans.value = loadAveragePlans() })
watch(() => JSON.stringify(systemSheet.value.rows.map(row => [row.key, quantities.value.map((q, i) => [q, row.prices[i]]).sort((a, b) => Number(a[0]) - Number(b[0]))]).sort()), (value, previous) => {
  if (!props.recordMode && value !== previous && averagePlans.value.length) {
    averageInputs.value = {}; averagePriceErrors.value = {}; averagePlans.value = []; averageNotice.value = '系统价格、渠道或数量已变化，请重新生成综合报价'; averageOpen.value = true
  }
})
watch(averagePlans, invalidate, { deep: true, flush: 'sync' })
const allRowsSheet = computed(() => buildSheet(orderedSourceRows.value))
function orderSheetRows(value: ReturnType<typeof applyAveragePlans>) {
  const order = new Map(rowOrder.value.map((key, index) => [key, index]))
  return { ...value, rows: [...value.rows].sort((a, b) => (order.get(a.key) ?? Infinity) - (order.get(b.key) ?? Infinity)) }
}
// Average rows share the same presentation order as their source routes, including hidden rows.
const orderedRowsSheet = computed(() => orderSheetRows(applyAveragePlans(allRowsSheet.value, averagePlans.value, props.rows, quantities.value, true)))
const sheet = computed(() => withAverageErrors(orderSheetRows(applyAveragePlans(buildSheet(visibleSourceRows.value), averagePlans.value, props.rows, quantities.value, props.showAllRows, allRowsSheet.value.rows))))
const editorSheet = computed(() => orderSheetRows(applyAveragePlans(buildSheet(visibleSourceRows.value), averagePlans.value, props.rows, quantities.value, true, allRowsSheet.value.rows)))
const hiddenRows = computed(() => allRowsSheet.value.rows.filter(row => hiddenRowKeys.value.has(row.key)))
function hideRow(key: string) {
  if (copying.value || props.showAllRows) return
  hiddenRowKeys.value.add(key)
  showHiddenRows.value = true
}
function restoreRow(key: string) {
  if (!copying.value) hiddenRowKeys.value.delete(key)
}
function restoreAllRows() {
  if (!copying.value) hiddenRowKeys.value = new Set()
}
watch(() => JSON.stringify([layoutUserId.value, props.skus, props.resetKey ?? props.contextKey, props.bundle]), () => {
  hiddenRowKeys.value = initialHiddenRows()
  rowOrder.value = []
  endRowDrag()
  showHiddenRows.value = false
}, { flush: 'sync' })
watch(() => props.rows, () => {
  const keys = new Set(props.rows.map(quoteSheetRowKey))
  hiddenRowKeys.value = new Set([...hiddenRowKeys.value].filter(key => keys.has(key)))
}, { deep: true })
watch(() => [...props.rows.map(quoteSheetRowKey), ...averagePlans.value.map(plan => `average:${plan.id}`)], keys => {
  const current = new Set(keys)
  rowOrder.value = rowOrder.value.filter(key => current.has(key))
  if (draggedRow.value && !current.has(draggedRow.value)) endRowDrag()
})
// Tracks calculator dependencies too (tax, weight, exchange rate), even if the original four prices are unchanged.
watch(sheet, invalidate, { flush: 'sync' })
const currentImage = computed(() => images.value[activeImage.value])
const missingProviders = computed(() => !columnVisible('provider') ? [] : [...new Map(visibleSourceRows.value
  .filter(row => !quoteSheetProviderName(row.carrier))
  .map(row => [quoteSheetProviderKey(row.carrier), { key: quoteSheetProviderKey(row.carrier), name: row.carrier || '未命名' }])).values()])
const canCopy = computed(() => Boolean(currentImage.value) && !editing.value && !props.sourcePending && !rendering.value && !copying.value)

const visibleGroups = computed(() => quoteSheetGroups(sheet.value.hiddenColumns, sheet.value.columnOrder, hasPhotos.value))
const textTable = computed(() => quoteSheetTextTable(sheet.value))
const groupNames: Record<QuoteSheetColumnKey, string> = { number: '序号', sku: 'SKU', product: '商品图片', prices: '价格整组', country: '国家', provider: '物流商', shippingTime: '运输时效', processingTime: '处理时间' }
function rememberColumnOrder(order: QuoteSheetColumnKey[]) {
  edits.value.columnOrder = order
  layoutSaveState.value = saveQuoteSheetColumnOrder(layoutUserId.value, order) ? 'saved' : 'failed'
}
function resetColumnOrder() {
  if (!copying.value) rememberColumnOrder(normalizeQuoteSheetOrder())
}
function moveGroup(key: QuoteSheetColumnKey, target: QuoteSheetColumnKey) {
  if (copying.value || key === target) return
  const order = normalizeQuoteSheetOrder(edits.value.columnOrder)
  const from = order.indexOf(key), to = order.indexOf(target)
  order.splice(from, 1)
  order.splice(to, 0, key)
  rememberColumnOrder(order)
  nextTick(() => editorScroll.value?.querySelector<HTMLButtonElement>(`[data-group-handle="${key}"]`)?.focus())
}
function moveGroupByKey(key: QuoteSheetColumnKey, direction: number) {
  const target = visibleGroups.value[visibleGroups.value.findIndex(column => column.key === key) + direction]
  if (target) moveGroup(key, target.key)
}
function startGroupDrag(key: QuoteSheetColumnKey, event: DragEvent) {
  if (copying.value) { event.preventDefault(); return }
  endColumnDrag()
  draggedGroup.value = key
  endRowDrag()
  if (event.dataTransfer) { event.dataTransfer.effectAllowed = 'move'; event.dataTransfer.setData('text/plain', key) }
}
function dropGroup(key: QuoteSheetColumnKey) {
  if (draggedGroup.value) moveGroup(draggedGroup.value, key)
  endColumnDrag()
}
function scrollWhileDragging(event: DragEvent) {
  const container = editorScroll.value
  if (!container || (draggedColumn.value == null && !draggedGroup.value)) return
  const bounds = container.getBoundingClientRect()
  if (event.clientX < bounds.left + 60) container.scrollLeft -= 30
  else if (event.clientX > bounds.right - 60) container.scrollLeft += 30
}
function columnVisible(key: QuoteSheetOptionalColumn) { return !edits.value.hiddenColumns?.includes(key) }
function toggleColumn(key: QuoteSheetOptionalColumn) {
  edits.value.hiddenColumns = columnVisible(key) ? [...(edits.value.hiddenColumns ?? []), key] : edits.value.hiddenColumns?.filter(column => column !== key)
}

function releaseImages() {
  images.value.forEach(image => URL.revokeObjectURL(image.url))
  images.value = []
  activeImage.value = 0
}
function invalidate() {
  generation++
  releaseImages()
  rendering.value = false
  editing.value = true
  message.value = ''
  failed.value = false
}
watch(() => [props.contextKey, props.resetKey, props.rows, props.countries, props.customQuantity, props.bundle, props.sourcePending], invalidate, { deep: true, flush: 'sync' })
// Reset after the parent finishes updating all props, so new quantities never use the old product's custom quantity.
watch(() => [props.contextKey, props.resetKey], () => {
  const resetKey = props.resetKey ?? props.contextKey
  if (resetKey !== previousResetKey) {
    edits.value = initialEdits()
    columns.value = initialColumns()
    loadSavedPrices()
    previousResetKey = resetKey
  } else if (props.contextKey !== previousContext) {
    clearPriceEdits()
  }
  previousContext = props.contextKey
  invalidate()
})
// Invalidate synchronously, but prune only after Vue batches array mutations.
// reverse/sort/splice temporarily contain duplicate or missing rows mid-operation.
watch(() => props.rows, () => {
  edits.value = reconcileQuoteSheetEdits(edits.value, props.rows)
}, { deep: true })
watch(edits, invalidate, { deep: true, flush: 'sync' })
watch(columns, invalidate, { deep: true, flush: 'sync' })
watch(() => props.salesperson, (value, previous) => {
  if (!props.initialQuote?.contact && (props.recordMode || loadQuoteSheetContact(layoutUserId.value).agent === undefined) && edits.value.agent === previous) edits.value.agent = value
})

function updateShippingTime(row: QuoteSheetSourceRow, event: Event) {
  edits.value.shippingTimes[quoteSheetRowKey(row)] = (event.target as HTMLInputElement).value
}
function updateProviderName(key: string, event: Event) {
  edits.value.providerNames ||= {}
  edits.value.providerNames[key] = (event.target as HTMLInputElement).value
}
function restoreShippingTime(row: QuoteSheetSourceRow) {
  delete edits.value.shippingTimes[quoteSheetRowKey(row)]
}
function rowEdits(key: string) {
  edits.value.fields ||= {}
  edits.value.fields[key] ||= {}
  return edits.value.fields[key]
}
function updateField(key: string, field: Exclude<keyof QuoteSheetRowEdits, 'prices'>, event: Event) {
  rowEdits(key)[field] = (event.target as HTMLInputElement).value
}
function setCountryFormat(format: QuoteSheetCountryFormat) {
  if (copying.value || (edits.value.countryFormat ?? 'name') === format) return
  for (const fields of Object.values(edits.value.fields || {})) {
    if (fields.country !== undefined) {
      const formatted = formatQuoteSheetCountry(fields.country, props.countries, format)
      if (formatted) fields.country = formatted
    }
  }
  edits.value.countryFormat = format
}
function updatePrice(key: string, quantity: number, event: Event) {
  if (quantities.value.filter(value => value === quantity).length !== 1) return
  const fields = rowEdits(key)
  fields.prices ||= {}
  fields.prices[String(quantity)] = (event.target as HTMLInputElement).value
}
function clearPriceEdits() {
  Object.values(edits.value.fields || {}).forEach(fields => { delete fields.prices })
}
function priceInputError(key: string, quantity: number) {
  return parseQuotePriceInput(edits.value.fields?.[key]?.prices?.[String(quantity)] ?? '').error
}
function confirmPrice(key: string, quantity: number, event: Event) {
  const input = event.target as HTMLInputElement
  if (input.disabled || copying.value || props.sourcePending || quantities.value.filter(value => value === quantity).length !== 1) return
  const raw = edits.value.fields?.[key]?.prices?.[String(quantity)]
  if (raw === undefined) return
  const parsed = parseQuotePriceInput(raw)
  if (parsed.error || parsed.value === null) return
  rowEdits(key).prices![String(quantity)] = parsed.value.toFixed(2)
  input.value = parsed.value.toFixed(2)
}
watch(() => new Map(props.rows.map(row => [quoteSheetRowKey(row), JSON.stringify([row.quote1, row.quote2, row.quote3, row.quoteCustom])])), (current, previous) => {
  // Deletion and sorting must not erase customer prices on surviving routes.
  if (current.size <= previous.size && [...current].every(([key, prices]) => previous.get(key) === prices)) return
  clearPriceEdits()
  loadSavedPrices()
})
watch(() => props.sourcePending, value => { if (value) clearPriceEdits() })
async function addColumn() {
  if (copying.value || columns.value.length >= MAX_QUOTE_SHEET_COLUMNS) return
  columns.value.push({ id: ++columnId, quantity: '', legacyCustom: false })
  await nextTick()
  const inputs = editorScroll.value?.querySelectorAll<HTMLInputElement>('.sheet-quantity input')
  const input = inputs?.[inputs.length - 1]
  input?.focus()
  input?.scrollIntoView?.({ block: 'nearest', inline: 'nearest' })
}
function removeColumn(index: number) {
  if (copying.value || columns.value.length <= 1) return
  const quantity = String(Number(columns.value[index].quantity))
  columns.value.splice(index, 1)
  clearUnusedQuantityPrice(quantity)
}
function clearUnusedQuantityPrice(quantity: string) {
  if (columns.value.some(column => String(Number(column.quantity)) === quantity)) return
  Object.values(edits.value.fields || {}).forEach(fields => { if (fields.prices) delete fields.prices[quantity] })
}
function updateQuantity(index: number, event: Event) {
  const old = String(Number(columns.value[index].quantity))
  columns.value[index].quantity = (event.target as HTMLInputElement).value
  columns.value[index].legacyCustom = false
  clearUnusedQuantityPrice(old)
}
function updateNote(index: number, event: Event) {
  edits.value.notes ||= [...CUSTOMER_QUOTE_NOTES]
  edits.value.notes[index] = (event.target as HTMLTextAreaElement).value
}
function moveColumn(from: number, to: number) {
  if (copying.value || from < 0 || to < 0 || from >= columns.value.length || to >= columns.value.length || from === to) return
  const reordered = [...columns.value]
  const [column] = reordered.splice(from, 1)
  reordered.splice(to, 0, column)
  columns.value = reordered
  nextTick(() => editorScroll.value?.querySelector<HTMLButtonElement>(`[data-column-handle="${column.id}"]`)?.focus())
}
function startColumnDrag(id: number, event: DragEvent) {
  if (copying.value) { event.preventDefault(); return }
  endColumnDrag()
  draggedColumn.value = id
  endRowDrag()
  if (event.dataTransfer) { event.dataTransfer.effectAllowed = 'move'; event.dataTransfer.setData('text/plain', String(id)) }
}
function endColumnDrag() { draggedColumn.value = null; dropTarget.value = null; draggedGroup.value = null; groupDropTarget.value = null }
function dropColumn(index: number) {
  if (draggedColumn.value != null) moveColumn(columns.value.findIndex(column => column.id === draggedColumn.value), index)
  endColumnDrag()
}
async function preview() {
  if (disposed || copying.value || props.sourcePending || rendering.value || !sheet.value.rows.length) return
  invalidate()
  if (sheet.value.issues.length) {
    failed.value = true
    message.value = sheet.value.issues.join('；')
    return
  }
  const token = generation
  const snapshot = sheet.value
  rendering.value = true
  try {
    const result = await renderCustomerQuoteSheet(snapshot, () => disposed || generation !== token, hasPhotos.value ? [...photos.value] : [])
    if (disposed || token !== generation) return
    const allocated: typeof images.value = []
    try {
      for (const image of result) allocated.push({ ...image, url: URL.createObjectURL(image.blob) })
    } catch (error) {
      allocated.forEach(image => URL.revokeObjectURL(image.url))
      throw error
    }
    images.value = allocated
    editing.value = false
    if (images.value.length > 1) message.value = `共 ${images.value.length} 张图片，请切换后逐张复制；完整说明位于最后一张。`
  } catch (error) {
    if (disposed || token !== generation) return
    failed.value = true
    message.value = error instanceof Error ? error.message : '预览生成失败，请重试'
  } finally {
    if (!disposed && token === generation) rendering.value = false
  }
}
async function copyCurrent() {
  if (!canCopy.value || !currentImage.value) return
  const token = generation
  const index = activeImage.value
  copying.value = true
  failed.value = false
  message.value = ''
  try {
    await copyQuoteSheetImage(currentImage.value.blob)
    if (disposed) return
    if (token !== generation) {
      failed.value = true
      message.value = '报价已发生变化，请重新预览并复制最新图片'
      return
    }
    message.value = images.value.length > 1
      ? `已复制第 ${index + 1} 张图片，可粘贴给客户；请继续复制其余图片`
      : '报价图片已复制，可直接粘贴给客户'
  } catch (error) {
    if (disposed) return
    failed.value = true
    message.value = error instanceof Error ? error.message : '图片复制失败，请重试'
  } finally { copying.value = false }
}
async function copyData() {
  if (disposed || props.sourcePending || copying.value || rendering.value) return
  const token = generation
  copying.value = true
  failed.value = false
  message.value = ''
  try {
    await copyQuoteSheetData(sheet.value)
    if (disposed) return
    if (token !== generation) throw new Error('报价已发生变化，请重新复制最新数据')
    message.value = `已复制 ${sheet.value.rows.length} 条报价数据，可直接粘贴到 Excel / WPS 表格`
  } catch (error) {
    if (disposed) return
    failed.value = true
    message.value = error instanceof Error ? error.message : '报价数据未复制成功，请重试'
  } finally { copying.value = false }
  return { message: message.value, failed: failed.value }
}
// Record drawers reuse this same model, edits and clipboard flow.
function capturePrices(): CapturedSheetPrices {
  if (props.sourcePending || copying.value) throw new Error('报价仍在计算或复制，请稍后保存')
  if (sheet.value.priceIssues?.length) throw new Error(sheet.value.priceIssues.join('；'))
  if (allRowsSheet.value.priceIssues?.length) throw new Error(allRowsSheet.value.priceIssues.join('；'))
  const system = systemSheet.value
  if (system.priceIssues?.length) throw new Error(system.priceIssues.join('；'))
  return { averagePlans: cloneAveragePlans(averagePlans.value).map(plan => ({ ...plan, quantities: [...quantities.value], prices: quantities.value.map(q => plan.prices[plan.quantities.indexOf(q)] ?? null), systemPrices: quantities.value.map(q => plan.systemPrices[plan.quantities.indexOf(q)] ?? null), members: plan.members.map(m => ({...m, sourcePrices: quantities.value.map(q => m.sourcePrices[plan.quantities.indexOf(q)] ?? null)})) })), hiddenRowKeys: [...hiddenRowKeys.value], contact: { agent: edits.value.agent, whatsapp: edits.value.whatsapp ?? '' }, quantities:[...quantities.value], rows:allRowsSheet.value.rows.map(row=>({ key:row.key, prices:[...row.prices], systemPrices:[...system.rows.find(original=>original.key===row.key)!.prices] })) }
}
defineExpose({ preview, copyData, invalidate, copying, capturePrices })
onBeforeUnmount(() => {
  window.removeEventListener('pagehide', clearPhotos)
  disposed = true
  releaseQuotePhotos(photos.value)
  generation++
  releaseImages()
})
</script>

<template>
  <section class="customer-sheet" aria-labelledby="customer-sheet-title">
    <QuoteBackToTop />
    <div class="sheet-toolbar"><h3 id="customer-sheet-title">客户报价单</h3></div>
    <p v-if="message" class="sheet-message" :class="{ failed }" :role="failed ? 'alert' : 'status'">{{ message }}</p>
    <div v-if="averageOpen" ref="averagePanelAnchor"><QuoteAveragePanel :rows="rows" :system="systemSheet" :quantities="quantities" :plans="averagePlans" :disabled="copying || rendering || sourcePending" @add="addAverage" @remove="removeAverage" /></div>
    <p v-if="averageNotice" class="sheet-pending" role="alert">{{ averageNotice }}</p>
    <QuotePhotoPicker v-if="photoPickerOpen" :photos="photos" @cancel="photoPickerOpen = false" @confirm="confirmPhotos" />
    <p v-if="sourcePending" class="sheet-pending" role="status">当前报价数据尚未就绪，请完成物流计算后预览。</p>
    <p v-if="!rows.length" class="sheet-empty">请先在上方报价矩阵中选择需要报价的国家与渠道</p>
    <div v-else class="sheet-editor">
      <template v-if="editing">
      <div class="sheet-metadata">
        <label>报价单标题<input :value="edits.title ?? 'JerryFulfillment Quote Sheet'" aria-label="报价单标题" maxlength="80" :disabled="copying" @input="edits.title = ($event.target as HTMLInputElement).value"></label>
        <label>By Agent · 署名<input :value="edits.agent" @input="rememberContact('agent', $event)" aria-label="报价单署名" autocomplete="off" maxlength="40" :disabled="copying"></label>
        <label>Date · 日期<input v-model="edits.date" aria-label="报价单日期" type="date" min="1000-01-01" max="9999-12-31" :disabled="copying"></label>
        <label>WhatsApp · 联系方式<input :value="edits.whatsapp" @input="rememberContact('whatsapp', $event)" aria-label="WhatsApp 联系方式" type="tel" autocomplete="off" maxlength="40" placeholder="例如 +86 138 0013 8000" :disabled="copying"></label>
      </div>
      <p v-if="contactSaveFailed" role="alert" class="sheet-message failed">署名或联系方式未能记住，请检查浏览器存储权限后重新填写。</p>
      <div v-if="missingProviders.length" class="sheet-provider-editor">
        <p class="sheet-edit-help">英文名补填：以下物流商尚无英文显示名，请填写后预览或复制。同一物流商的全部渠道共用此名称，仅当前页面有效。</p>
        <div class="sheet-metadata"><label v-for="provider in missingProviders" :key="provider.key">{{ provider.name }} · English name<input :value="edits.providerNames?.[provider.key] || ''" :aria-label="`${provider.name}英文名`" autocomplete="off" placeholder="Enter English name" maxlength="80" :disabled="copying" @input="updateProviderName(provider.key, $event)"></label></div>
      </div>
      <p v-if="!calculatePrice" class="sheet-edit-help">历史记录仅带出已保存数量的价格；新增数量没有历史价格时，请手动填写。</p>
      </template>
      <div class="sheet-controls">
        <div class="sheet-display-tools">
          <fieldset class="sheet-visibility" :disabled="copying" aria-label="显示列">
            <strong>显示列</strong>
            <label v-for="column in QUOTE_SHEET_OPTIONAL_COLUMNS" :key="column.key"><input type="checkbox" :checked="columnVisible(column.key)" :aria-label="`显示${column.name}列`" @change="toggleColumn(column.key)">{{ column.name }}</label>
          </fieldset>
          <fieldset class="sheet-photos" :disabled="copying || rendering" aria-label="报价单图片">
            <button class="sheet-photo-button" type="button" @click="photoPickerOpen = true">{{ photos.length ? '管理图片' : '添加图片' }}</button>
            <span>{{ photos.length }} / {{ MAX_QUOTE_PHOTOS }} 张</span>
            <button v-if="photos.length" type="button" @click="clearPhotos">移除图片</button>
            <label v-if="photos.length"><input v-model="showPhotos" type="checkbox" aria-label="显示商品图片列">显示图片</label>
          </fieldset>
        </div>
      <div class="sheet-row-tools">
        <template v-if="editing">
        <strong>显示 {{ sheet.rows.length }} 行 · 已隐藏 {{ hiddenRows.length }} 行</strong>
        <button v-if="hiddenRows.length" type="button" :aria-expanded="showHiddenRows" @click="showHiddenRows = !showHiddenRows">{{ showHiddenRows ? '收起隐藏行' : '查看隐藏行' }}（{{ hiddenRows.length }}）</button>
        <button v-if="hiddenRows.length" type="button" :disabled="copying" @click="restoreAllRows">恢复全部</button>
        <button type="button" :disabled="copying" @click="resetColumnOrder">恢复默认列顺序</button>
        </template>
        <div class="sheet-actions">
          <button type="button" :disabled="copying" :aria-expanded="averageOpen" @click="toggleAveragePanel">渠道平均报价</button>
          <button v-if="!editing" class="sheet-primary" type="button" :disabled="copying" @click="invalidate">编辑报价单</button>
          <button v-if="editing" class="sheet-primary" type="button" :disabled="sourcePending || !sheet.rows.length || rendering || copying" @click="preview">{{ rendering ? '正在生成预览…' : '预览报价单' }}</button>
          <button type="button" :disabled="!canCopy" @click="copyCurrent">{{ copying ? '正在复制…' : images.length > 1 ? '复制当前图片' : '复制报价图片' }}</button>
          <button type="button" :disabled="sourcePending || !sheet.rows.length || rendering || copying" @click="copyData">复制报价数据</button>
          <button v-if="editing" type="button" class="sheet-add-button" :title="`价格列 ${columns.length} / ${MAX_QUOTE_SHEET_COLUMNS}`" :disabled="copying || columns.length >= MAX_QUOTE_SHEET_COLUMNS" @click="addColumn">＋ 新增列</button>
        </div>
      </div>
      <div v-if="editing && layoutSaveState" class="sheet-layout-preference">
        <span v-if="layoutSaveState === 'saved'" role="status">已记住列顺序</span>
        <span v-else-if="layoutSaveState === 'failed'" role="alert">列顺序未能保存，当前调整仅本次有效；请允许浏览器本地存储后重新调整。</span>
      </div>
      </div>
      <template v-if="editing">
      <p v-if="!sheet.rows.length" class="sheet-pending" role="status">所有行已隐藏，请恢复至少一行后再预览或复制。</p>
      <div ref="editorScroll" class="sheet-editor-scroll" @dragover="scrollWhileDragging">
        <table>
          <thead>
            <tr>
              <th v-for="group in visibleGroups" :key="group.key" :data-group="group.key"
                :rowspan="group.key === 'prices' ? 1 : 2" :colspan="group.key === 'prices' ? columns.length : 1"
                class="sheet-group" :class="{ 'sheet-price-group': group.key === 'prices', 'sheet-drop-target': groupDropTarget === group.key && draggedGroup !== group.key }"
                @dragover.prevent="groupDropTarget = draggedGroup ? group.key : null" @drop.prevent="dropGroup(group.key)">
                <button type="button" class="sheet-group-drag" :draggable="!copying" :data-group-handle="group.key"
                  :aria-label="`${groupNames[group.key]}排序，左右键移动`" title="拖动排序，或聚焦后按左右方向键" :disabled="copying"
                  @dragstart="startGroupDrag(group.key, $event)" @dragend="endColumnDrag"
                  @keydown.left.prevent="moveGroupByKey(group.key, -1)" @keydown.right.prevent="moveGroupByKey(group.key, 1)">⠿ <span>{{ group.key === 'prices' ? '价格 · USD' : group.label }}</span></button>
                <div v-if="group.key === 'country'" class="sheet-country-format" role="group" aria-label="国家显示方式">
                  <button type="button" :aria-pressed="(edits.countryFormat ?? 'name') === 'name'" :disabled="copying" @click="setCountryFormat('name')">全称</button>
                  <button type="button" :aria-pressed="edits.countryFormat === 'code'" :disabled="copying" @click="setCountryFormat('code')">二字码</button>
                </div>
              </th>
              <th rowspan="2" class="sheet-row-action">操作</th>
            </tr>
            <tr>
            <th v-for="(column, index) in columns" :key="column.id" class="sheet-quantity" :class="{ 'sheet-drop-target': dropTarget === column.id && draggedColumn !== column.id }" @dragover.prevent.stop="dropTarget = draggedColumn == null ? null : column.id; groupDropTarget = draggedGroup ? 'prices' : null; scrollWhileDragging($event)" @drop.prevent.stop="draggedGroup ? dropGroup('prices') : dropColumn(index)">
              <button type="button" class="sheet-drag" :draggable="!copying" :data-column-handle="column.id" :aria-label="`第 ${index + 1} 个价格列排序，左右键移动`" title="拖动排序，或聚焦后按左右方向键" :disabled="copying" @dragstart="startColumnDrag(column.id, $event)" @dragend="endColumnDrag" @keydown.left.prevent="moveColumn(index, index - 1)" @keydown.right.prevent="moveColumn(index, index + 1)">⠿</button>
              <button v-if="columns.length > 1" type="button" class="sheet-remove" :aria-label="`删除第 ${index + 1} 个价格列`" :disabled="copying" @click="removeColumn(index)">×</button>
              <label><input :value="column.quantity" type="number" min="1" step="1" :aria-label="`第 ${index + 1} 个价格列数量`" :placeholder="column.legacyCustom ? 'Custom' : '数量'" :disabled="copying" @input="updateQuantity(index, $event)"> {{ bundle ? (Number(column.quantity) === 1 ? 'set' : 'sets') : (Number(column.quantity) === 1 ? 'pc' : 'pcs') }}</label>
            </th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(row, index) in editorSheet.rows" :key="row.key" :class="{ 'sheet-average-row': row.averageId, 'sheet-row-dragging': draggedRow === row.key, 'sheet-row-drop-target': rowDropTarget === row.key && draggedRow !== row.key }" @dragover="dragOverRow(row.key, $event)" @drop.prevent="dropRow(row.key)">
              <template v-for="group in visibleGroups" :key="group.key">
              <td v-if="group.key === 'number' && row.averageId"><b>AVG</b></td>
              <td v-else-if="group.key === 'number'"><input class="sheet-number" :value="edits.fields?.[row.key]?.number ?? row.number" :aria-label="`第 ${index + 1} 行序号`" :disabled="copying" @input="updateField(row.key, 'number', $event)"></td>
              <td v-else-if="group.key === 'product' && index === 0" :rowspan="editorSheet.rows.length" class="sheet-photo-cell"><div class="sheet-photo-grid" :class="{ 'sheet-photo-grid-many': photos.length > 2 }"><img v-for="(photo, photoIndex) in photos" :key="photo.url" :src="photo.url" :alt="`临时商品图 ${photoIndex + 1}`"></div></td>
              <td v-else-if="group.key === 'sku'" class="sheet-sku">{{ row.sku }}</td>
              <td v-else-if="row.averageId && ['country', 'provider', 'shippingTime', 'processingTime'].includes(group.key)">{{ row[group.key as 'country' | 'provider' | 'shippingTime' | 'processingTime'] }}<small v-if="group.key === 'country' && row.region" class="sheet-zone-label">{{ row.region }}</small></td>
              <td v-else-if="group.key === 'country'"><input class="sheet-country" :value="edits.fields?.[row.key]?.country ?? row.country" :aria-label="`第 ${index + 1} 行国家`" maxlength="80" :disabled="copying" @input="updateField(row.key, 'country', $event)"><div v-if="row.region || row.regionTranslationRequired"><input class="sheet-zone-label" :value="edits.fields?.[row.key]?.region ?? row.region" :aria-label="`第 ${index + 1} 行英文分区`" placeholder="English zone" maxlength="80" :disabled="copying" @input="updateField(row.key, 'region', $event)"></div></td>
              <td v-else-if="group.key === 'provider'"><input class="sheet-provider" :value="edits.fields?.[row.key]?.provider ?? row.provider" :aria-label="`第 ${index + 1} 行物流商`" maxlength="80" :disabled="copying" @input="updateField(row.key, 'provider', $event)"><small class="sheet-source" :title="row.sourceDescription">{{ compactSourceLabel(row.key) }}</small></td>
              <td v-else-if="group.key === 'shippingTime'" class="sheet-time"><input :value="edits.shippingTimes[row.key] ?? (formatShippingTime(sourceFor(row.key).eta) === '—' ? '' : formatShippingTime(sourceFor(row.key).eta))" :aria-label="`第 ${index + 1} 行运输时效`" placeholder="例如 6-12 workingdays" maxlength="80" :disabled="copying" @input="updateShippingTime(sourceFor(row.key), $event)"><button v-if="row.key in edits.shippingTimes" type="button" :disabled="copying" @click="restoreShippingTime(sourceFor(row.key))">恢复渠道时效</button></td>
              <td v-else-if="group.key === 'processingTime'"><input class="sheet-processing" :value="edits.fields?.[row.key]?.processingTime ?? row.processingTime" :aria-label="`第 ${index + 1} 行处理时间`" maxlength="80" :disabled="copying" @input="updateField(row.key, 'processingTime', $event)"></td>
              <template v-else-if="group.key === 'prices' && row.averageId"><td v-for="(price, priceIndex) in row.prices" :key="columns[priceIndex].id" class="sheet-price"><span>$</span><input :value="averageInputs[row.averageId + ':' + quantities[priceIndex]] ?? (price == null ? '' : price.toFixed(2))" :aria-label="'综合报价 ' + row.averageId + ' 数量 ' + quantities[priceIndex]" placeholder="客户报价" :disabled="copying || sourcePending" @input="changeAveragePrice(row.averageId!, quantities[priceIndex], $event)"></td></template>
              <template v-else-if="group.key === 'prices'">
              <td v-for="(price, priceIndex) in row.prices" :key="columns[priceIndex].id" class="sheet-price"><span>$</span><input :value="edits.fields?.[row.key]?.prices?.[String(quantities[priceIndex])] ?? (price == null ? '' : price.toFixed(2))" :aria-label="`第 ${index + 1} 行第 ${priceIndex + 1} 列美元价格`" :aria-invalid="Boolean(priceInputError(row.key, quantities[priceIndex]))" inputmode="text" placeholder="金额或算式" :maxlength="MAX_PRICE_EXPRESSION_LENGTH" :disabled="copying || sourcePending || quantities.filter(value => value === quantities[priceIndex]).length !== 1 || (!validQuoteSheetQuantity(quantities[priceIndex]) && !columns[priceIndex].legacyCustom)" @input="updatePrice(row.key, quantities[priceIndex], $event)" @blur="confirmPrice(row.key, quantities[priceIndex], $event)" @keydown.enter.prevent="confirmPrice(row.key, quantities[priceIndex], $event)"><small v-if="priceInputError(row.key, quantities[priceIndex])" class="sheet-price-error">{{ priceInputError(row.key, quantities[priceIndex]) }}</small></td>
              </template>
              </template>
              <td class="sheet-row-action"><div class="sheet-row-controls">
                <button v-if="!recordMode" type="button" class="sheet-row-handle" :data-row-handle="row.key" :draggable="!rowControlsDisabled" :disabled="rowControlsDisabled" :aria-label="`第 ${index + 1} 行排序，上下键移动`" title="拖拽整行排序，也可按上下方向键" @dragstart.stop="startRowDrag(row.key, $event)" @dragend="endRowDrag" @keydown.up.prevent="moveRowByKey(row.key, -1)" @keydown.down.prevent="moveRowByKey(row.key, 1)">⠿</button>
                <button v-if="!recordMode" type="button" :aria-label="`上移第 ${index + 1} 行`" title="上移" :disabled="rowControlsDisabled || index === 0" @click="moveRowByKey(row.key, -1)">↑</button>
                <button v-if="!recordMode" type="button" :aria-label="`下移第 ${index + 1} 行`" title="下移" :disabled="rowControlsDisabled || index === editorSheet.rows.length - 1" @click="moveRowByKey(row.key, 1)">↓</button>
                <button v-if="row.averageId" type="button" class="sheet-delete-row" :aria-label="`删除第 ${index + 1} 行综合方案`" title="删除此平均行，同时移除上方对应方案" :disabled="rowControlsDisabled" @click="removeAverage(row.averageId!)">删除</button>
                <template v-else>
                  <button type="button" title="隐藏此渠道，不影响综合报价计算" :aria-label="`隐藏第 ${index + 1} 行`" :disabled="copying || showAllRows" @click="hideRow(row.key)">隐藏</button>
                  <button v-if="canRemoveRows && !recordMode" type="button" class="sheet-delete-row" :aria-label="`移除第 ${index + 1} 行渠道`" title="从本次报价删除，并取消渠道选择" :disabled="rowControlsDisabled" @click="removeRow(row.key)">删除</button>
                </template>
              </div></td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-if="hiddenRows.length && showHiddenRows" class="sheet-hidden-rows">
        <div class="sheet-hidden-heading"><strong>已隐藏行（{{ hiddenRows.length }}）</strong><span>仅当前报价单有效，可随时恢复。</span></div>
        <div v-for="row in hiddenRows" :key="row.key" class="sheet-hidden-row">
          <span>{{ row.sku }}</span><span>{{ row.country }}<small v-if="row.region" class="sheet-zone-label">{{ row.region }}</small></span><span>{{ row.provider }}<small>{{ row.sourceDescription }}</small></span>
          <button type="button" :aria-label="`恢复 ${row.sourceDescription}`" :disabled="copying" @click="restoreRow(row.key)">恢复</button>
        </div>
      </div>
      <p v-if="sheet.tableIssues?.length" class="sheet-pending" role="status">{{ sheet.tableIssues.join('；') }}</p>
      <details class="sheet-notes-editor"><summary>报价说明</summary><label v-for="(note, index) in (edits.notes ?? CUSTOMER_QUOTE_NOTES)" :key="index">{{ index + 1 }}<textarea :value="note" :aria-label="`第 ${index + 1} 条报价说明`" maxlength="3000" :disabled="copying" @input="updateNote(index, $event)"></textarea></label></details>
      </template>
    <div v-if="!editing && currentImage" class="sheet-image-area">
      <div v-if="images.length > 1" class="sheet-pages">
        <button type="button" :disabled="activeImage === 0 || copying" @click="activeImage--">上一张</button>
        <span>第 {{ activeImage + 1 }} / {{ images.length }} 张 · 第 {{ currentImage.firstRow }}–{{ currentImage.lastRow }} 条渠道</span>
        <button type="button" :disabled="activeImage === images.length - 1 || copying" @click="activeImage++">下一张</button>
      </div>
      <div class="sheet-image-scroll"><img :src="currentImage.url" :width="currentImage.width" :height="currentImage.height" alt="JerryFulfillment Quote Sheet 客户报价图片预览" draggable="false"></div>
      <details class="sheet-accessible"><summary>查看报价单文字内容</summary>
        <p>{{ sheet.title }} — By Agent: {{ sheet.agent }} — Date: {{ sheet.date }}<span v-if="sheet.whatsapp"> — WhatsApp: {{ sheet.whatsapp }}</span></p>
        <table><thead><tr><th v-for="(label, index) in textTable[0]" :key="index">{{ label }}</th></tr></thead><tbody><tr v-for="(row, index) in textTable.slice(1)" :key="sheet.rows[index].key"><td v-for="(cell, column) in row" :key="column">{{ cell }}</td></tr></tbody></table>
        <h4>IMPORTANT NOTES</h4><ol><li v-for="note in sheet.notes" :key="note">{{ note }}</li></ol>
      </details>
    </div>
    </div>
  </section>
</template>

<style scoped>
.customer-sheet .sheet-zone-label{display:inline-block;max-width:180px;width:122px;height:auto;min-height:24px;box-sizing:border-box;margin:3px auto;padding:3px 6px;border:1px solid #f58220!important;border-radius:6px;background:#fff0e3;color:#b94f00;font-size:12px;text-align:center;overflow-wrap:anywhere}
.sheet-row-controls{display:flex;align-items:center;justify-content:center;gap:4px;white-space:nowrap}
.customer-sheet .sheet-row-controls button{padding:3px 6px;font-size:12px}
.customer-sheet .sheet-row-controls .sheet-row-handle{cursor:grab;font-size:18px;line-height:18px}
.customer-sheet .sheet-row-controls .sheet-row-handle:active{cursor:grabbing}
.customer-sheet .sheet-row-controls .sheet-delete-row{color:#b42318;border-color:#e8b9b4}
.customer-sheet .sheet-row-controls button:hover:not(:disabled){background:#fff0e3;border-color:#f58220}
.customer-sheet .sheet-row-controls .sheet-delete-row:hover:not(:disabled){background:#fff1f0;border-color:#b42318}
.sheet-row-controls button:focus-visible{outline:2px solid #f58220;outline-offset:2px}
.customer-sheet .sheet-row-dragging td{opacity:.5}
.customer-sheet .sheet-row-drop-target td{box-shadow:inset 0 -3px #f58220;background:#fff7ed}
.sheet-average-row td{background:#fff1df!important;border-top:1px solid #f58220!important;border-bottom:1px solid #f58220!important;color:#c95d00}.sheet-average-row input{color:#c95d00!important;font-weight:700}
.sheet-row-tools{display:flex;align-items:center;gap:6px;flex-wrap:wrap;margin:0;padding:4px 0 0;font-size:12px}.customer-sheet .sheet-row-tools>button,.customer-sheet .sheet-row-action button,.customer-sheet .sheet-hidden-row button{color:#d86b13;border-color:#f58220;white-space:nowrap}.sheet-row-action{position:sticky;right:0;z-index:1;min-width:58px;box-shadow:-2px 0 4px #20253212}.sheet-hidden-rows{margin-top:12px;border:1px solid #dfe5e9;border-radius:6px;overflow:hidden;font-size:12px}.sheet-hidden-heading{display:flex;gap:18px;padding:12px;background:#f1f3f5;flex-wrap:wrap}.sheet-hidden-heading span,.sheet-hidden-row small{color:#72808a}.sheet-hidden-row{display:flex;align-items:center;gap:18px;flex-wrap:wrap;padding:12px;border-top:1px solid #dfe5e9;background:#fff}.sheet-hidden-row>span{flex:1;min-width:130px;overflow-wrap:anywhere}.sheet-hidden-row small{line-height:1.5}.sheet-hidden-row button{margin-left:auto}
.sheet-layout-preference{display:flex;align-items:center;gap:8px;flex-wrap:wrap;margin:6px 0 0;font-size:12px;color:#72808a}.sheet-layout-preference [role="status"]{color:#287a4d}.sheet-layout-preference [role="alert"]{color:#b42318}
.sheet-country-format{display:flex;justify-content:center;margin-top:2px}.customer-sheet .sheet-country-format button{padding:2px 8px;border-color:#f58220;border-radius:0;font-size:12px;font-weight:650}.customer-sheet .sheet-country-format button:first-child{border-radius:6px 0 0 6px}.customer-sheet .sheet-country-format button:last-child{border-left:0;border-radius:0 6px 6px 0}.customer-sheet .sheet-country-format button[aria-pressed="true"]{background:#f58220;color:#fff}.sheet-country-format button:focus-visible{outline:2px solid #924e10;outline-offset:2px}
.sheet-photos{display:flex;align-items:center;gap:10px;flex-wrap:wrap;min-width:0;margin:0 0 0 auto;padding:0;border:0}.sheet-photos label{display:flex;align-items:center;gap:6px;font-size:12px}.sheet-photos span{font-size:12px;color:#64717d;white-space:nowrap}.sheet-photo-cell{min-width:180px}.sheet-photo-grid{display:grid;justify-content:center;gap:10px}.sheet-photo-grid img{width:150px;height:150px;object-fit:contain;background:#fff}.sheet-photo-grid-many{grid-template-columns:repeat(2,100px)}.sheet-photo-grid-many img{width:100px;height:100px}
.customer-sheet{margin:0 12px 10px;color:#202532}.sheet-toolbar{display:flex;align-items:center;margin-bottom:6px}.sheet-toolbar h3{margin:0;font-size:15px}.sheet-actions{display:flex;align-items:center;gap:6px;flex-wrap:wrap;margin-left:auto}.customer-sheet button{padding:4px 8px;border:1px solid #d7dce1;border-radius:6px;background:#fff;color:#243440;font-size:12px;font-weight:650;cursor:pointer}.customer-sheet button.sheet-primary{background:#f58220;border-color:#f58220;color:#fff}.customer-sheet button:disabled{background:#edf0f2;border-color:#e0e4e8;color:#919aa3;cursor:not-allowed}.sheet-editor{padding:6px;background:#fafbfc;border:1px solid #dfe5e9;border-radius:8px}.sheet-metadata{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:8px}.sheet-metadata label{display:grid;gap:2px;min-width:0;font-size:12px;font-weight:650}.customer-sheet input{height:28px;padding:0 5px;box-sizing:border-box;border:1px solid #ccd4db;border-radius:4px;background:#fff;color:#202532;font:inherit}.sheet-metadata input{width:100%;min-width:0}.sheet-editor-scroll,.sheet-image-scroll,.sheet-accessible{overflow-x:auto}.customer-sheet table{width:100%;border-collapse:collapse;font-size:12px}.sheet-editor table{width:100%;min-width:0}.customer-sheet th,.customer-sheet td{padding:3px 5px;border:1px solid #e0e3e6;text-align:center;vertical-align:middle}.customer-sheet th{background:#fff0e3;color:#924e10;font-weight:650}.customer-sheet td{background:#fff}.customer-sheet small{display:block;margin-top:2px;font-weight:400}.customer-sheet .sheet-source{max-width:200px;margin:0 auto;color:#76828c;font-size:10px;line-height:1.2;overflow-wrap:anywhere;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}.sheet-time input{width:136px;font-size:12px}.sheet-time button{display:block;margin:2px auto 0;padding:2px 4px;border:0;color:#a85d16;background:transparent;font-size:10px}.sheet-empty{padding:35px;text-align:center;border:1px dashed #d9e1e6;color:#87939d;font-size:12px}.sheet-image-area{border:1px solid #e0e4e8;background:#f6f7f9}.sheet-image-scroll img{display:block;width:100%;height:auto;min-width:768px}.sheet-pages{display:flex;justify-content:center;align-items:center;gap:15px;padding:10px;font-size:12px}.sheet-message,.sheet-pending{padding:10px 12px;border-radius:5px;background:#f0f7f1;color:#287a4d;font-size:12px;line-height:1.6}.sheet-message.failed,.sheet-pending{background:#fff4e6;color:#a65410}.sheet-accessible{padding:10px;background:#fff;font-size:12px;line-height:1.6}.sheet-accessible summary{cursor:pointer;color:#64727e}.sheet-accessible li{margin:8px 0}.sheet-quantity{position:relative;box-sizing:border-box;min-width:106px;padding:3px 16px!important;border-bottom-color:#f58220!important}.sheet-quantity label{white-space:nowrap}.sheet-quantity input{width:48px;height:24px}.sheet-remove{position:absolute;right:2px;top:0;padding:0 5px!important;border:0!important;background:transparent!important}.sheet-price{white-space:nowrap}.sheet-price input{width:76px;text-align:center}.sheet-price-error{max-width:160px;white-space:normal;color:#b42318;line-height:1.5}.sheet-price input[aria-invalid="true"]{border-color:#b42318!important}.sheet-number{width:32px;text-align:center}.sheet-country{width:122px}.sheet-provider{width:150px}.sheet-processing{width:118px}.sheet-editor td input:not(:focus){border-color:transparent}.sheet-editor td input:hover{border-color:#ccd4db}.customer-sheet input:focus{outline:1px solid #f58220;border-color:#f58220}.sheet-notes-editor{margin-top:5px;font-size:12px}.sheet-notes-editor summary{cursor:pointer;color:#925013}.sheet-notes-editor label{display:flex;gap:12px;margin:10px 0}.sheet-notes-editor textarea{width:100%;min-height:70px;resize:vertical;border:1px solid #ccd4db;padding:8px;font:inherit;line-height:1.6}
.sheet-visibility{display:flex;align-items:center;gap:14px;flex-wrap:wrap;min-width:0;margin:0;padding:0;border:0;font-size:12px}.sheet-visibility label{display:flex;align-items:center;gap:6px;cursor:pointer}.sheet-visibility input{width:16px;height:16px;padding:0;accent-color:#f58220}.sheet-sku{min-width:90px;max-width:180px;overflow-wrap:anywhere}

.sheet-drag{position:absolute;left:3px;top:0;padding:0 5px!important;border:0!important;background:transparent!important;color:#b46726!important;cursor:grab!important;font-size:17px!important;line-height:20px}.sheet-drag:active{cursor:grabbing!important}.sheet-drag:focus-visible{outline:2px solid #f58220;outline-offset:1px}.sheet-drop-target{box-shadow:inset 3px 0 #f58220;background:#ffe1c6}.customer-sheet .sheet-add-button{color:#d86b13;border-color:#f58220}.sheet-group{position:relative;white-space:nowrap}.customer-sheet .sheet-group-drag{display:inline-flex;align-items:center;gap:8px;border:0;background:transparent;color:#924e10;cursor:grab;padding:0 2px;font-size:17px;line-height:18px}.sheet-group-drag span{font-size:12px}.sheet-group-drag:active{cursor:grabbing}.sheet-group-drag:focus-visible{outline:2px solid #f58220;outline-offset:2px}.sheet-price-group{border:1px solid #f58220!important}

.sheet-controls{position:sticky;top:var(--quote-sheet-sticky-top,0px);z-index:10;padding:4px 0;background:#fafbfc;border-bottom:1px solid #dfe5e9}
.sheet-display-tools{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap;padding-bottom:4px;border-bottom:1px solid #e5e9ed}
.customer-sheet .sheet-photo-button{min-width:142px;min-height:44px;padding:8px 22px;border-color:#7c3aed;background:#7c3aed;color:#fff;font-size:16px;font-weight:750}
.customer-sheet .sheet-photo-button:hover:not(:disabled){background:#6d28d9;border-color:#6d28d9}
.customer-sheet .sheet-photo-button:disabled{background:#ede9fe;border-color:#ddd6fe;color:#8b7dad}
.sheet-photo-button:focus-visible{outline:2px solid #5b21b6;outline-offset:2px}
.sheet-edit-help{margin:8px 0;color:#72808a;font-size:12px;line-height:1.5}
.sheet-price>span{margin-right:3px}
.sheet-editor-scroll td input{height:24px}
@media(min-width:1200px){.sheet-metadata label{grid-template-columns:auto minmax(0,1fr);align-items:center;gap:6px;white-space:nowrap}}
@media(max-width:1100px){.sheet-metadata{grid-template-columns:repeat(2,minmax(0,1fr))}.sheet-row-tools .sheet-actions{flex:1 1 100%;justify-content:flex-end}}
@media(max-width:850px){.customer-sheet{margin-left:12px;margin-right:12px}.sheet-editor{padding:8px}.sheet-pages{gap:8px}.sheet-controls{position:static}.sheet-photos{margin-left:0}.sheet-actions{margin-left:0}.sheet-row-tools .sheet-actions{justify-content:flex-start}}
@media(max-width:480px){.sheet-metadata{grid-template-columns:minmax(0,1fr)}.sheet-visibility{gap:10px}}
</style>
