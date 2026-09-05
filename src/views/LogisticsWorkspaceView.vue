<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import LogisticsPager from '@/components/logistics/LogisticsPager.vue'
import { clampLogisticsPage, logisticsPageFromQuery, logisticsPageQuery, logisticsPageSize } from '@/components/logistics/logisticsPagination'
import LogisticsBillingReview from '@/components/quotation/LogisticsBillingReview.vue'
import { idempotencyKey, type PreparedDownload, type UploadProgress } from '@/services/http'
import { invalidatePublishedLogisticsCache } from '@/data/publishedLogisticsRepository'
import { LOGISTICS_PROVIDER_CHANNEL_PAGE_SIZE, logisticsWorkspaceLoadPlan, matchesLogisticsProviderScope, paginateLogisticsProviderChannels } from '@/data/logisticsWorkspaceView'
import { initializeLogisticsWorkspace } from '@/data/logisticsWorkspaceInitialization'
import { logisticsRebuild as service, money, shown, weightLabel, completedBatchStage, diffKinds, aggregateChangeSummary, batchComparisonSummary, rangeImpact, changeImpact, logisticsAdjustmentStatus, logisticsUploadError, formatTransferBytes, buildEtaCorrections, type Dataset, type Workspace, type Batch, type Version, type PricePage, type Diff, type DiffChange, type DiffKind, type Price, type RowCorrection, type ReadyPublishResult, type Channel, type Provider } from '@/data/logisticsRebuild'

const route = useRoute(), router = useRouter()
const requestedPageSize = Number(Array.isArray(route.query.size) ? route.query.size[0] : route.query.size)
const initialPageSize = logisticsPageSize(requestedPageSize)
const requestedPage = Number(Array.isArray(route.query.page) ? route.query.page[0] : route.query.page)
const tab = ref<'prices' | 'imports' | 'history'>(route.query.logisticsTab === 'prices' ? 'prices' : route.query.logisticsTab === 'history' ? 'history' : 'imports')
const datasets = ref<Dataset[]>([]), datasetId = ref(''), workspace = ref<Workspace | null>(null)
const batch = ref<Batch | null>(null), version = ref<Version | null>(null)
const batchResultQuery = ref(''), batchResultProvider = ref('all'), batchResultStatus = ref('all'), batchResultFocus = ref<'all' | 'added' | 'price' | 'range' | 'issues'>('all'), batchResultPage = ref(0)
const batchResultPageSize = ref(10)
const batchResultPageSizes = [10, 30, 50]
const prices = ref<PricePage>({ items: [], total: 0, page: 0, size: initialPageSize, totalPages: 0 })
const query = ref(''), country = ref(''), page = ref(logisticsPageFromQuery(requestedPage)), pageSize = ref(initialPageSize)
const workspaceLoading = ref(false), pricesLoading = ref(false), pricesLoaded = ref(false)
const files = ref<File[]>([]), replaceDrafts = ref(true)
const busy = ref(false), error = ref(''), message = ref(''), note = ref(''), removal = ref(false), risk = ref(false)
const editingRows = ref(false), editSnapshot = ref<Price[]>([])
const missingEtaMin = ref<number | null>(null), missingEtaMax = ref<number | null>(null)
const readyPublishNote = ref(''), readyPublishRemoval = ref(false), readyPublishRisk = ref(false), readyPublishResult = ref<ReadyPublishResult | null>(null)
const logisticsFileInput = ref<HTMLInputElement | null>(null)
const versionDetail = ref<HTMLElement | null>(null)
const uploadProgress = ref<UploadProgress>({ loaded: 0, total: 0, percent: 0, bytesPerSecond: 0 })
const uploadInFlight = ref(false), activeUploadBatchId = ref('')
const uploadFileSnapshot = ref<Array<{ name: string; size: number }>>([])
let cancelActiveUpload: (() => void) | null = null
const preparedDownload = ref<PreparedDownload | null>(null)
const providerSearch = ref(''), selectedProviderId = ref(''), providerChannelPage = ref(0), historyChannelId = ref('')
const uploadScope = ref<'provider' | 'multi'>('multi'), uploadProviderName = ref('')
function clearDownload() { preparedDownload.value = null }
watch([query, country, tab], clearDownload)
onUnmounted(clearDownload)
async function acceptanceUpdated() { await invalidatePublishedLogisticsCache(); await refresh(); if (version.value) { const id = version.value.id; const latest = await service.version(id); if (version.value?.id === id) version.value = latest } }
const diffType = ref('all'), detailTab = ref<'diff' | 'rows' | 'issues'>('diff'), detailPage = ref(0), detailPageSize = ref(20)
const statusLabel: Record<string, string> = { active: '当前生效库', preparing: '新库准备区', archived: '归档旧库', queued: '等待处理', processing: '处理中', completed: '处理完成', failed: '处理失败', interrupted: '处理已中断', draft: '待审核', published: '已生效', superseded: '历史版本', rejected: '已终止', blocked: '存在阻断', unchanged: '价格未变', parsed: '已解析', empty: '空表', metadata: '说明页', review: '待审核', staging: '生成草稿', parsing: '解析表格' }
const selected = computed(() => datasets.value.find(d => d.id === datasetId.value))
const archived = computed(() => selected.value?.status === 'archived')
const workspaceMode = computed<'base' | 'rules'>(() => tab.value === 'prices' ? 'rules' : 'base')
const filteredProviders = computed(() => {
  const keyword = providerSearch.value.trim().toLocaleLowerCase()
  return (workspace.value?.providers || []).filter(provider => !keyword || `${provider.name} ${provider.code || ''}`.toLocaleLowerCase().includes(keyword))
})
const selectedProvider = computed(() => (workspace.value?.providers || []).find(provider => provider.id === selectedProviderId.value) || filteredProviders.value[0] || workspace.value?.providers[0])
const selectedProviderChannels = computed(() => (workspace.value?.channels || []).filter(channel => channel.providerId === selectedProvider.value?.id && !channel.archived))
const providerChannelPagination = computed(() => paginateLogisticsProviderChannels(selectedProviderChannels.value, providerChannelPage.value))
const visibleProviderChannels = computed(() => providerChannelPagination.value.items)
const historyVersions = computed(() => (workspace.value?.versions || []).filter(item => !historyChannelId.value || item.channelId === historyChannelId.value))
const importTarget = computed(() => {
  if (!selected.value) return '未选择物流库'
  const dataset = `${selected.value.name}（${selected.value.status === 'active' ? '当前生效库' : selected.value.status === 'preparing' ? '准备库' : '归档库'}）`
  return uploadScope.value === 'provider' && uploadProviderName.value ? `${uploadProviderName.value} · 单物流商更新 · ${dataset}` : `多物流商更新 · ${dataset}`
})
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
const hasCoverageRemoval = (summary: Record<string, number> | undefined) => (summary?.removed || 0) > 0 || (summary?.coverageReduced || 0) > 0
const filteredDiffs = computed(() => (version.value?.diffRows || []).filter(d => diffType.value === 'all' ? !diffKinds(d).includes('unchanged') : diffKinds(d).includes(diffType.value as DiffKind)))
const visibleDiffs = computed(() => filteredDiffs.value.slice(detailPage.value * detailPageSize.value, (detailPage.value + 1) * detailPageSize.value))
const visibleRows = computed(() => (version.value?.rows || []).slice(detailPage.value * detailPageSize.value, (detailPage.value + 1) * detailPageSize.value))
const detailTotal = computed(() => detailTab.value === 'diff' ? filteredDiffs.value.length : (version.value?.rows?.length || 0))
const detailTotalPages = computed(() => Math.max(1, Math.ceil(detailTotal.value / detailPageSize.value)))
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
const visibleBatchResults = computed(() => filteredBatchResults.value.slice(batchResultPage.value * batchResultPageSize.value, (batchResultPage.value + 1) * batchResultPageSize.value))
const batchResultTotalPages = computed(() => Math.max(1, Math.ceil(filteredBatchResults.value.length / batchResultPageSize.value)))
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
function matchesUploadScope(providerName: string) {
  return matchesLogisticsProviderScope(uploadScope.value, uploadProviderName.value, providerName)
}
const readyBatchResults = computed(() => (batch.value?.payload.results || []).filter(item => currentBatchResultStatus(item) === 'draft' && !(item.errors || 0) && item.pricingReady === true && item.versionId && matchesUploadScope(item.providerName)))
const outOfScopeBatchResults = computed(() => {
  if (uploadScope.value !== 'provider' || !uploadProviderName.value) return []
  return (batch.value?.payload.results || []).filter(item => !matchesUploadScope(item.providerName))
})
const readyBatchNeedsRemoval = computed(() => readyBatchResults.value.some(item => hasCoverageRemoval(item.summary)))
const readyBatchNeedsRisk = computed(() => readyBatchResults.value.some(item => (item.summary?.highRisk || 0) > 0))
const versionChangeCounts = computed(() => aggregateChangeSummary(version.value ? [{ summary: version.value.summary }] : []))
const diffLabel: Record<string, string> = { added: '新增', price: '调价', rule: '规则变化', range: '重量区间变化', removed: '移除', unchanged: '无变化' }
const changeKeys = ['added', 'price', 'rule', 'range', 'removed'] as const
function providerChannelCount(provider: Provider) { return (workspace.value?.channels || []).filter(channel => channel.providerId === provider.id && !channel.archived).length }
function currentVersionFor(channel: Channel) { return (workspace.value?.versions || []).find(item => item.id === channel.currentVersionId && item.status === 'published') }
function draftVersionFor(channel: Channel) { return (workspace.value?.versions || []).find(item => item.channelId === channel.id && item.status === 'draft') }
function visibleVersionFor(channel: Channel) { return draftVersionFor(channel) || currentVersionFor(channel) }
function channelCountryCount(channel: Channel) { const value = visibleVersionFor(channel); return value?.countryCount == null ? '—' : String(value.countryCount) }
function channelHasPendingImport(channel: Channel) {
  return Boolean(batch.value?.payload.results.some(result => (result.channelId === channel.id || (result.channelName === channel.name && result.providerName === channel.providerName)) && !['published', 'unchanged'].includes(currentBatchResultStatus(result))))
}
function channelAdjustmentStatus(channel: Channel) { return logisticsAdjustmentStatus(channel, workspace.value?.versions || [], channelHasPendingImport(channel)) }
function channelAdjustmentLabel(channel: Channel) { return channelAdjustmentStatus(channel) === 'published' ? '已发布' : '待处理' }
function formatPublishedAt(value?: string) { return value ? value.replace('T', ' ').replace(/Z$/, '').slice(0, 19) : '—' }
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
let requestKey = idempotencyKey('logistics-import'), reviewKey = idempotencyKey('logistics-review')
let disposed = false, selectionEpoch = 0, priceRequestEpoch = 0

async function run(action: () => Promise<void | PreparedDownload>) {
  if (busy.value) return
  busy.value = true; error.value = ''; message.value = ''
  try { const result = await action(); if (result && !disposed) preparedDownload.value = result } catch (e) { error.value = e instanceof Error ? e.message : '操作失败，请重试' } finally { busy.value = false }
}
function filters() { return new URLSearchParams({ query: query.value.trim(), country: country.value.trim(), page: String(page.value), size: String(pageSize.value) }) }
function versionFilters(id: string) { return new URLSearchParams({ versionId: id }) }
function activeBatchStorageKey(id = datasetId.value) { return `milano.logistics.active-batch.${id}` }
function rememberActiveBatch(id: string) { sessionStorage.setItem(activeBatchStorageKey(), id) }
function forgetActiveBatch(id = datasetId.value) { if (id) sessionStorage.removeItem(activeBatchStorageKey(id)) }
async function restoreActiveBatch() {
  const id = sessionStorage.getItem(activeBatchStorageKey())
  if (!id || batch.value) return
  try {
    batch.value = await service.batch(id)
    resetBatchResultView()
    schedulePoll()
  } catch {
    forgetActiveBatch()
  }
}
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
  if (report?.status === 'filtered') return '已过滤'
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
  const filtered = report?.sheets?.filter(sheet => sheet.filteredFirstNextRows?.length) || []
  if (filtered.length) return `首重续重已过滤，不解析、不进入发布前检查：${filtered.map(sheet => sheet.name).join('、')}`
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
  const id = datasetId.value, selection = selectionEpoch, request = ++priceRequestEpoch
  pricesLoading.value = true
  try {
    const result = await requestPrices(id)
    if (!disposed && datasetId.value === id && selectionEpoch === selection && priceRequestEpoch === request) { prices.value = result; pricesLoaded.value = true }
  } finally {
    if (datasetId.value === id && selectionEpoch === selection && priceRequestEpoch === request) pricesLoading.value = false
  }
}
function invalidatePricePage() {
  priceRequestEpoch++
  pricesLoading.value = false
  pricesLoaded.value = false
  prices.value = { items: [], total: 0, page: 0, size: pageSize.value, totalPages: 0 }
}
async function refresh() {
  const id = datasetId.value, epoch = selectionEpoch
  workspaceLoading.value = true
  invalidatePricePage()
  try {
    const w = await service.workspace(id)
    if (disposed || id !== datasetId.value || epoch !== selectionEpoch) return
    workspace.value = w
    if (!w.providers.some(provider => provider.id === selectedProviderId.value)) selectedProviderId.value = w.providers[0]?.id || ''
    workspaceLoading.value = false
    if (tab.value === 'imports') await restoreActiveBatch()
    if (logisticsWorkspaceLoadPlan(tab.value).pricePage) await loadPrices()
  } finally {
    if (id === datasetId.value && epoch === selectionEpoch) workspaceLoading.value = false
  }
}
async function initialize() {
  const initialized = await initializeLogisticsWorkspace(route.query.dataset, service.datasets, async id => {
    datasetId.value = id
    await refresh()
  })
  datasets.value = initialized.datasets
  datasetId.value = initialized.datasetId
}
watch([datasetId, tab, page, pageSize], () => { if (datasetId.value) void router.replace({ query: { ...route.query, dataset: datasetId.value, logisticsTab: tab.value, ...logisticsPageQuery(page.value, pageSize.value) } }) })
watch([batchResultQuery, batchResultProvider, batchResultStatus, batchResultFocus], () => { batchResultPage.value = 0 })
watch(() => selectedProvider.value?.id, () => { providerChannelPage.value = 0 })
watch(() => selectedProviderChannels.value.length, () => { providerChannelPage.value = providerChannelPagination.value.page })
watch(batchResultTotalPages, totalPages => { batchResultPage.value = clampLogisticsPage(batchResultPage.value, totalPages) })
watch(detailTotalPages, totalPages => { detailPage.value = clampLogisticsPage(detailPage.value, totalPages) })
watch(tab, value => {
  if (value === 'prices' && datasetId.value && !pricesLoaded.value && !pricesLoading.value) void run(loadPrices)
  else if (value !== 'prices' && pricesLoading.value) invalidatePricePage()
})
async function submitPriceFilters() { page.value = 0; await loadPrices() }
function selectWorkspaceMode(mode: 'base' | 'rules') {
  version.value = null
  if (mode === 'rules') { tab.value = 'prices'; return }
  historyChannelId.value = ''; tab.value = 'imports'
}
function beginUpload(scope: 'provider' | 'multi' = 'provider') {
  uploadScope.value = scope
  uploadProviderName.value = scope === 'provider' ? selectedProvider.value?.name || '' : ''
  version.value = null; clearTimeout(pollTimer); forgetActiveBatch(); batch.value = null; tab.value = 'imports'
  void nextTick(() => logisticsFileInput.value?.click())
}
function selectUploadFiles(nextFiles: File[]) {
  files.value = nextFiles
  uploadFileSnapshot.value = nextFiles.map(file => ({ name: file.name, size: file.size }))
  activeUploadBatchId.value = ''
  requestKey = idempotencyKey('logistics-import')
  error.value = uploadValidation.value
}
function handleFileDrop(event: DragEvent) { uploadScope.value = 'provider'; uploadProviderName.value = selectedProvider.value?.name || ''; selectUploadFiles([...(event.dataTransfer?.files || [])]) }
function openChannelHistory(channelId: string) { historyChannelId.value = channelId; version.value = null; batch.value = null; tab.value = 'history' }
function showAllHistory() { historyChannelId.value = ''; version.value = null; batch.value = null; tab.value = 'history' }
async function changePricePage(nextPage: number) { if (nextPage === page.value) return; page.value = nextPage; await run(loadPrices) }
async function changePriceSize(size: number) { const nextSize = logisticsPageSize(size); if (nextSize === pageSize.value) return; pageSize.value = nextSize; page.value = 0; await run(loadPrices) }
function changeBatchResultPage(nextPage: number) { batchResultPage.value = clampLogisticsPage(nextPage, batchResultTotalPages.value) }
function changeProviderChannelPage(nextPage: number) { providerChannelPage.value = clampLogisticsPage(nextPage, providerChannelPagination.value.totalPages) }
function changeBatchResultSize(size: number) { batchResultPageSize.value = batchResultPageSizes.includes(size) ? size : 10; batchResultPage.value = 0 }
function resetDetailTableScroll(behavior: ScrollBehavior = 'smooth') { versionDetail.value?.querySelector<HTMLElement>('.review-main-panel > .scroll')?.scrollTo({ top: 0, behavior }) }
async function changeDetailPage(nextPage: number) { detailPage.value = clampLogisticsPage(nextPage, detailTotalPages.value); await nextTick(); resetDetailTableScroll() }
async function changeDetailSize(size: number) { detailPageSize.value = [10, 20, 50].includes(size) ? size : 20; detailPage.value = 0; await nextTick(); resetDetailTableScroll('auto') }
function chooseFiles(event: Event) {
  selectUploadFiles([...((event.target as HTMLInputElement).files || [])])
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
      batch.value = result; activeUploadBatchId.value = result.id; rememberActiveBatch(result.id); requestKey = idempotencyKey('logistics-import'); files.value = []
      if (logisticsFileInput.value) logisticsFileInput.value.value = ''
      await refresh(); schedulePoll()
    } catch (e) {
      if (e instanceof DOMException && e.name === 'AbortError') {
        files.value = []; activeUploadBatchId.value = ''; if (logisticsFileInput.value) logisticsFileInput.value.value = ''
        message.value = '已取消上传；如果服务器已接收完整文件，稍后可从对应渠道的“待处理”状态继续审核。'
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
function closeBatch() { clearTimeout(pollTimer); forgetActiveBatch(); batch.value = null; version.value = null; resetBatchResultView() }
async function openVersion(id: string) { await run(async () => { version.value = await service.version(id); missingEtaMin.value = null; missingEtaMax.value = null; note.value = ''; removal.value = false; risk.value = false; editingRows.value = false; editSnapshot.value = []; detailPage.value = 0; detailTab.value = version.value.basePublishedVersionId ? 'diff' : 'rows'; diffType.value = 'all'; reviewKey = idempotencyKey('logistics-review'); await nextTick(); versionDetail.value?.scrollIntoView({ behavior: 'smooth', block: 'start' }) }) }
function startEditing() { if (!version.value?.rows) return; editSnapshot.value = structuredClone(version.value.rows); editingRows.value = true; detailTab.value = 'rows'; detailPage.value = 0 }
function cancelEditing() { if (version.value) version.value.rows = structuredClone(editSnapshot.value); editingRows.value = false; editSnapshot.value = [] }
function syncEtaRoute(row: Price) { if (!version.value?.rows || !row.routeKey) return; for (const item of version.value.rows) if (item.routeKey === row.routeKey) { item.etaMinDays = row.etaMinDays; item.etaMaxDays = row.etaMaxDays } }
async function saveMissingEta() {
  if (!version.value) return
  const min = Number(missingEtaMin.value), max = Number(missingEtaMax.value)
  if (!Number.isInteger(min) || !Number.isInteger(max) || min < 1 || max < min || max > 365) { error.value = '请填写 1–365 天内的起止时效'; return }
  const etaChanges = (version.value.missingEtaRoutes || []).map(route => ({ routeKey: route.routeKey, etaMinDays: min, etaMaxDays: max }))
  if (!etaChanges.length) return
  await run(async () => {
    version.value = await service.patchRows(version.value!, [], etaChanges)
    await refresh()
    if (batch.value && version.value.batchId === batch.value.id) batch.value = await service.batch(batch.value.id)
    missingEtaMin.value = null; missingEtaMax.value = null
    message.value = `已保存 ${etaChanges.length} 条路线的时效，下次更新缺少时效时会沿用本次填写。`
  })
}
function applySuggestion(issue: { rowKey?: string; suggestedFields?: Partial<Price> }) { if (!version.value?.rows || !issue.rowKey || !issue.suggestedFields) return; if (!editingRows.value) startEditing(); const row = version.value.rows.find(item => item.rowKey === issue.rowKey); if (row) Object.assign(row, issue.suggestedFields); detailTab.value = 'rows' }
async function saveCorrections() {
  if (!version.value?.rows) return
  const keys: Array<keyof Price> = ['weightFromKg', 'weightToKg', 'weightFromInclusive', 'weightToInclusive', 'pricePerKg', 'registrationFee']
  const original = new Map(editSnapshot.value.map(row => [row.rowKey, row]))
  const changes: RowCorrection[] = version.value.rows.flatMap(row => { const before = original.get(row.rowKey); if (!before || !row.rowKey) return []; const fields: Record<string, number | boolean> = {}; for (const key of keys) if (row[key] !== before[key] && (typeof row[key] === 'number' || typeof row[key] === 'boolean')) fields[key] = row[key] as number | boolean; return Object.keys(fields).length ? [{ rowKey: row.rowKey, fields }] : [] })
  let etaChanges
  try { etaChanges = buildEtaCorrections(version.value.rows, editSnapshot.value) } catch (e) { error.value = e instanceof Error ? e.message : '时效格式不正确'; return }
  if (!changes.length && !etaChanges.length) { editingRows.value = false; return }
  await run(async () => { version.value = await service.patchRows(version.value!, changes, etaChanges); editingRows.value = false; editSnapshot.value = []; removal.value = false; risk.value = false; await refresh(); if (batch.value && version.value?.batchId === batch.value.id) batch.value = await service.batch(batch.value.id); message.value = version.value!.etaReady === false ? `修正已保存，仍有 ${version.value!.etaMissingCount || 0} 条路线缺少或冲突时效。` : version.value!.errors ? `已保存修正并重新校验，仍有 ${version.value!.errors} 个阻断问题。` : '修正已保存，完整渠道已重新校验并更新差异。' })
}
async function publish() {
  if (!version.value) return
  await run(async () => { version.value = await service.review(version.value!, note.value, removal.value, risk.value, reviewKey); reviewKey = idempotencyKey('logistics-review'); await invalidatePublishedLogisticsCache(); await refresh(); message.value = version.value.quoteReady === false ? '价格已保存为正式版本；渠道仍待适配，不开放自动报价。' : '新价格已生效。' })
}
async function recompare() { await run(async () => { version.value = await service.recompare(version.value!); risk.value = false; removal.value = false; reviewKey = idempotencyKey('logistics-review'); message.value = '已按最新正式价格重新对比，请重新审核。' }) }
async function rollback() { await run(async () => { version.value = await service.rollback(version.value!, note.value); await invalidatePublishedLogisticsCache(); await refresh(); message.value = '已创建新的回滚版本，历史报价没有改写。' }) }
async function publishReadyBatch() {
  if (!batch.value || !readyBatchResults.value.length || !readyPublishNote.value.trim()) return
  const selections = readyBatchResults.value.map(item => ({ channelId: item.channelId!, versionId: item.versionId!, removalConfirmed: readyPublishRemoval.value, reviewConfirmed: readyPublishRisk.value }))
  await run(async () => { readyPublishResult.value = await service.publishReady(batch.value!.id, selections, readyPublishNote.value.trim(), idempotencyKey('logistics-ready-publish')); await invalidatePublishedLogisticsCache(); await refresh(); batch.value = await service.batch(batch.value!.id); message.value = `成功发布 ${readyPublishResult.value!.publishedCount} 个渠道；跳过 ${readyPublishResult.value!.skippedCount} 个；失败 ${readyPublishResult.value!.failedCount} 个。` })
}
onMounted(() => { void run(initialize) })
onUnmounted(() => { disposed = true; clearTimeout(pollTimer); cancelActiveUpload?.() })
</script>

<template>
  <div class="logistics-page">
    <main>
      <header class="milano-heading"><div><p>LOGISTICS CONFIGURATION</p><h1>物流规则</h1><span>维护物流渠道、国家区域、重量限制与分段运费，供米莱诺报价计算直接调用。</span></div></header>
      <p v-if="error" role="alert" class="notice error">{{ error }}</p><p v-if="message" role="status" class="notice success">{{ message }}</p>
      <p v-if="preparedDownload" role="status" class="notice success">下载已就绪，请点击保存：<a :href="preparedDownload.url" :download="preparedDownload.filename">下载 {{ preparedDownload.filename }}</a>。下载仍需登录和物流权限；价格版本若发生变化，请重新生成链接。</p>
      <p v-if="archived" class="notice">这里是归档旧库，只能查阅和导出；不参与当前报价，不会被新导入自动恢复。</p>
      <p v-if="selected?.status === 'preparing'" class="notice">新库准备期间不影响当前报价。确认整体切换后，当前物流商和渠道列表才会全部换新。</p>
      <nav class="workspace-switch" aria-label="物流工作区"><button :class="{ active: workspaceMode === 'base' }" @click="selectWorkspaceMode('base')">基础资料设置</button><button :class="{ active: workspaceMode === 'rules' }" @click="selectWorkspaceMode('rules')">运费规则列表</button></nav>
      <input ref="logisticsFileInput" class="hidden-file" aria-label="物流报价文件" type="file" accept=".xls,.xlsx" multiple :disabled="busy || !selected || archived" @change="chooseFiles">

      <section v-if="tab === 'prices'" class="rule-workspace-card">
        <header class="rule-card-head"><div><h2>运费规则列表</h2><p>只读展示已审核发布的正式价格；更新价格请到“基础资料设置”中操作。</p></div></header>
        <form class="modern-filters" @submit.prevent="run(submitPriceFilters)"><label class="keyword-search"><span>⌕</span><input v-model="query" placeholder="搜索物流商或渠道"></label><label>国家<input v-model="country" placeholder="例如 美国 / US"></label><button class="outline-orange" :disabled="busy">查询</button><button type="button" @click="query='';country='';run(submitPriceFilters)">重置</button><span>共 {{ prices.total }} 条正式价格</span></form>
        <div class="scroll" :aria-busy="pricesLoading"><table><thead><tr><th>物流商 / 渠道</th><th>国家</th><th>重量段</th><th>计费价格</th><th>每票费用</th><th>版本 / 状态</th></tr></thead><tbody><template v-if="pricesLoading"><tr v-for="index in 6" :key="`skeleton-${index}`" class="price-skeleton" aria-hidden="true"><td v-for="column in 6" :key="column"><span /></td></tr></template><template v-else><tr v-for="(r, i) in prices.items" :key="`${r.versionId}-${page}-${i}`"><td><b>{{ r.providerName }}</b><small>{{ r.channelName }}</small></td><td>{{ r.areaName }}<small>{{ r.countryCode }} · {{ r.zoneName || '无分区' }}</small></td><td>{{ weightLabel(r) }}</td><td v-if="r.pricingModel === 'first-next'">首 {{ r.firstWeightKg }}kg / {{ r.currency || 'CNY' }} {{ money(r.firstWeightPrice) }}<small>续 {{ r.nextWeightKg }}kg / {{ r.currency || 'CNY' }} {{ money(r.nextWeightPrice) }}</small></td><td v-else-if="r.intervalPrice">{{ r.currency || 'CNY' }} {{ money(r.intervalPrice) }} / 档</td><td v-else>{{ r.currency || 'CNY' }} {{ money(r.pricePerKg) }} / kg</td><td>{{ r.currency || 'CNY' }} {{ money(r.registrationFee) }}</td><td>V{{ r.versionNumber }}<small :class="{ warning: r.quoteReady === false }">{{ r.quoteReady === false ? '价格已记录 · 计费待适配' : '可自动报价' }}</small></td></tr><tr v-if="!prices.items.length"><td colspan="6" class="empty">当前条件没有正式价格；新库请先导入并审核价格版本。</td></tr></template></tbody></table></div>
        <LogisticsPager v-if="pricesLoaded" :page="page" :size="pageSize" :total="prices.total" :total-pages="prices.totalPages" :loading="busy || pricesLoading" @page-change="changePricePage" @size-change="changePriceSize" />
      </section>

      <section v-if="tab === 'imports' && !version" class="stack">
        <template v-if="batch">
          <div class="batch-review-workbench">
            <header class="review-workbench-head">
              <div class="review-title"><span class="review-icon">▤</span><div><p class="eyebrow">IMPORT REVIEW</p><h2>物流价格批量审核</h2><p>{{ batch.payload.files.map(file => file.name).join('、') }}</p></div></div>
              <div class="review-steps" aria-label="审核步骤"><span class="done"><i>✓</i>已解析</span><b></b><span :class="{ current: batchResultCounts.draft || batchResultCounts.blocked }"><i>2</i>核对变化</span><b></b><span :class="{ done: !batchResultCounts.draft && !batchResultCounts.blocked }"><i>3</i>发布给财务</span></div>
              <div class="review-head-actions"><button :disabled="busy || batch.status !== 'completed'" @click="run(() => service.exportBatchStandardized(batch!.id))">导出关键字段</button><button :disabled="busy || batch.status !== 'completed'" @click="run(() => service.exportBatchDiff(batch!.id))">导出差异</button><button @click="closeBatch">返回渠道列表</button></div>
            </header>

            <div v-if="['queued', 'processing'].includes(batch.status)" class="review-progress"><div><b>{{ uploadStatusText || statusLabel[batch.phase] || batch.phase }}</b><strong>{{ batch.payload.progress || 0 }}%</strong></div><progress :value="batch.payload.progress || 0" max="100" /></div>
            <p v-if="batch.payload.error" class="notice error">批次失败原因：{{ batch.payload.error }}</p>
            <p v-if="outOfScopeBatchResults.length" class="notice error">本次按“{{ uploadProviderName }}”单物流商更新导入，但解析结果还包含 {{ [...new Set(outOfScopeBatchResults.map(item => item.providerName))].join('、') }}。这些渠道不会被一键发布，请核对文件后分别处理。</p>
            <nav class="review-focus-tabs" aria-label="变化类型筛选">
              <button :class="{ active: batchResultFocus === 'all' }" @click="batchResultFocus = 'all'">全部渠道 <b>{{ batchFocusCounts.all }}</b></button>
              <button :class="{ active: batchResultFocus === 'added' }" @click="batchResultFocus = 'added'">新增渠道 <b>{{ batchFocusCounts.added }}</b></button>
              <button :class="{ active: batchResultFocus === 'price' }" @click="batchResultFocus = 'price'">价格变化 <b>{{ batchFocusCounts.price }}</b></button>
              <button :class="{ active: batchResultFocus === 'range' }" @click="batchResultFocus = 'range'">重量区间 <b>{{ batchFocusCounts.range }}</b></button>
              <button class="issue-tab" :class="{ active: batchResultFocus === 'issues' }" @click="batchResultFocus = 'issues'">价格 / 重量问题 <b>{{ batchFocusCounts.issues + batchFailedFiles }}</b></button>
            </nav>

            <div class="batch-review-layout">
              <section class="review-main-panel">
                <div class="batch-result-toolbar"><label>搜索渠道<input v-model="batchResultQuery" placeholder="物流商或渠道名称"></label><label>物流商<select v-model="batchResultProvider"><option value="all">全部物流商</option><option v-for="provider in batchProviders" :key="provider" :value="provider">{{ provider }}</option></select></label><label>处理状态<select v-model="batchResultStatus"><option value="all">全部状态</option><option value="draft">待审核</option><option value="published">已发布</option><option value="blocked">存在阻断</option><option value="unchanged">价格未变</option></select></label></div>
                <div class="scroll"><table class="batch-results-table"><thead><tr><th>渠道</th><th>本次变化</th><th>审核结论</th><th>操作</th></tr></thead><tbody><tr v-for="(r, i) in visibleBatchResults" :key="`${r.versionId || r.channelId || r.channelName}-${i}`" :class="{ 'row-has-issue': batchResultHasIssues(r) }"><td><b>{{ r.channelName }}</b><small>{{ r.providerName }} · {{ r.priceRows ? `${r.priceRows} 条价格` : '已生成价格版本' }}</small></td><td><b class="comparison-explanation">{{ batchComparisonSummary(r) }}</b><div class="inline-change-summary"><span v-for="key in changeKeys" :key="key" :class="`change-${key}`">{{ diffLabel[key] }} {{ changeCount(r.summary, key) }}</span></div></td><td><strong class="review-status" :class="currentBatchResultStatus(r)">{{ statusLabel[currentBatchResultStatus(r)] || currentBatchResultStatus(r) }}</strong><small :class="{ warning: batchResultHasIssues(r) }">{{ r.message || batchResultReadiness(r) }}</small></td><td><button v-if="r.versionId" class="row-action" :disabled="busy" @click="openVersion(r.versionId)">{{ batchResultHasIssues(r) ? '修正问题' : '核对价格' }} →</button><details v-else open><summary>查看失败原因</summary><p>{{ r.message || '该渠道没有生成价格版本' }}</p><p v-for="(issue, j) in r.issues || []" :key="j">第 {{ issue.row || '—' }} 行 · {{ issue.field }}：{{ issue.message }}</p></details></td></tr><tr v-if="!visibleBatchResults.length"><td colspan="4" class="empty">当前筛选条件没有渠道结果。</td></tr></tbody></table></div>
                <LogisticsPager class="batch-result-pager" :page="batchResultPage" :size="batchResultPageSize" :total="filteredBatchResults.length" :total-pages="batchResultTotalPages" :loading="busy" :size-options="batchResultPageSizes" aria-label="导入批次渠道结果分页" @page-change="changeBatchResultPage" @size-change="changeBatchResultSize" />
                <details class="batch-source-files"><summary>原始文件与解析证据（{{ batch.payload.files.length }}）</summary><div class="batch-file-list"><details v-for="(file, i) in batch.payload.files" :key="i" :open="batchFileState(i) === '解析失败'"><summary><span>{{ file.name }}</span><b :class="{ warning: ['解析失败', '未完成'].includes(batchFileState(i)) }">{{ batchFileState(i) }}</b><button :disabled="busy || file.lifecycleStatus === 'deleted'" @click.stop="run(() => service.original(batch!.id, i))">{{ file.lifecycleStatus === 'deleted' ? '原文件已删除' : '下载原文件' }}</button><button v-if="batch.payload.fileReports?.[i]?.sourceEvidence" :disabled="busy" @click.stop="run(() => service.evidence(batch!.id, i))">解析证据</button></summary><p v-if="file.lifecycleStatus === 'deleted'" class="muted">原文件已按清理策略删除；SHA-256：{{ file.sha256 }}</p><p v-else-if="batch.payload.fileReports?.[i]?.retentionUntil" class="warning">解析失败，原文件保留至 {{ batch.payload.fileReports?.[i]?.retentionUntil }}。</p><p v-if="batchFileHint(i)" :class="{ warning: ['解析失败', '未完成'].includes(batchFileState(i)) }">{{ batchFileHint(i) }}</p></details></div></details>
              </section>

              <aside class="release-check-panel">
                <h3>发布前检查</h3>
                <button @click="batchResultFocus = 'price'"><span class="check-dot price">¥</span><span>价格变化<small>需核对旧价、新价和涨跌幅</small></span><b>{{ batchChangeCounts.price }}</b></button>
                <button @click="batchResultFocus = 'range'"><span class="check-dot range">↔</span><span>重量区间变化<small>包含扩大、缩小、重叠和断档</small></span><b>{{ batchChangeCounts.range }}</b></button>
                <button @click="batchResultFocus = 'issues'"><span class="check-dot issue">!</span><span>价格 / 重量问题<small>红色问题修正后才能发布</small></span><b>{{ batchFocusCounts.issues + batchFailedFiles }}</b></button>
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
          <section class="base-settings">
            <div class="base-toolbar"><div><h2>物流商与渠道版本</h2><p>按原表公斤价 × 计费重量＋每票费报价，不使用折扣或折后价。审核价格和重量区间；时效可后补，缺失不影响报价。</p></div><div class="provider-toolbar"><label class="provider-search-field">⌕<input v-model="providerSearch" placeholder="搜索物流商或编码"></label><a class="outline-orange" href="/templates/logistics-v2.xlsx" download="物流标准导入模板V2.xlsx">下载标准模板</a><button class="outline-orange" :disabled="busy || archived" @click="beginUpload('multi')">＋ 多物流商更新导入</button><button @click="showAllHistory">版本历史</button></div></div>
            <div class="provider-manager" :aria-busy="workspaceLoading">
              <aside class="provider-list"><div class="provider-sort-hint"><span>物流商列表</span><small>正式渠道数据仓库</small></div><div class="provider-list-scroll"><template v-if="workspaceLoading"><div v-for="index in 6" :key="`provider-skeleton-${index}`" class="provider-list-skeleton" aria-hidden="true"><i /><span><b /><small /></span><em /></div></template><template v-else><button v-for="provider in filteredProviders" :key="provider.id" :class="{ active: selectedProvider?.id === provider.id }" @click="selectedProviderId = provider.id"><u>⋮⋮</u><i>{{ provider.name.slice(0, 1) }}</i><span><b>{{ provider.name }}</b><small>{{ provider.code || provider.id.slice(0, 8) }}</small></span><em>{{ providerChannelCount(provider) }}个渠道</em><strong>›</strong></button><div v-if="!filteredProviders.length" class="provider-empty">没有匹配的物流商</div></template></div><footer>{{ workspaceLoading ? '正在加载物流商…' : `共 ${workspace?.providers.length || 0} 家物流商` }}</footer></aside>
              <section v-if="selectedProvider" class="provider-detail"><header><div class="provider-icon">{{ selectedProvider.name.slice(0, 1) }}</div><div><h3>{{ selectedProvider.name }} <span>{{ selectedProviderChannels.length }}个渠道</span></h3><small>物流商编码 {{ selectedProvider.code || '自动生成' }} · 渠道、价格和审核的统一入口</small></div><div class="provider-detail-actions"><button class="upload" :disabled="busy || archived" @click="beginUpload('provider')">单物流商更新导入</button></div></header>
                <div class="provider-detail-body"><h4>渠道价格与版本</h4><div class="provider-template-table version-table"><div class="template-table-head"><span>渠道名称</span><span>当前正式版本</span><span>数据规模</span><span>调价状态</span><span>最近发布</span><span>操作</span></div><div v-for="channel in visibleProviderChannels" :key="channel.id" class="template-table-row"><b>{{ channel.name }}<small>{{ channel.code }} · {{ channel.logisticsAttribute || channel.type || '物流属性待确认' }}</small></b><div><strong>{{ currentVersionFor(channel) ? `V${currentVersionFor(channel)!.versionNumber}` : '尚未发布' }}</strong><small>{{ visibleVersionFor(channel)?.fileName || '等待首个价格文件' }}</small></div><div>{{ channelCountryCount(channel) }}国 / {{ visibleVersionFor(channel)?.rowCount || 0 }}价格段</div><span class="adjustment-status" :class="channelAdjustmentStatus(channel)">{{ channelAdjustmentLabel(channel) }}</span><time>{{ formatPublishedAt(currentVersionFor(channel)?.publishedAt) }}</time><div class="template-actions"><button :disabled="busy || archived" @click="beginUpload('provider')">{{ currentVersionFor(channel) ? '更新价格' : '上传价格' }}</button><button v-if="draftVersionFor(channel)" class="move-template" :disabled="busy" @click="openVersion(draftVersionFor(channel)!.id)">审核发布</button><button @click="openChannelHistory(channel.id)">版本历史</button></div></div><div v-if="!selectedProviderChannels.length" class="template-table-empty">当前物流商尚未生成渠道</div></div><nav v-if="providerChannelPagination.total > LOGISTICS_PROVIDER_CHANNEL_PAGE_SIZE" class="provider-channel-pager" aria-label="物流商渠道分页"><span>第 {{ providerChannelPagination.from }}–{{ providerChannelPagination.to }} 条 / 共 {{ providerChannelPagination.total }} 条</span><div><button :disabled="providerChannelPagination.page === 0" @click="changeProviderChannelPage(providerChannelPagination.page - 1)">上一页</button><b>{{ providerChannelPagination.page + 1 }} / {{ providerChannelPagination.totalPages }} 页</b><button :disabled="providerChannelPagination.page + 1 >= providerChannelPagination.totalPages" @click="changeProviderChannelPage(providerChannelPagination.page + 1)">下一页</button></div></nav>
                  <div v-if="files.length || uploadStatusVisible" class="inline-upload-panel" @dragover.prevent @drop.prevent="handleFileDrop"><div class="inline-upload-head"><div><b>{{ importTarget }}</b><small>支持 .xls / .xlsx；单文件不超过100MB，批次不超过500MB</small></div><button :disabled="busy" @click="beginUpload(uploadScope)">重新选择</button><button class="primary" :disabled="busy || !files.length || Boolean(uploadValidation)" @click="upload">{{ uploadInFlight ? `${uploadProgress.percent}%` : `上传并解析 ${files.length} 份文件` }}</button></div><p v-if="uploadValidation" class="notice error">{{ uploadValidation }}</p><div v-if="displayedUploadFiles.length" class="upload-file-list"><span v-for="file in displayedUploadFiles" :key="`${file.name}-${file.size}`"><b>{{ file.name }}</b><small>{{ formatTransferBytes(file.size) }}</small></span></div><div v-if="uploadStatusVisible" class="logistics-upload-status" role="status"><div class="upload-status-heading"><b>{{ uploadStatusText }}</b><strong>{{ uploadStatusPercent }}%</strong></div><progress :value="uploadStatusPercent" max="100" /><p v-if="uploadInFlight" class="muted">已上传 {{ formatTransferBytes(uploadProgress.loaded) }} / {{ formatTransferBytes(uploadProgress.total) }}<template v-if="uploadProgress.bytesPerSecond"> · {{ formatTransferBytes(uploadProgress.bytesPerSecond) }}/s</template></p><p v-else-if="activeUploadBatch" class="muted">批次 {{ activeUploadBatch.id }} · {{ activeUploadBatch.payload.processedFiles || 0 }}/{{ activeUploadBatch.payload.totalFiles || activeUploadBatch.payload.files.length }} 个文件已解析</p><button v-if="uploadInFlight" type="button" @click="cancelUpload">取消上传</button></div><label class="check"><input v-model="replaceDrafts" type="checkbox">新价格替代同渠道未发布草稿，旧稿保留在历史中；正式价格审核后更新</label></div>
                </div>
              </section>
              <section v-else-if="workspaceLoading" class="provider-detail provider-detail-skeleton" aria-hidden="true"><header><span class="provider-icon" /><div><i /><i /></div></header><div class="provider-detail-body"><i v-for="index in 6" :key="index" /></div></section>
            </div>
          </section>
        </template>
      </section>

      <section v-if="tab === 'history'" class="stack">
        <div class="card history-card"><div class="section-head"><div><h2>{{ archived ? '旧库历史档案' : '渠道版本记录' }}</h2><p v-if="historyChannelId">正在查看 {{ workspace?.channels.find(c => c.id === historyChannelId)?.name }} 的全部历史版本</p><p v-else>保留每次导入、审核和发布后的差异记录。</p></div><button v-if="historyChannelId" @click="historyChannelId = ''">查看全部渠道</button><button @click="tab = 'imports'">返回基础资料</button></div><div class="scroll"><table><thead><tr><th>渠道</th><th>版本</th><th>来源</th><th>状态</th><th>价格行</th><th>操作</th></tr></thead><tbody><tr v-for="v in historyVersions" :key="v.id"><td>{{ workspace?.channels.find(c => c.id === v.channelId)?.name }}</td><td>V{{ v.versionNumber }}</td><td>{{ v.fileName }}<small>{{ v.importedAt }}</small></td><td>{{ statusLabel[v.status] }}</td><td>{{ v.rowCount }}</td><td><button :disabled="busy" @click="openVersion(v.id)">查看变动内容</button></td></tr><tr v-if="!historyVersions.length"><td colspan="6" class="empty">暂无版本记录。</td></tr></tbody></table></div></div>
      </section>

      <section v-if="version" ref="versionDetail" class="version-review-workbench">
        <header class="review-workbench-head">
          <div class="review-title"><span class="review-icon">▤</span><div><p class="eyebrow">PRICE REVIEW</p><h2>{{ workspace?.channels.find(c => c.id === version?.channelId)?.name }} · V{{ version.versionNumber }}</h2><p>{{ version.fileName }} · {{ statusLabel[version.status] }}</p></div></div>
          <div class="review-steps" aria-label="审核步骤"><span class="done"><i>✓</i>已解析</span><b></b><span class="current"><i>2</i>{{ editingRows ? '修正问题' : '核对变化' }}</span><b></b><span :class="{ done: version.status === 'published' }"><i>3</i>发布给财务</span></div>
          <button @click="version = null">{{ tab === 'history' ? '返回版本历史' : '返回批次审核' }}</button>
        </header>
        <p v-if="version.status === 'published' && version.quoteReady === false" class="notice">该版本已发布，但计费条件尚未完全适配，暂不开放自动报价。</p>
        <div v-if="version.status === 'draft' && version.etaReady === false" class="notice">
          <p>有 {{ version.etaMissingCount || version.missingEtaRoutes?.length || 0 }} 条路线暂无明确时效说明，不影响发布和报价。相同时效可在这里统一填写，不同国家或分区可在价格明细中分别编辑。</p>
          <label>最早 <input v-model.number="missingEtaMin" aria-label="缺失路线时效最早天数" type="number" min="1" max="365"> 天</label>
          <label>最晚 <input v-model.number="missingEtaMax" aria-label="缺失路线时效最晚天数" type="number" min="1" max="365"> 天</label>
          <button :disabled="busy || editingRows" @click="saveMissingEta">保存到本渠道待确认的路线</button>
        </div>
        <p v-else-if="version.status === 'draft' && version.pricingReady === false" class="notice error">发现阻断问题或待适配计费条件。可直接在线修改，不必返回 Excel 重新导入。</p>
        <nav class="review-focus-tabs version-tabs"><button :class="{ active: detailTab === 'diff' }" @click="detailTab = 'diff'; detailPage = 0">只看变化 <b>{{ filteredDiffs.length }}</b></button><button :class="{ active: detailTab === 'rows' }" @click="detailTab = 'rows'; detailPage = 0">完整价格 <b>{{ version.rows?.length || 0 }}</b></button><button class="issue-tab" :class="{ active: detailTab === 'issues' }" @click="detailTab = 'issues'; detailPage = 0">价格 / 重量问题 <b>{{ version.issues?.length || 0 }}</b></button></nav>
        <div class="version-review-layout">
          <section class="review-main-panel">
            <div class="version-toolbar"><div><button :disabled="busy" @click="run(() => service.exportPrices(datasetId, versionFilters(version!.id)))">导出价格</button><button :disabled="busy" @click="run(() => service.exportVersionStandardized(version!))">导出关键字段</button><button :disabled="busy" @click="run(() => service.exportDiff(version!))">导出差异</button><button v-if="version.batchId" :disabled="busy" @click="run(() => service.original(version!.batchId!, version!.sourceFileIndex || 0))">查看原表</button></div><div><select v-if="detailTab === 'diff'" v-model="diffType" aria-label="差异类型" @change="detailPage = 0"><option value="all">全部差异类型</option><option v-for="(label, key) in diffLabel" :key="key" :value="key">{{ label }}</option></select><button v-if="version.status === 'draft' && !editingRows" class="primary" @click="startEditing">批量修正价格</button><template v-if="editingRows"><button @click="cancelEditing">取消修正</button><button class="primary" :disabled="busy" @click="saveCorrections">保存并重新校验</button></template></div></div>
            <p class="muted">对比基线：{{ version.basePublishedVersionId || '初始价格版本（不与旧库比较）' }}</p>
            <LogisticsPager v-if="detailTab !== 'issues' && detailTotal" class="detail-pager detail-pager-top" :page="detailPage" :size="detailPageSize" :total="detailTotal" :total-pages="detailTotalPages" :loading="busy" :size-options="[10, 20, 50]" aria-label="价格明细顶部分页" @page-change="changeDetailPage" @size-change="changeDetailSize" />
            <p v-if="editingRows" class="pagination-edit-note">批量修正中：可以跨页修改，已改内容会保留；完成后统一点击“保存并重新校验”。</p>
            <div v-if="detailTab === 'diff'" class="scroll"><table class="diff-table"><thead><tr><th>国家 / 档位</th><th>变化类型</th><th>旧值 → 新值</th><th>涨跌 / 影响</th></tr></thead><tbody><tr v-for="d in visibleDiffs" :key="d.key" class="diff-row" :class="diffClass(d)"><td><b>{{ d.row.areaName }} · {{ d.row.zoneName || '无分区' }}</b><small>{{ weightLabel(d.row) }}</small></td><td><div class="diff-kind-list"><span v-for="kind in diffKinds(d)" :key="kind" class="change-chip" :class="`change-${kind}`">{{ diffLabel[kind] }}</span></div></td><td><div v-if="diffKinds(d).includes('range') && d.previous" class="range-compare"><div><span>旧</span><i class="old" :style="rangeBarStyle(d.previous, d.row)" /><small>{{ weightLabel(d.previous) }}</small></div><div><span>新</span><i class="next" :style="rangeBarStyle(d.row, d.previous)" /><small>{{ weightLabel(d.row) }}</small></div></div><div v-for="(c, i) in d.changes.filter(change => change.kind !== 'range')" :key="i" class="diff-change-line"><b>{{ c.field }}</b>：<del>{{ changeValue(c, c.before) }}</del> → <strong>{{ changeValue(c, c.after) }}</strong></div><span v-if="!d.changes.length && d.type === 'added'">— → <strong>{{ compactPrice(d) }}</strong></span><span v-else-if="!d.changes.length && d.type === 'removed'"><del>{{ compactPrice(d, d.previous) }}</del> → 已移除</span><span v-else-if="!d.changes.length">无字段变化</span></td><td><b>{{ diffImpact(d) }}</b><small v-for="(c, i) in d.changes.filter(change => change.kind === 'price' || change.price)" :key="i" :class="{ 'impact-up': Number(c.delta) > 0, 'impact-down': Number(c.delta) < 0 }">{{ c.field }}：{{ changeImpact(c, d.row.currency || 'CNY') }}</small></td></tr><tr v-if="!visibleDiffs.length"><td colspan="4" class="empty">当前筛选条件没有变化项。</td></tr></tbody></table></div>
            <div v-if="detailTab === 'rows'" class="scroll"><table v-if="!editingRows"><thead><tr><th>国家 / 档位</th><th>公斤价 / 每票费</th><th>来源</th><th>校验结果</th></tr></thead><tbody><tr v-for="(r, i) in visibleRows" :key="i"><td><b>{{ r.areaName }} · {{ r.zoneName || '无分区' }}</b><small>{{ weightLabel(r) }}</small></td><td>{{ r.currency || 'CNY' }} {{ money(r.pricePerKg) }} / {{ r.currency || 'CNY' }} {{ money(r.registrationFee) }}</td><td>{{ r.sourceSheet }} · 第{{ r.sourceRow }}行<small>{{ r.etaStatus && r.etaStatus !== 'ready' || !r.etaMinDays || !r.etaMaxDays ? '该物流暂无时效说明' : `时效 ${r.etaMinDays}–${r.etaMaxDays} 天` }}</small></td><td :class="{ warning: r.pendingReason }">{{ r.pendingReason || '✓ 价格校验通过' }}<details v-if="r.reviewWarning"><summary>原表说明</summary><small>{{ r.reviewWarning }}</small></details></td></tr></tbody></table><table v-else class="price-editor"><thead><tr><th>国家 / 分区</th><th>起重kg</th><th>含起点</th><th>止重kg</th><th>含终点</th><th>公斤价</th><th>每票费</th><th>时效 / 原表位置</th></tr></thead><tbody><tr v-for="r in visibleRows" :key="r.rowKey" :class="{ 'row-has-issue': r.pendingReason }"><td><b>{{ r.areaName }}</b><small>{{ r.zoneName || '无分区' }}</small></td><td><input v-model.number="r.weightFromKg" type="number" min="0" step="0.001"></td><td><input v-model="r.weightFromInclusive" type="checkbox"></td><td><input v-model.number="r.weightToKg" type="number" min="0" step="0.001"></td><td><input v-model="r.weightToInclusive" type="checkbox"></td><td><input v-model.number="r.pricePerKg" type="number" min="0" step="0.01"></td><td><input v-model.number="r.registrationFee" type="number" min="0" step="0.01"></td><td><input v-model.number="r.etaMinDays" aria-label="时效最早天数" type="number" min="1" max="365" step="1" @change="syncEtaRoute(r)"><input v-model.number="r.etaMaxDays" aria-label="时效最晚天数" type="number" min="1" max="365" step="1" @change="syncEtaRoute(r)"><small>{{ r.sourceSheet }} · 第{{ r.sourceRow }}行</small></td></tr></tbody></table></div>
            <ul v-if="detailTab === 'issues'" class="issue-list"><li v-for="(i, n) in version.issues || []" :key="n"><b>{{ i.level === 'error' ? '阻断' : '提醒' }}</b> · {{ i.sourceSheet || version.fileName }} · 第 {{ i.row || '—' }} 行 · {{ i.field }}：{{ i.message }}<button v-if="i.suggestedFields && version.status === 'draft'" @click="applySuggestion(i)">采用边界建议</button></li><li v-if="!version.issues?.length" class="issue-empty">✓ 没有解析或区间问题</li></ul>
            <LogisticsPager v-if="detailTab !== 'issues' && detailTotal" class="detail-pager detail-pager-bottom" :page="detailPage" :size="detailPageSize" :total="detailTotal" :total-pages="detailTotalPages" :loading="busy" :size-options="[10, 20, 50]" aria-label="价格明细底部分页" @page-change="changeDetailPage" @size-change="changeDetailSize" />
          </section>
          <aside class="release-check-panel version-check-panel"><h3>发布前检查</h3><button @click="detailTab = 'diff'; diffType = 'price'"><span class="check-dot price">¥</span><span>价格变化<small>已清楚展示旧价、新价和涨跌</small></span><b>{{ versionChangeCounts.price }}</b></button><button @click="detailTab = 'diff'; diffType = 'range'"><span class="check-dot range">↔</span><span>重量区间<small>扩大、缩小、重叠和断档</small></span><b>{{ versionChangeCounts.range }}</b></button><button @click="detailTab = 'issues'"><span class="check-dot issue">!</span><span>价格 / 重量问题<small>{{ version.errors ? '修正后重新校验' : '当前没有阻断问题' }}</small></span><b>{{ version.errors || 0 }}</b></button><button><span class="check-dot ready">✓</span><span>调价状态<small>{{ version.pricingReady === false ? '计费模型待适配' : '计费结构已校验' }}</small></span><b>{{ version.status === 'published' ? '已发布' : '待处理' }}</b></button><LogisticsBillingReview :key="`${version.id}-${version.status}`" :version-id="version.id" :readonly="archived" @updated="acceptanceUpdated" /><template v-if="version.status === 'draft'"><label v-if="hasCoverageRemoval(version.summary)" class="check"><input v-model="removal" type="checkbox">确认移除 / 覆盖缩小</label><label v-if="(version.summary.highRisk || 0) > 0" class="check"><input v-model="risk" type="checkbox">确认大幅涨跌风险</label></template></aside>
        </div>
        <footer v-if="!archived" class="review-publish-bar version-publish-bar"><button @click="version = null">返回批次</button><button :disabled="busy" @click="recompare">重新对比最新价格</button><label>审核备注<input v-model="note" maxlength="500" placeholder="填写价格来源、调整原因和审核结论"></label><template v-if="version.status === 'draft'"><button v-if="editingRows" class="primary" :disabled="busy" @click="saveCorrections">保存修正并重新校验</button><button class="primary publish-all" :disabled="busy || editingRows || version.errors > 0 || Boolean(version.blockingReasons?.length) || version.pricingReady === false || !note.trim() || (hasCoverageRemoval(version.summary) && !removal) || ((version.summary.highRisk || 0) > 0 && !risk)" @click="publish">一键审核并发布价格</button></template><button v-else-if="version.status === 'superseded'" :disabled="busy || !note.trim()" @click="rollback">以此版本创建回滚</button></footer>
      </section>
    </main>
  </div>
</template>

<style scoped>
.logistics-page{min-height:100vh;background:#f3f5f7;color:#243542;font-size:14px}main{max-width:1500px;margin:0 auto;padding:28px 32px 64px}.page-heading,.section-head{display:flex;justify-content:space-between;align-items:center;gap:24px}.page-heading h1{font-size:28px;margin:5px 0 8px;letter-spacing:-.6px}.page-heading p{color:#71818d;margin:4px 0}.eyebrow{font-size:11px;font-weight:750;letter-spacing:1.5px;color:#a76b30!important}.metrics{display:grid;grid-template-columns:repeat(4,1fr);gap:16px;margin:24px 0}.metrics>div{background:#fff;border:1px solid #e2e8ed;border-radius:10px;padding:18px 22px}.metrics small{color:#7c8b96;display:block}.metrics strong{display:block;font-size:30px;line-height:1.3;margin-top:6px}.tabs{display:flex;gap:28px;border-bottom:1px solid #dbe3e8;margin-bottom:20px}.tabs button{border:0;border-radius:0;background:none;padding:13px 0;color:#6d7d88;font-size:15px}.tabs .active{color:#c8752d;border-bottom:3px solid #df8d41;font-weight:700}.card{border:1px solid #dfe6eb;background:white;border-radius:10px;padding:22px;box-shadow:0 2px 4px #152a3b03}.stack{display:grid;gap:18px}h2{font-size:18px;margin:0 0 14px}.toolbar{display:flex;align-items:flex-end;gap:12px;flex-wrap:wrap;margin-bottom:16px}.toolbar>label{flex:1;min-width:150px;max-width:260px}label{display:flex;flex-direction:column;gap:7px;font-size:12px;color:#667781}input,select,textarea,button{font:inherit}input:not([type=checkbox]),select,textarea{border:1px solid #cdd8e0;border-radius:6px;padding:10px 12px;background:#fff;color:#253d4c}textarea{width:100%;min-height:70px;box-sizing:border-box;resize:vertical}button{border:1px solid #ccd7df;border-radius:6px;background:white;color:#435e70;padding:9px 14px;cursor:pointer;white-space:nowrap}button:hover:not(:disabled){background:#f4f8fa}button.primary{background:#da853c;border-color:#da853c;color:white}button.primary:hover:not(:disabled){background:#c77730}button:disabled{opacity:.45;cursor:not-allowed}.scroll{overflow:auto}table{width:100%;border-collapse:collapse;text-align:left}th{background:#f4f7f9;font-size:12px;color:#74838e;padding:12px;font-weight:600;white-space:nowrap}td{border-bottom:1px solid #e9eef1;padding:13px 12px;vertical-align:top;line-height:1.65}td small{display:block;color:#81919c;font-size:12px;max-width:460px;white-space:normal}td b{font-weight:600}td button{font-size:12px;padding:5px 10px}.notice{padding:14px 18px;background:#fff4df;border:1px solid #f1d8ad;border-radius:8px;line-height:1.7}.notice.error{background:#fff0ee;border-color:#eebcb5;color:#ab3e32}.notice.success{background:#edf8f2;border-color:#badfc9;color:#24724a}.muted{color:#7d8d98;line-height:1.7}.warning{color:#bc762b!important}.empty{text-align:center;color:#82929c;padding:36px}.pager{display:flex;gap:12px;align-items:center;justify-content:flex-end;margin-top:18px;color:#83939d;font-size:12px}.pager>span:first-child{margin-right:auto}.check{display:flex;flex-direction:row;align-items:center;margin:14px 0;font-size:13px}.cutover{border-top:3px solid #dd9247}.cutover select{max-width:500px;width:100%}.review{margin-top:24px;padding-top:20px;border-top:1px solid #e2e8ed}.version-detail{margin-top:24px;border-top:3px solid #54788e}.section-head p{color:#7f8f9b}progress{width:100%;height:9px;accent-color:#db8b42;margin:8px 0 14px}details{margin:12px 0;color:#657986}summary{cursor:pointer}summary button{font-size:11px;margin-left:8px}li{line-height:1.9}input[type=checkbox]{accent-color:#d58842}.batch-result-summary{display:flex;align-items:center;gap:10px;flex-wrap:wrap;margin:16px 0 12px}.batch-result-summary span{padding:7px 11px;border:1px solid #e2e8ed;border-radius:999px;background:#f8fafb;color:#667984;font-size:12px}.batch-result-summary b{color:#294250}.batch-result-toolbar{display:grid;grid-template-columns:minmax(240px,1fr) minmax(150px,220px) minmax(150px,220px);gap:12px;align-items:end;margin-bottom:12px}.batch-results-table td{padding-top:10px;padding-bottom:10px}.batch-result-pager{margin-top:12px}@media(max-width:800px){main{padding:18px 12px}.page-heading{align-items:flex-start;flex-direction:column}.metrics{grid-template-columns:repeat(2,1fr)}.card{padding:16px}td{min-width:100px}.toolbar>label{max-width:none}.tabs{gap:20px}.batch-result-toolbar{grid-template-columns:1fr}.batch-result-pager{flex-wrap:wrap}.batch-result-pager>span:first-child{width:100%}}
.import-target{padding:13px 15px;border:1px solid #efc28f;border-radius:8px;background:#fff8ef;color:#714b27}.import-target b{color:#9a5c22}.upload-file-list{display:grid;grid-template-columns:repeat(auto-fit,minmax(240px,1fr));gap:8px;margin:12px 0}.upload-file-list>span{display:flex;justify-content:space-between;align-items:center;gap:12px;padding:9px 11px;border:1px solid #dfe7ec;border-radius:7px;background:#f7f9fa;min-width:0}.upload-file-list b{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:12px}.upload-file-list small{color:#758691;white-space:nowrap}.logistics-upload-status{margin:14px 0;padding:14px 16px;border:1px solid #bcd6e8;border-radius:8px;background:#f3f9fd}.upload-status-heading{display:flex;justify-content:space-between;align-items:center;gap:16px}.upload-status-heading strong{color:#246da5}.logistics-upload-status progress{margin:10px 0 4px;accent-color:#3487bf}.logistics-upload-status p{margin:4px 0 10px}.change-summary,.inline-change-summary,.diff-kind-list{display:flex;align-items:center;gap:8px;flex-wrap:wrap}.change-summary{margin:14px 0}.change-summary>small{color:#80909b}.change-chip,.inline-change-summary span{display:inline-flex;align-items:center;gap:5px;padding:6px 9px;border:1px solid transparent;border-radius:999px;font-size:12px;font-weight:650;white-space:nowrap}.change-added{background:#edf8f2!important;border-color:#bfe2cd!important;color:#24724a!important}.change-price{background:#fff0ed!important;border-color:#f1c0b8!important;color:#bd493b!important}.change-rule{background:#fff7df!important;border-color:#ead69a!important;color:#956a13!important}.change-range{background:#eaf4ff!important;border-color:#b9d7f5!important;color:#246da5!important}.change-removed{background:#f3f4f5!important;border-color:#d4d8dc!important;color:#7b4545!important}.change-unchanged{background:#f3f5f6!important;border-color:#dde2e5!important;color:#70808b!important}.version-change-summary{padding:12px 0;border-top:1px solid #edf0f2;border-bottom:1px solid #edf0f2}.diff-row.diff-added{background:#f8fdf9}.diff-row.diff-price{background:#fff9f7}.diff-row.diff-rule{background:#fffdf5}.diff-row.diff-range{background:#f6faff}.diff-row.diff-removed{background:#fbf8f8}.diff-change-line{margin-bottom:5px}.impact-up{color:#c44135!important}.impact-down{color:#278155!important}.advanced-operation{margin-top:0}.advanced-operation>summary{display:flex;align-items:center;gap:20px;list-style:none;color:#243542}.advanced-operation>summary::-webkit-details-marker{display:none}.advanced-operation>summary::after{content:'⌄';margin-left:auto;color:#71818d}.advanced-operation[open]>summary::after{content:'⌃'}.advanced-operation>summary span{font-size:12px;color:#7d8d98}.advanced-operation-body{display:flex;align-items:flex-end;gap:12px;flex-wrap:wrap;margin-top:18px;padding-top:18px;border-top:1px solid #e7ecef}.advanced-operation-body label{min-width:260px}.diff-table th:nth-child(3){min-width:340px}.diff-table th:nth-child(4){min-width:190px}
.batch-file-list{display:grid;gap:8px;margin:14px 0}.batch-file-list details{margin:0;padding:10px 12px;border:1px solid #e1e7eb;border-radius:7px;background:#fafbfc}.batch-file-list summary{display:flex;align-items:center;gap:12px}.batch-file-list summary>span{min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.batch-file-list summary>b{margin-left:auto;color:#4f7a5f;font-size:12px}.comparison-explanation{display:block;margin-bottom:8px;max-width:620px;font-size:12px;line-height:1.65;color:#344f60}.version-detail{scroll-margin-top:18px}
.price-skeleton span{display:block;width:80%;height:14px;border-radius:5px;background:linear-gradient(90deg,#edf1f4 25%,#f7f9fa 50%,#edf1f4 75%);background-size:200% 100%;animation:price-skeleton 1.2s infinite}.price-skeleton td{height:40px}@keyframes price-skeleton{to{background-position:-200% 0}}
.ready-publish-panel{display:grid;grid-template-columns:minmax(280px,1fr) minmax(240px,1fr) auto;gap:14px;align-items:end;margin:16px 0;padding:16px;border:1px solid #b9ddc9;border-radius:9px;background:#f2faf5}.ready-publish-panel p{margin:5px 0 0;color:#648071;line-height:1.6}.ready-publish-panel .check{margin:0}.publish-result{margin:12px 0;padding:12px 15px;border-radius:8px;background:#f7f9fa}.publish-result p{margin:6px 0;color:#80603e}.range-compare{display:grid;gap:6px;margin-bottom:8px}.range-compare>div{position:relative;height:24px;background:#edf1f4;border-radius:4px;overflow:hidden}.range-compare span{position:absolute;left:7px;top:3px;z-index:2;font-size:11px;font-weight:700}.range-compare i{position:absolute;top:0;height:100%;border-radius:4px;opacity:.72}.range-compare i.old{background:#9aa9b3}.range-compare i.next{background:#4e9fe0}.range-compare small{position:absolute;right:7px;top:3px;z-index:2;color:#314a59}.price-editor input[type=number]{width:90px;padding:7px}.price-editor td{white-space:nowrap}.price-editor td:nth-child(8) input,.price-editor td:nth-child(9) input{display:block;margin-bottom:5px}.issue-list{padding:0;list-style:none}.issue-list li{margin:8px 0;padding:12px 14px;border-left:4px solid #d58b42;background:#fff8ef}.issue-list button{margin-left:12px;font-size:12px;padding:4px 8px}@media(max-width:900px){.ready-publish-panel{grid-template-columns:1fr}.ready-publish-panel button{justify-self:start}}
.batch-review-workbench,.version-review-workbench{overflow:hidden;border:1px solid #dbe3e9;border-radius:12px;background:#fff;box-shadow:0 8px 28px rgba(31,54,70,.06)}.review-workbench-head{display:grid;grid-template-columns:minmax(320px,1fr) auto minmax(250px,1fr);align-items:center;gap:28px;padding:20px 24px;border-bottom:1px solid #e4e9ed;background:#fff}.review-title{display:flex;align-items:center;gap:13px;min-width:0}.review-title h2{margin:1px 0 3px;font-size:21px}.review-title p{margin:0;color:#778791;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.review-icon{display:grid;place-items:center;width:38px;height:38px;border-radius:9px;background:#edf4fa;color:#356b94;font-size:22px}.review-steps{display:flex;align-items:center;justify-content:center;gap:10px;white-space:nowrap;color:#87949d;font-size:13px}.review-steps span{display:flex;align-items:center;gap:7px;font-weight:650}.review-steps i{display:grid;place-items:center;width:27px;height:27px;border-radius:50%;background:#edf0f3;color:#6e7c86;font-style:normal}.review-steps b{width:60px;height:2px;background:#dce3e8}.review-steps .done{color:#26784d}.review-steps .done i{background:#26935d;color:#fff}.review-steps .current{color:#1e62b4}.review-steps .current i{background:#1e62b4;color:#fff}.review-head-actions{display:flex;justify-content:flex-end;gap:8px}.review-progress{padding:16px 24px;background:#f7fafc}.review-progress>div{display:flex;justify-content:space-between}.review-focus-tabs{display:flex;gap:10px;padding:18px 24px;border-bottom:1px solid #e5eaee;background:#fbfcfd}.review-focus-tabs button{padding:10px 15px;background:#fff}.review-focus-tabs button b{margin-left:7px}.review-focus-tabs button.active{border-color:#2b73d2;box-shadow:0 0 0 1px #2b73d2;color:#1e62b4;background:#f5f9ff}.review-focus-tabs .issue-tab.active,.review-focus-tabs .issue-tab b{color:#c23d35}.batch-review-layout,.version-review-layout{display:grid;grid-template-columns:minmax(0,1fr) 286px;gap:18px;padding:18px 20px 20px;background:#f7f9fb}.review-main-panel{min-width:0;padding:16px;border:1px solid #e0e6eb;border-radius:9px;background:#fff}.review-main-panel .batch-result-toolbar{margin-bottom:14px}.batch-results-table th:nth-child(2){width:47%}.batch-results-table td{vertical-align:middle}.batch-results-table .inline-change-summary{margin-top:7px}.row-has-issue{background:#fff8f7!important}.review-status{display:inline-flex;padding:4px 8px;border-radius:5px;background:#edf3f7;color:#4e6574;font-size:12px}.review-status.published{background:#eaf7ef;color:#24724a}.review-status.blocked{background:#fff0ed;color:#b63e35}.review-status.draft{background:#fff7df;color:#966814}.row-action{border-color:#b9cee2;color:#245f92;font-weight:650}.batch-source-files{margin-top:14px;padding-top:12px;border-top:1px solid #e6ebee}.release-check-panel{position:sticky;top:16px;align-self:start;padding:17px;border:1px solid #dfe5ea;border-radius:9px;background:#fff}.release-check-panel h3{margin:0 0 14px;font-size:17px}.release-check-panel>button:not(.retry-button){display:grid;grid-template-columns:36px 1fr auto;align-items:center;gap:10px;width:100%;margin:9px 0;padding:13px;text-align:left}.release-check-panel>button>span:nth-child(2){font-weight:650;color:#314958}.release-check-panel>button small{display:block;margin-top:2px;color:#86949d;font-weight:400;white-space:normal}.release-check-panel>button>b{font-size:19px}.check-dot{display:grid;place-items:center;width:30px;height:30px;border-radius:8px;font-weight:800}.check-dot.price{background:#eaf3ff;color:#2f74c8}.check-dot.range{background:#fff0ef;color:#cc4540}.check-dot.issue{background:#fff5df;color:#c57916}.check-dot.ready{background:#eaf7ef;color:#258653}.release-note{padding:12px;border:1px solid #c9dced;border-radius:7px;background:#f2f8fd;color:#4d6a7e;font-size:12px;line-height:1.65}.retry-button{width:100%;color:#a64b3d;border-color:#e6b9b2}.review-publish-bar{position:sticky;bottom:0;z-index:5;display:grid;grid-template-columns:minmax(220px,1fr) minmax(260px,1.2fr) auto auto auto;align-items:end;gap:12px;padding:16px 22px;border-top:1px solid #dfe6eb;background:rgba(255,255,255,.97);box-shadow:0 -7px 20px rgba(30,51,67,.07);backdrop-filter:blur(8px)}.review-publish-bar>div small{display:block;margin-top:3px;color:#7d8c96}.review-publish-bar .check{margin:0;max-width:170px}.publish-all{min-height:42px;font-weight:700}.version-review-workbench{margin-top:0}.version-tabs{padding-top:14px;padding-bottom:14px}.version-review-layout{grid-template-columns:minmax(0,1fr) 310px}.version-toolbar{display:flex;justify-content:space-between;align-items:center;gap:12px;margin-bottom:12px}.version-toolbar>div{display:flex;gap:8px;align-items:center}.version-check-panel{position:static}.version-check-panel :deep(section),.version-check-panel :deep(.card){box-shadow:none!important;border-radius:7px!important;margin-top:14px!important}.version-publish-bar{grid-template-columns:auto auto minmax(280px,1fr) auto}.diff-change-line del{color:#8a969e}.diff-change-line strong{color:#173f5a}.issue-empty{border-left-color:#2d9561!important;background:#eff9f3!important;color:#26784d}.price-editor .row-has-issue input{border-color:#df655c;background:#fff8f7}
.review-main-panel>.detail-pager-top{margin:0 0 12px;padding:12px 0;border-top:0;border-bottom:1px solid #edf1f4}.review-main-panel>.detail-pager-bottom{margin-top:12px}.version-review-layout .review-main-panel>.scroll{max-height:min(64vh,720px);border:1px solid #e6ebef;border-radius:7px}.version-review-layout .review-main-panel>.scroll thead th{position:sticky;top:0;z-index:2;box-shadow:0 1px 0 #dfe6eb}.pagination-edit-note{margin:0 0 10px;padding:9px 12px;border-radius:6px;background:#fff6e8;color:#8b5d20;font-size:12px}
@media(max-width:1150px){.review-workbench-head{grid-template-columns:1fr}.review-steps{justify-content:flex-start}.review-head-actions{justify-content:flex-start}.batch-review-layout,.version-review-layout{grid-template-columns:1fr}.release-check-panel{position:static}.review-publish-bar,.version-publish-bar{grid-template-columns:1fr 1fr}.review-focus-tabs{overflow:auto}.version-toolbar{align-items:flex-start;flex-direction:column}}
@media(max-width:700px){.review-publish-bar,.version-publish-bar{grid-template-columns:1fr}.review-steps b{width:24px}.review-workbench-head{padding:16px}.batch-review-layout,.version-review-layout{padding:10px}.review-main-panel{padding:10px}}
.milano-heading{display:flex;align-items:flex-end;justify-content:space-between;gap:24px;margin-bottom:20px}.milano-heading p{margin:0 0 5px;color:#d97800;font-size:10px;font-weight:900;letter-spacing:.16em}.milano-heading h1{margin:0 0 7px;color:#17232e;font-size:28px;letter-spacing:-.5px}.milano-heading span{color:#72808a;font-size:12px}.workspace-switch{display:flex;gap:8px;margin:0 0 16px;padding:5px;background:#e7ecef;border-radius:10px}.workspace-switch button{min-width:180px;height:40px;padding:0 24px;border:0;border-radius:7px;background:transparent;color:#68747e;font-weight:750}.workspace-switch button.active{background:#fff;color:#17232e;box-shadow:0 3px 12px #24313d12}.workspace-switch button:focus-visible{outline:0;box-shadow:0 0 0 3px #ff991033}.hidden-file{display:none}.base-settings,.rule-workspace-card{overflow:hidden;border:1px solid #dfe6ea;border-radius:12px;background:#fff;box-shadow:0 8px 28px #1e2c3810}.base-toolbar,.rule-card-head{display:flex;align-items:center;justify-content:space-between;gap:20px;padding:22px 24px}.base-toolbar{padding-bottom:12px}.base-toolbar h2,.rule-card-head h2{margin:0 0 5px;color:#17232e;font-size:18px}.base-toolbar p,.rule-card-head p{margin:0;color:#7a858f;font-size:11px}.provider-toolbar,.provider-detail-actions{display:flex;align-items:center;gap:9px}.provider-toolbar label{display:flex;align-items:center;gap:7px;width:260px;height:38px;box-sizing:border-box;padding:0 11px;border:1px solid #dbe2e7;border-radius:7px;color:#72808b}.provider-toolbar input{width:100%;padding:0!important;border:0!important;outline:0;background:transparent}.outline-orange{height:38px;padding:0 16px;border:1px solid #ff9414;color:#d97900;font-weight:800}.provider-manager{display:grid;grid-template-columns:320px minmax(0,1fr);gap:14px;padding:12px 24px 26px}.provider-list,.provider-detail{min-width:0;overflow:hidden;border:1px solid #dfe5e9;border-radius:10px;background:#fff;box-shadow:0 5px 18px rgba(30,44,56,.035)}.provider-sort-hint{height:44px;display:flex;align-items:center;justify-content:space-between;padding:0 12px;border-bottom:1px solid #e5eaed}.provider-sort-hint span{font-weight:850}.provider-sort-hint small{color:#929da5}.provider-list-scroll{height:458px;padding:8px;overflow:auto;background:#f7f9fa}.provider-list-scroll>button{width:100%;min-height:54px;display:grid;grid-template-columns:14px 36px minmax(0,1fr) auto 12px;align-items:center;gap:8px;margin-bottom:7px;padding:7px 9px;border:1px solid #e0e6ea;border-radius:8px;background:#fff;box-shadow:0 2px 7px #2637440a;text-align:left;color:#26333d}.provider-list-scroll>button:hover{border-color:#f1b967;background:#fffaf2}.provider-list-scroll>button.active{border-color:#ffae42;box-shadow:inset 4px 0 #ff930f,0 4px 12px #bd690014;background:#fff4e4}.provider-list-scroll>button u{color:#aeb7be;text-decoration:none}.provider-list-scroll>button i,.provider-icon{display:grid;place-items:center;border-radius:50%;background:#fff0d9;color:#d97600;font-style:normal;font-weight:900}.provider-list-scroll>button i{width:32px;height:32px}.provider-list-scroll>button span{min-width:0;display:grid;gap:2px}.provider-list-scroll>button b{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.provider-list-scroll>button small{color:#9aa3aa;font-size:9px}.provider-list-scroll>button em{color:#84909a;font-size:10px;font-style:normal;white-space:nowrap}.provider-list-scroll>button strong{color:#89959e;font-size:20px}.provider-list-scroll>button.active em,.provider-list-scroll>button.active strong{color:#e17a00}.provider-list>footer{height:38px;display:grid;place-items:center;border-top:1px solid #e8ecef;color:#7f8a93;font-size:11px}.provider-empty{display:grid;place-items:center;height:160px;color:#929ca4}.provider-detail>header{display:flex;align-items:center;gap:12px;min-height:74px;padding:0 18px;border-bottom:1px solid #e8ecef}.provider-icon{width:42px;height:42px;font-size:16px;flex:none}.provider-detail h3{margin:0 0 4px;font-size:17px}.provider-detail h3 span{display:inline-block;margin-left:7px;padding:3px 7px;border-radius:10px;background:#fff0d8;color:#d47600;font-size:9px}.provider-detail header small{color:#8a949c}.provider-detail-actions{margin-left:auto;flex-wrap:wrap;justify-content:flex-end}.provider-detail .upload{height:36px;padding:0 15px;border:0;background:#ff9511;color:#fff;font-weight:800}.provider-detail-body{padding:18px}.provider-detail-body h4{margin:0 0 12px;font-size:14px}.provider-template-table{overflow-x:auto;border:1px solid #dfe5e9;border-radius:8px}.template-table-head,.template-table-row{min-width:1000px;display:grid;grid-template-columns:minmax(180px,1.3fr) minmax(180px,1.2fr) 120px 105px 150px 220px;align-items:center;gap:12px;min-height:48px;padding:0 14px}.template-table-head{min-height:38px;background:#f5f7f8;color:#66737e;font-size:10px;font-weight:800}.template-table-row{border-top:1px solid #edf0f2;font-size:11px}.template-table-row>b{min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.template-table-row>b small,.template-table-row>div>small{display:block;color:#939da5;font-size:9px;font-weight:500}.template-table-row>div>strong{display:block}.template-table-row time{color:#65727c}.adjustment-status{width:max-content;padding:4px 9px;border-radius:10px;font-size:9px;font-weight:850}.adjustment-status.published{background:#e6f6eb;color:#198951}.adjustment-status.pending{background:#fff0d8;color:#d57900}.template-actions{display:flex;align-items:center;flex-wrap:wrap;gap:5px 10px}.template-actions button{padding:0;border:0;background:none;color:#247cb0;font-size:10px}.template-actions .move-template{color:#d87900;font-weight:850}.template-table-empty{display:grid;place-items:center;height:104px;color:#929ca4}.inline-upload-panel{margin-top:18px;padding:16px;border:1px dashed #e2ad63;border-radius:9px;background:#fffaf3}.inline-upload-head{display:flex;align-items:center;gap:9px}.inline-upload-head>div{min-width:0;display:grid;gap:4px;margin-right:auto}.inline-upload-head small{color:#81909a}.secondary-management{margin-top:16px;border:1px solid #dfe6ea;border-radius:10px;background:#fff}.secondary-management>summary{display:flex;align-items:center;justify-content:space-between;gap:20px;padding:17px 20px;list-style:none}.secondary-management>summary::-webkit-details-marker{display:none}.secondary-management>summary span{display:grid;gap:3px}.secondary-management>summary small{color:#84919a}.secondary-management>summary em{color:#d87b00;font-style:normal;font-weight:800}.secondary-management-body{display:grid;gap:16px;padding:0 18px 18px;border-top:1px solid #e8edef;background:#f8fafb}.secondary-management-body>.card,.secondary-management-body>:deep(.card){margin-top:16px}.rule-card-head{border-bottom:1px solid #e6ebee}.modern-filters{display:flex;align-items:flex-end;gap:10px;flex-wrap:wrap;padding:15px 20px;border-bottom:1px solid #e6ebee;background:#fafbfc}.modern-filters label{min-width:150px}.modern-filters .keyword-search{min-width:260px;display:flex;flex-direction:row;align-items:center;gap:7px;height:38px;padding:0 11px;border:1px solid #dbe2e7;border-radius:7px;background:#fff}.modern-filters .keyword-search input{width:100%;padding:0;border:0;outline:0}.modern-filters>span{margin-left:auto;color:#7d8992}.history-card .section-head{margin-bottom:16px}.history-card .section-head>button:first-of-type{margin-left:auto}
.provider-channel-pager{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-top:12px;color:#71838e;font-size:12px}.provider-channel-pager>span{font-weight:650;color:#526b79}.provider-channel-pager>div{display:flex;align-items:center;gap:10px}.provider-channel-pager button{padding:6px 10px}.provider-channel-pager b{min-width:64px;text-align:center;color:#435e70}
@media(max-width:1050px){.milano-heading{align-items:flex-start;flex-direction:column}.provider-manager{grid-template-columns:280px minmax(0,1fr)}.provider-detail>header{align-items:flex-start;flex-wrap:wrap;padding-block:14px}.provider-detail-actions{width:100%;margin-left:54px;justify-content:flex-start}}
@media(max-width:760px){.workspace-switch button{min-width:0;flex:1;padding:0 10px}.base-toolbar{align-items:flex-start;flex-direction:column}.provider-toolbar{width:100%;flex-wrap:wrap}.provider-toolbar label{width:100%}.provider-manager{grid-template-columns:1fr;padding:10px}.provider-list-scroll{height:250px}.provider-detail-actions{margin-left:0}.inline-upload-head{align-items:stretch;flex-direction:column}.inline-upload-head>div{margin-right:0}.modern-filters{align-items:stretch}.modern-filters label,.modern-filters .keyword-search{width:100%;max-width:none}}
.provider-list-skeleton{min-height:54px;display:grid;grid-template-columns:36px minmax(0,1fr) 58px;align-items:center;gap:10px;margin-bottom:7px;padding:7px 12px;box-sizing:border-box;border:1px solid #e8edef;border-radius:8px;background:#fff}.provider-list-skeleton i,.provider-list-skeleton b,.provider-list-skeleton small,.provider-list-skeleton em,.provider-detail-skeleton i{display:block;border-radius:6px;background:linear-gradient(90deg,#edf1f4 25%,#f8fafb 50%,#edf1f4 75%);background-size:200% 100%;animation:price-skeleton 1.2s infinite}.provider-list-skeleton>i{width:32px;height:32px;border-radius:50%}.provider-list-skeleton span{display:grid;gap:7px}.provider-list-skeleton b{width:72%;height:11px}.provider-list-skeleton small{width:48%;height:8px}.provider-list-skeleton em{width:58px;height:10px}.provider-detail-skeleton header>div{display:grid;gap:9px;flex:1}.provider-detail-skeleton header>div i:first-child{width:180px;height:16px}.provider-detail-skeleton header>div i:last-child{width:310px;max-width:75%;height:10px}.provider-detail-skeleton .provider-detail-body{display:grid;gap:10px}.provider-detail-skeleton .provider-detail-body>i{height:48px;border:1px solid #edf0f2}.provider-toolbar .provider-search-field{flex-direction:row}.provider-toolbar a.outline-orange{display:inline-flex;align-items:center;box-sizing:border-box;border-radius:7px;background:#fff;text-decoration:none;white-space:nowrap}
</style>
