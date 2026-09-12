<script setup lang="ts">
import { computed } from 'vue'
import { customerPriceRevision } from '@/data/customerPriceRevision'
import type { QuotationRecord } from '@/data/quotationRecords'
const props = defineProps<{ record: QuotationRecord; before: string; after: string }>()
const revision = computed(() => customerPriceRevision(props.record, props.before, props.after))
</script>

<template>
  <div class="customer-price-revision">
    <p v-if="revision.note" class="revision-note">{{ revision.note }}</p>
    <section v-for="group in revision.groups" :key="group.optionId">
      <h4>{{ group.route }}</h4>
      <table aria-label="客户报价修改明细">
        <thead><tr><th>数量</th><th>修改前客户价</th><th></th><th>修改后客户价</th></tr></thead>
        <tbody><tr v-for="change in group.changes" :key="change.quantity"><td>{{ change.label }}</td><td>{{ change.before }}</td><td aria-hidden="true">→</td><td class="new-price">{{ change.after }}</td></tr></tbody>
      </table>
    </section>
  </div>
</template>

<style scoped>
.customer-price-revision{margin-top:10px;font-size:12px;color:#24323e}.customer-price-revision section{margin-top:10px}.customer-price-revision h4{margin:0;padding:10px 12px;background:#f6f8fa;border:1px solid #e4e9ed;border-bottom:0;font-size:12px;font-weight:600;overflow-wrap:anywhere}.customer-price-revision table{width:100%;border-collapse:collapse;table-layout:fixed;font-size:12px}.customer-price-revision th,.customer-price-revision td{padding:10px 6px;border:1px solid #e4e9ed;text-align:center;white-space:normal;overflow-wrap:anywhere}.customer-price-revision th{position:static;background:#fff6ed;font-size:11px;font-weight:500;color:#6e7781}.customer-price-revision th:nth-child(3){width:24px}.customer-price-revision .new-price{color:#c6630b;background:#fffaf3;font-weight:700}.customer-price-revision .revision-note{line-height:1.7;color:#71808c}
</style>
