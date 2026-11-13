<script setup lang="ts">
// Display classification only: never changes a channel's identity or billing rules.
const isTongyouEpacket = (name?: string | null) => /^TY[-－]E邮宝$/i.test(name?.trim() || '')
const isRongdingCosmeticsCommercial = (name?: string | null) =>
  /^(?:容鼎[｜|]?)?美国(?:标准DDP专线-特惠化妆品|化妆品专线)A\(SP\)$/i.test(name?.normalize('NFKC').replace(/\s/g, '') || '')
defineProps<{ name?: string | null }>()
</script>

<template>
  <mark
    v-if="isTongyouEpacket(name)"
    class="channel-coverage-badge"
    title="仅支持原表指定的西班牙邮编范围，报价时请选择对应邮编分区"
  >指定邮编</mark>
  <mark
    v-else-if="isRongdingCosmeticsCommercial(name)"
    class="channel-coverage-badge"
    :title="name?.includes('特惠') ? '仅支持特惠商派可达邮编，以到货预报为准' : '仅支持商派可达邮编，以到货预报为准'"
  >商派</mark>
  <mark
    v-else-if="name?.trim() && !/商派|精品/.test(name)"
    class="channel-coverage-badge"
    title="按渠道名称默认标记：不含“商派”或“精品”的渠道显示全邮编"
  >全邮编</mark>
</template>

<style scoped>
.channel-coverage-badge{display:inline-block;vertical-align:middle;flex-shrink:0;margin:0 0 0 4px;padding:1px 6px;border:1px solid #a9d9c8;border-radius:4px;background:#edf9f3;color:#176b4b;font-size:11px;font-weight:600;font-style:normal;line-height:1.6;white-space:nowrap}
</style>
