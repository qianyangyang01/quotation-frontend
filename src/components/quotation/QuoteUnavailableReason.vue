<script setup lang="ts">
defineProps<{ price: number | null | undefined; message?: string }>()
</script>

<template>
  <small v-if="price == null || !Number.isFinite(price)" class="quote-unavailable-reason">{{ message || '当前条件暂无可用运价' }}</small>
</template>

<style scoped>
.quote-unavailable-reason{display:block!important;white-space:normal!important;overflow-wrap:anywhere;line-height:1.5;font-size:11px!important;color:#9a5b16!important;margin-top:4px}
</style>
