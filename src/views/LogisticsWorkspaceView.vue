<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppTopbar from '@/components/AppTopbar.vue'
import LogisticsPager from '@/components/logistics/LogisticsPager.vue'
import { logisticsPageFromQuery, logisticsPageQuery, logisticsPageSize } from '@/components/logistics/logisticsPagination'
import LogisticsRequiredChannels from '@/components/quotation/LogisticsRequiredChannels.vue'
import LogisticsBillingReview from '@/components/quotation/LogisticsBillingReview.vue'
import { idempotencyKey, type PreparedDownload, type UploadProgress } from '@/services/http'
import { invalidatePublishedLogisticsCache } from '@/data/publishedLogisticsRepository'
import { logisticsRebuild as service, money, shown, weightLabel, completedBatchStage, diffKinds, aggregateChangeSummary, batchComparisonSummary, rangeImpact, changeImpact, logisticsUploadError, formatTransferBytes, type Dataset, type Workspace, type Batch, type BatchSummary, type Version, type Cutover, type PricePage, type Diff, type DiffChange, type DiffKind, type Price, type RowCorrection, type ReadyPublishResult } from '@/data/logisticsRebuild'

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
const editingRows = ref(false), editSnapshot = ref<Price[]>([])
const readyPublishNote = ref(''), readyPublishRemoval = ref(false), readyPublishRisk = ref(false), readyPublishResult = ref<ReadyPublishResult | null>(null)
const logisticsFileInput = ref<HTMLInputElement | null>(null)
const versionDetail = ref<HTMLElement | null>(null)
const uploadProgress = ref<UploadProgress>({ loaded: 0, total: 0, percent: 0, bytesPerSecond: 0 })
const uploadInFlight = ref(false), activeUploadBatchId = ref('')
const uploadFileSnapshot = ref<Array<{ name: string; size: number }>>([])
let cancelActiveUpload: (() => void) | null = null
const batchPublishProviderId = ref(''), batchPublishVersionIds = ref<string[]>([]), batchPublishReviewedIds = ref<string[]>([]), batchPublishNote = ref('')
const preparedDownload = ref<PreparedDownload | null>(null)
function clearDownload() { preparedDownload.value = null }
watch([query, country, attribute, tab], clearDownload)
onUnmounted(clearDownload)
const acceptanceRefresh = ref(0)
async function acceptanceUpdated() { cutover.value = null; await invalidatePublishedLogisticsCache(); await refresh(); if (version.value) { const id = version.value.id; const latest = await service.version(id); if (version.value?.id === id) version.value = latest } }
const diffType = ref('all'), detailTab = ref<'diff' | 'rows' | 'issues'>('diff'), detailPage = ref(0)
const cutover = ref<Cutover | null>(null), cutoverDirty = ref(false), cutoverNote = ref(''), unavailable = ref(false), cutoverConfirmed = ref(false)
const statusLabel: Record<string, string> = { active: '当前生效库', preparing: '新库准备区', archived: '归档旧库', queued: '等待处理', processing: '处理中', completed: '处理完成', failed: '处理失败', interrupted: '处理已中断', draft: '待审核', published: '已生效', superseded: '历史版本', rejected: '已终止', blocked: '存在阻断', unchanged: '价格未变', parsed: '已解析', empty: '空表', metadata: '说明页', review: '待审核', staging: '生成草稿', parsing: '解析表格' }
const selected = computed(() => datasets.value.find(d => d.id === datasetId.value))
const archived = computed(() => selected.value?.status === 'archived')
const importTarget = computed(() => selected.value ? `${selected.value.name}（${selected.value.status === 'active' ? '当前生效库' : selected.value.status === 'preparing' ? '准备库' : '归档库'}）` : '未选择物流库')
const uploadValidation = computed(() => files.value.length ? logisticsUploadError(files.value) : '')
const activeUploadBatch = computed(() => batch.value?.id === activeUploadBatchId.value ? batch.value : null)
const uploadStatusVisible = computed(() => uploadInFlight.value || Boolean(activeUploadBatch.value))
const uploadStatusPercent = computed(() => uploadInFlight.value ? uploadProgress.value.percent : Number(activeUploadBatch.value?.payload.progress || 0))
const uploadStatusText = computed(() => {
  if (uploadInFlight.value) return uploadProgress.value.percent >= 100 ? '文件已传完，服务器正在校验并保存' : '正在上传文件'
  const current = activeUploadBatch.value
  if (!current) return ''
  if (current.status === 'queued') return '文件已保存，等待后台解析'
  if (current.phase === 'parsing') return current.payload.currentFileName ? `正在解析 ${current.payload.currentFileName}（${Number(current.payload.processedFiles || 0) + 1}/${current.payload.totalFiles || current.payload.files.length}）` : '正在解析工作簿'
  if (current.phase === 'staging') return current.payload.currentChannelName ? `正在生成渠道草稿：${current.payload.currentChannelName}` : '正在生成渠道草稿'
  if (current.status === 'completed') return completedBatchStage(current)
  if (current.status === 'failed' || current.status === 'interrupted') return current.payload.error || statusLabel[current.status] || '处理失败'
  return statusLabel[current.phase] || statusLabel[current.status] || current.status
})
const displayedUploadFiles = computed(() => files.value.length ? files.value.map(file => ({ name: file.name, size: file.size })) : uploadFileSnapshot.value)
const readyTargets = computed(() => workspace.value?.channels.filter(c => c.quoteReady) || [])
const providersWithDrafts = computed(() => (workspace.value?.providers || []).filter(provider => workspace.value?.channels.some(channel => channel.providerId === provider.id && workspace.value?.versions.some(item => item.channelId === channel.id && item.status === 'draft'))))
const batchPublishProvider = computed(() => providersWithDrafts.value.find(provider => provider.id === batchPublishProviderId.value))
const batchPublishDrafts = computed(() => {
  const providerId = batchPublishProvider.value?.id
  if (!providerId) return []
  return (workspace.value?.versions || []).filter(item => item.status === 'draft').map(item => ({ version: item, channel: workspace.value?.channels.find(channel => channel.id === item.channelId) })).filter(item => item.channel?.providerId === providerId)
})
const selectedBatchPublishDrafts = computed(() => batchPublishDrafts.value.filter(item => batchPublishVersionIds.value.includes(item.version.id)))
const hasCoverageRemoval = (summary: Record<string, number> | undefined) => (summary?.removed || 0) > 0 || (summary?.coverageReduced || 0) > 0
const batchPublishNeedsReview = (item: typeof batchPublishDrafts.value[number]) => hasCoverageRemoval(item.version.summary) || (item.version.summary.highRisk || 0) > 0
const batchPublishBlocked = (item: typeof batchPublishDrafts.value[number]) => item.version.errors > 0
const batchPublishReady = computed(() => selectedBatchPublishDrafts.value.length > 0 && selectedBatchPublishDrafts.value.every(item => !batchPublishBlocked(item) && (!batchPublishNeedsReview(item) || batchPublishReviewedIds.value.includes(item.version.id))) && Boolean(batchPublishNote.value.trim()))
const filteredDiffs = computed(() => (version.value?.diffRows || []).filter(d => diffType.value === 'all' ? !diffKinds(d).includes('unchanged') : diffKinds(d).includes(diffType.value as DiffKind)))
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
const batchChangeCounts = computed(() => aggregateChangeSummary(batch.value?.payload.results || []))
const readyBatchResults = computed(() => (batch.value?.payload.results || []).filter(item => currentBatchResultStatus(item) === 'draft' && !(item.errors || 0) && item.pricingReady === true && item.versionId))
const readyBatchNeedsRemoval = computed(() => readyBatchResults.value.some(item => hasCoverageRemoval(item.summary)))
const readyBatchNeedsRisk = computed(() => readyBatchResults.value.some(item => (item.summary?.highRisk || 0) > 0))
const versionChangeCounts = computed(() => aggregateChangeSummary(version.value ? [{ summary: version.value.summary }] : []))
const diffLabel: Record<string, string> = { added: '新增', price: '调价', rule: '规则变化', range: '重量区间变化', removed: '移除', unchanged: '无变化' }
const changeKeys = ['added', 'price', 'rule', 'range', 'removed'] as const
function changeCount(summary: Record<string, number> | undefined, key: typeof changeKeys[number]) { return Number(summary?.[key] || 0) }
function diffClass(diff: Diff) { return `diff-${diff.type}` }
function diffImpact(diff: Diff) {
  if (diffKinds(diff).includes('range')) return rangeImpact(diff)
  if (diff.type === 'added') return '新增覆盖范围'
  if (diff.type === 'removed') return '停止覆盖'
  if (diffKinds(diff).includes('price')) {
    const prices = diff.changes.filter(change => change.kind === 'price' || change.price)
    return prices.length === 1 ? changeImpact(prices[0]!, diff.row.currency || 'CNY') : `${prices.length} 项价格变化`
  }
  if (diffKinds(diff).includes('rule')) return '需复核计费规则'
  return '无变化'
}
function changeValue(change: DiffChange, value: unknown) {
  if (value == null) return '—'
  if (['起始重量', '截止重量'].includes(change.field) && typeof value === 'number') return `${Number((value * 1000).toFixed(3))}g`
  if (['起重', '最小计重', '首重', '续重'].includes(change.field) && typeof value === 'number') return `${Number(value.toFixed(3))}kg`
  if (typeof value === 'boolean') return value ? '是' : '否'
  return shown(value)
}
function compactPrice(diff: Diff, source = diff.row) {
  const price = source || diff.row, currency = price.currency || 'CNY'
  if (price.pricingModel === 'first-next' || price.firstWeightPrice) return `${currency} 首重 ${money(price.firstWeightPrice)} / 续重 ${money(price.nextWeightPrice)}`
  if (price.intervalPrice) return `${currency} ${money(price.intervalPrice)} / 档`
  return `${currency} ${money(price.pricePerKg)} / kg · 每票 ${money(price.registrationFee)}`
}
let pollTimer: ReturnType<typeof setTimeout> | undefined
let requestKey = idempotencyKey('logistics-import'), reviewKey = idempotencyKey('logistics-review'), activationKey = idempotencyKey('logistics-activation'), batchPublishKey = idempotencyKey('logistics-provider-publish')
let disposed = false, selectionEpoch = 0

async function run(action: () => Promise<void | PreparedDownload>) {
  if (busy.value) return
  busy.value = true; error.value = ''; message.value = ''
  try { const result = await action(); if (result && !disposed) preparedDownload.value = result } catch (e) { error.value = e instanceof Error ? e.message : '操作失败，请重试' } finally { busy.value = false }
}
function filters() { return new URLSearchParams({ query: query.value.trim(), country: country.value.trim(), attribute: attribute.value.trim(), page: String(page.value), size: String(pageSize.value) }) }
function versionFilters(id: string) { return new URLSearchParams({ versionId: id }) }
function currentBatchResultStatus(result: Batch['payload']['results'][number]) { return workspace.value?.versions.find(item => item.id === result.versionId)?.status || result.status }
function batchResultReadiness(result: Batch['payload']['results'][number]) {
  if ((result.errors || 0) > 0) return `存在 ${result.errors} 个阻断问题，不能发布`
  if (result.pendingReasons?.length) return `暂不能自动报价：${result.pendingReasons.slice(0, 2).join('；')}${result.pendingReasons.length > 2 ? `；另有 ${result.pendingReasons.length - 2} 项` : ''}`
  const status = currentBatchResultStatus(result)
  const channel = workspace.value?.channels.find(item => item.id === result.channelId)
  if (status === 'published' && channel?.quoteReady) return '已发布并通过计费验收，可用于报价'
  if (status === 'published') return '价格已发布，尚需完成计费验收才能用于报价'
  if (status === 'unchanged') return '与当前正式价格一致，无需生成新版本'
  return result.pricingReady === true ? '价格结构已校验，可一键发布并同步财务' : '请打开价格明细核对计费条件'
}
function rangeBarStyle(row: Price, other?: Price) {
  const min = Math.min(Number(row.weightFromKg), Number(other?.weightFromKg ?? row.weightFromKg)), max = Math.max(Number(row.weightToKg), Number(other?.weightToKg ?? row.weightToKg)), span = Math.max(max - min, 0.001)
  return { marginLeft: `${((Number(row.weightFromKg) - min) / span) * 100}%`, width: `${Math.max(((Number(row.weightToKg) - Number(row.weightFromKg)) / span) * 100, 2)}%` }
}
function batchFileState(index: number) {
  const report = batch.value?.payload.fileReports?.[index]
  if (report?.status === 'failed') return '解析失败'
  if (report?.status === 'template-pending') return '新模板待适配'
  if (report) return '已解析'
  if (batch.value?.status === 'failed') return '未完成'
  if (batch.value?.status === 'processing' && batch.value.payload.currentFileIndex === index) return '正在解析'
  if (index < Number(batch.value?.payload.processedFiles || 0)) return '已解析'
  return '等待解析'
}
function batchFileHint(index: number) {
  const state = batchFileState(index), report = batch.value?.payload.fileReports?.[index]
  if (state === '解析失败') return report?.message || '解析失败，但服务器没有返回具体原因'
  if (state === '新模板待适配') return '原表结构与通用模板及已知物流商模板不同，文件保留7天；新增解析器后可直接重试。'
  if (state === '正在解析') return '文件仍在处理中，这不是解析失败；完成后会显示工作表、价格条数和异常原因。'
  if (state === '等待解析') return '尚未轮到这个文件解析。'
  if (state === '未完成') return batch.value?.payload.error || '批次提前结束，请重试后查看具体原因。'
  return ''
}
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
function chooseFiles(event: Event) {
  files.value = [...((event.target as HTMLInputElement).files || [])]
  uploadFileSnapshot.value = files.value.map(file => ({ name: file.name, size: file.size }))
  activeUploadBatchId.value = ''
  requestKey = idempotencyKey('logistics-import')
  error.value = uploadValidation.value
}
async function upload() {
  const validation = logisticsUploadError(files.value)
  if (validation) { error.value = validation; return }
  await run(async () => {
    uploadInFlight.value = true; activeUploadBatchId.value = ''
    uploadProgress.value = { loaded: 0, total: files.value.reduce((total, file) => total + file.size, 0), percent: 0, bytesPerSecond: 0 }
    const upload = service.upload(datasetId.value, files.value, replaceDrafts.value, requestKey, progress => { uploadProgress.value = progress })
    cancelActiveUpload = upload.cancel
    try {
      const result = await upload.promise
      batch.value = result; activeUploadBatchId.value = result.id; requestKey = idempotencyKey('logistics-import'); files.value = []
      if (logisticsFileInput.value) logisticsFileInput.value.value = ''
      await refresh(); schedulePoll()
    } catch (e) {
      if (e instanceof DOMException && e.name === 'AbortError') {
        files.value = []; activeUploadBatchId.value = ''; if (logisticsFileInput.value) logisticsFileInput.value.value = ''
        message.value = '已取消上传；如果服务器已接收完整文件，稍后仍可能在“导入记录”中出现批次。'
        return
      }
      throw e
    } finally { uploadInFlight.value = false; cancelActiveUpload = null }
  })
}
function cancelUpload() { cancelActiveUpload?.() }
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
async function openVersion(id: string) { await run(async () => { version.value = await service.version(id); note.value = ''; removal.value = false; risk.value = false; editingRows.value = false; editSnapshot.value = []; detailPage.value = 0; detailTab.value = version.value.basePublishedVersionId ? 'diff' : 'rows'; diffType.value = 'all'; reviewKey = idempotencyKey('logistics-review'); await nextTick(); versionDetail.value?.scrollIntoView({ behavior: 'smooth', block: 'start' }) }) }
function startEditing() { if (!version.value?.rows) return; editSnapshot.value = structuredClone(version.value.rows); editingRows.value = true; detailTab.value = 'rows'; detailPage.value = 0 }
function cancelEditing() { if (version.value) version.value.rows = structuredClone(editSnapshot.value); editingRows.value = false; editSnapshot.value = [] }
function applySuggestion(issue: { rowKey?: string; suggestedFields?: Partial<Price> }) { if (!version.value?.rows || !issue.rowKey || !issue.suggestedFields) return; if (!editingRows.value) startEditing(); const row = version.value.rows.find(item => item.rowKey === issue.rowKey); if (row) Object.assign(row, issue.suggestedFields); detailTab.value = 'rows' }
async function saveCorrections() {
  if (!version.value?.rows) return
  const keys: Array<keyof Price> = ['weightFromKg', 'weightToKg', 'weightFromInclusive', 'weightToInclusive', 'pricePerKg', 'registrationFee', 'firstWeightKg', 'firstWeightPrice', 'nextWeightKg', 'nextWeightPrice', 'intervalPrice']
  const original = new Map(editSnapshot.value.map(row => [row.rowKey, row]))
  const changes: RowCorrection[] = version.value.rows.flatMap(row => { const before = original.get(row.rowKey); if (!before || !row.rowKey) return []; const fields: Record<string, number | boolean> = {}; for (const key of keys) if (row[key] !== before[key] && (typeof row[key] === 'number' || typeof row[key] === 'boolean')) fields[key] = row[key] as number | boolean; return Object.keys(fields).length ? [{ rowKey: row.rowKey, fields }] : [] })
  if (!changes.length) { editingRows.value = false; return }
  await run(async () => { version.value = await service.patchRows(version.value!, changes); editingRows.value = false; editSnapshot.value = []; removal.value = false; risk.value = false; await refresh(); if (batch.value && version.value?.batchId === batch.value.id) batch.value = await service.batch(batch.value.id); message.value = version.value!.errors ? `已保存修正并重新校验，仍有 ${version.value!.errors} 个阻断问题。` : '修正已保存，完整渠道已重新校验并更新差异。' })
}
async function publish() {
  if (!version.value) return
  await run(async () => { version.value = await service.review(version.value!, note.value, removal.value, risk.value, reviewKey); reviewKey = idempotencyKey('logistics-review'); await invalidatePublishedLogisticsCache(); await refresh(); message.value = version.value.quoteReady === false ? '价格已保存为正式版本；渠道仍待适配，不开放自动报价。' : '新价格已生效。' })
}
async function recompare() { await run(async () => { version.value = await service.recompare(version.value!); risk.value = false; removal.value = false; reviewKey = idempotencyKey('logistics-review'); message.value = '已按最新正式价格重新对比，请重新审核。' }) }
async function rollback() { await run(async () => { version.value = await service.rollback(version.value!, note.value); await invalidatePublishedLogisticsCache(); await refresh(); message.value = '已创建新的回滚版本，历史报价没有改写。' }) }
function selectBatchPublishProvider() {
  const drafts = batchPublishDrafts.value.filter(item => !batchPublishBlocked(item))
  batchPublishVersionIds.value = drafts.map(item => item.version.id); batchPublishReviewedIds.value = []; batchPublishNote.value = ''; batchPublishKey = idempotencyKey('logistics-provider-publish')
}
async function publishProviderDrafts() {
  if (!batchPublishProvider.value || !batchPublishReady.value) return
  const provider = batchPublishProvider.value, selectedDrafts = selectedBatchPublishDrafts.value
  await run(async () => {
    const result = await service.publishProvider(provider.id, selectedDrafts.map(item => ({ channelId: item.version.channelId, versionId: item.version.id, removalConfirmed: hasCoverageRemoval(item.version.summary), reviewConfirmed: batchPublishReviewedIds.value.includes(item.version.id) })), batchPublishNote.value.trim(), batchPublishKey)
    batchPublishKey = idempotencyKey('logistics-provider-publish'); batchPublishVersionIds.value = []; batchPublishReviewedIds.value = []; batchPublishNote.value = ''
    await invalidatePublishedLogisticsCache(); await refresh(); message.value = `已原子批量发布 ${result.count} 个${provider.name}价格版本；请逐个完成计费验收后再用于自动报价。`
  })
}
async function publishReadyBatch() {
  if (!batch.value || !readyBatchResults.value.length || !readyPublishNote.value.trim()) return
  const selections = readyBatchResults.value.map(item => ({ channelId: item.channelId!, versionId: item.versionId!, removalConfirmed: readyPublishRemoval.value, reviewConfirmed: readyPublishRisk.value }))
  await run(async () => { readyPublishResult.value = await service.publishReady(batch.value!.id, selections, readyPublishNote.value.trim(), idempotencyKey('logistics-ready-publish')); await invalidatePublishedLogisticsCache(); await refresh(); batch.value = await service.batch(batch.value!.id); message.value = `成功发布 ${readyPublishResult.value!.publishedCount} 个渠道；跳过 ${readyPublishResult.value!.skippedCount} 个；失败 ${readyPublishResult.value!.failedCount} 个。` })
}
async function prepareCutover() { await run(async () => { await service.backup(datasetId.value); cutover.value = await service.preview(datasetId.value); cutoverDirty.value = false; cutoverConfirmed.value = false; unavailable.value = false; activationKey = idempotencyKey('logistics-activation'); message.value = '旧库快照已备份，请核对渠道映射与暂不可用清单。' }) }
async function updatePreview() { await run(async () => { cutover.value = await service.preview(datasetId.value, cutover.value?.mappings); cutoverDirty.value = false; cutoverConfirmed.value = false }) }
function mappingChanged() { cutoverDirty.value = true; cutoverConfirmed.value = false }
async function activate() { await run(async () => { await service.activate(datasetId.value, cutover.value!, cutoverNote.value, unavailable.value, activationKey); await invalidatePublishedLogisticsCache(); cutover.value = null; await initialize(); message.value = '新库已整体生效，旧库已归档。请核对未迁移的模板与草稿。' }) }
onMounted(() => { void run(initialize) })
onUnmounted(() => { disposed = true; clearTimeout(pollTimer); cancelActiveUpload?.() })
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
        <div class="scroll" :aria-busy="pricesLoading"><table><thead><tr><th>物流商 / 渠道</th><th>国家</th><th>重量段</th><th>计费价格</th><th>每票费用</th><th>版本 / 状态</th></tr></thead><tbody><template v-if="pricesLoading"><tr v-for="index in 6" :key="`skeleton-${index}`" class="price-skeleton" aria-hidden="true"><td v-for="column in 6" :key="column"><span /></td></tr></template><template v-else><tr v-for="(r, i) in prices.items" :key="`${r.versionId}-${page}-${i}`"><td><b>{{ r.providerName }}</b><small>{{ r.channelName }}</small></td><td>{{ r.areaName }}<small>{{ r.countryCode }} · {{ r.zoneName || '无分区' }}</small></td><td>{{ weightLabel(r) }}</td><td v-if="r.pricingModel === 'first-next'">首 {{ r.firstWeightKg }}kg / {{ r.currency || 'CNY' }} {{ money(r.firstWeightPrice) }}<small>续 {{ r.nextWeightKg }}kg / {{ r.currency || 'CNY' }} {{ money(r.nextWeightPrice) }}</small></td><td v-else-if="r.intervalPrice">{{ r.currency || 'CNY' }} {{ money(r.intervalPrice) }} / 档</td><td v-else>{{ r.currency || 'CNY' }} {{ money(r.pricePerKg) }} / kg</td><td>{{ r.currency || 'CNY' }} {{ money(r.registrationFee) }}</td><td>V{{ r.versionNumber }}<small :class="{ warning: r.quoteReady === false }">{{ r.quoteReady === false ? '价格已记录 · 计费待适配' : '可自动报价' }}</small></td></tr><tr v-if="!prices.items.length"><td colspan="6" class="empty">当前条件没有正式价格；新库请先导入并审核价格版本。</td></tr></template></tbody></table></div>
        <LogisticsPager v-if="pricesLoaded" :page="page" :size="pageSize" :total="prices.total" :total-pages="prices.totalPages" :loading="busy || pricesLoading" @page-change="changePricePage" @size-change="changePriceSize" />
      </section>

      <section v-if="tab === 'imports'" class="stack">
        <div v-if="!archived" class="card import-card">
          <h2>向当前物流库导入价格</h2>
          <p class="import-target"><b>本次导入目标：</b>{{ importTarget }}</p>
          <p><a href="/templates/logistics-v2.xlsx" download="物流标准导入模板V2.xlsx">下载新版标准模板（含示例和填写说明）</a></p>
          <p class="muted">支持一次选择并上传1–30个 .xls / .xlsx 文件，每个文件不超过100MB、同一批次总计不超过500MB。多个文件会进入同一批次并逐个显示解析进度；未审核价格不影响报价。</p>
          <div class="toolbar">
            <input ref="logisticsFileInput" aria-label="物流报价文件" type="file" accept=".xls,.xlsx" multiple :disabled="busy || !selected" @change="chooseFiles">
            <button class="primary" :disabled="busy || !selected || !files.length || Boolean(uploadValidation)" @click="upload">{{ uploadInFlight ? `${uploadProgress.percent}%` : `上传并解析 ${files.length} 份文件` }}</button>
          </div>
          <p v-if="!selected" class="warning">请先选择物流库，再上传价格文件。</p>
          <p v-if="uploadValidation" class="notice error">{{ uploadValidation }}</p>
          <div v-if="displayedUploadFiles.length && (files.length || uploadStatusVisible)" class="upload-file-list" aria-label="待上传或正在处理的文件">
            <span v-for="file in displayedUploadFiles" :key="`${file.name}-${file.size}`"><b>{{ file.name }}</b><small>{{ formatTransferBytes(file.size) }}</small></span>
          </div>
          <div v-if="uploadStatusVisible" class="logistics-upload-status" role="status">
            <div class="upload-status-heading"><b>{{ uploadStatusText }}</b><strong>{{ uploadStatusPercent }}%</strong></div>
            <progress :value="uploadStatusPercent" max="100" />
            <p v-if="uploadInFlight" class="muted">已上传 {{ formatTransferBytes(uploadProgress.loaded) }} / {{ formatTransferBytes(uploadProgress.total) }}<template v-if="uploadProgress.bytesPerSecond"> · {{ formatTransferBytes(uploadProgress.bytesPerSecond) }}/s</template></p>
            <p v-else-if="activeUploadBatch" class="muted">批次 {{ activeUploadBatch.id }} · {{ activeUploadBatch.payload.processedFiles || 0 }}/{{ activeUploadBatch.payload.totalFiles || activeUploadBatch.payload.files.length }} 个文件已解析</p>
            <button v-if="uploadInFlight" type="button" @click="cancelUpload">取消上传</button>
          </div>
          <label class="check"><input v-model="replaceDrafts" type="checkbox">如果已有不同待审稿，确认终止旧稿并保留历史，再生成新稿</label>
        </div>
        <LogisticsRequiredChannels v-if="datasetId" :dataset-id="datasetId" :preparing="selected?.status === 'preparing'" :refresh-key="acceptanceRefresh" @updated="acceptanceUpdated" />
        <div class="card"><h2>导入记录</h2><div class="scroll"><table><thead><tr><th>时间</th><th>原始文件</th><th>状态</th><th>进度</th><th>操作</th></tr></thead><tbody><tr v-for="b in batches" :key="b.id"><td>{{ b.created_at }}</td><td>{{ b.files.map(f => f.name).join('、') }}</td><td>{{ statusLabel[b.status] || b.status }}</td><td>{{ b.progress || 0 }}%</td><td><button :disabled="busy" @click="openBatch(b.id)">查看结果</button></td></tr><tr v-if="!batches.length"><td colspan="5" class="empty">暂无导入批次。</td></tr></tbody></table></div></div>
        <div v-if="batch" class="card"><div class="section-head"><h2>本批处理结果</h2><button :disabled="busy || batch.status !== 'completed'" @click="run(() => service.exportBatchDiff(batch!.id))">导出批次差异</button></div><p>{{ statusLabel[batch.status] }} · {{ completedBatchStage(batch) || statusLabel[batch.phase] || batch.phase }} · {{ batch.payload.progress || 0 }}%<span v-if="batch.payload.elapsedMs"> · 处理 {{ (batch.payload.elapsedMs / 1000).toFixed(2) }} 秒</span></p><progress :value="batch.payload.progress || 0" max="100" /><p v-if="batch.payload.error" class="warning">批次失败原因：{{ batch.payload.error }}</p><button v-if="(['failed', 'interrupted'].includes(batch.status) || batch.payload.fileReports?.some(file => file.status === 'failed')) && !archived" :disabled="busy" @click="run(async () => { batch = await service.retry(batch!.id); schedulePoll() })">重试失败 / 超时文件</button>
          <div class="batch-file-list"><details v-for="(file, i) in batch.payload.files" :key="i" :open="batchFileState(i) === '解析失败'"><summary><span>{{ file.name }}</span><b :class="{ warning: ['解析失败', '未完成'].includes(batchFileState(i)) }">{{ batchFileState(i) }}</b><button :disabled="busy || file.lifecycleStatus === 'deleted'" @click.stop="run(() => service.original(batch!.id, i))">{{ file.lifecycleStatus === 'deleted' ? '原文件已删除' : '下载原文件' }}</button><button v-if="batch.payload.fileReports?.[i]?.sourceEvidence" :disabled="busy" @click.stop="run(() => service.evidence(batch!.id, i))">查看解析证据</button></summary><p v-if="file.lifecycleStatus === 'deleted'" class="muted">原文件已按清理策略删除；文件名、SHA-256、来源行和解析证据仍保留。SHA-256：{{ file.sha256 }}</p><p v-else-if="batch.payload.fileReports?.[i]?.retentionUntil" class="warning">解析失败，原文件保留至 {{ batch.payload.fileReports?.[i]?.retentionUntil }}，期间可重试。</p><p v-if="batchFileHint(i)" :class="{ warning: ['解析失败', '未完成'].includes(batchFileState(i)) }">{{ batchFileHint(i) }}</p><ul><li v-for="s in batch.payload.fileReports?.[i]?.sheets || []" :key="s.name">{{ s.name }}：{{ statusLabel[s.status] || s.status }} · {{ s.priceRows || 0 }} 条价格<span v-if="s.message"> · {{ s.message }}</span></li></ul></details></div>
          <p v-if="['queued', 'processing'].includes(batch.status) && !batch.payload.results.length" class="notice">文件仍在处理中，暂时没有渠道结果不代表解析失败。当前步骤完成后，这里会显示价格条数、变化内容和不能报价的具体原因。</p>
          <template v-if="batch.payload.results.length"><div class="batch-result-summary" aria-label="批次状态汇总"><span>共 <b>{{ batchResultCounts.total }}</b> 个渠道</span><span>待审核 <b>{{ batchResultCounts.draft }}</b></span><span>已发布 <b>{{ batchResultCounts.published }}</b></span><span :class="{ warning: batchResultCounts.blocked > 0 }">阻断 <b>{{ batchResultCounts.blocked }}</b></span></div>
            <div class="change-summary" aria-label="批次变化汇总"><span v-for="key in changeKeys" :key="key" class="change-chip" :class="`change-${key}`">{{ diffLabel[key] }} <b>{{ batchChangeCounts[key] }}</b></span><small>这里是与当前正式版本的比较，不是最终价格表；点击“查看价格与原因”可查看每一条价格。</small></div>
            <div class="ready-publish-panel"><div><b>可一键发布 {{ readyBatchResults.length }} 个渠道</b><p>逐渠道独立发布；某个渠道失败不会影响其他渠道。已有财务绑定自动使用新版本；新增渠道进入财务设置待勾选。</p></div><label>审核备注<input v-model="readyPublishNote" maxlength="500" placeholder="例如：已核对本批价格及区间"></label><label v-if="readyBatchNeedsRemoval" class="check"><input v-model="readyPublishRemoval" type="checkbox">已确认移除或覆盖缩小</label><label v-if="readyBatchNeedsRisk" class="check"><input v-model="readyPublishRisk" type="checkbox">已确认大幅涨跌风险</label><button class="primary" :disabled="busy || !readyBatchResults.length || !readyPublishNote.trim() || (readyBatchNeedsRemoval && !readyPublishRemoval) || (readyBatchNeedsRisk && !readyPublishRisk)" @click="publishReadyBatch">发布本批可用渠道</button></div>
            <div v-if="readyPublishResult" class="publish-result"><b>发布结果：成功 {{ readyPublishResult.publishedCount }} · 跳过 {{ readyPublishResult.skippedCount }} · 失败 {{ readyPublishResult.failedCount }}</b><p v-for="item in readyPublishResult.skipped" :key="item.versionId">跳过 {{ item.channelName }}：{{ item.reason }}</p><p v-for="item in readyPublishResult.failed" :key="item.versionId">失败 {{ item.channelName }}：{{ item.reason }}</p></div>
            <div class="batch-result-toolbar"><label>搜索渠道<input v-model="batchResultQuery" placeholder="物流商或渠道名称"></label><label>物流商<select v-model="batchResultProvider"><option value="all">全部物流商</option><option v-for="provider in batchProviders" :key="provider" :value="provider">{{ provider }}</option></select></label><label>处理状态<select v-model="batchResultStatus"><option value="all">全部状态</option><option value="draft">待审核</option><option value="published">已发布</option><option value="blocked">存在阻断</option><option value="unchanged">价格未变</option></select></label></div>
            <div class="scroll"><table class="batch-results-table"><thead><tr><th>物流商 / 渠道</th><th>解析及报价状态</th><th>价格变化说明</th><th>操作</th></tr></thead><tbody><tr v-for="(r, i) in visibleBatchResults" :key="`${r.versionId || r.channelId || r.channelName}-${i}`"><td>{{ r.providerName }}<small>{{ r.channelName }}</small></td><td>{{ statusLabel[currentBatchResultStatus(r)] || currentBatchResultStatus(r) }}<small :class="{ warning: (r.errors || 0) > 0 || Boolean(r.pendingReasons?.length) }">{{ r.message || batchResultReadiness(r) }}</small></td><td><b class="comparison-explanation">{{ batchComparisonSummary(r) }}</b><div v-if="r.basePublishedVersionId !== ''" class="inline-change-summary"><span v-for="key in changeKeys" :key="key" :class="`change-${key}`">{{ diffLabel[key] }} {{ changeCount(r.summary, key) }}</span></div><small v-if="(r.errors || 0) > 0" class="warning">{{ r.errors }} 个阻断问题</small></td><td><button v-if="r.versionId" :disabled="busy" @click="openVersion(r.versionId)">查看价格与原因</button><details v-else open><summary>解析失败原因</summary><p>{{ r.message || '该渠道没有生成价格版本' }}</p><p v-for="(issue, j) in r.issues || []" :key="j">第 {{ issue.row || '—' }} 行 · {{ issue.field }}：{{ issue.message }}</p></details></td></tr><tr v-if="!visibleBatchResults.length"><td colspan="4" class="empty">当前筛选条件没有渠道结果。</td></tr></tbody></table></div>
            <footer class="pager batch-result-pager"><span>筛选结果 {{ filteredBatchResults.length }} 条 · 每页 {{ batchResultPageSize }} 条</span><button :disabled="batchResultPage === 0" @click="batchResultPage--">上一页</button><span>{{ batchResultPage + 1 }} / {{ batchResultTotalPages }}</span><button :disabled="batchResultPage + 1 >= batchResultTotalPages" @click="batchResultPage++">下一页</button></footer></template>
        </div>
        <div v-if="selected?.status === 'preparing'" class="card cutover"><h2>新库整体切换</h2><p>准备完毕后，先备份旧库并核对渠道关联。切换不删除历史报价；无法匹配的财务渠道、模板和草稿需要后续人工处理。</p><button :disabled="busy" @click="prepareCutover">备份旧库并生成切换预览</button>
          <template v-if="cutover"><p class="warning">必用清单{{ cutover.requiredConfirmed ? '已确认' : '未确认' }} · 必用 {{ cutover.requiredCount }} 个 · 未就绪 {{ cutover.requiredNotReady?.length || 0 }} 个</p><p>可自动报价 {{ cutover.readyChannels }} 个 · 暂不可用 {{ cutover.pendingChannels.length }} 个 · 未映射 {{ cutover.unmappedChannels }} 个</p><div class="scroll"><table><thead><tr><th>旧渠道</th><th>新渠道</th><th>匹配结果</th></tr></thead><tbody><tr v-for="m in cutover.mappings" :key="m.oldChannelId"><td>{{ m.oldName }}</td><td><select v-model="m.newChannelId" :disabled="busy" @change="mappingChanged"><option value="">暂不迁移，保留待处理</option><option v-for="c in readyTargets" :key="c.id" :value="c.id">{{ c.providerName }} / {{ c.name }}</option></select></td><td>{{ m.status === 'matched' ? '已匹配，需核对' : '待处理' }}</td></tr></tbody></table></div><button v-if="cutoverDirty" :disabled="busy" @click="updatePreview">按修改后的映射重新生成预览</button><details><summary>查看暂不可用渠道</summary><p v-for="c in cutover.pendingChannels" :key="c.id">{{ c.providerName }} / {{ c.name }}</p></details><details><summary>财务与模板关联变更（{{ cutover.bindingChanges?.length || 0 }} 项）</summary><p>待恢复重计价的报价草稿：{{ cutover.draftsToReprice || 0 }} 份。未映射引用保留待处理，不扩大允许范围。</p><div class="scroll"><table><thead><tr><th>类型 / 标识 / 位置</th><th>迁移前</th><th>迁移后</th><th>状态</th></tr></thead><tbody><tr v-for="(b, i) in cutover.bindingChanges || []" :key="i"><td>{{ b.kind === 'finance' ? '财务渠道限制' : '报价模板' }}<small>{{ b.id }} {{ b.path }}</small></td><td>{{ b.before }}</td><td>{{ b.after }}</td><td>{{ b.status === 'mapped' ? '仅迁移引用' : '保留待处理' }}</td></tr></tbody></table></div></details><label>切换审核备注<textarea v-model="cutoverNote" maxlength="500" /></label><label class="check"><input v-model="unavailable" type="checkbox">已确认未映射及暂不可用渠道：切换后不回退使用旧库价格</label><label class="check"><input v-model="cutoverConfirmed" type="checkbox">已核对映射、备份和影响清单，确认将当前物流列表整体换新</label><button class="primary" :disabled="busy || cutoverDirty || !cutoverConfirmed || !cutoverNote.trim() || !cutover.requiredReady || ((cutover.unmappedChannels > 0 || cutover.pendingChannels.length > 0) && !unavailable)" @click="activate">确认整体切换</button></template>
        </div>
        <details class="card advanced-operation"><summary><b>新建整套物流价格库（高级操作）</b><span>仅用于整库重建，日常更新价格无需创建</span></summary><div class="advanced-operation-body"><label>新物流库名称<input v-model="name" maxlength="120"></label><button :disabled="busy || !name.trim()" @click="createDataset">创建独立新库</button><span class="muted">不会清空当前库，也不会自动切换为生产生效库。</span></div></details>
      </section>

      <section v-if="tab === 'history'" class="stack">
        <div v-if="!archived && providersWithDrafts.length" class="card batch-publish"><div class="section-head"><div><h2>按物流商批量审核发布</h2><p>同一物流商的所选草稿在一个数据库事务中发布；任一版本校验失败则全部回滚。</p></div></div>
          <div class="toolbar"><label>物流商<select v-model="batchPublishProviderId" aria-label="批量发布物流商" @change="selectBatchPublishProvider"><option value="">请选择</option><option v-for="provider in providersWithDrafts" :key="provider.id" :value="provider.id">{{ provider.name }}</option></select></label></div>
          <template v-if="batchPublishProvider"><div class="scroll"><table><thead><tr><th>选择</th><th>渠道 / 版本</th><th>差异</th><th>风险复核</th></tr></thead><tbody><tr v-for="item in batchPublishDrafts" :key="item.version.id"><td><input v-model="batchPublishVersionIds" type="checkbox" :value="item.version.id" :disabled="busy || batchPublishBlocked(item)" :aria-label="`选择 ${item.channel?.name} V${item.version.versionNumber}`"></td><td><b>{{ item.channel?.name }}</b><small>V{{ item.version.versionNumber }} · {{ item.version.fileName }} · {{ item.version.rowCount }} 行</small></td><td><div class="inline-change-summary"><span v-for="key in changeKeys" :key="key" :class="`change-${key}`">{{ diffLabel[key] }} {{ changeCount(item.version.summary, key) }}</span></div><small v-if="batchPublishBlocked(item)" class="warning">阻断错误 {{ item.version.errors }} 个，不能发布</small></td><td><label v-if="batchPublishNeedsReview(item)" class="check"><input v-model="batchPublishReviewedIds" type="checkbox" :value="item.version.id" :disabled="busy">已核对风险、移除及覆盖缩小项</label><span v-else>无需额外确认</span></td></tr></tbody></table></div>
            <label>批量审核备注<textarea v-model="batchPublishNote" maxlength="500" placeholder="填写价格来源、影响范围和审核结论" /></label><p class="muted">已选择 {{ selectedBatchPublishDrafts.length }} 个版本。发布后仍需逐版本完成独立计费验收，未验收版本不会开放自动报价。</p><button class="primary" :disabled="busy || !batchPublishReady" @click="publishProviderDrafts">确认原子批量发布</button>
          </template>
        </div>
        <div class="card"><h2>{{ archived ? '旧库历史档案' : '渠道版本记录' }}</h2><div class="scroll"><table><thead><tr><th>渠道</th><th>版本</th><th>来源</th><th>状态</th><th>价格行</th><th>操作</th></tr></thead><tbody><tr v-for="v in workspace?.versions || []" :key="v.id"><td>{{ workspace?.channels.find(c => c.id === v.channelId)?.name }}</td><td>V{{ v.versionNumber }}</td><td>{{ v.fileName }}<small>{{ v.importedAt }}</small></td><td>{{ statusLabel[v.status] }}</td><td>{{ v.rowCount }}</td><td><button :disabled="busy" @click="openVersion(v.id)">查看与导出</button></td></tr></tbody></table></div></div>
      </section>

      <section v-if="version" ref="versionDetail" class="card version-detail"><div class="section-head"><div><p class="eyebrow">VERSION REVIEW</p><h2>{{ workspace?.channels.find(c => c.id === version?.channelId)?.name }} · V{{ version.versionNumber }}</h2><p>{{ statusLabel[version.status] }} · {{ version.fileName }}</p></div><button @click="version = null">关闭详情</button></div>
        <p v-if="version.status === 'published' && version.quoteReady === false" class="notice">该版本已发布，但计费条件尚未完全适配，暂不开放自动报价。</p>
        <p v-else-if="version.status === 'draft' && version.pricingReady === false" class="notice">该草稿存在阻断问题或待适配计费条件，修正并重新校验后才能发布。</p>
        <LogisticsBillingReview :key="`${version.id}-${version.status}`" :version-id="version.id" :readonly="archived" @updated="acceptanceUpdated" />
        <div class="toolbar"><button :disabled="busy" @click="run(() => service.exportPrices(datasetId, versionFilters(version!.id)))">导出本版本价格</button><button :disabled="busy" @click="run(() => service.exportDiff(version!))">导出本版本差异</button><button v-if="version.batchId" :disabled="busy" @click="run(() => service.original(version!.batchId!, version!.sourceFileIndex || 0))">下载原文件</button></div>
        <p class="muted">对比基线：{{ version.basePublishedVersionId || '初始价格版本（不与旧库比较）' }}</p>
        <div class="change-summary version-change-summary" aria-label="版本变化汇总"><span v-for="key in changeKeys" :key="key" class="change-chip" :class="`change-${key}`">{{ diffLabel[key] }} <b>{{ versionChangeCounts[key] }}</b></span><small>同一价格行可能同时计入多个变化类别。</small></div>
        <div class="toolbar"><button @click="detailTab = 'diff'; detailPage = 0">变化明细</button><button @click="detailTab = 'rows'; detailPage = 0">完整价格</button><button @click="detailTab = 'issues'; detailPage = 0">解析问题 {{ version.issues?.length || 0 }}</button><select v-if="detailTab === 'diff'" v-model="diffType" aria-label="差异类型" @change="detailPage = 0"><option value="all">全部差异类型</option><option v-for="(label, key) in diffLabel" :key="key" :value="key">{{ label }}</option></select><button v-if="version.status === 'draft' && !editingRows" class="primary" @click="startEditing">批量修正价格</button><template v-if="editingRows"><button @click="cancelEditing">取消修正</button><button class="primary" :disabled="busy" @click="saveCorrections">保存并重新校验</button></template></div>
        <div v-if="detailTab === 'diff'" class="scroll"><table class="diff-table"><thead><tr><th>国家 / 档位</th><th>变化类型</th><th>原值 → 新值</th><th>影响</th></tr></thead><tbody><tr v-for="d in visibleDiffs" :key="d.key" class="diff-row" :class="diffClass(d)"><td>{{ d.row.areaName }} · {{ d.row.zoneName || '无分区' }}<small>{{ weightLabel(d.row) }}</small></td><td><div class="diff-kind-list"><span v-for="kind in diffKinds(d)" :key="kind" class="change-chip" :class="`change-${kind}`">{{ diffLabel[kind] }}</span></div></td><td><div v-if="diffKinds(d).includes('range') && d.previous" class="range-compare"><div><span>旧</span><i class="old" :style="rangeBarStyle(d.previous, d.row)" /><small>{{ weightLabel(d.previous) }}</small></div><div><span>新</span><i class="next" :style="rangeBarStyle(d.row, d.previous)" /><small>{{ weightLabel(d.row) }}</small></div></div><div v-for="(c, i) in d.changes.filter(change => change.kind !== 'range')" :key="i" class="diff-change-line"><b>{{ c.field }}</b>：{{ changeValue(c, c.before) }} → {{ changeValue(c, c.after) }}</div><span v-if="!d.changes.length && d.type === 'added'">— → {{ compactPrice(d) }}</span><span v-else-if="!d.changes.length && d.type === 'removed'">{{ compactPrice(d, d.previous) }} → 已移除</span><span v-else-if="!d.changes.length">无字段变化</span></td><td><b>{{ diffImpact(d) }}</b><small v-for="(c, i) in d.changes.filter(change => change.kind === 'price' || change.price)" :key="i" :class="{ 'impact-up': Number(c.delta) > 0, 'impact-down': Number(c.delta) < 0 }">{{ c.field }}：{{ changeImpact(c, d.row.currency || 'CNY') }}</small></td></tr><tr v-if="!visibleDiffs.length"><td colspan="4" class="empty">当前筛选条件没有变化项。</td></tr></tbody></table></div>
        <div v-if="detailTab === 'rows'" class="scroll"><table v-if="!editingRows"><thead><tr><th>国家 / 档位</th><th>公斤价 / 每票费</th><th>首续重 / 区间价</th><th>来源</th><th>待适配原因</th></tr></thead><tbody><tr v-for="(r, i) in visibleRows" :key="i"><td>{{ r.areaName }} · {{ r.zoneName || '无分区' }}<small>{{ weightLabel(r) }}</small></td><td>{{ r.currency || 'CNY' }} {{ money(r.pricePerKg) }} / {{ r.currency || 'CNY' }} {{ money(r.registrationFee) }}</td><td>首 {{ r.currency || 'CNY' }} {{ money(r.firstWeightPrice) }} / 续 {{ r.currency || 'CNY' }} {{ money(r.nextWeightPrice) }}<small>区间 {{ r.currency || 'CNY' }} {{ money(r.intervalPrice) }}</small></td><td>{{ r.sourceSheet }} · 第{{ r.sourceRow }}行</td><td class="warning">{{ r.pendingReason || '—' }}</td></tr></tbody></table><table v-else class="price-editor"><thead><tr><th>国家 / 分区</th><th>起重kg</th><th>含起点</th><th>止重kg</th><th>含终点</th><th>公斤价</th><th>每票费</th><th>首重kg / 价</th><th>续重kg / 价</th><th>区间价</th><th>原表位置</th></tr></thead><tbody><tr v-for="r in visibleRows" :key="r.rowKey"><td><b>{{ r.areaName }}</b><small>{{ r.zoneName || '无分区' }}</small></td><td><input v-model.number="r.weightFromKg" type="number" min="0" step="0.001"></td><td><input v-model="r.weightFromInclusive" type="checkbox"></td><td><input v-model.number="r.weightToKg" type="number" min="0" step="0.001"></td><td><input v-model="r.weightToInclusive" type="checkbox"></td><td><input v-model.number="r.pricePerKg" type="number" min="0" step="0.01"></td><td><input v-model.number="r.registrationFee" type="number" min="0" step="0.01"></td><td><input v-model.number="r.firstWeightKg" type="number" min="0" step="0.001"><input v-model.number="r.firstWeightPrice" type="number" min="0" step="0.01"></td><td><input v-model.number="r.nextWeightKg" type="number" min="0" step="0.001"><input v-model.number="r.nextWeightPrice" type="number" min="0" step="0.01"></td><td><input v-model.number="r.intervalPrice" type="number" min="0" step="0.01"></td><td>{{ r.sourceSheet }} · 第{{ r.sourceRow }}行</td></tr></tbody></table></div>
        <ul v-if="detailTab === 'issues'" class="issue-list"><li v-for="(i, n) in version.issues || []" :key="n"><b>{{ i.level === 'error' ? '阻断' : '提醒' }}</b> · {{ i.sourceSheet || version.fileName }} · 第 {{ i.row || '—' }} 行 · {{ i.field }}：{{ i.message }}<button v-if="i.suggestedFields && version.status === 'draft'" @click="applySuggestion(i)">采用边界建议</button></li></ul>
        <footer v-if="detailTab !== 'issues'" class="pager"><span>共 {{ detailTotal }} 条</span><button :disabled="detailPage === 0" @click="detailPage--">上一页</button><span>{{ detailPage + 1 }} / {{ Math.max(1, Math.ceil(detailTotal / 50)) }}</span><button :disabled="(detailPage + 1) * 50 >= detailTotal" @click="detailPage++">下一页</button></footer>
        <div v-if="!archived" class="review"><label>审核 / 回滚备注<textarea v-model="note" maxlength="500" placeholder="价格来源、核对结论与调整原因" /></label><template v-if="version.status === 'draft'"><label class="check"><input v-model="removal" type="checkbox">已核对并确认本版本移除或重量覆盖缩小的国家、重量档位</label><label class="check"><input v-model="risk" type="checkbox">已核对大幅价格变化及相关风险</label><div class="toolbar"><button :disabled="busy" @click="recompare">重新对比最新价格</button><button class="primary" :disabled="busy || version.errors > 0 || !note.trim() || (hasCoverageRemoval(version.summary) && !removal) || ((version.summary.highRisk || 0) > 0 && !risk)" @click="publish">审核并发布价格</button></div></template><button v-else-if="version.status === 'superseded'" :disabled="busy || !note.trim()" @click="rollback">以此历史价格创建回滚版本</button></div>
      </section>
    </main>
  </div>
</template>

<style scoped>
.logistics-page{min-height:100vh;background:#f3f5f7;color:#243542;font-size:14px}main{max-width:1500px;margin:0 auto;padding:28px 32px 64px}.page-heading,.section-head{display:flex;justify-content:space-between;align-items:center;gap:24px}.page-heading h1{font-size:28px;margin:5px 0 8px;letter-spacing:-.6px}.page-heading p{color:#71818d;margin:4px 0}.eyebrow{font-size:11px;font-weight:750;letter-spacing:1.5px;color:#a76b30!important}.dataset-picker{display:flex;align-items:flex-end;gap:12px}.dataset-picker select{min-width:260px}.metrics{display:grid;grid-template-columns:repeat(4,1fr);gap:16px;margin:24px 0}.metrics>div{background:#fff;border:1px solid #e2e8ed;border-radius:10px;padding:18px 22px}.metrics small{color:#7c8b96;display:block}.metrics strong{display:block;font-size:30px;line-height:1.3;margin-top:6px}.tabs{display:flex;gap:28px;border-bottom:1px solid #dbe3e8;margin-bottom:20px}.tabs button{border:0;border-radius:0;background:none;padding:13px 0;color:#6d7d88;font-size:15px}.tabs .active{color:#c8752d;border-bottom:3px solid #df8d41;font-weight:700}.card{border:1px solid #dfe6eb;background:white;border-radius:10px;padding:22px;box-shadow:0 2px 4px #152a3b03}.stack{display:grid;gap:18px}h2{font-size:18px;margin:0 0 14px}.toolbar{display:flex;align-items:flex-end;gap:12px;flex-wrap:wrap;margin-bottom:16px}.toolbar>label{flex:1;min-width:150px;max-width:260px}label{display:flex;flex-direction:column;gap:7px;font-size:12px;color:#667781}input,select,textarea,button{font:inherit}input:not([type=checkbox]),select,textarea{border:1px solid #cdd8e0;border-radius:6px;padding:10px 12px;background:#fff;color:#253d4c}textarea{width:100%;min-height:70px;box-sizing:border-box;resize:vertical}button{border:1px solid #ccd7df;border-radius:6px;background:white;color:#435e70;padding:9px 14px;cursor:pointer;white-space:nowrap}button:hover:not(:disabled){background:#f4f8fa}button.primary{background:#da853c;border-color:#da853c;color:white}button.primary:hover:not(:disabled){background:#c77730}button:disabled{opacity:.45;cursor:not-allowed}.scroll{overflow:auto}table{width:100%;border-collapse:collapse;text-align:left}th{background:#f4f7f9;font-size:12px;color:#74838e;padding:12px;font-weight:600;white-space:nowrap}td{border-bottom:1px solid #e9eef1;padding:13px 12px;vertical-align:top;line-height:1.65}td small{display:block;color:#81919c;font-size:12px;max-width:460px;white-space:normal}td b{font-weight:600}td button{font-size:12px;padding:5px 10px}.notice{padding:14px 18px;background:#fff4df;border:1px solid #f1d8ad;border-radius:8px;line-height:1.7}.notice.error{background:#fff0ee;border-color:#eebcb5;color:#ab3e32}.notice.success{background:#edf8f2;border-color:#badfc9;color:#24724a}.muted{color:#7d8d98;line-height:1.7}.warning{color:#bc762b!important}.tag{padding:8px 10px;border-radius:6px;background:#edf1f5;white-space:nowrap;font-size:12px}.tag.active{background:#e8f6ed;color:#348359}.tag.preparing{background:#fff0d8;color:#b77724}.empty{text-align:center;color:#82929c;padding:36px}.pager{display:flex;gap:12px;align-items:center;justify-content:flex-end;margin-top:18px;color:#83939d;font-size:12px}.pager>span:first-child{margin-right:auto}.check{display:flex;flex-direction:row;align-items:center;margin:14px 0;font-size:13px}.cutover{border-top:3px solid #dd9247}.cutover select{max-width:500px;width:100%}.review{margin-top:24px;padding-top:20px;border-top:1px solid #e2e8ed}.version-detail{margin-top:24px;border-top:3px solid #54788e}.section-head p{color:#7f8f9b}progress{width:100%;height:9px;accent-color:#db8b42;margin:8px 0 14px}details{margin:12px 0;color:#657986}summary{cursor:pointer}summary button{font-size:11px;margin-left:8px}li{line-height:1.9}input[type=checkbox]{accent-color:#d58842}.batch-result-summary{display:flex;align-items:center;gap:10px;flex-wrap:wrap;margin:16px 0 12px}.batch-result-summary span{padding:7px 11px;border:1px solid #e2e8ed;border-radius:999px;background:#f8fafb;color:#667984;font-size:12px}.batch-result-summary b{color:#294250}.batch-result-toolbar{display:grid;grid-template-columns:minmax(240px,1fr) minmax(150px,220px) minmax(150px,220px);gap:12px;align-items:end;margin-bottom:12px}.batch-results-table td{padding-top:10px;padding-bottom:10px}.batch-result-pager{margin-top:12px}@media(max-width:800px){main{padding:18px 12px}.page-heading{align-items:flex-start;flex-direction:column}.metrics{grid-template-columns:repeat(2,1fr)}.card{padding:16px}.dataset-picker{flex-wrap:wrap}td{min-width:100px}.toolbar>label{max-width:none}.tabs{gap:20px}.batch-result-toolbar{grid-template-columns:1fr}.batch-result-pager{flex-wrap:wrap}.batch-result-pager>span:first-child{width:100%}}
.import-target{padding:13px 15px;border:1px solid #efc28f;border-radius:8px;background:#fff8ef;color:#714b27}.import-target b{color:#9a5c22}.upload-file-list{display:grid;grid-template-columns:repeat(auto-fit,minmax(240px,1fr));gap:8px;margin:12px 0}.upload-file-list>span{display:flex;justify-content:space-between;align-items:center;gap:12px;padding:9px 11px;border:1px solid #dfe7ec;border-radius:7px;background:#f7f9fa;min-width:0}.upload-file-list b{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:12px}.upload-file-list small{color:#758691;white-space:nowrap}.logistics-upload-status{margin:14px 0;padding:14px 16px;border:1px solid #bcd6e8;border-radius:8px;background:#f3f9fd}.upload-status-heading{display:flex;justify-content:space-between;align-items:center;gap:16px}.upload-status-heading strong{color:#246da5}.logistics-upload-status progress{margin:10px 0 4px;accent-color:#3487bf}.logistics-upload-status p{margin:4px 0 10px}.change-summary,.inline-change-summary,.diff-kind-list{display:flex;align-items:center;gap:8px;flex-wrap:wrap}.change-summary{margin:14px 0}.change-summary>small{color:#80909b}.change-chip,.inline-change-summary span{display:inline-flex;align-items:center;gap:5px;padding:6px 9px;border:1px solid transparent;border-radius:999px;font-size:12px;font-weight:650;white-space:nowrap}.change-added{background:#edf8f2!important;border-color:#bfe2cd!important;color:#24724a!important}.change-price{background:#fff0ed!important;border-color:#f1c0b8!important;color:#bd493b!important}.change-rule{background:#fff7df!important;border-color:#ead69a!important;color:#956a13!important}.change-range{background:#eaf4ff!important;border-color:#b9d7f5!important;color:#246da5!important}.change-removed{background:#f3f4f5!important;border-color:#d4d8dc!important;color:#7b4545!important}.change-unchanged{background:#f3f5f6!important;border-color:#dde2e5!important;color:#70808b!important}.version-change-summary{padding:12px 0;border-top:1px solid #edf0f2;border-bottom:1px solid #edf0f2}.diff-row.diff-added{background:#f8fdf9}.diff-row.diff-price{background:#fff9f7}.diff-row.diff-rule{background:#fffdf5}.diff-row.diff-range{background:#f6faff}.diff-row.diff-removed{background:#fbf8f8}.diff-change-line{margin-bottom:5px}.impact-up{color:#c44135!important}.impact-down{color:#278155!important}.advanced-operation{margin-top:0}.advanced-operation>summary{display:flex;align-items:center;gap:20px;list-style:none;color:#243542}.advanced-operation>summary::-webkit-details-marker{display:none}.advanced-operation>summary::after{content:'⌄';margin-left:auto;color:#71818d}.advanced-operation[open]>summary::after{content:'⌃'}.advanced-operation>summary span{font-size:12px;color:#7d8d98}.advanced-operation-body{display:flex;align-items:flex-end;gap:12px;flex-wrap:wrap;margin-top:18px;padding-top:18px;border-top:1px solid #e7ecef}.advanced-operation-body label{min-width:260px}.diff-table th:nth-child(3){min-width:340px}.diff-table th:nth-child(4){min-width:190px}
.batch-file-list{display:grid;gap:8px;margin:14px 0}.batch-file-list details{margin:0;padding:10px 12px;border:1px solid #e1e7eb;border-radius:7px;background:#fafbfc}.batch-file-list summary{display:flex;align-items:center;gap:12px}.batch-file-list summary>span{min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.batch-file-list summary>b{margin-left:auto;color:#4f7a5f;font-size:12px}.comparison-explanation{display:block;margin-bottom:8px;max-width:620px;font-size:12px;line-height:1.65;color:#344f60}.version-detail{scroll-margin-top:18px}
.price-skeleton span{display:block;width:80%;height:14px;border-radius:5px;background:linear-gradient(90deg,#edf1f4 25%,#f7f9fa 50%,#edf1f4 75%);background-size:200% 100%;animation:price-skeleton 1.2s infinite}.price-skeleton td{height:40px}@keyframes price-skeleton{to{background-position:-200% 0}}
.ready-publish-panel{display:grid;grid-template-columns:minmax(280px,1fr) minmax(240px,1fr) auto;gap:14px;align-items:end;margin:16px 0;padding:16px;border:1px solid #b9ddc9;border-radius:9px;background:#f2faf5}.ready-publish-panel p{margin:5px 0 0;color:#648071;line-height:1.6}.ready-publish-panel .check{margin:0}.publish-result{margin:12px 0;padding:12px 15px;border-radius:8px;background:#f7f9fa}.publish-result p{margin:6px 0;color:#80603e}.range-compare{display:grid;gap:6px;margin-bottom:8px}.range-compare>div{position:relative;height:24px;background:#edf1f4;border-radius:4px;overflow:hidden}.range-compare span{position:absolute;left:7px;top:3px;z-index:2;font-size:11px;font-weight:700}.range-compare i{position:absolute;top:0;height:100%;border-radius:4px;opacity:.72}.range-compare i.old{background:#9aa9b3}.range-compare i.next{background:#4e9fe0}.range-compare small{position:absolute;right:7px;top:3px;z-index:2;color:#314a59}.price-editor input[type=number]{width:90px;padding:7px}.price-editor td{white-space:nowrap}.price-editor td:nth-child(8) input,.price-editor td:nth-child(9) input{display:block;margin-bottom:5px}.issue-list{padding:0;list-style:none}.issue-list li{margin:8px 0;padding:12px 14px;border-left:4px solid #d58b42;background:#fff8ef}.issue-list button{margin-left:12px;font-size:12px;padding:4px 8px}@media(max-width:900px){.ready-publish-panel{grid-template-columns:1fr}.ready-publish-panel button{justify-self:start}}
</style>
