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
const batchResultQuery = ref(''), batchResultProvider = ref('all'), batchResultStatus = ref('all'), batchResultFocus = ref<'all' | 'added' | 'price' | 'range' | 'issues'>('all'), batchResultPage = ref(0)
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
    const matchesFocus = batchResultFocus.value === 'all'
      || (batchResultFocus.value === 'issues' ? batchResultHasIssues(result) : changeCount(result.summary, batchResultFocus.value) > 0)
    return matchesKeyword && matchesProvider && matchesStatus && matchesFocus
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
const batchFocusCounts = computed(() => {
  const results = batch.value?.payload.results || []
  return {
    all: results.length,
    added: results.filter(result => changeCount(result.summary, 'added') > 0).length,
    price: results.filter(result => changeCount(result.summary, 'price') > 0).length,
    range: results.filter(result => changeCount(result.summary, 'range') > 0).length,
    issues: results.filter(batchResultHasIssues).length,
  }
})
const batchFailedFiles = computed(() => batch.value?.payload.fileReports?.filter(file => file.status === 'failed' || file.status === 'template-pending').length || 0)
const readyBatchResults = computed(() => (batch.value?.payload.results || []).filter(item => currentBatchResultStatus(item) === 'draft' && !(item.errors || 0) && item.pricingReady === true && item.versionId))
const readyBatchNeedsRemoval = computed(() => readyBatchResults.value.some(item => hasCoverageRemoval(item.summary)))
const readyBatchNeedsRisk = computed(() => readyBatchResults.value.some(item => (item.summary?.highRisk || 0) > 0))
const versionChangeCounts = computed(() => aggregateChangeSummary(version.value ? [{ summary: version.value.summary }] : []))
const diffLabel: Record<string, string> = { added: '新增', price: '调价', rule: '规则变化', range: '重量区间变化', removed: '移除', unchanged: '无变化' }
const changeKeys = ['added', 'price', 'rule', 'range', 'removed'] as const
function changeCount(summary: Record<string, number> | undefined, key: typeof changeKeys[number]) { return Number(summary?.[key] || 0) }
function batchResultHasIssues(result: Batch['payload']['results'][number]) { return (result.errors || 0) > 0 || Boolean(result.pendingReasons?.length) || !result.versionId }
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
let disposed = false, selectionEpoch = 0, autoOpenLatestBatch = true

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
    if (tab.value === 'imports' && autoOpenLatestBatch && !batch.value && b.length) {
      batch.value = await service.batch(b[0]!.id)
      resetBatchResultView()
      schedulePoll()
    }
  } finally {
    if (id === datasetId.value && epoch === selectionEpoch) pricesLoading.value = false
  }
}
async function changeDataset() {
  clearDownload()
  selectionEpoch++; autoOpenLatestBatch = true; clearTimeout(pollTimer); batch.value = null; version.value = null; cutover.value = null; page.value = 0; files.value = []
  workspace.value = null; batches.value = []; pricesLoaded.value = false; prices.value = { items: [], total: 0, page: 0, size: pageSize.value, totalPages: 0 }
  await run(refresh)
}
async function initialize() {
  datasets.value = await service.datasets()
  datasetId.value ||= datasets.value.find(d => d.id === route.query.dataset)?.id || datasets.value.find(d => d.status === 'active')?.id || datasets.value[0]?.id || ''
  if (datasetId.value) await refresh()
}
watch([datasetId, tab, page, pageSize], () => { if (datasetId.value) void router.replace({ query: { ...route.query, dataset: datasetId.value, logisticsTab: tab.value, ...logisticsPageQuery(page.value, pageSize.value) } }) })
watch([batchResultQuery, batchResultProvider, batchResultStatus, batchResultFocus], () => { batchResultPage.value = 0 })
watch(tab, value => {
  if (value === 'imports' && autoOpenLatestBatch && !batch.value && batches.value.length) void openBatch(batches.value[0]!.id)
})
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
function resetBatchResultView() { batchResultQuery.value = ''; batchResultProvider.value = 'all'; batchResultStatus.value = 'all'; batchResultFocus.value = 'all'; batchResultPage.value = 0; readyPublishResult.value = null }
async function openBatch(id: string) { autoOpenLatestBatch = true; await run(async () => { batch.value = await service.batch(id); resetBatchResultView(); schedulePoll() }) }
function closeBatch() { autoOpenLatestBatch = false; clearTimeout(pollTimer); batch.value = null; version.value = null; resetBatchResultView() }
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

      <section v-if="tab === 'imports' && !version" class="stack">
        <template v-if="batch">
          <div class="batch-review-workbench">
            <header class="review-workbench-head">
              <div class="review-title"><span class="review-icon">▤</span><div><p class="eyebrow">IMPORT REVIEW</p><h2>物流价格批量审核</h2><p>{{ batch.payload.files.map(file => file.name).join('、') }}</p></div></div>
              <div class="review-steps" aria-label="审核步骤"><span class="done"><i>✓</i>已解析</span><b></b><span :class="{ current: batchResultCounts.draft || batchResultCounts.blocked }"><i>2</i>核对变化</span><b></b><span :class="{ done: !batchResultCounts.draft && !batchResultCounts.blocked }"><i>3</i>发布给财务</span></div>
              <div class="review-head-actions"><button :disabled="busy || batch.status !== 'completed'" @click="run(() => service.exportBatchDiff(batch!.id))">导出差异</button><button @click="closeBatch">上传新价格 / 批次列表</button></div>
            </header>

            <div v-if="['queued', 'processing'].includes(batch.status)" class="review-progress"><div><b>{{ uploadStatusText || statusLabel[batch.phase] || batch.phase }}</b><strong>{{ batch.payload.progress || 0 }}%</strong></div><progress :value="batch.payload.progress || 0" max="100" /></div>
            <p v-if="batch.payload.error" class="notice error">批次失败原因：{{ batch.payload.error }}</p>
            <nav class="review-focus-tabs" aria-label="变化类型筛选">
              <button :class="{ active: batchResultFocus === 'all' }" @click="batchResultFocus = 'all'">全部渠道 <b>{{ batchFocusCounts.all }}</b></button>
              <button :class="{ active: batchResultFocus === 'added' }" @click="batchResultFocus = 'added'">新增渠道 <b>{{ batchFocusCounts.added }}</b></button>
              <button :class="{ active: batchResultFocus === 'price' }" @click="batchResultFocus = 'price'">价格变化 <b>{{ batchFocusCounts.price }}</b></button>
              <button :class="{ active: batchResultFocus === 'range' }" @click="batchResultFocus = 'range'">重量区间 <b>{{ batchFocusCounts.range }}</b></button>
              <button class="issue-tab" :class="{ active: batchResultFocus === 'issues' }" @click="batchResultFocus = 'issues'">解析 / 区间问题 <b>{{ batchFocusCounts.issues + batchFailedFiles }}</b></button>
            </nav>

            <div class="batch-review-layout">
              <section class="review-main-panel">
                <div class="batch-result-toolbar"><label>搜索渠道<input v-model="batchResultQuery" placeholder="物流商或渠道名称"></label><label>物流商<select v-model="batchResultProvider"><option value="all">全部物流商</option><option v-for="provider in batchProviders" :key="provider" :value="provider">{{ provider }}</option></select></label><label>处理状态<select v-model="batchResultStatus"><option value="all">全部状态</option><option value="draft">待审核</option><option value="published">已发布</option><option value="blocked">存在阻断</option><option value="unchanged">价格未变</option></select></label></div>
                <div class="scroll"><table class="batch-results-table"><thead><tr><th>渠道</th><th>本次变化</th><th>审核结论</th><th>操作</th></tr></thead><tbody><tr v-for="(r, i) in visibleBatchResults" :key="`${r.versionId || r.channelId || r.channelName}-${i}`" :class="{ 'row-has-issue': batchResultHasIssues(r) }"><td><b>{{ r.channelName }}</b><small>{{ r.providerName }} · {{ r.priceRows ? `${r.priceRows} 条价格` : '已生成价格版本' }}</small></td><td><b class="comparison-explanation">{{ batchComparisonSummary(r) }}</b><div class="inline-change-summary"><span v-for="key in changeKeys" :key="key" :class="`change-${key}`">{{ diffLabel[key] }} {{ changeCount(r.summary, key) }}</span></div></td><td><strong class="review-status" :class="currentBatchResultStatus(r)">{{ statusLabel[currentBatchResultStatus(r)] || currentBatchResultStatus(r) }}</strong><small :class="{ warning: batchResultHasIssues(r) }">{{ r.message || batchResultReadiness(r) }}</small></td><td><button v-if="r.versionId" class="row-action" :disabled="busy" @click="openVersion(r.versionId)">{{ batchResultHasIssues(r) ? '修正问题' : '核对价格' }} →</button><details v-else open><summary>查看失败原因</summary><p>{{ r.message || '该渠道没有生成价格版本' }}</p><p v-for="(issue, j) in r.issues || []" :key="j">第 {{ issue.row || '—' }} 行 · {{ issue.field }}：{{ issue.message }}</p></details></td></tr><tr v-if="!visibleBatchResults.length"><td colspan="4" class="empty">当前筛选条件没有渠道结果。</td></tr></tbody></table></div>
                <footer class="pager batch-result-pager"><span>筛选结果 {{ filteredBatchResults.length }} 条 · 每页 {{ batchResultPageSize }} 条</span><button :disabled="batchResultPage === 0" @click="batchResultPage--">上一页</button><span>{{ batchResultPage + 1 }} / {{ batchResultTotalPages }}</span><button :disabled="batchResultPage + 1 >= batchResultTotalPages" @click="batchResultPage++">下一页</button></footer>
                <details class="batch-source-files"><summary>原始文件与解析证据（{{ batch.payload.files.length }}）</summary><div class="batch-file-list"><details v-for="(file, i) in batch.payload.files" :key="i" :open="batchFileState(i) === '解析失败'"><summary><span>{{ file.name }}</span><b :class="{ warning: ['解析失败', '未完成'].includes(batchFileState(i)) }">{{ batchFileState(i) }}</b><button :disabled="busy || file.lifecycleStatus === 'deleted'" @click.stop="run(() => service.original(batch!.id, i))">{{ file.lifecycleStatus === 'deleted' ? '原文件已删除' : '下载原文件' }}</button><button v-if="batch.payload.fileReports?.[i]?.sourceEvidence" :disabled="busy" @click.stop="run(() => service.evidence(batch!.id, i))">解析证据</button></summary><p v-if="file.lifecycleStatus === 'deleted'" class="muted">原文件已按清理策略删除；SHA-256：{{ file.sha256 }}</p><p v-else-if="batch.payload.fileReports?.[i]?.retentionUntil" class="warning">解析失败，原文件保留至 {{ batch.payload.fileReports?.[i]?.retentionUntil }}。</p><p v-if="batchFileHint(i)" :class="{ warning: ['解析失败', '未完成'].includes(batchFileState(i)) }">{{ batchFileHint(i) }}</p></details></div></details>
              </section>

              <aside class="release-check-panel">
                <h3>发布前检查</h3>
                <button @click="batchResultFocus = 'price'"><span class="check-dot price">¥</span><span>价格变化<small>需核对旧价、新价和涨跌幅</small></span><b>{{ batchChangeCounts.price }}</b></button>
                <button @click="batchResultFocus = 'range'"><span class="check-dot range">↔</span><span>重量区间变化<small>包含扩大、缩小、重叠和断档</small></span><b>{{ batchChangeCounts.range }}</b></button>
                <button @click="batchResultFocus = 'issues'"><span class="check-dot issue">!</span><span>解析 / 区间问题<small>红色问题修正后才能发布</small></span><b>{{ batchFocusCounts.issues + batchFailedFiles }}</b></button>
                <button @click="batchResultStatus = 'draft'; batchResultFocus = 'all'"><span class="check-dot ready">✓</span><span>可发布渠道<small>逐渠道独立事务，不互相阻塞</small></span><b>{{ readyBatchResults.length }}</b></button>
                <p class="release-note">已有渠道发布后自动切换新价格；新增渠道进入财务设置列表，默认不勾选。</p>
                <button v-if="(['failed', 'interrupted'].includes(batch.status) || batch.payload.fileReports?.some(file => file.status === 'failed')) && !archived" class="retry-button" :disabled="busy" @click="run(async () => { batch = await service.retry(batch!.id); schedulePoll() })">重试失败 / 超时文件</button>
              </aside>
            </div>

            <div v-if="readyPublishResult" class="publish-result"><b>发布结果：成功 {{ readyPublishResult.publishedCount }} · 跳过 {{ readyPublishResult.skippedCount }} · 失败 {{ readyPublishResult.failedCount }}</b><p v-for="item in readyPublishResult.skipped" :key="item.versionId">跳过 {{ item.channelName }}：{{ item.reason }}</p><p v-for="item in readyPublishResult.failed" :key="item.versionId">失败 {{ item.channelName }}：{{ item.reason }}</p></div>
            <footer class="review-publish-bar"><div><b>{{ batchResultCounts.blocked || batchFocusCounts.issues + batchFailedFiles ? `还有 ${batchFocusCounts.issues + batchFailedFiles} 个问题需要处理` : '本批检查通过' }}</b><small>正常渠道不必等待失败文件，可直接发布给财务使用。</small></div><label>审核备注<input v-model="readyPublishNote" maxlength="500" placeholder="填写价格来源和审核结论"></label><label v-if="readyBatchNeedsRemoval" class="check"><input v-model="readyPublishRemoval" type="checkbox">确认移除 / 缩小</label><label v-if="readyBatchNeedsRisk" class="check"><input v-model="readyPublishRisk" type="checkbox">确认大幅涨跌</label><button class="primary publish-all" :disabled="busy || !readyBatchResults.length || !readyPublishNote.trim() || (readyBatchNeedsRemoval && !readyPublishRemoval) || (readyBatchNeedsRisk && !readyPublishRisk)" @click="publishReadyBatch">一键发布 {{ readyBatchResults.length }} 个可用渠道</button></footer>
          </div>
        </template>

        <template v-else>
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
        <div v-if="selected?.status === 'preparing'" class="card cutover"><h2>新库整体切换</h2><p>准备完毕后，先备份旧库并核对渠道关联。切换不删除历史报价；无法匹配的财务渠道、模板和草稿需要后续人工处理。</p><button :disabled="busy" @click="prepareCutover">备份旧库并生成切换预览</button>
          <template v-if="cutover"><p class="warning">必用清单{{ cutover.requiredConfirmed ? '已确认' : '未确认' }} · 必用 {{ cutover.requiredCount }} 个 · 未就绪 {{ cutover.requiredNotReady?.length || 0 }} 个</p><p>可自动报价 {{ cutover.readyChannels }} 个 · 暂不可用 {{ cutover.pendingChannels.length }} 个 · 未映射 {{ cutover.unmappedChannels }} 个</p><div class="scroll"><table><thead><tr><th>旧渠道</th><th>新渠道</th><th>匹配结果</th></tr></thead><tbody><tr v-for="m in cutover.mappings" :key="m.oldChannelId"><td>{{ m.oldName }}</td><td><select v-model="m.newChannelId" :disabled="busy" @change="mappingChanged"><option value="">暂不迁移，保留待处理</option><option v-for="c in readyTargets" :key="c.id" :value="c.id">{{ c.providerName }} / {{ c.name }}</option></select></td><td>{{ m.status === 'matched' ? '已匹配，需核对' : '待处理' }}</td></tr></tbody></table></div><button v-if="cutoverDirty" :disabled="busy" @click="updatePreview">按修改后的映射重新生成预览</button><details><summary>查看暂不可用渠道</summary><p v-for="c in cutover.pendingChannels" :key="c.id">{{ c.providerName }} / {{ c.name }}</p></details><details><summary>财务与模板关联变更（{{ cutover.bindingChanges?.length || 0 }} 项）</summary><p>待恢复重计价的报价草稿：{{ cutover.draftsToReprice || 0 }} 份。未映射引用保留待处理，不扩大允许范围。</p><div class="scroll"><table><thead><tr><th>类型 / 标识 / 位置</th><th>迁移前</th><th>迁移后</th><th>状态</th></tr></thead><tbody><tr v-for="(b, i) in cutover.bindingChanges || []" :key="i"><td>{{ b.kind === 'finance' ? '财务渠道限制' : '报价模板' }}<small>{{ b.id }} {{ b.path }}</small></td><td>{{ b.before }}</td><td>{{ b.after }}</td><td>{{ b.status === 'mapped' ? '仅迁移引用' : '保留待处理' }}</td></tr></tbody></table></div></details><label>切换审核备注<textarea v-model="cutoverNote" maxlength="500" /></label><label class="check"><input v-model="unavailable" type="checkbox">已确认未映射及暂不可用渠道：切换后不回退使用旧库价格</label><label class="check"><input v-model="cutoverConfirmed" type="checkbox">已核对映射、备份和影响清单，确认将当前物流列表整体换新</label><button class="primary" :disabled="busy || cutoverDirty || !cutoverConfirmed || !cutoverNote.trim() || !cutover.requiredReady || ((cutover.unmappedChannels > 0 || cutover.pendingChannels.length > 0) && !unavailable)" @click="activate">确认整体切换</button></template>
        </div>
        <details class="card advanced-operation"><summary><b>新建整套物流价格库（高级操作）</b><span>仅用于整库重建，日常更新价格无需创建</span></summary><div class="advanced-operation-body"><label>新物流库名称<input v-model="name" maxlength="120"></label><button :disabled="busy || !name.trim()" @click="createDataset">创建独立新库</button><span class="muted">不会清空当前库，也不会自动切换为生产生效库。</span></div></details>
        </template>
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

      <section v-if="version" ref="versionDetail" class="version-review-workbench">
        <header class="review-workbench-head">
          <div class="review-title"><span class="review-icon">▤</span><div><p class="eyebrow">PRICE REVIEW</p><h2>{{ workspace?.channels.find(c => c.id === version?.channelId)?.name }} · V{{ version.versionNumber }}</h2><p>{{ version.fileName }} · {{ statusLabel[version.status] }}</p></div></div>
          <div class="review-steps" aria-label="审核步骤"><span class="done"><i>✓</i>已解析</span><b></b><span class="current"><i>2</i>{{ editingRows ? '修正问题' : '核对变化' }}</span><b></b><span :class="{ done: version.status === 'published' }"><i>3</i>发布给财务</span></div>
          <button @click="version = null">返回批次审核</button>
        </header>
        <p v-if="version.status === 'published' && version.quoteReady === false" class="notice">该版本已发布，但计费条件尚未完全适配，暂不开放自动报价。</p>
        <p v-else-if="version.status === 'draft' && version.pricingReady === false" class="notice error">发现阻断问题或待适配计费条件。可直接在线修改，不必返回 Excel 重新导入。</p>
        <nav class="review-focus-tabs version-tabs"><button :class="{ active: detailTab === 'diff' }" @click="detailTab = 'diff'; detailPage = 0">只看变化 <b>{{ filteredDiffs.length }}</b></button><button :class="{ active: detailTab === 'rows' }" @click="detailTab = 'rows'; detailPage = 0">完整价格 <b>{{ version.rows?.length || 0 }}</b></button><button class="issue-tab" :class="{ active: detailTab === 'issues' }" @click="detailTab = 'issues'; detailPage = 0">解析 / 区间问题 <b>{{ version.issues?.length || 0 }}</b></button></nav>
        <div class="version-review-layout">
          <section class="review-main-panel">
            <div class="version-toolbar"><div><button :disabled="busy" @click="run(() => service.exportPrices(datasetId, versionFilters(version!.id)))">导出价格</button><button :disabled="busy" @click="run(() => service.exportDiff(version!))">导出差异</button><button v-if="version.batchId" :disabled="busy" @click="run(() => service.original(version!.batchId!, version!.sourceFileIndex || 0))">查看原表</button></div><div><select v-if="detailTab === 'diff'" v-model="diffType" aria-label="差异类型" @change="detailPage = 0"><option value="all">全部差异类型</option><option v-for="(label, key) in diffLabel" :key="key" :value="key">{{ label }}</option></select><button v-if="version.status === 'draft' && !editingRows" class="primary" @click="startEditing">批量修正价格</button><template v-if="editingRows"><button @click="cancelEditing">取消修正</button><button class="primary" :disabled="busy" @click="saveCorrections">保存并重新校验</button></template></div></div>
            <p class="muted">对比基线：{{ version.basePublishedVersionId || '初始价格版本（不与旧库比较）' }}</p>
            <div v-if="detailTab === 'diff'" class="scroll"><table class="diff-table"><thead><tr><th>国家 / 档位</th><th>变化类型</th><th>旧值 → 新值</th><th>涨跌 / 影响</th></tr></thead><tbody><tr v-for="d in visibleDiffs" :key="d.key" class="diff-row" :class="diffClass(d)"><td><b>{{ d.row.areaName }} · {{ d.row.zoneName || '无分区' }}</b><small>{{ weightLabel(d.row) }}</small></td><td><div class="diff-kind-list"><span v-for="kind in diffKinds(d)" :key="kind" class="change-chip" :class="`change-${kind}`">{{ diffLabel[kind] }}</span></div></td><td><div v-if="diffKinds(d).includes('range') && d.previous" class="range-compare"><div><span>旧</span><i class="old" :style="rangeBarStyle(d.previous, d.row)" /><small>{{ weightLabel(d.previous) }}</small></div><div><span>新</span><i class="next" :style="rangeBarStyle(d.row, d.previous)" /><small>{{ weightLabel(d.row) }}</small></div></div><div v-for="(c, i) in d.changes.filter(change => change.kind !== 'range')" :key="i" class="diff-change-line"><b>{{ c.field }}</b>：<del>{{ changeValue(c, c.before) }}</del> → <strong>{{ changeValue(c, c.after) }}</strong></div><span v-if="!d.changes.length && d.type === 'added'">— → <strong>{{ compactPrice(d) }}</strong></span><span v-else-if="!d.changes.length && d.type === 'removed'"><del>{{ compactPrice(d, d.previous) }}</del> → 已移除</span><span v-else-if="!d.changes.length">无字段变化</span></td><td><b>{{ diffImpact(d) }}</b><small v-for="(c, i) in d.changes.filter(change => change.kind === 'price' || change.price)" :key="i" :class="{ 'impact-up': Number(c.delta) > 0, 'impact-down': Number(c.delta) < 0 }">{{ c.field }}：{{ changeImpact(c, d.row.currency || 'CNY') }}</small></td></tr><tr v-if="!visibleDiffs.length"><td colspan="4" class="empty">当前筛选条件没有变化项。</td></tr></tbody></table></div>
            <div v-if="detailTab === 'rows'" class="scroll"><table v-if="!editingRows"><thead><tr><th>国家 / 档位</th><th>公斤价 / 每票费</th><th>首续重 / 区间价</th><th>来源</th><th>校验结果</th></tr></thead><tbody><tr v-for="(r, i) in visibleRows" :key="i"><td><b>{{ r.areaName }} · {{ r.zoneName || '无分区' }}</b><small>{{ weightLabel(r) }}</small></td><td>{{ r.currency || 'CNY' }} {{ money(r.pricePerKg) }} / {{ r.currency || 'CNY' }} {{ money(r.registrationFee) }}</td><td>首 {{ r.currency || 'CNY' }} {{ money(r.firstWeightPrice) }} / 续 {{ r.currency || 'CNY' }} {{ money(r.nextWeightPrice) }}<small>区间 {{ r.currency || 'CNY' }} {{ money(r.intervalPrice) }}</small></td><td>{{ r.sourceSheet }} · 第{{ r.sourceRow }}行</td><td :class="{ warning: r.pendingReason }">{{ r.pendingReason || '✓ 通过' }}</td></tr></tbody></table><table v-else class="price-editor"><thead><tr><th>国家 / 分区</th><th>起重kg</th><th>含起点</th><th>止重kg</th><th>含终点</th><th>公斤价</th><th>每票费</th><th>首重kg / 价</th><th>续重kg / 价</th><th>区间价</th><th>原表位置</th></tr></thead><tbody><tr v-for="r in visibleRows" :key="r.rowKey" :class="{ 'row-has-issue': r.pendingReason }"><td><b>{{ r.areaName }}</b><small>{{ r.zoneName || '无分区' }}</small></td><td><input v-model.number="r.weightFromKg" type="number" min="0" step="0.001"></td><td><input v-model="r.weightFromInclusive" type="checkbox"></td><td><input v-model.number="r.weightToKg" type="number" min="0" step="0.001"></td><td><input v-model="r.weightToInclusive" type="checkbox"></td><td><input v-model.number="r.pricePerKg" type="number" min="0" step="0.01"></td><td><input v-model.number="r.registrationFee" type="number" min="0" step="0.01"></td><td><input v-model.number="r.firstWeightKg" type="number" min="0" step="0.001"><input v-model.number="r.firstWeightPrice" type="number" min="0" step="0.01"></td><td><input v-model.number="r.nextWeightKg" type="number" min="0" step="0.001"><input v-model.number="r.nextWeightPrice" type="number" min="0" step="0.01"></td><td><input v-model.number="r.intervalPrice" type="number" min="0" step="0.01"></td><td>{{ r.sourceSheet }} · 第{{ r.sourceRow }}行</td></tr></tbody></table></div>
            <ul v-if="detailTab === 'issues'" class="issue-list"><li v-for="(i, n) in version.issues || []" :key="n"><b>{{ i.level === 'error' ? '阻断' : '提醒' }}</b> · {{ i.sourceSheet || version.fileName }} · 第 {{ i.row || '—' }} 行 · {{ i.field }}：{{ i.message }}<button v-if="i.suggestedFields && version.status === 'draft'" @click="applySuggestion(i)">采用边界建议</button></li><li v-if="!version.issues?.length" class="issue-empty">✓ 没有解析或区间问题</li></ul>
            <footer v-if="detailTab !== 'issues'" class="pager"><span>共 {{ detailTotal }} 条</span><button :disabled="detailPage === 0" @click="detailPage--">上一页</button><span>{{ detailPage + 1 }} / {{ Math.max(1, Math.ceil(detailTotal / 50)) }}</span><button :disabled="(detailPage + 1) * 50 >= detailTotal" @click="detailPage++">下一页</button></footer>
          </section>
          <aside class="release-check-panel version-check-panel"><h3>发布前检查</h3><button @click="detailTab = 'diff'; diffType = 'price'"><span class="check-dot price">¥</span><span>价格变化<small>已清楚展示旧价、新价和涨跌</small></span><b>{{ versionChangeCounts.price }}</b></button><button @click="detailTab = 'diff'; diffType = 'range'"><span class="check-dot range">↔</span><span>重量区间<small>扩大、缩小、重叠和断档</small></span><b>{{ versionChangeCounts.range }}</b></button><button @click="detailTab = 'issues'"><span class="check-dot issue">!</span><span>解析 / 区间问题<small>{{ version.errors ? '修正后重新校验' : '当前没有阻断问题' }}</small></span><b>{{ version.errors || 0 }}</b></button><button><span class="check-dot ready">✓</span><span>发布状态<small>{{ version.pricingReady === false ? '计费模型待适配' : '计费结构已校验' }}</small></span><b>{{ version.status === 'published' ? '已发布' : version.errors || version.pricingReady === false ? '待处理' : '可发布' }}</b></button><LogisticsBillingReview :key="`${version.id}-${version.status}`" :version-id="version.id" :readonly="archived" @updated="acceptanceUpdated" /><template v-if="version.status === 'draft'"><label v-if="hasCoverageRemoval(version.summary)" class="check"><input v-model="removal" type="checkbox">确认移除 / 覆盖缩小</label><label v-if="(version.summary.highRisk || 0) > 0" class="check"><input v-model="risk" type="checkbox">确认大幅涨跌风险</label></template></aside>
        </div>
        <footer v-if="!archived" class="review-publish-bar version-publish-bar"><button @click="version = null">返回批次</button><button :disabled="busy" @click="recompare">重新对比最新价格</button><label>审核备注<input v-model="note" maxlength="500" placeholder="填写价格来源、调整原因和审核结论"></label><template v-if="version.status === 'draft'"><button v-if="editingRows" class="primary" :disabled="busy" @click="saveCorrections">保存修正并重新校验</button><button class="primary publish-all" :disabled="busy || editingRows || version.errors > 0 || version.pricingReady === false || !note.trim() || (hasCoverageRemoval(version.summary) && !removal) || ((version.summary.highRisk || 0) > 0 && !risk)" @click="publish">一键审核并发布价格</button></template><button v-else-if="version.status === 'superseded'" :disabled="busy || !note.trim()" @click="rollback">以此版本创建回滚</button></footer>
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
.batch-review-workbench,.version-review-workbench{overflow:hidden;border:1px solid #dbe3e9;border-radius:12px;background:#fff;box-shadow:0 8px 28px rgba(31,54,70,.06)}.review-workbench-head{display:grid;grid-template-columns:minmax(320px,1fr) auto minmax(250px,1fr);align-items:center;gap:28px;padding:20px 24px;border-bottom:1px solid #e4e9ed;background:#fff}.review-title{display:flex;align-items:center;gap:13px;min-width:0}.review-title h2{margin:1px 0 3px;font-size:21px}.review-title p{margin:0;color:#778791;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.review-icon{display:grid;place-items:center;width:38px;height:38px;border-radius:9px;background:#edf4fa;color:#356b94;font-size:22px}.review-steps{display:flex;align-items:center;justify-content:center;gap:10px;white-space:nowrap;color:#87949d;font-size:13px}.review-steps span{display:flex;align-items:center;gap:7px;font-weight:650}.review-steps i{display:grid;place-items:center;width:27px;height:27px;border-radius:50%;background:#edf0f3;color:#6e7c86;font-style:normal}.review-steps b{width:60px;height:2px;background:#dce3e8}.review-steps .done{color:#26784d}.review-steps .done i{background:#26935d;color:#fff}.review-steps .current{color:#1e62b4}.review-steps .current i{background:#1e62b4;color:#fff}.review-head-actions{display:flex;justify-content:flex-end;gap:8px}.review-progress{padding:16px 24px;background:#f7fafc}.review-progress>div{display:flex;justify-content:space-between}.review-focus-tabs{display:flex;gap:10px;padding:18px 24px;border-bottom:1px solid #e5eaee;background:#fbfcfd}.review-focus-tabs button{padding:10px 15px;background:#fff}.review-focus-tabs button b{margin-left:7px}.review-focus-tabs button.active{border-color:#2b73d2;box-shadow:0 0 0 1px #2b73d2;color:#1e62b4;background:#f5f9ff}.review-focus-tabs .issue-tab.active,.review-focus-tabs .issue-tab b{color:#c23d35}.batch-review-layout,.version-review-layout{display:grid;grid-template-columns:minmax(0,1fr) 286px;gap:18px;padding:18px 20px 20px;background:#f7f9fb}.review-main-panel{min-width:0;padding:16px;border:1px solid #e0e6eb;border-radius:9px;background:#fff}.review-main-panel .batch-result-toolbar{margin-bottom:14px}.batch-results-table th:nth-child(2){width:47%}.batch-results-table td{vertical-align:middle}.batch-results-table .inline-change-summary{margin-top:7px}.row-has-issue{background:#fff8f7!important}.review-status{display:inline-flex;padding:4px 8px;border-radius:5px;background:#edf3f7;color:#4e6574;font-size:12px}.review-status.published{background:#eaf7ef;color:#24724a}.review-status.blocked{background:#fff0ed;color:#b63e35}.review-status.draft{background:#fff7df;color:#966814}.row-action{border-color:#b9cee2;color:#245f92;font-weight:650}.batch-source-files{margin-top:14px;padding-top:12px;border-top:1px solid #e6ebee}.release-check-panel{position:sticky;top:16px;align-self:start;padding:17px;border:1px solid #dfe5ea;border-radius:9px;background:#fff}.release-check-panel h3{margin:0 0 14px;font-size:17px}.release-check-panel>button:not(.retry-button){display:grid;grid-template-columns:36px 1fr auto;align-items:center;gap:10px;width:100%;margin:9px 0;padding:13px;text-align:left}.release-check-panel>button>span:nth-child(2){font-weight:650;color:#314958}.release-check-panel>button small{display:block;margin-top:2px;color:#86949d;font-weight:400;white-space:normal}.release-check-panel>button>b{font-size:19px}.check-dot{display:grid;place-items:center;width:30px;height:30px;border-radius:8px;font-weight:800}.check-dot.price{background:#eaf3ff;color:#2f74c8}.check-dot.range{background:#fff0ef;color:#cc4540}.check-dot.issue{background:#fff5df;color:#c57916}.check-dot.ready{background:#eaf7ef;color:#258653}.release-note{padding:12px;border:1px solid #c9dced;border-radius:7px;background:#f2f8fd;color:#4d6a7e;font-size:12px;line-height:1.65}.retry-button{width:100%;color:#a64b3d;border-color:#e6b9b2}.review-publish-bar{position:sticky;bottom:0;z-index:5;display:grid;grid-template-columns:minmax(220px,1fr) minmax(260px,1.2fr) auto auto auto;align-items:end;gap:12px;padding:16px 22px;border-top:1px solid #dfe6eb;background:rgba(255,255,255,.97);box-shadow:0 -7px 20px rgba(30,51,67,.07);backdrop-filter:blur(8px)}.review-publish-bar>div small{display:block;margin-top:3px;color:#7d8c96}.review-publish-bar .check{margin:0;max-width:170px}.publish-all{min-height:42px;font-weight:700}.version-review-workbench{margin-top:0}.version-tabs{padding-top:14px;padding-bottom:14px}.version-review-layout{grid-template-columns:minmax(0,1fr) 310px}.version-toolbar{display:flex;justify-content:space-between;align-items:center;gap:12px;margin-bottom:12px}.version-toolbar>div{display:flex;gap:8px;align-items:center}.version-check-panel{position:static}.version-check-panel :deep(section),.version-check-panel :deep(.card){box-shadow:none!important;border-radius:7px!important;margin-top:14px!important}.version-publish-bar{grid-template-columns:auto auto minmax(280px,1fr) auto}.diff-change-line del{color:#8a969e}.diff-change-line strong{color:#173f5a}.issue-empty{border-left-color:#2d9561!important;background:#eff9f3!important;color:#26784d}.price-editor .row-has-issue input{border-color:#df655c;background:#fff8f7}
@media(max-width:1150px){.review-workbench-head{grid-template-columns:1fr}.review-steps{justify-content:flex-start}.review-head-actions{justify-content:flex-start}.batch-review-layout,.version-review-layout{grid-template-columns:1fr}.release-check-panel{position:static}.review-publish-bar,.version-publish-bar{grid-template-columns:1fr 1fr}.review-focus-tabs{overflow:auto}.version-toolbar{align-items:flex-start;flex-direction:column}}
@media(max-width:700px){.review-publish-bar,.version-publish-bar{grid-template-columns:1fr}.review-steps b{width:24px}.review-workbench-head{padding:16px}.batch-review-layout,.version-review-layout{padding:10px}.review-main-panel{padding:10px}}
</style>
