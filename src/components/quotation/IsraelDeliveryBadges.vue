<script setup lang="ts">
import type { IsraelDeliveryMode } from '@/data/logisticsAttributes'
defineProps<{ modes?: IsraelDeliveryMode[] }>()
</script>

<template>
  <span v-for="mode in modes" :key="mode" class="delivery-badge" :class="mode === '自提' ? 'pickup' : 'door'" :title="`以色列${mode}`">{{ mode }}</span>
</template>

<style scoped>
.delivery-badge{display:inline-flex;align-items:center;flex-shrink:0;padding:2px 6px;border:1px solid;border-radius:4px;font-size:11px;font-weight:700;line-height:1.4;white-space:nowrap}
.pickup{color:#6d28a8;background:#f5efff;border-color:#ccb2ec}
.door{color:#1769a3;background:#edf7ff;border-color:#9bc9eb}
</style>
