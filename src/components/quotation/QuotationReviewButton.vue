<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { type QuotationRecord, type QuotationReviewState, type ReviewAction } from '@/data/quotationRecords'
import PriceExceptionSelect from './PriceExceptionSelect.vue'
import type { PriceExceptionType } from '@/data/priceExceptions'

const props = defineProps<{ record: QuotationRecord; state: QuotationReviewState; account: string; busy: boolean }>()
const emit = defineEmits<{ action: [value: ReviewAction]; reload: [] }>()
const dialog = ref<HTMLDialogElement | null>(null)
const note = ref('')
const rejecting = ref(false), exceptionType = ref<PriceExceptionType | ''>(''), exceptionSkus = ref<string[]>([])
const skuOptions = computed(() => [...new Set(props.record.quoteMode === 'bundle' ? (props.record.bundleItems || []).map(item => item.sku) : [props.record.primarySku])].filter(sku => /^[A-Z0-9][A-Z0-9._/-]{0,95}$/.test(sku)))
const canReject = computed(() => !!exceptionType.value && (exceptionType.value !== 'purchase-price' || exceptionSkus.value.length > 0))
watch(() => props.record.id, () => { note.value = '' })
watch(() => props.state.financeReviewStatus, value => { if (value !== 'reviewing') note.value = '' })
const active = computed(() => props.state.financeReviewStatus === 'reviewing')
const own = computed(() => active.value && props.state.financeReviewClaimedAccount === props.account)
const stale = computed(() => (props.state._version ?? 0) > (props.record._version ?? 0))
const claimChanged = computed(() => (props.state._reviewVersion ?? 0) !== (props.record._reviewVersion ?? 0))
const needsReload = computed(() => stale.value || claimChanged.value)
const label = computed(() => {
  if (props.busy) return '正在提交…'
  if (active.value && !own.value) return `${props.state.financeReviewClaimedBy || '其他财务'}审核中`
  if (needsReload.value) return '重新加载详情'
  if (own.value) return '审核完成'
  return props.state.financeReviewStatus === 'pending' ? '开始审核' : '重新审核'
})
const hint = computed(() => {
  if (active.value && !own.value) return '由当前审核人完成审核'
  if (needsReload.value) return `${stale.value ? '报价内容已更新' : '审核占用已变化'}，请重新加载并核对后完成审核。`
  return own.value ? '选择物流免审-采购已审、同渠道免审、审核通过或价格异常' : '开始审核并占用当前报价'
})
watch([() => props.record.id, () => props.account, () => props.state._version, () => props.state._reviewVersion, () => props.state.financeReviewStatus, () => props.state.financeReviewClaimedAccount], () => { dialog.value?.close(); rejecting.value = false; exceptionType.value = ''; exceptionSkus.value = [] })
function submit() {
  if (props.busy || (active.value && !own.value)) return
  if (needsReload.value) { emit('reload'); return }
  if (own.value) { rejecting.value = false; dialog.value?.showModal() }
  else emit('action', { action: 'claim' })
}
function complete(financeReviewStatus: 'approved' | 'rejected' | 'channel-exempt' | 'logistics-exempt') {
  if (props.busy || !own.value || needsReload.value) return
  if (financeReviewStatus === 'rejected') {
    if (!canReject.value) return
    emit('action', { action: 'complete', financeReviewStatus, note: note.value.trim(), exceptionType: exceptionType.value as PriceExceptionType, ...(exceptionType.value === 'purchase-price' ? { exceptionSkus: exceptionSkus.value } : {}) })
  } else {
    dialog.value?.close()
    emit('action', { action: 'complete', financeReviewStatus, note: note.value.trim() })
  }
}
function openRejection() { rejecting.value = true; exceptionType.value = ''; exceptionSkus.value = skuOptions.value.length === 1 ? [...skuOptions.value] : [] }
</script>

<template>
  <button type="button" class="review-button" :disabled="busy || (active && !own)" :title="hint" @click="submit">{{ label }}</button>
  <Teleport to="body">
    <dialog ref="dialog" class="review-result-dialog" :aria-label="rejecting ? '确认价格异常' : '选择审核结果'" @cancel="busy && $event.preventDefault()" @click="!busy && ($event.target === dialog) && dialog?.close()">
      <header><strong>{{ rejecting ? '确认价格异常' : '选择审核结果' }}</strong><button type="button" :disabled="busy" aria-label="关闭审核结果" @click="dialog?.close()">×</button></header>
      <p class="review-context">{{ record.primarySku }} · {{ record.customerName }}<small>{{ record.no }}</small></p>
      <template v-if="rejecting">
        <p class="rejection-status">审核结果：价格异常 · 不可报价</p>
        <PriceExceptionSelect v-model="exceptionType" :disabled="busy" />
        <template v-if="exceptionType === 'purchase-price'">
          <p class="exception-hint">此分类将作为采购异常排名的统计依据；无采购归属的 SKU 单列待归属。</p>
          <fieldset v-if="skuOptions.length > 1" class="exception-skus"><legend>请选择采购价格异常的 SKU（必选）</legend><label v-for="sku in skuOptions" :key="sku"><input v-model="exceptionSkus" :disabled="busy" type="checkbox" :value="sku">{{ sku }}</label></fieldset>
          <p v-else class="exception-owner">{{ skuOptions.length ? '需处理人员：按该 SKU 自动关联采购报价人，提交时保存归属。' : '该报价没有可关联的采购 SKU，请选择其他异常类型。' }}</p>
        </template>
        <label class="review-note exception-note"><span>异常说明（选填）</span><textarea v-model="note" :disabled="busy" maxlength="500" rows="4" aria-label="异常说明" placeholder="选填，可补充异常位置、核对依据及需要修改的内容" /></label>
        <p class="exception-help">提交后保存异常分类、说明及审核历史，作为后续分类统计的依据。</p>
        <footer class="exception-footer"><button type="button" :disabled="busy" @click="rejecting = false">返回审核结果</button><button type="button" class="confirm-rejection" :disabled="busy || !own || needsReload || !canReject" @click="complete('rejected')">{{ busy ? '正在提交…' : '确认价格异常' }}</button></footer>
      </template>
      <template v-else>
      <label class="review-note">审核意见（选填）<textarea v-model="note" maxlength="500" rows="3" aria-label="审核意见（选填）" placeholder="有建议时填写，保存后业务员可查看；不填写也可完成审核" /></label>
      <div class="review-results">
        <button type="button" class="logistics-exempt" :disabled="busy || !own || needsReload" @click="complete('logistics-exempt')"><b>物流免审-采购已审</b><small>可报价 · 仅当前报价</small></button>
        <button v-if="record.quoteMode!=='fob'" type="button" class="channel-exempt" :disabled="busy || !own || needsReload" @click="complete('channel-exempt')"><b>同渠道免审</b><small>可报价 · 不再审核</small></button>
        <button type="button" class="approved" :disabled="busy || !own || needsReload" @click="complete('approved')"><b>审核通过</b><small>可报价</small></button>
        <button type="button" class="rejected" :disabled="busy || !own || needsReload" @click="openRejection"><b>价格异常</b><small>不可报价</small></button>
      </div>
      </template>
    </dialog>
  </Teleport>
</template>

<style scoped>
.review-result-dialog{max-height:90dvh;overflow:auto}.rejection-status{padding:12px;border-radius:6px;background:#fff0ef;color:#b52b25;font-size:13px}.exception-hint{background:#fff6e7;color:#956015;padding:12px;border-radius:6px;font-size:13px;line-height:1.6}.exception-note{margin-top:20px}.required{color:#b52b25}.exception-owner,.exception-help{font-size:12px;color:#718193;line-height:1.6}.exception-skus{display:grid;gap:8px;border:1px solid #dce3eb;border-radius:6px;margin:12px 0;padding:12px;max-height:180px;overflow:auto}.exception-skus label{display:flex;align-items:center;gap:8px;font-size:13px}.exception-skus legend{font-size:13px}.exception-footer{display:flex;justify-content:flex-end;gap:12px;padding-top:16px;border-top:1px solid #e2e7ee}.exception-footer button{font:inherit;padding:11px 16px;border:1px solid #ccd7e2;background:#fff;color:#284563;border-radius:6px;cursor:pointer}.exception-footer .confirm-rejection{background:#cf3630;border-color:#cf3630;color:#fff}.exception-footer button:disabled{opacity:.5;cursor:not-allowed}
.review-context{display:grid;gap:4px;margin:0 0 16px;color:#526575;font-size:13px;overflow-wrap:anywhere}.review-context small{font-size:11px;color:#748493}
.review-note{display:grid;gap:8px;margin-bottom:18px;font-size:13px}.review-note textarea{box-sizing:border-box;width:100%;padding:10px;border:1px solid #ccd7e2;border-radius:6px;resize:vertical;font:inherit;line-height:1.6}.review-note textarea:focus-visible{outline:2px solid #3782d6;outline-offset:2px}
.review-button{height:40px;padding:0 16px;border:1px solid #078347;border-radius:7px;background:#078347;color:#fff;font-size:12px;font-weight:700;cursor:pointer}.review-button:disabled{opacity:.5;cursor:not-allowed}
.review-result-dialog{box-sizing:border-box;width:min(530px,calc(100vw - 32px));padding:20px;border:1px solid #e0e4e8;border-radius:12px;color:#202532;background:#fff;box-shadow:0 20px 60px #17212b33;font-family:Arial,"Microsoft YaHei",sans-serif}.review-result-dialog::backdrop{background:#17212b66}.review-result-dialog header{display:flex;justify-content:space-between;align-items:center;gap:20px;margin-bottom:18px}.review-result-dialog header button{border:0;background:transparent;color:#65717c;font-size:24px;cursor:pointer}.review-results{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}.review-results button{flex:1;display:grid;gap:6px;padding:18px 12px;border:1px solid;border-radius:8px;cursor:pointer;font:inherit}.review-results b{font-size:15px}.review-results small{font-size:12px}.review-results .logistics-exempt{color:#5e429a;background:#f5f0ff;border-color:#c9b5eb}.review-results .channel-exempt{color:#17659b;background:#edf6ff;border-color:#9bc8e8}.review-results .approved{color:#078347;background:#e7f7ee;border-color:#9ad8b4}.review-results .rejected{color:#b52b25;background:#fff0ef;border-color:#efb0ac}.review-results button:disabled{opacity:.5;cursor:not-allowed}
@media(max-width:480px){.review-results{grid-template-columns:1fr}.review-results button{padding:14px 12px}}
</style>
