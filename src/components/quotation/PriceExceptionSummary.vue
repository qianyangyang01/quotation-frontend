<script setup lang="ts">
import { priceExceptionLabel, type PriceException } from '@/data/priceExceptions'
defineProps<{ value?: PriceException }>()
</script>
<template>
  <div class="exception-summary"><b>{{ priceExceptionLabel(value?.category) }}</b><span v-for="target in value?.targets" :key="target.source + ':' + target.sku">{{ target.sku }} · 采购报价人：{{ target.procurementOwner || '待归属' }}</span></div>
</template>
<style scoped>
.exception-summary{display:grid;gap:4px;margin:7px 0;color:#965d13;font-size:12px;line-height:1.6}.exception-summary b{font-weight:600}.exception-summary span{color:#657487}
</style>
