<script setup lang="ts">
import { computed, defineAsyncComponent, nextTick, onBeforeUnmount, onErrorCaptured, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { currentAuthUser, hasPermission } from '@/data/authStore'
import { idempotencyKey } from '@/services/http'
import { mergeBatchShipping, saveBatchShipping, type BatchSingleDocument, type BatchSingleItem } from '@/services/batchSingleQuotation'
import { parseShippingPaste, shippingEntryError, type ShippingBatchEntry } from '@/services/batchShippingInput'
import { rememberQuotationCustomer } from '@/data/lastQuotationCustomer'
import BatchSingleSheetEditor from '@/components/quotation/BatchSingleSheetEditor.vue'
import BatchSingleSheet from '@/components/quotation/BatchSingleSheet.vue'
import ShippingEntrySwitch from '@/components/quotation/ShippingEntrySwitch.vue'

const Workbench = defineAsyncComponent(() => import('./QuotationSystemView.vue'))
const engine = ref<InstanceType<typeof import('./QuotationSystemView.vue').default>>()
const editor = ref<InstanceType<typeof BatchSingleSheetEditor>>()
let nextId = 1, generation = 0, disposed = false, lastSignature = '', saveKey = ''
const makeRow = (weight = '', note = ''): ShippingBatchEntry => ({ id: String(nextId++), weight, note, selected: true })
const rows = ref<ShippingBatchEntry[]>([makeRow()])
const stage = ref<'input' | 'channels' | 'result'>('input'), error = ref(''), busy = ref(false), sheetBusy = ref(false), preparing = ref(false)
const progress = ref(''), stopping = ref(false), inputPage = ref(0), pageSize = ref(10)
const pasteOpen = ref(false), pasteText = ref(''), seed = ref<BatchSingleItem>(), saved = ref<BatchSingleDocument>()
const editorKey = ref(0), resultSection = ref<HTMLElement>(), initializing = ref(false)
const selected = computed(() => rows.value.filter(row => row.selected))
const completed = computed(() => selected.value.filter(row => row.item && !row.error))
const unfinished = computed(() => selected.value.filter(row => !row.item || row.error))
const locked = computed(() => busy.value || sheetBusy.value || preparing.value || initializing.value)
const inputLocked = computed(() => locked.value || stage.value !== 'input')
const pages = computed(() => Math.max(1, Math.ceil(rows.value.length / pageSize.value)))
const visibleRows = computed(() => rows.value.slice(inputPage.value * pageSize.value, (inputPage.value + 1) * pageSize.value))
const partial = computed(() => {
  if (!completed.value.length) return undefined
  try { return mergeBatchShipping(completed.value.map(row => row.item!)) } catch { return undefined }
})
const combined = computed(() => !unfinished.value.length ? partial.value : undefined)
const countryCount = computed(() => new Set(partial.value?.sheet.rows.map(row => row.country)).size)
watch(pageSize, () => { inputPage.value = 0 })
watch(pages, value => { inputPage.value = Math.min(inputPage.value, value - 1) })
function addRow(source?: ShippingBatchEntry) {
  if (inputLocked.value || rows.value.length >= 50) return
  rows.value.push(makeRow(source?.weight, source?.note)); inputPage.value = pages.value - 1
}
function applyPaste() {
  if (inputLocked.value) return
  try {
    const pasted = parseShippingPaste(pasteText.value)
    const existing = rows.value.filter(row => row.weight.trim() || row.note.trim())
    if (existing.length + pasted.length > 50) throw new Error('合计超过50条，未导入任何内容')
    if (!existing.length) nextId = 1
    rows.value = [...existing, ...pasted.map(row => makeRow(row.weight, row.note))]
    pasteOpen.value = false; pasteText.value = ''; error.value = ''; inputPage.value = 0
  } catch (e) { error.value = e instanceof Error ? e.message : '粘贴失败' }
}
async function prepare(): Promise<boolean> {
  if (locked.value) return false
  error.value = ''
  if (!selected.value.length) { error.value = '请至少选入一条克重'; return false }
  rows.value.forEach(row => { row.error = row.selected ? shippingEntryError(row) : ''; row.item = undefined })
  if (selected.value.some(row => row.error)) { error.value = '请修正标红的重量或备注'; return false }
  saved.value = undefined; seed.value = undefined; stage.value = 'channels'; initializing.value = true
  const token = ++generation
  try {
    const deadline = Date.now() + 45000
    while (!disposed && token === generation && !engine.value?.batchReady && Date.now() < deadline) { await nextTick(); await new Promise(resolve => window.setTimeout(resolve, 80)) }
    if (disposed || token !== generation) return false
    if (!engine.value?.batchReady) throw new Error('报价工作区加载超时，请返回重试')
    engine.value.primeBatchShipping(selected.value[0]!)
    return true
  } catch (e) { if (token === generation) { error.value = e instanceof Error ? e.message : '加载失败'; stage.value = 'input' } return false }
  finally { if (token === generation) initializing.value = false }
}
async function generate(first: BatchSingleItem, retry = false) {
  if (busy.value || sheetBusy.value) return
  if (!first.entryId || !selected.value.some(row => row.id === first.entryId)) { error.value = '样例已移出本批，请重新选择'; return }
  const token = ++generation, account = currentAuthUser.value.account
  const fresh = () => !disposed && token === generation && currentAuthUser.value.account === account
  const candidates = [...(retry ? unfinished.value : selected.value)]
  seed.value = JSON.parse(JSON.stringify(first)); busy.value = true; stopping.value = false; error.value = ''; saved.value = undefined
  stage.value = 'result'
  if (!retry) rows.value.forEach(row => { row.item = undefined; row.error = '' })
  try {
    await nextTick(); resultSection.value?.scrollIntoView?.({ block: 'start' })
    await new Promise(resolve => window.setTimeout(resolve, 0))
    for (let index = 0; index < candidates.length; index++) {
      if (!fresh() || stopping.value) break
      const row = candidates[index]!
      progress.value = `正在计算 ${index + 1}/${candidates.length} · ${row.weight}g · ${row.note || '第' + row.id + '条'}`
      row.item = undefined; row.error = ''
      try {
        const item = !retry && row.id === first.entryId ? first : await engine.value!.calculateBatchItem(row.id, first.conditions, first.input.customerQuote!.quantities)
        if (!fresh()) return
        row.item = item
      } catch (e) { if (!fresh()) return; row.error = e instanceof Error ? e.message : '计算失败，请重试' }
    }
    if (fresh() && !unfinished.value.length) { try { mergeBatchShipping(completed.value.map(row => row.item!)) } catch (e) { error.value = e instanceof Error ? e.message : '报价单合并失败' } }
    if (fresh()) { progress.value = `${completed.value.length}条已完成，${unfinished.value.length}条待处理`; editorKey.value++ }
  } finally { if (fresh()) { busy.value = false; stopping.value = false } }
}
async function editConditions() {
  if (locked.value) return
  if (saved.value) { lastSignature = ''; saveKey = '' }
  await engine.value?.restoreBatchSample()
  rows.value.forEach(row => { row.item = undefined; row.error = '' }); stage.value = 'channels'; saved.value = undefined
}
function editInput() { if (locked.value) return; if (saved.value) { lastSignature = ''; saveKey = '' } generation++; engine.value?.resetBatchShippingChannels(); stage.value = 'input'; rows.value.forEach(row => { row.item = undefined; row.error = '' }); saved.value = undefined; seed.value = undefined }
function excludeFailed() { if (locked.value) return; unfinished.value.forEach(row => { row.selected = false }); editorKey.value++ }
async function calculateItem(id: string, quantities: number[]) {
  if (!engine.value || !seed.value) throw new Error('报价工作区未就绪')
  return engine.value.calculateBatchItem(id, seed.value.conditions, quantities)
}
async function save() {
  if (locked.value || !combined.value || saved.value || !editor.value) return
  busy.value = true; error.value = ''
  const token = generation, account = currentAuthUser.value.account
  try {
    const document = await editor.value.capture()
    const signature = JSON.stringify(document)
    if (signature !== lastSignature) { lastSignature = signature; saveKey = idempotencyKey('batch-shipping') }
    const result = await saveBatchShipping(document, saveKey)
    if (disposed || generation !== token || currentAuthUser.value.account !== account) return
    saved.value = result
    rememberQuotationCustomer(currentAuthUser.value.id, { name: document.customerName, selectedCustomerId: document.items[0]?.input.customerOperation?.id || '' })
  } catch (e) { if (token === generation) error.value = e instanceof Error ? e.message : '保存失败，重试不会重复生成记录' }
  finally { if (token === generation) busy.value = false }
}
const unsaved = computed(() => !saved.value && rows.value.some(row => row.weight.trim() || row.note.trim()))
onBeforeRouteLeave(() => !locked.value && (!unsaved.value || window.confirm('本批报价尚未保存，离开会丢失输入，确定离开？')))
function beforeUnload(event: BeforeUnloadEvent) { if (unsaved.value || locked.value) { event.preventDefault(); event.returnValue = '' } }
onMounted(() => window.addEventListener('beforeunload', beforeUnload))
onBeforeUnmount(() => { disposed = true; generation++; window.removeEventListener('beforeunload', beforeUnload) })
watch(() => currentAuthUser.value.account, () => { generation++; rows.value = [makeRow()]; stage.value = 'input'; saved.value = undefined; seed.value = undefined; busy.value = false; initializing.value = false; preparing.value = false; sheetBusy.value = false })
onErrorCaptured(e => { generation++; busy.value = false; initializing.value = false; error.value = e.message; return false })
</script>

<template>
  <main class="batch-shipping-page">
    <header class="page-heading"><div><h1>批量代发货报价</h1><p>多条克重独立计价，合并生成一张客户报价单</p></div><ShippingEntrySwitch active="batch" /></header>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <template v-if="hasPermission('quote')">
      <section class="card input-card">
        <header class="section-heading"><h2><i>01</i>批量录入代发数据</h2><span>每行一个重量和备注 · 最多50条</span></header>
        <div class="body">
          <div class="toolbar"><button :disabled="inputLocked || rows.length >= 50" @click="addRow()">＋ 添加一行</button><button :disabled="inputLocked" @click="pasteOpen = !pasteOpen">从 Excel 粘贴</button><span>相同克重也按不同录入行分别保存</span></div>
          <div v-if="pasteOpen && !inputLocked" class="paste-panel"><label>第一列重量（g），第二列备注（选填）<textarea v-model="pasteText" aria-label="批量代发粘贴" placeholder="200&#9;短袖&#10;350&#9;连衣裙" /></label><button class="primary" @click="applyPaste">确认粘贴</button><button @click="pasteOpen = false">取消</button></div>
          <div class="table-scroll"><table><thead><tr><th>选入</th><th>序号</th><th>重量（g / 件）</th><th>备注（选填）</th><th>操作</th></tr></thead><tbody><tr v-for="row in visibleRows" :key="row.id"><td><input v-model="row.selected" :disabled="inputLocked" type="checkbox" :aria-label="`选入第${row.id}条`"></td><td>{{ row.id }}</td><td><input v-model="row.weight" :readonly="inputLocked" class="weight" inputmode="decimal" :aria-label="`第${row.id}条克重`" placeholder="填写克重"><small v-if="row.error" class="field-error">{{ row.error }}</small></td><td><input v-model="row.note" :readonly="inputLocked" class="note" :aria-label="`第${row.id}条备注`" maxlength="200" placeholder="可填写 SKU、商品名称或其他备注"></td><td><button class="text" :disabled="inputLocked || rows.length >= 50" @click="addRow(row)">复制</button><button class="text" :disabled="inputLocked" @click="rows = rows.filter(item => item.id !== row.id)">删除</button></td></tr></tbody></table></div>
          <nav class="pagination"><span>共{{ rows.length }}条 · 已选{{ selected.length }}条</span><div><select v-model.number="pageSize" aria-label="每页录入条数"><option :value="10">每页10条</option><option :value="20">每页20条</option><option :value="50">每页50条</option></select><button :disabled="!inputPage" @click="inputPage--">上一页</button><span>{{ inputPage + 1 }} / {{ pages }}</span><button :disabled="inputPage + 1 >= pages" @click="inputPage++">下一页</button></div></nav>
        </div><footer><span>客户自备商品，无需填写采购成本；按实重计价，不增加包材重量。</span><button v-if="stage !== 'input'" :disabled="locked" @click="editInput">修改录入清单</button></footer>
      </section>
      <p v-if="initializing" role="status">正在加载报价条件…</p>
      <section v-show="stage !== 'result'" class="card workbench" :inert="locked || undefined">
        <Workbench ref="engine" :key="currentAuthUser.account" batch-capture batch-shipping :before-shipping-query="prepare" :batch-size="selected.length" :batch-skus="selected.map(row => row.id)" :shipping-entries="selected" @batch-captured="generate" @batch-preparing="preparing = $event" @batch-sample-busy="sheetBusy = $event" />
      </section>
      <section v-if="stage === 'result'" ref="resultSection" class="card result-card">
        <header class="section-heading"><h2><i>03</i>合并报价单</h2><span>{{ saved ? '已保存' : busy ? progress : combined ? '生成完成 · 尚未保存' : '有待处理项 · 尚未保存' }}</span></header>
        <div class="body"><div class="result-stats"><span>{{ combined ? 1 : 0 }}张完整报价单</span><span>{{ completed.length }}/{{ selected.length }}条已完成</span><span>{{ partial?.sheet.rows.length || 0 }}行明细</span><span>{{ countryCount }}个国家</span></div>
          <div v-if="busy" class="progress" role="status">{{ progress || '正在校验并保存…' }}<button v-if="!combined" :disabled="stopping" @click="stopping = true">{{ stopping ? '正在停止…' : '停止生成' }}</button></div>
          <div v-if="unfinished.length && !busy" class="pending"><b>{{ unfinished.length }}条待处理，尚不能整批保存</b><ul><li v-for="row in unfinished" :key="row.id">第{{ row.id }}条 · {{ row.weight }}g · {{ row.error || '尚未完成' }}</li></ul><button :disabled="locked || !seed" @click="generate(seed!, true)">仅重试失败 / 未完成项</button><button :disabled="locked" @click="excludeFailed">取消选入待处理项</button></div>
          <div v-if="partial" class="quote-basics"><span>客户 <b>{{ partial.customerName }}</b></span><span>业务类型 <b>仅代发货报价</b></span><span>客户等级 <b>{{ partial.items[0]?.input.customerGrade }}</b></span><span>币种 <b>USD</b></span></div>
          <BatchSingleSheetEditor v-if="combined && !saved" :inert="busy || undefined" :key="editorKey" ref="editor" :document="combined" :calculate-item="calculateItem" @busy="sheetBusy = $event" />
          <BatchSingleSheet v-else-if="partial && !busy" :sheet="saved?.sheet || partial.sheet" :photos="saved?.items[0]?.input.customerQuote?.showPhotos === false ? [] : saved?.items[0]?.input.customerQuote?.photos" :read-only="!saved" paginated />
          <p v-if="saved" class="success" role="status">已生成{{ saved.createdRecords?.length }}条独立报价记录。批次：{{ saved.no }}。<RouterLink :to="{ path: '/quotation/my-records', query: { batch: saved.no } }">查看本批报价记录</RouterLink></p>
        </div><footer><span>每条克重单独生成报价记录，按原流程审核与跟进。</span><div><button :disabled="locked" @click="editConditions">修改条件 / 重新生成</button><button class="primary" :disabled="locked || !combined || !!saved" @click="save">{{ saved ? '已保存' : busy && combined ? '正在保存…' : `保存${selected.length}条报价记录` }}</button></div></footer>
      </section>
    </template>
  </main>
</template>

<style scoped>
.batch-shipping-page{padding:22px;background:#f4f6f8;color:#1b2935;min-height:80vh;font-size:13px}.page-heading{display:flex;justify-content:space-between;align-items:center;margin:2px 0 26px}.page-heading h1{font-size:24px;margin:0}.page-heading p{color:#748399;margin:12px 0 0}.card{margin:16px 0;background:white;border:1px solid #dfe5ed;border-radius:9px;overflow:hidden}.section-heading{display:flex;align-items:center;gap:20px;padding:15px 20px;background:#fff6e8;border-bottom:1px solid #f1dcb8}.section-heading h2{margin:0;display:flex;align-items:center;gap:10px;font-size:16px}.section-heading i{font-style:normal;display:grid;place-items:center;width:27px;height:27px;border-radius:6px;background:#ff9800;color:white;font-size:12px}.section-heading>span{color:#748399;font-size:12px}.body{padding:20px}button,select{padding:9px 13px;border:1px solid #d2dbe1;border-radius:6px;background:white;color:#263344;cursor:pointer;font:inherit}button:disabled{opacity:.45;cursor:default}.primary{background:#ff9800;border-color:#ff9800;font-weight:600}.toolbar{display:flex;gap:10px;align-items:center;margin-bottom:16px}.toolbar span{color:#748399;margin-left:auto;font-size:12px}table{width:100%;border-collapse:collapse;text-align:left}th{background:#eef5fc;font-weight:500}td,th{padding:10px 12px;border-bottom:1px solid #e4e8ee}td:nth-child(4){width:48%}input:not([type=checkbox]){padding:8px 10px;border:1px solid #ccd6e2;border-radius:6px;font:inherit;box-sizing:border-box}.weight{width:160px}.note{width:100%}.text{border:0;padding:5px 8px;color:#52738e}.pagination,.pagination>div{display:flex;gap:12px;align-items:center}.pagination{justify-content:space-between;margin-top:16px;color:#748399}.card footer{display:flex;align-items:center;justify-content:space-between;gap:16px;padding:16px 20px;border-top:1px solid #e5eaf0}.card footer>span{color:#748399;font-size:12px}.card footer>div{display:flex;gap:10px}.workbench{padding:12px}.workbench :deep(.jerry-app){min-height:0;background:transparent}.workbench :deep(.jerry-app .quotation-page){width:100%;max-width:none;padding:0;margin:0;box-sizing:border-box}.workbench :deep(.draft-status-bar){display:none}.result-card{border-color:#d9d1f4;scroll-margin-top:20px}.result-card .section-heading{background:#f3effc;border-color:#e5dff4}.result-card .section-heading i{background:#9870d7}.quote-basics{display:flex;gap:36px;flex-wrap:wrap;padding:14px 0 4px;color:#748399}.quote-basics b{color:#18334c;margin-left:8px;font-weight:500}.result-stats{display:flex;gap:28px;padding-bottom:16px;border-bottom:1px solid #e5eaf0;color:#63768b}.error,.pending{background:#fff4e9;color:#a33d20;padding:14px;border-radius:6px}.pending{margin-top:16px}.pending li{margin:8px 0}.pending button{margin-right:10px}.field-error{display:block;color:#b23d27;margin-top:4px}.success{color:#16774c}.progress{padding:20px;display:flex;justify-content:space-between}.paste-panel{padding:14px;background:#f7f9fc;margin:12px 0}.paste-panel label{display:block}.paste-panel textarea{display:block;box-sizing:border-box;width:100%;min-height:100px;margin:10px 0;padding:10px;border:1px solid #ccd6e2;border-radius:6px}.paste-panel button{margin-right:10px}.table-scroll{overflow:auto}@media(max-width:800px){.batch-shipping-page{padding:10px}.toolbar,.page-heading,.card footer,.pagination{flex-wrap:wrap}.body{padding:12px}.weight{width:110px}.note{min-width:200px}.result-stats{gap:12px;flex-wrap:wrap}}
.input-card .body{padding:12px 16px}.input-card .section-heading{padding:10px 16px}.input-card .toolbar{margin-bottom:10px}.input-card .toolbar button{padding:6px 12px}.input-card td,.input-card th{padding:5px 12px}.input-card th{background:#f5f7fa;color:#526275;font-size:12px}.input-card input:not([type=checkbox]){padding:5px 9px;border-radius:4px;height:30px}.input-card input[readonly]{background:#fafbfc;color:#526275}.input-card .weight{width:140px}.input-card .pagination{margin-top:10px}.input-card .pagination button,.input-card .pagination select{padding:5px 10px}.input-card footer{padding:10px 16px;min-height:36px}.input-card .text{color:#2274c9;padding:5px 8px}.input-card .text:last-child{color:#7c8795}
</style>
