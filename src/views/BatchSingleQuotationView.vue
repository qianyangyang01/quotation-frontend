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
import BatchQuotationProgress from '@/components/quotation/BatchQuotationProgress.vue'
import BatchSingleSheetEditor from '@/components/quotation/BatchSingleSheetEditor.vue'
import { batchLineState, batchQueryLabels, batchQueryFailure, batchFollowupText, deferredBatchLines } from '@/services/batchSingleReadiness'

const route = useRoute()
onMounted(() => { if (route.query.records) void history() })
const Workbench = defineAsyncComponent(() => import('./QuotationSystemView.vue'))
const engine = ref<InstanceType<typeof import('./QuotationSystemView.vue').default>>()
const text = ref(''), lines = ref<BatchSingleLine[]>([]), error = ref(''), progress = ref(''), active = ref(false)
const running = ref(false), saving = ref(false), engineFailed = ref(false), showEditor = ref(true)
const validating = ref(false), generating = ref(false), stopping = ref(false), stopped = ref(false), currentSku = ref('')
const seed = ref<BatchSingleItem>(), resultSection = ref<HTMLElement>()
const batchBusy = computed(() => validating.value || generating.value)
const sheetEditor = ref<InstanceType<typeof BatchSingleSheetEditor>>(), sheetBusy = ref(false)
const locked = computed(() => running.value || saving.value || validating.value || sheetBusy.value)
const saved = ref<BatchSingleDocument>(), historyDocument = ref<BatchSingleDocument>()
const correction = ref<{ document: BatchSingleDocument; index: number }>()
const inputExpanded = ref(true), editorSection = ref<HTMLElement>()
const processingPage = ref(0), processingSize = ref(10), processingScroll = ref<HTMLElement>()
const followupMessage = ref('')
const readyLines = computed(() => lines.value.filter(line => line.queried && !line.error && !line.deferred))
const followupLines = computed(() => deferredBatchLines(lines.value))
const indexedLines = computed(() => lines.value.map((line, index) => ({ line, index })))
const processingPages = computed(() => Math.max(1, Math.ceil(indexedLines.value.length / processingSize.value)))
const visibleLines = computed(() => indexedLines.value.slice(processingPage.value * processingSize.value, (processingPage.value + 1) * processingSize.value))
watch(processingSize, () => { processingPage.value = 0 })
watch(processingPages, count => { processingPage.value = Math.min(processingPage.value, count - 1) })
watch(processingPage, () => { processingScroll.value?.scrollTo?.({ top: 0 }) })
const records = ref<Awaited<ReturnType<typeof loadBatchSingleRecords>>>({ items: [], total: 0 })
const historyOpen = ref(false), historyBusy = ref(false), page = ref(0), scope = ref('mine')
let disposed = false, generation = 0, lastRequest = '', saveKey = '', historyGeneration = 0
const selected = computed(() => lines.value.filter(line => line.selected))
const completed = computed(() => selected.value.filter(line => line.item && !line.error))
const unfinished = computed(() => selected.value.filter(line => !line.item || line.error))
const partial = computed(() => {
  if (!completed.value.length) return undefined
  try { return mergeBatchSingle(completed.value.map(line => line.item!)) } catch { return undefined }
})
const countryCount = computed(() => new Set(partial.value?.sheet.rows.map(row => row.country)).size)
const tierLabel = computed(() => ({ '10': '阶梯价1', '100': '阶梯价2', '100+': '阶梯价3' })[seed.value?.conditions.monthlySalesEstimate || ''] || '—')
const combined = computed(() => {
  if (!selected.value.length || selected.value.some(line => !line.item || line.error)) return undefined
  try { return mergeBatchSingle(selected.value.map(line => line.item!)) } catch { return undefined }
})
onErrorCaptured(e => { engineFailed.value = true; running.value = false; generating.value = false; validating.value = false; generation++; error.value = `批量工作区异常：${e.message}。可重新打开此功能，普通报价仍可使用。`; return false })
async function prepare() {
  if (locked.value) return
  error.value = ''; saved.value = undefined; correction.value = undefined; seed.value = undefined; stopped.value = false; processingPage.value = 0; followupMessage.value = ''; sheetBusy.value = false
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
        if (!purchase.quoteReady) { line.queryState = 'incomplete'; line.error = purchaseQuoteBlockingMessage(purchase) || '采购资料不完整'; line.selected = false; continue }
        line.queried = true; line.queryState = 'ready'; first ??= purchase
      } catch (e) { if (disposed || token !== generation || account !== currentAuthUser.value.account) return; line.queryState = batchQueryFailure(e); line.error = e instanceof Error ? e.message : '查询失败，可重新查询'; line.selected = false }
    }
    progress.value = `查询完成：${lines.value.filter(line => line.queried).length} 个可报价，${lines.value.filter(line => line.error).length} 个异常`
    if (first) {
      const deadline = Date.now() + 45000
      while (!disposed && token === generation && !engine.value?.batchReady && !engineFailed.value && Date.now() < deadline) { await nextTick(); await new Promise(resolve => window.setTimeout(resolve, 100)) }
      if (disposed || token !== generation || account !== currentAuthUser.value.account) return
      if (!engine.value?.batchReady) throw new Error('报价条件加载失败或超时，请重新查询')
      engine.value.primeBatchProduct(first)
    }
  } catch (e) { if (token === generation && account === currentAuthUser.value.account) error.value = e instanceof Error ? e.message : '查询失败，请重试' }
  finally { if (token === generation) running.value = false }
}
async function copyFollowup() {
  try { await navigator.clipboard.writeText(batchFollowupText(lines.value, true)); followupMessage.value = '已复制异常 SKU，下次可直接粘贴查询。' }
  catch { followupMessage.value = '复制失败，请重试或从下方清单手动复制异常 SKU。' }
}
async function goToConditions() { if (locked.value) return; await edit(); await nextTick(); editorSection.value?.scrollIntoView?.({ behavior: 'smooth', block: 'start' }) }
function preparing(value: boolean) {
  validating.value = value
  if (value) {
    // Invalidate previous prices before the user's new conditions are captured.
    for (const line of lines.value) { line.item = undefined; if (line.queried) line.error = '' }
    seed.value = undefined; stopped.value = false
  }
}
async function captured(first: BatchSingleItem) {
  if (running.value || saving.value) return
  if (!selected.value.some(line => line.sku === first.input.primarySku)) { error.value = 'SKU勾选范围已变化，请重新加载渠道后生成批量报价'; return }
  await generate(first)
}
async function generate(first: BatchSingleItem, retry = false) {
  const token = ++generation, account = currentAuthUser.value.account
  const candidates = [...(retry ? unfinished.value : selected.value)], quantities = first.input.customerQuote!.quantities
  if (!candidates.length) return
  const fresh = () => !disposed && token === generation && account === currentAuthUser.value.account
  seed.value = JSON.parse(JSON.stringify(first))
  running.value = true; generating.value = true; stopping.value = false; stopped.value = false
  error.value = ''; saved.value = undefined; currentSku.value = candidates[0]!.sku
  for (const line of candidates) { line.item = undefined; line.error = '' }
  showEditor.value = false
  try {
    await nextTick()
    resultSection.value?.scrollIntoView?.({ behavior: 'instant', block: 'start' })
    await new Promise(resolve => window.setTimeout(resolve, 0)) // Let the first capture release its save lock.
    for (let i = 0; i < candidates.length; i++) {
      if (!fresh()) return
      if (stopping.value) break
      const line = candidates[i]!; currentSku.value = line.sku; progress.value = `正在计算 ${i+1}/${candidates.length} · ${line.sku}`
      try {
        const item = !retry && line.sku === first.input.primarySku ? first : await engine.value!.calculateBatchItem(line.sku, first.conditions, quantities)
        if (!fresh()) return
        line.item = item
      } catch (e) { if (!fresh()) return; line.error = e instanceof Error ? e.message : '查询失败，请重试' }
    }
    stopped.value = stopping.value && unfinished.value.some(line => !line.error)
    progress.value = `${stopped.value ? '已停止' : '已完成'}：${completed.value.length}个成功，${unfinished.value.length}个待处理`
    if (!combined.value && !unfinished.value.length) error.value = '各 SKU 的客户、汇率或数量列不一致，请统一重新生成'
  } finally { if (token === generation) { running.value = false; generating.value = false; stopping.value = false; currentSku.value = '' } }
}
function stop() { if (generating.value) stopping.value = true }
async function retryUnfinished() { if (!locked.value && seed.value && !engineFailed.value) await generate(seed.value, true) }
async function edit() {
  if (locked.value) return
  saved.value = undefined; stopped.value = false
  await engine.value?.restoreBatchSample()
  showEditor.value = true
  // A changed common condition must never be combined with earlier successful prices.
  for (const line of lines.value) line.item = undefined
}
async function save() {
  if (locked.value || !combined.value || saved.value || showEditor.value) return
  const token = generation, account = currentAuthUser.value.account
  const fresh = () => !disposed && token === generation && account === currentAuthUser.value.account
  saving.value = true; error.value = ''
  try {
    if (!sheetEditor.value) throw new Error('报价单编辑器尚未准备好，请稍后保存')
    const input = await sheetEditor.value.capture()
    if (!fresh()) return
    const signature = JSON.stringify({ input, correction: correction.value })
    if (signature !== lastRequest) { lastRequest = signature; saveKey = idempotencyKey('batch-single') }
    const target = correction.value; const result = target ? await correctBatchSingle(target.document, target.index, input, saveKey) : await saveBatchSingle(input, saveKey); if (fresh()) { saved.value = result; if (target) { historyOpen.value = true; historyDocument.value = result; correction.value = undefined } }
  }
  catch (e) { if (fresh()) error.value = e instanceof Error ? e.message : '保存失败，可重试；同一请求不会重复生成报价单' }
  finally { if (fresh()) saving.value = false }
}
async function calculateSheetItem(sku: string, quantities: number[]) {
  if (!engine.value || !seed.value) throw new Error('报价计算工作区尚未准备好')
  return engine.value.calculateBatchItem(sku, seed.value.conditions, quantities)
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
  if (!document || !item || locked.value) return
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
    generating.value = true; currentSku.value = item.input.primarySku; showEditor.value = false
    const fresh = await engine.value.calculateBatchItem(item.input.primarySku, quotationReissuePayload(source), item.input.customerQuote!.quantities)
    if (disposed || token !== generation || account !== currentAuthUser.value.account) return
    lines.value[0]!.item = fresh; seed.value = fresh; showEditor.value = false; progress.value = '已按当前资料重新查询，请核对修正前后的报价后提交'
  } catch (e) { if (!disposed && token === generation && account === currentAuthUser.value.account) { showEditor.value = true; error.value = e instanceof Error ? e.message : '重新查询失败'; if (lines.value[0]) lines.value[0].error = error.value } }
  finally { if (token === generation) { running.value = false; generating.value = false; stopping.value = false; currentSku.value = '' } }
}
async function reset() { generation++; active.value = false; running.value = false; saving.value = false; validating.value = false; generating.value = false; stopping.value = false; stopped.value = false; seed.value = undefined; await nextTick(); lines.value = []; correction.value = undefined; inputExpanded.value = true; progress.value = ''; processingPage.value = 0; saved.value = undefined; error.value = ''; engineFailed.value = false; followupMessage.value = ''; sheetBusy.value = false }
watch(() => currentAuthUser.value.account, () => { historyGeneration++; historyOpen.value = false; historyDocument.value = undefined; records.value = { items: [], total: 0 }; void reset() })
onBeforeUnmount(() => { disposed = true; generation++; historyGeneration++ })
</script>
<template>
  <main class="batch-page">
    <header><div><h1>批量单品 SKU 报价</h1><p>多个 SKU 独立计价，合并生成一张客户报价单</p></div><nav><RouterLink to="/quotation">返回普通报价</RouterLink><RouterLink v-if="hasPermission('myRecords') || hasPermission('allRecords')" to="/quotation/my-records">报价记录</RouterLink><button v-if="hasPermission('myRecords') || hasPermission('allRecords')" @click="history">历史批量记录</button></nav></header>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <section v-if="historyOpen" class="card">
      <header><h2>历史批量记录</h2><button @click="historyOpen=false; historyGeneration++">关闭记录</button></header>
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
        <div class="input-row"><textarea v-if="!active || inputExpanded" v-model="text" :disabled="active || running || saving" placeholder="从 Excel 粘贴 SKU，每行一个" aria-label="批量SKU清单" rows="3" /><div v-else class="input-summary"><b>{{ lines.length }} 个 SKU</b><span>{{ lines.slice(0,3).map(line => line.sku).join(' / ') }}{{ lines.length>3?' / …':'' }}</span></div><div class="input-buttons"><button v-if="active" :disabled="locked" @click="inputExpanded=!inputExpanded">{{ inputExpanded ? '收起清单' : '查看输入清单' }}</button><button v-if="!correction" class="primary" :disabled="locked || !!saved" @click="prepare">{{ running ? '处理中…' : active ? '重新查询 SKU' : '查询 SKU' }}</button><button v-if="active" :disabled="locked" @click="reset">重新录入</button></div></div>
        <p class="input-hint">先查询商品资料，再设置客户和渠道生成整批报价。一次保存，每个 SKU 生成一条独立记录，在“报价记录”按原流程审核。</p>
      </section>
      <section v-if="lines.length" class="card readiness-card">
        <div class="readiness-summary"><div><h2>查询结果</h2><p>共 {{ lines.length }} 个 SKU · 可继续报价 {{ readyLines.length }} 个 · 异常 {{ followupLines.length }} 个</p><p v-if="followupLines.length">资料异常的 SKU 已默认不勾选，可报价的 SKU 直接继续报价。</p></div><button v-if="followupLines.length" :disabled="locked" @click="copyFollowup">复制异常 SKU</button></div>
        <p v-if="followupMessage" role="status">{{ followupMessage }}</p>
      </section><section v-if="lines.length" class="card processing-card"><details :open="showEditor && !batchBusy"><summary>SKU 处理清单 · 已选 {{ selected.length }} 个 <span>{{ progress }}</span></summary><header><h2>02 SKU 处理清单</h2><p role="status">{{ progress }}</p><button class="primary" :disabled="locked || !selected.length" @click="goToConditions">设置条件与渠道 ↓</button></header><div ref="processingScroll" class="processing-scroll"><table><thead><tr><th>选入</th><th>SKU</th><th>状态</th><th>明细数</th></tr></thead><tbody><tr v-for="{ line, index } in visibleLines" :key="index"><td><input v-model="line.selected" type="checkbox" :aria-label="`选入 ${line.sku} 第${index+1}行`" :disabled="locked || !!saved || !line.queried"></td><td>{{ line.sku }}</td><td :class="{error:line.error}"><strong>{{ line.item && !line.error ? '报价已生成' : line.queried && line.error ? '报价异常，待处理' : batchQueryLabels[batchLineState(line)] }}</strong><small class="line-reason">{{ line.error || (line.deferred ? '已暂缓，可下次再报价' : line.queried ? '采购资料齐全，待按所选渠道校验报价' : '') }}</small></td><td>{{ line.item?.sheet.rows.length ?? '—' }}</td></tr></tbody></table></div><nav class="processing-pagination" aria-label="SKU处理清单分页"><span>共 {{ lines.length }} 个SKU · 已选 {{ selected.length }} 个</span><label>每页 <select v-model.number="processingSize" aria-label="每页处理SKU数"><option :value="10">10</option><option :value="20">20</option><option :value="50">50</option></select> 个</label><div><button :disabled="processingPage===0" @click="processingPage--">上一页</button><span>{{ processingPage+1 }} / {{ processingPages }}</span><button :disabled="processingPage+1>=processingPages" @click="processingPage++">下一页</button></div></nav><p v-if="lines.some(l=>l.error)">{{ lines.some(line => line.queried && line.error) ? '生成报价时出现异常的 SKU 可重试，或取消勾选后保存其余商品。' : '资料异常的 SKU 已自动排除，可复制后下次再查询报价。' }}</p></details></section>
      <section v-if="active && !engineFailed" v-show="showEditor && !batchBusy" ref="editorSection" class="editor card" :inert="locked || undefined">
        <h2>02 设置公共条件与渠道</h2><p>当前已选 {{ selected.length }} 个 SKU，以下公共条件统一应用。加载渠道并选择方案后，点击“生成整批报价”；系统会按各 SKU 的成本和重量分别计算。</p>
        <Workbench ref="engine" batch-capture :batch-size="selected.length" :batch-skus="selected.map(line => line.sku)" @batch-sample-busy="sheetBusy=$event" :batch-initial-sku="selected[0]?.sku" @batch-captured="captured" @batch-preparing="preparing" />
      </section>
      <section v-if="active && seed && (!showEditor || batchBusy)" class="card common-summary">
        <h2>02 公共条件与渠道</h2><div class="condition-grid">
          <div><small>客户名称</small><b>{{ seed.input.customerName }}</b></div><div><small>物流属性</small><b>{{ seed.conditions.logisticsAttribute || '—' }}</b></div><div><small>客户等级</small><b>{{ seed.input.customerGrade }}</b></div><div><small>采购阶梯</small><b>{{ tierLabel }}</b></div><div><small>佣金阈值</small><b>{{ seed.input.commissionThreshold }}</b></div><div><small>报价方案</small><b>{{ seed.conditions.activeTemplate?.name || '自选渠道' }} · {{ seed.sheet.rows.length }} 条渠道</b></div>
        </div>
      </section>
      <div v-if="active && (batchBusy || !showEditor)" ref="resultSection" class="result-section">
        <BatchQuotationProgress v-if="batchBusy" :lines="selected" :current-sku="currentSku" :validating="validating && !generating" :stopping="stopping" @stop="stop" />
        <section v-else class="card result-card">
          <header class="result-heading"><h2>03 合并报价单</h2><span :class="{ success: combined }">{{ saved ? '已保存' : stopped ? '已停止 · 已完成结果已保留' : combined ? '生成完成 · 尚未保存' : '存在待处理项 · 尚未保存' }}</span></header>
          <div class="result-body">
            <div class="result-stats"><span><b>{{ combined ? 1 : 0 }}</b> 张完整报价单</span><span><b>{{ completed.length }} / {{ selected.length }}</b> 个 SKU 已完成</span><span><b>{{ partial?.sheet.rows.length || 0 }}</b> 行明细</span><span><b>{{ countryCount }}</b> 个国家</span></div>
            <div v-if="unfinished.length" class="pending-panel"><strong>{{ unfinished.length }} 个 SKU 待处理，当前只展示成功结果，尚不能整批保存</strong><ul><li v-for="line in unfinished" :key="line.sku"><b>{{ line.sku }}</b><span>{{ line.error || '尚未计算' }}</span></li></ul><button :disabled="locked || !seed || engineFailed" @click="retryUnfinished">{{ stopped ? '继续处理未完成项' : '仅重试失败 / 未完成项' }}</button><p>也可展开上方 SKU 处理清单，明确取消选入待处理项后保存其余商品。</p></div>
            <div v-if="partial && (!combined || saved)" class="quote-meta"><strong>{{ (saved?.sheet || partial.sheet).title || 'JerryFulfillment Quote Sheet' }}</strong><span>客户：{{ partial.customerName }}</span><span>By Agent：{{ (saved?.sheet || partial.sheet).agent }}</span><span>Date：{{ (saved?.sheet || partial.sheet).date }}</span></div>
            <div v-if="combined && !saved" :inert="saving || undefined"><BatchSingleSheetEditor :key="JSON.stringify(combined)" ref="sheetEditor" :document="combined" :calculate-item="calculateSheetItem" @busy="sheetBusy=$event" /></div><BatchSingleSheet v-else-if="partial" :sheet="saved?.sheet || partial.sheet" :photos="saved?.items[0]?.input.customerQuote?.showPhotos === false ? [] : saved?.items[0]?.input.customerQuote?.photos" :read-only="!combined" paginated />
            <p v-if="saved" class="success" role="status"><template v-if="saved.createdRecords">已生成 {{ saved.createdRecords.length }} 条独立报价记录，按原流程逐条审核。批次：{{ saved.no }}。<RouterLink :to="{ path: '/quotation/my-records', query: { batch: saved.no } }">查看本批报价记录</RouterLink></template><template v-else>已保存历史批次修正：{{ saved.no }}</template></p>
          </div>
          <footer class="result-footer"><span :class="{ success: combined }">{{ combined ? '所有选中 SKU 已完成，核对后一次保存为独立报价记录' : '请先处理失败或未完成的 SKU' }}</span><div><button :disabled="locked" @click="goToConditions">修改条件 / 重新生成</button><button class="primary" :disabled="!combined || locked || !!saved" @click="save">{{ saving ? '正在校验并保存…' : saved ? '已保存' : correction ? '提交修正并重新审核' : `保存 ${selected.length} 条报价记录` }}</button></div></footer>
        </section>
      </div>
    </template>
  </main>
</template>
<style scoped>
.line-reason{display:block;margin-top:5px;color:#7d6f61;white-space:normal}.readiness-summary{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap}.readiness-summary p:last-child{margin-bottom:0}.readiness-summary>button{flex-shrink:0}
.card.result-card{padding:0;border-color:#d9d1f4}.result-card .result-heading h2{margin:0;border:0;padding:0}.result-card .result-heading>span.success{color:#16774c}
.processing-card summary{cursor:pointer;font-size:13px;font-weight:600}.processing-card summary>span{font-weight:400;color:#71869a;margin-left:20px}.processing-card details[open]>summary{margin-bottom:15px}.condition-grid{display:grid;grid-template-columns:1fr .8fr .8fr .8fr .7fr 2fr;gap:20px}.condition-grid small{display:block;color:#8092a5;font-size:12px;margin-bottom:8px}.condition-grid b{font-size:13px;font-weight:500}.result-section{scroll-margin-top:20px}.result-card{padding:0;border-color:#d9d1f4}.result-heading{padding:16px 20px;background:#f3effc}.result-heading h2{margin:0;border:0;padding:0}.result-heading>span{font-size:12px;color:#9a792d}.result-body{padding:0 20px}.result-stats{display:flex;gap:28px;flex-wrap:wrap;border-bottom:1px solid #e5eaf0;padding:18px 0;color:#7c8fa3;font-size:12px}.result-stats b{font-size:22px;color:#18334c;margin-right:5px}.quote-meta{display:flex;align-items:center;gap:24px;flex-wrap:wrap;padding-top:18px;font-size:12px;color:#7c8fa3}.quote-meta strong{margin-right:auto;color:#18334c;font-size:15px}.pending-panel{background:#fff8ed;border:1px solid #edd1a4;border-radius:6px;padding:16px;margin-top:16px;font-size:13px;color:#805c2d}.pending-panel ul{max-height:180px;overflow:auto;padding-left:20px}.pending-panel li{padding:5px}.pending-panel li span{margin-left:16px}.result-footer{display:flex;align-items:center;justify-content:space-between;gap:16px;padding:16px 20px;border-top:1px solid #e5eaf0;background:#fcfcfe}.result-footer>span{font-size:12px;color:#8496a8}.result-footer>div{display:flex;gap:10px;flex-wrap:wrap}@media(max-width:900px){.condition-grid{grid-template-columns:repeat(3,minmax(0,1fr))}.result-footer{flex-wrap:wrap}}@media(max-width:600px){.condition-grid{grid-template-columns:repeat(2,minmax(0,1fr))}.result-body{padding:0 12px}.result-stats{gap:14px}.processing-card summary>span{display:block;margin:8px 0}}

.input-card{padding:12px 18px}.input-card header{justify-content:flex-start}.input-card header h2,.processing-card header h2{margin:0}.input-card header span{font-size:12px;color:#738190}.input-card header button{margin-left:auto}.input-row{display:flex;align-items:center;gap:14px;margin-top:10px}.input-row textarea{flex:1;max-width:none;min-width:0;resize:vertical;min-height:64px;max-height:180px}.input-buttons{display:flex;gap:8px;flex-shrink:0}.input-summary{display:flex;gap:14px;align-items:center;flex:1;min-width:0;font-size:13px}.input-summary span{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:#657889}.input-hint{margin:8px 0 0}.processing-card header{margin-bottom:10px}.processing-card header p{margin:0;flex:1}.processing-card td,.processing-card th{padding:8px 12px}.editor{scroll-margin-top:16px}@media(max-width:700px){.input-row{flex-wrap:wrap}.input-row textarea{flex-basis:100%}.input-summary{flex-basis:100%}.processing-card header p{flex-basis:100%}.processing-pagination{gap:8px}.input-card header{gap:6px}}

.processing-scroll{max-height:420px;overflow:auto;overscroll-behavior:auto}.processing-scroll th{position:sticky;top:0;z-index:1}.processing-pagination{display:flex;gap:12px;margin-top:12px;font-size:13px}.processing-pagination div{display:flex;align-items:center;gap:12px}.processing-pagination select{padding:8px;border:1px solid #ccd7e3;border-radius:5px}.batch-page{padding:22px;background:#f4f6f8;color:#1b2935;min-height:80vh}header,.actions,nav{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap}h1{font-size:24px;margin:0}h2{font-size:17px;margin:0 0 12px}p,.actions span{font-size:13px;color:#687786}.card{background:white;border:1px solid #e0e5eb;border-radius:10px;padding:18px;margin:16px 0;overflow:auto}.card h2{border-left:4px solid #f90;padding-left:10px}textarea{width:100%;max-width:650px;padding:10px;box-sizing:border-box;border:1px solid #d0d9e1;border-radius:6px}button{padding:10px 14px;border:1px solid #d2dbe1;border-radius:6px;background:white;cursor:pointer}.primary{background:#ff9400;border-color:#ff9400;color:#17232d;font-weight:700}button:disabled{opacity:.45;cursor:default}table{width:100%;border-collapse:collapse;font-size:13px;text-align:left}th,td{padding:12px;border-bottom:1px solid #e4e8ee}th{background:#eef5fc}.error{color:#b23d27;background:#fff4f0;padding:10px}.success{color:#16774c}.actions{margin-top:12px;justify-content:flex-end}.editor{padding:12px}.editor :deep(.quotation-page){padding:0}.editor :deep(.quotation-page > .draft-status-bar){display:none}@media(max-width:700px){.batch-page{padding:10px}.card{padding:12px}}
</style>
