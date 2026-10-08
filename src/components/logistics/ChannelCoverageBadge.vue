<script setup lang="ts">
// Display classification only: never changes a channel's identity or billing rules.
defineProps<{ name?: string | null }>()
</script>

<template>
  <mark
    v-if="name?.trim() && !/商派|精品/.test(name)"
    class="channel-coverage-badge"
    title="按渠道名称默认标记：不含“商派”或“精品”的渠道显示全邮编"
  >全邮编</mark>
</template>

<style scoped>
.channel-coverage-badge{display:inline-block;vertical-align:middle;flex-shrink:0;margin:0 0 0 4px;padding:1px 6px;border:1px solid #a9d9c8;border-radius:4px;background:#edf9f3;color:#176b4b;font-size:11px;font-weight:600;font-style:normal;line-height:1.6;white-space:nowrap}
</style>
