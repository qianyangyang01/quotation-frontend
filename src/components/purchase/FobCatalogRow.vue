<script setup lang="ts">
import type { FobCatalogRecord } from '@/services/purchaseCatalog'
import PurchaseCategoryBadge from './PurchaseCategoryBadge.vue'
defineProps<{ record: FobCatalogRecord; canMaintain: boolean }>()
defineEmits<{ detail: []; maintain: [] }>()
</script>
<template>
  <tr class="fob-catalog-row" :data-sku="record.sku" data-source="fob">
    <td><div class="product"><PurchaseCategoryBadge :category="record.category || ''" /><span><b>{{ record.category || '暂无数据' }}</b><small>{{ record.sku }}</small><em>FOB数据</em><small>报价人：{{ record.quotationOwner || '暂无数据' }}</small></span></div></td>
    <td><div v-if="record.parsed?.priceTiers.length" class="tiers"><span v-for="(tier,index) in record.parsed.priceTiers" :key="tier.minQty"><small>第{{ index+1 }}档 · {{ tier.maxQty == null ? `${tier.minQty}${tier.unit}起` : `${tier.minQty}–${tier.maxQty}${tier.unit}` }}</small><b>¥{{ tier.unitPriceCny.toFixed(2) }}/{{ tier.unit }}</b></span></div><span v-else>阶梯待核对</span><small>不含票采购价</small></td>
    <td><b>{{ record.weightRaw || '暂无克重' }}<template v-if="record.weightRaw && /^[\d.]+$/.test(record.weightRaw.trim())"> g</template></b><small>起订 {{ record.parsed?.minOrderQty ?? record.moqRaw ?? '待核对' }}</small><small v-if="record.parsed">下单倍数 {{ record.parsed.orderMultiple }}</small></td>
    <td><template v-if="record.parsed"><b>¥{{ record.parsed.freight.unitFreightCny.toFixed(2) }}/单位</b><small>{{ record.parsed.freight.basis }}</small><small v-if="record.parsed.freight.estimated">预估/预拍运费</small></template><span v-else>运费待核对</span></td>
    <td class="description"><b>{{ record.size || '暂无数据' }}</b><small>{{ record.color || '暂无数据' }}</small></td>
    <td><em>FOB专用资料</em><small>{{ record.parsed ? `已识别 ${record.parsed.priceTiers.length} 档` : '请核对价格与运费原文' }}</small></td>
    <td class="actions"><button @click="$emit('detail')">查看详情</button><button v-if="canMaintain" @click="$emit('maintain')">粘贴更新 / 修改记录</button></td>
  </tr>
</template>
<style scoped>
td{padding:20px 14px;vertical-align:middle;border-top:1px solid #e2e8f0;color:#142b43;font-size:12px}small{display:block;font-size:11px;line-height:1.6;margin-top:3px}em{display:inline-block;font-style:normal;border-radius:12px;padding:3px 7px;font-size:10px;color:#047857;background:#e4f5ee;margin-top:5px}.product{display:flex;align-items:center;gap:12px}.tiers{display:grid;gap:5px}.tiers>span{display:flex;justify-content:space-between;gap:8px;background:#eff9f4;padding:5px 7px;border-radius:7px}.tiers small{margin:0}.tiers b{white-space:nowrap}.description{max-width:220px;overflow-wrap:anywhere}.actions button{border:0;background:transparent;color:#93610d;font-weight:700;padding:5px;cursor:pointer}
</style>
