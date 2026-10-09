<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { idempotencyKey } from '@/services/http'
import { saveFobQuotation } from '@/services/fobQuotationRecords'
import type { QuoteSheetColumnKey } from '@/data/customerQuoteSheet'
import { fobQuantityQuote, fobSheetRows, fobTierQuotes, type FobQuoteProduct, type FobSheetRow, type FobSmallOrderPolicy } from '@/services/fobQuotation'
import { copyQuoteSheetData } from '@/services/customerQuoteSheetClipboard'
import { copyQuoteSheetImage, renderCustomerQuoteSheet, type QuoteSheetImage } from '@/services/customerQuoteSheetRenderer'
import { formatQuoteDate, localQuoteDate, quoteSheetTextTable, type CustomerQuoteSheet } from '@/data/customerQuoteSheet'

const props = defineProps<{ product: FobQuoteProduct; rate: number; quantity: string; policy: FobSmallOrderPolicy | null; salesperson?: string; savedSheet?: CustomerQuoteSheet; initialCustomer?: string }>()
const customer = ref(props.initialCustomer || ''), saving = ref(false), savedNo = ref(''), saveError = ref('')
const columns = ref<QuoteSheetColumnKey[]>(['number','sku','prices']), priceOrder = ref([0,1]), rowOrder = ref<string[]>([])
const drag = ref<{kind:'row'|'column'|'price'; index:number} | null>(null)
let disposed = false, lastSignature = '', saveKey = ''
const mode = ref<'tiers' | 'quantity'>('tiers'), editing = ref(false), copying = ref(false), rendering = ref(false), message = ref(''), renderError = ref('')
const title = ref('JerryFulfillment Quote Sheet'), agent = ref(props.salesperson || ''), date = ref(localQuoteDate()), whatsapp = ref('')
const images = ref<Array<QuoteSheetImage & { url: string }>>([]), activeImage = ref(0)
const currentImage = computed(() => images.value[activeImage.value])
const editedNotes = ref<string[] | null>(null)
const defaultNotes = computed(() => [
  'FOB wholesale quotation. Unit prices are in USD. International shipping is not included.',
  `MOQ: ${props.product.parsed.minOrderQty}.` + (props.product.parsed.orderMultiple > 1 ? ` Order in multiples of ${props.product.parsed.orderMultiple}.` : ''),
])
const notes = computed(() => editedNotes.value ?? defaultNotes.value)
function updateNote(index: number, event: Event) {
  editedNotes.value = [...notes.value]
  editedNotes.value[index] = (event.target as HTMLTextAreaElement).value.slice(0, 3000)
}
let generation = 0
watch(() => props.salesperson, (value, old) => { if (agent.value === (old || '')) agent.value = value || '' })
function range(row: FobSheetRow) {
  const unit = row.unit === '件' ? 'pcs' : row.unit
  return `${row.maxQty == null ? `${row.minQty}+` : row.minQty === row.maxQty ? row.minQty : `${row.minQty}–${row.maxQty}`} ${unit}`
}
const sheet = computed<CustomerQuoteSheet>(() => {
  if (props.savedSheet) return props.savedSheet
  let rows: FobSheetRow[] = [], error = ''
  try {
    if (mode.value === 'tiers') rows = fobSheetRows(props.product, props.rate, props.policy)
    else {
      const quote = fobQuantityQuote(props.product, fobTierQuotes(props.product, props.rate), props.quantity, props.rate, props.policy)
      rows = [{ minQty: quote.quantity, maxQty: quote.quantity, unit: quote.row.unit, declaredUsd: quote.declaredUsd, undeclaredUsd: quote.undeclaredUsd }]
    }
  } catch (e) { error = e instanceof Error ? e.message : '报价单生成失败' }
  const formattedDate = formatQuoteDate(date.value)
  return {
    title: title.value, agent: agent.value, date: formattedDate, whatsapp: whatsapp.value,
    columnOrder: [...columns.value], quantityLabels: priceOrder.value.map(i => ['With declaration', 'Without declaration'][i]!), priceGroupLabel: 'FOB Unit Price (USD)', showQuantityRange: true,
    hiddenColumns: ['country', 'provider', 'shippingTime', 'processingTime'],
    rows: rows.map((row, index) => ({ key: `${props.product.sku}-${index}`, number: index + 1, sku: props.product.sku,
      quantityRange: range(row), prices: priceOrder.value.map(i => [Number(row.declaredUsd), Number(row.undeclaredUsd)][i]!),
      country: '', provider: '', shippingTime: '', sourceDescription: '' })).sort((a,b) => { const rank = (key:string) => { const i=rowOrder.value.indexOf(key); return i<0 ? rows.length : i }; return rank(a.key)-rank(b.key) }).map((row,index) => ({...row,number:index+1})),
    issues: [...(error ? [error] : []), ...(!formattedDate ? ['请填写有效报价日期'] : [])],
    notes: [...notes.value],
  }
})
const textTable = computed(() => quoteSheetTextTable(sheet.value))
function clearImages() { images.value.forEach(image => URL.revokeObjectURL(image.url)); images.value = []; activeImage.value = 0 }
async function preview() {
  const request = ++generation
  clearImages(); renderError.value = ''; message.value = ''
  if (sheet.value.issues.length || !sheet.value.rows.length) { rendering.value = false; return }
  rendering.value = true
  try {
    const result = await renderCustomerQuoteSheet(sheet.value, () => request !== generation)
    if (request !== generation) return
    images.value = result.map(image => ({ ...image, url: URL.createObjectURL(image.blob) }))
  } catch (e) { if (request === generation) renderError.value = e instanceof Error ? e.message : '报价图片生成失败' }
  finally { if (request === generation) rendering.value = false }
}
watch([sheet, editing], () => {
  generation++; clearImages(); message.value = ''; renderError.value = ''; rendering.value = false
  if (!editing.value) void preview()
}, { immediate: true })
onBeforeUnmount(() => { disposed = true; generation++; clearImages() })
function move(kind:'row'|'column'|'price', from:number, to:number) {
  if (saving.value || copying.value || props.savedSheet) return
  if(kind==='row') { const order=sheet.value.rows.map(r=>r.key); if(to<0||to>=order.length)return;order.splice(to,0,order.splice(from,1)[0]!);rowOrder.value=order }
  else if(kind==='column') {const order=[...columns.value];if(to<0||to>=order.length)return;order.splice(to,0,order.splice(from,1)[0]!);columns.value=order}
  else if(to>=0&&to<2) priceOrder.value=[...priceOrder.value].reverse()
}
function startDrag(kind:'row'|'column'|'price', index:number,event:DragEvent) {drag.value={kind,index};event.dataTransfer?.setData('text/plain',`${kind}:${index}`);if(event.dataTransfer)event.dataTransfer.effectAllowed='move'}
function drop(kind:'row'|'column'|'price',index:number) {if(drag.value?.kind===kind)move(kind,drag.value.index,index);drag.value=null}
function resetLayout() {columns.value=['number','sku','prices'];priceOrder.value=[0,1];rowOrder.value=[]}
watch([sheet,customer],()=>{savedNo.value='';saveError.value=''})
async function save() {
  if(saving.value || props.savedSheet || !props.policy || sheet.value.issues.length || !sheet.value.rows.length)return
  if(!customer.value.trim()){saveError.value='请填写客户名称后保存';return}
  saving.value=true;saveError.value=''
  const name=customer.value.trim()
  const snapshot=JSON.parse(JSON.stringify({schemaVersion:1,product:props.product,rate:props.rate,quantity:Number(props.quantity),policy:props.policy,displayMode:mode.value,sheet:sheet.value}))
  const signature=JSON.stringify({name,snapshot})
  if(signature!==lastSignature){lastSignature=signature;saveKey=idempotencyKey('fob-quotation')}
  try { const saved=await saveFobQuotation(name,snapshot,saveKey);if(!disposed) { if(JSON.stringify({name:customer.value.trim(),snapshot:{schemaVersion:1,product:props.product,rate:props.rate,quantity:Number(props.quantity),policy:props.policy,displayMode:mode.value,sheet:sheet.value}})===signature) savedNo.value=saved.no;else message.value='先前报价已保存：'+saved.no+'；当前条件已变化，需要另行保存' } }
  catch(e){if(!disposed)saveError.value=e instanceof Error?e.message:'保存失败，请重试'}
  finally{if(!disposed)saving.value=false}
}
async function copy(asImage: boolean) {
  if (copying.value || rendering.value || sheet.value.issues.length || !sheet.value.rows.length || asImage && !currentImage.value) return
  copying.value = true; message.value = ''
  try {
    if (asImage) await copyQuoteSheetImage(currentImage.value!.blob)
    else await copyQuoteSheetData(sheet.value)
    message.value = asImage ? '已复制报价图片' : '已复制报价数据'
  } catch (e) { message.value = e instanceof Error ? e.message : '复制失败，请重试' }
  finally { copying.value = false }
}
</script>

<template>
  <section class="customer-sheet" aria-label="FOB客户报价单">
    <div class="sheet-toolbar"><h3>{{ savedSheet ? '已保存客户报价单' : '客户报价单' }}</h3></div>
    <div v-if="!savedSheet" class="sheet-save"><label>客户名称 <input v-model="customer" aria-label="FOB客户名称" maxlength="120" :disabled="saving" placeholder="填写客户名称"></label><button class="sheet-primary" type="button" :disabled="saving || !policy || !!sheet.issues.length || !sheet.rows.length || !!savedNo" @click="save">{{ saving ? '正在保存…' : savedNo ? '已保存' : '保存FOB报价' }}</button><span v-if="savedNo" role="status">已保存：{{ savedNo }}，可在我的报价记录查看</span><span v-if="saveError" role="alert">{{ saveError }}</span></div>
    <fieldset :disabled="saving" class="sheet-edit-fieldset">
    <div class="sheet-editor">
      <div v-if="editing" class="sheet-metadata">
        <label>报价单标题<input v-model="title" aria-label="FOB报价单标题" maxlength="80" :disabled="copying"></label>
        <label>By Agent · 署名<input v-model="agent" aria-label="FOB报价单署名" maxlength="40" :disabled="copying"></label>
        <label>Date · 日期<input v-model="date" aria-label="FOB报价单日期" type="date" min="1000-01-01" max="9999-12-31" :disabled="copying"></label>
        <label>WhatsApp · 联系方式<input v-model="whatsapp" aria-label="FOB报价单联系方式" maxlength="40" :disabled="copying"></label>
      </div>
      <div class="sheet-controls">
        <label v-if="!savedSheet">展示范围 <select v-model="mode" aria-label="FOB报价单展示范围" :disabled="copying"><option value="tiers">全部阶梯</option><option value="quantity">当前数量</option></select></label>
        <div class="sheet-actions">
          <button v-if="!savedSheet" class="sheet-primary" type="button" :disabled="copying" @click="editing = !editing">{{ editing ? '预览报价单' : '编辑报价单' }}</button>
          <button type="button" :disabled="!currentImage || copying || rendering" @click="copy(true)">{{ copying ? '正在复制…' : images.length > 1 ? '复制当前图片' : '复制报价图片' }}</button>
          <button type="button" :disabled="!sheet.rows.length || !!sheet.issues.length || copying || rendering" @click="copy(false)">复制报价数据</button>
        </div>
      </div>
      <div v-if="editing && !savedSheet" class="fob-layout-editor">
        <div class="drag-tools"><b>列排序</b><button v-for="(column,index) in columns" :key="column" type="button" draggable="true" :aria-label="`${column}列排序，左右键移动`" @dragstart="startDrag('column',index,$event)" @dragend="drag=null" @dragover.prevent @drop.prevent="drop('column',index)" @keydown.left.prevent="move('column',index,index-1)" @keydown.right.prevent="move('column',index,index+1)">⠿ {{ {number:'序号',sku:'SKU / 数量',prices:'价格'}[column as 'number'|'sku'|'prices'] }}</button><button type="button" @click="resetLayout">恢复默认排序</button></div>
        <div class="drag-tools"><b>价格列</b><button v-for="(column,index) in priceOrder" :key="column" type="button" draggable="true" :aria-label="`${column===0?'报关':'不报关'}价格列排序，左右键移动`" @dragstart="startDrag('price',index,$event)" @dragend="drag=null" @dragover.prevent @drop.prevent="drop('price',index)" @keydown.left.prevent="move('price',index,index-1)" @keydown.right.prevent="move('price',index,index+1)">⠿ {{ column===0?'报关':'不报关' }}</button></div>
        <table><thead><tr><th>行排序</th><th>SKU</th><th>数量</th><th v-for="label in sheet.quantityLabels" :key="label">{{ label }}</th></tr></thead><tbody><tr v-for="(row,index) in sheet.rows" :key="row.key" @dragover.prevent @drop.prevent="drop('row',index)"><td><button type="button" draggable="true" :aria-label="`第${index+1}行排序，上下键移动`" @dragstart="startDrag('row',index,$event)" @dragend="drag=null" @keydown.up.prevent="move('row',index,index-1)" @keydown.down.prevent="move('row',index,index+1)">⠿</button></td><td>{{ row.sku }}</td><td>{{ row.quantityRange }}</td><td v-for="(price,index) in row.prices" :key="index">${{ price?.toFixed(2) }}</td></tr></tbody></table>
      </div>
      <p v-if="sheet.issues.length" class="sheet-pending" role="alert">{{ sheet.issues.join('；') }}</p>
      <p v-else-if="renderError" class="sheet-pending" role="alert">{{ renderError }} <button type="button" @click="preview">重新生成预览</button></p>
      <p v-else-if="rendering" class="sheet-pending" role="status">正在生成预览…</p>
      <div v-if="!sheet.issues.length && sheet.rows.length" aria-label="FOB报价单预览">
        <div v-if="!editing && currentImage" class="sheet-image-area">
          <div v-if="images.length > 1" class="sheet-pages"><button type="button" :disabled="activeImage === 0 || copying" @click="activeImage--">上一张</button><span>第 {{ activeImage + 1 }} / {{ images.length }} 张</span><button type="button" :disabled="activeImage === images.length - 1 || copying" @click="activeImage++">下一张</button></div>
          <div class="sheet-image-scroll"><img :src="currentImage.url" :width="currentImage.width" :height="currentImage.height" alt="JerryFulfillment Quote Sheet FOB客户报价图片预览" draggable="false"></div>
        </div>
        <details class="sheet-accessible"><summary>查看报价单文字内容</summary>
          <p>{{ sheet.title }} — By Agent: {{ sheet.agent }} — Date: {{ sheet.date }}</p>
          <table><thead><tr><th v-for="(cell,index) in textTable[0]" :key="index">{{ cell }}</th></tr></thead><tbody><tr v-for="(row,index) in textTable.slice(1)" :key="index"><td v-for="(cell,column) in row" :key="column">{{ cell }}</td></tr></tbody></table>
          <h4>IMPORTANT NOTES</h4><ol><li v-for="note in sheet.notes" :key="note">{{ note }}</li></ol>
        </details>
      </div>
      <details v-if="editing" class="sheet-notes-editor" open>
        <summary>报价说明</summary>
        <label v-for="(note,index) in notes" :key="index">{{ index + 1 }}<textarea :value="note" :aria-label="`FOB第 ${index + 1} 条报价说明`" maxlength="3000" :disabled="copying" @input="updateNote(index,$event)" /></label>
      </details>
    </div>
    </fieldset>
    <p v-if="message" class="sheet-message" role="status">{{ message }}</p>
  </section>
</template>

<style scoped>
.customer-sheet .sheet-save>button.sheet-primary{min-width:148px;min-height:44px;padding:10px 22px;font-size:16px;line-height:1.4;font-weight:700}
.sheet-edit-fieldset{margin:0;padding:0;border:0;min-width:0}.sheet-save,.drag-tools{display:flex;gap:10px;align-items:center;flex-wrap:wrap;margin:8px 0;font-size:12px}.sheet-save input{min-width:200px}.sheet-save [role=alert]{color:#b73126}.sheet-save [role=status]{color:#287a4d}.fob-layout-editor{overflow:auto;padding:8px 0}.drag-tools [draggable=true]{cursor:grab}

.sheet-notes-editor{margin-top:5px;font-size:12px}.sheet-notes-editor summary{cursor:pointer;color:#925013}.sheet-notes-editor label{display:flex;gap:12px;margin:6px 0}.sheet-notes-editor textarea{box-sizing:border-box;width:100%;min-height:64px;resize:vertical;border:1px solid #ccd4db;padding:6px;font:inherit;line-height:1.6}
.customer-sheet{margin:10px 0;padding:10px 12px;border:1px solid #dfe5e9;border-radius:8px;background:#fff;color:#202532}.sheet-toolbar{display:flex;align-items:center;margin-bottom:6px}.sheet-toolbar h3{margin:0;font-size:15px}.sheet-actions{display:flex;align-items:center;gap:6px;flex-wrap:wrap;margin-left:auto}.customer-sheet button{padding:4px 8px;border:1px solid #d7dce1;border-radius:6px;background:#fff;color:#243440;font-size:12px;font-weight:650;cursor:pointer}.customer-sheet button.sheet-primary{background:#f58220;border-color:#f58220;color:#fff}.customer-sheet button:disabled{background:#edf0f2;border-color:#e0e4e8;color:#919aa3;cursor:not-allowed}.sheet-editor{padding:6px;background:#fafbfc;border:1px solid #dfe5e9;border-radius:8px}.sheet-metadata{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:8px}.sheet-metadata label{display:grid;gap:2px;min-width:0;font-size:12px;font-weight:650}.customer-sheet input,.customer-sheet select{height:28px;padding:0 5px;box-sizing:border-box;border:1px solid #ccd4db;border-radius:4px;background:#fff;color:#202532;font:inherit}.sheet-metadata input{width:100%;min-width:0}.sheet-image-scroll,.sheet-accessible{overflow-x:auto}.customer-sheet table{width:100%;border-collapse:collapse;font-size:12px}.customer-sheet th,.customer-sheet td{padding:3px 5px;border:1px solid #e0e3e6;text-align:center;vertical-align:middle}.customer-sheet th{background:#fff0e3;color:#924e10;font-weight:650}.customer-sheet td{background:#fff}.sheet-image-area{border:1px solid #e0e4e8;background:#f6f7f9}.sheet-image-scroll img{display:block;width:100%;height:auto;min-width:768px}.sheet-pages{display:flex;justify-content:center;align-items:center;gap:15px;padding:10px;font-size:12px}.sheet-message,.sheet-pending{padding:8px 10px;border-radius:5px;background:#f0f7f1;color:#287a4d;font-size:12px;line-height:1.6}.sheet-pending{background:#fff4e6;color:#a65410}.sheet-accessible{padding:6px;background:#fff;font-size:12px;line-height:1.6}.sheet-accessible summary{cursor:pointer;color:#64727e}.sheet-accessible li{margin:6px 0}.sheet-controls{display:flex;align-items:center;flex-wrap:wrap;gap:8px;padding:4px 0;background:#fafbfc;font-size:12px}.customer-sheet input:focus{outline:1px solid #f58220;border-color:#f58220}@media(max-width:1100px){.sheet-metadata{grid-template-columns:repeat(2,minmax(0,1fr))}}@media(max-width:600px){.sheet-metadata{grid-template-columns:minmax(0,1fr)}.sheet-actions{margin-left:0}}
</style>
