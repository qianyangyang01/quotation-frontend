<script setup lang="ts">
import { computed } from 'vue'
import { normalizeQuotationRecord } from '@/data/quotationRecords'
import { customerGradeDisplayLabel } from '@/data/financeChannelPolicies'
const props = defineProps<{ before: string; after: string }>()
function parse(value: string) {
  try { return normalizeQuotationRecord(JSON.parse(value)) } catch { return null }
}
const snapshots = computed(() => [parse(props.before), parse(props.after)])
const fields = computed(() => [
  { label: '客户', values: snapshots.value.map(row => row?.customerName) },
  { label: 'SKU', values: snapshots.value.map(row => row?.primarySku) },
  { label: '物流属性', values: snapshots.value.map(row => row?.logisticsAttribute) },
  { label: '客户等级', values: snapshots.value.map(row => row ? customerGradeDisplayLabel(row.customerGrade) : '') },
  { label: '系统报价', values: snapshots.value.map(row => row ? `$${row.systemQuoteUsd.toFixed(2)}` : '') },
  { label: '国家与渠道（1件）', values: snapshots.value.map(row => row?.quoteOptions?.map(option => `${option.country} · ${option.carrier} · ${option.channel}：${option.quote1Usd == null ? '不可报价' : '$' + option.quote1Usd.toFixed(2)}`).join('\n')) },
])
</script>
<template>
  <details class="quote-revision"><summary>撤回重新编辑 · 查看前后报价</summary>
    <p>保留原报价单号，重新提交后需重新审核。</p>
    <table><thead><tr><th>项目</th><th>修改前</th><th>修改后</th></tr></thead><tbody><tr v-for="field in fields" :key="field.label"><th>{{ field.label }}</th><td v-for="(value, index) in field.values" :key="index">{{ value || '未保存' }}</td></tr></tbody></table>
  </details>
</template>
<style scoped>
.quote-revision{margin-top:12px;font-size:13px}.quote-revision summary{cursor:pointer;font-weight:600}.quote-revision table{width:100%;table-layout:fixed;border-collapse:collapse}.quote-revision th,.quote-revision td{padding:8px;text-align:left;vertical-align:top;border:1px solid #e2e7ee;white-space:pre-wrap;overflow-wrap:anywhere}.quote-revision th:first-child{width:22%}.quote-revision p{color:#63717d}
</style>
