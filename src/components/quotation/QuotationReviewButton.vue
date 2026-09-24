<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { type QuotationRecord, type QuotationReviewState, type ReviewAction } from '@/data/quotationRecords'

const props = defineProps<{ record: QuotationRecord; state: QuotationReviewState; account: string; busy: boolean }>()
const emit = defineEmits<{ action: [value: ReviewAction]; reload: [] }>()
const dialog = ref<HTMLDialogElement | null>(null)
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
  return own.value ? '选择同渠道免审、审核通过或价格异常' : '开始审核并占用当前报价'
})
watch([() => props.record.id, () => props.state._version, () => props.state._reviewVersion, () => props.state.financeReviewStatus, () => props.state.financeReviewClaimedAccount, () => props.busy], () => dialog.value?.close())
function submit() {
  if (props.busy || (active.value && !own.value)) return
  if (needsReload.value) { emit('reload'); return }
  if (own.value) dialog.value?.showModal()
  else emit('action', { action: 'claim' })
}
function complete(financeReviewStatus: 'approved' | 'rejected' | 'channel-exempt') {
  if (props.busy || !own.value || needsReload.value) return
  dialog.value?.close()
  emit('action', { action: 'complete', financeReviewStatus, note: '' })
}
</script>

<template>
  <button type="button" class="review-button" :disabled="busy || (active && !own)" :title="hint" @click="submit">{{ label }}</button>
  <Teleport to="body">
    <dialog ref="dialog" class="review-result-dialog" aria-label="选择审核结果" @click="($event.target === dialog) && dialog?.close()">
      <header><strong>选择审核结果</strong><button type="button" aria-label="关闭审核结果" @click="dialog?.close()">×</button></header>
      <div class="review-results">
        <button type="button" class="channel-exempt" :disabled="busy || !own || needsReload" @click="complete('channel-exempt')"><b>同渠道免审</b><small>可报价 · 不再审核</small></button>
        <button type="button" class="approved" :disabled="busy || !own || needsReload" @click="complete('approved')"><b>审核通过</b><small>可报价</small></button>
        <button type="button" class="rejected" :disabled="busy || !own || needsReload" @click="complete('rejected')"><b>价格异常</b><small>不可报价</small></button>
      </div>
    </dialog>
  </Teleport>
</template>

<style scoped>
.review-button{height:40px;padding:0 16px;border:1px solid #078347;border-radius:7px;background:#078347;color:#fff;font-size:12px;font-weight:700;cursor:pointer}.review-button:disabled{opacity:.5;cursor:not-allowed}
.review-result-dialog{box-sizing:border-box;width:min(530px,calc(100vw - 32px));padding:20px;border:1px solid #e0e4e8;border-radius:12px;color:#202532;background:#fff;box-shadow:0 20px 60px #17212b33;font-family:Arial,"Microsoft YaHei",sans-serif}.review-result-dialog::backdrop{background:#17212b66}.review-result-dialog header{display:flex;justify-content:space-between;align-items:center;gap:20px;margin-bottom:18px}.review-result-dialog header button{border:0;background:transparent;color:#65717c;font-size:24px;cursor:pointer}.review-results{display:flex;gap:12px}.review-results button{flex:1;display:grid;gap:6px;padding:18px 12px;border:1px solid;border-radius:8px;cursor:pointer;font:inherit}.review-results b{font-size:15px}.review-results small{font-size:12px}.review-results .channel-exempt{color:#17659b;background:#edf6ff;border-color:#9bc8e8}.review-results .approved{color:#078347;background:#e7f7ee;border-color:#9ad8b4}.review-results .rejected{color:#b52b25;background:#fff0ef;border-color:#efb0ac}.review-results button:disabled{opacity:.5;cursor:not-allowed}
@media(max-width:480px){.review-results{flex-direction:column}.review-results button{padding:14px 12px}}
</style>
