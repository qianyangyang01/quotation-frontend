<script setup lang="ts">
import ChannelCoverageBadge from '@/components/logistics/ChannelCoverageBadge.vue'
import { computed } from 'vue'
import type { QuotationMatrixRow } from './types'

const props = defineProps<{ row: Pick<QuotationMatrixRow, 'country' | 'carrier' | 'transport' | 'rule' | 'quoteRegion'> }>()
const region = computed(() => {
  const value = props.row.quoteRegion?.trim() || ''
  if (!value || value === '全国统一') return ''
  const prefix = `${props.row.carrier}｜${props.row.rule}｜`
  return value.startsWith(prefix) ? value.slice(prefix.length) : value
})
const regionLabel = computed(() => region.value.startsWith(props.row.country)
  ? region.value : `${props.row.country} · ${region.value}`)
</script>

<template>
  <span class="quote-channel-name">
    <span class="channel-title-line"><strong class="channel-title">{{ row.carrier }}｜{{ row.transport }}</strong><ChannelCoverageBadge :name="row.transport" /></span>
    <span v-if="region" class="channel-region">{{ regionLabel }}</span>
  </span>
</template>

<style scoped>
.quote-channel-name{display:flex;flex-direction:column;align-items:flex-start;gap:5px;min-width:0;max-width:100%;text-align:left}
.channel-title-line{display:flex;align-items:center;flex-wrap:wrap;gap:2px;max-width:100%}
.channel-title{font-size:13px;font-weight:800;line-height:1.5;white-space:normal;overflow-wrap:anywhere}
.channel-region{max-width:100%;box-sizing:border-box;padding:3px 8px;border:1px solid #a7c8e8;border-radius:5px;background:#eaf3fc;color:#174f80;font-size:12px;font-weight:800;line-height:1.5;white-space:normal;overflow-wrap:anywhere}
</style>
