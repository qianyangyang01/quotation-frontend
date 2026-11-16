<script setup lang="ts">
defineProps<{ active: 'single' | 'batch' }>()
</script>

<template>
  <nav class="shipping-entry-switch" aria-label="代发货录入方式">
    <span v-if="active === 'single'" class="entry-option active" aria-current="page">单条录入</span>
    <RouterLink v-else class="entry-option" :to="{ path: '/quotation', query: { mode: 'shipping-only' } }">单条录入</RouterLink>
    <span v-if="active === 'batch'" class="entry-option active" aria-current="page">批量录入</span>
    <RouterLink v-else class="entry-option" to="/quotation/batch-shipping">批量录入</RouterLink>
  </nav>
</template>

<style scoped>
.shipping-entry-switch{display:inline-flex;width:fit-content;justify-self:start;align-items:center;gap:4px;padding:4px;border:1px solid #dfe5ec;border-radius:9px;background:#eef1f5;flex-shrink:0}
.shipping-entry-switch .entry-option{display:flex;align-items:center;justify-content:center;min-width:96px;min-height:34px;padding:0 14px;box-sizing:border-box;border-radius:6px;color:#63738a;font-size:13px;font-weight:500;text-decoration:none;white-space:nowrap;transition:background .15s,color .15s}
.shipping-entry-switch a:hover{background:#fff;color:#a65b00}
.shipping-entry-switch .active{background:#fff;color:#bf6d00;font-weight:600;box-shadow:0 1px 4px #182c4314}
.shipping-entry-switch a:focus-visible{outline:2px solid #e88a00;outline-offset:2px}
</style>
