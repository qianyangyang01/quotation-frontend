<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { ApiError } from '@/services/http'
import { createSupplierRecord, deleteSupplierRecord, loadSupplierRecords, removeSupplierBusinessLicense, updateSupplierRecord, uploadSupplierBusinessLicense, type NumericDraft, type SupplierRecord, type SupplierRecordDraft, type SupplierRecordInput } from '@/services/supplierRecords'
import SupplierRecordEditor from './SupplierRecordEditor.vue'
import { deliveryLabel, deliveryValueForRequest, invoiceNeedsTaxPoint, legacyDeliveryText, normalizeInvoiceType, normalizeQualityGrade, qualityLabel, SCORE_ITEM_LABELS, taxPointDecimalForInvoice, validDeliveryOption, type ScoreBreakdown } from './supplierRecordOptions'

const emit = defineEmits<{ close: []; notice: [message: string] }>()

const records = ref<SupplierRecord[]>([])
const loading = ref(true)
const loadError = ref('')
const query = ref('')
const industryBelt = ref('')
const rating = ref('')
const currentPage = ref(1)
const pageSize = ref(10)
const total = ref(0)
const totalPages = ref(1)
const editor = ref<SupplierRecordDraft | null>(null)
const editingRecord = ref<SupplierRecord | null>(null)
const expandedId = ref<string | null>(null)
const savedSnapshot = ref('')
const saving = ref(false)
const formError = ref('')
const pendingLicenseFile = ref<File | null>(null)
const removeLicenseRequested = ref(false)
const deleteTarget = ref<SupplierRecord | null>(null)
const deleting = ref(false)
const pendingDiscardAction = ref<(() => void) | null>(null)
let searchTimer = 0

const hasUnsavedChanges = computed(() => Boolean(editor.value) && (JSON.stringify(editor.value) !== savedSnapshot.value || Boolean(pendingLicenseFile.value) || removeLicenseRequested.value))
const canGoPrevious = computed(() => currentPage.value > 1 && !editor.value)
const canGoNext = computed(() => currentPage.value < totalPages.value && !editor.value)
const pageStart = computed(() => total.value ? (currentPage.value - 1) * pageSize.value + 1 : 0)
const pageEnd = computed(() => Math.min(currentPage.value * pageSize.value, total.value))

onMounted(load)
onUnmounted(() => window.clearTimeout(searchTimer))

watch(query, scheduleFilterReload)
watch(industryBelt, scheduleFilterReload)
watch(rating, scheduleFilterReload)
watch(pageSize, () => {
  if (editor.value) return
  currentPage.value = 1
  void load()
})

function scheduleFilterReload() {
  if (editor.value) return
  currentPage.value = 1
  window.clearTimeout(searchTimer)
  searchTimer = window.setTimeout(() => void load(), 300)
}

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const page = await loadSupplierRecords({
      query: query.value,
      industryBelt: industryBelt.value,
      rating: rating.value,
      page: currentPage.value - 1,
      size: pageSize.value,
    })
    records.value = page.items
    total.value = page.total
    totalPages.value = Math.max(1, page.totalPages)
    if (currentPage.value > totalPages.value) {
      currentPage.value = totalPages.value
      await load()
    }
  } catch (error) {
    loadError.value = message(error, '供应商记录读取失败')
  } finally {
    loading.value = false
  }
}

function emptyDraft(): SupplierRecordDraft {
  return {
    name: '', industryBelt: '', bossName: '', contactDetails: '', invoiceType: '', taxPointPercent: '',
    qualityGrade: '', deliveryTerms: '', capacityOrder: '', stockingStrategy: '', alternativeInquiry: '', corporateAccount: '', corporateBank: '',
    hotProductRecommendation: null, freeSample: null, afterSales: '', afterSalesAvailable: null, priceLevel: '', cooperationScore: '', rating: '待评价',
    monthlyPurchaseAmount: '', notes: '', suggestion: '', legacyDeliveryTerms: '',
  }
}

function draftOf(record: SupplierRecord): SupplierRecordDraft {
  const deliveryTerms = record.deliveryTerms.trim()
  const legacyDeliveryTerms = legacyDeliveryText(deliveryTerms)
  return {
    name: record.name, industryBelt: record.industryBelt, bossName: record.bossName,
    contactDetails: record.contactDetails, invoiceType: normalizeInvoiceType(record.invoiceType),
    taxPointPercent: record.taxPoint == null ? '' : Number((record.taxPoint * 100).toFixed(4)),
    qualityGrade: normalizeQualityGrade(record.qualityGrade), deliveryTerms: legacyDeliveryTerms ? '' : deliveryTerms, capacityOrder: record.capacityOrder,
    stockingStrategy: record.stockingStrategy, alternativeInquiry: record.alternativeInquiry, corporateAccount: record.corporateAccount, corporateBank: record.corporateBank,
    hotProductRecommendation: record.hotProductRecommendation, freeSample: record.freeSample, afterSales: record.afterSales,
    afterSalesAvailable: record.afterSalesAvailable, priceLevel: record.priceLevel,
    cooperationScore: record.cooperationScore ?? '', rating: record.rating || '待评价',
    monthlyPurchaseAmount: record.monthlyPurchaseAmount ?? '', notes: record.notes, suggestion: record.suggestion,
    legacyDeliveryTerms,
  }
}

function setEditor(draft: SupplierRecordDraft, record: SupplierRecord | null, id: string) {
  editor.value = draft
  editingRecord.value = record
  expandedId.value = id
  savedSnapshot.value = JSON.stringify(draft)
  formError.value = ''
  pendingLicenseFile.value = null
  removeLicenseRequested.value = false
}

function startCreate() { setEditor(emptyDraft(), null, 'new') }
function startEdit(record: SupplierRecord) { setEditor(draftOf(record), record, record.id) }
function clearEditor() {
  editor.value = null
  editingRecord.value = null
  expandedId.value = null
  savedSnapshot.value = ''
  formError.value = ''
  pendingLicenseFile.value = null
  removeLicenseRequested.value = false
}

function requestTransition(action: () => void) {
  if (saving.value || deleting.value) return
  if (hasUnsavedChanges.value) pendingDiscardAction.value = action
  else action()
}

function requestToggle(record: SupplierRecord) {
  if (expandedId.value === record.id) requestTransition(clearEditor)
  else requestTransition(() => startEdit(record))
}

function discardAndContinue() {
  const action = pendingDiscardAction.value
  pendingDiscardAction.value = null
  clearEditor()
  action?.()
}

function normalizeNumber(value: NumericDraft) {
  return typeof value === 'number' && Number.isFinite(value) ? value : null
}

function missingScoreLabels(items: string[]) {
  return items.map((item) => SCORE_ITEM_LABELS[item as keyof ScoreBreakdown] || item)
}

function inputOf(draft: SupplierRecordDraft): SupplierRecordInput {
  const taxPointPercent = normalizeNumber(draft.taxPointPercent)
  const invoiceType = normalizeInvoiceType(draft.invoiceType)
  return {
    name: draft.name.trim(), industryBelt: draft.industryBelt.trim(), bossName: draft.bossName.trim(),
    contactDetails: draft.contactDetails.trim(), invoiceType,
    taxPoint: taxPointDecimalForInvoice(invoiceType, taxPointPercent),
    qualityGrade: draft.qualityGrade.trim(), deliveryTerms: deliveryValueForRequest(draft.deliveryTerms, draft.legacyDeliveryTerms),
    capacityOrder: draft.capacityOrder.trim(), stockingStrategy: draft.stockingStrategy.trim(),
    alternativeInquiry: draft.alternativeInquiry.trim(), corporateAccount: draft.corporateAccount.trim(), corporateBank: draft.corporateBank.trim(),
    hotProductRecommendation: draft.hotProductRecommendation, freeSample: draft.freeSample,
    afterSales: draft.afterSales.trim(), afterSalesAvailable: draft.afterSalesAvailable, priceLevel: draft.priceLevel.trim(),
    cooperationScore: normalizeNumber(draft.cooperationScore),
    rating: draft.rating.trim(), monthlyPurchaseAmount: normalizeNumber(draft.monthlyPurchaseAmount),
    notes: draft.notes.trim(), suggestion: draft.suggestion.trim(),
  }
}

function validateDraft(draft: SupplierRecordDraft) {
  if (!draft.name.trim()) return '请填写供应商名称'
  const taxPoint = normalizeNumber(draft.taxPointPercent)
  if (invoiceNeedsTaxPoint(draft.invoiceType) && taxPoint == null) return `请填写${normalizeInvoiceType(draft.invoiceType)}票点`
  if (taxPoint != null && (taxPoint < 0 || taxPoint > 100)) return '票点必须在 0% 到 100% 之间'
  const amount = normalizeNumber(draft.monthlyPurchaseAmount)
  if (amount != null && amount < 0) return '预估每月采购额不能为负数'
  if (!validDeliveryOption(draft.deliveryTerms.trim())) return '请选择固定的交期选项'
  return ''
}

async function save() {
  if (!editor.value || saving.value) return
  const validation = validateDraft(editor.value)
  if (validation) { formError.value = validation; return }
  saving.value = true
  formError.value = ''
  try {
    const input = inputOf(editor.value)
    let saved = editingRecord.value
      ? await updateSupplierRecord(editingRecord.value, input)
      : await createSupplierRecord(input)
    if (pendingLicenseFile.value) saved = await uploadSupplierBusinessLicense(saved, pendingLicenseFile.value)
    else if (removeLicenseRequested.value && saved.businessLicenseAssetId) saved = await removeSupplierBusinessLicense(saved)
    clearEditor()
    await load()
    emit('notice', `${saved.name} 已保存`)
  } catch (error) {
    formError.value = error instanceof ApiError && error.status === 409
      ? '记录已被其他用户修改，请取消编辑并刷新后重试'
      : message(error, '供应商记录保存失败')
  } finally {
    saving.value = false
  }
}

function selectLicense(file: File | null) {
  if (file && file.size > 20 * 1024 * 1024) { formError.value = '营业执照图片不能超过 20MB'; return }
  pendingLicenseFile.value = file
  removeLicenseRequested.value = false
  formError.value = ''
}

function requestRemoveLicense() {
  pendingLicenseFile.value = null
  removeLicenseRequested.value = Boolean(editingRecord.value?.businessLicenseAssetId)
}

function requestDelete(record: SupplierRecord) {
  requestTransition(() => { deleteTarget.value = record })
}

async function confirmDelete() {
  if (!deleteTarget.value || deleting.value) return
  deleting.value = true
  try {
    const name = deleteTarget.value.name
    await deleteSupplierRecord(deleteTarget.value)
    deleteTarget.value = null
    if (records.value.length === 1 && currentPage.value > 1) currentPage.value -= 1
    await load()
    emit('notice', `${name} 已删除`)
  } catch (error) {
    emit('notice', error instanceof ApiError && error.status === 409 ? '记录已变化，请刷新后重试' : message(error, '供应商记录删除失败'))
  } finally {
    deleting.value = false
  }
}

function goToPage(page: number) {
  if (editor.value || page < 1 || page > totalPages.value || page === currentPage.value) return
  currentPage.value = page
  void load()
}

function display(value: string | null | undefined) { return value?.trim() || '暂无数据' }
function invoiceLabel(value: string | null | undefined) { return display(normalizeInvoiceType(value)) }
function taxLabel(value: number | null) { return value == null ? '暂无数据' : `${Number((value * 100).toFixed(4))}%` }
function money(value: number | null) { return value == null ? '暂无数据' : `¥${value.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}` }
function dateTime(value: string) { const date = new Date(value); return Number.isNaN(date.getTime()) ? '暂无数据' : date.toLocaleString('zh-CN', { hour12: false }) }
function message(error: unknown, fallback: string) { return error instanceof Error && error.message ? error.message : fallback }
</script>

<template>
  <section class="supplier-tabs" aria-label="采购资料视图切换">
    <button @click="emit('close')">采购资料</button>
    <button class="active">供应商记录</button>
  </section>

  <section class="supplier-records-notice">
    <b>ⓘ</b><span>供应商记录为独立信息留档，暂不参与报价、采购资料或其他业务联动</span>
  </section>

  <section class="supplier-toolbar">
    <label class="supplier-search">⌕<input v-model="query" :disabled="Boolean(editor)" placeholder="搜索供应商名称、老板姓名或联系方式"></label>
    <label><span>产业带</span><input v-model="industryBelt" :disabled="Boolean(editor)" placeholder="全部"></label>
    <label><span>评级</span><select v-model="rating" :disabled="Boolean(editor)"><option value="">全部</option><option>待评价</option><option>A级</option><option>B级</option><option>C级</option></select></label>
    <button class="supplier-add" :disabled="Boolean(editor)" @click="requestTransition(startCreate)">＋ 新增一项</button>
  </section>

  <section class="supplier-card">
    <header>
      <div><b>核心供应商</b><span>月采购金额 1 万元以上</span></div>
      <span v-if="editor">请先保存或取消当前编辑，再使用筛选与分页</span>
    </header>

    <div v-if="loadError" class="supplier-load-state error"><b>供应商记录加载失败</b><span>{{ loadError }}</span><button @click="load">重试</button></div>
    <div v-else-if="loading" class="supplier-load-state"><b>正在读取供应商记录…</b></div>
    <div v-else class="supplier-table-wrap">
      <table class="supplier-table">
        <thead><tr><th>展开</th><th>供应商名称</th><th>产业带</th><th>老板姓名</th><th>开票 / 票点</th><th>质量</th><th>交期（天）</th><th>评级</th><th>综合评分</th><th>预估每月采购额</th><th>更新时间</th><th>操作</th></tr></thead>
        <tbody>
          <template v-if="expandedId === 'new' && editor">
            <tr class="supplier-summary new"><td>⌄</td><td colspan="10"><b>新增供应商记录</b><small>仅供应商名称必填</small></td><td></td></tr>
            <tr class="supplier-detail-row"><td colspan="12"><SupplierRecordEditor v-model="editor" :saving="saving" :error="formError" :pending-license-file="pendingLicenseFile" :remove-license="removeLicenseRequested" @license-select="selectLicense" @remove-license="requestRemoveLicense" @save="save" @cancel="requestTransition(clearEditor)" /></td></tr>
          </template>
          <template v-for="record in records" :key="record.id">
            <tr class="supplier-summary" :class="{ expanded: expandedId === record.id }" @click="requestToggle(record)">
              <td><button class="expand-button" :aria-label="expandedId === record.id ? '收起' : '展开'">{{ expandedId === record.id ? '⌄' : '›' }}</button></td>
              <td><b>{{ record.name }}</b><small>{{ display(record.contactDetails) }}</small></td>
              <td>{{ display(record.industryBelt) }}</td><td>{{ display(record.bossName) }}</td>
              <td><b>{{ invoiceLabel(record.invoiceType) }}</b><small>{{ normalizeInvoiceType(record.invoiceType) === '没票' ? '无需票点' : taxLabel(record.taxPoint) }}</small></td>
              <td>{{ qualityLabel(record.qualityGrade) }}</td><td>{{ deliveryLabel(record.deliveryTerms) }}</td>
              <td><em :class="record.rating === 'A级' ? 'rating-a' : ''">{{ display(record.rating) }}</em></td>
              <td><strong class="score" :class="{ pending: record.scoreStatus !== 'COMPLETE' }" :title="record.scoreStatus === 'PENDING' ? `待补充：${missingScoreLabels(record.missingScoreItems).join('、')}` : `评分规则：${record.scorePolicyVersion || '—'}`">{{ record.scoreStatus === 'COMPLETE' ? (record.calculatedScore ?? '—') : '待评分' }}</strong><small v-if="record.cooperationScore != null">历史人工：{{ record.cooperationScore }}</small></td>
              <td>{{ money(record.monthlyPurchaseAmount) }}</td><td>{{ dateTime(record.updatedAt) }}</td>
              <td class="supplier-actions"><button @click.stop="requestToggle(record)">编辑</button><button class="danger" @click.stop="requestDelete(record)">删除</button></td>
            </tr>
            <tr v-if="expandedId === record.id && editor" class="supplier-detail-row"><td colspan="12"><SupplierRecordEditor v-model="editor" :saving="saving" :error="formError" :license-url="removeLicenseRequested ? '' : editingRecord?.businessLicenseUrl" :pending-license-file="pendingLicenseFile" :remove-license="removeLicenseRequested" @license-select="selectLicense" @remove-license="requestRemoveLicense" @save="save" @cancel="requestTransition(clearEditor)" /></td></tr>
          </template>
        </tbody>
      </table>
      <div v-if="!records.length && expandedId !== 'new'" class="supplier-load-state"><b>暂无供应商记录</b><span>点击“＋ 新增一项”开始记录。</span></div>
    </div>

    <footer class="supplier-pagination">
      <span>共 {{ total }} 条<span v-if="total"> · 当前 {{ pageStart }}–{{ pageEnd }} 条</span></span>
      <div><button :disabled="!canGoPrevious" @click="goToPage(currentPage - 1)">‹</button><b>{{ currentPage }} / {{ totalPages }}</b><button :disabled="!canGoNext" @click="goToPage(currentPage + 1)">›</button><select v-model.number="pageSize" :disabled="Boolean(editor)"><option :value="10">10条/页</option><option :value="20">20条/页</option><option :value="50">50条/页</option></select></div>
    </footer>
  </section>

  <Teleport to="body">
    <div v-if="pendingDiscardAction" class="supplier-mask">
      <section class="supplier-confirm"><h3>放弃未保存修改？</h3><p>当前填写内容尚未保存，继续后这些修改会丢失。</p><footer><button @click="pendingDiscardAction=null">继续编辑</button><button class="danger-solid" @click="discardAndContinue">放弃修改</button></footer></section>
    </div>
    <div v-if="deleteTarget" class="supplier-mask">
      <section class="supplier-confirm"><h3>确认删除供应商记录？</h3><p>将删除“{{ deleteTarget.name }}”。此操作不会影响采购商品或报价，但删除后无法从页面恢复。</p><footer><button :disabled="deleting" @click="deleteTarget=null">取消</button><button class="danger-solid" :disabled="deleting" @click="confirmDelete">{{ deleting ? '删除中…' : '确认删除' }}</button></footer></section>
    </div>
  </Teleport>
</template>

<style scoped>
.supplier-tabs{display:flex;gap:24px;margin:-4px 0 14px;border-bottom:1px solid #dfe5e9}.supplier-tabs button{padding:11px 4px;border:0;border-bottom:2px solid transparent;background:none;color:#53616b;font-weight:800}.supplier-tabs button.active{border-color:#ff8a00;color:#d66f00}.supplier-records-notice{display:flex;align-items:center;gap:9px;margin-bottom:14px;padding:12px 15px;border:1px solid #ffd2a1;border-radius:8px;background:#fff7ed;color:#745331;font-size:12px}.supplier-records-notice b{color:#ef8500;font-size:16px}.supplier-toolbar{display:flex;align-items:center;gap:10px;margin-bottom:12px}.supplier-toolbar label{display:flex;align-items:center;gap:7px;height:38px;padding:0 10px;border:1px solid #dce3e8;border-radius:7px;background:#fff;color:#697680;font-size:11px}.supplier-toolbar input,.supplier-toolbar select{min-width:100px;border:0;outline:0;background:transparent}.supplier-search{width:320px}.supplier-search input{width:100%}.supplier-add{height:40px;margin-left:auto;padding:0 17px;border:0;border-radius:7px;background:#ff8a00;color:#17212b;font-weight:900}.supplier-toolbar :disabled{cursor:not-allowed;opacity:.55}.supplier-card{border:1px solid #dfe5e9;border-radius:10px;background:#fff;overflow:hidden}.supplier-card>header{display:flex;align-items:center;justify-content:space-between;padding:14px 16px;border-bottom:1px solid #e6ebee}.supplier-card>header div{display:grid;gap:4px}.supplier-card>header b{font-size:15px}.supplier-card>header span{color:#7b8790;font-size:10px}.supplier-table-wrap{overflow-x:auto}.supplier-table{width:100%;min-width:1320px;border-collapse:collapse}.supplier-table th{padding:11px 9px;background:#f6f8f9;color:#697781;font-size:10px;text-align:left;white-space:nowrap}.supplier-table td{padding:11px 9px;border-top:1px solid #e8ecef;color:#33414a;font-size:11px;vertical-align:middle}.supplier-summary{cursor:pointer}.supplier-summary:hover,.supplier-summary.expanded{background:#fff8ee}.supplier-summary.new{background:#fff8ee}.supplier-summary td:first-child{width:42px;text-align:center}.supplier-summary td:nth-child(2){min-width:180px}.supplier-summary b,.supplier-summary small{display:block}.supplier-summary small{margin-top:4px;color:#85919a;font-size:9px}.expand-button{border:0;background:none;color:#5b6871;font-size:20px}.supplier-summary em{display:inline-block;padding:3px 7px;border-radius:9px;background:#edf1f3;color:#61707a;font-size:9px;font-style:normal}.supplier-summary em.rating-a{background:#fff0dc;color:#c66a00}.score{display:inline-grid;place-items:center;min-width:30px;height:24px;padding:0 7px;border:1px solid #ffd1a0;border-radius:12px;color:#e37800}.score.pending{border-color:#d9dfe3;color:#78858e}.supplier-actions{white-space:nowrap}.supplier-actions button{border:0;background:none;color:#1670c8;font-weight:800}.supplier-actions .danger{color:#d84b40}.supplier-detail-row>td{padding:8px;background:#f7f9fa}.supplier-editor{padding:14px;border:1px solid #e1e6e9;border-radius:8px;background:#fff}.supplier-editor fieldset{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px;margin:0 0 12px;padding:12px;border:1px solid #e5eaed;border-radius:7px}.supplier-editor legend{padding:0 7px;color:#23313b;font-size:12px;font-weight:900}.supplier-editor label{display:grid;gap:5px;color:#596771;font-size:10px}.supplier-editor input,.supplier-editor select,.supplier-editor textarea{box-sizing:border-box;width:100%;min-height:35px;padding:7px 9px;border:1px solid #dce3e8;border-radius:6px;background:#fff;color:#26343d;font:inherit}.supplier-editor textarea{min-height:64px;resize:vertical}.supplier-editor .wide{grid-column:1/-1}.supplier-editor>footer{display:flex;justify-content:flex-end;gap:8px}.supplier-editor>footer button{height:36px;padding:0 14px;border:1px solid #dce3e8;border-radius:6px;background:#fff;font-weight:800}.supplier-editor>footer .save{border-color:#ff8a00;background:#ff8a00;color:#17212b}.supplier-form-error{padding:9px 11px;border-radius:6px;background:#fff0ee;color:#b33830;font-size:11px}.supplier-load-state{display:grid;justify-items:center;gap:7px;padding:54px;color:#78858f}.supplier-load-state.error b{color:#b53b33}.supplier-load-state button{height:34px;padding:0 13px;border:1px solid #ff9a24;border-radius:6px;background:#fff;color:#a65d00}.supplier-pagination{display:flex;align-items:center;justify-content:space-between;padding:12px 14px;border-top:1px solid #e5eaed;color:#6e7b84;font-size:10px}.supplier-pagination div{display:flex;align-items:center;gap:8px}.supplier-pagination button,.supplier-pagination select{height:32px;border:1px solid #dce3e8;border-radius:6px;background:#fff}.supplier-pagination button{width:32px}.supplier-mask{position:fixed;z-index:120;inset:0;display:grid;place-items:center;padding:20px;background:rgba(17,24,39,.46);backdrop-filter:blur(2px)}.supplier-confirm{width:min(430px,94vw);padding:22px;border-radius:10px;background:#fff;box-shadow:0 22px 65px rgba(17,24,39,.28)}.supplier-confirm h3{margin:0 0 9px}.supplier-confirm p{color:#66737c;line-height:1.65}.supplier-confirm footer{display:flex;justify-content:flex-end;gap:8px;margin-top:18px}.supplier-confirm button{height:38px;padding:0 14px;border:1px solid #dce3e8;border-radius:6px;background:#fff;font-weight:800}.supplier-confirm .danger-solid{border-color:#d94b40;background:#d94b40;color:#fff}@media(max-width:900px){.supplier-toolbar{flex-wrap:wrap}.supplier-search{width:100%}.supplier-add{margin-left:0}.supplier-card>header{align-items:start;flex-direction:column;gap:6px}.supplier-editor fieldset{grid-template-columns:1fr}.supplier-editor .wide{grid-column:auto}}
</style>
