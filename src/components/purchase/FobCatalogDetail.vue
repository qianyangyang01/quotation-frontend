<script setup lang="ts">
import type { FobCatalogRecord } from '@/services/purchaseCatalog'
import { FOB_COLUMNS } from '@/data/fobClipboard'
defineProps<{ record: FobCatalogRecord }>()
defineEmits<{ close: [] }>()
</script>
<template>
  <Teleport to="body"><div class="fob-detail-mask" @click.self="$emit('close')" @keydown.esc.stop="$emit('close')"><section role="dialog" aria-modal="true" aria-labelledby="fob-detail-title">
    <header><h2 id="fob-detail-title">{{ record.sku }} · FOB资料详情</h2><button aria-label="关闭FOB详情" @click="$emit('close')">关闭</button></header>
    <p>FOB数据 · 不含票采购价 · 与普通采购资料独立保存</p>
    <table v-if="record.parsed"><thead><tr><th>采购数量</th><th>单价</th><th>均摊运费/单位</th></tr></thead><tbody><tr v-for="tier in record.parsed.priceTiers" :key="tier.minQty"><td>{{ tier.minQty }}{{ tier.maxQty == null ? '起' : `–${tier.maxQty}` }}{{ tier.unit }}</td><td>¥{{ tier.unitPriceCny.toFixed(2) }}</td><td>¥{{ record.parsed.freight.unitFreightCny.toFixed(2) }}</td></tr></tbody></table>
    <p v-if="record.parsed">运费采用：{{ record.parsed.freight.basis }}<template v-if="record.parsed.freight.estimated">（预估/预拍）</template></p>
    <dl><template v-for="[label,field] in FOB_COLUMNS" :key="field"><dt>{{ label }}</dt><dd>{{ record[field] || '暂无数据' }}</dd></template></dl>
  </section></div></Teleport>
</template>
<style scoped>
.fob-detail-mask{position:fixed;inset:0;z-index:1100;background:#142b4380;display:grid;place-items:center;padding:24px}section{width:min(850px,100%);max-height:90vh;overflow:auto;background:white;padding:24px;border-radius:16px;color:#142b43}header{display:flex;align-items:center;justify-content:space-between}h2{font-size:20px}button{padding:8px 12px;cursor:pointer;background:white;border:1px solid #ccd8df;border-radius:6px}p{color:#557267}table{width:100%;border-collapse:collapse}td,th{padding:8px;text-align:left;border:1px solid #dce6e3}dl{display:grid;grid-template-columns:110px 1fr;gap:12px}dt{font-weight:600}dd{margin:0;white-space:pre-wrap;overflow-wrap:anywhere}
</style>
