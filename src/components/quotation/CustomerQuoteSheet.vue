<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import {
  buildCustomerQuoteSheet, CUSTOMER_QUOTE_NOTES, formatShippingTime, newQuoteSheetEdits,
  quoteSheetRowKey, quoteSheetUsd, reconcileQuoteSheetEdits, quoteSheetProviderKey, quoteSheetProviderName,
  MAX_QUOTE_SHEET_COLUMNS, validQuoteSheetQuantity, QUOTE_SHEET_OPTIONAL_COLUMNS, quoteSheetColumns, quoteSheetCell,
  type QuoteSheetOptionalColumn,
  type QuoteSheetCountry, type QuoteSheetSourceRow, type QuoteSheetPriceCalculator, type QuoteSheetRowEdits,
} from '@/data/customerQuoteSheet'
import { copyQuoteSheetImage, renderCustomerQuoteSheet, type QuoteSheetImage } from '@/services/customerQuoteSheetRenderer'
import { copyQuoteSheetData } from '@/services/customerQuoteSheetClipboard'
import QuoteBackToTop from './QuoteBackToTop.vue'
import type { CustomerPriceSnapshot, CapturedSheetPrices } from '@/data/customerQuotePrices'
import { loadQuotePhotos, releaseQuotePhotos, type QuoteLocalPhoto } from '@/services/quoteLocalPhotos'

const props = defineProps<{
  rows: QuoteSheetSourceRow[]; countries: QuoteSheetCountry[]; salesperson: string
  contextKey: string; customQuantity: number; bundle: boolean; sourcePending: boolean
  calculatePrice?: QuoteSheetPriceCalculator
  resetKey?: string
  initialQuote?: CustomerPriceSnapshot
  skus?: string[]
}>()
const edits = ref(newQuoteSheetEdits(props.salesperson))
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
const photoInput = ref<HTMLInputElement>()
const photos = shallowRef<QuoteLocalPhoto[]>([])
const showPhotos = ref(true)
const loadingPhotos = ref(false)
const photoError = ref('')
let photoGeneration = 0
const hasPhotos = computed(() => showPhotos.value && photos.value.length > 0)
function clearPhotos() {
  photoGeneration++
  releaseQuotePhotos(photos.value)
  photos.value = []
  loadingPhotos.value = false
  photoError.value = ''
  showPhotos.value = true
  invalidate()
}
async function choosePhotos(event: Event) {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  input.value = '' // Allow selecting the same file again.
  if (!files.length || copying.value || disposed) return
  const token = ++photoGeneration
  loadingPhotos.value = true
  photoError.value = ''
  try {
    const loaded = await loadQuotePhotos(files)
    if (disposed || token !== photoGeneration) { releaseQuotePhotos(loaded); return }
    releaseQuotePhotos(photos.value)
    photos.value = loaded
    showPhotos.value = true
    invalidate()
  } catch (error) {
    if (!disposed && token === photoGeneration) photoError.value = error instanceof Error ? error.message : '图片读取失败，请重新选择'
  } finally {
    if (!disposed && token === photoGeneration) loadingPhotos.value = false
  }
}
// Keep local photos out of sheet edits and all persisted snapshots. Product/account/record resets clear them.
watch(() => JSON.stringify([props.skus, props.resetKey ?? props.contextKey, props.bundle]), clearPhotos, { flush: 'sync' })
watch(showPhotos, invalidate, { flush: 'sync' })

const sheet = computed(() => buildCustomerQuoteSheet({
  rows: props.rows, countries: props.countries, edits: edits.value, skus: props.skus,
  customQuantity: props.customQuantity, bundle: props.bundle,
  quantities: quantities.value, calculatePrice: props.sourcePending ? undefined : props.calculatePrice,
  legacyCustomIndex: columns.value.findIndex(column => column.legacyCustom),
}))
// Tracks calculator dependencies too (tax, weight, exchange rate), even if the original four prices are unchanged.
watch(sheet, invalidate, { flush: 'sync' })
const currentImage = computed(() => images.value[activeImage.value])
const missingProviders = computed(() => !columnVisible('provider') ? [] : [...new Map(props.rows
  .filter(row => !quoteSheetProviderName(row.carrier))
  .map(row => [quoteSheetProviderKey(row.carrier), { key: quoteSheetProviderKey(row.carrier), name: row.carrier || '未命名' }])).values()])
const canCopy = computed(() => Boolean(currentImage.value) && !editing.value && !props.sourcePending && !rendering.value && !copying.value && !loadingPhotos.value)

const visibleColumns = computed(() => quoteSheetColumns(sheet.value.hiddenColumns))
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
    edits.value = newQuoteSheetEdits(props.salesperson)
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
  if (edits.value.agent === previous) edits.value.agent = value
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
function updatePrice(key: string, quantity: number, event: Event) {
  if (quantities.value.filter(value => value === quantity).length !== 1) return
  const fields = rowEdits(key)
  fields.prices ||= {}
  fields.prices[String(quantity)] = (event.target as HTMLInputElement).value
}
function clearPriceEdits() {
  Object.values(edits.value.fields || {}).forEach(fields => { delete fields.prices })
}
watch(() => props.rows.map(row => [quoteSheetRowKey(row), row.quote1, row.quote2, row.quote3, row.quoteCustom]).sort((a, b) => String(a[0]).localeCompare(String(b[0]))).map(row => JSON.stringify(row)).join('|'), () => { clearPriceEdits(); loadSavedPrices() })
watch(() => props.sourcePending, value => { if (value) clearPriceEdits() })
async function addColumn() {
  if (copying.value || columns.value.length >= MAX_QUOTE_SHEET_COLUMNS) return
  columns.value.push({ id: ++columnId, quantity: '', legacyCustom: false })
  await nextTick()
  const inputs = editorScroll.value?.querySelectorAll<HTMLInputElement>('.sheet-quantity input')
  inputs?.[inputs.length - 1]?.focus()
  if (editorScroll.value) editorScroll.value.scrollLeft = editorScroll.value.scrollWidth
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
  draggedColumn.value = id
  if (event.dataTransfer) { event.dataTransfer.effectAllowed = 'move'; event.dataTransfer.setData('text/plain', String(id)) }
}
function endColumnDrag() { draggedColumn.value = null; dropTarget.value = null }
function dropColumn(index: number) {
  if (draggedColumn.value != null) moveColumn(columns.value.findIndex(column => column.id === draggedColumn.value), index)
  endColumnDrag()
}
async function preview() {
  if (disposed || copying.value || loadingPhotos.value || props.sourcePending || rendering.value || !props.rows.length) return
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
  const system = buildCustomerQuoteSheet({ rows:props.rows, countries:props.countries, edits:newQuoteSheetEdits(props.salesperson),
    customQuantity:props.customQuantity, bundle:props.bundle, quantities:quantities.value, calculatePrice:props.calculatePrice,
    legacyCustomIndex:columns.value.findIndex(column=>column.legacyCustom) })
  if (system.priceIssues?.length) throw new Error(system.priceIssues.join('；'))
  return { quantities:[...quantities.value], rows:sheet.value.rows.map(row=>({ key:row.key, prices:[...row.prices], systemPrices:[...system.rows.find(original=>original.key===row.key)!.prices] })) }
}
defineExpose({ preview, copyData, invalidate, copying, capturePrices })
onBeforeUnmount(() => {
  disposed = true
  photoGeneration++
  releaseQuotePhotos(photos.value)
  generation++
  releaseImages()
})
</script>

<template>
  <section class="customer-sheet" aria-labelledby="customer-sheet-title">
    <QuoteBackToTop />
    <div class="sheet-toolbar">
      <div><h3 id="customer-sheet-title">客户报价单</h3><p>点击内容可修改；右侧新增列，填写数量后自动带出对应价格</p></div>
      <div class="sheet-actions">
        <button v-if="!editing" type="button" :disabled="copying" @click="invalidate">编辑报价单</button>
        <button v-if="editing" class="sheet-primary" type="button" :disabled="sourcePending || !rows.length || rendering || copying || loadingPhotos" @click="preview">{{ rendering ? '正在生成预览…' : '预览报价单' }}</button>
        <button type="button" class="sheet-primary" :disabled="!canCopy" @click="copyCurrent">{{ copying ? '正在复制…' : images.length > 1 ? '复制当前图片' : '复制报价图片' }}</button>
        <button type="button" :disabled="sourcePending || !rows.length || rendering || copying" @click="copyData">复制报价数据</button>
      </div>
    </div>
    <p v-if="message" class="sheet-message" :class="{ failed }" :role="failed ? 'alert' : 'status'">{{ message }}</p>
    <p class="sheet-local-note">图片仅在当前页面临时生成，不上传、不存储；保存报价记录时带入客户价格，系统原价与物流资料不变。</p>
    <fieldset v-if="rows.length" class="sheet-visibility" :disabled="copying"><legend>显示列</legend>
      <label v-for="column in QUOTE_SHEET_OPTIONAL_COLUMNS" :key="column.key"><input type="checkbox" :checked="columnVisible(column.key)" :aria-label="`显示${column.name}列`" @change="toggleColumn(column.key)">{{ column.name }}</label>
      <span>取消勾选后，报价图片和复制数据中将隐藏该列；仅当前报价单有效。</span>
    </fieldset>
    <fieldset v-if="rows.length" class="sheet-photos" :disabled="copying || rendering || loadingPhotos">
      <legend>临时商品图</legend>
      <input ref="photoInput" type="file" accept="image/jpeg,image/png,image/webp" multiple hidden aria-label="选择临时商品图片" @change="choosePhotos">
      <button type="button" @click="photoInput?.click()">{{ loadingPhotos ? '正在读取图片…' : photos.length ? '替换图片' : '选择图片' }}</button>
      <button v-if="photos.length" type="button" @click="clearPhotos">移除图片</button>
      <label v-if="photos.length"><input v-model="showPhotos" type="checkbox" aria-label="显示商品图片列">显示图片</label>
      <span>图片仅用于本次展示，不上传保存；刷新、离开页面或更换商品后清除。支持 JPG / PNG / WebP，最多 4 张，每张不超过 10MB。</span>
    </fieldset>
    <p v-if="photoError" class="sheet-message failed" role="alert">{{ photoError }}</p>
    <p v-if="sourcePending" class="sheet-pending" role="status">当前报价数据尚未就绪，请完成物流计算后预览。</p>
    <p v-if="!rows.length" class="sheet-empty">请先在上方报价矩阵中选择需要报价的国家与渠道</p>
    <div v-else-if="editing" class="sheet-editor">
      <div class="sheet-metadata">
        <label>报价单标题<input :value="edits.title ?? 'JerryFulfillment Quote Sheet'" aria-label="报价单标题" maxlength="80" :disabled="copying" @input="edits.title = ($event.target as HTMLInputElement).value"></label>
        <label>By Agent · 署名<input v-model="edits.agent" aria-label="报价单署名" autocomplete="off" maxlength="40" :disabled="copying"></label>
        <label>Date · 日期<input v-model="edits.date" aria-label="报价单日期" type="date" min="1000-01-01" max="9999-12-31" :disabled="copying"></label>
      </div>
      <div v-if="missingProviders.length" class="sheet-provider-editor">
        <p class="sheet-edit-help">英文名补填：以下物流商尚无英文显示名，请填写后预览或复制。同一物流商的全部渠道共用此名称，仅当前页面有效。</p>
        <div class="sheet-metadata"><label v-for="provider in missingProviders" :key="provider.key">{{ provider.name }} · English name<input :value="edits.providerNames?.[provider.key] || ''" :aria-label="`${provider.name}英文名`" autocomplete="off" placeholder="Enter English name" maxlength="80" :disabled="copying" @input="updateProviderName(provider.key, $event)"></label></div>
      </div>
      <p class="sheet-edit-help">数量填写正整数，最多 10 个价格列；拖动列头手柄可排序，内容可直接修改，空价格显示 —。</p>
      <p v-if="!calculatePrice" class="sheet-edit-help">历史记录仅带出已保存数量的价格；新增数量没有历史价格时，请手动填写。</p>
      <span class="sheet-column-count">价格列 {{ columns.length }} / 10</span>
      <div ref="editorScroll" class="sheet-editor-scroll">
        <table>
          <thead><tr><th>No.</th><th v-if="hasPhotos">Product</th><th>SKU</th><th v-if="columnVisible('country')">Country</th><th v-if="columnVisible('provider')">Logistics Provider</th><th v-if="columnVisible('shippingTime')">Shipping Time</th><th v-if="columnVisible('processingTime')">Processing Time</th>
            <th v-for="(column, index) in columns" :key="column.id" class="sheet-quantity" :class="{ 'sheet-drop-target': dropTarget === column.id && draggedColumn !== column.id }" @dragover.prevent="dropTarget = draggedColumn == null ? null : column.id" @drop.prevent="dropColumn(index)">
              <button type="button" class="sheet-drag" :draggable="!copying" :data-column-handle="column.id" :aria-label="`第 ${index + 1} 个价格列排序，左右键移动`" title="拖动排序，或聚焦后按左右方向键" :disabled="copying" @dragstart="startColumnDrag(column.id, $event)" @dragend="endColumnDrag" @keydown.left.prevent="moveColumn(index, index - 1)" @keydown.right.prevent="moveColumn(index, index + 1)">⠿</button>
              <button v-if="columns.length > 1" type="button" class="sheet-remove" :aria-label="`删除第 ${index + 1} 个价格列`" :disabled="copying" @click="removeColumn(index)">×</button>
              <label><input :value="column.quantity" type="number" min="1" step="1" :aria-label="`第 ${index + 1} 个价格列数量`" :placeholder="column.legacyCustom ? 'Custom' : '数量'" :disabled="copying" @input="updateQuantity(index, $event)"> {{ bundle ? (Number(column.quantity) === 1 ? 'set' : 'sets') : (Number(column.quantity) === 1 ? 'pc' : 'pcs') }}</label><small>USD</small>
            </th>
            <th class="sheet-add"><button type="button" :disabled="copying || columns.length >= MAX_QUOTE_SHEET_COLUMNS" @click="addColumn">＋ 新增列</button></th>
          </tr></thead>
          <tbody>
            <tr v-for="(row, index) in sheet.rows" :key="row.key">
              <td><input class="sheet-number" :value="edits.fields?.[row.key]?.number ?? row.number" :aria-label="`第 ${index + 1} 行序号`" :disabled="copying" @input="updateField(row.key, 'number', $event)"></td>
              <td v-if="hasPhotos && index === 0" :rowspan="sheet.rows.length" class="sheet-photo-cell"><div class="sheet-photo-grid" :class="{ 'sheet-photo-grid-many': photos.length > 2 }"><img v-for="(photo, photoIndex) in photos" :key="photo.url" :src="photo.url" :alt="`临时商品图 ${photoIndex + 1}`"></div></td>
              <td class="sheet-sku">{{ row.sku }}</td>
              <td v-if="columnVisible('country')"><input class="sheet-country" :value="edits.fields?.[row.key]?.country ?? row.country" :aria-label="`第 ${index + 1} 行国家`" maxlength="80" :disabled="copying" @input="updateField(row.key, 'country', $event)"></td>
              <td v-if="columnVisible('provider')"><input class="sheet-provider" :value="edits.fields?.[row.key]?.provider ?? row.provider" :aria-label="`第 ${index + 1} 行物流商`" maxlength="80" :disabled="copying" @input="updateField(row.key, 'provider', $event)"><small class="sheet-source">{{ row.sourceDescription }}</small></td>
              <td v-if="columnVisible('shippingTime')" class="sheet-time"><input :value="edits.shippingTimes[row.key] ?? (formatShippingTime(rows[index].eta) === '—' ? '' : formatShippingTime(rows[index].eta))" :aria-label="`第 ${index + 1} 行运输时效`" placeholder="例如 6-12 days" maxlength="80" :disabled="copying" @input="updateShippingTime(rows[index], $event)"><button v-if="row.key in edits.shippingTimes" type="button" :disabled="copying" @click="restoreShippingTime(rows[index])">恢复渠道时效</button></td>
              <td v-if="columnVisible('processingTime')"><input class="sheet-processing" :value="edits.fields?.[row.key]?.processingTime ?? row.processingTime" :aria-label="`第 ${index + 1} 行处理时间`" maxlength="80" :disabled="copying" @input="updateField(row.key, 'processingTime', $event)"></td>
              <td v-for="(price, priceIndex) in row.prices" :key="columns[priceIndex].id" class="sheet-price"><span>$</span><input :value="edits.fields?.[row.key]?.prices?.[String(quantities[priceIndex])] ?? (price == null ? '' : price.toFixed(2))" :aria-label="`第 ${index + 1} 行第 ${priceIndex + 1} 列美元价格`" inputmode="decimal" placeholder="—" maxlength="15" :disabled="copying || sourcePending || quantities.filter(value => value === quantities[priceIndex]).length !== 1 || (!validQuoteSheetQuantity(quantities[priceIndex]) && !columns[priceIndex].legacyCustom)" @input="updatePrice(row.key, quantities[priceIndex], $event)"></td>
              <td class="sheet-add"></td>
            </tr>
          </tbody>
        </table>
      </div>
      <p v-if="sheet.tableIssues?.length" class="sheet-pending" role="status">{{ sheet.tableIssues.join('；') }}</p>
      <details class="sheet-notes-editor"><summary>报价说明 · 点击编辑</summary><label v-for="(note, index) in (edits.notes ?? CUSTOMER_QUOTE_NOTES)" :key="index">{{ index + 1 }}<textarea :value="note" :aria-label="`第 ${index + 1} 条报价说明`" maxlength="3000" :disabled="copying" @input="updateNote(index, $event)"></textarea></label></details>
    </div>
    <div v-if="!editing && currentImage" class="sheet-image-area">
      <div v-if="images.length > 1" class="sheet-pages">
        <button type="button" :disabled="activeImage === 0 || copying" @click="activeImage--">上一张</button>
        <span>第 {{ activeImage + 1 }} / {{ images.length }} 张 · 第 {{ currentImage.firstRow }}–{{ currentImage.lastRow }} 条渠道</span>
        <button type="button" :disabled="activeImage === images.length - 1 || copying" @click="activeImage++">下一张</button>
      </div>
      <div class="sheet-image-scroll"><img :src="currentImage.url" :width="currentImage.width" :height="currentImage.height" alt="JerryFulfillment Quote Sheet 客户报价图片预览" draggable="false"></div>
      <details class="sheet-accessible"><summary>查看报价单文字内容</summary>
        <p>{{ sheet.title }} — By Agent: {{ sheet.agent }} — Date: {{ sheet.date }}</p>
        <table><thead><tr><th v-for="column in visibleColumns" :key="column.key">{{ column.label }}</th><th v-for="(label, index) in sheet.quantityLabels" :key="index">{{ label }} (USD)</th></tr></thead><tbody><tr v-for="row in sheet.rows" :key="row.key"><td v-for="column in visibleColumns" :key="column.key">{{ quoteSheetCell(row, column.key) }}</td><td v-for="(price, index) in row.prices" :key="index">{{ quoteSheetUsd(price) }}</td></tr></tbody></table>
        <h4>IMPORTANT NOTES</h4><ol><li v-for="note in sheet.notes" :key="note">{{ note }}</li></ol>
      </details>
    </div>
  </section>
</template>

<style scoped>
.sheet-photos{display:flex;align-items:center;gap:12px;flex-wrap:wrap;margin:12px 0;padding:12px;border:1px solid #dfe5e9;border-radius:6px}.sheet-photos legend{font-size:12px;font-weight:650}.sheet-photos label{display:flex;align-items:center;gap:6px;font-size:12px}.sheet-photos span{flex:1;min-width:220px;font-size:12px;color:#72808a;line-height:1.6}.sheet-photo-cell{min-width:180px}.sheet-photo-grid{display:grid;justify-content:center;gap:10px}.sheet-photo-grid img{width:150px;height:150px;object-fit:contain;background:#fff}.sheet-photo-grid-many{grid-template-columns:repeat(2,100px)}.sheet-photo-grid-many img{width:100px;height:100px}
.customer-sheet{margin:0 22px 18px;color:#202532}.sheet-toolbar{display:flex;align-items:center;justify-content:space-between;gap:14px}.sheet-toolbar h3{margin:0;font-size:15px}.sheet-toolbar p,.sheet-local-note,.sheet-edit-help{color:#72808a;font-size:12px;line-height:1.6}.sheet-toolbar p{margin:5px 0}.sheet-local-note{margin:8px 0 14px}.sheet-actions{display:flex;gap:8px;flex-wrap:wrap}.customer-sheet button{padding:9px 13px;border:1px solid #d7dce1;border-radius:6px;background:#fff;color:#243440;font-size:12px;font-weight:650;cursor:pointer}.customer-sheet button.sheet-primary{background:#f58220;border-color:#f58220;color:#fff}.customer-sheet button:disabled{background:#edf0f2;border-color:#e0e4e8;color:#919aa3;cursor:not-allowed}.sheet-editor{padding:16px;background:#fafbfc;border:1px solid #dfe5e9;border-radius:8px}.sheet-metadata{display:flex;gap:18px;flex-wrap:wrap}.sheet-metadata label{display:grid;gap:6px;font-size:12px;font-weight:650}.customer-sheet input{height:36px;padding:0 9px;box-sizing:border-box;border:1px solid #ccd4db;border-radius:4px;background:#fff;color:#202532;font:inherit}.sheet-metadata input{min-width:205px}.sheet-editor-scroll,.sheet-image-scroll,.sheet-accessible{overflow-x:auto}.customer-sheet table{width:100%;border-collapse:collapse;font-size:12px}.sheet-editor table{width:max-content;min-width:0}.customer-sheet th,.customer-sheet td{padding:10px 9px;border:1px solid #e0e3e6;text-align:center;vertical-align:middle}.customer-sheet th{background:#fff0e3;color:#924e10;font-weight:650}.customer-sheet td{background:#fff}.customer-sheet small{display:block;margin-top:4px;font-weight:400}.sheet-source{max-width:230px;color:#76828c;font-size:10px;line-height:1.5}.sheet-time input{width:170px;font-size:12px}.sheet-time button{display:block;margin:5px auto 0;padding:2px 4px;border:0;color:#a85d16;background:transparent;font-size:10px}.sheet-empty{padding:35px;text-align:center;border:1px dashed #d9e1e6;color:#87939d;font-size:12px}.sheet-image-area{border:1px solid #e0e4e8;background:#f6f7f9}.sheet-image-scroll img{display:block;width:100%;height:auto;min-width:768px}.sheet-pages{display:flex;justify-content:center;align-items:center;gap:15px;padding:10px;font-size:12px}.sheet-message,.sheet-pending{padding:10px 12px;border-radius:5px;background:#f0f7f1;color:#287a4d;font-size:12px;line-height:1.6}.sheet-message.failed,.sheet-pending{background:#fff4e6;color:#a65410}.sheet-accessible{padding:10px;background:#fff;font-size:12px;line-height:1.6}.sheet-accessible summary{cursor:pointer;color:#64727e}.sheet-accessible li{margin:8px 0}@media(max-width:850px){.sheet-toolbar{align-items:flex-start;flex-direction:column}.customer-sheet{margin-left:12px;margin-right:12px}.sheet-editor{padding:12px}.sheet-pages{gap:8px}.sheet-metadata{width:100%}.sheet-metadata label{flex:1}}
.sheet-column-count{display:block;text-align:right;font-size:12px;color:#72808a;margin:8px 0}.sheet-quantity{position:relative;min-width:112px;padding-top:22px!important}.sheet-quantity input{width:66px}.sheet-remove{position:absolute;right:2px;top:0;padding:0 5px!important;border:0!important;background:transparent!important}.sheet-add{position:sticky;right:0;z-index:3;min-width:105px;background:#fafbfc!important;border-left:1px dashed #d7dce1!important}.sheet-add button{white-space:nowrap;color:#ee791a;border-color:#ee791a}.sheet-price{white-space:nowrap}.sheet-price input{width:90px;text-align:center}.sheet-number{width:45px}.sheet-country{width:190px}.sheet-provider{width:160px}.sheet-processing{width:130px}.sheet-editor td input:not(:focus){border-color:transparent}.sheet-editor td input:hover{border-color:#ccd4db}.customer-sheet input:focus{outline:1px solid #f58220;border-color:#f58220}.sheet-notes-editor{margin-top:14px;font-size:12px}.sheet-notes-editor summary{cursor:pointer;color:#925013}.sheet-notes-editor label{display:flex;gap:12px;margin:10px 0}.sheet-notes-editor textarea{width:100%;min-height:70px;resize:vertical;border:1px solid #ccd4db;padding:8px;font:inherit;line-height:1.6}
.sheet-visibility{display:flex;align-items:center;gap:16px;flex-wrap:wrap;margin:12px 0;padding:12px 14px;border:1px solid #e0e4e8;border-radius:6px;font-size:12px}.sheet-visibility legend{padding:0 5px;font-weight:650}.sheet-visibility label{display:flex;align-items:center;gap:6px;cursor:pointer}.sheet-visibility input{width:16px;height:16px;padding:0;accent-color:#f58220}.sheet-visibility span{color:#72808a}.sheet-sku{min-width:160px;max-width:240px;overflow-wrap:anywhere}
.sheet-editor table th:nth-child(-n+2),.sheet-editor table td:nth-child(-n+2){position:sticky;z-index:1}.sheet-editor table th:nth-child(-n+2){z-index:2}.sheet-editor table th:nth-child(1),.sheet-editor table td:nth-child(1){left:0;min-width:45px}.sheet-editor table th:nth-child(2),.sheet-editor table td:nth-child(2){left:64px;min-width:160px;box-shadow:2px 0 3px #20253212}

.sheet-drag{position:absolute;left:3px;top:0;padding:0 5px!important;border:0!important;background:transparent!important;color:#b46726!important;cursor:grab!important;font-size:17px!important;line-height:20px}.sheet-drag:active{cursor:grabbing!important}.sheet-drag:focus-visible{outline:2px solid #f58220;outline-offset:1px}.sheet-quantity.sheet-drop-target{box-shadow:inset 3px 0 #f58220;background:#ffe1c6}
</style>
