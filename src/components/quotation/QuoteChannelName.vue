<script setup lang="ts">
import ChannelCoverageBadge from '@/components/logistics/ChannelCoverageBadge.vue'
import IsraelDeliveryBadges from './IsraelDeliveryBadges.vue'
import { computed } from 'vue'
import type { QuotationMatrixRow } from './types'
import { priceRegionLabel } from './priceRegionLabel'

const props = defineProps<{ row: Pick<QuotationMatrixRow, 'country' | 'carrier' | 'transport' | 'rule' | 'quoteRegion' | 'priceRegion' | 'deliveryModes'> }>()
const regionLabel = computed(() => priceRegionLabel(props.row))
const acceptsSeeds = computed(() => props.row.carrier.trim() === '快驴' && ['美国商派-特敏感', '美国专线-特敏感'].includes(props.row.transport.trim()))
</script>

<template>
  <span class="quote-channel-name">
    <span class="channel-title-line"><strong class="channel-title">{{ row.carrier }}｜{{ row.transport }}</strong><span v-if="acceptsSeeds" class="cargo-capability">可发种子</span><ChannelCoverageBadge :name="row.transport" /><IsraelDeliveryBadges :modes="row.deliveryModes" /></span>
    <span v-if="regionLabel" class="channel-region">{{ regionLabel }}</span>
  </span>
</template>

<style scoped>
.quote-channel-name{display:flex;flex-direction:column;align-items:flex-start;gap:5px;min-width:0;max-width:100%;text-align:left}
.channel-title-line{display:flex;align-items:center;flex-wrap:wrap;gap:2px;max-width:100%}
.channel-title{font-size:13px;font-weight:800;line-height:1.5;white-space:normal;overflow-wrap:anywhere}
.cargo-capability{margin-left:4px;padding:2px 6px;border:1px solid #9dcbb2;border-radius:4px;background:#f0faf4;color:#28734c;font-size:11px;font-weight:700;line-height:1.4;white-space:nowrap}
.channel-region{max-width:100%;box-sizing:border-box;padding:3px 8px;border:1px solid #a7c8e8;border-radius:5px;background:#eaf3fc;color:#174f80;font-size:12px;font-weight:800;line-height:1.5;white-space:normal;overflow-wrap:anywhere}
</style>
