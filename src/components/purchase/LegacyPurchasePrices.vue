<script setup lang="ts">
import { computed } from 'vue'
import type { PurchaseProductRecord } from '@/data/purchaseStore'
import { legacyPurchaseDisplay, formatLegacyPurchaseUnitPrice } from '@/services/legacyPurchaseDisplay'
const props = defineProps<{ record: PurchaseProductRecord }>()
const prices = computed(() => (props.record.priceTiers.length ? props.record.priceTiers.map((_, i) => ['10', '100', '100+', 'tier4', 'tier5'][i]!) : ['10'])
  .map(tier => legacyPurchaseDisplay(props.record, tier)).filter(price => price != null))
</script>
<template>
  <div v-if="prices.length" class="legacy-purchase-prices">
    <div v-for="(price, index) in prices" :key="index">
      <b>{{ price.label }} ¥{{ formatLegacyPurchaseUnitPrice(price.unitPrice) }}/件</b>
      <small>{{ price.range }}</small>
      <small>{{ price.detail }}</small>
    </div>
  </div>
  <span v-else>暂无采购价格</span>
</template>
<style scoped>
.legacy-purchase-prices{display:grid;gap:8px;min-width:180px}.legacy-purchase-prices>div{display:grid;gap:4px}.legacy-purchase-prices b{color:#bb6800;font-size:12px}.legacy-purchase-prices small{color:#697580;font-size:10px;line-height:1.5}
</style>
