<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppTopbar from '@/components/AppTopbar.vue'
import LogisticsPager from '@/components/logistics/LogisticsPager.vue'
import { logisticsPageFromQuery, logisticsPageQuery, logisticsPageSize } from '@/components/logistics/logisticsPagination'
import LogisticsRequiredChannels from '@/components/quotation/LogisticsRequiredChannels.vue'
import LogisticsBillingReview from '@/components/quotation/LogisticsBillingReview.vue'
import { idempotencyKey, type PreparedDownload } from '@/services/http'
import { invalidatePublishedLogisticsCache } from '@/data/publishedLogisticsRepository'
import { logisticsRebuild as service, money, shown, weightLabel, completedBatchStage, type Dataset, type Workspace, type Batch, type BatchSummary, type Version, type Cutover, type PricePage } from '@/data/logisticsRebuild'

const route = useRoute(), router = useRouter()
const requestedPageSize = Number(Array.isArray(route.query.size) ? route.query.size[0] : route.query.size)
const initialPageSize = logisticsPageSize(requestedPageSize)
const requestedPage = Number(Array.isArray(route.query.page) ? route.query.page[0] : route.query.page)
const tab = ref<'prices' | 'imports' | 'history'>(route.query.logisticsTab === 'imports' ? 'imports' : route.query.logisticsTab === 'history' ? 'history' : 'prices')
const datasets = ref<Dataset[]>([]), datasetId = ref(''), workspace = ref<Workspace | null>(null)
const batches = ref<BatchSummary[]>([]), batch = ref<Batch | null>(null), version = ref<Version | null>(null)
const batchResultQuery = ref(''), batchResultProvider = ref('all'), batchResultStatus = ref('all'), batchResultPage = ref(0)
const batchResultPageSize = 10
const prices = ref<PricePage>({ items: [], total: 0, page: 0, size: initialPageSize, totalPages: 0 })
const query = ref(''), country = ref(''), attribute = ref(''), page = ref(logisticsPageFromQuery(requestedPage)), pageSize = ref(initialPageSize)
const pricesLoading = ref(false), pricesLoaded = ref(false)
const name = ref('物流新库'), files = ref<File[]>([]), replaceDrafts = ref(false)
const busy = ref(false), error = ref(''), message = ref(''), note = ref(''), removal = ref(false), risk = ref(false)
const preparedDownload = ref<PreparedDownload | null>(null)
function clearDownload() { preparedDownload.value = null }
watch([query, country, attribute, tab], clearDownload)
onUnmounted(clearDownload)
const acceptanceRefresh = ref(0)
async function acceptanceUpdated() { cutover.value = null; await invalidatePublishedLogisticsCache(); await refresh(); if (version.value) { const id = version.value.id; const latest = await service.version(id); if (version.value?.id === id) version.value = latest } }
const diffType = ref('all'), detailTab = ref<'diff' | 'rows' | 'issues'>('diff'), detailPage = ref(0)
const cutover = ref<Cutover | null>(null), cutoverDirty = ref(false), cutoverNote = ref(''), unavailable = ref(false), cutoverConfirmed = ref(false)
const selected = computed(() => datasets.value.find(d => d.id === datasetId.value))
const archived = computed(() => selected.value?.status === 'archived')
const readyTargets = computed(() => workspace.value?.channels.filter(c => c.quoteReady) || [])
const filteredDiffs = computed(() => (version.value?.diffRows || []).filter(d => diffType.value === 'all' || d.type === diffType.value))
const visibleDiffs = computed(() => filteredDiffs.value.slice(detailPage.value * 50, (detailPage.value + 1) * 50))
const visibleRows = computed(() => (version.value?.rows || []).slice(detailPage.value * 50, (detailPage.value + 1) * 50))
const detailTotal = computed(() => detailTab.value === 'diff' ? filteredDiffs.value.length : (version.value?.rows?.length || 0))
const batchProviders = computed(() => [...new Set((batch.value?.payload.results || []).map(result => result.providerName))].sort((a, b) => a.localeCompare(b, 'zh-CN')))
const filteredBatchResults = computed(() => {
  const keyword = batchResultQuery.value.trim().toLocaleLowerCase()
  return (batch.value?.payload.results || []).filter(result => {
    const matchesKeyword = !keyword || `${result.providerName} ${result.channelName}`.toLocaleLowerCase().includes(keyword)
    const matchesProvider = batchResultProvider.value === 'all' || result.providerName === batchResultProvider.value
    const matchesStatus = batchResultStatus.value === 'all' || currentBatchResultStatus(result) === batchResultStatus.value
    return matchesKeyword && matchesProvider && matchesStatus
  })
})
const visibleBatchResults = computed(() => filteredBatchResults.value.slice(batchResultPage.value * batchResultPageSize, (batchResultPage.value + 1) * batchResultPageSize))
const batchResultTotalPages = computed(() => Math.max(1, Math.ceil(filteredBatchResults.value.length / batchResultPageSize)))
const batchResultCounts = computed(() => (batch.value?.payload.results || []).reduce((counts, result) => {
  counts.total++
  const status = currentBatchResultStatus(result)
  if (status === 'draft') counts.draft++
  else if (status === 'blocked') counts.blocked++
  else if (status === 'published') counts.published++
  return counts
}, { total: 0, draft: 0, blocked: 0, published: 0 }))
const statusLabel: Record<string, string> = { active: '当前生效库', preparing: '新库准备区', archived: '归档旧库', queued: '等待处理', processing: '处理中', completed: '处理完成', failed: '处理失败', interrupted: '处理已中断', draft: '待审核', published: '已生效', superseded: '历史版本', rejected: '已终止', blocked: '存在阻断', unchanged: '价格未变', parsed: '已解析', empty: '空表', metadata: '说明页', review: '待审核', staging: '生成草稿', parsing: '解析表格' }
const diffLabel: Record<string, string> = { added: '新增', price: '价格变化', rule: '规则变化', removed: '移除', unchanged: '无变化' }
let pollTimer: ReturnType<typeof setTimeout> | undefined
let requestKey = idempotencyKey('logistics-import'), reviewKey = idempotencyKey('logistics-review'), activationKey = idempotencyKey('logistics-activation')
let disposed = false, selectionEpoch = 0

async function run(action: () => Promise<void | PreparedDownload>) {
  if (busy.value) return
  busy.value = true; error.value = ''; message.value = ''
  try { const result = await action(); if (result && !disposed) preparedDownload.value = result } catch (e) { error.value = e instanceof Error ? e.message : '操作失败，请重试' } finally { busy.value = false }
}
function filters() { return new URLSearchParams({ query: query.value.trim(), country: country.value.trim(), attribute: attribute.value.trim(), page: String(page.value), size: String(pageSize.value) }) }
function versionFilters(id: string) { return new URLSearchParams({ versionId: id }) }
function currentBatchResultStatus(result: Batch['payload']['results'][number]) { return workspace.value?.versions.find(item => item.id === result.versionId)?.status || result.status }
async function requestPrices(id: string) {
  let result = await service.prices(id, filters())
  if (result.totalPages > 0 && page.value >= result.totalPages) {
    page.value = result.totalPages - 1
    result = await service.prices(id, filters())
  }
  return result
}
async function loadPrices() {
  const id = datasetId.value
  pricesLoading.value = true
  try {
    const result = await requestPrices(id)
    if (datasetId.value === id) { prices.value = result; pricesLoaded.value = true }
  } finally {
    if (datasetId.value === id) pricesLoading.value = false
  }
}
async function refresh() {
  const id = datasetId.value, epoch = selectionEpoch
  pricesLoading.value = true
  try {
    const [w, b, pricePage] = await Promise.all([service.workspace(id), service.batches(id), requestPrices(id)])
    if (disposed || id !== datasetId.value || epoch !== selectionEpoch) return
    workspace.value = w; batches.value = b; prices.value = pricePage; pricesLoaded.value = true; acceptanceRefresh.value++
  } finally {
    if (id === datasetId.value && epoch === selectionEpoch) pricesLoading.value = false
  }
}
async function changeDataset() {
  clearDownload()
  selectionEpoch++; clearTimeout(pollTimer); batch.value = null; version.value = null; cutover.value = null; page.value = 0; files.value = []
  workspace.value = null; batches.value = []; pricesLoaded.value = false; prices.value = { items: [], total: 0, page: 0, size: pageSize.value, totalPages: 0 }
  await run(refresh)
}
async function initialize() {
  datasets.value = await service.datasets()
  datasetId.value ||= datasets.value.find(d => d.id === route.query.dataset)?.id || datasets.value.find(d => d.status === 'active')?.id || datasets.value[0]?.id || ''
  if (datasetId.value) await refresh()
}
watch([datasetId, tab, page, pageSize], () => { if (datasetId.value) void router.replace({ query: { ...route.query, dataset: datasetId.value, logisticsTab: tab.value, ...logisticsPageQuery(page.value, pageSize.value) } }) })
watch([batchResultQuery, batchResultProvider, batchResultStatus], () => { batchResultPage.value = 0 })
async function submitPriceFilters() { page.value = 0; await loadPrices() }
async function changePricePage(nextPage: number) { if (nextPage === page.value) return; page.value = nextPage; await run(loadPrices) }
async function changePriceSize(size: number) { const nextSize = logisticsPageSize(size); if (nextSize === pageSize.value) return; pageSize.value = nextSize; page.value = 0; await run(loadPrices) }
async function createDataset() { await run(async () => { const d = await service.create(name.value); datasetId.value = d.id; version.value = null; batch.value = null; cutover.value = null; selectionEpoch++; await initialize(); tab.value = 'imports'; message.value = '新库已创建，旧库仍正常生效。' }) }
function chooseFiles(event: Event) { files.value = [...((event.target as HTMLInputElement).files || [])]; requestKey = idempotencyKey('logistics-import') }
async function upload() {
  await run(async () => { batch.value = await service.upload(datasetId.value, files.value, replaceDrafts.value, requestKey); requestKey = idempotencyKey('logistics-import'); files.value = []; await refresh(); schedulePoll() })
}
function schedulePoll() {
  clearTimeout(pollTimer)
  if (!batch.value || !['queued', 'processing'].includes(batch.value.status) || disposed) return
  pollTimer = setTimeout(async () => {
    const id = batch.value?.id, epoch = selectionEpoch
    if (!id) return
    try {
      const next = await service.batch(id)
      if (disposed || epoch !== selectionEpoch || batch.value?.id !== id) return
      batch.value = next
      if (['queued', 'processing'].includes(next.status)) schedulePoll(); else await refresh()
    } catch (e) { error.value = e instanceof Error ? e.message : '进度读取失败，可重新打开批次'; schedulePoll() }
  }, 2000)
}
function resetBatchResultView() { batchResultQuery.value = ''; batchResultProvider.value = 'all'; batchResultStatus.value = 'all'; batchResultPage.value = 0 }
async function openBatch(id: string) { await run(async () => { batch.value = await service.batch(id); resetBatchResultView(); schedulePoll() }) }
async function openVersion(id: string) { await run(async () => { version.value = await service.version(id); note.value = ''; removal.value = false; risk.value = false; detailPage.value = 0; detailTab.value = 'diff'; diffType.value = 'all'; reviewKey = idempotencyKey('logistics-review') }) }
async function publish() {
  if (!version.value) return
  await run(async () => { version.value = await service.review(version.value!, note.value, removal.value, risk.value, reviewKey); reviewKey = idempotencyKey('logistics-review'); await invalidatePublishedLogisticsCache(); await refresh(); message.value = version.value.quoteReady === false ? '价格已保存为正式版本；渠道仍待适配，不开放自动报价。' : '新价格已生效。' })
}
async function recompare() { await run(async () => { version.value = await service.recompare(version.value!); risk.value = false; removal.value = false; reviewKey = idempotencyKey('logistics-review'); message.value = '已按最新正式价格重新对比，请重新审核。' }) }
async function rollback() { await run(async () => { version.value = await service.rollback(version.value!, note.value); await invalidatePublishedLogisticsCache(); await refresh(); message.value = '已创建新的回滚版本，历史报价没有改写。' }) }
async function prepareCutover() { await run(async () => { await service.backup(datasetId.value); cutover.value = await service.preview(datasetId.value); cutoverDirty.value = false; cutoverConfirmed.value = false; unavailable.value = false; activationKey = idempotencyKey('logistics-activation'); message.value = '旧库快照已备份，请核对渠道映射与暂不可用清单。' }) }
async function updatePreview() { await run(async () => { cutover.value = await service.preview(datasetId.value, cutover.value?.mappings); cutoverDirty.value = false; cutoverConfirmed.value = false }) }
function mappingChanged() { cutoverDirty.value = true; cutoverConfirmed.value = false }
async function activate() { await run(async () => { await service.activate(datasetId.value, cutover.value!, cutoverNote.value, unavailable.value, activationKey); await invalidatePublishedLogisticsCache(); cutover.value = null; await initialize(); message.value = '新库已整体生效，旧库已归档。请核对未迁移的模板与草稿。' }) }
onMounted(() => { void run(initialize) })
onUnmounted(() => { disposed = true; clearTimeout(pollTimer) })
</script>

<template>
  <div class="logistics-page">
    <AppTopbar />
    <main>
      <header class="page-heading"><div><p class="eyebrow">LOGISTICS / PRICE WORKSPACE</p><h1>物流价格管理</h1><p>原表导入 · 版本审核 · 更新对比 · Excel 导出</p></div><div class="dataset-picker"><label>正在查看的物流库<select v-model="datasetId" :disabled="busy" @change="changeDataset"><option v-for="d in datasets" :key="d.id" :value="d.id">{{ d.name }} · {{ statusLabel[d.status] }}</option></select></label><span class="tag" :class="selected?.status">{{ statusLabel[selected?.status || ''] }}</span></div></header>
      <p v-if="error" role="alert" class="notice error">{{ error }}</p><p v-if="message" role="status" class="notice success">{{ message }}</p>
      <p v-if="preparedDownload" role="status" class="notice success">下载已就绪，请点击保存：<a :href="preparedDownload.url" :download="preparedDownload.filename">下载 {{ preparedDownload.filename }}</a>。下载仍需登录和物流权限；价格版本若发生变化，请重新生成链接。</p>
      <p v-if="archived" class="notice">这里是归档旧库，只能查阅和导出；不参与当前报价，不会被新导入自动恢复。</p>
      <p v-if="selected?.status === 'preparing'" class="notice">新库准备期间不影响当前报价。确认整体切换后，当前物流商和渠道列表才会全部换新。</p>
      <section class="metrics"><div><small>物流商</small><strong>{{ workspace?.providers.length || 0 }}</strong></div><div><small>渠道</small><strong>{{ workspace?.channels.length || 0 }}</strong></div><div><small>可自动报价</small><strong>{{ readyTargets.length }}</strong></div><div><small>待审价格版本</small><strong>{{ workspace?.versions.filter(v => v.status === 'draft').length || 0 }}</strong></div></section>
      <nav class="tabs" aria-label="物流工作区"><button :class="{ active: tab === 'prices' }" @click="tab = 'prices'">当前物流价格</button><button :class="{ active: tab === 'imports' }" @click="tab = 'imports'">导入与更新</button><button :class="{ active: tab === 'history' }" @click="tab = 'history'">版本与历史</button></nav>

      <section v-if="tab === 'prices'" class="card">
        <form class="toolbar" @submit.prevent="run(submitPriceFilters)"><label>物流商 / 渠道<input v-model="query" placeholder="搜索名称"></label><label>国家<input v-model="country" placeholder="例如 美国 / US"></label><label>货物属性<input v-model="attribute" placeholder="例如 普货"></label><button :disabled="busy">查询</button><button type="button" class="primary" :disabled="busy" @click="run(() => service.exportPrices(datasetId, filters()))">导出全部筛选价格</button></form>
        <LogisticsPager v-if="pricesLoaded" position="top" :page="page" :size="pageSize" :total="prices.total" :total-pages="prices.totalPages" :loading="busy || pricesLoading" @page-change="changePricePage" @size-change="changePriceSize" />
        <div class="scroll" :aria-busy="pricesLoading"><table><thead><tr><th>物流商 / 渠道</th><th>国家</th><th>重量段</th><th>计费价格</th><th>每票费用</th><th>版本 / 状态</th><th>操作</th></tr></thead><tbody><template v-if="pricesLoading"><tr v-for="index in 6" :key="`skeleton-${index}`" class="price-skeleton" aria-hidden="true"><td v-for="column in 7" :key="column"><span /></td></tr></template><template v-else><tr v-for="(r, i) in prices.items" :key="`${r.versionId}-${page}-${i}`"><td><b>{{ r.providerName }}</b><small>{{ r.channelName }}</small></td><td>{{ r.areaName }}<small>{{ r.countryCode }} · {{ r.zoneName || '无分区' }}</small></td><td>{{ weightLabel(r) }}</td><td v-if="r.pricingModel === 'first-next'">首 {{ r.firstWeightKg }}kg / {{ r.currency || 'CNY' }} {{ money(r.firstWeightPrice) }}<small>续 {{ r.nextWeightKg }}kg / {{ r.currency || 'CNY' }} {{ money(r.nextWeightPrice) }}</small></td><td v-else-if="r.intervalPrice">{{ r.currency || 'CNY' }} {{ money(r.intervalPrice) }} / 档</td><td v-else>{{ r.currency || 'CNY' }} {{ money(r.pricePerKg) }} / kg</td><td>{{ r.currency || 'CNY' }} {{ money(r.registrationFee) }}</td><td>V{{ r.versionNumber }}<small :class="{ warning: r.quoteReady === false }">{{ r.quoteReady === false ? '价格已记录 · 计费待适配' : '可自动报价' }}</small></td><td><button :disabled="busy" @click="openVersion(r.versionId!)">查看</button></td></tr><tr v-if="!prices.items.length"><td colspan="7" class="empty">当前条件没有正式价格；新库请先导入并审核价格版本。</td></tr></template></tbody></table></div>
        <LogisticsPager v-if="pricesLoaded" position="bottom" :page="page" :size="pageSize" :total="prices.total" :total-pages="prices.totalPages" :loading="busy || pricesLoading" @page-change="changePricePage" @size-change="changePriceSize" />
      </section>

      <section v-if="tab === 'imports'" class="stack">
        <LogisticsRequiredChannels v-if="datasetId" :dataset-id="datasetId" :preparing="selected?.status === 'preparing'" :refresh-key="acceptanceRefresh" @updated="acceptanceUpdated" />
        <div class="card toolbar"><label>新物流库名称<input v-model="name" maxlength="120"></label><button :disabled="busy || !name.trim()" @click="createDataset">创建独立新库</button><span class="muted">不会清空当前库，也不会自动切换。</span></div>
        <div v-if="!archived" class="card"><h2>导入物流商原始报价表</h2><p><a href="/templates/logistics-v2.xlsx" download="物流标准导入模板V2.xlsx">下载新版标准模板（含示例和填写说明）</a></p><p class="muted">支持 .xls / .xlsx、多工作表、多渠道；每批最多30个文件，总计100MB。文件名无需填写或校验日期，开头日期会自动从显示名称中去除。未审核价格不影响报价。</p><div class="toolbar"><input aria-label="物流报价文件" type="file" accept=".xls,.xlsx" multiple :disabled="busy" @change="chooseFiles"><button class="primary" :disabled="busy || !files.length" @click="upload">{{ busy ? '处理中…' : `上传并解析 ${files.length} 份文件` }}</button></div><label class="check"><input v-model="replaceDrafts" type="checkbox">如果已有不同待审稿，确认终止旧稿并保留历史，再生成新稿</label></div>
        <div class="card"><h2>导入记录</h2><div class="scroll"><table><thead><tr><th>时间</th><th>原始文件</th><th>状态</th><th>进度</th><th>操作</th></tr></thead><tbody><tr v-for="b in batches" :key="b.id"><td>{{ b.created_at }}</td><td>{{ b.files.map(f => f.name).join('、') }}</td><td>{{ statusLabel[b.status] || b.status }}</td><td>{{ b.progress || 0 }}%</td><td><button :disabled="busy" @click="openBatch(b.id)">查看结果</button></td></tr><tr v-if="!batches.length"><td colspan="5" class="empty">暂无导入批次。</td></tr></tbody></table></div></div>
        <div v-if="batch" class="card"><div class="section-head"><h2>本批处理结果</h2><button :disabled="busy || batch.status !== 'completed'" @click="run(() => service.exportBatchDiff(batch!.id))">导出批次差异</button></div><p>{{ statusLabel[batch.status] }} · {{ completedBatchStage(batch) || statusLabel[batch.phase] || batch.phase }} · {{ batch.payload.progress || 0 }}%<span v-if="batch.payload.elapsedMs"> · 处理 {{ (batch.payload.elapsedMs / 1000).toFixed(2) }} 秒</span></p><progress :value="batch.payload.progress || 0" max="100" /><p v-if="batch.payload.error" class="warning">{{ batch.payload.error }}</p><button v-if="['failed', 'interrupted', 'processing'].includes(batch.status) && !archived" :disabled="busy" @click="run(async () => { batch = await service.retry(batch!.id); schedulePoll() })">重试失败 / 超时批次</button>
          <details v-for="(file, i) in batch.payload.files" :key="i"><summary>{{ file.name }} <button :disabled="busy" @click.stop="run(() => service.original(batch!.id, i))">下载原文件</button><button v-if="batch.payload.fileReports?.[i]?.sourceEvidence" :disabled="busy" @click.stop="run(() => service.evidence(batch!.id, i))">下载解析证据</button></summary><p v-if="batch.payload.fileReports?.[i]?.message" class="warning">{{ batch.payload.fileReports[i]?.message }}</p><ul><li v-for="s in batch.payload.fileReports?.[i]?.sheets || []" :key="s.name">{{ s.name }}：{{ statusLabel[s.status] || s.status }} · {{ s.priceRows || 0 }} 条价格 {{ s.message || '' }}</li></ul></details>
          <div class="batch-result-summary" aria-label="批次结果汇总"><span>共 <b>{{ batchResultCounts.total }}</b> 个渠道</span><span>待审核 <b>{{ batchResultCounts.draft }}</b></span><span>已发布 <b>{{ batchResultCounts.published }}</b></span><span :class="{ warning: batchResultCounts.blocked > 0 }">阻断 <b>{{ batchResultCounts.blocked }}</b></span></div>
          <div class="batch-result-toolbar"><label>搜索渠道<input v-model="batchResultQuery" placeholder="物流商或渠道名称"></label><label>物流商<select v-model="batchResultProvider"><option value="all">全部物流商</option><option v-for="provider in batchProviders" :key="provider" :value="provider">{{ provider }}</option></select></label><label>处理状态<select v-model="batchResultStatus"><option value="all">全部状态</option><option value="draft">待审核</option><option value="published">已发布</option><option value="blocked">存在阻断</option><option value="unchanged">价格未变</option></select></label></div>
          <div class="scroll"><table class="batch-results-table"><thead><tr><th>物流商 / 渠道</th><th>结果</th><th>更新摘要</th><th>操作</th></tr></thead><tbody><tr v-for="(r, i) in visibleBatchResults" :key="`${r.versionId || r.channelId || r.channelName}-${i}`"><td>{{ r.providerName }}<small>{{ r.channelName }}</small></td><td>{{ statusLabel[currentBatchResultStatus(r)] || currentBatchResultStatus(r) }}<small class="warning">{{ r.message }}{{ r.quoteReady === false ? '计费规则待适配' : '' }}</small></td><td>新增 {{ r.summary?.added || 0 }} · 调价 {{ r.summary?.price || 0 }} · 移除 {{ r.summary?.removed || 0 }}<small>{{ r.errors || 0 }} 个阻断问题</small></td><td><button v-if="r.versionId" :disabled="busy" @click="openVersion(r.versionId)">核对版本</button><details v-else><summary>查看原因</summary><p>{{ r.message }}</p><p v-for="(issue, j) in r.issues || []" :key="j">{{ issue.message }}</p></details></td></tr><tr v-if="!visibleBatchResults.length"><td colspan="4" class="empty">当前筛选条件没有渠道结果。</td></tr></tbody></table></div>
          <footer class="pager batch-result-pager"><span>筛选结果 {{ filteredBatchResults.length }} 条 · 每页 {{ batchResultPageSize }} 条</span><button :disabled="batchResultPage === 0" @click="batchResultPage--">上一页</button><span>{{ batchResultPage + 1 }} / {{ batchResultTotalPages }}</span><button :disabled="batchResultPage + 1 >= batchResultTotalPages" @click="batchResultPage++">下一页</button></footer>
        </div>
        <div v-if="selected?.status === 'preparing'" class="card cutover"><h2>新库整体切换</h2><p>准备完毕后，先备份旧库并核对渠道关联。切换不删除历史报价；无法匹配的财务渠道、模板和草稿需要后续人工处理。</p><button :disabled="busy" @click="prepareCutover">备份旧库并生成切换预览</button>
          <template v-if="cutover"><p class="warning">必用清单{{ cutover.requiredConfirmed ? '已确认' : '未确认' }} · 必用 {{ cutover.requiredCount }} 个 · 未就绪 {{ cutover.requiredNotReady?.length || 0 }} 个</p><p>可自动报价 {{ cutover.readyChannels }} 个 · 暂不可用 {{ cutover.pendingChannels.length }} 个 · 未映射 {{ cutover.unmappedChannels }} 个</p><div class="scroll"><table><thead><tr><th>旧渠道</th><th>新渠道</th><th>匹配结果</th></tr></thead><tbody><tr v-for="m in cutover.mappings" :key="m.oldChannelId"><td>{{ m.oldName }}</td><td><select v-model="m.newChannelId" :disabled="busy" @change="mappingChanged"><option value="">暂不迁移，保留待处理</option><option v-for="c in readyTargets" :key="c.id" :value="c.id">{{ c.providerName }} / {{ c.name }}</option></select></td><td>{{ m.status === 'matched' ? '已匹配，需核对' : '待处理' }}</td></tr></tbody></table></div><button v-if="cutoverDirty" :disabled="busy" @click="updatePreview">按修改后的映射重新生成预览</button><details><summary>查看暂不可用渠道</summary><p v-for="c in cutover.pendingChannels" :key="c.id">{{ c.providerName }} / {{ c.name }}</p></details><details><summary>财务与模板关联变更（{{ cutover.bindingChanges?.length || 0 }} 项）</summary><p>待恢复重计价的报价草稿：{{ cutover.draftsToReprice || 0 }} 份。未映射引用保留待处理，不扩大允许范围。</p><div class="scroll"><table><thead><tr><th>类型 / 标识 / 位置</th><th>迁移前</th><th>迁移后</th><th>状态</th></tr></thead><tbody><tr v-for="(b, i) in cutover.bindingChanges || []" :key="i"><td>{{ b.kind === 'finance' ? '财务渠道限制' : '报价模板' }}<small>{{ b.id }} {{ b.path }}</small></td><td>{{ b.before }}</td><td>{{ b.after }}</td><td>{{ b.status === 'mapped' ? '仅迁移引用' : '保留待处理' }}</td></tr></tbody></table></div></details><label>切换审核备注<textarea v-model="cutoverNote" maxlength="500" /></label><label class="check"><input v-model="unavailable" type="checkbox">已确认未映射及暂不可用渠道：切换后不回退使用旧库价格</label><label class="check"><input v-model="cutoverConfirmed" type="checkbox">已核对映射、备份和影响清单，确认将当前物流列表整体换新</label><button class="primary" :disabled="busy || cutoverDirty || !cutoverConfirmed || !cutoverNote.trim() || !cutover.requiredReady || ((cutover.unmappedChannels > 0 || cutover.pendingChannels.length > 0) && !unavailable)" @click="activate">确认整体切换</button></template>
        </div>
      </section>

      <section v-if="tab === 'history'" class="card"><h2>{{ archived ? '旧库历史档案' : '渠道版本记录' }}</h2><div class="scroll"><table><thead><tr><th>渠道</th><th>版本</th><th>来源</th><th>状态</th><th>价格行</th><th>操作</th></tr></thead><tbody><tr v-for="v in workspace?.versions || []" :key="v.id"><td>{{ workspace?.channels.find(c => c.id === v.channelId)?.name }}</td><td>V{{ v.versionNumber }}</td><td>{{ v.fileName }}<small>{{ v.importedAt }}</small></td><td>{{ statusLabel[v.status] }}</td><td>{{ v.rowCount }}</td><td><button :disabled="busy" @click="openVersion(v.id)">查看与导出</button></td></tr></tbody></table></div></section>

      <section v-if="version" class="card version-detail"><div class="section-head"><div><p class="eyebrow">VERSION REVIEW</p><h2>{{ workspace?.channels.find(c => c.id === version?.channelId)?.name }} · V{{ version.versionNumber }}</h2><p>{{ statusLabel[version.status] }} · {{ version.fileName }}</p></div><button @click="version = null">关闭详情</button></div>
        <p v-if="version.quoteReady === false" class="notice">该版本的价格可核对和管理，但计费条件尚未完全适配。即使审核价格，也不会开放自动报价。</p>
        <LogisticsBillingReview :key="`${version.id}-${version.status}`" :version-id="version.id" :readonly="archived" @updated="acceptanceUpdated" />
        <div class="toolbar"><button :disabled="busy" @click="run(() => service.exportPrices(datasetId, versionFilters(version!.id)))">导出本版本价格</button><button :disabled="busy" @click="run(() => service.exportDiff(version!))">导出本版本差异</button><button v-if="version.batchId" :disabled="busy" @click="run(() => service.original(version!.batchId!, version!.sourceFileIndex || 0))">下载原文件</button></div>
        <p class="muted">对比基线：{{ version.basePublishedVersionId || '初始价格版本（不与旧库比较）' }}</p>
        <div class="toolbar"><button @click="detailTab = 'diff'; detailPage = 0">价格变化</button><button @click="detailTab = 'rows'; detailPage = 0">完整价格</button><button @click="detailTab = 'issues'; detailPage = 0">解析问题 {{ version.issues?.length || 0 }}</button><select v-if="detailTab === 'diff'" v-model="diffType" aria-label="差异类型" @change="detailPage = 0"><option value="all">全部差异类型</option><option v-for="(label, key) in diffLabel" :key="key" :value="key">{{ label }}</option></select></div>
        <div v-if="detailTab === 'diff'" class="scroll"><table><thead><tr><th>国家 / 档位</th><th>类型</th><th>原值 → 新值</th><th>变化</th></tr></thead><tbody><tr v-for="d in visibleDiffs" :key="d.key"><td>{{ d.row.areaName }} · {{ d.row.zoneName || '无分区' }}<small>{{ weightLabel(d.row) }}</small></td><td>{{ diffLabel[d.type] }}</td><td><div v-for="(c, i) in d.changes" :key="i">{{ c.field }}：{{ shown(c.before) }} → {{ shown(c.after) }}</div><span v-if="!d.changes.length">公斤价 ¥{{ money(d.previous?.pricePerKg) }} → {{ d.type === 'removed' ? '移除' : `¥${money(d.row.pricePerKg)}` }}<small>每票 ¥{{ money(d.previous?.registrationFee) }} → {{ d.type === 'removed' ? '移除' : `¥${money(d.row.registrationFee)}` }}</small></span></td><td><div v-for="(c, i) in d.changes" :key="i">{{ c.delta == null ? '规则变化' : `${c.delta > 0 ? '+' : ''}${money(c.delta)}` }}<small>{{ c.percentChange == null ? '—' : `${c.percentChange > 0 ? '+' : ''}${c.percentChange.toFixed(2)}%` }}</small></div></td></tr></tbody></table></div>
        <div v-if="detailTab === 'rows'" class="scroll"><table><thead><tr><th>国家 / 档位</th><th>公斤价 / 每票费</th><th>首续重 / 区间价</th><th>来源</th><th>待适配原因</th></tr></thead><tbody><tr v-for="(r, i) in visibleRows" :key="i"><td>{{ r.areaName }} · {{ r.zoneName || '无分区' }}<small>{{ weightLabel(r) }}</small></td><td>{{ r.currency || 'CNY' }} {{ money(r.pricePerKg) }} / {{ r.currency || 'CNY' }} {{ money(r.registrationFee) }}</td><td>首 {{ r.currency || 'CNY' }} {{ money(r.firstWeightPrice) }} / 续 {{ r.currency || 'CNY' }} {{ money(r.nextWeightPrice) }}<small>区间 {{ r.currency || 'CNY' }} {{ money(r.intervalPrice) }}</small></td><td>{{ r.sourceSheet }} · 第{{ r.sourceRow }}行</td><td class="warning">{{ r.pendingReason || '—' }}</td></tr></tbody></table></div>
        <ul v-if="detailTab === 'issues'"><li v-for="(i, n) in version.issues || []" :key="n">第 {{ i.row || '—' }} 行 · {{ i.field }}：{{ i.message }}</li></ul>
        <footer v-if="detailTab !== 'issues'" class="pager"><span>共 {{ detailTotal }} 条</span><button :disabled="detailPage === 0" @click="detailPage--">上一页</button><span>{{ detailPage + 1 }} / {{ Math.max(1, Math.ceil(detailTotal / 50)) }}</span><button :disabled="(detailPage + 1) * 50 >= detailTotal" @click="detailPage++">下一页</button></footer>
        <div v-if="!archived" class="review"><label>审核 / 回滚备注<textarea v-model="note" maxlength="500" placeholder="价格来源、核对结论与调整原因" /></label><template v-if="version.status === 'draft'"><label class="check"><input v-model="removal" type="checkbox">已核对并确认本版本移除的国家、重量档位</label><label class="check"><input v-model="risk" type="checkbox">已核对大幅价格变化及相关风险</label><div class="toolbar"><button :disabled="busy" @click="recompare">重新对比最新价格</button><button class="primary" :disabled="busy || version.errors > 0 || !note.trim() || ((version.summary.removed || 0) > 0 && !removal) || ((version.summary.highRisk || 0) > 0 && !risk)" @click="publish">审核并发布价格</button></div></template><button v-else-if="version.status === 'superseded'" :disabled="busy || !note.trim()" @click="rollback">以此历史价格创建回滚版本</button></div>
      </section>
    </main>
  </div>
</template>

<style scoped>
.logistics-page{min-height:100vh;background:#f3f5f7;color:#243542;font-size:14px}main{max-width:1500px;margin:0 auto;padding:28px 32px 64px}.page-heading,.section-head{display:flex;justify-content:space-between;align-items:center;gap:24px}.page-heading h1{font-size:28px;margin:5px 0 8px;letter-spacing:-.6px}.page-heading p{color:#71818d;margin:4px 0}.eyebrow{font-size:11px;font-weight:750;letter-spacing:1.5px;color:#a76b30!important}.dataset-picker{display:flex;align-items:flex-end;gap:12px}.dataset-picker select{min-width:260px}.metrics{display:grid;grid-template-columns:repeat(4,1fr);gap:16px;margin:24px 0}.metrics>div{background:#fff;border:1px solid #e2e8ed;border-radius:10px;padding:18px 22px}.metrics small{color:#7c8b96;display:block}.metrics strong{display:block;font-size:30px;line-height:1.3;margin-top:6px}.tabs{display:flex;gap:28px;border-bottom:1px solid #dbe3e8;margin-bottom:20px}.tabs button{border:0;border-radius:0;background:none;padding:13px 0;color:#6d7d88;font-size:15px}.tabs .active{color:#c8752d;border-bottom:3px solid #df8d41;font-weight:700}.card{border:1px solid #dfe6eb;background:white;border-radius:10px;padding:22px;box-shadow:0 2px 4px #152a3b03}.stack{display:grid;gap:18px}h2{font-size:18px;margin:0 0 14px}.toolbar{display:flex;align-items:flex-end;gap:12px;flex-wrap:wrap;margin-bottom:16px}.toolbar>label{flex:1;min-width:150px;max-width:260px}label{display:flex;flex-direction:column;gap:7px;font-size:12px;color:#667781}input,select,textarea,button{font:inherit}input:not([type=checkbox]),select,textarea{border:1px solid #cdd8e0;border-radius:6px;padding:10px 12px;background:#fff;color:#253d4c}textarea{width:100%;min-height:70px;box-sizing:border-box;resize:vertical}button{border:1px solid #ccd7df;border-radius:6px;background:white;color:#435e70;padding:9px 14px;cursor:pointer;white-space:nowrap}button:hover:not(:disabled){background:#f4f8fa}button.primary{background:#da853c;border-color:#da853c;color:white}button.primary:hover:not(:disabled){background:#c77730}button:disabled{opacity:.45;cursor:not-allowed}.scroll{overflow:auto}table{width:100%;border-collapse:collapse;text-align:left}th{background:#f4f7f9;font-size:12px;color:#74838e;padding:12px;font-weight:600;white-space:nowrap}td{border-bottom:1px solid #e9eef1;padding:13px 12px;vertical-align:top;line-height:1.65}td small{display:block;color:#81919c;font-size:12px;max-width:460px;white-space:normal}td b{font-weight:600}td button{font-size:12px;padding:5px 10px}.notice{padding:14px 18px;background:#fff4df;border:1px solid #f1d8ad;border-radius:8px;line-height:1.7}.notice.error{background:#fff0ee;border-color:#eebcb5;color:#ab3e32}.notice.success{background:#edf8f2;border-color:#badfc9;color:#24724a}.muted{color:#7d8d98;line-height:1.7}.warning{color:#bc762b!important}.tag{padding:8px 10px;border-radius:6px;background:#edf1f5;white-space:nowrap;font-size:12px}.tag.active{background:#e8f6ed;color:#348359}.tag.preparing{background:#fff0d8;color:#b77724}.empty{text-align:center;color:#82929c;padding:36px}.pager{display:flex;gap:12px;align-items:center;justify-content:flex-end;margin-top:18px;color:#83939d;font-size:12px}.pager>span:first-child{margin-right:auto}.check{display:flex;flex-direction:row;align-items:center;margin:14px 0;font-size:13px}.cutover{border-top:3px solid #dd9247}.cutover select{max-width:500px;width:100%}.review{margin-top:24px;padding-top:20px;border-top:1px solid #e2e8ed}.version-detail{margin-top:24px;border-top:3px solid #54788e}.section-head p{color:#7f8f9b}progress{width:100%;height:9px;accent-color:#db8b42;margin:8px 0 14px}details{margin:12px 0;color:#657986}summary{cursor:pointer}summary button{font-size:11px;margin-left:8px}li{line-height:1.9}input[type=checkbox]{accent-color:#d58842}.batch-result-summary{display:flex;align-items:center;gap:10px;flex-wrap:wrap;margin:16px 0 12px}.batch-result-summary span{padding:7px 11px;border:1px solid #e2e8ed;border-radius:999px;background:#f8fafb;color:#667984;font-size:12px}.batch-result-summary b{color:#294250}.batch-result-toolbar{display:grid;grid-template-columns:minmax(240px,1fr) minmax(150px,220px) minmax(150px,220px);gap:12px;align-items:end;margin-bottom:12px}.batch-results-table td{padding-top:10px;padding-bottom:10px}.batch-result-pager{margin-top:12px}@media(max-width:800px){main{padding:18px 12px}.page-heading{align-items:flex-start;flex-direction:column}.metrics{grid-template-columns:repeat(2,1fr)}.card{padding:16px}.dataset-picker{flex-wrap:wrap}td{min-width:100px}.toolbar>label{max-width:none}.tabs{gap:20px}.batch-result-toolbar{grid-template-columns:1fr}.batch-result-pager{flex-wrap:wrap}.batch-result-pager>span:first-child{width:100%}}
.price-skeleton span{display:block;width:80%;height:14px;border-radius:5px;background:linear-gradient(90deg,#edf1f4 25%,#f7f9fa 50%,#edf1f4 75%);background-size:200% 100%;animation:price-skeleton 1.2s infinite}.price-skeleton td{height:40px}@keyframes price-skeleton{to{background-position:-200% 0}}
</style>
