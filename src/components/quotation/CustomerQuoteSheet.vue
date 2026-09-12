<script setup lang="ts">
import { computed, onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import {
  buildCustomerQuoteSheet, CUSTOMER_QUOTE_NOTES, formatShippingTime, newQuoteSheetEdits,
  quoteSheetRowKey, quoteSheetUsd, reconcileQuoteSheetEdits,
  type QuoteSheetCountry, type QuoteSheetSourceRow,
} from '@/data/customerQuoteSheet'
import { copyQuoteSheetImage, renderCustomerQuoteSheet, type QuoteSheetImage } from '@/services/customerQuoteSheetRenderer'
import { copyQuoteSheetData } from '@/services/customerQuoteSheetClipboard'

const props = defineProps<{
  rows: QuoteSheetSourceRow[]; countries: QuoteSheetCountry[]; salesperson: string
  contextKey: string; customQuantity: number; bundle: boolean; sourcePending: boolean
}>()
const edits = ref(newQuoteSheetEdits(props.salesperson))
const editing = ref(true)
const rendering = ref(false)
const copying = ref(false)
const message = ref('')
const failed = ref(false)
const images = shallowRef<Array<QuoteSheetImage & { url: string }>>([])
const activeImage = ref(0)
let generation = 0
let previousContext = props.contextKey
let disposed = false

const sheet = computed(() => buildCustomerQuoteSheet({
  rows: props.rows, countries: props.countries, edits: edits.value,
  customQuantity: props.customQuantity, bundle: props.bundle,
}))
const currentImage = computed(() => images.value[activeImage.value])
const canCopy = computed(() => Boolean(currentImage.value) && !editing.value && !props.sourcePending && !rendering.value && !copying.value)

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
watch(() => [props.contextKey, props.rows, props.countries, props.customQuantity, props.bundle, props.sourcePending], () => {
  if (props.contextKey !== previousContext) {
    edits.value = newQuoteSheetEdits(props.salesperson)
    previousContext = props.contextKey
  } else {
    edits.value = reconcileQuoteSheetEdits(edits.value, props.rows)
  }
  invalidate()
}, { deep: true, flush: 'sync' })
watch(edits, invalidate, { deep: true, flush: 'sync' })
watch(() => props.salesperson, (value, previous) => {
  if (edits.value.agent === previous) edits.value.agent = value
})

function updateShippingTime(row: QuoteSheetSourceRow, event: Event) {
  edits.value.shippingTimes[quoteSheetRowKey(row)] = (event.target as HTMLInputElement).value
}
function restoreShippingTime(row: QuoteSheetSourceRow) {
  delete edits.value.shippingTimes[quoteSheetRowKey(row)]
}
async function preview() {
  if (props.sourcePending || rendering.value || !props.rows.length) return
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
    const result = await renderCustomerQuoteSheet(snapshot, () => disposed || generation !== token)
    if (disposed || token !== generation) return
    images.value = result.map(image => ({ ...image, url: URL.createObjectURL(image.blob) }))
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
  if (props.sourcePending || copying.value || rendering.value) return
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
defineExpose({ preview, copyData, invalidate, copying })
onBeforeUnmount(() => {
  disposed = true
  generation++
  releaseImages()
})
</script>

<template>
  <section class="customer-sheet" aria-labelledby="customer-sheet-title">
    <div class="sheet-toolbar">
      <div><h3 id="customer-sheet-title">客户报价单</h3><p>编辑署名、日期和运输时效后预览，再复制图片发给客户</p></div>
      <div class="sheet-actions">
        <button v-if="!editing" type="button" :disabled="copying" @click="invalidate">编辑报价单</button>
        <button v-if="editing" class="sheet-primary" type="button" :disabled="sourcePending || !rows.length || rendering || copying" @click="preview">{{ rendering ? '正在生成预览…' : '预览报价单' }}</button>
        <button type="button" class="sheet-primary" :disabled="!canCopy" @click="copyCurrent">{{ copying ? '正在复制…' : images.length > 1 ? '复制当前图片' : '复制报价图片' }}</button>
        <button type="button" :disabled="sourcePending || !rows.length || rendering || copying" @click="copyData">复制报价数据</button>
      </div>
    </div>
    <p class="sheet-local-note">图片仅在当前页面临时生成，不上传、不存储；手动修改不影响系统原始报价和物流资料。</p>
    <p v-if="sourcePending" class="sheet-pending" role="status">当前报价数据尚未就绪，请完成物流计算后预览。</p>
    <p v-if="!rows.length" class="sheet-empty">请先在上方报价矩阵中选择需要报价的国家与渠道</p>
    <div v-else-if="editing" class="sheet-editor">
      <div class="sheet-metadata">
        <label>By Agent · 署名<input v-model="edits.agent" aria-label="报价单署名" autocomplete="off" maxlength="40" :disabled="copying"></label>
        <label>Date · 日期<input v-model="edits.date" aria-label="报价单日期" type="date" min="1000-01-01" max="9999-12-31" :disabled="copying"></label>
      </div>
      <p class="sheet-edit-help">仅署名、日期和 Shipping Time 可编辑，美元价格直接使用系统结果。缺失时效可填写 6-12 days；留空显示 —。</p>
      <div class="sheet-editor-scroll">
        <table>
          <thead><tr><th>No.</th><th>Country</th><th>Logistics Provider</th><th>Shipping Time</th><th>Processing Time</th><th v-for="(label, index) in sheet.quantityLabels" :key="index">{{ label }}<small>USD</small></th></tr></thead>
          <tbody>
            <tr v-for="(row, index) in sheet.rows" :key="row.key">
              <td>{{ row.number }}</td><td>{{ row.country }}</td>
              <td><b>{{ row.provider }}</b><small class="sheet-source">{{ row.sourceDescription }}</small></td>
              <td class="sheet-time"><input :value="edits.shippingTimes[row.key] ?? (formatShippingTime(rows[index].eta) === '—' ? '' : formatShippingTime(rows[index].eta))" :aria-label="`第 ${row.number} 行运输时效`" placeholder="例如 6-12 days" maxlength="80" :disabled="copying" @input="updateShippingTime(rows[index], $event)"><button v-if="row.key in edits.shippingTimes" type="button" :disabled="copying" @click="restoreShippingTime(rows[index])">恢复渠道时效</button></td>
              <td>1-2 days</td><td v-for="(price, priceIndex) in row.prices" :key="priceIndex">{{ quoteSheetUsd(price) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
    <div v-if="!editing && currentImage" class="sheet-image-area">
      <div v-if="images.length > 1" class="sheet-pages">
        <button type="button" :disabled="activeImage === 0 || copying" @click="activeImage--">上一张</button>
        <span>第 {{ activeImage + 1 }} / {{ images.length }} 张 · 第 {{ currentImage.firstRow }}–{{ currentImage.lastRow }} 条渠道</span>
        <button type="button" :disabled="activeImage === images.length - 1 || copying" @click="activeImage++">下一张</button>
      </div>
      <div class="sheet-image-scroll"><img :src="currentImage.url" :width="currentImage.width" :height="currentImage.height" alt="JerryFulfillment Quote Sheet 客户报价图片预览" draggable="false"></div>
      <details class="sheet-accessible"><summary>查看报价单文字内容</summary>
        <p>JerryFulfillment Quote Sheet — By Agent: {{ sheet.agent }} — Date: {{ sheet.date }}</p>
        <table><thead><tr><th>No.</th><th>Country</th><th>Logistics Provider</th><th>Shipping Time</th><th>Processing Time</th><th v-for="(label, index) in sheet.quantityLabels" :key="index">{{ label }} (USD)</th></tr></thead><tbody><tr v-for="row in sheet.rows" :key="row.key"><td>{{ row.number }}</td><td>{{ row.country }}</td><td>{{ row.provider }}</td><td>{{ row.shippingTime }}</td><td>1-2 days</td><td v-for="(price, index) in row.prices" :key="index">{{ quoteSheetUsd(price) }}</td></tr></tbody></table>
        <h4>IMPORTANT NOTES</h4><ol><li v-for="note in CUSTOMER_QUOTE_NOTES" :key="note">{{ note }}</li></ol>
      </details>
    </div>
    <p v-if="message" class="sheet-message" :class="{ failed }" role="status">{{ message }}</p>
  </section>
</template>

<style scoped>
.customer-sheet{margin:0 22px 18px;color:#202532}.sheet-toolbar{display:flex;align-items:center;justify-content:space-between;gap:14px}.sheet-toolbar h3{margin:0;font-size:15px}.sheet-toolbar p,.sheet-local-note,.sheet-edit-help{color:#72808a;font-size:12px;line-height:1.6}.sheet-toolbar p{margin:5px 0}.sheet-local-note{margin:8px 0 14px}.sheet-actions{display:flex;gap:8px;flex-wrap:wrap}.customer-sheet button{padding:9px 13px;border:1px solid #d7dce1;border-radius:6px;background:#fff;color:#243440;font-size:12px;font-weight:650;cursor:pointer}.customer-sheet button.sheet-primary{background:#f58220;border-color:#f58220;color:#fff}.customer-sheet button:disabled{background:#edf0f2;border-color:#e0e4e8;color:#919aa3;cursor:not-allowed}.sheet-editor{padding:16px;background:#fafbfc;border:1px solid #dfe5e9;border-radius:8px}.sheet-metadata{display:flex;gap:18px;flex-wrap:wrap}.sheet-metadata label{display:grid;gap:6px;font-size:12px;font-weight:650}.customer-sheet input{height:36px;padding:0 9px;box-sizing:border-box;border:1px solid #ccd4db;border-radius:4px;background:#fff;color:#202532;font:inherit}.sheet-metadata input{min-width:205px}.sheet-editor-scroll,.sheet-image-scroll,.sheet-accessible{overflow-x:auto}.customer-sheet table{width:100%;border-collapse:collapse;font-size:12px}.sheet-editor table{min-width:1000px}.customer-sheet th,.customer-sheet td{padding:10px 9px;border:1px solid #e0e3e6;text-align:center;vertical-align:middle}.customer-sheet th{background:#fff0e3;color:#924e10;font-weight:650}.customer-sheet td{background:#fff}.customer-sheet small{display:block;margin-top:4px;font-weight:400}.sheet-source{max-width:230px;color:#76828c;font-size:10px;line-height:1.5}.sheet-time input{width:170px;font-size:12px}.sheet-time button{display:block;margin:5px auto 0;padding:2px 4px;border:0;color:#a85d16;background:transparent;font-size:10px}.sheet-empty{padding:35px;text-align:center;border:1px dashed #d9e1e6;color:#87939d;font-size:12px}.sheet-image-area{border:1px solid #e0e4e8;background:#f6f7f9}.sheet-image-scroll img{display:block;width:100%;height:auto;min-width:768px}.sheet-pages{display:flex;justify-content:center;align-items:center;gap:15px;padding:10px;font-size:12px}.sheet-message,.sheet-pending{padding:10px 12px;border-radius:5px;background:#f0f7f1;color:#287a4d;font-size:12px;line-height:1.6}.sheet-message.failed,.sheet-pending{background:#fff4e6;color:#a65410}.sheet-accessible{padding:10px;background:#fff;font-size:12px;line-height:1.6}.sheet-accessible summary{cursor:pointer;color:#64727e}.sheet-accessible li{margin:8px 0}@media(max-width:850px){.sheet-toolbar{align-items:flex-start;flex-direction:column}.customer-sheet{margin-left:12px;margin-right:12px}.sheet-editor{padding:12px}.sheet-pages{gap:8px}.sheet-metadata{width:100%}.sheet-metadata label{flex:1}}
</style>
