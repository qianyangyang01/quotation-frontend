<script setup lang="ts">
import { computed, defineAsyncComponent, nextTick, onMounted, onBeforeUnmount, onErrorCaptured, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { currentAuthUser, hasPermission } from '@/data/authStore'
import { idempotencyKey } from '@/services/http'
import { batchReviewLabels, correctBatchSingle, loadBatchSingleRecord, loadBatchSingleRecords, mergeBatchSingle, parseBatchSingleSkus, saveBatchSingle, type BatchSingleDocument, type BatchSingleItem, type BatchSingleLine } from '@/services/batchSingleQuotation'
import { normalizeQuotationRecord } from '@/data/quotationRecords'
import { loadPurchaseProduct, purchaseQuoteBlockingMessage, type PurchaseProductRecord } from '@/data/purchaseStore'
import { quotationReissuePayload } from '@/services/quotationReissue'
import BatchSingleSheet from '@/components/quotation/BatchSingleSheet.vue'
import BatchSingleRecordDetail from '@/components/quotation/BatchSingleRecordDetail.vue'

const route = useRoute()
onMounted(() => { if (route.query.records) void history() })
const Workbench = defineAsyncComponent(() => import('./QuotationSystemView.vue'))
const engine = ref<InstanceType<typeof import('./QuotationSystemView.vue').default>>()
const text = ref(''), lines = ref<BatchSingleLine[]>([]), error = ref(''), progress = ref(''), active = ref(false)
const running = ref(false), saving = ref(false), engineFailed = ref(false), showEditor = ref(true)
const saved = ref<BatchSingleDocument>(), historyDocument = ref<BatchSingleDocument>()
const correction = ref<{ document: BatchSingleDocument; index: number }>()
const inputExpanded = ref(true), editorSection = ref<HTMLElement>()
const processingPage = ref(0), processingSize = ref(10), processingScroll = ref<HTMLElement>()
const processingPages = computed(() => Math.max(1, Math.ceil(lines.value.length / processingSize.value)))
const visibleLines = computed(() => lines.value.map((line, index) => ({ line, index })).slice(processingPage.value * processingSize.value, (processingPage.value + 1) * processingSize.value))
watch(processingSize, () => { processingPage.value = 0 })
watch(processingPages, count => { processingPage.value = Math.min(processingPage.value, count - 1) })
watch(processingPage, () => { processingScroll.value?.scrollTo?.({ top: 0 }) })
const records = ref<Awaited<ReturnType<typeof loadBatchSingleRecords>>>({ items: [], total: 0 })
const historyOpen = ref(false), historyBusy = ref(false), page = ref(0), scope = ref('mine')
let disposed = false, generation = 0, lastRequest = '', saveKey = '', historyGeneration = 0
const selected = computed(() => lines.value.filter(line => line.selected))
const combined = computed(() => {
  if (!selected.value.length || selected.value.some(line => !line.item || line.error)) return undefined
  try { return mergeBatchSingle(selected.value.map(line => line.item!)) } catch { return undefined }
})
onErrorCaptured(e => { engineFailed.value = true; running.value = false; generation++; error.value = `批量工作区异常：${e.message}。可重新打开此功能，普通报价仍可使用。`; return false })
async function prepare() {
  if (running.value || saving.value) return
  error.value = ''; saved.value = undefined; correction.value = undefined; processingPage.value = 0
  try { lines.value = parseBatchSingleSkus(text.value) }
  catch (e) { error.value = e instanceof Error ? e.message : 'SKU清单无效'; return }
  const token = ++generation, account = currentAuthUser.value.account
  running.value = true; active.value = selected.value.length > 0; showEditor.value = true; engineFailed.value = false; inputExpanded.value = false
  let first: PurchaseProductRecord | undefined
  try {
    const candidates = [...selected.value]
    for (let i = 0; i < candidates.length; i++) {
      const line = candidates[i]!
      if (disposed || token !== generation || account !== currentAuthUser.value.account) return
      progress.value = `正在查询 ${i + 1}/${candidates.length} · ${line.sku}`
      try {
        const purchase = await loadPurchaseProduct(line.sku)
        if (disposed || token !== generation || account !== currentAuthUser.value.account) return
        if (!purchase.quoteReady) throw new Error(purchaseQuoteBlockingMessage(purchase) || '采购资料不完整')
        line.queried = true; first ??= purchase
      } catch (e) { line.error = e instanceof Error ? e.message : '查询失败，可重新查询'; line.selected = false }
    }
    progress.value = `查询完成：${lines.value.filter(line => line.queried).length} 个可报价，${lines.value.filter(line => line.error).length} 个异常`
    if (first) {
      const deadline = Date.now() + 45000
      while (!disposed && token === generation && !engine.value?.batchReady && !engineFailed.value && Date.now() < deadline) { await nextTick(); await new Promise(resolve => window.setTimeout(resolve, 100)) }
      if (disposed || token !== generation || account !== currentAuthUser.value.account) return
      if (!engine.value?.batchReady) throw new Error('报价条件加载失败或超时，请重新查询')
      engine.value.primeBatchProduct(first)
    }
  } catch (e) { error.value = e instanceof Error ? e.message : '查询失败，请重试' }
  finally { if (token === generation) running.value = false }
}
async function goToConditions() { showEditor.value = true; await nextTick(); editorSection.value?.scrollIntoView?.({ behavior: 'smooth', block: 'start' }) }
async function captured(first: BatchSingleItem) {
  if (running.value || saving.value) return
  if (!selected.value.some(line => line.sku === first.input.primarySku)) { error.value = 'SKU勾选范围已变化，请重新加载渠道后生成批量报价'; return }
  await generate(first)
}
async function generate(first: BatchSingleItem) {
  const token = ++generation; running.value = true; error.value = ''; saved.value = undefined
  const candidates = [...selected.value], quantities = first.input.customerQuote!.quantities
  try {
    await new Promise(resolve => window.setTimeout(resolve, 0)) // Let the first capture release its save lock.
    for (let i = 0; i < candidates.length; i++) {
      if (disposed || token !== generation) return
      const line = candidates[i]!; progress.value = `正在计算 ${i+1}/${candidates.length} · ${line.sku}`
      line.item = undefined; line.error = ''
      try {
        const item = line.sku === first.input.primarySku ? first : await engine.value!.calculateBatchItem(line.sku, first.conditions, quantities)
        if (disposed || token !== generation) return
        line.item = item
      } catch (e) { line.error = e instanceof Error ? e.message : '查询失败，请重试' }
    }
    showEditor.value = false
    progress.value = `已完成：${candidates.filter(l => l.item).length}个成功，${candidates.filter(l => l.error).length}个待处理`
    if (!combined.value && !candidates.some(l => l.error)) error.value = '各 SKU 的客户、汇率或数量列不一致，请统一重新生成'
  } finally { if (token === generation) running.value = false }
}
function edit() { saved.value = undefined; showEditor.value = true }
async function save() {
  if (saving.value || running.value || !combined.value || saved.value) return
  saving.value = true; error.value = ''
  const input = JSON.parse(JSON.stringify(combined.value)) as BatchSingleDocument, signature = JSON.stringify({ input, correction: correction.value })
  if (signature !== lastRequest) { lastRequest = signature; saveKey = idempotencyKey('batch-single') }
  try { const target = correction.value; const result = target ? await correctBatchSingle(target.document, target.index, input, saveKey) : await saveBatchSingle(input, saveKey); if (!disposed) { saved.value = result; if (target) { historyOpen.value = true; historyDocument.value = result; correction.value = undefined } } }
  catch (e) { if (!disposed) error.value = e instanceof Error ? e.message : '保存失败，可重试；同一请求不会重复生成报价单' }
  finally { saving.value = false }
}
async function history() {
  const token = ++historyGeneration; historyOpen.value = true; historyBusy.value = true; error.value = ''; historyDocument.value = undefined
  try { const result = await loadBatchSingleRecords(page.value, scope.value); if (!disposed && token === historyGeneration) records.value = result }
  catch (e) { if (!disposed && token === historyGeneration) error.value = e instanceof Error ? e.message : '记录读取失败' }
  finally { if (token === historyGeneration) historyBusy.value = false }
}
async function openRecord(id: string) {
  const token = ++historyGeneration; historyBusy.value = true; error.value = ''
  try { const record = await loadBatchSingleRecord(id); if (!disposed && token === historyGeneration) historyDocument.value = record }
  catch (e) { if (!disposed && token === historyGeneration) error.value = e instanceof Error ? e.message : '记录读取失败' }
  finally { if (token === historyGeneration) historyBusy.value = false }
}
function recordUpdated(document: BatchSingleDocument) { historyDocument.value = document; const row = records.value.items.find(item => item.id === document.id); if (row) { row.reviewStatus = document.review?.status; row.revision = document.review?.revision } }
async function beginCorrection(index: number) {
  const document = historyDocument.value, item = document?.items[index]
  if (!document || !item || running.value || saving.value) return
  if (active.value && !saved.value && !window.confirm('开始修正会替换当前未保存的批量输入，是否继续？')) return
  await reset(); correction.value = { document, index }; historyDocument.value = undefined; historyOpen.value = false
  text.value = item.input.primarySku; lines.value = [{ sku: item.input.primarySku, selected: true, error: '' }]; active.value = true; showEditor.value = true; running.value = true
  const token = ++generation, account = currentAuthUser.value.account
  try {
    const deadline = Date.now() + 45000
    while (!disposed && !engine.value?.batchReady && Date.now() < deadline) { await nextTick(); await new Promise(resolve => window.setTimeout(resolve, 100)) }
    if (disposed || token !== generation || account !== currentAuthUser.value.account) return
    if (!engine.value?.batchReady) throw new Error('修正工作区加载超时，请重新打开记录后重试')
    const source = normalizeQuotationRecord({ ...item.input, id: document.id, no: document.no })
    if (!source) throw new Error('原报价条件不完整，请核对')
    const fresh = await engine.value.calculateBatchItem(item.input.primarySku, quotationReissuePayload(source), item.input.customerQuote!.quantities)
    if (disposed || token !== generation || account !== currentAuthUser.value.account) return
    lines.value[0]!.item = fresh; showEditor.value = false; progress.value = '已按当前资料重新查询，请核对修正前后的报价后提交'
  } catch (e) { if (!disposed && token === generation && account === currentAuthUser.value.account) { error.value = e instanceof Error ? e.message : '重新查询失败'; if (lines.value[0]) lines.value[0].error = error.value } }
  finally { if (token === generation) running.value = false }
}
async function reset() { generation++; active.value = false; await nextTick(); lines.value = []; correction.value = undefined; inputExpanded.value = true; progress.value = ''; processingPage.value = 0; saved.value = undefined; error.value = ''; engineFailed.value = false }
onBeforeUnmount(() => { disposed = true; generation++; historyGeneration++ })
</script>
<template>
  <main class="batch-page">
    <header><div><h1>批量单品 SKU 报价</h1><p>多个 SKU 独立计价，合并生成一张客户报价单</p></div><nav><RouterLink to="/quotation">返回普通报价</RouterLink><button v-if="hasPermission('myRecords') || hasPermission('allRecords')" @click="history">批量报价记录</button></nav></header>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <section v-if="historyOpen" class="card">
      <header><h2>批量报价记录</h2><button @click="historyOpen=false; historyGeneration++">关闭记录</button></header>
      <select v-if="hasPermission('allRecords')" v-model="scope" @change="page=0; history()"><option value="mine">我的批量报价</option><option value="company">全部批量报价</option></select>
      <p v-if="historyBusy" role="status">正在读取…</p>
      <table v-else><thead><tr><th>报价单号</th><th>客户</th><th>SKU数</th><th>报价人</th><th>保存时间</th><th>审核状态</th><th>操作</th></tr></thead><tbody><tr v-for="record in records.items" :key="record.id"><td>{{ record.no }}</td><td>{{ record.customerName }}</td><td>{{ record.skuCount }}</td><td>{{ record.salespersonName }}</td><td>{{ new Date(record.createdAt).toLocaleString('zh-CN', { hour12: false }) }}</td><td>{{ batchReviewLabels[record.reviewStatus || 'pending'] }}<small v-if="record.revision"> · 修订 {{ record.revision }}</small></td><td><button class="primary" @click="openRecord(record.id)">查看 SKU 明细</button></td></tr></tbody></table>
      <p v-if="!historyBusy && !records.total">暂无批量报价记录</p>
      <div class="actions"><button :disabled="page===0 || historyBusy" @click="page--; history()">上一页</button><span>共 {{ records.total }} 张 · 第 {{ page+1 }} 页</span><button :disabled="(page+1)*20>=records.total || historyBusy" @click="page++; history()">下一页</button></div>
      <BatchSingleRecordDetail v-if="historyDocument" :document="historyDocument" @close="historyDocument=undefined" @updated="recordUpdated" @correct="beginCorrection" />
    </section>
    <template v-if="hasPermission('quote')"><section v-if="correction" class="card correction-summary"><h2>修正退回商品 · {{ correction.document.no }}</h2><p>仅更新 {{ correction.document.items[correction.index]?.input.primarySku }}；保留原客户、等级、佣金和数量列，其余SKU不变。</p><p>退回原因：{{ correction.document.review?.items[correction.index]?.reason }}</p><details><summary>查看修正前报价</summary><BatchSingleSheet :sheet="correction.document.items[correction.index]!.sheet" read-only /></details></section>
      <section class="card input-card">
        <header><h2>01 批量录入 SKU</h2><span>每行一个 SKU · 最多 50 个</span></header>
        <div class="input-row"><textarea v-if="!active || inputExpanded" v-model="text" :disabled="active || running || saving" placeholder="从 Excel 粘贴 SKU，每行一个" aria-label="批量SKU清单" rows="3" /><div v-else class="input-summary"><b>{{ lines.length }} 个 SKU</b><span>{{ lines.slice(0,3).map(line => line.sku).join(' / ') }}{{ lines.length>3?' / …':'' }}</span></div><div class="input-buttons"><button v-if="active" :disabled="running || saving" @click="inputExpanded=!inputExpanded">{{ inputExpanded ? '收起清单' : '查看输入清单' }}</button><button v-if="!correction" class="primary" :disabled="running || saving || !!saved" @click="prepare">{{ running ? '处理中…' : active ? '重新查询 SKU' : '查询 SKU' }}</button><button v-if="active" :disabled="running || saving" @click="reset">重新录入</button></div></div>
        <p class="input-hint">先查询商品资料，再设置客户和渠道生成整批报价。保存后可在“批量报价记录”查看。</p>
      </section>
      <section v-if="lines.length" class="card processing-card"><header><h2>02 SKU 处理清单</h2><p role="status">{{ progress }}</p><button class="primary" :disabled="running || saving || !selected.length" @click="goToConditions">设置条件与渠道 ↓</button></header><div ref="processingScroll" class="processing-scroll"><table><thead><tr><th>选入</th><th>SKU</th><th>状态</th><th>明细数</th></tr></thead><tbody><tr v-for="{ line, index } in visibleLines" :key="index"><td><input v-model="line.selected" type="checkbox" :aria-label="`选入 ${line.sku} 第${index+1}行`" :disabled="running || saving || !!saved"></td><td>{{ line.sku }}</td><td :class="{error:line.error}">{{ line.error || (line.item ? '报价已生成' : line.queried ? '资料已查询 · 待设置报价' : '待查询') }}</td><td>{{ line.item?.sheet.rows.length ?? '—' }}</td></tr></tbody></table></div><nav class="processing-pagination" aria-label="SKU处理清单分页"><span>共 {{ lines.length }} 个SKU · 已选 {{ selected.length }} 个</span><label>每页 <select v-model.number="processingSize" aria-label="每页处理SKU数"><option :value="10">10</option><option :value="20">20</option><option :value="50">50</option></select> 个</label><div><button :disabled="processingPage===0" @click="processingPage--">上一页</button><span>{{ processingPage+1 }} / {{ processingPages }}</span><button :disabled="processingPage+1>=processingPages" @click="processingPage++">下一页</button></div></nav><p v-if="lines.some(l=>l.error)">异常项不会自动保存。可以调整条件重新生成，或明确取消勾选异常项后保存其余商品。</p></section>
      <section v-if="active && !engineFailed" v-show="showEditor" ref="editorSection" class="editor card" :inert="running || saving || undefined"><h2>03 设置公共条件与渠道</h2><p>当前已选 {{ selected.length }} 个 SKU，以下公共条件统一应用。点击“加载渠道”后选择报价方案，再点击“应用条件并生成批量报价”；系统会按各 SKU 的成本和重量分别计算。</p><Workbench ref="engine" batch-capture :batch-initial-sku="selected[0]?.sku" @batch-captured="captured" /></section>
      <section v-if="active && !showEditor" class="card"><h2>04 合并报价单</h2><p>已选 {{ selected.length }} 个 SKU · {{ combined ? '将保存1张报价单' : '请处理异常项或取消选入' }}</p><BatchSingleSheet v-if="combined" :sheet="combined.sheet" /><p v-if="saved" class="success" role="status">已保存：{{ saved.no }}，包含 {{ saved.items.length }} 个 SKU</p><div class="actions"><button :disabled="running || saving" @click="edit">修改条件 / 重新生成</button><button class="primary" :disabled="!combined || running || saving || !!saved" @click="save">{{ saving ? '正在校验并保存…' : saved ? '已保存' : correction ? '提交修正并重新审核' : `保存 1 张报价单 · ${selected.length}个 SKU` }}</button></div></section>
    </template>
  </main>
</template>
<style scoped>
.input-card{padding:12px 18px}.input-card header{justify-content:flex-start}.input-card header h2,.processing-card header h2{margin:0}.input-card header span{font-size:12px;color:#738190}.input-card header button{margin-left:auto}.input-row{display:flex;align-items:center;gap:14px;margin-top:10px}.input-row textarea{flex:1;max-width:none;min-width:0;resize:vertical;min-height:64px;max-height:180px}.input-buttons{display:flex;gap:8px;flex-shrink:0}.input-summary{display:flex;gap:14px;align-items:center;flex:1;min-width:0;font-size:13px}.input-summary span{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:#657889}.input-hint{margin:8px 0 0}.processing-card header{margin-bottom:10px}.processing-card header p{margin:0;flex:1}.processing-card td,.processing-card th{padding:8px 12px}.editor{scroll-margin-top:16px}@media(max-width:700px){.input-row{flex-wrap:wrap}.input-row textarea{flex-basis:100%}.input-summary{flex-basis:100%}.processing-card header p{flex-basis:100%}.processing-pagination{gap:8px}.input-card header{gap:6px}}

.processing-scroll{max-height:420px;overflow:auto;overscroll-behavior:auto}.processing-scroll th{position:sticky;top:0;z-index:1}.processing-pagination{display:flex;gap:12px;margin-top:12px;font-size:13px}.processing-pagination div{display:flex;align-items:center;gap:12px}.processing-pagination select{padding:8px;border:1px solid #ccd7e3;border-radius:5px}.batch-page{padding:22px;background:#f4f6f8;color:#1b2935;min-height:80vh}header,.actions,nav{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap}h1{font-size:24px;margin:0}h2{font-size:17px;margin:0 0 12px}p,.actions span{font-size:13px;color:#687786}.card{background:white;border:1px solid #e0e5eb;border-radius:10px;padding:18px;margin:16px 0;overflow:auto}.card h2{border-left:4px solid #f90;padding-left:10px}textarea{width:100%;max-width:650px;padding:10px;box-sizing:border-box;border:1px solid #d0d9e1;border-radius:6px}button{padding:10px 14px;border:1px solid #d2dbe1;border-radius:6px;background:white;cursor:pointer}.primary{background:#ff9400;border-color:#ff9400;color:#17232d;font-weight:700}button:disabled{opacity:.45;cursor:default}table{width:100%;border-collapse:collapse;font-size:13px;text-align:left}th,td{padding:12px;border-bottom:1px solid #e4e8ee}th{background:#eef5fc}.error{color:#b23d27;background:#fff4f0;padding:10px}.success{color:#16774c}.actions{margin-top:12px;justify-content:flex-end}.editor{padding:12px}.editor :deep(.quotation-page){padding:0}.editor :deep(.quotation-page > .draft-status-bar){display:none}@media(max-width:700px){.batch-page{padding:10px}.card{padding:12px}}
</style>
