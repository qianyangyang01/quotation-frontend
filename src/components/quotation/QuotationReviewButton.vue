<script setup lang="ts">
import { computed } from 'vue'
import { type QuotationRecord, type QuotationReviewState, type ReviewAction } from '@/data/quotationRecords'

const props = defineProps<{ record: QuotationRecord; state: QuotationReviewState; account: string; busy: boolean }>()
const emit = defineEmits<{ action: [value: ReviewAction]; reload: [] }>()
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
  return own.value ? '确认审核完成，标记为可报价' : '开始审核并占用当前报价'
})
function submit() {
  if (props.busy || (active.value && !own.value)) return
  if (needsReload.value) { emit('reload'); return }
  emit('action', own.value ? { action: 'complete', financeReviewStatus: 'approved', note: '' } : { action: 'claim' })
}
</script>

<template>
  <button type="button" class="review-button" :disabled="busy || (active && !own)" :title="hint" @click="submit">{{ label }}</button>
</template>

<style scoped>
.review-button{height:40px;padding:0 16px;border:1px solid #078347;border-radius:7px;background:#078347;color:#fff;font-size:12px;font-weight:700;cursor:pointer}.review-button:disabled{opacity:.5;cursor:not-allowed}
</style>
