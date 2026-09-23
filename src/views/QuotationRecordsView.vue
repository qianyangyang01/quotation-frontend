<script setup lang="ts">
import QuotationLifecycleDialog from '@/components/quotation/QuotationLifecycleDialog.vue'
import { changeQuotationLifecycle, lifecycleLabel, lifecycleProtection, type RecordLifecycle, type LifecycleAction } from '@/data/quotationLifecycle'
import { operationFeesLabel } from '@/data/customerOperationFees'
import { useQuotationReviewSync } from '@/composables/useQuotationReviewSync'
import { reviewQuotationRecord, financeReviewLabel, type ReviewAction } from '@/data/quotationRecords'
import QuotationReviewPanel from '@/components/quotation/QuotationReviewPanel.vue'
import QuotationReviewButton from '@/components/quotation/QuotationReviewButton.vue'
import QuotationReviewHistory from '@/components/quotation/QuotationReviewHistory.vue'
import QuotationWeightTrace from '@/components/quotation/QuotationWeightTrace.vue'
import QuotationProductCostTrace from '@/components/quotation/QuotationProductCostTrace.vue'
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { customerGradeDisplayLabel } from '@/data/financeChannelPolicies'
import { updateQuotationRecord, type QuotationRecord, type QuotationRecordDealLine, type QuotationRecordQuoteOption, type QuotationRecordStatus } from '@/data/quotationRecords'
import { loadPurchaseProducts } from '@/data/purchaseStore'
import { quotationProductCategories } from '@/components/quotation/types'
import QuotationProductImage from '@/components/quotation/QuotationProductImage.vue'
import PurchaseCategoryBadge from '@/components/purchase/PurchaseCategoryBadge.vue'
import QuotationRecordCopyActions from '@/components/quotation/QuotationRecordCopyActions.vue'
import { representativePriceDifference } from '@/data/customerQuotePrices'
import CustomerPriceComparison from '@/components/quotation/CustomerPriceComparison.vue'
import CustomerPriceRevision from '@/components/quotation/CustomerPriceRevision.vue'
import { currentAuthUser, hasPermission } from '@/data/authStore'

import { loadRecordPage, loadFilteredRecords, loadRecord, recentRecordDates } from '@/data/quotationRecordQuery'
import { quotationDetailsCsv } from '@/data/quotationAnalytics'

const props = defineProps<{ scope: 'mine' | 'company' }>()
const route = useRoute()
const records = ref<QuotationRecord[]>([])
const purchaseProducts = ref<Awaited<ReturnType<typeof loadPurchaseProducts>>>([])
const lifecycle = ref<RecordLifecycle>('active')
const checkedIds = ref<string[]>([])
const lifecycleAction = ref<LifecycleAction | null>(null)
const confirmationRows = ref<QuotationRecord[]>([])
const lifecycleBusy = ref(false)
const lifecycleError = ref('')
const lifecycleAdmin = computed(() => currentAuthUser.value.role === 'super_admin' && hasPermission('allRecords'))
const canManageLifecycle = computed(() => lifecycleAdmin.value || props.scope === 'mine')
function isActive(row: QuotationRecord) { return (reviewSync.stateFor(row).lifecycleState || row.lifecycleState || 'active') === 'active' }
function selectionBlocked(row: QuotationRecord) {
  if (!canManageLifecycle.value || (!lifecycleAdmin.value && row.salespersonAccount !== currentAuthUser.value.account)) return '只能处理自己的报价记录'
  if (row._version == null) return '记录版本缺失，请刷新'
  if (lifecycle.value === 'active') return lifecycleProtection({...row,...reviewSync.stateFor(row)})
  if (!lifecycleAdmin.value && row.lifecycleChangedAccount !== currentAuthUser.value.account) return '请由管理员恢复此记录'
  return ''
}
const selectableRows = computed(() => records.value.filter(row => !selectionBlocked(row)))
const allPageChecked = computed(() => selectableRows.value.length > 0 && selectableRows.value.every(row => checkedIds.value.includes(row.id)))
function togglePage() { checkedIds.value = allPageChecked.value ? [] : selectableRows.value.map(row => row.id) }
function beginLifecycle(action: LifecycleAction) {
  if (lifecycleBusy.value || loading.value) return
  const rows = records.value.filter(row => checkedIds.value.includes(row.id))
  if (!rows.length) return
  const blocked = rows.map(row => selectionBlocked(row) || (action === 'trash' ? lifecycleProtection({...row,...reviewSync.stateFor(row)}) : '')).find(Boolean)
  if (blocked) { toast(blocked); return }
  confirmationRows.value = rows.map(row => ({...row}))
  lifecycleError.value = ''; lifecycleAction.value = action
}
async function confirmLifecycle(reason: string) {
  if (!lifecycleAction.value || lifecycleBusy.value || !reason.trim()) return
  lifecycleBusy.value = true; lifecycleError.value = ''
  const account = currentAuthUser.value.account
  try {
    const result = await changeQuotationLifecycle(lifecycleAction.value, reason, confirmationRows.value)
    if (account !== currentAuthUser.value.account) return
    lifecycleAction.value = null; checkedIds.value = []; selected.value = null
    toast('已处理 ' + result.changed + ' 条报价记录')
    await refresh()
  } catch (error) {
    lifecycleError.value = error instanceof Error ? error.message : '操作失败，请刷新后重新选择'
  } finally { lifecycleBusy.value = false }
}
const search = ref('')
const filterStatus = ref<'' | 'pending' | 'won' | 'processed' | 'finance-pending' | 'finance-approved' | 'finance-rejected' | 'finance-reviewing' | 'finance-mine'>('')
const filterCountry = ref('')
const filterCategory = ref('')
const startDate=ref('');const endDate=ref('');const page=ref(0);const pageSize=ref(10)
const total=ref(0);const totalPages=ref(0);const loading=ref(false);const loadError=ref('');const exporting=ref(false)
const summary=ref<{pending:number;won:number;lost:number;total:number;processed?:number}>({pending:0,won:0,lost:0,total:0});const countries=ref<string[]>([])
const filters=computed(()=>({lifecycle:lifecycle.value,q:search.value.trim(),status:filterStatus.value,country:filterCountry.value,category:filterCategory.value,startDate:startDate.value,endDate:endDate.value}))
const dateError=computed(()=>startDate.value && endDate.value && startDate.value>endDate.value ? '开始日期不能晚于结束日期' : '')
let requestId=0;let refreshTimer:ReturnType<typeof setTimeout>|undefined
async function refresh(silent = false) {
  const id=++requestId
  if (!silent) checkedIds.value=[]
  if(dateError.value) { loading.value=false;records.value=[];total.value=0;totalPages.value=0;summary.value={pending:0,won:0,lost:0,total:0};return }
  if (!silent) loading.value=true;loadError.value=''
  try {
    const result=await loadRecordPage(props.scope,{...filters.value},page.value,pageSize.value)
    if(id!==requestId)return
    records.value=result.items;total.value=result.total;totalPages.value=result.totalPages;page.value=result.page;summary.value=result.summary;countries.value=result.countries
    checkedIds.value=checkedIds.value.filter(id=>records.value.some(row=>row.id===id&&!selectionBlocked(row)))
  } catch(error) {if(id===requestId){records.value=[];total.value=0;totalPages.value=0;summary.value={pending:0,won:0,lost:0,total:0};loadError.value=error instanceof Error?error.message:'加载失败，请重试'}}
  finally {if(id===requestId)loading.value=false}
}
function resetFilters(){search.value='';filterStatus.value='';filterCountry.value='';filterCategory.value='';startDate.value='';endDate.value=''}
function recent(days:number){const dates=recentRecordDates(days);startDate.value=dates.startDate;endDate.value=dates.endDate}
function changePage(next:number){if(loading.value)return;page.value=next;void refresh()}
async function exportRecords(){
  if(exporting.value || dateError.value)return
  exporting.value=true
  try {
    const rows=await loadFilteredRecords(props.scope,{...filters.value})
    const blob=new Blob([quotationDetailsCsv(rows,purchaseProducts.value)],{type:'text/csv;charset=utf-8'})
    const url=URL.createObjectURL(blob);const link=document.createElement('a');link.href=url;link.download=title.value+'-'+recentRecordDates(1).endDate+'.csv';link.click();setTimeout(()=>URL.revokeObjectURL(url),1000)
    toast('已导出当前筛选范围全部 '+rows.length+' 条记录')
  }catch(error){toast(error instanceof Error?error.message:'导出失败，请重试')}finally{exporting.value=false}
}
watch([filters,pageSize,()=>props.scope,()=>currentAuthUser.value.account],()=>{checkedIds.value=[];lifecycleAction.value=null;++requestId;page.value=0;records.value=[];total.value=0;totalPages.value=0;summary.value={pending:0,won:0,lost:0,total:0};selected.value=null;loading.value=true;clearTimeout(refreshTimer);refreshTimer=setTimeout(()=>void refresh(),250)})
onUnmounted(()=>{++requestId;clearTimeout(refreshTimer)})
const selected = ref<QuotationRecord | null>(null)
const reviewSync = useQuotationReviewSync(records, selected, computed(() => currentAuthUser.value.account))
watch(() => records.value.map(row => reviewSync.stateFor(row).lifecycleState || row.lifecycleState || 'active').join(','), () => {
  if (records.value.some(row => (reviewSync.stateFor(row).lifecycleState || row.lifecycleState || 'active') !== (row.lifecycleState || 'active'))) {
    selected.value = null; void refresh(true)
  }
})
const canReview = computed(() => ['super_admin','finance'].includes(currentAuthUser.value.role) && hasPermission('allRecords'))
const reviewing = ref(new Set<string>())
async function changeReview(row: QuotationRecord, action: ReviewAction) {
  if (!canReview.value || !isActive(row) || lifecycleBusy.value || reviewing.value.has(row.id)) return
  const account = currentAuthUser.value.account
  reviewing.value.add(row.id)
  try {
    const saved = await reviewQuotationRecord(row.id, action, row._version, action.action==='claim'?reviewSync.stateFor(row)._reviewVersion:row._reviewVersion)
    if (account !== currentAuthUser.value.account) return
    reviewSync.accept(saved)
    records.value = records.value.map(item => item.id === saved.id ? saved : item)
    if (action.action==='claim') open(saved)
    else if (selected.value?.id === saved.id && !editing.value) selected.value = saved
    toast(action.action==='claim'?'已开始审核，其他财务可看到你的占用':action.action==='complete'?'审核结果已保存':'已取消占用，其他财务可以开始审核')
    if (filterStatus.value.startsWith('finance-')) await refresh(true)
  } catch (error) {
    if (account !== currentAuthUser.value.account) return
    toast(error instanceof Error ? error.message : '审核操作失败，请重试')
    void reviewSync.poll()
  } finally { reviewing.value.delete(row.id) }
}
async function reloadReview(row:QuotationRecord) {
  const account=currentAuthUser.value.account
  try { const fresh=await loadRecord(row.id);if(fresh&&selected.value?.id===row.id&&account===currentAuthUser.value.account&&!editing.value){selected.value=fresh;reviewSync.accept(fresh)} }
  catch(error) {toast(error instanceof Error?error.message:'详情加载失败，请重试')}
}
let reviewListTimer:ReturnType<typeof setInterval>|undefined
onMounted(()=>{reviewListTimer=setInterval(()=>{if(document.visibilityState!=='hidden'&&filterStatus.value.startsWith('finance-')&&!loading.value)void refresh(true)},15000)})
onUnmounted(()=>clearInterval(reviewListTimer))
const editing = ref(false)
const detailTab = ref<'overview' | 'options' | 'history'>('overview')
const notice = ref('')
const dealLineEditor = ref<HTMLElement | null>(null)
interface DealLineForm { id: string; optionId: string; unitPriceUsd: string; quantity: string }
const form = reactive({ status: 'won' as 'won' | 'lost', dealLines: [] as DealLineForm[], date: new Date().toISOString().slice(0, 10), note: '' })
const isMine = computed(() => props.scope === 'mine')
const title = computed(() => isMine.value ? '我的报价记录' : '报价记录')
const list = computed(() => records.value)
function recordSku(row: QuotationRecord) {
  if (row.quoteMode === 'bundle' && row.bundleItems?.length) return row.bundleItems.map(item => item.sku).join('+')
  return row.quoteMode === 'bundle' ? row.primarySku.replace(/[、,，]/g, '+') : row.primarySku
}
function recordCommission(row: QuotationRecord) {
  const value = row.commissionThreshold === undefined ? 1 : row.commissionThreshold
  if (typeof value !== 'number' || !Number.isFinite(value) || value <= 0 || value > 1) return '佣金阈值：未保存有效值'
  return `佣金阈值：${value} · ${value < 1 ? '已加佣金' : '未加佣金'}`
}
function recordOptions(row: QuotationRecord) { return row.quoteOptions || [] }
function optionLabel(option: QuotationRecordQuoteOption) { return `${option.country}${option.quoteRegion ? `（${option.quoteRegion}）` : ''} · ${option.channel}${option.carrier && option.carrier !== option.channel ? ` · ${option.carrier}` : ''}` }
function recordCountries(row: QuotationRecord) { return [...new Set(recordOptions(row).map(option => option.country).filter(country => country && country !== '—'))] }
function hasMultipleOptions(row: QuotationRecord) { return recordOptions(row).length > 1 }
function primaryOption(row: QuotationRecord) { return recordOptions(row).find(option => option.isPrimary) || recordOptions(row)[0] }
function numericRange(values: number[], prefix = '') {
  const sorted = [...new Set(values.filter(Number.isFinite))].sort((a, b) => a - b)
  if (!sorted.length) return '—'
  const first = `${prefix}${sorted[0].toFixed(2)}`
  return sorted.length === 1 ? first : `${first} ~ ${prefix}${sorted[sorted.length - 1].toFixed(2)}`
}
function quote1UsdRange(row: QuotationRecord) { return numericRange(recordOptions(row).flatMap(option => option.quote1Usd == null ? [] : [option.quote1Usd]), '$') }
function quote1CnyRange(row: QuotationRecord) { return numericRange(recordOptions(row).flatMap(option => option.quote1Usd == null ? [] : [option.quote1Usd * row.exchangeRate]), '¥') }
function optionPrice(value: number | null) { return value == null ? '—' : usd(value) }

function canEditPrices(row:QuotationRecord) { return isActive(row) && row.salespersonAccount===currentAuthUser.value.account }
function pricesSaved(row:QuotationRecord) {
  const index=records.value.findIndex(item=>item.id===row.id)
  if(index>=0) records.value[index]=row
  if(selected.value?.id===row.id) selected.value=row
  toast(row.status==='won' ? '报价记录已更新，状态为已成交' : row.quoteConfirmed ? '报价已确认，已标记为已处理' : '客户报价已保存，请核对后确认报价'); void refresh()
}
const revisionGroups = computed(() => {
  const groups = new Map<string, { id: string; changedAt: string; editorName: string; editorAccount: string; changes: QuotationRecord['revisions'] }>()
  for (const revision of selected.value?.revisions || []) {
    const key = `${revision.changedAt}-${revision.editorAccount}`
    const group = groups.get(key)
    if (group) group.changes.push(revision)
    else groups.set(key, { id: key, changedAt: revision.changedAt, editorName: revision.editorName, editorAccount: revision.editorAccount, changes: [revision] })
  }
  return [...groups.values()].reverse()
})
const selectedOptionGroups = computed(() => {
  const groups = new Map<string, { country: string; countryCode?: string; options: QuotationRecordQuoteOption[] }>()
  for (const option of selected.value ? recordOptions(selected.value) : []) {
    const group = groups.get(option.country)
    if (group) group.options.push(option)
    else groups.set(option.country, { country: option.country, countryCode: option.countryCode, options: [option] })
  }
  return [...groups.values()]
})
const selectedDealOptionIds = computed(() => new Set((selected.value?.dealLines || []).map(line => line.optionId)))
const formDealTotalQuantity = computed(() => form.dealLines.reduce((sum, line) => sum + (Number(line.quantity) || 0), 0))
const formDealTotalUsd = computed(() => form.dealLines.reduce((sum, line) => sum + (Number(line.unitPriceUsd) || 0) * (Number(line.quantity) || 0), 0))
const pending = computed(() => summary.value.pending)
const won = computed(() => summary.value.won)
const cny = (value: number) => `¥${value.toFixed(2)}`
const usd = (value: number) => `$${value.toFixed(2)}`
function displayStatus(row:QuotationRecord) { return row.status==='won' ? '已成交' : row.quoteConfirmed ? '已处理' : '待处理' }
const statusText = (value: QuotationRecordStatus) => ({ pending: '待处理', won: '已成交', lost: '未成交' })[value]
const dateTime = (value?: string) => {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false, timeZone: 'Asia/Shanghai' }).format(date)
}
function recordPurchaseProduct(row: QuotationRecord) {
  const primarySku = row.primarySku.split(/[、,+\s]/).find(Boolean) || row.primarySku
  return purchaseProducts.value.find(item => item.sku === primarySku)
}
let filteredSyncTimer: ReturnType<typeof setInterval> | undefined
let filteredSyncBusy = false
onUnmounted(() => clearInterval(filteredSyncTimer))
onMounted(async () => {
  filteredSyncTimer = setInterval(async () => {
    if (!filterStatus.value.startsWith('finance-') || loading.value || filteredSyncBusy || document.visibilityState === 'hidden') return
    filteredSyncBusy = true
    try { await refresh(true) } finally { filteredSyncBusy = false }
  }, 3000)
  await Promise.allSettled([refresh(),loadPurchaseProducts().then(rows=>{purchaseProducts.value=rows})])
})
watch(()=>[route.query.record,props.scope],async()=>{
  const id=route.query.record;if(props.scope!=='company' || typeof id!=='string')return
  try{const record=await loadRecord(id);if(props.scope==='company' && route.query.record===id && record)open(record)}catch(error){toast(error instanceof Error?error.message:'报价记录加载失败')}
},{immediate:true})
function fillForm(row: QuotationRecord) {
  form.status = row.status === 'lost' ? 'lost' : 'won'
  form.dealLines = (row.dealLines || []).map(line => ({ id: line.id, optionId: line.optionId, unitPriceUsd: line.unitPriceUsd.toFixed(2), quantity: String(line.quantity) }))
  if (!form.dealLines.length && recordOptions(row).length === 1 && recordOptions(row)[0].available !== false) form.dealLines.push({ id: `draft-${Date.now()}`, optionId: recordOptions(row)[0].id, unitPriceUsd: '', quantity: '' })
  form.date = row.closedAt || new Date().toISOString().slice(0, 10)
  form.note = row.note || ''
}
async function addDealLine() {
  if (!selected.value) return
  const used = new Set(form.dealLines.map(line => line.optionId))
  const option = recordOptions(selected.value).find(item => item.available !== false && !used.has(item.id))
  if (!option) { toast('报价单中的渠道已经全部添加'); return }
  form.dealLines.push({ id: `draft-${Date.now()}-${form.dealLines.length}`, optionId: option.id, unitPriceUsd: '', quantity: '' })
  await nextTick()
  dealLineEditor.value?.querySelector('article:last-of-type')?.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
}
function removeDealLine(index: number) { form.dealLines.splice(index, 1) }
function availableDealOptions(line: DealLineForm) {
  if (!selected.value) return []
  const used = new Set(form.dealLines.filter(item => item !== line).map(item => item.optionId))
  return recordOptions(selected.value).filter(option => option.available !== false && (option.id === line.optionId || !used.has(option.id)))
}
function open(row: QuotationRecord) {
  selected.value = row
  detailTab.value = 'overview'
  editing.value = false
  fillForm(row)
}
function closeDrawer() { editing.value = false; selected.value = null }
function cancelEdit() {
  if (!selected.value) return
  fillForm(selected.value)
  editing.value = false
}
async function submit() {
  if (!selected.value) return
  const options = recordOptions(selected.value)
  if (form.status === 'won' && !form.dealLines.length) { toast('请至少添加一条成交方案'); return }
  const dealLines: QuotationRecordDealLine[] = []
  for (const [index, line] of form.dealLines.entries()) {
    const option = options.find(item => item.id === line.optionId)
    const unitPriceUsd = Number(line.unitPriceUsd)
    const quantity = Number(line.quantity)
    if (!option) { toast(`请选择第 ${index + 1} 条成交方案的国家和物流渠道`); return }
    if (!(unitPriceUsd > 0)) { toast(`请填写第 ${index + 1} 条成交方案的有效成交单价`); return }
    if (!Number.isInteger(quantity) || quantity <= 0) { toast(`请填写第 ${index + 1} 条成交方案的正整数成交数量`); return }
    dealLines.push({ id: line.id.startsWith('draft-') ? `deal-${Date.now()}-${index}` : line.id, optionId: option.id, optionLabel: optionLabel(option), country: option.country, carrier: option.carrier, channel: option.channel, unitPriceUsd, quantity, amountUsd: Number((unitPriceUsd * quantity).toFixed(2)) })
  }
  if (!form.date) { toast('请选择处理日期'); return }
  const previousUpdatedAt = selected.value.updatedAt
  const savedLines = form.status === 'won' ? dealLines : []
  const totalUsd = savedLines.reduce((sum, line) => sum + line.amountUsd, 0)
  const totalQuantity = savedLines.reduce((sum, line) => sum + line.quantity, 0)
  const singleLine = savedLines.length === 1 ? savedLines[0] : undefined
  const updated = await updateQuotationRecord(selected.value.id, { status: form.status, dealLines: savedLines, dealOptionId: singleLine?.optionId, dealOptionLabel: singleLine?.optionLabel || (savedLines.length ? `${savedLines.length}条成交方案` : undefined), actualQuoteUsd: savedLines.length ? totalUsd : undefined, actualQuoteCny: savedLines.length ? totalUsd * selected.value.exchangeRate : undefined, dealQuantity: savedLines.length ? totalQuantity : undefined, closedAt: form.date, note: form.note.trim() || undefined }, selected.value._version)
  if (!updated) { toast('保存失败，请刷新页面后重试'); return }
  await refresh(); selected.value = updated
  editing.value = false
  toast(updated.updatedAt === previousUpdatedAt ? '没有检测到内容变化' : '报价结果已保存，并记录本次修改时间')
}
function toast(text: string) { notice.value = text; window.setTimeout(() => notice.value === text && (notice.value = ''), 2400) }

</script>

<template>
  <div class="app">
    <main><header class="heading"><div><p>QUOTATION FOLLOW-UP</p><h1>{{ title }}</h1><span>{{ isMine ? '核对客户报价、处理状态与成交结果' : '查看全体业务员的报价、处理状态与成交结果' }}</span></div><RouterLink v-if="isMine" to="/quotation">＋ 新建报价</RouterLink></header>
      <section class="stats"><article class="red"><i>!</i><span><small>待处理</small><b>{{ pending }}</b><em>当前筛选范围</em></span></article><article><i>✓</i><span><small>已处理</small><b>{{ summary.processed || 0 }}</b><em>当前筛选范围</em></span></article><article class="green"><i>✓</i><span><small>已成交</small><b>{{ won }}</b><em>当前筛选范围</em></span></article><article><i>总</i><span><small>全部报价</small><b>{{ summary.total }}</b><em>当前筛选范围</em></span></article></section>
      <nav class="lifecycle-tabs" aria-label="报价记录分类">
        <button v-for="state in (['active','archived','trashed'] as const)" :key="state" :class="{active:lifecycle===state}" :aria-current="lifecycle===state ? 'page' : undefined" :disabled="lifecycleBusy" @click="lifecycle=state">{{ lifecycleLabel(state) }}</button>
        <small>{{ lifecycle==='trashed' ? '回收站记录不计入业务统计，可恢复' : lifecycle==='archived' ? '已归档记录仍计入历史统计' : '测试、误操作记录可移入回收站' }}</small>
      </nav>
      <section class="filters"><label class="search">⌕<input v-model="search" placeholder="搜索客户、SKU、品类、国家、渠道或报价单号"></label><label>状态<select v-model="filterStatus"><option value="">全部状态</option><option value="pending">待处理</option><option value="processed">已处理</option><option value="finance-pending">待财务审核</option><option value="finance-reviewing">审核中</option><option v-if="canReview" value="finance-mine">我正在审核</option><option value="finance-approved">财务已审核-可报价</option><option value="finance-rejected">财务已审核-价格有误不可报价</option><option value="won">已成交</option></select></label><label>产品品类<select v-model="filterCategory"><option value="">全部品类</option><option v-for="item in quotationProductCategories" :key="item" :value="item">{{ item }}</option></select></label><label>报价国家<select v-model="filterCountry"><option value="">全部国家</option><option v-for="item in countries" :key="item">{{ item }}</option></select></label><button @click="resetFilters">重置</button><b>共 {{ total }} 条记录</b></section>
      <section class="record-date-filters" aria-label="报价时间筛选">
        <label>开始日期<input v-model="startDate" type="date" aria-label="开始日期" :max="endDate || undefined"></label>
        <label>结束日期<input v-model="endDate" type="date" aria-label="结束日期" :min="startDate || undefined"></label>
        <button @click="recent(1)">今天</button><button @click="recent(7)">近 7 天</button><button @click="recent(30)">近 30 天</button><button @click="startDate='';endDate=''">全部时间</button>
        <div class="record-export-actions">
        <button :disabled="loading || exporting || !!dateError || !!loadError || !total" @click="exportRecords">{{ exporting ? '正在导出…' : '导出筛选结果' }}</button>
        </div>
        <small>按北京时间的创建日期筛选，包含结束日期全天</small>
      </section>
      <p v-if="dateError" role="alert">{{ dateError }}</p>
      <p v-if="loadError" role="alert">{{ loadError }} <button @click="refresh()">重试</button></p>
      <p v-if="loading" role="status">正在加载报价记录…</p>
      <p v-if="reviewSync.error.value" role="alert">{{ reviewSync.error.value }}</p>
      <section v-if="canManageLifecycle" class="lifecycle-toolbar" aria-label="报价记录批量操作">
        <label><input type="checkbox" aria-label="全选本页可操作记录" :checked="allPageChecked" :indeterminate="checkedIds.length>0 && !allPageChecked" :disabled="loading || lifecycleBusy || !selectableRows.length" @change="togglePage">全选本页</label><span>已选 {{ checkedIds.length }} 条</span>
        <div><button v-if="lifecycle==='active'" :disabled="!checkedIds.length || loading || lifecycleBusy" @click="beginLifecycle('archive')">批量归档</button><button v-if="lifecycle!=='trashed'" class="trash-button" :disabled="!checkedIds.length || loading || lifecycleBusy" @click="beginLifecycle('trash')">移入回收站</button><button v-if="lifecycle!=='active'" :disabled="!checkedIds.length || loading || lifecycleBusy" @click="beginLifecycle('restore')">恢复所选记录</button></div>
        <small>{{ lifecycle==='active' ? '已成交、审核中或已审核记录不可批量清理' : '保留原报价及操作记录' }}</small>
      </section>
      <section class="records quote-record-table" :aria-busy="loading">
        <header><span>报价单 / 商品</span><span>客户</span><span>报价规模</span><span>报价差异</span><span>处理状态</span></header>
        <article v-for="row in list" :key="row.id">
          <div class="quote-info">
            <input v-if="canManageLifecycle" v-model="checkedIds" type="checkbox" class="lifecycle-checkbox" :value="row.id" :aria-label="'选择报价 ' + row.no" :disabled="loading || lifecycleBusy || !!selectionBlocked(row)" :title="selectionBlocked(row) || '选择此报价'">
            <QuotationProductImage class="quote-record-image" :snapshot-image="row.productImage" :physical-image="recordPurchaseProduct(row)?.physicalImage" :product-image="recordPurchaseProduct(row)?.productImage" :alt="row.productSummary"><template #fallback><PurchaseCategoryBadge :category="recordPurchaseProduct(row)?.category || row.productCategory" /></template></QuotationProductImage>
            <div class="quote-info-copy">
              <div class="record-product-title"><b class="record-sku" :title="recordSku(row)">{{ recordSku(row) }}</b><strong>{{ row.productSummary }}</strong></div>
              <div class="record-product-meta"><span class="record-commission" :class="{applied: (row.commissionThreshold ?? 1) > 0 && (row.commissionThreshold ?? 1) < 1}">{{ recordCommission(row) }}</span><small>{{ row.quoteMode==='bundle' ? '组合报价' : '单品SKU' }}<template v-if="!isMine"> · {{ row.salespersonName }}</template></small></div>
              <small class="record-number" :title="row.no">报价单号：{{ row.no }}</small>
              <small>创建于 {{ dateTime(row.createdAt) }}</small>
              <small v-if="row.lifecycleChangedAt" class="lifecycle-metadata">{{ lifecycleLabel(row.lifecycleState) }} · {{ row.lifecycleChangedBy }} · {{ dateTime(row.lifecycleChangedAt) }}<br>原因：{{ row.lifecycleReason }}</small>
              <small v-if="lifecycle==='active' && selectionBlocked(row)" class="lifecycle-lock">{{ selectionBlocked(row) }}</small>
            </div>
          </div>
          <div class="record-customer"><b>{{ row.customerName }}</b><small class="record-customer-grade">客户级别：{{ customerGradeDisplayLabel(row.customerGrade) }}</small></div>
          <div class="route-summary"><b>{{ hasMultipleOptions(row) ? '多方案报价' : '单方案报价' }}</b><span class="country-tags"><i>{{ recordCountries(row).length || 1 }}国</i><i>{{ recordOptions(row).length || 1 }}渠道</i><em v-for="country in recordCountries(row).slice(0,2)" :key="country">{{ country }}</em><em v-if="recordCountries(row).length>2">+{{ recordCountries(row).length-2 }}</em></span></div>
          <button class="difference-cell" :class="representativePriceDifference(row).changed ? 'lower' : 'equal'" :title="representativePriceDifference(row).channel" @click="open(row)"><b>{{ representativePriceDifference(row).label }}</b><span>{{ representativePriceDifference(row).detail }}</span></button>
          <div class="record-row-actions">
            <QuotationReviewPanel :record="row" :state="reviewSync.stateFor(row)" :account="currentAuthUser.account" :can-review="canReview&&isActive(row)" :admin="currentAuthUser.role==='super_admin'" :busy="reviewing.has(row.id)||lifecycleBusy" compact @action="changeReview(row,$event)" @open="open(row)" />
            <div class="record-action-buttons">
              <em :class="row.status==='won' ? 'won' : row.quoteConfirmed ? 'processed' : 'pending'">{{ displayStatus(row) }}</em>
              <RouterLink v-if="hasPermission('quote') && isActive(row)" class="reissue-quote" :to="{ path: '/quotation', query: { reissue: row.id } }">再次发起</RouterLink>
            </div>
            <small v-if="row.status==='won'">{{ row.quoteConfirmed ? '报价已确认' : '报价待确认' }}</small>
          </div>
        </article>
        <div v-if="!loading && !loadError && !dateError && !list.length" class="empty"><b>暂无报价记录</b><span>当前筛选范围内没有记录，可以调整日期或重置筛选。</span><RouterLink v-if="isMine" to="/quotation">去新建报价</RouterLink></div>
      </section>
      <nav class="record-pagination" aria-label="报价记录分页">
        <span>共 {{ total }} 条 · 第 {{ totalPages ? page+1 : 0 }} / {{ totalPages }} 页</span>
        <label>每页<select v-model.number="pageSize" aria-label="每页记录数"><option :value="10">10 条</option><option :value="30">30 条</option><option :value="50">50 条</option></select></label>
        <button :disabled="loading || page===0 || !!dateError" @click="changePage(page-1)">上一页</button>
        <button :disabled="loading || page+1>=totalPages || !!dateError" @click="changePage(page+1)">下一页</button>
      </nav>
    </main>
    <QuotationLifecycleDialog v-if="lifecycleAction" :action="lifecycleAction" :rows="confirmationRows" :busy="lifecycleBusy" :error="lifecycleError" @cancel="lifecycleAction=null" @confirm="confirmLifecycle" />
    <div v-if="selected" class="mask" @click.self="closeDrawer">
      <aside class="record-drawer">
        <header><div><small>{{ editing ? 'QUOTATION FOLLOW-UP' : 'QUOTATION DOCUMENT' }}</small><h2>{{ editing ? (selected.status === 'pending' ? '回填成交结果' : '修改成交结果') : selected.no }}</h2><span v-if="!editing">{{ selected.customerName }} · {{ selected.productSummary }} · {{ recordCountries(selected).length || 1 }}国{{ recordOptions(selected).length || 1 }}渠道</span></div><button aria-label="关闭" @click="closeDrawer">×</button></header>

        <p v-if="!isActive(selected)" class="lifecycle-readonly">{{ lifecycleLabel(selected.lifecycleState) }} · {{ selected.lifecycleChangedBy }} · {{ selected.lifecycleReason }}。恢复后可修改。</p>
        <template v-if="!editing">
          <nav class="detail-tabs drawer-tabs"><button :class="{active:detailTab==='overview'}" @click="detailTab='overview'">报价概览</button><button :class="{active:detailTab==='options'}" @click="detailTab='options'">国家与渠道 <i>{{ recordOptions(selected).length }}</i></button><button :class="{active:detailTab==='history'}" @click="detailTab='history'">修改记录 <i>{{ revisionGroups.length }}</i></button></nav>
          <section v-if="detailTab==='overview'" class="overview-panel">
            <div class="overview-metrics"><article><small>报价国家</small><b>{{ recordCountries(selected).length || 1 }}</b><span>个国家</span></article><article><small>报价渠道</small><b>{{ recordOptions(selected).length || 1 }}</b><span>条渠道</span></article><article><small>1{{ selected.quoteMode==='bundle'?'套':'件' }}报价区间</small><b>{{ hasMultipleOptions(selected) ? quote1UsdRange(selected) : usd(selected.systemQuoteUsd) }}</b><span>{{ hasMultipleOptions(selected) ? quote1CnyRange(selected) : cny(selected.systemQuoteCny) }}</span></article></div>
            <article class="primary-plan"><header><b>首选方案</b><span>报价单优先展示</span></header><div><span><strong>{{ primaryOption(selected)?.country || selected.country }} · {{ primaryOption(selected)?.carrier || selected.carrier }}｜{{ primaryOption(selected)?.channel || selected.channel }}</strong><small>渠道编码：{{ primaryOption(selected)?.channelCode || '—' }} · {{ primaryOption(selected)?.eta || selected.rule }}</small></span><b>{{ optionPrice(primaryOption(selected)?.quote1Usd ?? selected.systemQuoteUsd) }}</b></div></article>
            <QuotationWeightTrace :snapshot="selected.weightSnapshot" :bundle="selected.quoteMode === 'bundle'" />
            <QuotationProductCostTrace :record="selected" />
            <section class="overview-info"><header>报价基本信息</header><div><span>客户</span><b>{{ selected.customerName }}</b><span>商品</span><b>{{ selected.productSummary }}</b><span>SKU</span><b>{{ selected.primarySku }}</b><span>报价类型</span><b>{{ selected.quoteMode === 'bundle' ? '组合报价' : '单品报价' }}</b><span>报价模式</span><b>{{ selected.matrixMode === 'template' ? `模板 · ${selected.quotationTemplateName || '个人报价'}` : hasMultipleOptions(selected) ? '多国家多渠道报价' : '常用国家快速报价' }}</b><span>客户等级</span><b>{{ customerGradeDisplayLabel(selected.customerGrade) }}</b><template v-if="selected.customerOperation"><span>公司操作费</span><b>{{ selected.customerOperation.feesByQuantityUsd ? operationFeesLabel(selected.customerOperation, selected.quoteMode === 'bundle' ? '套' : '件') : `$${selected.customerOperation.feeUsd.toFixed(2)}/单（历史固定费用）` }}</b></template><span>佣金设置</span><b>{{ recordCommission(selected) }}</b><span>自定义数量</span><b>{{ selected.customQuoteQuantity || '—' }}{{ selected.quoteMode==='bundle'?'套':'件' }}</b><span>创建时间</span><b>{{ dateTime(selected.createdAt) }}</b><span>最后修改</span><b>{{ dateTime(selected.updatedAt) }}</b></div></section>
            <section v-if="selected.quoteMode === 'bundle' && selected.bundleItems?.length" class="bundle-snapshot"><header><b>组合商品快照</b><span>{{ selected.bundleItems.length }} 个 SKU · 单套重量 {{ selected.bundleItems.reduce((sum,item)=>sum+item.effectiveWeightKg*item.quantityPerSet,0).toFixed(3) }} kg</span></header><div><article v-for="item in selected.bundleItems" :key="item.sku"><span><b>{{ item.name || item.sku }}</b><small>{{ item.sku }} × {{ item.quantityPerSet }}/套</small></span><em>{{ (item.effectiveWeightKg * 1000).toFixed(0) }} g/件</em><em>采购 ¥{{ item.purchaseUnitPriceCny.toFixed(2) }}</em><em>国内运费 ¥{{ item.domesticFreightPerUnitCny.toFixed(2) }}</em></article></div></section>
            <section v-else-if="selected.quoteMode === 'bundle'" class="bundle-snapshot legacy"><header><b>组合商品</b><span>历史记录未保存结构化明细</span></header><p>{{ selected.productSummary || selected.primarySku }}</p></section>
          </section>
          <section v-else-if="detailTab==='options'" class="option-detail-panel">
            <CustomerPriceComparison :record="selected" :can-edit="canEditPrices(selected)" @saved="pricesSaved" />
          </section>
          <section v-else class="revision-history detail-history"><header><b>处理 / 修改记录</b><span>{{ revisionGroups.length }} 次操作</span></header><div v-if="revisionGroups.length"><article v-for="group in revisionGroups" :key="group.id"><time>{{ dateTime(group.changedAt) }}</time><span>{{ group.editorName }} · {{ group.editorAccount }}</span><template v-for="revision in group.changes" :key="revision.id"><CustomerPriceRevision v-if="revision.field==='customerQuote'" :record="selected" :before="revision.before" :after="revision.after" /><p v-else-if="revision.field==='quoteConfirmed'"><b>报价处理</b>：{{ revision.after === 'true' ? '已确认报价，标记为已处理' : '客户报价已变化，需重新确认' }}</p><p v-else-if="revision.field==='lifecycleState'"><b>记录分类</b>：{{ lifecycleLabel(revision.before) }} → {{ lifecycleLabel(revision.after) }}<br>原因：{{ revision.reason || '—' }}</p><p v-else-if="revision.field==='financeReviewStatus'"><b>财务审核</b>：{{ financeReviewLabel(revision.before) }} → {{ financeReviewLabel(revision.after) }}</p><p v-else-if="revision.field==='status'"><b>处理状态</b>：{{ statusText(revision.before as QuotationRecordStatus) || revision.before }} → {{ statusText(revision.after as QuotationRecordStatus) || revision.after }}</p><p v-else><b>{{ revision.fieldLabel }}</b>：{{ revision.before || '未填写' }} → {{ revision.after || '未填写' }}</p></template></article></div><p v-else class="history-empty">暂无可追溯的修改记录；旧记录将从下一次修改开始记录。</p></section>
          <QuotationReviewHistory v-if="detailTab==='history'" :id="selected.id" :version="reviewSync.stateFor(selected)._reviewVersion" :account="currentAuthUser.account" />
          <footer v-if="detailTab==='overview'" class="drawer-view-footer"><QuotationRecordCopyActions :key="selected.id" :record="selected" :can-edit="canEditPrices(selected)" @saved="pricesSaved">
            <QuotationReviewButton v-if="canReview&&isActive(selected)" :record="selected" :state="reviewSync.stateFor(selected)" :account="currentAuthUser.account" :busy="reviewing.has(selected.id)||lifecycleBusy" @action="changeReview(selected,$event)" @reload="reloadReview(selected)" />
          </QuotationRecordCopyActions></footer>
        </template>

        <template v-else>
          <QuotationWeightTrace :snapshot="selected.weightSnapshot" :bundle="selected.quoteMode === 'bundle'" />
          <QuotationProductCostTrace :record="selected" />
          <section class="snapshot"><span>报价单号</span><b>{{ selected.no }}</b><span>客户</span><b>{{ selected.customerName }}</b><span>{{ hasMultipleOptions(selected) ? `1${selected.quoteMode==='bundle'?'套':'件'}报价区间` : '系统报价' }}</span><b>{{ hasMultipleOptions(selected) ? `${quote1UsdRange(selected)} / ${quote1CnyRange(selected)}` : `${usd(selected.systemQuoteUsd)} / ${cny(selected.systemQuoteCny)}` }}</b><span>佣金设置</span><b>{{ recordCommission(selected) }}</b><span>首选报价渠道</span><b>{{ selected.country }} · {{ selected.carrier }}｜{{ selected.channel }}</b><span>创建时间</span><b>{{ dateTime(selected.createdAt) }}</b><span>最后修改</span><b>{{ dateTime(selected.updatedAt) }}</b></section>
          <section v-if="recordOptions(selected).length" class="specified-snapshot"><header><b>{{ selected.matrixMode === 'template' ? '模板报价清单' : hasMultipleOptions(selected) ? '多国家渠道报价清单' : '报价方案' }}</b><span>{{ selectedOptionGroups.length }} 个国家 · {{ recordOptions(selected).length }} 条渠道</span></header><div class="option-table-head"><span>物流商 / 渠道</span><span>预计时效</span><span>1{{ selected.quoteMode==='bundle'?'套':'件' }}</span><span>2{{ selected.quoteMode==='bundle'?'套':'件' }}</span><span>3{{ selected.quoteMode==='bundle'?'套':'件' }}</span><span>{{ selected.customQuoteQuantity || '自定义' }}{{ selected.quoteMode==='bundle'?'套':'件' }}</span></div><div class="country-option-groups"><details v-for="(group,index) in selectedOptionGroups" :key="group.country" :open="index===0 || group.options.some(option=>option.isPrimary || selectedDealOptionIds.has(option.id))"><summary><span><b>{{ group.countryCode || '' }} {{ group.country }}</b><small>{{ group.options.length }} 条渠道</small></span><i>⌄</i></summary><article v-for="option in group.options" :key="option.id" :class="{ primary:option.isPrimary, deal:selectedDealOptionIds.has(option.id) }"><span><b>{{ option.carrier }}｜{{ option.channel }}</b><small v-if="option.available === false">{{ option.availabilityMessage }}</small><small v-for="message in option.quantityMessages" :key="message">{{ message }}</small><small>渠道编码：{{ option.channelCode || '—' }} · 计费规则：{{ option.rule }}<template v-if="option.quoteRegion"> · {{ option.quoteRegion }}</template></small><em v-if="option.isPrimary">首选</em><em v-if="selectedDealOptionIds.has(option.id)" class="deal-badge">已成交</em></span><b>{{ option.eta }}</b><em>{{ optionPrice(option.quote1Usd) }}</em><em>{{ optionPrice(option.quote2Usd) }}</em><em>{{ optionPrice(option.quote3Usd) }}</em><em class="custom-option-price">{{ optionPrice(option.quoteCustomUsd) }}</em></article></details></div></section>
          <template v-if="isMine && isActive(selected)"><label class="result">成交结果 <span><label><input v-model="form.status" type="radio" value="won"> 已成交</label><label><input v-model="form.status" type="radio" value="lost"> 未成交</label></span></label><section v-if="form.status==='won'" ref="dealLineEditor" class="deal-line-editor"><header><div><b>成交方案明细</b><span>每个渠道分别填写成交单价和数量</span></div><button type="button" @click="addDealLine">＋ 添加成交方案</button></header><div class="deal-line-head"><span>成交国家与渠道</span><span>成交单价</span><span>数量</span><span>成交金额</span><span>操作</span></div><article v-for="(line,index) in form.dealLines" :key="line.id"><select v-model="line.optionId"><option value="">请选择成交国家和物流渠道</option><option v-for="option in availableDealOptions(line)" :key="option.id" :value="option.id">{{ optionLabel(option) }} · {{ option.eta }}</option></select><label><i>$</i><input v-model="line.unitPriceUsd" type="number" min="0.01" step="0.01"></label><label><input v-model="line.quantity" type="number" min="1" step="1"><i>{{ selected.quoteMode==='bundle'?'套':'件' }}</i></label><strong>{{ usd((Number(line.unitPriceUsd)||0)*(Number(line.quantity)||0)) }}</strong><button type="button" @click="removeDealLine(index)">删除</button></article><div v-if="!form.dealLines.length" class="deal-line-empty">尚未添加成交方案，请点击右上角“＋ 添加成交方案”</div><footer><span>成交渠道 <b>{{ form.dealLines.length }}</b> 条</span><span>成交总数量 <b>{{ formDealTotalQuantity }}</b>{{ selected.quoteMode==='bundle'?'套':'件' }}</span><span>成交总金额 <strong>{{ usd(formDealTotalUsd) }}</strong></span></footer></section><label>处理日期<input v-model="form.date" type="date"></label><label>备注 / 未成交原因<textarea v-model="form.note" maxlength="200"></textarea></label><footer><button @click="cancelEdit">取消</button><button class="primary" @click="submit">保存修改</button></footer></template>
        </template>
      </aside>
    </div>
    <Transition name="toast"><div v-if="notice" class="toast">✓ {{ notice }}</div></Transition>
  </div>
</template>

<style scoped>
.reissue-quote{padding:7px 12px;border:1px solid #ffb54e;border-radius:6px;background:#fff8ed;color:#a95f00;font-size:11px;font-weight:700;text-decoration:none}
.finance-review{box-sizing:border-box;max-width:100%;min-width:0;padding:7px;border:1px solid #d9e1e7;border-radius:6px;font-size:12px;color:#586575;background:#f7f9fb;white-space:normal}.finance-review.approved{color:#078347;background:#e7f7ee;border-color:#9ad8b4}.finance-review.rejected{color:#b52b25;background:#fff0ef;border-color:#efb0ac}.record-row-actions{min-width:0;gap:8px}

.stats{grid-template-columns:repeat(4,1fr)}.record-row-actions{flex-wrap:wrap}.record-row-actions>em.processed{background:#e8f3ff;border-color:#b4d3f0;color:#256da6}.record-row-actions>small{font-size:10px;color:#71808c}
.revision-history article>.customer-price-revision{grid-column:1/-1;min-width:0}
.record-date-filters,.record-pagination{display:flex;align-items:center;flex-wrap:wrap;gap:10px;margin:12px 0;color:#53616c;font-size:12px}.record-date-filters label,.record-pagination label{display:flex;align-items:center;gap:7px}.record-date-filters input,.record-date-filters button,.record-pagination button,.record-pagination select,.record-export-actions button{min-height:34px;padding:5px 10px;background:#fff;border:1px solid #dce3e8;border-radius:6px;color:#34434f}.record-date-filters small{color:#798690}.record-pagination{padding:12px;background:#f7f9fa;border-radius:8px}.record-pagination>span{margin-right:auto}.record-export-actions{display:flex;justify-content:flex-end;margin:12px 0}.record-pagination button:disabled,.record-export-actions button:disabled{opacity:.45;cursor:not-allowed}

.app{--orange:#ff9900;--ink:#17212b;--line:#e4e9ee;min-height:100vh;background:#f5f7fa;color:var(--ink);font-family:Inter,"PingFang SC","Microsoft YaHei",sans-serif}.topbar{height:68px;display:flex;align-items:center;padding:0 4vw;background:#fff;border-bottom:1px solid var(--line)}.brand{display:flex;gap:11px;align-items:center;margin-right:48px;color:var(--ink);text-decoration:none}.brand>i{display:grid;place-items:center;width:39px;height:39px;border-radius:10px;background:var(--orange);font-size:20px;font-style:normal;font-weight:900}.brand b,.brand small,.user b,.user small{display:block}.brand b{font-size:17px}.brand small{color:#8a949d;font-size:8px;letter-spacing:.15em}.topbar nav{display:flex;height:100%;gap:26px}.topbar nav a{position:relative;display:flex;align-items:center;color:#65717c;text-decoration:none;font-size:13px}.topbar nav a.active{color:var(--ink);font-weight:800}.topbar nav a.active:after{position:absolute;right:0;bottom:0;left:0;height:3px;background:var(--orange);content:""}.user{display:flex;align-items:center;gap:8px;margin-left:auto;font-size:12px}.user>i{display:grid;place-items:center;width:34px;height:34px;border-radius:50%;background:#17212b;color:#fff;font-size:10px;font-style:normal}.user small{color:#8b969f;font-size:10px}main{width:min(1440px,92vw);margin:auto;padding:32px 0 70px}.heading{display:flex;align-items:end;justify-content:space-between;margin-bottom:22px}.heading p,aside header small{margin:0 0 7px;color:#d87600;font-size:10px;font-weight:900;letter-spacing:.18em}.heading h1{margin:0 0 8px;font-size:29px}.heading span{color:#74808a;font-size:13px}.heading>a,.primary{padding:12px 18px;border:0;border-radius:8px;background:var(--orange);color:#17212b;font-size:12px;font-weight:800;text-decoration:none}.stats{display:grid;grid-template-columns:repeat(4,1fr);gap:16px;margin-bottom:18px}.stats article{display:flex;align-items:center;gap:14px;min-height:82px;padding:14px 18px;border:1px solid var(--line);border-radius:10px;background:#fff;box-shadow:0 6px 17px rgba(23,33,43,.04)}.stats i{display:grid;place-items:center;width:38px;height:38px;border-radius:50%;background:#eef1f4;color:#68737d;font-size:19px;font-style:normal;font-weight:800}.stats .red i{background:#fff0ef;color:#da4941}.stats .green i{background:#eaf8ef;color:#19975a}.stats span{display:grid;gap:3px}.stats small{color:#7c8790;font-size:11px}.stats b{font-size:24px}.stats em{color:#9ca5ac;font-size:10px;font-style:normal}.filters{display:flex;align-items:end;gap:10px;margin-bottom:14px;padding:14px 16px;border:1px solid var(--line);border-radius:10px;background:#fff}.filters label{display:grid;gap:5px;color:#7d8790;font-size:10px}.filters input,.filters select{height:36px;border:1px solid #dce3e8;border-radius:6px;background:#fff;padding:0 10px;color:#25313b;outline:0;font-size:12px}.filters .search{display:flex;align-items:center;width:310px;height:36px;box-sizing:border-box;gap:8px;border:1px solid #dce3e8;border-radius:6px;padding:0 10px;color:#7d8790}.filters .search input{width:100%;height:34px;border:0;padding:0}.filters button{height:36px;border:1px solid #dce3e8;border-radius:6px;background:#fff;color:#64707a}.filters>b{margin-left:auto;color:#71808a;font-size:11px}.records{overflow:hidden;border:1px solid var(--line);border-radius:11px;background:#fff;box-shadow:0 12px 28px rgba(23,33,43,.045)}.records>header,.records>article{display:grid;grid-template-columns:1.3fr 1.35fr .8fr .85fr .55fr .75fr;gap:12px;align-items:center;padding:13px 18px}.records>header{background:#f8fafb;color:#74808a;font-size:11px}.records>article{min-height:76px;border-top:1px solid #edf0f2;font-size:12px}.records article>div{display:grid;gap:4px;min-width:0}.records article b,.records article strong{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.records article strong{color:#47545e;font-size:12px;font-weight:500}.records article small{overflow:hidden;color:#8b969f;font-size:10px;text-overflow:ellipsis;white-space:nowrap}.price b{font-size:14px;font-variant-numeric:tabular-nums}.actions{display:flex!important;align-items:center;justify-content:flex-end;gap:9px}.actions em{padding:5px 8px;border-radius:12px;font-size:10px;font-style:normal;white-space:nowrap}.actions em.pending{background:#fff0ef;color:#d94a42}.actions em.won{background:#e8f7ee;color:#178857}.actions em.lost{background:#f0f2f4;color:#78828a}.actions button{border:0;border-radius:6px;background:#17212b;color:#fff;padding:8px 11px;font-size:11px;font-weight:800}.actions em.pending+button{background:var(--orange);color:#17212b}.records>.empty{display:grid;justify-items:center;gap:8px;padding:60px;color:#7e8992}.records>.empty b{color:#35414b;font-size:15px}.records>.empty span{font-size:12px}.records>.empty a{color:#a96000;font-size:12px;font-weight:800}.mask{position:fixed;z-index:30;inset:0;background:rgba(17,24,39,.42);backdrop-filter:blur(3px)}aside{position:absolute;top:0;right:0;display:flex;flex-direction:column;width:min(470px,100vw);height:100%;box-sizing:border-box;overflow-y:auto;padding:25px;background:#fff;box-shadow:-15px 0 45px rgba(17,24,39,.2)}aside>header{display:flex;justify-content:space-between;padding-bottom:18px;border-bottom:1px solid var(--line)}aside h2{margin:6px 0 0;font-size:21px}aside header button{border:0;background:none;font-size:24px}.snapshot{display:grid;grid-template-columns:105px 1fr;gap:12px;margin:18px 0;padding:15px;border-radius:8px;background:#f7f9fa;font-size:12px}.snapshot span{color:#84909a}.result{display:grid;gap:8px;margin-top:12px}.result>span{display:flex;gap:17px}.result label{color:#26313b}.result input{width:auto}aside>label{display:grid;gap:7px;margin-top:13px;color:#4d5a64;font-size:12px}aside input,aside textarea{box-sizing:border-box;width:100%;border:1px solid #dce3e8;border-radius:7px;padding:10px;font:inherit;outline:0}aside textarea{height:92px;resize:none}aside footer{display:flex;justify-content:flex-end;gap:10px;margin-top:auto;padding-top:22px}aside footer button{height:40px;padding:0 18px;border:1px solid #dce3e8;border-radius:7px;background:#fff;font-weight:800}aside footer .primary{border:0}.edit-button{border-color:#ffb451!important;color:#a96000;background:#fffaf2!important}.detail{display:grid;gap:10px;margin-top:17px;padding:16px;border-radius:8px;background:#f7f9fa;font-size:12px}.detail b{font-size:16px}.detail b.won{color:#178857}.detail b.lost{color:#78828a}.detail span,.detail p{margin:0;color:#66727c}.revision-history{display:grid;gap:10px;margin-top:14px;padding:15px;border:1px solid #e1e7eb;border-radius:8px;background:#fff}.revision-history>header{display:flex;align-items:center;justify-content:space-between;font-size:12px}.revision-history>header span{color:#87939c;font-size:10px}.revision-history>div{display:grid}.revision-history article{display:grid;grid-template-columns:auto 1fr;gap:3px 9px;padding:10px 0;border-top:1px solid #edf0f2;font-size:10px}.revision-history time{color:#53616c;font-weight:800}.revision-history article>span{color:#8a959e;text-align:right}.revision-history article p{grid-column:1/-1;margin:2px 0 0;color:#62707a;line-height:1.5}.revision-history article p b{color:#303c46}.history-empty{margin:0;color:#8b969f;font-size:10px;line-height:1.6}.toast{position:fixed;right:24px;bottom:24px;z-index:40;padding:13px 18px;border-radius:8px;background:#17212b;color:#fff;font-size:12px}.toast-enter-active,.toast-leave-active{transition:.2s}.toast-enter-from,.toast-leave-to{opacity:0;transform:translateY(8px)}@media(max-width:1100px){.topbar nav{gap:14px}.records>header,.records>article{grid-template-columns:1.1fr 1.15fr .7fr .7fr .45fr .6fr;padding-inline:12px}}@media(max-width:860px){.topbar nav{display:none}.stats{grid-template-columns:1fr 1fr}.filters{flex-wrap:wrap}.filters>b{margin-left:0}.records{overflow:auto}.records>header,.records>article{width:940px}.heading{align-items:start;gap:12px;flex-direction:column}}@media(max-width:520px){.stats{grid-template-columns:1fr}.filters .search{width:100%}}
.records>header,.records>article{grid-template-columns:1.55fr 1.3fr .72fr .9fr .48fr .82fr}
.specified-snapshot{display:grid;gap:8px;margin-bottom:12px;padding:12px;border:1px solid #e0e6ea;border-radius:8px;background:#fff}.specified-snapshot>header{display:flex;justify-content:space-between;color:#4e5d67;font-size:10px}.specified-snapshot>header span{color:#818d96}.specified-snapshot>div{display:grid;max-height:190px;overflow:auto}.specified-snapshot article{display:grid;grid-template-columns:58px minmax(0,1fr) auto;align-items:center;gap:8px;padding:7px 0;border-top:1px solid #edf0f2;font-size:9px}.specified-snapshot article>span{display:grid;min-width:0;gap:2px}.specified-snapshot article strong,.specified-snapshot article small{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.specified-snapshot article small{color:#87939c}.specified-snapshot article em{color:#b76400;font-style:normal;font-weight:800;white-space:nowrap}
.quote-info{display:flex!important;align-items:center;gap:12px}.quote-info>img,.quote-image-placeholder{flex:0 0 48px;width:48px;height:48px;border:1px solid #e4e9ee;border-radius:8px;object-fit:cover;background:#f5f7fa}.quote-image-placeholder{display:grid;place-items:center;color:#a96000;font-size:17px;font-weight:900}.quote-info-copy{display:grid;gap:4px;min-width:0}.lost-price b{color:#b52b25;font-size:13px;font-weight:900}.lost-price small{color:#a96864!important}.actions{display:grid!important;grid-template-columns:68px 52px;align-items:center;justify-content:start;gap:8px}.actions em{box-sizing:border-box;min-width:68px;padding:7px 9px;border:1px solid transparent;border-radius:7px;font-size:12px;font-weight:900;line-height:1;text-align:center}.actions em.pending{border-color:#f3b3af;background:#fff0ef;color:#c9342c}.actions em.won{border-color:#a9dfbd;background:#e6f7ed;color:#087c43}.actions em.lost{border-color:#cbd1d6;background:#edf0f2;color:#36424c}.actions button{box-sizing:border-box;width:52px;height:32px;padding:0;font-size:11px}.records>header span:last-child{text-align:left}@media(max-width:1100px){.records>header,.records>article{grid-template-columns:1.4fr 1.15fr .68fr .82fr .45fr .78fr}}
.route-summary .country-tags{display:flex;align-items:center;gap:4px;overflow:hidden}.country-tags i,.country-tags em{padding:3px 6px;border-radius:10px;background:#edf7f1;color:#197b4e;font-size:8px;font-style:normal;font-weight:800;white-space:nowrap}.country-tags em{background:#eef1f4;color:#65727c}.price-range b{color:#b76400;font-size:13px}.price-range small{white-space:normal!important}.specified-snapshot{gap:0;padding:0;overflow:hidden}.specified-snapshot>header{padding:13px 15px;background:#f8fafb}.option-table-head,.country-option-groups article{display:grid;grid-template-columns:minmax(190px,1.6fr) .62fr repeat(4,.58fr);align-items:center;gap:8px}.option-table-head{padding:8px 15px;background:#f3f6f8;color:#7b8791;font-size:8px}.country-option-groups{display:grid;max-height:360px;overflow:auto}.country-option-groups details{border-top:1px solid #e8edf0}.country-option-groups summary{display:flex;align-items:center;justify-content:space-between;padding:10px 15px;background:#fbfcfd;cursor:pointer;list-style:none}.country-option-groups summary::-webkit-details-marker{display:none}.country-option-groups summary>span{display:flex;align-items:center;gap:8px}.country-option-groups summary b{font-size:11px}.country-option-groups summary small{padding:3px 6px;border-radius:10px;background:#edf1f4;color:#6c7983;font-size:8px}.country-option-groups summary i{font-style:normal;transition:transform .15s}.country-option-groups details[open] summary i{transform:rotate(180deg)}.country-option-groups article{min-height:48px;padding:7px 15px;border-top:1px solid #eef1f3;font-size:9px}.country-option-groups article.primary{background:#fff9ed}.country-option-groups article.deal{box-shadow:inset 3px 0 #20a365}.country-option-groups article>span{position:relative;display:grid;min-width:0;gap:2px}.country-option-groups article>span b,.country-option-groups article>span small{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.country-option-groups article>span small{color:#84919a;font-size:7px}.country-option-groups article>span>em{position:absolute;right:2px;top:0;padding:2px 5px;border-radius:8px;background:#fff0d5;color:#b66800;font-size:7px;font-style:normal;font-weight:800}.country-option-groups article>span>.deal-badge{top:20px;background:#e7f7ed;color:#16834e}.country-option-groups article>em{font-style:normal;font-weight:800}.custom-option-price{padding:5px;border-radius:5px;background:#fff2dd;color:#b76400}aside{width:min(720px,100vw)}aside>label select{box-sizing:border-box;width:100%;height:40px;border:1px solid #dce3e8;border-radius:7px;padding:0 10px;background:#fff;color:#26313b;font:inherit;outline:0}@media(max-width:720px){.option-table-head,.country-option-groups article{min-width:650px}.specified-snapshot{overflow-x:auto}.country-option-groups{min-width:650px}.route-summary .country-tags{max-width:190px}}
.record-workspace{display:grid;grid-template-columns:minmax(420px,.92fr) minmax(560px,1.08fr);gap:16px;align-items:start}.record-list,.record-detail{overflow:hidden;border:1px solid var(--line);border-radius:12px;background:#fff;box-shadow:0 10px 28px rgba(23,33,43,.05)}.record-list>header{display:flex;align-items:center;justify-content:space-between;padding:15px 17px;border-bottom:1px solid #e8edf0}.record-list>header div{display:grid;gap:3px}.record-list>header b{font-size:14px}.record-list>header span{color:#84909a;font-size:10px}.record-list>header em{padding:5px 9px;border-radius:12px;background:#f1f4f6;color:#5e6b75;font-size:10px;font-style:normal;font-weight:800}.record-card{position:relative;display:grid;width:100%;grid-template-columns:54px minmax(0,1fr) auto;gap:12px;align-items:center;padding:15px 16px;border:0;border-bottom:1px solid #edf0f2;background:#fff;color:var(--ink);text-align:left;cursor:pointer}.record-card:hover{background:#fbfcfd}.record-card.selected{background:#fff9ef;box-shadow:inset 4px 0 var(--orange)}.record-card-product img,.record-card-product i{display:grid;width:48px;height:48px;place-items:center;border:1px solid #e2e8ec;border-radius:8px;background:#f5f7fa;object-fit:cover;color:#af6800;font-style:normal;font-weight:900}.record-card-main{display:grid;min-width:0;gap:4px}.record-card-title{display:flex;align-items:center;gap:8px}.record-card-title b{overflow:hidden;font-size:12px;text-overflow:ellipsis}.record-card-title em,.detail-heading-actions em,.outcome-card em{padding:4px 7px;border-radius:10px;font-size:9px;font-style:normal;font-weight:900}.record-card-title em.pending,.detail-heading-actions em.pending,.outcome-card em.pending{background:#fff0ef;color:#c9342c}.record-card-title em.won,.detail-heading-actions em.won,.outcome-card em.won{background:#e6f7ed;color:#087c43}.record-card-title em.lost,.detail-heading-actions em.lost,.outcome-card em.lost{background:#edf0f2;color:#4e5a64}.record-card-main>strong{overflow:hidden;font-size:12px;text-overflow:ellipsis;white-space:nowrap}.record-card-main>small{color:#87939c;font-size:9px}.record-card-tags{display:flex;gap:4px;overflow:hidden}.record-card-tags i{padding:3px 6px;border-radius:9px;background:#eef4f7;color:#536572;font-size:8px;font-style:normal;white-space:nowrap}.record-card.selected .record-card-tags i{background:#fff0d4;color:#a96000}.record-card-price{display:grid;min-width:100px;justify-items:end;gap:3px}.record-card-price small{color:#89949d;font-size:9px}.record-card-price b{color:#b76400;font-size:13px}.record-card-price em{position:absolute;right:8px;color:#9ba5ac;font-size:18px;font-style:normal}.record-card-price b,.record-card-price small{margin-right:14px}.record-detail{position:sticky;top:84px;min-height:570px}.detail-heading{display:flex;align-items:center;justify-content:space-between;padding:18px 20px;border-bottom:1px solid #e5eaee}.detail-heading>div:first-child{display:grid;gap:3px}.detail-heading small{color:#d87600;font-size:8px;font-weight:900;letter-spacing:.16em}.detail-heading h2{margin:0;font-size:17px}.detail-heading span{color:#7f8a93;font-size:10px}.detail-heading-actions{display:flex;align-items:center;gap:8px}.detail-heading-actions button{height:34px;border:0;border-radius:7px;padding:0 12px;background:var(--orange);color:#17212b;font-size:10px;font-weight:900}.detail-tabs{display:flex;padding:0 16px;border-bottom:1px solid #e7ebee}.detail-tabs button{position:relative;height:46px;border:0;background:none;padding:0 14px;color:#7a8690;font-size:11px;font-weight:800}.detail-tabs button.active{color:#17212b}.detail-tabs button.active:after{position:absolute;right:10px;bottom:0;left:10px;height:3px;background:var(--orange);content:""}.detail-tabs i{margin-left:4px;padding:2px 5px;border-radius:8px;background:#eef1f4;font-size:8px;font-style:normal}.overview-panel,.option-detail-panel,.detail-history{padding:18px}.overview-metrics{display:grid;grid-template-columns:repeat(3,1fr);gap:10px}.overview-metrics article{display:grid;gap:3px;padding:13px;border:1px solid #e4e9ed;border-radius:9px;background:#f8fafb}.overview-metrics small{color:#83909a;font-size:9px}.overview-metrics b{font-size:18px}.overview-metrics span{color:#8b969f;font-size:9px}.primary-plan{margin-top:12px;border:1px solid #f1c887;border-radius:9px;background:#fff9ee}.primary-plan>header{display:flex;justify-content:space-between;padding:9px 12px;border-bottom:1px solid #f5dfba}.primary-plan>header b{color:#a96000;font-size:10px}.primary-plan>header span{color:#a77a3b;font-size:8px}.primary-plan>div{display:flex;align-items:center;justify-content:space-between;padding:13px}.primary-plan>div span{display:grid;gap:3px}.primary-plan strong{font-size:13px}.primary-plan small{color:#7e8a93;font-size:9px}.primary-plan>div>b{color:#d87500;font-size:20px}.overview-info{margin-top:12px;border:1px solid #e3e8ec;border-radius:9px}.overview-info>header,.outcome-card>header{padding:11px 13px;border-bottom:1px solid #e8ecef;font-size:11px;font-weight:900}.overview-info>div{display:grid;grid-template-columns:72px 1fr 72px 1fr;gap:9px 8px;padding:13px;font-size:9px}.overview-info span{color:#84909a}.overview-info b{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.outcome-card{margin-top:12px;border:1px solid #e3e8ec;border-radius:9px}.outcome-card>div{display:flex;align-items:center;gap:12px;flex-wrap:wrap;padding:13px;color:#6c7882;font-size:9px}.outcome-card span b{color:#26313b}.view-options{width:100%;height:38px;margin-top:12px;border:0;border-radius:7px;background:#17212b;color:#fff;font-size:10px;font-weight:900}.option-detail-panel>header{display:flex;align-items:center;justify-content:space-between;margin-bottom:12px}.option-detail-panel>header>div{display:grid;gap:3px}.option-detail-panel>header b{font-size:12px}.option-detail-panel>header span{color:#84909a;font-size:9px}.option-detail-panel>header em{padding:5px 8px;border-radius:10px;background:#eef2f4;color:#596873;font-size:9px;font-style:normal}.detail-country-groups{display:grid;gap:8px}.detail-country-groups details{overflow:hidden;border:1px solid #e2e8ec;border-radius:9px}.detail-country-groups summary{display:flex;align-items:center;justify-content:space-between;padding:12px 13px;background:#f8fafb;cursor:pointer;list-style:none}.detail-country-groups summary::-webkit-details-marker{display:none}.detail-country-groups summary>span{display:flex;align-items:center;gap:7px}.detail-country-groups summary b{font-size:11px}.detail-country-groups summary small{padding:3px 6px;border-radius:9px;background:#edf1f4;color:#687680;font-size:8px}.detail-country-groups summary i{color:#72808a;font-size:8px;font-style:normal}.detail-option-head,.detail-country-groups article{display:grid;grid-template-columns:minmax(145px,1.45fr) .65fr repeat(4,.58fr);gap:6px;align-items:center}.detail-option-head{padding:8px 12px;background:#f1f5f7;color:#7b8790;font-size:7px}.detail-country-groups article{min-height:48px;padding:7px 12px;border-top:1px solid #edf1f3;font-size:8px}.detail-country-groups article.primary{background:#fff9ed;box-shadow:inset 3px 0 var(--orange)}.detail-country-groups article.deal{box-shadow:inset 3px 0 #20a365}.detail-country-groups article>span{position:relative;display:grid;min-width:0;gap:2px}.detail-country-groups article>span b,.detail-country-groups article>span small{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.detail-country-groups article>span small{color:#86929b;font-size:7px}.detail-country-groups article>span em{position:absolute;right:0;top:0;padding:2px 4px;border-radius:7px;background:#fff0d5;color:#b66800;font-size:7px;font-style:normal}.detail-country-groups article>span .deal-badge{top:18px;background:#e7f7ed;color:#16834e}.detail-country-groups article>i{font-style:normal;font-weight:800}.detail-history{margin:18px;background:#fff}.detail-empty{display:grid;min-height:570px;place-content:center;gap:7px;color:#87939c;text-align:center}.detail-empty b{color:#394650;font-size:14px}.detail-empty span{font-size:10px}@media(max-width:1150px){.record-workspace{grid-template-columns:1fr}.record-detail{position:static;min-height:0}.record-list{max-height:520px;overflow:auto}}@media(max-width:700px){.record-card{grid-template-columns:48px minmax(0,1fr)}.record-card-price{display:none}.overview-metrics{grid-template-columns:1fr}.overview-info>div{grid-template-columns:70px 1fr}.detail-option-head,.detail-country-groups article{min-width:600px}.detail-country-groups details{overflow-x:auto}}
.quote-record-table>header,.quote-record-table>article{grid-template-columns:minmax(340px,1.55fr) minmax(220px,1.15fr) minmax(250px,1fr) minmax(260px,.9fr);gap:24px;padding:14px 18px}.quote-record-table>article{min-height:88px}.record-row-actions{display:flex!important;align-items:center;justify-content:center;gap:12px}.record-row-actions>em{box-sizing:border-box;min-width:68px;padding:7px 9px;border:1px solid transparent;border-radius:7px;font-size:11px;font-style:normal;font-weight:900;line-height:1;text-align:center;white-space:nowrap}.record-row-actions>em.pending{border-color:#f3b3af;background:#fff0ef;color:#c9342c}.record-row-actions>em.won{border-color:#a9dfbd;background:#e6f7ed;color:#087c43}.record-row-actions>em.lost{border-color:#cbd1d6;background:#edf0f2;color:#4e5a64}.record-row-actions button{box-sizing:border-box;height:40px;padding:0 17px;border-radius:7px;font-size:12px;font-weight:900;white-space:nowrap;cursor:pointer;transition:background .16s,border-color .16s,box-shadow .16s,transform .16s}.view-record{border:1px solid #ffad37;background:#fff8ed;color:#a95f00;box-shadow:0 3px 9px rgba(190,105,0,.08)}.view-record:hover{border-color:#f3940d;background:#fff1dc;box-shadow:0 5px 13px rgba(190,105,0,.14);transform:translateY(-1px)}.record-drawer>header>div{display:grid;gap:3px}.record-drawer>header span{color:#7d8992;font-size:10px}.drawer-tabs{margin:0 -25px;padding:0 25px}.record-drawer .overview-panel,.record-drawer .option-detail-panel{padding-inline:0}.drawer-view-footer{margin-top:18px;border-top:1px solid #e5eaee}.drawer-view-footer .primary{background:var(--orange);color:#17212b}.record-drawer .detail-history{margin-inline:0}.quote-record-table>header span:last-child{text-align:center}@media(max-width:1180px){.quote-record-table{overflow:auto}.quote-record-table>header,.quote-record-table>article{min-width:1040px}}@media(max-width:720px){.record-drawer{padding:18px}.drawer-tabs{margin-inline:-18px;padding-inline:18px}.record-drawer .overview-metrics{grid-template-columns:1fr}.record-drawer .overview-info>div{grid-template-columns:70px 1fr}.record-row-actions{justify-content:center}}
.record-drawer>*{flex-shrink:0}.deal-line-editor{display:grid;gap:0;margin-top:14px;overflow:hidden;border:1px solid #e0e6ea;border-radius:9px;background:#fff}.deal-line-editor>header{display:flex;align-items:center;justify-content:space-between;padding:13px 14px;border-bottom:1px solid #e6ebee;background:#f8fafb}.deal-line-editor>header>div{display:grid;gap:3px}.deal-line-editor>header b{font-size:12px}.deal-line-editor>header span{color:#82909a;font-size:9px}.deal-line-editor>header button{height:34px;padding:0 11px;border:1px solid #ffad37;border-radius:6px;background:#fff8ed;color:#a96000;font-size:10px;font-weight:900}.deal-line-head,.deal-line-editor>article{display:grid;grid-template-columns:minmax(230px,1.7fr) 105px 105px 90px 48px;gap:8px;align-items:center}.deal-line-head{padding:8px 12px;background:#f1f5f7;color:#79858f;font-size:8px}.deal-line-editor>article{padding:10px 12px;border-top:1px solid #edf1f3}.deal-line-editor select{min-width:0;height:38px;border:1px solid #dce3e8;border-radius:6px;background:#fff;padding:0 9px;font-size:10px}.deal-line-editor>article label{display:flex;align-items:center;height:38px;margin:0;border:1px solid #dce3e8;border-radius:6px;background:#fff;overflow:hidden}.deal-line-editor>article label input{min-width:0;height:36px;border:0;padding:0 7px}.deal-line-editor>article label i{padding:0 7px;color:#78858f;font-size:9px;font-style:normal}.deal-line-editor>article strong{color:#b76400;font-size:12px;text-align:right}.deal-line-editor>article>button{height:32px;border:0;background:none;color:#d34c43;font-size:9px}.deal-line-empty{padding:30px 14px;color:#89949d;font-size:10px;text-align:center}.deal-line-editor>footer{display:flex;justify-content:flex-end;gap:18px;margin:0;padding:11px 14px;border-top:1px solid #e6ebee;background:#fffaf1;color:#63717b;font-size:10px}.deal-line-editor>footer b{color:#17212b}.deal-line-editor>footer strong{color:#d87500;font-size:14px}.saved-deal-lines{display:grid!important;width:100%;padding-top:0!important;border-top:1px dashed #e3e8ec}.saved-deal-lines article{display:flex;align-items:center;justify-content:space-between;width:100%;padding:8px 0;border-top:1px solid #f0f2f4}.saved-deal-lines article:first-child{border-top:0}.saved-deal-lines article span{display:grid;gap:2px}.saved-deal-lines article small{color:#85919a;font-size:8px}.saved-deal-lines article strong{color:#b76400;font-size:11px}@media(max-width:720px){.deal-line-editor{overflow-x:auto}.deal-line-head,.deal-line-editor>article{min-width:650px}.deal-line-editor>footer{min-width:620px}}
.quote-record-image{flex:0 0 48px;width:48px;height:48px;border:1px solid #e4e9ee;border-radius:8px;background:#f5f7fa;color:#a96000;font-size:17px;font-weight:900}
.quote-record-image.empty{border:0;background:transparent}
.bundle-snapshot{margin-top:12px;overflow:hidden;border:1px solid #e3e8ec;border-radius:9px;background:#fff}.bundle-snapshot>header{display:flex;align-items:center;justify-content:space-between;padding:11px 13px;border-bottom:1px solid #e8ecef}.bundle-snapshot>header b{font-size:11px}.bundle-snapshot>header span{color:#7e8a93;font-size:9px}.bundle-snapshot>div{display:grid}.bundle-snapshot article{display:grid;grid-template-columns:minmax(180px,1fr) repeat(3,auto);gap:12px;align-items:center;padding:10px 13px;border-top:1px solid #eef1f3;font-size:9px}.bundle-snapshot article:first-child{border-top:0}.bundle-snapshot article span{display:grid;gap:3px;min-width:0}.bundle-snapshot article b,.bundle-snapshot article small{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.bundle-snapshot article small{color:#82909a}.bundle-snapshot article em{color:#586671;font-style:normal;white-space:nowrap}.bundle-snapshot.legacy p{margin:0;padding:12px 13px;color:#65727c;font-size:10px}@media(max-width:720px){.bundle-snapshot article{grid-template-columns:1fr 1fr}.bundle-snapshot article span{grid-column:1/-1}}
.quote-record-table>header,.quote-record-table>article{grid-template-columns:minmax(300px,1.45fr) minmax(180px,1fr) minmax(210px,.9fr) minmax(110px,.55fr) minmax(230px,.85fr)}.difference-cell{display:grid!important;min-width:104px;justify-items:start!important;gap:3px;border:0!important;border-radius:8px!important;background:#f2f5f7!important;padding:9px 11px!important;color:#60707b!important;text-align:left;cursor:pointer}.difference-cell b{font-size:11px}.difference-cell span{font-size:9px}.difference-cell.lower{background:#fff0ef!important;color:#c8352e!important}.difference-cell.higher{background:#fff3df!important;color:#b96700!important}.difference-cell.equal{background:#e9f7ef!important;color:#0c7f49!important}.difference-cell.missing{background:#f1f3f5!important;color:#697680!important}.difference-cell:hover{box-shadow:0 4px 12px rgba(24,36,46,.1);transform:translateY(-1px)}
.difference-mask{position:fixed;inset:0;z-index:80;display:grid;place-items:center;padding:24px;background:rgba(20,30,39,.45);backdrop-filter:blur(3px)}.difference-dialog{display:flex;width:min(720px,calc(100vw - 32px));max-height:min(820px,calc(100vh - 32px));flex-direction:column;overflow:hidden;border-radius:14px;background:#fff;box-shadow:0 24px 70px rgba(15,27,37,.28)}.difference-dialog>header{display:flex;align-items:center;justify-content:space-between;padding:19px 22px;border-bottom:1px solid #e5eaee}.difference-dialog>header>div{display:grid;gap:3px}.difference-dialog>header small{color:#d87500;font-size:8px;font-weight:900;letter-spacing:.15em}.difference-dialog>header h2{margin:0;font-size:18px}.difference-dialog>header span{color:#7d8992;font-size:10px}.difference-dialog>header>button{width:32px;height:32px;border:0;border-radius:50%;background:#f0f3f5;color:#65737d;font-size:18px}.difference-dialog-body{display:grid;gap:13px;padding:18px 22px;overflow:auto}.difference-hero{overflow:hidden;border:1px solid #e1e7eb;border-radius:10px;background:#fbfcfd}.difference-hero>div{display:grid;grid-template-columns:1fr auto 1fr;align-items:center;gap:16px;padding:17px}.difference-hero span{display:grid;justify-items:center;gap:4px}.difference-hero small{color:#81909a;font-size:9px}.difference-hero span b{font-size:23px}.difference-hero i{color:#a8b1b8;font-size:24px;font-style:normal}.difference-hero>strong{display:block;padding:11px;background:#f2f5f7;color:#566570;font-size:14px;text-align:center}.difference-hero.lower>strong{background:#fff0ef;color:#cc3932}.difference-hero.higher>strong{background:#fff2df;color:#bd6900}.difference-hero.equal>strong{background:#e8f7ee;color:#0c8250}.difference-breakdown{overflow:hidden;border:1px solid #e1e7eb;border-radius:10px}.difference-breakdown>header{display:flex;justify-content:space-between;padding:11px 13px;background:#f7f9fa}.difference-breakdown>header b{font-size:11px}.difference-breakdown>header span{color:#82909a;font-size:9px}.difference-breakdown>article{display:grid;grid-template-columns:minmax(180px,1.4fr) 1fr 1fr .7fr;gap:10px;align-items:center;padding:12px 13px;border-top:1px solid #edf0f2}.difference-breakdown article>span{display:grid;gap:3px}.difference-breakdown article small{color:#84919a;font-size:8px}.difference-breakdown article b{font-size:10px}.difference-channel b{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.difference-value{justify-items:end}.difference-value>b{font-size:12px!important}.difference-value.lower{color:#cc3932}.difference-value.higher{color:#bd6900}.difference-value.equal{color:#0c8250}.difference-value.missing{color:#7b8790}.difference-empty{padding:30px;color:#7e8b94;font-size:10px;text-align:center}.difference-tip{margin:0;padding:10px 12px;border-radius:8px;background:#fff8e9;color:#79613a;font-size:9px;line-height:1.6}.difference-dialog>footer{display:flex;justify-content:flex-end;gap:9px;padding:13px 22px;border-top:1px solid #e6ebee}.difference-dialog>footer button{height:38px;border:1px solid #dbe2e7;border-radius:7px;background:#fff;padding:0 15px;font-size:10px;font-weight:900}.difference-dialog>footer .primary{border-color:#ff9500;background:#ff9500;color:#17212b}@media(max-width:1180px){.quote-record-table>header,.quote-record-table>article{min-width:1120px}}@media(max-width:620px){.difference-mask{padding:0}.difference-dialog{width:100vw;max-height:100vh;height:100vh;border-radius:0}.difference-breakdown{overflow-x:auto}.difference-breakdown>article{min-width:620px}.difference-hero>div{gap:8px}.difference-hero span b{font-size:18px}}
.quote-record-table .record-row-actions{display:flex;flex-direction:column;align-items:stretch}.record-row-actions>small{white-space:normal}.record-row-actions>em{align-self:center}
.quote-info-copy .record-sku{font-size:16px!important;font-weight:800;line-height:1.4;color:#17212b;white-space:normal;overflow-wrap:anywhere;overflow:visible}.quote-info-copy .record-commission{justify-self:start;padding:3px 7px;border-radius:5px;background:#f2f5f7;color:#60707b;font-size:11px;line-height:1.5;white-space:normal}.quote-info-copy .record-commission.applied{background:#fff3df;color:#a75c00}.quote-info-copy .record-number{font-size:10px;line-height:1.5;white-space:normal;overflow-wrap:anywhere;color:#85919a}
/* List-only presentation: keep the detail drawer and quotation calculations unchanged. */
main{width:min(1680px,calc(100% - 48px))}
.heading span{font-size:15px}
.filters{gap:12px;flex-wrap:wrap}
.filters label{font-size:13px;color:#53616c}
.filters input,.filters select,.filters button{font-size:14px;height:40px}
.filters .search{width:360px;height:40px}
.filters .search input{height:38px}
.filters>b{font-size:14px}
.record-date-filters{gap:10px;font-size:14px;margin:14px 0}
.record-date-filters input,.record-date-filters button,.record-pagination button,.record-pagination select{min-height:38px;font:inherit}
.record-date-filters small{flex-basis:100%;font-size:13px;line-height:1.5;color:#63717d}
.record-date-filters .record-export-actions{margin:0 0 0 auto}
.record-pagination{font-size:14px;padding:14px 16px}
.quote-record-table{overflow-x:auto;box-shadow:none}
.quote-record-table>header,.quote-record-table>article{
  box-sizing:border-box;width:100%;min-width:1180px;
  grid-template-columns:minmax(330px,1.65fr) minmax(145px,.8fr) minmax(170px,.9fr) minmax(170px,.85fr) minmax(240px,1fr);
  gap:16px;padding:14px 16px;
}
.quote-record-table>header{font-size:14px;font-weight:600;color:#52616d}
.quote-record-table>header span:last-child{text-align:left}
.quote-record-table>article{min-height:0;font-size:16px;line-height:1.45}
.quote-record-table>article+article{border-top:2px solid #cbd5df}
.quote-record-table>article>div{gap:8px}
.quote-record-table .quote-info{display:flex!important;align-items:center;justify-content:flex-start;gap:12px;min-width:0}
.quote-record-table .quote-record-image{flex:0 0 48px;width:48px;min-width:48px;height:48px}
.quote-record-table .quote-info-copy{display:grid;flex:1;min-width:0;gap:4px}
.quote-record-table .record-product-title{display:flex;flex-wrap:wrap;align-items:baseline;gap:4px 12px;min-width:0}
.quote-record-table .record-sku{font-size:18px!important;line-height:1.4}
.quote-record-table .record-product-title strong{font-size:14px;color:#52616d;white-space:normal;overflow-wrap:anywhere}
.quote-record-table .record-product-meta{display:flex;flex-wrap:wrap;align-items:center;gap:4px 10px}
.quote-record-table>article small{font-size:13px;line-height:1.5;color:#63717d;white-space:normal;overflow-wrap:anywhere}
.quote-record-table .record-number{font-size:13px;line-height:1.5;color:#63717d}
.quote-record-table .record-commission{font-size:13px;line-height:1.5;padding:2px 6px}
.quote-record-table .record-customer{justify-items:start;align-content:center}
.quote-record-table .record-customer>b{font-size:16px;color:#17212b;white-space:normal;overflow-wrap:anywhere}
.quote-record-table .record-customer-grade{padding:3px 8px;border-radius:6px;background:#edf5f0;color:#276848}
.quote-record-table .route-summary>b{font-size:16px;line-height:1.5}
.quote-record-table .country-tags{flex-wrap:wrap;gap:5px;overflow:visible}
.quote-record-table .country-tags i,.quote-record-table .country-tags em{font-size:12px;line-height:1.4;padding:3px 6px}
.quote-record-table .difference-cell{box-sizing:border-box;min-width:0;width:100%;padding:10px 12px!important;gap:5px;align-content:center}
.quote-record-table .difference-cell b{font-size:14px;line-height:1.5;white-space:normal;overflow-wrap:anywhere}
.quote-record-table .difference-cell span{font-size:13px;line-height:1.5}
.quote-record-table .record-row-actions{gap:7px}
.quote-record-table .finance-review{width:100%;min-height:38px;font-size:14px;line-height:1.4;padding:7px 9px}
.quote-record-table strong.finance-review{white-space:normal;font-weight:600}
.quote-record-table .record-row-actions>small{font-size:12px}
.record-action-buttons{display:flex;align-items:center;gap:8px}
.record-action-buttons>em,.record-action-buttons>.reissue-quote{display:flex;flex:1;align-items:center;justify-content:center;box-sizing:border-box;min-height:36px;padding:6px 10px;border:1px solid;border-radius:6px;font-size:14px;line-height:1.4;font-style:normal;font-weight:600;white-space:nowrap}
.record-action-buttons>em.pending{border-color:#f3b3af;background:#fff0ef;color:#c9342c}
.record-action-buttons>em.won{border-color:#a9dfbd;background:#e6f7ed;color:#087c43}
.record-action-buttons>em.processed{border-color:#b4d3f0;background:#e8f3ff;color:#256da6}
.record-action-buttons>.reissue-quote{border-color:#ffb54e;background:#fff8ed;color:#a95f00}
.record-action-buttons>.reissue-quote:hover{background:#fff0d8;border-color:#e89820}
@media(max-width:720px){
  main{width:calc(100% - 24px);padding-top:20px}
  .filters .search{width:100%}
  .filters>b{margin-left:0}
  .record-date-filters .record-export-actions{margin-left:0}
  .record-pagination{gap:12px}
}
.lifecycle-tabs{display:flex;align-items:center;gap:24px;margin:20px 0 14px;border-bottom:1px solid #dfe5eb}.lifecycle-tabs button{padding:12px 8px;border:0;border-bottom:3px solid transparent;background:none;color:#66717c;font:inherit;font-weight:700;cursor:pointer}.lifecycle-tabs button.active{border-bottom-color:var(--orange);color:#17212b}.lifecycle-tabs small{margin-left:auto;color:#788590;font-size:12px}.lifecycle-toolbar{display:flex;align-items:center;gap:16px;flex-wrap:wrap;margin:12px 0 0;padding:12px 16px;border:1px solid #e1e7ec;border-radius:8px 8px 0 0;background:#fff;font-size:13px}.lifecycle-toolbar label{display:flex;align-items:center;gap:8px}.lifecycle-toolbar>div{display:flex;gap:8px;margin-left:auto}.lifecycle-toolbar button{padding:8px 12px;border:1px solid #e0e5eb;border-radius:6px;background:#fff8ed;color:#925900;font:inherit;font-weight:600;cursor:pointer}.lifecycle-toolbar .trash-button{color:#bd3c32;background:#fff5f4;border-color:#edb7b2}.lifecycle-toolbar button:disabled{opacity:.45;cursor:not-allowed}.lifecycle-toolbar small{color:#77838e}.lifecycle-checkbox,.lifecycle-toolbar input{flex:0 0 17px;width:17px;height:17px;accent-color:#ed990f;cursor:pointer}.lifecycle-checkbox:disabled{cursor:not-allowed}.quote-info-copy .lifecycle-metadata{white-space:normal;line-height:1.6;color:#796341}.quote-info-copy .lifecycle-lock{white-space:normal;color:#8a7560;font-size:11px}.lifecycle-readonly{padding:12px;background:#fff8ed;border:1px solid #f0d9b6;border-radius:6px;font-size:12px}@media(max-width:720px){.lifecycle-tabs{gap:12px;flex-wrap:wrap}.lifecycle-tabs small{width:100%;margin:0 0 8px}.lifecycle-toolbar>div{margin-left:0}}
</style>
