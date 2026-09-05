<script setup lang="ts">
import { computed } from 'vue'
import type { QuotationMatrixRow } from './types'

const props = defineProps<{ row: QuotationMatrixRow }>()
const missing = computed(() => !props.row.taxIncluded && !props.row.taxConfigured)
const badgeText = computed(() => {
  if (props.row.taxFeeMode === 'no-tax') return '无关税'
  if (props.row.taxIncluded) return '免税'
  if (missing.value) return props.row.taxLabel || '物流商税务属性待设置'
  return `关税 $${Number(props.row.countryFixedTaxUsd || 0).toFixed(2)}/单`
})
</script>

<template>
  <mark class="tax-channel-badge" :class="{ exempt:row.taxIncluded, missing }">{{ badgeText }}</mark>
</template>

<style scoped>
.tax-channel-badge{display:inline-flex;align-items:center;box-sizing:border-box;padding:3px 7px;border:1px solid #f2a257;border-radius:4px;background:#fffaf4;color:#e56a0a;font-size:10px;font-weight:700;line-height:1.2;white-space:nowrap}.tax-channel-badge.exempt{border-color:#efb274;background:#fffaf4;color:#e56a0a}.tax-channel-badge.missing{border-color:#df6b5d;background:#fff4f1;color:#b52d21}
</style>
