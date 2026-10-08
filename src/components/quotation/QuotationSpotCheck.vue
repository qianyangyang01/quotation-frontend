<script setup lang="ts">
import { computed } from 'vue'
import type { QuotationReviewState } from '@/data/quotationRecords'
const props = defineProps<{ state: QuotationReviewState; canMark: boolean; busy?: boolean; detail?: boolean }>()
defineEmits<{ mark: [] }>()
const description = computed(() => [
  props.state.spotCheckedBy ? `抽检人：${props.state.spotCheckedBy}` : '',
  props.state.spotCheckedAt ? `抽检时间：${new Date(props.state.spotCheckedAt).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' })}` : '',
].filter(Boolean).join(' · '))
</script>

<template>
  <div v-if="state.spotChecked || canMark" class="spot-check" :class="{ detail }" @click.stop>
    <button v-if="canMark" class="spot-check-button" :class="{ 'spot-check-badge': state.spotChecked }" type="button" :disabled="busy" :title="state.spotChecked ? `${description} · 再次点击取消抽检标记` : '标记已抽检'" :aria-label="state.spotChecked ? '已抽检，点击取消抽检标记' : '标记已抽检'" @click="$emit('mark')">{{ busy ? (state.spotChecked ? '正在取消…' : '正在标记…') : (state.spotChecked ? '✓ 已抽检' : '标记已抽检') }}</button>
    <span v-else-if="state.spotChecked" class="spot-check-badge" :title="description" :aria-label="`已抽检${description ? '，' + description : ''}`">✓ 已抽检</span>
    <small v-if="detail && state.spotChecked">{{ description }}</small>
  </div>
</template>

<style scoped>
.spot-check{display:flex;align-items:center;flex-wrap:wrap;gap:8px;align-self:flex-start}
.spot-check .spot-check-badge,.spot-check .spot-check-button{display:inline-flex;align-items:center;box-sizing:border-box;width:auto;min-height:28px;height:auto;padding:4px 9px;border:1px solid #d7c8ef;border-radius:5px;background:#f3edfc;color:#7952a3;font-size:12px;font-weight:600;line-height:1.4;white-space:nowrap;box-shadow:none}
.spot-check .spot-check-button{cursor:pointer}.spot-check .spot-check-button:hover{background:#eae0f8;border-color:#bba1db}.spot-check .spot-check-button:focus-visible{outline:2px solid #9870c5;outline-offset:2px}.spot-check .spot-check-button:disabled{opacity:.6;cursor:wait}
.spot-check.detail{margin:10px 0 14px}.spot-check small{color:#7a6a8c;font-size:12px;line-height:1.5}
</style>
