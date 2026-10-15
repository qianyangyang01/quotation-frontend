<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import PriceExceptionSelect from './PriceExceptionSelect.vue'
import { priceExceptionLabel, type PriceExceptionType } from '@/data/priceExceptions'
import type { QuotationRecord, QuotationReviewState, ReviewAction } from '@/data/quotationRecords'
import { fobItemReviews, fobItemReviewLabel, reviewFobItems, type FobReviewResult } from '@/services/fobCorrections'
import type { FobQuotationSnapshot } from '@/services/fobQuotationRecords'
import { canViewPurchaseCost } from '@/data/quotationVisibility'

const props = defineProps<{ record: QuotationRecord; state: QuotationReviewState; account: string; canReview: boolean; busy: boolean }>()
const emit = defineEmits<{ saved: [record: QuotationRecord]; action: [action: ReviewAction]; reload: [] }>()
const exceptionTypes = ref<Record<number, PriceExceptionType | ''>>({})
const checked = ref<number[]>([]), reasons = ref<Record<number, string>>({}), note = ref(''), error = ref(''), sending = ref(false)
const query = ref(''), statusFilter = ref(''), page = ref(0), pageSize = ref(20), expanded = ref<number[]>([])
const result = ref<FobReviewResult>('approved'), returnDialog = ref<HTMLDialogElement | null>(null), commonReason = ref('')
const returnIndexes = ref<number[]>([]), scroll = ref<HTMLElement | null>(null), notice = ref('')
const states = computed(() => fobItemReviews(props.record))
const own = computed(() => props.state.financeReviewStatus === 'reviewing' && props.state.financeReviewClaimedAccount === props.account)
const stale = computed(() => props.record._reviewVersion !== props.state._reviewVersion || props.record._version !== props.state._version)
const disabled = computed(() => sending.value || props.busy || !props.canReview || !own.value || stale.value)
const entries = computed(() => (props.record.fob?.items ?? []).map((item, index) => ({ item, index, state: states.value[index]! })))
const filtered = computed(() => entries.value.filter(({ item, state }) => item.product.sku.toLowerCase().includes(query.value.trim().toLowerCase()) && (!statusFilter.value || (statusFilter.value === 'logistics-exempt' ? state.status === 'approved' && state.reviewResult === 'logistics-exempt' : statusFilter.value === 'approved' ? state.status === 'approved' && state.reviewResult !== 'logistics-exempt' : state.status === statusFilter.value))))
const pages = computed(() => Math.max(1, Math.ceil(filtered.value.length / pageSize.value)))
const visible = computed(() => filtered.value.slice(page.value * pageSize.value, (page.value + 1) * pageSize.value))
const pageSelected = computed(() => checked.value.filter(index => visible.value.some(entry => entry.index === index)).length)
const hiddenSelected = computed(() => checked.value.filter(index => !filtered.value.some(entry => entry.index === index)).length)
const allPage = computed(() => visible.value.length > 0 && visible.value.every(entry => checked.value.includes(entry.index)))
const hasRejected = computed(() => checked.value.some(index => states.value[index]?.status === 'rejected'))
const hasUnsavedChanges = computed(() => checked.value.length > 0 || !!note.value.trim() || Object.values(reasons.value).some(value => !!value.trim()) || Object.values(exceptionTypes.value).some(Boolean))
defineExpose({ hasUnsavedChanges })
watch(() => props.record.id, () => { query.value = ''; statusFilter.value = ''; page.value = 0; expanded.value = []; notice.value = '' })
watch(() => [props.record.id, props.record._reviewVersion, props.record._version], () => {
  checked.value = []; reasons.value = {}; exceptionTypes.value = {}; note.value = ''; error.value = ''; returnIndexes.value = []; returnDialog.value?.close()
}, { immediate: true })
watch([query, statusFilter, pageSize], () => { page.value = 0 })
watch(pages, count => { page.value = Math.min(page.value, count - 1) })
watch([page, query, statusFilter, pageSize], async () => { await nextTick(); if (scroll.value) scroll.value.scrollTop = 0 })
function selectPage() {
  if (disabled.value) return
  const ids = visible.value.map(entry => entry.index)
  checked.value = allPage.value ? checked.value.filter(index => !ids.includes(index)) : [...new Set([...checked.value, ...ids])]
}
function selectFiltered() { if (!disabled.value) checked.value = [...new Set([...checked.value, ...filtered.value.map(entry => entry.index)])] }
function toggle(index: number) { expanded.value = expanded.value.includes(index) ? expanded.value.filter(i => i !== index) : [...expanded.value, index] }
function ranges(item: FobQuotationSnapshot) { return item.displayMode === 'tiers' ? item.ranges ?? [] : item.current ? [item.current] : [] }
function lines(item: FobQuotationSnapshot, index: number) {
  const rows = ranges(item)
  return (rows.length ? (expanded.value.includes(index) ? rows : rows.slice(0, 1)) : [null]).map(range => ({ range, tier: range ? item.tiers?.find(t => range.minQty >= t.minQty && (t.maxQty == null || range.minQty <= t.maxQty)) : undefined }))
}
function money(value: number | string | undefined) { return value == null || !Number.isFinite(Number(value)) ? '—' : Number(value).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 4 }) }
function openReturn() {
  if (disabled.value || !checked.value.length) return
  error.value = ''; returnIndexes.value = [...checked.value]; commonReason.value = ''; returnDialog.value?.showModal()
}
function applyReason() { for (const index of returnIndexes.value) reasons.value[index] = commonReason.value.trim() }
async function review(action: 'approve' | 'return') {
  if (disabled.value) return
  const indexes = action === 'return' ? returnIndexes.value : checked.value
  if (!indexes.length || (action === 'approve' && (result.value === 'rejected' || hasRejected.value))) return
  const requestId = props.record.id, requestAccount = props.account
  const items = indexes.map(index => ({ index, reason: action === 'return' ? (reasons.value[index] || '').trim() : '', ...(action === 'return' && exceptionTypes.value[index] ? { exceptionType: exceptionTypes.value[index] as PriceExceptionType } : {}) }))

  if (action === 'return' && items.some(item => !item.exceptionType)) { error.value = '请为每个退回SKU选择异常类型'; return }
  sending.value = true; error.value = ''
  try {
    const updated = await reviewFobItems(props.record, action, items, action === 'return' ? 'rejected' : result.value, note.value.trim())
    if (props.record.id !== requestId || props.account !== requestAccount) return
    checked.value = []; reasons.value = {}; exceptionTypes.value = {}; note.value = ''; returnIndexes.value = []; returnDialog.value?.close()
    notice.value = `已${action === 'return' ? '退回' : '审核'}${items.length}项。${updated.financeReviewStatus === 'pending' ? '仍有待审核项，点击“继续审核”处理下一批。' : ''}`
    emit('saved', updated)
  } catch (e) { error.value = e instanceof Error ? e.message : '提交失败，请重试' }
  finally { sending.value = false }
}
</script>

<template>
  <section class="item-review" aria-label="批量FOB逐项审核">
    <header class="review-toolbar"><div><b>逐项审核 · {{ entries.length }}项</b><span>已通过 {{ states.filter(s => s.status === 'approved').length }} · 价格异常 {{ states.filter(s => s.status === 'rejected').length }} · 待审核 {{ states.filter(s => s.status === 'pending').length }}</span></div><button v-if="canReview && own" class="cancel-review" :disabled="disabled" @click="emit('action', { action: 'cancel' })">取消审核</button></header>
    <div class="review-filters"><input v-model="query" type="search" placeholder="搜索SKU" aria-label="审核SKU搜索"><select v-model="statusFilter" aria-label="逐项审核状态"><option value="">全部审核状态</option><option value="pending">待审核</option><option value="logistics-exempt">物流免审-采购已审</option><option value="approved">审核通过</option><option value="rejected">价格异常 / 已退回</option></select><button @click="expanded = visible.every(entry => expanded.includes(entry.index)) ? expanded.filter(index => !visible.some(entry => entry.index === index)) : [...new Set([...expanded, ...visible.map(entry => entry.index)])]">{{ visible.length && visible.every(entry => expanded.includes(entry.index)) ? '收起本页阶梯' : '展开本页阶梯' }}</button></div>
    <p v-if="stale" class="review-alert" role="alert">报价或审核已变化，请重新加载后操作。<button @click="emit('reload')">重新加载</button></p>
    <p v-if="error" class="review-alert" role="alert">{{ error }}</p><p v-if="notice" class="review-notice" role="status">{{ notice }}</p>
    <div ref="scroll" class="review-table-scroll" role="region" aria-label="商品审核表，可局部滚动" tabindex="0">
      <table :class="{ 'no-selection': !canReview }"><thead><tr><th v-if="canReview" class="select-col"><input type="checkbox" :checked="allPage" :indeterminate="pageSelected > 0 && !allPage" :disabled="disabled || !visible.length" aria-label="全选本页审核商品" @change="selectPage"></th><th class="sku-col">SKU</th><th>数量区间</th><template v-if="canViewPurchaseCost"><th>含票价 ¥</th><th>运费 ¥</th><th>成本 ¥</th><th>小单加价 ¥</th></template><th>报关价 $</th><th>不报关价 $</th><th class="status-col">审核状态</th></tr></thead>
        <tbody v-for="{ item, index, state } in visible" :key="index" :class="{ selected: checked.includes(index) }"><tr v-for="({ range, tier }, lineIndex) in lines(item, index)" :key="lineIndex">
          <td v-if="canReview && lineIndex === 0" class="select-col" :rowspan="lines(item, index).length"><input v-model="checked" type="checkbox" :value="index" :disabled="disabled" :aria-label="`选择审核 ${item.product.sku} 第${index + 1}项`"></td>
          <td v-if="lineIndex === 0" class="sku-col" :rowspan="lines(item, index).length"><b>{{ item.product.sku }}</b><small>汇率 {{ item.rate }}</small><button v-if="ranges(item).length > 1" class="text-button" :aria-expanded="expanded.includes(index)" @click="toggle(index)">{{ expanded.includes(index) ? `${ranges(item).length}档 · 收起` : `首档 · 展开全部${ranges(item).length}档` }}</button></td>
          <td class="quantity-col">{{ range ? `${range.minQty}${range.maxQty === range.minQty ? '' : range.maxQty == null ? '+' : '–' + range.maxQty}${range.unit}` : '未保存价格明细' }}</td>
          <template v-if="canViewPurchaseCost"><td class="money">{{ money(tier?.taxIncludedCny) }}</td><td class="money">{{ money(tier?.freightCny) }}</td><td class="money">{{ money(tier?.costCny) }}</td><td class="money" :title="range && range.extraCny == null ? '该历史报价未单独保存小单加价，最终报价沿用原快照' : undefined">{{ money(range?.extraCny) }}</td></template><td class="money price">{{ money(range?.declaredUsd) }}</td><td class="money price">{{ money(range?.undeclaredUsd) }}</td>
          <td v-if="lineIndex === 0" class="status-col" :rowspan="lines(item, index).length"><span class="status-badge" :class="state.status">{{ fobItemReviewLabel(state) }}</span><small v-if="state.reviewedBy">{{ state.reviewedBy }}</small><details v-if="state.priceException || state.reason || state.note"><summary>查看意见</summary><p><template v-if="state.status === 'rejected'">{{ priceExceptionLabel(state.priceException?.category) }} · </template>{{ state.reason || state.note }}</p></details></td>
        </tr></tbody>
      </table><p v-if="!visible.length" class="empty">没有符合条件的商品</p>
    </div>
    <nav class="review-pagination" aria-label="审核商品分页"><div v-if="canReview"><label><input type="checkbox" :checked="allPage" :disabled="disabled || !visible.length" @change="selectPage">全选本页</label><button class="text-button" :disabled="disabled || !filtered.length" @click="selectFiltered">选择全部筛选结果（{{ filtered.length }}）</button></div><div><span>共 {{ filtered.length }} 个SKU</span><label>每页<select v-model.number="pageSize" aria-label="每页审核SKU数"><option :value="10">10</option><option :value="20">20</option><option :value="50">50</option></select></label><button :disabled="page === 0" @click="page--">上一页</button><span>{{ page + 1 }} / {{ pages }}</span><button :disabled="page + 1 >= pages" @click="page++">下一页</button></div></nav>
    <footer v-if="canReview" class="review-action-dock">
      <template v-if="own"><div class="selection-summary" aria-live="polite"><b>已选 {{ checked.length }} 项（本页 {{ pageSelected }} 项，其他页 {{ checked.length - pageSelected }} 项）</b><button class="text-button" :disabled="disabled || !checked.length" @click="checked = []">清空选择</button><span v-if="hiddenSelected" class="review-alert">其中 {{ hiddenSelected }} 项被筛选隐藏，仍会一起提交</span></div>
        <div class="action-row"><fieldset :disabled="disabled" class="review-results"><legend>审核结果</legend><label class="exempt" :class="{ active: result === 'logistics-exempt' }"><input v-model="result" type="radio" value="logistics-exempt">物流免审-采购已审</label><label class="unavailable"><input type="radio" disabled><span>同渠道免审<small>FOB无国际渠道，不适用</small></span></label><label class="approved" :class="{ active: result === 'approved' }"><input v-model="result" type="radio" value="approved">审核通过</label><label class="rejected" :class="{ active: result === 'rejected' }"><input v-model="result" type="radio" value="rejected">价格异常</label></fieldset><div class="submit-buttons"><button class="return-button" :disabled="disabled || !checked.length" @click="openReturn">退回所选（{{ checked.length }}）</button><button class="confirm-button" :disabled="disabled || !checked.length || (result !== 'rejected' && hasRejected)" @click="result === 'rejected' ? openReturn() : review('approve')">{{ sending ? '正在提交…' : `确认审核（${checked.length}）` }}</button></div></div>
        <label class="review-note">审核意见（选填）<input v-model="note" maxlength="500" :disabled="disabled" placeholder="填写本次所选商品的审核意见"></label><small>仅处理所选SKU，未选商品保持原状态。{{ hasRejected ? '退回项须重新查询提交后才能通过。' : result === 'rejected' ? '确认后选择异常类型并退回所选商品。' : '点击退回所选后选择异常类型，说明可选填。' }}</small>
      </template>
      <button v-else-if="state.financeReviewStatus !== 'reviewing'" class="confirm-button" :disabled="busy || sending || stale" @click="emit('action', { action: 'claim' })">{{ states.some(s => s.status !== 'pending') ? '继续审核' : '开始逐项审核' }}</button><span v-else>{{ state.financeReviewClaimedBy }}审核中</span>
    </footer>
    <Teleport to="body"><dialog ref="returnDialog" class="fob-return-dialog" aria-label="填写所选SKU退回原因" @cancel="sending && $event.preventDefault()"><header><b>退回所选 {{ returnIndexes.length }} 项</b><button :disabled="sending" aria-label="关闭退回原因" @click="returnDialog?.close()">×</button></header><p>这些商品将标记为价格异常。业务员核实后重新提交，其他商品不变。</p><div class="common-reason"><label>统一原因（可选）<textarea v-model="commonReason" maxlength="500" :disabled="disabled" rows="2" /></label><button :disabled="disabled || !commonReason.trim()" @click="applyReason">应用到所选商品</button></div><div class="return-reasons"><div class="return-item" v-for="index in returnIndexes" :key="index">{{ record.fob?.items?.[index]?.product.sku }}<PriceExceptionSelect :model-value="exceptionTypes[index] || ''" :disabled="disabled" :label="`第${index + 1}项异常类型`" @update:model-value="exceptionTypes[index] = $event" /><textarea v-model="reasons[index]" maxlength="500" :disabled="disabled" :aria-label="`第${index + 1}项退回原因`" rows="2" placeholder="填写该商品的退回原因（选填）" /></div></div><p v-if="error || stale" class="review-alert" role="alert">{{ stale ? '报价或审核已变化，请关闭并重新加载后处理；原因尚未提交。' : error }}</p><footer><button :disabled="sending" @click="returnDialog?.close()">返回修改</button><button class="return-button" :disabled="disabled" @click="review('return')">{{ sending ? '正在退回…' : `确认退回${returnIndexes.length}项` }}</button></footer></dialog></Teleport>
  </section>
</template>

<style scoped>
.return-item{display:grid;gap:8px}

.item-review{display:flex;flex-direction:column;min-height:0;gap:10px;padding:14px 0;color:#25364b;font-size:13px;background:#fff}.review-toolbar,.review-toolbar>div,.review-filters,.review-pagination,.review-pagination>div,.selection-summary,.action-row,.submit-buttons{display:flex;align-items:center;gap:10px;flex-wrap:wrap}.review-toolbar,.review-pagination{justify-content:space-between}.review-toolbar span,small{font-size:12px;color:#718193}.review-toolbar b{font-size:16px}.review-filters input{flex:1;min-width:120px;max-width:270px}button,input,select,textarea{font:inherit;box-sizing:border-box}button,select,input[type=search],input:not([type]),textarea{border:1px solid #ccd8e4;border-radius:5px;padding:8px 10px;background:#fff;color:#284b70}button{cursor:pointer}button:disabled{opacity:.5;cursor:not-allowed}button:focus-visible,input:focus-visible,select:focus-visible,textarea:focus-visible{outline:2px solid #387ac2;outline-offset:2px}input[type=checkbox],input[type=radio]{width:17px;height:17px;flex-shrink:0;margin:0}label{display:inline-flex;align-items:center;gap:6px}.text-button{border:0;padding:5px 4px;color:#236bb0;background:transparent}.review-alert{color:#b52b25}.review-notice{color:#197542}.review-alert,.review-notice{margin:0}.cancel-review{margin-left:auto}
.review-table-scroll{min-height:130px;max-height:48vh;overflow:auto;overscroll-behavior:contain;scrollbar-gutter:stable;border:1px solid #dbe4ed;border-radius:6px;background:#fff}.review-table-scroll:focus-visible{outline:2px solid #387ac2}table{border-collapse:separate;border-spacing:0;min-width:1080px;width:100%;font-size:13px}th,td{padding:11px 12px;border-right:1px solid #e1e8ef;border-bottom:1px solid #e1e8ef;text-align:left;background:#fff}th{position:sticky;top:0;z-index:4;background:#f1f5fa;white-space:nowrap}td small{display:block;margin-top:5px}.select-col{position:sticky;left:0;width:44px;min-width:44px;box-sizing:border-box;text-align:center;z-index:2}.sku-col{position:sticky;left:44px;width:160px;min-width:160px;max-width:180px;z-index:2;overflow-wrap:anywhere}.no-selection .sku-col{left:0}th.select-col,th.sku-col{z-index:6}.status-col{position:sticky;right:0;min-width:158px;z-index:2;border-left:1px solid #dbe4ed}th.status-col{z-index:6}.selected td{background:#f6faff}.selected .sku-col,.selected .select-col{background:#edf5ff}.quantity-col,.money{white-space:nowrap}.money{text-align:right;font-variant-numeric:tabular-nums}.price{color:#165787;font-weight:600}.status-badge{display:inline-block;white-space:nowrap;font-size:12px;padding:5px 7px;border-radius:5px;background:#fff4dc;color:#966007}.status-badge.approved{color:#18844d;background:#edf8f0}.status-badge.rejected{color:#b52b25;background:#fff0ef}.status-col details{font-size:12px;max-width:180px;margin-top:6px}.status-col details p{white-space:pre-wrap;overflow-wrap:anywhere}.empty{text-align:center;padding:24px;color:#718193}.review-pagination{font-size:12px}.review-pagination select{padding:6px}.review-pagination button{min-height:34px}
.review-action-dock{background:#fff;border-top:1px solid #dbe4ed;padding:12px 0 0;display:grid;gap:10px;position:sticky;bottom:0;z-index:8}.review-results{display:flex;flex-wrap:wrap;gap:8px;border:0;padding:0;margin:0;min-width:0}.review-results legend{position:absolute;width:1px;height:1px;overflow:hidden;clip-path:inset(50%)}.review-results label{box-sizing:border-box;padding:10px 9px;min-height:46px;border:1px solid #dce3eb;border-radius:5px;white-space:nowrap;cursor:pointer;font-size:12px}.review-results small{display:block;margin-top:3px;font-size:11px}.review-results .active{box-shadow:inset 0 0 0 1px currentColor}.exempt{color:#7442ae;background:#f7f2ff}.approved{color:#18844d;background:#f1faf4}.rejected{color:#b52b25;background:#fff5f4}.review-results .unavailable{color:#84909c;background:#f4f6f8;cursor:not-allowed}.submit-buttons{margin-left:auto;gap:16px;flex-wrap:nowrap}.submit-buttons button{min-height:46px;min-width:140px;font-weight:700}.return-button{border:1px solid #df423d;color:#ba302b;background:#fff}.confirm-button{background:#ec8500;color:#fff;border:1px solid #dc7b00;font-weight:700;min-height:44px;padding:10px 20px}.review-note{font-size:12px}.review-note input{flex:1;max-width:600px}.selection-summary b{font-size:13px}
.fob-return-dialog{box-sizing:border-box;width:min(670px,calc(100vw - 24px));max-height:85dvh;padding:22px;border:1px solid #dbe4ed;border-radius:10px;color:#25364b;font:14px Arial,'Microsoft YaHei',sans-serif}.fob-return-dialog::backdrop{background:#17212b66}.fob-return-dialog header,.fob-return-dialog footer,.common-reason{display:flex;gap:12px;align-items:center;justify-content:space-between}.fob-return-dialog header b{font-size:18px}.fob-return-dialog p{font-size:13px;line-height:1.5}.common-reason label{flex:1}.fob-return-dialog label{display:grid;gap:7px}.fob-return-dialog textarea{width:100%;resize:vertical}.return-reasons{max-height:36dvh;overflow:auto;display:grid;gap:12px;margin:16px 0;padding-right:8px}.fob-return-dialog footer{justify-content:flex-end}.fob-return-dialog footer button{min-height:44px}
@media(max-width:900px){.action-row{display:grid;gap:10px}.submit-buttons{justify-content:flex-end}.review-table-scroll{max-height:38vh}.review-results{gap:6px}.review-results label{padding:7px}.review-pagination{gap:6px}}@media(max-width:540px){.item-review{font-size:12px;gap:7px}.review-toolbar{flex-wrap:nowrap}.review-toolbar>div{display:block}.review-toolbar b{font-size:14px}.cancel-review{white-space:nowrap}.review-table-scroll .status-col{position:static}.review-table-scroll th.status-col{position:sticky;right:auto}.sku-col{min-width:115px;width:115px}.review-pagination>div{gap:6px}.review-toolbar span{display:block}.review-results{display:grid;grid-template-columns:1fr 1fr}.review-results label{white-space:normal;min-height:44px}.submit-buttons{width:100%;gap:12px}.submit-buttons button{flex:1;min-width:0}.review-table-scroll{min-height:100px}.review-note{align-items:flex-start;flex-direction:column}.review-note input{width:100%}.selection-summary{gap:5px}.common-reason{flex-direction:column;align-items:stretch}}
</style>
