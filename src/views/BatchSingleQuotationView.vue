<script setup lang="ts">
import { computed, defineAsyncComponent, nextTick, onMounted, onBeforeUnmount, onErrorCaptured, ref } from 'vue'
import { useRoute } from 'vue-router'
import { hasPermission } from '@/data/authStore'
import { idempotencyKey } from '@/services/http'
import { loadBatchSingleRecord, loadBatchSingleRecords, mergeBatchSingle, parseBatchSingleSkus, saveBatchSingle, type BatchSingleDocument, type BatchSingleItem, type BatchSingleLine } from '@/services/batchSingleQuotation'
import BatchSingleSheet from '@/components/quotation/BatchSingleSheet.vue'
import BatchSingleRecordDetail from '@/components/quotation/BatchSingleRecordDetail.vue'

const route = useRoute()
onMounted(() => { if (route.query.records) void history() })
const Workbench = defineAsyncComponent(() => import('./QuotationSystemView.vue'))
const engine = ref<InstanceType<typeof import('./QuotationSystemView.vue').default>>()
const text = ref(''), lines = ref<BatchSingleLine[]>([]), error = ref(''), progress = ref(''), active = ref(false)
const running = ref(false), saving = ref(false), engineFailed = ref(false), showEditor = ref(true)
const saved = ref<BatchSingleDocument>(), historyDocument = ref<BatchSingleDocument>()
const records = ref<Awaited<ReturnType<typeof loadBatchSingleRecords>>>({ items: [], total: 0 })
const historyOpen = ref(false), historyBusy = ref(false), page = ref(0), scope = ref('mine')
let disposed = false, generation = 0, lastRequest = '', saveKey = '', historyGeneration = 0
const selected = computed(() => lines.value.filter(line => line.selected))
const combined = computed(() => {
  if (!selected.value.length || selected.value.some(line => !line.item || line.error)) return undefined
  try { return mergeBatchSingle(selected.value.map(line => line.item!)) } catch { return undefined }
})
onErrorCaptured(e => { engineFailed.value = true; running.value = false; generation++; error.value = `批量工作区异常：${e.message}。可重新打开此功能，普通报价仍可使用。`; return false })
function prepare() {
  error.value = ''; saved.value = undefined
  try { lines.value = parseBatchSingleSkus(text.value); active.value = selected.value.length > 0; showEditor.value = true; engineFailed.value = false }
  catch (e) { error.value = e instanceof Error ? e.message : 'SKU清单无效' }
}
async function captured(first: BatchSingleItem) {
  if (running.value || saving.value) return
  if (!selected.value.some(line => line.sku === first.input.primarySku)) { error.value = '当前商品不在勾选清单中，请使用清单中的 SKU'; return }
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
  const input = JSON.parse(JSON.stringify(combined.value)) as BatchSingleDocument, signature = JSON.stringify(input)
  if (signature !== lastRequest) { lastRequest = signature; saveKey = idempotencyKey('batch-single') }
  try { const result = await saveBatchSingle(input, saveKey); if (!disposed) saved.value = result }
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
async function reset() { generation++; active.value = false; await nextTick(); lines.value = []; saved.value = undefined; error.value = ''; engineFailed.value = false }
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
      <table v-else><thead><tr><th>报价单号</th><th>客户</th><th>SKU数</th><th>报价人</th><th>保存时间</th><th>操作</th></tr></thead><tbody><tr v-for="record in records.items" :key="record.id"><td>{{ record.no }}</td><td>{{ record.customerName }}</td><td>{{ record.skuCount }}</td><td>{{ record.salespersonName }}</td><td>{{ new Date(record.createdAt).toLocaleString('zh-CN', { hour12: false }) }}</td><td><button class="primary" @click="openRecord(record.id)">查看 SKU 明细</button></td></tr></tbody></table>
      <p v-if="!historyBusy && !records.total">暂无批量报价记录</p>
      <div class="actions"><button :disabled="page===0 || historyBusy" @click="page--; history()">上一页</button><span>共 {{ records.total }} 张 · 第 {{ page+1 }} 页</span><button :disabled="(page+1)*20>=records.total || historyBusy" @click="page++; history()">下一页</button></div>
      <BatchSingleRecordDetail v-if="historyDocument" :document="historyDocument" @close="historyDocument=undefined" />
    </section>
    <template v-if="hasPermission('quote')">
      <section class="card">
        <h2>01 批量录入 SKU</h2><p>从 Excel 复制，每行一个 SKU，最多50个。先用首个商品设置客户、阶梯和国家渠道，再应用到整批。</p>
        <textarea v-model="text" :disabled="active || running || saving" placeholder="DEMO001&#10;DEMO002&#10;DEMO003" aria-label="批量SKU清单" rows="4" />
        <div class="actions"><button v-if="!active" class="primary" @click="prepare">识别 SKU 并设置报价条件</button><button v-else :disabled="running || saving" @click="reset">重新录入</button><span>当前内容仅在此页面保留，保存成功后可在批量报价记录查看。</span></div>
      </section>
      <section v-if="lines.length" class="card"><h2>02 SKU 处理清单</h2><p role="status">{{ progress }}</p><table><thead><tr><th>选入</th><th>SKU</th><th>状态</th><th>明细数</th></tr></thead><tbody><tr v-for="(line,i) in lines" :key="i"><td><input v-model="line.selected" type="checkbox" :aria-label="`选入 ${line.sku} 第${i+1}行`" :disabled="running || saving || !!saved"></td><td>{{ line.sku }}</td><td :class="{error:line.error}">{{ line.error || (line.item ? '已完成' : '待查询') }}</td><td>{{ line.item?.sheet.rows.length ?? '—' }}</td></tr></tbody></table><p v-if="lines.some(l=>l.error)">异常项不会自动保存。可以调整条件重新生成，或明确取消勾选异常项后保存其余商品。</p></section>
      <section v-if="active && !engineFailed" v-show="showEditor" class="editor card" :inert="running || saving || undefined"><h2>03 设置公共条件与渠道</h2><p>以下设置应用到选中的每个 SKU；各商品分别使用采购表重量。底部点击“应用条件并生成批量报价”。</p><Workbench ref="engine" batch-capture :batch-initial-sku="selected[0]?.sku" @batch-captured="captured" /></section>
      <section v-if="active && !showEditor" class="card"><h2>04 合并报价单</h2><p>已选 {{ selected.length }} 个 SKU · {{ combined ? '将保存1张报价单' : '请处理异常项或取消选入' }}</p><BatchSingleSheet v-if="combined" :sheet="combined.sheet" /><p v-if="saved" class="success" role="status">已保存：{{ saved.no }}，包含 {{ saved.items.length }} 个 SKU</p><div class="actions"><button :disabled="running || saving" @click="edit">修改条件 / 重新生成</button><button class="primary" :disabled="!combined || running || saving || !!saved" @click="save">{{ saving ? '正在校验并保存…' : saved ? '已保存' : `保存 1 张报价单 · ${selected.length}个 SKU` }}</button></div></section>
    </template>
  </main>
</template>
<style scoped>
.batch-page{padding:22px;background:#f4f6f8;color:#1b2935;min-height:80vh}header,.actions,nav{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap}h1{font-size:24px;margin:0}h2{font-size:17px;margin:0 0 12px}p,.actions span{font-size:13px;color:#687786}.card{background:white;border:1px solid #e0e5eb;border-radius:10px;padding:18px;margin:16px 0;overflow:auto}.card h2{border-left:4px solid #f90;padding-left:10px}textarea{width:100%;max-width:650px;padding:10px;box-sizing:border-box;border:1px solid #d0d9e1;border-radius:6px}button{padding:10px 14px;border:1px solid #d2dbe1;border-radius:6px;background:white;cursor:pointer}.primary{background:#ff9400;border-color:#ff9400;color:#17232d;font-weight:700}button:disabled{opacity:.45;cursor:default}table{width:100%;border-collapse:collapse;font-size:13px;text-align:left}th,td{padding:12px;border-bottom:1px solid #e4e8ee}th{background:#eef5fc}.error{color:#b23d27;background:#fff4f0;padding:10px}.success{color:#16774c}.actions{margin-top:12px;justify-content:flex-end}.editor{padding:12px}.editor :deep(.quotation-page){padding:0}.editor :deep(.quotation-page > .draft-status-bar){display:none}@media(max-width:700px){.batch-page{padding:10px}.card{padding:12px}}
</style>
