<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import AppTopbar from '@/components/AppTopbar.vue'
import type { LogisticsRule } from '@/data/logistics'
import { loadPublishedLogisticsManifest } from '@/data/publishedLogisticsRepository'
import {
  logisticsChannelRows,
  logisticsProviderRows,
  logisticsRuleDetailColumns,
  logisticsRuleTabs,
  logisticsWorkspaceSummary,
  type LogisticsRuleTab,
} from '@/data/logisticsWorkspaceView'
import { parseLogisticsWorkbook, type LogisticsDiffField, type LogisticsDiffRow, type LogisticsImportPreview } from '@/data/logisticsWorkbook'
import {
  addLogisticsChannel,
  addLogisticsProvider,
  archiveLogisticsChannel,
  createLogisticsDraft,
  deleteLogisticsChannel,
  deleteLogisticsProvider,
  importLogisticsProviderFiles,
  importLogisticsGlobalFiles,
  filterPublishableLogisticsVersionIds,
  initialLogisticsBatchSelection,
  initialLogisticsVersionPublishSelection,
  loadCurrentVersionRows,
  loadLogisticsVersionDetail,
  loadLogisticsWorkspace,
  normalizeLogisticsPriceRow,
  previewLogisticsProviderImports,
  previewLogisticsGlobalImports,
  publishLogisticsProviderVersions,
  publishLogisticsVersion,
  logisticsVersionHasBlockingErrors,
  logisticsVersionNeedsRiskReview,
  rejectLogisticsVersion,
  rollbackLogisticsVersion,
  setLogisticsChannelStatus,
  setLogisticsProviderStatus,
  workspaceLogisticsRules,
  type LogisticsBatchPreview,
  type LogisticsChannelRecord,
  type LogisticsChannelVersionRecord,
  type LogisticsProviderRecord,
  type LogisticsWorkspaceState,
} from '@/data/logisticsRepository'

const rules = ref<LogisticsRule[]>([])
const workspaceLoading = ref(true)
const workspaceError = ref('')
const typeFilter = ref('')
const statusFilter = ref('')
const changeFilter = ref<'pending' | 'up' | 'down' | 'risk' | ''>('')
const searchMode = ref<'name' | 'code'>('name')
const keyword = ref('')
const page = ref(1)
const pageSize = ref(10)
const toast = ref('')
const view = ref<'list' | 'details'>('list')
const activeRule = ref<LogisticsRule | null>(null)
const areaKeyword = ref('')
type WorkspaceMode = 'rules' | 'base'
const workspaceMode = ref<WorkspaceMode>('base')
const activeRulesTab = ref<LogisticsRuleTab>('运费规则')
const publishedCountries = ref<Array<{ code: string; name: string }>>([])
const showBlockedOnly = ref(false)
const templateInput = ref<HTMLInputElement | null>(null)
const uploadChannelId = ref('')
const uploadMode = ref<'single' | 'batch-update' | 'batch-import' | 'global-import'>('single')
const providerSearch = ref('')
const showProviderEditor = ref(false)
const providerForm = reactive({ name: '', code: '' })
const showChannelEditor = ref(false)
const channelForm = reactive({ name: '', code: '', type: '专线', logisticsAttribute: '普货' })
const workspace = ref<LogisticsWorkspaceState>({ providers: [], channels: [], versions: [], audits: [] })
const selectedProviderId = ref('')
const importPreview = ref<LogisticsImportPreview | null>(null)
const reviewingVersion = ref<LogisticsChannelVersionRecord | null>(null)
const reviewingChannel = ref<LogisticsChannelRecord | null>(null)
const showImportReview = ref(false)
const auditNote = ref('')
const removalConfirmed = ref(false)
const reviewOnlyRisk = ref(false)
const reviewCountryKeyword = ref('')
const selectedReviewKey = ref('')
const expandedReviewCountries = ref<string[]>([])
const reviewedDiffKeys = ref<string[]>([])
const historyChannel = ref<LogisticsChannelRecord | null>(null)
const showVersionHistory = ref(false)
const pendingHistoryAction = ref<{ type: 'terminate' | 'rollback'; version: LogisticsChannelVersionRecord } | null>(null)
const historyActionNote = ref('')
const batchFiles = ref<File[]>([])
const batchPreview = ref<LogisticsBatchPreview | null>(null)
const showBatchPreview = ref(false)
const replaceBatchDrafts = ref(false)
const batchSelectedKeys = ref<string[]>([])
const showArchivedChannels = ref(false)
const archivingChannel = ref<LogisticsChannelRecord | null>(null)
const archiveReason = ref('')
const showBatchReview = ref(false)
const batchSelectedVersionIds = ref<string[]>([])
const batchReviewedVersionIds = ref<string[]>([])
const batchAuditNote = ref('')
const providerSettings = computed(() => workspace.value.providers)
const blockedProviderIds = computed(() => new Set(logisticsChannelRows(workspace.value).filter(item => item.blockedErrors > 0).map(item => item.channel.providerId)))
const filteredProviderSettings = computed(() => {
  const query = providerSearch.value.trim().toLowerCase()
  return providerSettings.value.filter(item => (!showBlockedOnly.value || blockedProviderIds.value.has(item.id)) && (!query || `${item.name} ${item.code}`.toLowerCase().includes(query)))
})
const selectedProvider = computed(() => providerSettings.value.find(item => item.id === selectedProviderId.value) ?? providerSettings.value[0])
const selectedProviderChannels = computed(() => workspace.value.channels.filter(item => item.providerId === selectedProvider.value?.id && Boolean(item.archived) === showArchivedChannels.value && (!showBlockedOnly.value || Boolean(draftVersion(item)?.errors))))
const activeProviderChannels = computed(() => workspace.value.channels.filter(item => item.providerId === selectedProvider.value?.id && !item.archived))
const historyVersions = computed(() => workspace.value.versions.filter(item => item.channelId === historyChannel.value?.id))
const selectedProviderDrafts = computed(() => activeProviderChannels.value.map(channel => ({ channel, version: draftVersion(channel) })).filter((item): item is { channel: LogisticsChannelRecord; version: LogisticsChannelVersionRecord } => Boolean(item.version)))
const publishableProviderDrafts = computed(() => selectedProviderDrafts.value.filter(item => !logisticsVersionHasBlockingErrors(item.version)))
const blockedProviderDrafts = computed(() => selectedProviderDrafts.value.filter(item => logisticsVersionHasBlockingErrors(item.version)))
const selectedPublishableVersionIds = computed(() => filterPublishableLogisticsVersionIds(selectedProviderDrafts.value.map(item => item.version), batchSelectedVersionIds.value))
const selectedBatchItems = computed(() => batchPreview.value?.items.filter(item => batchSelectedKeys.value.includes(item.fileKey)) ?? [])
const selectedBatchDraftCount = computed(() => selectedBatchItems.value.filter(item => item.hasDraft).length)
const workspaceSummary = computed(() => logisticsWorkspaceSummary(workspace.value))
const providerRows = computed(() => logisticsProviderRows(workspace.value))
const channelRows = computed(() => logisticsChannelRows(workspace.value))
const reviewChangedDiffs = computed(() => importPreview.value?.diffRows.filter(item => item.type !== 'unchanged') ?? [])
const reviewVisibleDiffs = computed(() => reviewChangedDiffs.value.filter(item => {
  const query = reviewCountryKeyword.value.trim().toLowerCase()
  const matchesSearch = !query || `${item.row.areaName} ${item.row.countryCode}`.toLowerCase().includes(query)
  const matchesRisk = !reviewOnlyRisk.value || item.risk || item.type === 'removed'
  return matchesSearch && matchesRisk
}))
const reviewCountryGroups = computed(() => {
  const groups = new Map<string, { key: string; name: string; code: string; items: LogisticsDiffRow[] }>()
  reviewVisibleDiffs.value.forEach(item => {
    const key = `${item.row.countryCode || '—'}|${item.row.areaName || '未命名区域'}`
    const group = groups.get(key) || { key, name: item.row.areaName || '未命名区域', code: item.row.countryCode || '—', items: [] }
    group.items.push(item); groups.set(key, group)
  })
  return [...groups.values()].map(group => ({
    ...group,
    items: group.items.sort((a, b) => a.row.weightFromKg - b.row.weightFromKg),
    riskCount: group.items.filter(item => item.risk || item.type === 'removed').length,
    priceCount: group.items.filter(item => item.type === 'price').length,
  }))
})
const selectedReviewDiff = computed(() => reviewVisibleDiffs.value.find(item => item.key === selectedReviewKey.value) || reviewVisibleDiffs.value[0] || null)
const selectedReviewCountryKey = computed(() => selectedReviewDiff.value ? `${selectedReviewDiff.value.row.countryCode || '—'}|${selectedReviewDiff.value.row.areaName || '未命名区域'}` : '')
const reviewPriceChanges = computed(() => selectedReviewDiff.value?.changes.filter(item => item.price) ?? [])
const reviewRuleChanges = computed(() => selectedReviewDiff.value?.changes.filter(item => !item.price) ?? [])
const reviewedCount = computed(() => reviewChangedDiffs.value.filter(item => reviewedDiffKeys.value.includes(item.key)).length)
const mandatoryReviewDiffs = computed(() => reviewChangedDiffs.value.filter(item => item.risk || item.type === 'removed'))
const remainingRequiredReviewCount = computed(() => mandatoryReviewDiffs.value.filter(item => !reviewedDiffKeys.value.includes(item.key)).length)
const allRequiredReviewed = computed(() => remainingRequiredReviewCount.value === 0)

function providerChannelCount(provider: LogisticsProviderRecord) { return workspace.value.channels.filter(item => item.providerId === provider.id).length }
function channelVersionCount(channel: LogisticsChannelRecord) { return workspace.value.versions.filter(item => item.channelId === channel.id).length }
function currentVersion(channel: LogisticsChannelRecord) { return workspace.value.versions.find(item => item.id === channel.currentVersionId) }
function draftVersion(channel: LogisticsChannelRecord) { return workspace.value.versions.find(item => item.channelId === channel.id && item.status === 'draft') }
function visibleVersion(channel: LogisticsChannelRecord) { return currentVersion(channel) || draftVersion(channel) }
function countryCount(version?: LogisticsChannelVersionRecord) { return version?.countryCount || (version ? new Set(version.rows.map(row => row.countryCode || row.areaName)).size : 0) }
function reviewCountryKey(diff: LogisticsDiffRow) { return `${diff.row.countryCode || '—'}|${diff.row.areaName || '未命名区域'}` }
function initializeReviewWorkspace(preview: LogisticsImportPreview) {
  const changes = preview.diffRows.filter(item => item.type !== 'unchanged')
  const first = changes.find(item => item.risk || item.type === 'removed') || changes[0]
  reviewOnlyRisk.value = false; reviewCountryKeyword.value = ''; reviewedDiffKeys.value = []
  selectedReviewKey.value = first?.key || ''
  expandedReviewCountries.value = first ? [reviewCountryKey(first)] : []
}
function selectReviewDiff(diff: LogisticsDiffRow) {
  selectedReviewKey.value = diff.key
  const countryKey = reviewCountryKey(diff)
  if (!expandedReviewCountries.value.includes(countryKey)) expandedReviewCountries.value = [...expandedReviewCountries.value, countryKey]
}
function toggleReviewCountry(key: string) {
  expandedReviewCountries.value = expandedReviewCountries.value.includes(key)
    ? expandedReviewCountries.value.filter(item => item !== key)
    : [...expandedReviewCountries.value, key]
}
function toggleRiskReview() {
  reviewOnlyRisk.value = !reviewOnlyRisk.value
  const first = reviewVisibleDiffs.value[0]
  if (first && !reviewVisibleDiffs.value.some(item => item.key === selectedReviewKey.value)) selectReviewDiff(first)
}
function toggleReviewed(diff: LogisticsDiffRow | null) {
  if (!diff) return
  reviewedDiffKeys.value = reviewedDiffKeys.value.includes(diff.key)
    ? reviewedDiffKeys.value.filter(item => item !== diff.key)
    : [...reviewedDiffKeys.value, diff.key]
}
function reviewTypeLabel(type: LogisticsDiffRow['type']) { return type === 'added' ? '新增' : type === 'price' ? '调价' : type === 'removed' ? '移除' : type === 'rule' ? '规则变更' : '无变化' }
function displayReviewValue(change: LogisticsDiffField, value: string | number | boolean) {
  if (typeof value === 'boolean') return value ? '是' : '否'
  if (change.price && typeof value === 'number') return change.field.includes('费率') ? `${value}%` : `¥${value.toFixed(2)}`
  if (value === '') return '未设置'
  return String(value)
}
function reviewChangeDirection(change: LogisticsDiffField) {
  const before = Number(change.before); const after = Number(change.after)
  if (!change.price || !Number.isFinite(before) || !Number.isFinite(after)) return 'rule'
  if (before === 0 && after > 0) return 'added'
  return after > before ? 'up' : after < before ? 'down' : 'same'
}
function reviewDelta(change: LogisticsDiffField) {
  const before = Number(change.before); const after = Number(change.after)
  if (!change.price || !Number.isFinite(before) || !Number.isFinite(after)) return ''
  if (before === 0 && after > 0) return '新增收费项'
  const delta = after - before
  return `${delta > 0 ? '↑' : '↓'} ¥${Math.abs(delta).toFixed(2)}`
}
function reviewPercent(change: LogisticsDiffField) {
  const before = Number(change.before); const after = Number(change.after)
  if (!change.price || !Number.isFinite(before) || !Number.isFinite(after)) return ''
  if (before === 0) return after > 0 ? '+100%' : '0%'
  const percent = (after - before) / Math.abs(before) * 100
  return `${percent > 0 ? '+' : ''}${percent.toFixed(1)}%`
}
function triggerTemplateUpload(channelId = '', mode: 'single' | 'batch-update' | 'batch-import' | 'global-import' = 'single') {
  uploadChannelId.value = channelId
  uploadMode.value = mode
  templateInput.value?.click()
}
async function refreshWorkspace() {
  workspaceLoading.value = true
  workspaceError.value = ''
  try {
    const [nextWorkspace, manifestResult] = await Promise.all([
      loadLogisticsWorkspace(),
      loadPublishedLogisticsManifest().catch(() => null),
    ])
    workspace.value = nextWorkspace
    publishedCountries.value = manifestResult?.manifest.countries || []
    rules.value = structuredClone(workspaceLogisticsRules(workspace.value))
    if (!workspace.value.providers.some(item => item.id === selectedProviderId.value)) selectedProviderId.value = workspace.value.providers[0]?.id || ''
  } catch (error) {
    workspaceError.value = error instanceof Error ? error.message : '物流工作区加载失败'
  } finally { workspaceLoading.value = false }
}
async function importChannelFile(channel: LogisticsChannelRecord, file: File, openReview = true) {
  const preview = await parseLogisticsWorkbook(file, await loadCurrentVersionRows(workspace.value, channel))
  if (!preview.validRows) throw new Error('文件没有可导入的有效价格行')
  const version = await createLogisticsDraft(channel.id, preview, file)
  await refreshWorkspace()
  if (openReview) {
    importPreview.value = preview; reviewingVersion.value = version; reviewingChannel.value = channel
    auditNote.value = ''; removalConfirmed.value = false; initializeReviewWorkspace(preview); showImportReview.value = true
  }
  return version
}
async function handleTemplateUpload(event: Event) {
  const input = event.target as HTMLInputElement
  if (!input.files?.length) return
  const files = Array.from(input.files)
  try {
    if (uploadMode.value !== 'single') {
      batchFiles.value = files
      if (uploadMode.value === 'global-import') batchPreview.value = await previewLogisticsGlobalImports(files)
      else {
        if (!selectedProvider.value) throw new Error('请先选择物流商')
        batchPreview.value = await previewLogisticsProviderImports(selectedProvider.value.id, files)
      }
      batchSelectedKeys.value = initialLogisticsBatchSelection(batchPreview.value)
      replaceBatchDrafts.value = false
      showBatchPreview.value = true
    } else {
      const channel = workspace.value.channels.find(item => item.id === uploadChannelId.value)
      if (!channel) throw new Error('请先选择需要更新的渠道')
      await importChannelFile(channel, files[0]!)
    }
  } catch (error) { notify(error instanceof Error ? error.message : 'Excel 导入失败') }
  input.value = ''
}
async function commitBatchImport() {
  if (!batchPreview.value || !selectedBatchItems.value.length) return notify('请至少选择一份可提交文件')
  if (selectedBatchDraftCount.value && !replaceBatchDrafts.value) return notify('存在已有待审稿，请勾选明确终止旧草稿后再提交')
  try {
    const indexes = new Set(selectedBatchItems.value.map(item => item.fileIndex)); const selectedFiles = batchFiles.value.filter((_, index) => indexes.has(index))
    const result = uploadMode.value === 'global-import'
      ? await importLogisticsGlobalFiles(selectedFiles, replaceBatchDrafts.value)
      : await importLogisticsProviderFiles(selectedProvider.value!.id, selectedFiles, replaceBatchDrafts.value)
    showBatchPreview.value = false; await refreshWorkspace(); notify(`已处理 ${result.count} 个渠道版本`)
    if (uploadMode.value !== 'global-import') openBatchReview()
  } catch (error) { notify(error instanceof Error ? error.message : '批量导入失败，未写入任何文件') }
}
function openBatchReview() {
  if (!selectedProviderDrafts.value.length) return notify('当前物流商没有待审核版本')
  batchSelectedVersionIds.value = initialLogisticsVersionPublishSelection(selectedProviderDrafts.value.map(item => item.version))
  batchReviewedVersionIds.value = []; batchAuditNote.value = ''; showBatchReview.value = true
}
function batchNeedsReview(version: LogisticsChannelVersionRecord) { return logisticsVersionNeedsRiskReview(version) }
async function publishBatchVersions() {
  if (!selectedProvider.value) return
  const publishableIds = new Set(filterPublishableLogisticsVersionIds(selectedProviderDrafts.value.map(item => item.version), batchSelectedVersionIds.value))
  batchSelectedVersionIds.value = [...publishableIds]
  const selected = selectedProviderDrafts.value.filter(item => publishableIds.has(item.version.id))
  if (!selected.length) return notify(blockedProviderDrafts.value.length ? '当前没有可发布版本，请先修正阻断文件' : '请选择至少一个待审核版本')
  if (!batchAuditNote.value.trim()) return notify('请填写批量审核备注')
  const unreviewed = selected.filter(item => batchNeedsReview(item.version) && !batchReviewedVersionIds.value.includes(item.version.id))
  if (unreviewed.length) return notify(`仍有 ${unreviewed.length} 个风险版本未确认`)
  try {
    await publishLogisticsProviderVersions(selectedProvider.value.id, selected.map(item => ({ channelId: item.channel.id, versionId: item.version.id, removalConfirmed: item.version.summary.removed > 0, reviewConfirmed: !batchNeedsReview(item.version) || batchReviewedVersionIds.value.includes(item.version.id) })), batchAuditNote.value)
    showBatchReview.value = false; await refreshWorkspace(); notify(`已批量发布 ${selected.length} 个正式版本`)
  } catch (error) { notify(error instanceof Error ? error.message : '批量发布失败，所有渠道仍保持原正式版本') }
}
function handleTemplateDrop(event: DragEvent) {
  if (!selectedProvider.value) return
  const files = Array.from(event.dataTransfer?.files ?? []).filter(file => /\.xlsx$/i.test(file.name))
  if (!files.length) return notify('请拖入 Excel 模板文件')
  const transfer = new DataTransfer(); files.forEach(file => transfer.items.add(file))
  if (templateInput.value) { templateInput.value.files = transfer.files; uploadMode.value = 'batch-import'; void handleTemplateUpload({ target: templateInput.value } as unknown as Event) }
}
async function saveProvider() {
  try {
    const provider = await addLogisticsProvider(providerForm.name, providerForm.code)
    await refreshWorkspace(); selectedProviderId.value = provider.id
    Object.assign(providerForm, { name: '', code: '' }); showProviderEditor.value = false; notify('物流商已添加，财务税务属性待设置')
  } catch (error) { notify(error instanceof Error ? error.message : '新增物流商失败') }
}
function openArchive(channel: LogisticsChannelRecord) { archivingChannel.value = channel; archiveReason.value = ''; }
async function confirmArchive() {
  if (!archivingChannel.value || !archiveReason.value.trim()) return notify('请填写归档原因')
  try { await archiveLogisticsChannel(archivingChannel.value, archiveReason.value); archivingChannel.value = null; await refreshWorkspace(); notify('渠道已归档，正式规则和报价已立即移除') }
  catch (error) { notify(error instanceof Error ? error.message : '渠道归档失败') }
}
async function toggleProviderStatus() {
  const provider = selectedProvider.value
  if (!provider) return
  const next = !provider.enabled
  const message = next ? `确认重新启用物流商“${provider.name}”吗？` : `确认停用物流商“${provider.name}”吗？旗下渠道会保留，但立即退出业务报价。`
  if (!window.confirm(message)) return
  try { await setLogisticsProviderStatus(provider, next); await refreshWorkspace(); notify(next ? '物流商已重新启用' : '物流商已停用，历史数据仍保留') }
  catch (error) { notify(error instanceof Error ? error.message : '物流商状态修改失败') }
}
async function removeProvider() {
  const provider = selectedProvider.value
  if (!provider || providerChannelCount(provider) > 0) return notify('物流商已有渠道，只能停用，不能删除')
  if (!window.confirm(`确认永久删除空物流商“${provider.name}”吗？此操作不可撤销。`)) return
  try { await deleteLogisticsProvider(provider.id); selectedProviderId.value = ''; await refreshWorkspace(); notify('空物流商已删除') }
  catch (error) { notify(error instanceof Error ? error.message : '物流商删除失败') }
}
async function toggleChannelStatus(channel: LogisticsChannelRecord) {
  const next = !channel.enabled
  const message = next ? `确认重新启用渠道“${channel.name}”吗？` : `确认停用渠道“${channel.name}”吗？正式版本和历史会保留，但立即退出业务报价。`
  if (!window.confirm(message)) return
  try { await setLogisticsChannelStatus(channel, next); await refreshWorkspace(); notify(next ? '渠道已重新启用' : '渠道已停用，版本历史仍保留') }
  catch (error) { notify(error instanceof Error ? error.message : '渠道状态修改失败') }
}
async function removeChannel(channel: LogisticsChannelRecord) {
  if (channelVersionCount(channel) > 0) return notify('渠道已有版本记录，只能停用，不能删除')
  if (!window.confirm(`确认永久删除空渠道“${channel.name}”吗？此操作不可撤销。`)) return
  try { await deleteLogisticsChannel(channel.id); await refreshWorkspace(); notify('空渠道已删除') }
  catch (error) { notify(error instanceof Error ? error.message : '渠道删除失败') }
}
async function saveChannel() {
  if (!selectedProvider.value) return
  try {
    const channel = await addLogisticsChannel({ providerId: selectedProvider.value.id, ...channelForm })
    await refreshWorkspace(); Object.assign(channelForm, { name: '', code: '', type: '专线', logisticsAttribute: '普货' }); showChannelEditor.value = false
    notify('渠道已创建，请上传首个价格版本'); triggerTemplateUpload(channel.id)
  } catch (error) { notify(error instanceof Error ? error.message : '新增渠道失败') }
}
async function openDraftReview(channel: LogisticsChannelRecord) {
  const version = draftVersion(channel); if (!version) return
  try {
    const detail = await loadLogisticsVersionDetail(version)
    Object.assign(version, detail)
    importPreview.value = { fileName: detail.fileName, sourceHash: detail.sourceHash, rows: detail.rows, issues: detail.issues, validRows: detail.rows.length, errors: detail.issues.filter(item => item.level === 'error').length, warnings: detail.issues.filter(item => item.level === 'warning').length, diffRows: detail.diffRows, summary: detail.summary }
    reviewingChannel.value = channel; reviewingVersion.value = detail; auditNote.value = detail.auditNote; removalConfirmed.value = false; initializeReviewWorkspace(importPreview.value); showImportReview.value = true
  } catch (error) { notify(error instanceof Error ? error.message : '版本明细加载失败') }
}
async function publishReviewedVersion() {
  if (!reviewingChannel.value || !reviewingVersion.value) return
  if (!allRequiredReviewed.value) return notify(`请先核对剩余 ${remainingRequiredReviewCount.value} 项异常变更`)
  if (!auditNote.value.trim()) return notify('请填写审核备注后再发布')
  if (reviewingVersion.value.summary.removed && !removalConfirmed.value) return notify('请先确认本次移除的国家或价格段')
  try { await publishLogisticsVersion(reviewingChannel.value.id, reviewingVersion.value.id, auditNote.value, removalConfirmed.value); showImportReview.value = false; await refreshWorkspace(); notify('价格版本已发布，业务报价已切换到新版本') }
  catch (error) { notify(error instanceof Error ? error.message : '发布失败，仍继续使用旧版本') }
}
function openHistory(channel: LogisticsChannelRecord) { historyChannel.value = channel; showVersionHistory.value = true }
function downloadVersion(version: LogisticsChannelVersionRecord) {
  if (!version.originalFile) return notify('系统内置 legacy-v1 没有原始 Excel 文件')
  const link = document.createElement('a'); link.href = URL.createObjectURL(version.originalFile); link.download = version.fileName; link.click(); URL.revokeObjectURL(link.href)
}
function rollbackVersion(version: LogisticsChannelVersionRecord) {
  pendingHistoryAction.value = { type: 'rollback', version }
  historyActionNote.value = `回滚至V${version.versionNumber}`
}
function terminateDraft(version: LogisticsChannelVersionRecord) {
  pendingHistoryAction.value = { type: 'terminate', version }
  historyActionNote.value = '测试草稿终止，不参与正式报价'
}
async function confirmHistoryAction() {
  if (!historyChannel.value || !pendingHistoryAction.value) return
  const action = pendingHistoryAction.value
  const note = historyActionNote.value.trim()
  if (!note) return notify(action.type === 'terminate' ? '请填写终止原因' : '请填写回滚备注')
  try {
    if (action.type === 'terminate') await rejectLogisticsVersion(historyChannel.value.id, action.version.id, note)
    else await rollbackLogisticsVersion(historyChannel.value.id, action.version.id, note)
    pendingHistoryAction.value = null; await refreshWorkspace()
    notify(action.type === 'terminate' ? '待审草稿已终止，历史记录已保留' : '回滚成功，报价已恢复到目标版本')
  } catch (error) { notify(error instanceof Error ? error.message : action.type === 'terminate' ? '终止草稿失败' : '回滚失败') }
}
onMounted(() => { void refreshWorkspace() })
function gramsFromKg(value: number) { return Math.ceil(Math.max(0, Number(value) || 0) * 1000) }

function draftForRule(rule: LogisticsRule) {
  const channel = workspace.value.channels.find(item => item.ruleId === rule.id)
  return channel ? draftVersion(channel) : undefined
}
function draftHasDirection(version: LogisticsChannelVersionRecord | undefined, direction: 'up' | 'down') {
  return Boolean(version?.diffRows.some(diff => diff.changes.some(change => change.price && Number(change.after) !== Number(change.before) && (direction === 'up' ? Number(change.after) > Number(change.before) : Number(change.after) < Number(change.before)))))
}
const filtered = computed(() => rules.value.filter(rule => {
  const searchValue = searchMode.value === 'name'
    ? `${rule.name} ${rule.relations[0]?.carrier || ''} ${rule.relations[0]?.channel || ''}`
    : rule.relations[0]?.channelCode || ''
  const draft = draftForRule(rule)
  const changeMatches = !changeFilter.value
    || (changeFilter.value === 'pending' && Boolean(draft))
    || (changeFilter.value === 'risk' && Boolean(draft?.summary.highRisk))
    || (changeFilter.value === 'up' && draftHasDirection(draft, 'up'))
    || (changeFilter.value === 'down' && draftHasDirection(draft, 'down'))
  return changeMatches && (!typeFilter.value || rule.type.includes(typeFilter.value)) && (!statusFilter.value || rule.status === statusFilter.value) && (!keyword.value.trim() || searchValue.toLowerCase().includes(keyword.value.trim().toLowerCase()))
}))
const pages = computed(() => Math.max(1, Math.ceil(filtered.value.length / pageSize.value)))
const visibleRules = computed(() => filtered.value.slice((page.value - 1) * pageSize.value, page.value * pageSize.value))
const areaRows = computed(() => activeRule.value?.prices ?? [])
const visibleAreaRows = computed(() => areaRows.value.map((area, index) => ({ area, index })).filter(({ area }) => !areaKeyword.value.trim() || `${area.areaName} ${area.countryCode}`.toLowerCase().includes(areaKeyword.value.trim().toLowerCase())))
function selectRulesTab(tab: LogisticsRuleTab) { activeRulesTab.value = tab }

function openBlockedDrafts(providerId = '') {
  const first = channelRows.value.find(item => item.blockedErrors > 0 && (!providerId || item.channel.providerId === providerId))
  showBlockedOnly.value = true
  showArchivedChannels.value = false
  if (first) selectedProviderId.value = first.channel.providerId
  workspaceMode.value = 'base'
}

function clearBlockedFilter() {
  showBlockedOnly.value = false
  if (!workspace.value.providers.some(item => item.id === selectedProviderId.value)) selectedProviderId.value = workspace.value.providers[0]?.id || ''
}

function notify(message: string) { toast.value = message; window.setTimeout(() => toast.value === message && (toast.value = ''), 2200) }
async function hydrateRulePrices(rule: LogisticsRule) {
  const channel = workspace.value.channels.find(item => item.ruleId === rule.id)
  const version = channel ? currentVersion(channel) : undefined
  if (!channel || !version) return
  if (rule.prices.length && rule.prices.length === rule.priceRowCount) return
  const rows = await loadCurrentVersionRows(workspace.value, channel)
  rule.prices = rows.map(normalizeLogisticsPriceRow)
  rule.priceRowCount = rows.length
  rule.areaCount = new Set(rows.map(row => row.countryCode || row.areaName)).size
  rule.phoneRequired = rows.some(row => row.phoneRequired)
}
async function openRuleDetails(rule: LogisticsRule) { try { await hydrateRulePrices(rule); activeRule.value = rule; view.value = 'details' } catch (error) { notify(error instanceof Error ? error.message : '规则详情加载失败') } }
function exportRules() { const data = rules.value; const link = document.createElement('a'); link.href = URL.createObjectURL(new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' })); link.download = '正式运费规则.json'; link.click(); URL.revokeObjectURL(link.href); notify(`已导出 ${data.length} 条正式规则`) }
function exportAreas() { if (!activeRule.value) return; const link = document.createElement('a'); link.href = URL.createObjectURL(new Blob([JSON.stringify(activeRule.value.prices, null, 2)], { type: 'application/json' })); link.download = `${activeRule.value.englishName || activeRule.value.id}-areas.json`; link.click(); URL.revokeObjectURL(link.href); notify(`已导出 ${activeRule.value.prices.length} 条区域规则`) }
</script>

<template>
  <div class="erp">
    <AppTopbar />
    <section class="workspace">
      <div v-if="workspaceLoading" class="workspace-loading"><i></i><span><b>正在加载物流工作区</b><small>先读取物流商、渠道和版本摘要，价格段将在打开明细时加载</small></span></div>
      <div v-else-if="workspaceError" class="workspace-loading error"><span><b>物流工作区加载失败</b><small>{{ workspaceError }}</small></span><button @click="refreshWorkspace">重新加载</button></div>
      <template v-if="view === 'list'">
        <div class="milano-heading"><div><p>LOGISTICS CONFIGURATION</p><h1>物流规则</h1><span>维护物流渠道、国家区域、重量限制与分段运费，供米莱诺报价计算直接调用。</span></div></div>
        <section class="workspace-switch"><button :class="{ active: workspaceMode === 'base' }" @click="workspaceMode='base'">基础资料设置</button><button :class="{ active: workspaceMode === 'rules' }" @click="workspaceMode='rules'">运费规则列表</button></section>
        <template v-if="workspaceMode === 'rules'">
          <section class="published-summary" aria-label="物流渠道版本统计">
            <span><b>{{ workspaceSummary.channels }}</b> 个渠道</span><i>/</i>
            <span><b>{{ workspaceSummary.published }}</b> 个已发布</span><i>/</i>
            <button type="button" @click="openBlockedDrafts()"><b>{{ workspaceSummary.blockedDrafts }}</b> 个阻断草稿</button>
          </section>
          <nav class="published-tabs" aria-label="物流正式数据分类"><button v-for="tab in logisticsRuleTabs" :key="tab" :class="{ active:activeRulesTab===tab }" @click="selectRulesTab(tab)">{{ tab }}</button></nav>

          <section v-if="activeRulesTab==='物流商'" class="published-panel provider-summary-grid">
            <article v-for="provider in providerRows" :key="provider.id"><i>{{ provider.name.slice(0,1) }}</i><div><b>{{ provider.name }}</b><small>{{ provider.code }}</small></div><span>{{ provider.channels }} 个渠道<br>{{ provider.published }} 个已发布<em v-if="provider.blockedDrafts"> · {{ provider.blockedDrafts }} 个阻断</em></span></article>
            <div v-if="!providerRows.length" class="modern-empty">暂无物流商数据</div>
          </section>

          <section v-else-if="activeRulesTab==='物流渠道'" class="published-panel">
            <div class="modern-table-scroll"><table class="published-data-table channel-table"><thead><tr><th>物流商</th><th>渠道</th><th>渠道编码</th><th>物流属性</th><th>正式版本</th><th>草稿状态</th><th>状态</th></tr></thead><tbody><tr v-for="item in channelRows" :key="item.channel.id"><td>{{ item.providerName }}</td><td><b>{{ item.channel.name }}</b></td><td><code class="channel-code" :title="item.channel.code">{{ item.channel.code }}</code></td><td>{{ item.channel.logisticsAttribute }}</td><td><span v-if="item.published" class="status-pill success">V{{ item.published.versionNumber }} · 已发布</span><span v-else>尚未发布</span></td><td><button v-if="item.blockedErrors" class="blocked-link" @click="openBlockedDrafts(item.channel.providerId)">{{ item.blockedErrors }} 个阻断错误</button><span v-else-if="item.draft">V{{ item.draft.versionNumber }} 待审核</span><span v-else>无草稿</span></td><td><span class="status-pill" :class="item.channel.enabled&&item.providerEnabled?'success':'disabled'">{{ item.channel.enabled&&item.providerEnabled?'启用':'禁用' }}</span></td></tr></tbody></table></div>
          </section>

          <section v-else-if="activeRulesTab==='运费规则'" class="rule-workspace-card">
            <header class="rule-card-head"><div><h2>运费规则列表</h2><p>只读展示渠道已审核发布的正式版本；价格和版本请到基础资料中的对应渠道维护。</p></div><div class="header-actions"><button class="secondary-button" @click="exportRules">导出正式规则</button></div></header>
            <div class="modern-filters">
              <label class="keyword-search"><span>⌕</span><input v-model="keyword" :placeholder="searchMode==='name'?'搜索规则、物流商或渠道':'搜索渠道编码'" @keyup.enter="page=1"></label>
              <div class="search-mode"><button :class="{ active:searchMode==='name' }" @click="searchMode='name';page=1">名称</button><button :class="{ active:searchMode==='code' }" @click="searchMode='code';page=1">渠道编码</button></div>
              <select v-model="typeFilter" @change="page=1"><option value="">全部类型</option><option>专线</option><option>挂号</option><option>free</option></select>
              <select v-model="statusFilter" @change="page=1"><option value="">全部启用状态</option><option>启用</option><option>禁用</option></select>
              <div class="change-chips"><button :class="{ active:changeFilter==='pending' }" @click="changeFilter=changeFilter==='pending'?'':'pending';page=1">有新版本待审核</button><button :class="{ active:changeFilter==='up' }" @click="changeFilter=changeFilter==='up'?'':'up';page=1">价格上涨</button><button :class="{ active:changeFilter==='down' }" @click="changeFilter=changeFilter==='down'?'':'down';page=1">价格下降</button><button :class="{ active:changeFilter==='risk' }" @click="changeFilter=changeFilter==='risk'?'':'risk';page=1">大幅调价</button></div>
              <button class="reset-button" @click="typeFilter='';statusFilter='';changeFilter='';keyword='';page=1">重置</button>
              <span class="filter-result">共 {{ filtered.length }} 条</span>
            </div>
            <div class="modern-table-scroll"><table class="modern-rule-table"><colgroup><col class="col-rule"><col class="col-provider"><col class="col-channel"><col class="col-code"><col class="col-count"><col class="col-count"><col class="col-version"><col class="col-status"><col class="col-action"></colgroup><thead><tr><th>规则名称</th><th>物流商</th><th>渠道</th><th>渠道编码</th><th>区域数</th><th>价格段</th><th>版本</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-for="rule in visibleRules" :key="rule.id"><td class="rule-name-cell"><b>{{ rule.name }}</b><small>{{ rule.type }}</small></td><td>{{ rule.relations[0]?.carrier || '—' }}</td><td>{{ rule.relations[0]?.channel || '—' }}</td><td><code class="channel-code" :title="rule.relations[0]?.channelCode || '—'">{{ rule.relations[0]?.channelCode || '—' }}</code></td><td>{{ rule.areaCount }}</td><td>{{ rule.priceRowCount }}</td><td><span class="status-pill success">{{ rule.published }}</span></td><td><span class="status-pill" :class="rule.status==='启用'?'success':'disabled'">{{ rule.status }}</span></td><td class="modern-ops"><button class="area-button" @click="openRuleDetails(rule)">查看详情</button></td></tr><tr v-if="!visibleRules.length"><td colspan="9" class="modern-empty">当前没有已审核发布的正式运费规则</td></tr></tbody></table></div>
            <footer class="modern-pagination"><span>共 {{ filtered.length }} 条正式规则</span><label>每页<select v-model.number="pageSize" @change="page=1"><option>10</option><option>20</option><option>50</option><option>100</option></select>条</label><button :disabled="page===1" @click="page=Math.max(1,page-1)">上一页</button><b>{{ page }} / {{ pages }}</b><button :disabled="page===pages" @click="page=Math.min(pages,page+1)">下一页</button></footer>
          </section>

          <section v-else-if="activeRulesTab==='国家区域'" class="published-panel country-summary-grid">
            <article v-for="country in publishedCountries" :key="country.code||country.name"><b>{{ country.name }}</b><code>{{ country.code || '—' }}</code><small>正式版本可用区域</small></article>
            <div v-if="!publishedCountries.length" class="modern-empty">正式版本暂无国家区域</div>
          </section>

        </template>
        <section v-else class="base-settings">
          <div class="base-toolbar"><div><h2>物流商与渠道版本</h2><p>完整 Excel 快照先进入草稿审核；只有发布后的价格版本才参与业务报价。</p></div><div class="provider-toolbar"><label>⌕<input v-model="providerSearch" placeholder="搜索物流商或编码"></label><button v-if="showBlockedOnly" class="blocked-filter-button" @click="clearBlockedFilter">正在查看阻断草稿 ×</button><button class="outline-orange" @click="triggerTemplateUpload('','global-import')">全局批量导入</button><button class="outline-orange" @click="showProviderEditor=true">＋ 新增物流商</button></div></div>
          <input ref="templateInput" class="hidden-file" type="file" multiple accept=".xlsx" @change="handleTemplateUpload">
          <div class="provider-manager">
            <aside class="provider-list"><div class="provider-sort-hint"><span>物流商列表</span><small>正式渠道数据仓库</small></div><div class="provider-list-scroll"><button v-for="provider in filteredProviderSettings" :key="provider.id" :class="{ active:selectedProvider?.id===provider.id }" @click="selectedProviderId=provider.id"><u>⋮⋮</u><i>{{ provider.name.slice(0,1) }}</i><span><b>{{ provider.name }}</b><small>{{ provider.code }}</small></span><em>{{ providerChannelCount(provider) }}个渠道</em><strong>›</strong></button><div v-if="!filteredProviderSettings.length" class="provider-empty">没有匹配的物流商</div></div><footer>共 {{ providerSettings.length }} 家物流商</footer></aside>
            <section v-if="selectedProvider" class="provider-detail"><header><div class="provider-icon">{{ selectedProvider.name.slice(0,1) }}</div><div><h3>{{ selectedProvider.name }} <span>{{ activeProviderChannels.length }}个渠道</span><em v-if="!selectedProvider.enabled">已停用</em></h3><small>物流商编码 {{ selectedProvider.code }} · 渠道、价格和审核的唯一维护入口</small></div><div class="provider-detail-actions"><button class="outline-orange" @click="showChannelEditor=true">＋ 新增渠道</button><button class="outline-orange" @click="triggerTemplateUpload('','batch-import')">批量导入渠道</button><button class="upload" @click="triggerTemplateUpload('','batch-update')">批量更新价格</button><button v-if="selectedProviderDrafts.length" class="upload" @click="openBatchReview">批量审核 可发布 {{ publishableProviderDrafts.length }} / 阻断 {{ blockedProviderDrafts.length }}</button><button class="state-action" @click="showArchivedChannels=!showArchivedChannels">{{ showArchivedChannels?'返回日常渠道':'查看已归档' }}</button><button class="state-action" @click="toggleProviderStatus">{{ selectedProvider.enabled ? '停用物流商' : '重新启用' }}</button><button v-if="providerChannelCount(selectedProvider)===0" class="danger-action" @click="removeProvider">删除物流商</button></div></header><div class="provider-detail-body"><h4>{{ showArchivedChannels?'已归档渠道':'渠道价格与版本' }}</h4><div class="provider-template-table version-table"><div class="template-table-head"><span>渠道名称</span><span>当前正式版本</span><span>数据规模</span><span>{{ showArchivedChannels?'归档信息':'调价状态' }}</span><span>最近发布</span><span>操作</span></div><div v-for="channel in selectedProviderChannels" :key="channel.id" class="template-table-row"><b>{{ channel.name }}<small>{{ channel.code }} · {{ channel.logisticsAttribute }}<em v-if="!channel.enabled"> · 已停用</em></small></b><div><strong>{{ currentVersion(channel) ? `V${currentVersion(channel)!.versionNumber}` : '尚未发布' }}</strong><small>{{ visibleVersion(channel)?.fileName || '等待首个价格文件' }}</small></div><div>{{ countryCount(visibleVersion(channel)) }}国 / {{ visibleVersion(channel)?.rowCount || 0 }}价格段</div><span v-if="channel.archived" class="pending">{{ channel.archiveReason }} · {{ channel.archivedBy }} · {{ channel.archivedAt }}</span><span v-else :class="{ pending:draftVersion(channel) }">{{ draftVersion(channel) ? `V${draftVersion(channel)!.versionNumber} 待审核` : '无待审版本' }}</span><time>{{ currentVersion(channel)?.publishedAt || '—' }}</time><div class="template-actions"><button v-if="!channel.archived" @click="triggerTemplateUpload(channel.id)">{{ currentVersion(channel) ? '更新价格' : '上传价格' }}</button><button v-if="!channel.archived&&draftVersion(channel)" class="move-template" @click="openDraftReview(channel)">审核发布</button><button @click="openHistory(channel)">版本历史</button><button v-if="!channel.archived" class="state-action" @click="toggleChannelStatus(channel)">{{ channel.enabled ? '停用' : '启用' }}</button><button v-if="!channel.archived&&channelVersionCount(channel)===0" class="danger-action" @click="removeChannel(channel)">删除</button><button v-else-if="!channel.archived" class="danger-action" @click="openArchive(channel)">归档</button></div></div><div v-if="!selectedProviderChannels.length" class="template-table-empty">{{ showArchivedChannels?'当前物流商没有已归档渠道':'当前物流商尚未创建渠道' }}</div></div><button v-if="!showArchivedChannels" class="template-dropzone" @dragover.prevent @drop.prevent="handleTemplateDrop" @click="triggerTemplateUpload('','batch-update')"><i>⇧</i><span>拖拽多个渠道 Excel 到这里，系统将按<strong>渠道编码或名称自动匹配并预检</strong></span></button></div></section>
          </div>
        </section>
      </template>
      <template v-else-if="activeRule"><div class="area-page-head"><button class="back-button" @click="view='list'">‹ 返回运费规则</button><div><p>PUBLISHED RULE DETAIL</p><h1>{{ activeRule.name }}</h1><span>当前正式版本的区域、重量限制与分段价格；修改规则请返回基础资料中的对应渠道上传新版本。</span></div></div><section class="area-workspace-card"><div class="area-toolbar"><label><span>⌕</span><input v-model="areaKeyword" placeholder="搜索区域名称或国家代码"></label><div><button class="secondary-button" @click="exportAreas">导出正式版本</button><button class="secondary-button" @click="view='list';workspaceMode='base'">返回渠道版本</button></div><span>共 {{ visibleAreaRows.length }} 条价格段</span></div><div class="modern-table-scroll"><table class="modern-area-table rule-detail-table"><thead><tr><th v-for="column in logisticsRuleDetailColumns" :key="column">{{ column }}</th></tr></thead><tbody><tr v-for="entry in visibleAreaRows" :key="entry.index"><td class="rule-name-cell"><b>{{ entry.area.areaName }}</b><small>{{ entry.area.countryCode || '暂无国家代码' }}</small></td><td><b>{{ gramsFromKg(entry.area.weightFromKg) }}～{{ gramsFromKg(entry.area.weightToKg) }} g</b></td><td>{{ entry.area.volumeDivisor || '—' }}</td><td>{{ entry.area.maxSideCm ? `${entry.area.maxSideCm} cm` : '—' }}</td><td>{{ entry.area.maxPerimeterCm ? `${entry.area.maxPerimeterCm} cm` : '—' }}</td><td class="limit-cell"><span>{{ entry.area.prohibitGeneralCargo?'禁止普货':'允许普货' }}</span><small>禁运：{{ entry.area.prohibitedMarks || '无' }} · 允许：{{ entry.area.allowedMarks || '全部' }}</small></td><td class="price-cell"><b>¥{{ entry.area.pricePerKg }}</b></td><td>¥{{ entry.area.registrationFee }}</td><td>{{ entry.area.etaMinDays }}～{{ entry.area.etaMaxDays }} 天</td><td><span class="status-pill success">正式</span></td></tr><tr v-if="!visibleAreaRows.length"><td colspan="10" class="modern-empty">没有找到符合条件的正式规则价格段</td></tr></tbody></table></div></section></template>
    </section>

    <div v-if="showProviderEditor" class="mask"><div class="modal small base-modal"><header>新增物流商<button @click="showProviderEditor=false">×</button></header><div class="simple-form"><label>物流商名称<input v-model="providerForm.name" placeholder="例如：燕文物流"></label><label>物流商编码<input v-model="providerForm.code" placeholder="例如：YANWEN" @input="providerForm.code=providerForm.code.toUpperCase()"></label></div><footer><button class="primary-orange" @click="saveProvider">保存物流商</button><button @click="showProviderEditor=false">取消</button></footer></div></div>
    <div v-if="showChannelEditor" class="mask"><div class="modal small base-modal"><header><div><small>LOGISTICS CHANNEL</small><b>新增物流渠道</b></div><button @click="showChannelEditor=false">×</button></header><div class="simple-form"><label>渠道名称<input v-model="channelForm.name" placeholder="例如：燕文普货专线"></label><label>渠道编码<input v-model="channelForm.code" placeholder="唯一编码，例如：YW-PH" @input="channelForm.code=channelForm.code.toUpperCase()"></label><label>规则类型<select v-model="channelForm.type"><option>专线</option><option>挂号</option><option>快递</option></select></label><label>物流属性<select v-model="channelForm.logisticsAttribute"><option>普货</option><option>带电</option><option>化妆品</option><option>敏感货</option></select></label></div><footer><button @click="showChannelEditor=false">取消</button><button class="primary-orange" @click="saveChannel">创建并上传</button></footer></div></div>
    <div v-if="showBatchPreview && batchPreview" class="mask"><div class="modal batch-modal"><header><div><small>BATCH IMPORT PREVIEW</small><b>{{ uploadMode==='global-import'?'全局批量导入':selectedProvider?.name }} · {{ batchPreview.count }} 个文件预检</b></div><button @click="showBatchPreview=false">×</button></header><div class="batch-body"><div class="batch-summary"><span>可提交 {{ selectedBatchItems.length }} 份</span><span :class="{ danger:batchPreview.blocking }">阻断 {{ batchPreview.blocking }} 份</span><span>将终止旧草稿 {{ selectedBatchDraftCount }} 份</span></div><div class="batch-list"><article v-for="item in batchPreview.items" :key="item.fileKey" :class="{ blocked:item.errors }"><input v-model="batchSelectedKeys" type="checkbox" :value="item.fileKey" :disabled="Boolean(item.errors)"><span :class="['batch-action',item.action]">{{ item.action==='match'?'匹配':item.action==='create'?'新建':item.action==='restore'?'恢复':'阻断' }}</span><div><b>{{ item.fileName }}</b><small>{{ item.providerName||selectedProvider?.name||item.providerMatchMessage }} · {{ item.channelName||'未匹配渠道' }} · {{ item.validRows }} 行</small><em v-if="item.hasDraft" class="batch-warning">已有待审稿</em><em v-if="item.errors" class="batch-error">解析错误 {{ item.errors }} 个</em></div><div class="history-summary"><span>新增 {{ item.summary.added }}</span><span>调价 {{ item.summary.price }}</span><span>移除 {{ item.summary.removed }}</span><span>风险 {{ item.summary.highRisk }}</span></div><details v-if="item.errors" open class="batch-issues"><summary>阻断原因</summary><p v-for="(issue,index) in item.issues" :key="index">Excel 第 {{ issue.row||'—' }} 行 · {{ issue.field }}：{{ issue.message }}</p></details><em v-else class="batch-ok">可提交</em></article></div><label v-if="selectedBatchDraftCount" class="removal-confirm"><input v-model="replaceBatchDrafts" type="checkbox">我确认终止所选渠道的旧待审稿，保留历史后生成下一版本</label></div><footer><span>可提交 {{ selectedBatchItems.length }} 份、阻断 {{ batchPreview.blocking }} 份、将终止旧草稿 {{ selectedBatchDraftCount }} 份</span><button @click="showBatchPreview=false">取消</button><button class="primary-orange" :disabled="!selectedBatchItems.length || Boolean(selectedBatchDraftCount&&!replaceBatchDrafts)" @click="commitBatchImport">确认导入所选文件</button></footer></div></div>
    <div v-if="archivingChannel" class="mask"><div class="modal small base-modal"><header><div><small>CHANNEL ARCHIVE</small><b>归档 {{ archivingChannel.name }}</b></div><button @click="archivingChannel=null">×</button></header><div class="simple-form"><p>归档后渠道会退出日常列表、正式规则和报价；版本、文件、审核及历史关联全部保留。再次导入同渠道时可恢复并继续版本号。</p><label>归档原因<textarea v-model="archiveReason" maxlength="500" placeholder="请填写停用或替换该渠道的业务原因"></textarea></label></div><footer><button @click="archivingChannel=null">取消</button><button class="danger-action" :disabled="!archiveReason.trim()" @click="confirmArchive">确认归档</button></footer></div></div>
    <div v-if="showBatchReview && selectedProvider" class="mask"><div class="modal batch-modal"><header><div><small>PROVIDER REVIEW</small><b>{{ selectedProvider.name }} · 批量审核发布</b></div><button @click="showBatchReview=false">×</button></header><div class="batch-body"><div class="batch-summary"><span>可发布 {{ publishableProviderDrafts.length }} 个</span><span :class="{ danger:blockedProviderDrafts.length }">阻断 {{ blockedProviderDrafts.length }} 个</span><span>已勾选 {{ selectedPublishableVersionIds.length }} 个</span></div><p class="batch-help">阻断版本仅供查看，不能勾选且不会进入本次发布；后端仍会在提交时执行最终校验。</p><div class="batch-list"><article v-for="item in selectedProviderDrafts" :key="item.version.id" :class="{ blocked:logisticsVersionHasBlockingErrors(item.version) }"><input v-model="batchSelectedVersionIds" type="checkbox" :value="item.version.id" :disabled="logisticsVersionHasBlockingErrors(item.version)"><div><b>{{ item.channel.name }} · V{{ item.version.versionNumber }}</b><small>{{ item.version.fileName }} · {{ item.version.rowCount }} 行</small><em v-if="logisticsVersionHasBlockingErrors(item.version)" class="batch-error">阻断错误 {{ item.version.errors }} 个</em></div><div class="history-summary"><span>新增 {{ item.version.summary.added }}</span><span>调价 {{ item.version.summary.price }}</span><span>移除 {{ item.version.summary.removed }}</span><span>风险 {{ item.version.summary.highRisk }}</span></div><details v-if="logisticsVersionHasBlockingErrors(item.version)" open class="batch-issues"><summary>阻断原因</summary><p v-for="(issue,index) in item.version.issues.filter(issue=>issue.level==='error').slice(0,3)" :key="index">Excel 第 {{ issue.row||'—' }} 行 · {{ issue.field }}：{{ issue.message }}</p><p v-if="!item.version.issues.some(issue=>issue.level==='error')">该版本记录了阻断错误，请进入单版本审核查看详情或重新导入修正文件。</p></details><label v-else-if="batchNeedsReview(item.version)" class="batch-review-check"><input v-model="batchReviewedVersionIds" type="checkbox" :value="item.version.id">已核对风险/移除项</label><em v-else class="batch-ok">校验通过</em></article></div><label class="audit-note">批量审核备注<textarea v-model="batchAuditNote" placeholder="填写本批价格来源、调整原因和审核结论"></textarea></label></div><footer><span>可发布 {{ publishableProviderDrafts.length }} 个、阻断 {{ blockedProviderDrafts.length }} 个</span><button @click="showBatchReview=false">暂不发布</button><button class="primary-orange" :disabled="!selectedPublishableVersionIds.length" @click="publishBatchVersions">审核通过并批量发布</button></footer></div></div>
    <div v-if="showImportReview && importPreview && reviewingVersion" class="mask">
      <div class="modal import-review-modal">
        <header>
          <div><small>IMPORT REVIEW</small><b>{{ reviewingChannel?.name }} · V{{ reviewingVersion.versionNumber }} 导入审核</b></div>
          <button aria-label="关闭" @click="showImportReview=false">×</button>
        </header>
        <div class="review-body">
          <div v-if="reviewingChannel && !reviewingChannel.currentVersionId" class="review-first-import"><b>首次导入</b><span>当前渠道尚无正式版本，本次有效价格段全部按“新增”展示。</span></div>
          <div class="review-stats">
            <article><small>有效行</small><b>{{ importPreview.validRows }}</b></article>
            <article><small>已跳过错误 / 警告</small><b>{{ importPreview.errors }} / {{ importPreview.warnings }}</b></article>
            <article class="added"><small>新增</small><b>{{ importPreview.summary.added }}</b></article>
            <article class="changed"><small>调价</small><b>{{ importPreview.summary.price }}</b></article>
            <article class="removed"><small>移除</small><b>{{ importPreview.summary.removed }}</b></article>
            <article class="risk"><small>大幅涨跌</small><b>{{ importPreview.summary.highRisk }}</b></article>
          </div>

          <div v-if="mandatoryReviewDiffs.length" class="review-risk-banner">
            <span><i>!</i>发现 <b>{{ mandatoryReviewDiffs.length }}</b> 项异常变更，发布前必须逐项核对</span>
            <button :class="{ active:reviewOnlyRisk }" @click="toggleRiskReview">{{ reviewOnlyRisk ? '查看全部变更' : '只看异常' }}</button>
          </div>

          <div v-if="reviewChangedDiffs.length" class="review-workspace">
            <aside class="review-navigator">
              <label class="review-country-search"><span>⌕</span><input v-model="reviewCountryKeyword" placeholder="搜索国家或国家代码"></label>
              <div class="review-country-list">
                <section v-for="group in reviewCountryGroups" :key="group.key" class="review-country-group" :class="{ selected:selectedReviewCountryKey===group.key }">
                  <button class="review-country-head" @click="toggleReviewCountry(group.key)">
                    <span><b>{{ group.name }} <em>{{ group.code }}</em></b><small>{{ group.items.length }}个重量段 · {{ group.priceCount }}项调价<template v-if="group.riskCount"> · <strong>{{ group.riskCount }}项异常</strong></template></small></span>
                    <i v-if="group.riskCount" class="country-risk-count">{{ group.riskCount }}</i>
                    <u>{{ expandedReviewCountries.includes(group.key) ? '⌃' : '⌄' }}</u>
                  </button>
                  <div v-if="expandedReviewCountries.includes(group.key)" class="review-weight-list">
                    <button v-for="diff in group.items" :key="diff.key" :class="{ active:selectedReviewDiff?.key===diff.key, reviewed:reviewedDiffKeys.includes(diff.key) }" @click="selectReviewDiff(diff)">
                      <span><b>{{ diff.row.weightFromKg }}～{{ diff.row.weightToKg }} KG</b><small>{{ reviewTypeLabel(diff.type) }} · {{ diff.changes.length || 1 }}项变更</small></span>
                      <em v-if="diff.risk || diff.type==='removed'" class="weight-risk">{{ diff.type==='removed'?'需确认':'高风险' }}</em>
                      <i :class="{ checked:reviewedDiffKeys.includes(diff.key) }">{{ reviewedDiffKeys.includes(diff.key) ? '✓' : '' }}</i>
                    </button>
                  </div>
                </section>
                <div v-if="!reviewCountryGroups.length" class="review-nav-empty">没有匹配的国家或异常变更</div>
              </div>
              <footer class="review-progress-legend"><span><i class="done">✓</i>已核对</span><span><i></i>未核对</span></footer>
            </aside>

            <section v-if="selectedReviewDiff" class="review-detail">
              <div class="review-detail-head">
                <div><h3>{{ selectedReviewDiff.row.areaName }} {{ selectedReviewDiff.row.countryCode }} · {{ selectedReviewDiff.row.weightFromKg }}～{{ selectedReviewDiff.row.weightToKg }} KG</h3><small>Excel 第 {{ selectedReviewDiff.row.sourceRow }} 行</small></div>
                <div class="review-detail-badges">
                  <span :class="selectedReviewDiff.type">{{ reviewTypeLabel(selectedReviewDiff.type) }}</span>
                  <span v-if="selectedReviewDiff.risk || selectedReviewDiff.type==='removed'" class="high-risk">{{ selectedReviewDiff.type==='removed'?'需单独确认':'高风险' }}</span>
                  <span>{{ selectedReviewDiff.changes.length || 1 }}项变化</span>
                  <button :class="{ reviewed:reviewedDiffKeys.includes(selectedReviewDiff.key) }" @click="toggleReviewed(selectedReviewDiff)">{{ reviewedDiffKeys.includes(selectedReviewDiff.key) ? '✓ 已核对' : '标记为已核对' }}</button>
                </div>
              </div>

              <div v-if="reviewPriceChanges.length" class="price-change-cards">
                <article v-for="change in reviewPriceChanges" :key="change.field" :class="['price-change-card',reviewChangeDirection(change),{ risk:selectedReviewDiff.risk }]">
                  <div class="change-icon">¥</div>
                  <div class="change-main"><b>{{ change.field }}</b><div><span><small>原值</small><strong>{{ displayReviewValue(change,change.before) }}</strong></span><i>→</i><span class="after"><small>新值</small><strong>{{ displayReviewValue(change,change.after) }}</strong></span></div></div>
                  <div class="change-result"><b>{{ reviewDelta(change) }}</b><span>{{ reviewPercent(change) }}</span></div>
                </article>
              </div>

              <div v-if="selectedReviewDiff.type==='added' || selectedReviewDiff.type==='removed'" :class="['segment-change-card',selectedReviewDiff.type]">
                <i>{{ selectedReviewDiff.type==='added'?'＋':'−' }}</i>
                <span><b>{{ selectedReviewDiff.type==='added'?'新增完整价格段':'移除完整价格段' }}</b><small>{{ selectedReviewDiff.type==='added'?'发布后该国家与重量段将参与业务报价':'发布后该国家与重量段将不再参与新报价' }}</small></span>
              </div>

              <div v-if="reviewRuleChanges.length" class="rule-change-panel">
                <header><b>其他规则变化（{{ reviewRuleChanges.length }}）</b></header>
                <div v-for="change in reviewRuleChanges" :key="change.field"><b>{{ change.field }}</b><span>{{ displayReviewValue(change,change.before) }}</span><i>→</i><strong>{{ displayReviewValue(change,change.after) }}</strong></div>
              </div>

              <div v-if="!reviewPriceChanges.length && !reviewRuleChanges.length && selectedReviewDiff.type!=='added' && selectedReviewDiff.type!=='removed'" class="review-detail-empty">当前价格段没有可展示的字段变化</div>
              <footer class="review-detail-progress"><span>已核对 <b>{{ reviewedCount }}</b> / {{ reviewChangedDiffs.length }}</span><em v-if="remainingRequiredReviewCount">仍有 {{ remainingRequiredReviewCount }} 项异常必须核对</em><em v-else class="complete">必核对项已完成</em></footer>
            </section>
            <div v-else class="review-detail review-detail-empty">请选择左侧国家与重量段查看变化</div>
          </div>
          <div v-else class="review-no-change">文件与当前正式版本没有差异</div>

          <div v-if="importPreview.issues.length" class="issue-list"><b>校验提示（错误行已跳过，不会进入草稿）</b><span v-for="issue in importPreview.issues.slice(0,12)" :key="`${issue.row}-${issue.field}-${issue.message}`" :class="issue.level">第{{ issue.row }}行 · {{ issue.field }}：{{ issue.message }}</span><small v-if="importPreview.issues.length>12">另有 {{ importPreview.issues.length-12 }} 条提示</small></div>
          <label class="audit-note">审核备注<textarea v-model="auditNote" placeholder="填写本次更新原因、审核结论或需要追溯的信息"></textarea></label>
          <label v-if="importPreview.summary.removed" class="removal-confirm"><input v-model="removalConfirmed" type="checkbox">我已逐项确认：本次将从正式报价中移除 {{ importPreview.summary.removed }} 个国家或价格段</label>
        </div>
        <footer class="review-modal-footer">
          <span v-if="remainingRequiredReviewCount">请先核对剩余 {{ remainingRequiredReviewCount }} 项异常变更</span>
          <span v-else class="ready">异常变更已核对，可以填写审核备注并发布</span>
          <button @click="showImportReview=false">暂不发布</button>
          <button class="primary-orange" :disabled="!allRequiredReviewed" @click="publishReviewedVersion">审核通过并立即发布</button>
        </footer>
      </div>
    </div>
    <div v-if="showVersionHistory && historyChannel" class="mask"><div class="modal history-modal"><header><div><small>VERSION HISTORY</small><b>{{ historyChannel.name }} · 版本历史</b></div><button @click="showVersionHistory=false">×</button></header><div class="history-list"><article v-for="version in historyVersions" :key="version.id"><span :class="['version-status',version.status]">{{ version.status==='published'?'当前正式':version.status==='draft'?'待审核':version.status==='superseded'?'历史版本':'已终止' }}</span><div><b>V{{ version.versionNumber }} · {{ version.fileName }}</b><small>导入 {{ version.importedAt }} / {{ version.importedBy }}<template v-if="version.publishedAt"> · 发布 {{ version.publishedAt }} / {{ version.publishedBy }}</template></small><p>{{ version.auditNote || '暂无审核备注' }}</p></div><div class="history-summary"><span>新增 {{ version.summary.added }}</span><span>调价 {{ version.summary.price }}</span><span>移除 {{ version.summary.removed }}</span><span>规则 {{ version.summary.rule }}</span></div><div class="history-actions"><button @click="downloadVersion(version)">下载原文件</button><button v-if="version.status==='draft'" class="danger" @click="terminateDraft(version)">终止草稿</button><button v-else-if="historyChannel.currentVersionId!==version.id" class="rollback" @click="rollbackVersion(version)">回滚到此版</button></div></article></div><footer><button @click="showVersionHistory=false">关闭</button></footer></div></div>
    <div v-if="pendingHistoryAction && historyChannel" class="mask history-action-mask"><div class="modal small history-action-modal"><header><b>{{ pendingHistoryAction.type==='terminate' ? '终止待审草稿' : '回滚正式价格版本' }}</b><button @click="pendingHistoryAction=null">×</button></header><div><p v-if="pendingHistoryAction.type==='terminate'">确认终止 <strong>{{ historyChannel.name }} V{{ pendingHistoryAction.version.versionNumber }}</strong> 吗？草稿不会参与报价，文件和审计记录仍会保留。</p><p v-else>确认将 <strong>{{ historyChannel.name }}</strong> 回滚到 V{{ pendingHistoryAction.version.versionNumber }} 吗？系统会生成新的连续正式版本，不改写历史版本号。</p><label>{{ pendingHistoryAction.type==='terminate' ? '终止原因' : '回滚备注' }}<textarea v-model="historyActionNote" rows="3"></textarea></label></div><footer><button @click="pendingHistoryAction=null">取消</button><button :class="pendingHistoryAction.type==='terminate'?'danger-confirm':'primary-orange'" @click="confirmHistoryAction">{{ pendingHistoryAction.type==='terminate' ? '确认终止' : '确认回滚并发布' }}</button></footer></div></div>
    <div v-if="toast" class="toast">{{ toast }}</div>
  </div>
</template>

<style scoped>
:global(body){margin:0}.erp{--cyan:#0699bd;--blue:#2e91c5;--line:#d7d7d7;min-height:100vh;background:#fff;color:#555;font:13px Arial,"Microsoft YaHei",sans-serif}.top{height:42px;display:flex;align-items:stretch;background:#0495b8;color:#fff;position:sticky;top:0;z-index:30}.logo{width:175px;display:grid;place-items:center;background:#078eae;font-weight:bold}.top nav{display:flex}.top nav a{min-width:58px;padding:0 10px;display:grid;place-items:center;border-left:1px solid #0788aa;font-weight:bold}.top nav a.active{background:#087f9d;border-bottom:3px solid #ffe31b}.tools{margin-left:auto;display:flex;align-items:center;padding:0 15px;white-space:nowrap}.sidebar{position:fixed;left:0;top:42px;bottom:0;width:177px;border-right:1px solid #ccc;background:#fafafa;overflow:auto}.sidebar h3{margin:0;padding:11px 8px;background:#eee;border-bottom:1px solid #d3d3d3;font-size:13px}.sidebar button{width:100%;height:34px;border:0;border-bottom:1px solid #e6e6e6;background:#fff;text-align:left;padding-left:15px;color:#666}.sidebar button i{font-size:8px;color:#bbb;margin-right:7px}.sidebar button.active{background:#079abe;color:#fff;font-weight:bold}.sidebar button.active i{color:#fff}.workspace{margin-left:177px;padding:0 21px 50px;min-width:900px}.crumb{height:31px;display:flex;align-items:center;background:#f1f1f1;margin:0 -21px 10px;padding:0 12px}.tab-title{display:inline-block;background:#069bc0;color:#fff;padding:9px 18px;border-radius:4px 4px 0 0;font-weight:bold}.cyan-line{height:3px;background:#079cbe}.tip{margin:6px 0 20px;padding:14px 8px;background:#d9f1f9;border:1px solid #c5e6f1}.filters{border:1px solid var(--line);padding:8px 20px 5px}.filters>div{display:flex;align-items:center;min-height:34px}.filters label{min-width:82px;font-weight:bold}.filters select{width:145px;height:30px;margin-right:36px;border:1px solid #ccc;border-radius:4px;background:linear-gradient(#fff,#eee);padding:0 10px}.filters input{width:295px;height:28px;border:1px solid #ccc;padding:0 10px}.filters button{height:27px;border:0;background:#fff}.filters button.chosen{background:#3b9bc6;color:#fff}.search{background:#338fc0!important;color:#fff!important;border:0!important;padding:0 15px}.data-card{margin-top:14px;border:1px solid var(--line)}.actions{height:49px;display:flex;align-items:center;padding:0 10px;border-bottom:1px solid var(--line)}button{cursor:pointer}.actions button,.area-actions button{height:30px;border:0;border-radius:3px;color:#fff;margin-right:4px;padding:0 11px;font-weight:bold}.green{background:#4fbd65!important}.blue{background:#43acd1!important;color:#fff;border:0}.yellow{background:#e6b84e!important}.teal{background:#42b8c5!important}.actions>div{margin-left:auto}.actions span{background:#3f93c5;color:#fff;padding:7px}.scroll{overflow:auto;max-height:610px}table{width:100%;border-collapse:collapse;table-layout:fixed}th{background:#eee;padding:11px 6px;border:1px solid #d5d5d5;color:#555}td{padding:8px 7px;border:1px solid #ddd;text-align:center;line-height:20px;word-break:break-word}th:first-child,td:first-child{width:20px}.type{display:inline-block;padding:2px 8px;background:#50b969;color:#fff;border-radius:3px}.unpublished{color:#b66060}.ops a{color:#4287aa;margin:0 2px;cursor:pointer}.ops{width:170px}.marks{font-size:11px}td small{display:block;color:#999}footer{display:flex;justify-content:flex-end;align-items:center;padding:8px;background:#fafafa}footer button{height:31px;border:1px solid #ddd;background:#fff;color:#4589aa}footer button.current{background:#2695be;color:#fff}footer span{margin-left:10px}footer select{height:27px}.area-head{display:flex;align-items:center;gap:15px;padding:15px 0}.area-head button{height:30px;border:1px solid #ccc;background:#fff}.area-actions{display:flex;align-items:center;padding:10px;border:1px solid #ddd}.area-actions input{height:28px;width:220px;border:1px solid #ccc;padding:0 8px;margin-right:4px}.area-actions button:not(.green):not(.blue):not(.search){background:#888}.mask{position:fixed;inset:0;z-index:80;display:grid;place-items:center;background:#0007}.modal{width:780px;background:#fff;border-radius:4px;box-shadow:0 10px 40px #0005}.modal.small{width:520px}.modal.area-modal{width:760px}.modal header{height:42px;display:flex;align-items:center;padding:0 15px;background:#f1f1f1;border-bottom:1px solid #ccc;font-weight:bold}.modal header button{margin-left:auto;border:0;background:none;font-size:22px}.form{display:grid;grid-template-columns:1fr 1fr;gap:14px;padding:22px}.form label{display:grid;grid-template-columns:120px 1fr;align-items:center}.form input,.form select{height:32px;border:1px solid #ccc;padding:0 8px}.modal footer{gap:6px;padding:12px 20px;border-top:1px solid #ddd}.modal footer button{padding:0 18px}.check{display:block;padding:35px}.toast{position:fixed;right:25px;bottom:25px;z-index:100;background:#333;color:#fff;padding:12px 20px;border-radius:4px}@media(max-width:1150px){.top nav a:nth-child(n+10){display:none}}
.ops button{border:0;background:transparent;color:#4287aa;margin:0 2px;padding:0;cursor:pointer;font:inherit}
.erp{min-height:100vh;background:#f4f6f8;color:#17232e;font-family:Inter,"PingFang SC","Microsoft YaHei",sans-serif}
.milano-topbar{height:68px;display:flex;align-items:center;padding:0 4vw;background:#fff;border-bottom:1px solid #e3e8ec;position:sticky;top:0;z-index:30}
.milano-brand{display:flex;align-items:center;gap:11px;margin-right:56px;color:#17232e;text-decoration:none}
.milano-brand>span{width:39px;height:39px;display:grid;place-items:center;border-radius:10px;background:#ff9910;font-size:21px;font-weight:950}
.milano-brand strong,.milano-brand small,.milano-user small{display:block}.milano-brand small{color:#9199a2;font-size:8px;letter-spacing:.18em}
.milano-topbar nav{display:flex;align-items:center;gap:31px;height:100%}.milano-topbar nav a{height:100%;display:flex;align-items:center;position:relative;color:#66717c;text-decoration:none;font-size:13px}.milano-topbar nav a.active{color:#17232e;font-weight:850}.milano-topbar nav a.active:after{content:"";position:absolute;inset:auto 0 0;height:3px;background:#ff9910}
.milano-user{display:flex;align-items:center;gap:10px;margin-left:auto;font-size:11px}.milano-user>span{width:35px;height:35px;display:grid;place-items:center;border-radius:50%;background:#1b2731;color:#fff}.milano-user small{color:#929ba4}
.workspace{width:min(1500px,94vw);min-width:0;margin:0 auto;padding:36px 0 70px}.workspace-loading{display:flex;align-items:center;gap:13px;margin-bottom:18px;padding:14px 16px;border:1px solid #e2e8ec;border-radius:10px;background:#fff}.workspace-loading>i{width:22px;height:22px;border:3px solid #ffe1b2;border-top-color:#ff9700;border-radius:50%;animation:workspace-spin .8s linear infinite}.workspace-loading span{display:grid;gap:3px}.workspace-loading small{color:#7a8791}.workspace-loading.error{border-color:#efc9c4;background:#fff8f7}.workspace-loading.error button{margin-left:auto;padding:7px 12px;border:1px solid #db887d;border-radius:6px;background:#fff;color:#a13d31}@keyframes workspace-spin{to{transform:rotate(360deg)}}.milano-heading{display:flex;align-items:flex-end;justify-content:space-between;margin-bottom:24px}.milano-heading p{margin:0 0 8px;color:#dd7c00;font-size:10px;font-weight:900;letter-spacing:.2em}.milano-heading h1{margin:0 0 7px;font-size:30px}.milano-heading span{color:#75808a;font-size:12px}.tip{background:#fff7e9;border-color:#f2d7ad}.data-card,.filters{background:#fff}.scroll{max-height:none}
.workspace-switch{display:flex;gap:8px;margin:0 0 16px;padding:5px;background:#e9edf0;border-radius:10px}.workspace-switch button{min-width:180px;height:40px;padding:0 24px;border:0;border-radius:7px;background:transparent;color:#68747e;font-weight:750}.workspace-switch button.active{background:#fff;color:#17232e;box-shadow:0 3px 12px #24313d12}.base-settings{overflow:hidden;background:#fff;border:1px solid #e0e6ea;border-radius:12px;box-shadow:0 8px 28px #1e2c3810}.base-tabs{display:flex;height:54px;border-bottom:1px solid #e5eaed}.base-tabs button{min-width:220px;border:0;background:#fff;color:#586671;font-weight:800;position:relative}.base-tabs button.active{color:#e47d00}.base-tabs button.active:after{content:"";position:absolute;left:20px;right:20px;bottom:0;height:3px;background:#ff930f}.base-toolbar,.section-title{display:flex;align-items:center;justify-content:space-between;gap:20px}.base-toolbar{padding:22px 24px 12px}.base-toolbar h2,.section-title h2{margin:0 0 5px;font-size:18px;color:#17232e}.base-toolbar p,.section-title p{margin:0;color:#7a858f;font-size:11px}.outline-orange{height:38px;padding:0 16px;border:1px solid #ff9414;border-radius:7px;background:#fff;color:#d97900;font-weight:800}.hidden-file{display:none}.provider-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:16px;padding:12px 24px 26px}.provider-card{border:1px solid #dfe5e9;border-radius:10px;overflow:hidden}.provider-card>header{display:flex;align-items:center;gap:12px;padding:16px;border-bottom:1px solid #edf0f2}.provider-icon{width:42px;height:42px;display:grid;place-items:center;border-radius:50%;background:#fff0d9;color:#d97600;font-weight:900;font-size:16px}.provider-card h3{margin:0;color:#17232e;font-size:16px}.provider-card h3 span{display:inline-block;margin-left:6px;padding:3px 7px;border-radius:10px;background:#fff0d8;color:#d47600;font-size:9px}.provider-card header small{color:#8a949c}.provider-card .upload{height:34px;margin-left:auto;padding:0 13px;border:0;border-radius:6px;background:#ff9511;color:#fff;font-weight:800}.provider-card h4{margin:14px 16px 8px;font-size:11px}.template-list{padding:0 16px}.template-list>div{display:grid;grid-template-columns:minmax(140px,1.4fr) auto auto auto;gap:8px;align-items:center;padding:10px 0;border-top:1px solid #eef1f3;font-size:10px}.template-list b{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.template-list span{color:#199260}.template-list span.pending{color:#d38200}.template-list time{color:#89939b}.template-list button,.provider-card footer button{border:0;background:none;color:#247cb0}.provider-card>footer{justify-content:space-between;padding:11px 16px;border-top:1px solid #edf0f2;color:#8a949c}.empty-template{margin:0 16px 16px;padding:28px;text-align:center;background:#f7f9fa;color:#8b959d}.codes-layout{display:grid;grid-template-columns:.8fr 1.2fr;gap:16px;padding:22px}.codes-card{border:1px solid #dfe5e9;border-radius:10px;overflow:hidden}.section-title{padding:16px}.settings-table{table-layout:auto}.settings-table th{position:static;background:#f5f7f8;border:0;border-top:1px solid #e5e9ec}.settings-table td{height:38px;border:0;border-top:1px solid #edf0f2;text-align:left;padding:6px 14px}.settings-table input{box-sizing:border-box;width:100%;height:30px;border:1px solid #d8e0e5;border-radius:5px;padding:0 8px;background:#fff}.zone-filters{display:grid;grid-template-columns:repeat(3,1fr);gap:8px;padding:12px 16px;background:#f5f9fd;border-top:1px solid #e3e9ee}.zone-filters select,.simple-form input,.simple-form select{height:34px;border:1px solid #d7e0e6;border-radius:6px;background:#fff;padding:0 9px}.zone-note{margin:12px 16px 16px;padding:10px 12px;border:1px solid #f5d6ad;border-radius:6px;background:#fff8ef;color:#b46a09;font-size:10px}.danger-text{border:0;background:none;color:#d45445}.empty-row{text-align:center!important;color:#8b959d}.save-settings{display:flex;align-items:center;justify-content:flex-end;gap:18px;padding:15px 22px;border-top:1px solid #e3e8eb;background:#fafbfc;color:#7e8992;font-size:11px}.save-settings button,.primary-orange{height:38px;padding:0 24px;border:0;border-radius:6px;background:#ff9310;color:#fff;font-weight:850}.simple-form{display:grid;gap:14px;padding:22px}.simple-form label{display:grid;grid-template-columns:105px 1fr;align-items:center;color:#5f6d78}.base-modal footer{gap:7px}.base-modal footer .primary-orange{border:0;color:#fff}.provider-card footer{margin:0}
.provider-toolbar{display:flex;align-items:center;gap:12px}.provider-toolbar label{display:flex;align-items:center;gap:7px;width:260px;height:38px;box-sizing:border-box;padding:0 11px;border:1px solid #dbe2e7;border-radius:7px;color:#72808b}.provider-toolbar input{width:100%;border:0;outline:0;background:transparent;color:#26333d}.provider-manager{display:grid;grid-template-columns:320px minmax(0,1fr);gap:14px;padding:12px 24px 26px}.provider-list,.provider-detail{min-width:0;border:1px solid #dfe5e9;border-radius:10px;background:#fff;overflow:hidden}.provider-sort-hint{height:44px;display:flex;align-items:center;justify-content:space-between;padding:0 12px;border-bottom:1px solid #e5eaed;background:#fff}.provider-sort-hint span{font-weight:850}.provider-sort-hint small{color:#929da5}.provider-list-scroll{height:458px;padding:8px;overflow:auto;background:#f6f8fa}.provider-list-scroll>button{width:100%;min-height:54px;display:grid;grid-template-columns:14px 36px minmax(0,1fr) auto 12px;align-items:center;gap:8px;margin-bottom:7px;padding:7px 9px;border:1px solid #e0e6ea;border-radius:8px;background:#fff;box-shadow:0 2px 7px #2637440a;text-align:left;color:#26333d;transition:border-color .15s,box-shadow .15s,transform .15s,opacity .15s}.provider-list-scroll>button:hover{border-color:#f1b967;background:#fffaf2}.provider-list-scroll>button.active{border-color:#ffae42;box-shadow:inset 4px 0 #ff930f,0 4px 12px #bd690014;background:#fff4e4}.provider-list-scroll>button.dragging{opacity:.35}.provider-list-scroll>button.drag-over{border-color:#ff930f;box-shadow:0 -3px 0 #ff930f,0 5px 14px #bd690018;transform:translateY(2px)}.provider-list-scroll>button u{color:#aeb7be;font-size:13px;line-height:1;text-decoration:none;cursor:grab}.provider-list-scroll>button:active u{cursor:grabbing}.provider-list-scroll>button i{width:32px;height:32px;display:grid;place-items:center;border-radius:50%;background:#fff0d9;color:#d97600;font-style:normal;font-weight:900}.provider-list-scroll>button span{min-width:0;display:grid;gap:2px}.provider-list-scroll>button b{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.provider-list-scroll>button small{color:#9aa3aa;font-size:8px}.provider-list-scroll>button em{color:#84909a;font-size:10px;font-style:normal;white-space:nowrap}.provider-list-scroll>button.active em,.provider-list-scroll>button.active strong{color:#e17a00}.provider-list-scroll>button strong{color:#89959e;font-size:20px}.provider-list>footer{justify-content:center;height:38px;box-sizing:border-box;border-top:1px solid #e8ecef;color:#7f8a93;font-size:11px}.provider-empty{display:grid;place-items:center;height:160px;color:#929ca4}.provider-detail>header{display:flex;align-items:center;gap:12px;min-height:74px;padding:0 18px;border-bottom:1px solid #e8ecef}.provider-detail h3{margin:0 0 4px;font-size:17px}.provider-detail h3 span{display:inline-block;margin-left:7px;padding:3px 7px;border-radius:10px;background:#fff0d8;color:#d47600;font-size:9px}.provider-detail header small{color:#8a949c}.provider-detail-actions{display:flex;align-items:center;gap:8px;margin-left:auto}.provider-detail .upload{height:36px;padding:0 15px;border:0;border-radius:6px;background:#ff9511;color:#fff;font-weight:800}.danger-outline{height:36px;padding:0 13px;border:1px solid #e7a59d;border-radius:6px;background:#fff;color:#c94f40;font-weight:750}.danger-outline:hover{background:#fff3f1}.provider-detail-body{padding:18px}.provider-detail-body h4{margin:0 0 12px;font-size:14px}.provider-template-table{border:1px solid #dfe5e9;border-radius:8px;overflow-x:auto}.template-table-head,.template-table-row{min-width:900px;display:grid;grid-template-columns:minmax(165px,1.35fr) minmax(150px,1.15fr) 90px 78px 126px 170px;align-items:center;gap:12px;min-height:48px;padding:0 14px}.template-table-head{min-height:38px;background:#f5f7f8;color:#66737e;font-size:10px;font-weight:800}.template-table-row{border-top:1px solid #edf0f2;font-size:11px}.template-table-row>b{min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.template-table-row>b small,.matched-channel small{display:block;color:#939da5;font-size:8px;font-weight:500}.matched-channel{min-width:0}.matched-channel strong{display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.template-table-row code{color:#4a5862;font-weight:800}.template-table-row>span{width:max-content;padding:3px 8px;border-radius:10px;background:#e6f6eb;color:#198951;font-size:9px;font-weight:800}.template-table-row>span.pending{background:#fff0d8;color:#d57900}.template-table-row time{color:#65727c}.template-actions{display:flex;align-items:center;flex-wrap:wrap;gap:5px 9px}.template-actions button{padding:0;border:0;background:none;color:#247cb0;font-size:10px}.template-actions .move-template{color:#d87900;font-weight:800}.template-actions .delete-template{color:#cf5142}.template-table-empty{display:grid;place-items:center;height:104px;color:#929ca4}.template-dropzone{width:100%;height:130px;display:grid;place-items:center;align-content:center;gap:7px;margin-top:18px;border:1px dashed #ccd7df;border-radius:8px;background:#fbfcfd;color:#6f7d87}.template-dropzone:hover{border-color:#ffae45;background:#fffaf2}.template-dropzone i{font-size:28px;color:#9aa7b0;font-style:normal}.template-dropzone strong{margin-left:3px;color:#1f80b7}
.settings-table th:first-child,.settings-table td:first-child{width:auto}.codes-card:first-child .settings-table th:first-child,.codes-card:first-child .settings-table td:first-child{width:78px}.zone-card .settings-table th:first-child,.zone-card .settings-table td:first-child{width:42%}.workspace-switch button:focus,.base-tabs button:focus{outline:none}.workspace-switch button:focus-visible,.base-tabs button:focus-visible{box-shadow:0 0 0 3px #ff991033}
.country-region-card{margin:22px}.country-region-table th:nth-child(1){width:18%}.country-region-table th:nth-child(2){width:24%}.country-region-table th:nth-child(3){width:16%}.country-region-table th:nth-child(4){width:24%}.country-region-table th:nth-child(5){width:12%}.australia-country-row{background:#fff8ee}.zone-total{color:#d87900}.zone-toggle{border:0;background:none;color:#247cb0;font-weight:750}.australia-zone-row{background:#fffdf9}.australia-zone-row td:first-child{display:flex;align-items:center;gap:8px;padding-left:30px}.zone-branch{color:#e18a1b;font-size:16px}.fixed-zone{display:inline-block;padding:4px 8px;border-radius:10px;background:#fff0d8;color:#c76c00;font-size:9px;font-weight:750}.country-zone-note{margin:14px 16px 16px;padding:10px 12px;border:1px solid #f5d6ad;border-radius:6px;background:#fff8ef;color:#a9620a;font-size:10px}
.template-table-head,.template-table-row{min-width:680px;grid-template-columns:minmax(150px,1.2fr) minmax(220px,1.6fr) 130px 90px}.template-table-row>b,.template-file{min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.template-file{color:#687681}
@media(max-width:900px){.milano-topbar nav{display:none}.milano-brand{margin-right:0}.workspace{padding-top:22px}}
.country-region-card .country-region-table th:first-child,.country-region-card .country-region-table td:first-child{width:18%}
.country-region-panel{margin:22px;padding:20px;border:1px solid #dfe5e9;border-radius:10px;background:#fff}.country-region-panel>.section-title{padding:0 0 16px}.standard-country-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px}.country-area-card{display:grid;grid-template-columns:minmax(0,1fr) 110px auto;align-items:center;gap:14px;padding:16px;border:1px solid #e1e7eb;border-radius:9px;background:#fbfcfd}.country-area-card div,.australia-area-card header>div{display:grid;gap:3px}.country-area-card small,.australia-area-card small{color:#8a959d;font-size:9px}.country-area-card label,.australia-area-card header label{display:grid;gap:4px;color:#78858f;font-size:9px}.country-area-card input,.australia-area-card input{box-sizing:border-box;width:100%;height:32px;border:1px solid #d8e0e5;border-radius:6px;padding:0 9px;background:#fff}.country-area-card>span{padding:5px 9px;border-radius:12px;background:#eef3f6;color:#65747f;font-size:9px;white-space:nowrap}.australia-area-card{margin-top:14px;border:1px solid #f0b65f;border-radius:10px;background:#fffaf2;overflow:hidden}.australia-area-card>header{display:grid;grid-template-columns:minmax(160px,1fr) 110px auto auto;align-items:center;gap:16px;padding:16px 18px}.australia-area-card>header>span{padding:5px 10px;border-radius:12px;background:#fff0d8;color:#c96d00;font-size:9px;font-weight:800}.australia-zone-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:10px;padding:14px 18px 18px;border-top:1px solid #f4d8ad}.australia-zone-grid label{display:grid;grid-template-columns:1fr;gap:6px;padding:13px;border:1px solid #eadfcf;border-radius:8px;background:#fff}.australia-zone-grid label span{color:#8a959d;font-size:9px}.australia-area-card>p{margin:0;padding:11px 18px;border-top:1px solid #f4d8ad;color:#a86613;font-size:10px}.country-region-panel .zone-toggle{height:32px;padding:0 10px;border:1px solid #e8c48e;border-radius:6px;background:#fff;color:#b66a0b}.country-region-panel+.save-settings{margin-top:0}
.country-directory-toolbar{display:flex;align-items:center;justify-content:space-between;gap:18px}.country-directory-toolbar h2{margin:0 0 5px;color:#17232e}.country-directory-toolbar p{margin:0;color:#7b8790;font-size:11px}.country-directory-toolbar>label{display:flex;align-items:center;gap:7px;width:300px;height:38px;box-sizing:border-box;padding:0 11px;border:1px solid #dbe2e7;border-radius:7px;color:#73808a}.country-directory-toolbar>label input{width:100%;border:0;outline:0;background:transparent}.country-directory-table{margin-top:14px;border:1px solid #e0e6ea;border-radius:9px;overflow:hidden}.country-directory-table table{table-layout:fixed}.country-directory-table th{border:0;border-bottom:1px solid #e0e6ea;background:#f5f7f8}.country-directory-table th:nth-child(1){width:22%}.country-directory-table th:nth-child(2){width:28%}.country-directory-table th:nth-child(3){width:16%}.country-directory-table th:nth-child(4){width:20%}.country-directory-table th:nth-child(5){width:14%}.country-directory-table td,.country-directory-table td:first-child{width:auto;border:0;border-top:1px solid #edf0f2;padding:9px 14px;text-align:left}.country-directory-table input{box-sizing:border-box;width:100%;height:30px;border:1px solid #d8e0e5;border-radius:5px;padding:0 8px}.country-directory-table code{font-weight:800;color:#34434e}.country-unified{display:inline-block;padding:4px 8px;border-radius:10px;background:#eef3f6;color:#65747f;font-size:9px}.country-edit,.country-save{border:0;background:none;color:#247cb0;font-weight:750}.country-save{color:#e07800}.country-directory-pagination{display:flex;align-items:center;justify-content:flex-end;gap:10px;padding:13px 2px 0;color:#75818a}.country-directory-pagination>span{margin-right:auto}.country-directory-pagination select{height:30px;margin:0 5px;border:1px solid #d8e0e5;border-radius:5px;background:#fff}.country-directory-pagination button{height:30px;padding:0 11px;border:1px solid #d8e0e5;border-radius:5px;background:#fff;color:#53636f}.country-directory-pagination button:disabled{opacity:.45;cursor:not-allowed}
.country-directory-table th:nth-child(1){width:38%}.country-directory-table th:nth-child(2){width:18%}.country-directory-table th:nth-child(3){width:28%}.country-directory-table th:nth-child(4){width:16%}
.australia-area-card>header{grid-template-columns:minmax(160px,1fr) auto auto}
.australia-zone-item{display:grid;gap:6px;padding:13px;border:1px solid #eadfcf;border-radius:8px;background:#fff}.australia-zone-item span{color:#8a959d;font-size:9px}
.country-directory-table th:nth-child(1){width:55%}.country-directory-table th:nth-child(2){width:30%}.country-directory-table th:nth-child(3){width:15%}
@media(max-width:1100px){.provider-grid,.codes-layout{grid-template-columns:1fr}.template-list>div{grid-template-columns:1fr auto}.template-list time{display:none}.zone-filters{grid-template-columns:1fr}.provider-manager{grid-template-columns:250px minmax(0,1fr)}}
@media(max-width:1100px){.standard-country-grid{grid-template-columns:1fr}.australia-zone-grid{grid-template-columns:repeat(2,minmax(0,1fr))}}
@media(max-width:760px){.base-toolbar{align-items:flex-start;flex-direction:column}.provider-toolbar{width:100%}.provider-toolbar label{width:auto;flex:1}.provider-manager{grid-template-columns:1fr}.provider-list-scroll{height:250px}.provider-detail>header{flex-wrap:wrap;padding-block:12px}.provider-detail-actions{width:100%;margin-left:0}.provider-detail-actions .upload{margin-left:auto}}

/* Milano quotation theme — logistics workspace */
.rule-stat-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:14px;margin-bottom:16px}.rule-stat-grid article{min-width:0;display:flex;align-items:center;gap:14px;padding:18px 20px;border:1px solid #e0e6ea;border-radius:11px;background:#fff;box-shadow:0 7px 22px rgba(29,42,53,.045)}.stat-icon{flex:0 0 42px;width:42px;height:42px;display:grid;place-items:center;border-radius:50%;font-size:15px;font-weight:900}.stat-icon.orange{background:#fff0d7;color:#d87700}.stat-icon.green{background:#e5f6ec;color:#178955}.stat-icon.blue{background:#e9f3f9;color:#347eaa}.stat-icon.amber{background:#fff5df;color:#b57a11}.rule-stat-grid article div{min-width:0;display:grid;grid-template-columns:1fr auto;align-items:end;gap:2px 10px}.rule-stat-grid small{color:#75818b;font-size:10px}.rule-stat-grid b{grid-row:1/3;grid-column:2;font-size:25px;line-height:1;color:#18252f}.rule-stat-grid em{color:#9aa3aa;font-size:9px;font-style:normal;white-space:nowrap}
.rule-workspace-card,.area-workspace-card{overflow:visible;border:1px solid #dfe5e9;border-radius:12px;background:#fff;box-shadow:0 8px 28px rgba(30,44,56,.05)}.rule-card-head{display:flex;align-items:center;justify-content:space-between;gap:20px;padding:20px 22px;border-bottom:1px solid #e6ebee}.rule-card-head h2{margin:0 0 5px;font-size:18px}.rule-card-head p{margin:0;color:#7a8790;font-size:11px}.header-actions{display:flex;align-items:center;gap:9px}.primary-button,.primary-orange{height:38px;padding:0 18px;border:0;border-radius:7px;background:#ff9411!important;color:#fff!important;font-weight:850;box-shadow:0 5px 13px rgba(223,119,0,.16)}.primary-button:hover,.primary-orange:hover{background:#ed8500!important}.secondary-button,.reset-button,.cancel-button,.back-button{height:36px;padding:0 14px;border:1px solid #d7e0e5;border-radius:7px;background:#fff;color:#50606c;font-weight:750}.secondary-button:hover,.reset-button:hover,.cancel-button:hover,.back-button:hover{border-color:#f0b15b;background:#fffaf2;color:#c86e00}
.modern-filters{display:flex;align-items:center;flex-wrap:wrap;gap:10px;padding:14px 22px;background:#f8fafb;border-bottom:1px solid #e6ebee}.modern-filters select{box-sizing:border-box;height:37px;min-width:130px;padding:0 31px 0 11px;border:1px solid #d8e1e6;border-radius:7px;background:#fff;color:#53616c}.keyword-search{width:min(290px,100%);height:37px;box-sizing:border-box;display:flex;align-items:center;gap:7px;padding:0 11px;border:1px solid #d8e1e6;border-radius:7px;background:#fff;color:#7a8791}.keyword-search:focus-within{border-color:#f3a33a;box-shadow:0 0 0 3px rgba(255,148,17,.1)}.keyword-search input{min-width:0;width:100%;border:0;outline:0;background:transparent;color:#26343e}.search-mode{display:flex;height:37px;padding:3px;border-radius:7px;background:#e9eef1}.search-mode button{padding:0 11px;border:0;border-radius:5px;background:transparent;color:#73808a;font-size:10px;font-weight:750}.search-mode button.active{background:#fff;color:#d87800;box-shadow:0 2px 7px rgba(38,51,61,.1)}.reset-button{height:37px}.filter-result{margin-left:auto;color:#78848e;font-size:11px}
.modern-table-scroll{max-width:100%;overflow-x:auto;overflow-y:visible}.modern-rule-table,.modern-area-table{width:100%;min-width:1050px;border-collapse:separate;border-spacing:0;table-layout:auto}.modern-rule-table th,.modern-area-table th{height:39px;padding:0 14px;border:0;border-bottom:1px solid #e1e7eb;background:#f4f7f8;color:#66747e;text-align:left;font-size:10px;font-weight:800}.modern-rule-table td,.modern-area-table td{height:58px;padding:10px 14px;border:0;border-bottom:1px solid #edf1f3;color:#26343e;text-align:left;line-height:1.45;vertical-align:middle}.modern-rule-table tbody tr:hover,.modern-area-table tbody tr:hover{background:#fffaf3}.modern-rule-table th:first-child,.modern-rule-table td:first-child{width:auto;text-align:left}.modern-area-table th:first-child,.modern-area-table td:first-child{width:190px;text-align:left}.modern-rule-table th:last-child,.modern-rule-table td:last-child{width:auto}.modern-area-table th:last-child,.modern-area-table td:last-child{width:126px}.modern-rule-table .col-rule{width:16%}.modern-rule-table .col-provider{width:11%}.modern-rule-table .col-channel{width:15%}.modern-rule-table .col-code{width:15%}.modern-rule-table .col-count{width:7%}.modern-rule-table .col-version{width:9%}.modern-rule-table .col-status{width:8%}.modern-rule-table .col-action{width:9%}.modern-rule-table input[type=checkbox],.modern-area-table input[type=checkbox]{accent-color:#ff9411}.rule-name-cell b,.maintenance-cell b,.price-cell b{display:block;color:#17242e;font-size:12px}.rule-name-cell small,.maintenance-cell small,.price-cell small,.limit-cell small,.modern-rule-table td>small{display:block;margin-top:4px;color:#909aa2;font-size:9px}.rule-type{display:inline-block;margin-bottom:3px;padding:3px 8px;border-radius:10px;background:#fff0da;color:#ce7200;font-size:9px;font-weight:850}.status-pill{display:inline-block;padding:4px 9px;border:1px solid transparent;border-radius:11px;font-size:9px;font-weight:850}.status-pill.success{border-color:#a9dfbf;background:#e9f8ef;color:#16834f}.status-pill.pending{border-color:#f1d49d;background:#fff6e6;color:#a66a05}.status-pill.disabled{border-color:#d6dde1;background:#f1f4f5;color:#68757e}.modern-ops{position:relative;display:flex;align-items:center;gap:7px;white-space:nowrap}.modern-ops>button,.more-wrap>button{height:30px;padding:0 10px;border-radius:6px;background:#fff;font-size:10px;font-weight:800}.area-button{border:1px solid #ff9411;color:#cf7200}.edit-button{border:1px solid #d5dee3;color:#435662}.more-button{min-width:32px;border:1px solid #d5dee3!important;color:#62717c;letter-spacing:1px}.more-wrap{position:relative}.more-popover{position:absolute;z-index:25;right:0;top:36px;width:125px;padding:6px;border:1px solid #dfe5e9;border-radius:8px;background:#fff;box-shadow:0 10px 28px rgba(27,40,50,.18)}.more-popover button{width:100%;height:32px;padding:0 9px;border:0;border-radius:5px;background:#fff;color:#43535e;text-align:left;font-size:10px}.more-popover button:hover{background:#f5f7f8}.more-popover button.danger{color:#cf4e41}.modern-empty{height:120px!important;color:#939da4!important;text-align:center!important}
.modern-pagination{display:flex;align-items:center;justify-content:flex-end;gap:10px;min-height:52px;box-sizing:border-box;padding:8px 18px;border-radius:0 0 12px 12px;border-top:0;background:#fafbfc;color:#75818a;font-size:10px}.modern-pagination>span{margin-right:auto}.modern-pagination label{display:flex;align-items:center;gap:5px}.modern-pagination select{height:30px;border:1px solid #d8e0e5;border-radius:5px;background:#fff}.modern-pagination button{height:30px;padding:0 11px;border:1px solid #d8e0e5;border-radius:5px;background:#fff;color:#52626d}.modern-pagination button:disabled{opacity:.42;cursor:not-allowed}.modern-pagination b{color:#34434d}
.area-page-head{display:flex;align-items:flex-end;gap:18px;margin-bottom:20px}.area-page-head>div{flex:1}.area-page-head p{margin:0 0 7px;color:#d97700;font-size:10px;font-weight:900;letter-spacing:.18em}.area-page-head h1{margin:0 0 6px;font-size:28px}.area-page-head span{color:#78848e;font-size:11px}.back-button{align-self:center}.area-page-head>.primary-button{align-self:center}.area-workspace-card{overflow:hidden}.area-toolbar{display:flex;align-items:center;gap:12px;padding:15px 18px;border-bottom:1px solid #e4eaed;background:#fff}.area-toolbar>label{width:290px;height:37px;box-sizing:border-box;display:flex;align-items:center;gap:7px;padding:0 11px;border:1px solid #d8e1e6;border-radius:7px;color:#7b8790}.area-toolbar input{min-width:0;width:100%;border:0;outline:0}.area-toolbar>div{display:flex;gap:7px}.area-toolbar>span{margin-left:auto;color:#78848d;font-size:10px}.limit-cell span{display:block;color:#42535e}.danger-outline{border-color:#edb5ae!important;color:#bd4b40!important;background:#fff!important}
.mask{backdrop-filter:blur(3px);background:rgba(17,27,35,.48)}.modal,.modal.small,.modal.area-modal{overflow:hidden;border:1px solid #e0e6ea;border-radius:12px;background:#fff;box-shadow:0 22px 60px rgba(18,29,38,.24)}.modal header{min-height:66px;height:auto;box-sizing:border-box;display:flex;align-items:center;padding:12px 20px;border-bottom:1px solid #e5eaed;background:#fff;color:#192730}.modal header>div{display:grid;gap:4px}.modal header small{color:#d87800;font-size:8px;font-weight:900;letter-spacing:.16em}.modal header b{font-size:17px}.modal header>button{width:32px;height:32px;display:grid;place-items:center;margin-left:auto;border:0;border-radius:50%;background:#f3f5f6;color:#71808a;font-size:20px}.form{display:grid;grid-template-columns:1fr 1fr;gap:16px;padding:22px}.form label{display:grid;grid-template-columns:1fr;align-items:initial;gap:7px;color:#62707b;font-size:10px}.form input,.form select,.simple-form input,.simple-form select{box-sizing:border-box;width:100%;height:38px;border:1px solid #d7e0e5;border-radius:7px;padding:0 10px;background:#fff;color:#273640}.form input:focus,.form select:focus,.simple-form input:focus,.simple-form select:focus{outline:0;border-color:#f2a23a;box-shadow:0 0 0 3px rgba(255,148,17,.1)}.modal footer{display:flex;justify-content:flex-end;gap:8px;padding:14px 20px;border-top:1px solid #e4e9ec;background:#fafbfc}.modal footer button{height:38px;padding:0 18px;border-radius:7px}.check{display:flex;align-items:flex-start;gap:11px;margin:24px;padding:18px;border:1px solid #e1e7ea;border-radius:8px;background:#fafbfc}.check input{margin-top:3px;accent-color:#ff9411}.check span{display:grid;gap:4px}.check small{color:#87929a}.toast{right:28px;bottom:28px;border-radius:8px;background:#1e2b34;box-shadow:0 10px 30px rgba(16,25,32,.25)}
.base-settings,.provider-list,.provider-detail,.codes-card,.country-region-panel,.country-directory-table{box-shadow:0 5px 18px rgba(30,44,56,.035)}.provider-list-scroll{background:#f7f9fa}.template-dropzone{transition:border-color .15s,background .15s}.save-settings{border-radius:0 0 12px 12px}.base-settings button,.rule-workspace-card button,.area-workspace-card button,.modal button{cursor:pointer}
.published-summary{display:flex;align-items:center;gap:13px;margin-bottom:13px;padding:15px 20px;border:1px solid #e0e6ea;border-radius:11px;background:#fff;color:#6d7982;box-shadow:0 7px 22px rgba(29,42,53,.045)}.published-summary span,.published-summary button{display:flex;align-items:baseline;gap:5px}.published-summary b{color:#17242e;font-size:22px}.published-summary i{color:#c4cbd0;font-style:normal}.published-summary button{padding:0;border:0;background:transparent;color:#c7473c;font:inherit}.published-summary button b{color:#c7473c}.published-tabs{display:flex;gap:4px;margin-bottom:14px;padding:5px;border-radius:9px;background:#e9edf0}.published-tabs button{flex:1;height:39px;border:0;border-radius:6px;background:transparent;color:#65717b}.published-tabs button.active{background:#fff;color:#171f26;font-weight:850;box-shadow:0 2px 10px #21303e12}.published-panel{overflow:hidden;border:1px solid #dfe5e9;border-radius:12px;background:#fff;box-shadow:0 8px 28px rgba(30,44,56,.05)}.provider-summary-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px;padding:16px}.provider-summary-grid article{display:flex;align-items:center;gap:12px;min-width:0;padding:16px;border:1px solid #e2e7ea;border-radius:9px}.provider-summary-grid article>i{flex:0 0 38px;width:38px;height:38px;display:grid;place-items:center;border-radius:9px;background:#fff0d9;color:#c97000;font-style:normal;font-weight:900}.provider-summary-grid article>div{min-width:0;display:grid;gap:4px}.provider-summary-grid article>div b,.provider-summary-grid article>div small{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.provider-summary-grid article>div small{color:#8b969e}.provider-summary-grid article>span{margin-left:auto;color:#72808a;font-size:9px;line-height:1.7;text-align:right}.provider-summary-grid article>span em{color:#c7473c;font-style:normal}.published-data-table{width:100%;min-width:900px;border-collapse:separate;border-spacing:0;table-layout:auto}.published-data-table th,.published-data-table td{height:44px;padding:8px 14px;border:0;border-bottom:1px solid #edf1f3;text-align:left}.published-data-table th{background:#f4f7f8;color:#66747e;font-size:10px}.published-data-table th:first-child,.published-data-table td:first-child{width:auto;text-align:left}.published-data-table td small{display:block;margin-top:4px;color:#909aa2}.channel-code{display:block;max-width:220px;overflow:hidden;color:#2c647f;text-overflow:ellipsis;white-space:nowrap}.blocked-link{padding:0;border:0;background:transparent;color:#c7473c;font-weight:800}.country-summary-grid{display:grid;grid-template-columns:repeat(5,minmax(0,1fr));gap:10px;padding:16px}.country-summary-grid article{display:grid;grid-template-columns:minmax(0,1fr) auto;gap:5px;padding:13px;border:1px solid #e2e7ea;border-radius:8px}.country-summary-grid article b{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.country-summary-grid article code{color:#c97000}.country-summary-grid article small{grid-column:1/-1;color:#8b969e}.detail-panel>header{display:flex;align-items:center;justify-content:space-between;gap:20px;padding:18px 20px;border-bottom:1px solid #e6ebee}.detail-panel>header h2{margin:0 0 5px}.detail-panel>header p{margin:0;color:#7a8790}.detail-panel>header label{display:grid;gap:5px;color:#71808a;font-size:9px}.detail-panel select,.calculator-form select,.calculator-form input{min-width:300px;height:38px;box-sizing:border-box;border:1px solid #d7e0e5;border-radius:6px;padding:0 10px;background:#fff}.weight-table{min-width:1050px}.error-text{color:#c7473c!important}.freight-calculator{display:grid;grid-template-columns:minmax(0,1fr) 360px}.calculator-form{display:grid;gap:13px;padding:28px}.calculator-form>p{margin:0;color:#d77b00;font-size:10px;font-weight:900;letter-spacing:.16em}.calculator-form h2{margin:0 0 8px}.calculator-form label{display:grid;gap:6px;color:#71808a;font-size:10px}.calculator-result{padding:30px;background:#1b2630;color:#fff}.calculator-result>small{color:#aeb7bf}.calculator-result>strong{display:block;margin:10px 0 25px;color:#ffad38;font-size:28px}.calculator-result p{display:flex;justify-content:space-between;gap:15px;margin:0;padding:9px 0;border-top:1px solid #34414c;font-size:11px}.calculator-result p span{color:#aab4bd}.blocked-filter-button{height:38px;padding:0 12px;border:1px solid #e9a9a2;border-radius:7px;background:#fff3f1;color:#be4237;font-weight:800}
.rule-detail-table{min-width:1500px}.rule-detail-table th,.rule-detail-table td{white-space:nowrap}.rule-detail-table .limit-cell{min-width:210px;white-space:normal}
@media(max-width:1180px){.rule-stat-grid{grid-template-columns:repeat(2,minmax(0,1fr))}.rule-card-head{align-items:flex-start}.header-actions{flex-wrap:wrap;justify-content:flex-end}.modern-table-scroll{overflow-x:auto}.modern-rule-table,.modern-area-table{min-width:980px}.rule-detail-table{min-width:1500px}.provider-summary-grid{grid-template-columns:repeat(2,minmax(0,1fr))}.country-summary-grid{grid-template-columns:repeat(3,minmax(0,1fr))}.more-popover{position:fixed;right:5vw;top:auto}}
@media(max-width:900px){.workspace{width:94vw;min-width:0}.rule-card-head,.area-page-head{align-items:flex-start;flex-direction:column}.header-actions{width:100%;justify-content:flex-start}.area-page-head>.primary-button{align-self:flex-start}.modern-filters{align-items:stretch}.keyword-search{width:100%}.filter-result{width:100%;margin-left:0}.area-toolbar{align-items:flex-start;flex-wrap:wrap}.area-toolbar>label{width:100%}.area-toolbar>span{width:100%;margin-left:0}.form{grid-template-columns:1fr}.modal,.modal.area-modal{width:min(680px,92vw);max-height:90vh;overflow:auto}}
@media(max-width:620px){.rule-stat-grid{grid-template-columns:1fr}.rule-stat-grid article{padding:14px 16px}.header-actions{display:grid;grid-template-columns:1fr 1fr}.header-actions .primary-button{grid-column:1/-1}.header-actions button{width:100%}.modern-filters select{flex:1}.search-mode{width:100%}.search-mode button{flex:1}.modern-pagination{flex-wrap:wrap}.modern-pagination>span{width:100%;margin:0}.area-toolbar>div{width:100%;overflow-x:auto}.milano-heading h1,.area-page-head h1{font-size:24px}.provider-toolbar{flex-direction:column;align-items:stretch}.provider-toolbar label{width:100%}.provider-detail-actions{align-items:stretch;flex-direction:column}.provider-detail-actions .upload{width:100%;margin:0}.modal footer{position:sticky;bottom:0}}
.version-table .template-table-head,.version-table .template-table-row{min-width:1060px;grid-template-columns:minmax(180px,1.3fr) minmax(180px,1.2fr) 120px 105px 150px 220px}.version-table .template-table-row>div>strong,.version-table .template-table-row>div>small{display:block}.version-table .template-table-row>div>small{color:#8c979f;font-size:9px}.import-review-modal{width:min(1180px,94vw);max-height:92vh}.review-body{padding:20px;overflow:auto;max-height:72vh}.review-stats{display:grid;grid-template-columns:repeat(6,minmax(0,1fr));gap:10px;margin-bottom:16px}.review-stats article{display:grid;gap:7px;padding:14px;border:1px solid #e1e7ea;border-radius:8px;background:#f8fafb}.review-stats small{color:#7d8992}.review-stats b{font-size:23px}.review-stats .added b{color:#168951}.review-stats .changed b{color:#d47900}.review-stats .removed b,.review-stats .risk b{color:#cf493d}.issue-list{display:grid;gap:5px;margin-bottom:16px;padding:14px;border:1px solid #efd5ad;border-radius:8px;background:#fff9ef}.issue-list span{font-size:10px}.issue-list span.error{color:#c83f35}.issue-list span.warning{color:#a46a0b}.diff-table{border:1px solid #e0e6ea;border-radius:8px;overflow:auto}.diff-head,.diff-row{min-width:900px;display:grid;grid-template-columns:90px 230px minmax(350px,1fr) 120px;align-items:center;gap:12px;padding:10px 14px}.diff-head{background:#f3f6f8;color:#6a7781;font-size:10px;font-weight:800}.diff-row{border-top:1px solid #edf0f2}.diff-row strong{width:max-content;padding:4px 8px;border-radius:10px;font-size:9px}.diff-row strong.added{background:#e4f6eb;color:#16894f}.diff-row strong.price{background:#fff0d7;color:#c97100}.diff-row strong.removed{background:#ffe9e7;color:#c7463b}.diff-row strong.rule{background:#e8f2fb;color:#307ba7}.diff-row small{color:#65727c}.diff-row em{color:#77909d;font-style:normal}.diff-row em.danger{color:#cc3f35;font-weight:800}.audit-note{display:grid;gap:7px;margin-top:16px;font-weight:800}.audit-note textarea{min-height:70px;resize:vertical;border:1px solid #d7e0e5;border-radius:7px;padding:10px;font:inherit}.removal-confirm{display:flex;align-items:center;gap:8px;margin-top:12px;padding:12px;border:1px solid #efc1bc;border-radius:7px;background:#fff3f1;color:#b83d33;font-weight:750}.history-modal{width:min(1040px,94vw);max-height:90vh}.history-list{display:grid;gap:10px;max-height:70vh;overflow:auto;padding:18px}.history-list article{display:grid;grid-template-columns:88px minmax(240px,1fr) 270px 160px;align-items:center;gap:15px;padding:15px;border:1px solid #e1e7ea;border-radius:9px}.version-status{width:max-content;padding:5px 9px;border-radius:12px;background:#eef2f4;color:#687681;font-size:9px;font-weight:800}.version-status.published{background:#e4f6eb;color:#16894f}.version-status.draft{background:#fff0d7;color:#c97100}.history-list article>div>b,.history-list article>div>small{display:block}.history-list article>div>small{margin-top:4px;color:#85919a;font-size:9px}.history-list p{margin:7px 0 0;color:#5d6b76;font-size:10px}.history-summary{display:flex;flex-wrap:wrap;gap:6px}.history-summary span{padding:4px 7px;border-radius:10px;background:#f2f5f7;color:#64727d;font-size:9px}.history-actions{display:flex;justify-content:flex-end;gap:8px}.history-actions button{border:0;background:none;color:#247cb0}.history-actions .rollback{color:#d67700;font-weight:800}.primary-orange:disabled{cursor:not-allowed;opacity:.45}@media(max-width:900px){.review-stats{grid-template-columns:repeat(3,minmax(0,1fr))}.history-list article{grid-template-columns:80px 1fr}.history-summary,.history-actions{grid-column:2}}
.change-chips{display:flex;flex-wrap:wrap;gap:5px}.change-chips button{height:32px;padding:0 9px;border:1px solid #dbe3e8;border-radius:15px;background:#fff;color:#687681;font-size:9px}.change-chips button.active{border-color:#ff9b1b;background:#fff1dd;color:#c96e00;font-weight:800}
.import-review-modal{width:min(1280px,96vw);max-height:94vh;display:flex;flex-direction:column}.import-review-modal>.review-body{flex:1;max-height:none;min-height:0;padding:18px 20px;overflow:auto}.review-first-import{display:flex;align-items:center;gap:9px;margin-bottom:14px;padding:10px 14px;border:1px solid #a8d9bd;border-radius:8px;background:#eefaf3;color:#53665b}.review-first-import b{color:#168652;font-size:12px}.review-first-import span{font-size:10px}.review-risk-banner{min-height:50px;box-sizing:border-box;display:flex;align-items:center;gap:12px;margin-bottom:14px;padding:10px 14px;border:1px solid #f3d29f;border-radius:8px;background:#fff9ef;color:#4b3a21}.review-risk-banner>span{display:flex;align-items:center;gap:6px;font-size:11px}.review-risk-banner>span>i{width:21px;height:21px;display:grid;place-items:center;border-radius:50%;background:#f29113;color:#fff;font-style:normal;font-weight:900}.review-risk-banner>span>b{color:#d54a3c;font-size:14px}.review-risk-banner>button{height:31px;margin-left:auto;padding:0 12px;border:1px solid #f1a23b;border-radius:6px;background:#fff;color:#d87900;font-size:10px;font-weight:800}.review-risk-banner>button.active{background:#ff9411;color:#fff}
.review-workspace{height:500px;min-height:430px;display:grid;grid-template-columns:minmax(320px,38%) minmax(0,1fr);overflow:hidden;border:1px solid #dfe6ea;border-radius:10px;background:#fff}.review-navigator{min-width:0;display:flex;flex-direction:column;border-right:1px solid #e3e9ec;background:#fafbfc}.review-country-search{height:49px;box-sizing:border-box;display:flex;align-items:center;gap:7px;margin:0;padding:0 14px;border-bottom:1px solid #e3e9ec;color:#87929a}.review-country-search input{width:100%;height:31px;border:0;outline:0;background:transparent;color:#27343d}.review-country-list{flex:1;min-height:0;overflow:auto}.review-country-group{border-bottom:1px solid #e7ecef}.review-country-group.selected>.review-country-head{background:#fffaf3}.review-country-head{width:100%;min-height:61px;box-sizing:border-box;display:flex;align-items:center;gap:9px;padding:10px 13px;border:0;background:#fff;color:#26343e;text-align:left}.review-country-head:hover{background:#fffaf3}.review-country-head>span{min-width:0;display:grid;gap:5px;flex:1}.review-country-head b{font-size:12px}.review-country-head b em{color:#6d7982;font-style:normal}.review-country-head small{color:#89949c;font-size:9px}.review-country-head small strong{color:#d4493d}.review-country-head>u{color:#85919a;text-decoration:none}.country-risk-count{width:20px;height:20px;display:grid;place-items:center;border-radius:50%;background:#e64e40;color:#fff;font-size:9px;font-style:normal;font-weight:900}.review-weight-list{padding:0 9px 8px;background:#f7f9fa}.review-weight-list>button{width:100%;min-height:52px;box-sizing:border-box;display:flex;align-items:center;gap:8px;margin-top:4px;padding:8px 10px;border:1px solid transparent;border-radius:7px;background:transparent;color:#40505b;text-align:left}.review-weight-list>button:hover{background:#fff}.review-weight-list>button.active{border-color:#f2b15b;background:#fff5e7;box-shadow:0 2px 8px rgba(226,132,15,.08)}.review-weight-list>button>span{min-width:0;display:grid;gap:4px;flex:1}.review-weight-list>button b{font-size:11px}.review-weight-list>button small{color:#8a959d;font-size:9px}.review-weight-list>button>i{width:16px;height:16px;display:grid;place-items:center;border:1px solid #b8c4ca;border-radius:50%;color:#fff;font-size:9px;font-style:normal}.review-weight-list>button>i.checked{border-color:#1a9c60;background:#1a9c60}.weight-risk{padding:3px 6px;border-radius:8px;background:#ffe8e5;color:#cd4136;font-size:8px;font-style:normal;font-weight:850}.review-nav-empty{display:grid;place-items:center;min-height:150px;padding:20px;color:#909ba3;text-align:center}.review-progress-legend{min-height:40px;justify-content:flex-start!important;gap:16px!important;padding:6px 14px!important;border-top:1px solid #e3e9ec!important;background:#fff!important;color:#74818a!important;font-size:9px}.review-progress-legend span{display:flex;align-items:center;gap:5px;margin:0}.review-progress-legend i{width:13px;height:13px;display:grid;place-items:center;border:1px solid #b8c4ca;border-radius:50%;font-size:8px;font-style:normal}.review-progress-legend i.done{border-color:#1a9c60;background:#1a9c60;color:#fff}
.review-detail{min-width:0;display:flex;flex-direction:column;padding:18px;background:#fff;overflow:auto}.review-detail-head{display:flex;align-items:flex-start;gap:15px;margin-bottom:14px}.review-detail-head>div:first-child{min-width:210px;flex:1}.review-detail-head h3{margin:0;color:#1c2a34;font-size:19px}.review-detail-head small{display:block;margin-top:5px;color:#929ca3;font-size:9px}.review-detail-badges{display:flex;align-items:center;justify-content:flex-end;flex-wrap:wrap;gap:6px}.review-detail-badges>span{padding:5px 8px;border:1px solid #dfe5e8;border-radius:12px;background:#f7f9fa;color:#65737d;font-size:8px;font-weight:800}.review-detail-badges>span.price{border-color:#f1d297;background:#fff2da;color:#ca7200}.review-detail-badges>span.added{border-color:#a9dfbf;background:#e9f8ef;color:#16834f}.review-detail-badges>span.removed,.review-detail-badges>span.high-risk{border-color:#f0aaa3;background:#fff0ee;color:#ce4438}.review-detail-badges>span.rule{border-color:#afd4e8;background:#eef8fd;color:#287aa6}.review-detail-badges>button{height:30px;padding:0 10px;border:1px solid #9bc7e5;border-radius:6px;background:#fff;color:#2b7fae;font-size:9px;font-weight:800}.review-detail-badges>button.reviewed{border-color:#9ad8b5;background:#eaf8ef;color:#188652}
.price-change-cards{display:grid;gap:10px}.price-change-card{min-height:104px;display:grid;grid-template-columns:42px minmax(0,1fr) 110px;align-items:center;gap:13px;padding:14px 16px;border:1px solid #e1e7ea;border-radius:9px;background:#fff}.price-change-card.risk.up,.price-change-card.risk.added{border-color:#efb0aa;background:#fffafa}.change-icon{width:39px;height:39px;display:grid;place-items:center;border-radius:9px;background:#fff0d9;color:#d77900;font-size:18px;font-weight:900}.change-main>b{display:block;margin-bottom:10px;font-size:12px}.change-main>div{display:grid;grid-template-columns:minmax(90px,1fr) 26px minmax(90px,1fr);align-items:end}.change-main>div>i{color:#839099;font-size:19px;font-style:normal;text-align:center}.change-main span{display:grid;gap:3px}.change-main small{color:#929ca3;font-size:8px}.change-main strong{color:#293741;font-size:17px}.price-change-card.down .after strong,.price-change-card.down .change-result{color:#168d55}.price-change-card.up .after strong,.price-change-card.up .change-result,.price-change-card.added .after strong,.price-change-card.added .change-result{color:#d64a3d}.change-result{display:grid;justify-items:end;gap:5px;color:#c97a12}.change-result>b{font-size:13px}.change-result>span{padding:3px 7px;border-radius:10px;background:#f1f4f5;font-size:9px;font-weight:800}.price-change-card.down .change-result>span{background:#e8f7ee}.price-change-card.up .change-result>span,.price-change-card.added .change-result>span{background:#ffece9}
.segment-change-card{display:flex;align-items:center;gap:12px;padding:17px;margin-top:10px;border:1px solid #b8e2ca;border-radius:9px;background:#f1fbf5}.segment-change-card.removed{border-color:#efb2ac;background:#fff3f1}.segment-change-card>i{width:32px;height:32px;display:grid;place-items:center;border-radius:50%;background:#1c9c61;color:#fff;font-size:20px;font-style:normal}.segment-change-card.removed>i{background:#d64a3d}.segment-change-card>span{display:grid;gap:4px}.segment-change-card b{font-size:12px}.segment-change-card small{color:#73818b}.rule-change-panel{margin-top:10px;overflow:hidden;border:1px solid #e1e7ea;border-radius:9px}.rule-change-panel>header{min-height:42px!important;padding:0 14px!important;border:0!important;background:#f8fafb!important}.rule-change-panel>header b{font-size:11px!important}.rule-change-panel>div{display:grid;grid-template-columns:minmax(130px,1fr) minmax(100px,1fr) 30px minmax(100px,1fr);align-items:center;gap:8px;padding:10px 14px;border-top:1px solid #edf1f3}.rule-change-panel>div>b{font-size:10px}.rule-change-panel>div>span{color:#687680}.rule-change-panel>div>i{color:#8a969e;font-style:normal;text-align:center}.rule-change-panel>div>strong{color:#cf7500}.review-detail>.review-detail-empty{flex:1;display:grid;place-items:center;color:#909ba3}.review-detail-progress{min-height:42px;margin-top:auto!important;justify-content:flex-start!important;gap:12px!important;padding:12px 0 0!important;border:0!important;background:#fff!important}.review-detail-progress>span{margin:0;color:#79858e;font-size:10px}.review-detail-progress>span b{color:#168d55;font-size:13px}.review-detail-progress>em{margin-left:auto;color:#cf493d;font-size:9px;font-style:normal;font-weight:800}.review-detail-progress>em.complete{color:#168d55}.review-no-change{display:grid;place-items:center;min-height:220px;border:1px dashed #d8e1e6;border-radius:9px;color:#8b969e}.review-body>.issue-list{margin-top:14px;margin-bottom:0}.review-modal-footer{flex:0 0 auto}.review-modal-footer>span{margin:0 auto 0 0;color:#c7473c;font-size:9px;font-weight:800}.review-modal-footer>span.ready{color:#168852}.review-modal-footer .primary-orange:disabled{cursor:not-allowed;opacity:.45}
.batch-modal{width:min(1120px,94vw);max-height:90vh;display:flex;flex-direction:column}.batch-body{min-height:0;overflow:auto;padding:18px}.batch-summary{display:flex;gap:10px;margin-bottom:14px}.batch-summary span{padding:8px 12px;border-radius:7px;background:#f1f5f7;color:#50616d}.batch-summary .danger{background:#fff0ee;color:#c7473c}.batch-list{display:grid;gap:8px}.batch-list article{display:grid;grid-template-columns:auto minmax(260px,1fr) minmax(250px,auto) minmax(100px,auto);align-items:center;gap:12px;padding:12px;border:1px solid #e1e7ea;border-radius:8px}.batch-list article.blocked{border-color:#efb7b1;background:#fff8f7}.batch-list article>input:disabled{cursor:not-allowed;opacity:.45}.batch-list article>div>b,.batch-list article>div>small{display:block}.batch-list article>div>small{margin-top:4px;color:#87939b}.batch-action{padding:5px 8px;border-radius:12px;background:#e9f7ef;color:#178651;font-size:9px;font-weight:850}.batch-action.create{background:#fff0d9;color:#c87200}.batch-warning,.batch-error{color:#c7463b;font-size:9px;font-style:normal;font-weight:800}.batch-ok{color:#168852;font-size:9px;font-style:normal;font-weight:800}.batch-review-check{display:flex;align-items:center;gap:6px;color:#bd4a3f;font-size:9px;font-weight:800}.batch-help{margin:0 0 14px;color:#65747e}.batch-modal .audit-note{margin-top:16px}
@media(max-width:960px){.import-review-modal{width:96vw}.review-workspace{height:auto;grid-template-columns:1fr}.review-navigator{max-height:320px;border-right:0;border-bottom:1px solid #e3e9ec}.review-detail{min-height:420px}.review-detail-head{flex-direction:column}.review-detail-badges{justify-content:flex-start}.review-stats{grid-template-columns:repeat(3,minmax(0,1fr))}.batch-list article{grid-template-columns:auto 1fr}.batch-list article>.history-summary,.batch-list article>em,.batch-list article>label{grid-column:2}}
@media(max-width:620px){.import-review-modal>.review-body{padding:12px}.review-stats{grid-template-columns:repeat(2,minmax(0,1fr))}.review-risk-banner{align-items:flex-start;flex-direction:column}.review-risk-banner>button{margin-left:0}.price-change-card{grid-template-columns:35px minmax(0,1fr)}.change-result{grid-column:2;justify-items:start}.review-modal-footer{flex-wrap:wrap}.review-modal-footer>span{width:100%}.review-modal-footer button{flex:1}}
.provider-detail-actions{flex-wrap:wrap;justify-content:flex-end}.provider-detail h3 em{display:inline-block;margin-left:6px;padding:3px 7px;border-radius:10px;background:#f0f2f4;color:#6f7a82;font-size:9px;font-style:normal}.provider-detail-actions .state-action,.provider-detail-actions .danger-action{height:36px;padding:0 12px;border-radius:6px;background:#fff;font-weight:750}.provider-detail-actions .state-action{border:1px solid #d6dde2;color:#596873}.provider-detail-actions .danger-action{border:1px solid #e7a59d;color:#c94f40}.template-table-head,.template-table-row{min-width:1000px;grid-template-columns:minmax(165px,1.35fr) minmax(150px,1.15fr) 90px 78px 126px 260px}.template-table-row>b small em{color:#c74d3d;font-style:normal}.template-actions .state-action{color:#6a737a}.template-actions .danger-action{color:#cf5142}.history-action-mask{z-index:120}.history-action-modal>div{display:grid;gap:16px;padding:22px}.history-action-modal p{margin:0;color:#53616b;line-height:1.7}.history-action-modal label{display:grid;gap:7px;color:#5f6d78;font-weight:750}.history-action-modal textarea{box-sizing:border-box;width:100%;padding:10px;border:1px solid #d7e0e6;border-radius:6px;resize:vertical;font:inherit;font-weight:400}.history-action-modal .danger-confirm{height:38px;padding:0 22px;border:0;border-radius:6px;background:#d94a3b;color:#fff;font-weight:850}
</style>
