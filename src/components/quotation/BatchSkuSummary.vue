<script setup lang="ts">
import { computed, ref } from 'vue'
import { copyQuotationText } from '@/services/customerQuoteSheetClipboard'
const props = defineProps<{ skus: string[] }>()
const skus = computed(() => [...new Set(props.skus)])
const expanded = ref(false), message = ref('')
async function copy() {
  try { await copyQuotationText(skus.value.join('\n')); message.value = `已复制${skus.value.length}个SKU` }
  catch { expanded.value = true; message.value = '复制失败，请在展开清单中选择复制' }
}
</script>
<template>
  <div class="batch-sku-summary"><b>FOB批量报价 · {{ skus.length }}个SKU</b><p>{{ skus.slice(0, 3).join('、') }}{{ skus.length > 3 ? '…' : '' }}</p><div class="sku-actions"><button :aria-expanded="expanded" @click="expanded = !expanded">{{ expanded ? '收起清单' : `展开全部${skus.length}个` }}</button><button @click="copy">复制SKU</button><small role="status">{{ message }}</small></div><div v-if="expanded" class="sku-scroll" role="region" aria-label="完整SKU清单" tabindex="0"><span v-for="sku in skus" :key="sku">{{ sku }}</span></div></div>
</template>
<style scoped>
.batch-sku-summary{min-width:0}.batch-sku-summary>b{font-size:16px;color:#172a40}.batch-sku-summary p{margin:5px 0;color:#526575;font-size:13px;overflow-wrap:anywhere}.sku-actions{display:flex;align-items:center;flex-wrap:wrap;gap:8px}.sku-actions button{border:0;padding:4px 0;background:none;color:#236bb0;font-size:13px;cursor:pointer}.sku-actions small{color:#697d8d;font-size:12px}.sku-scroll{max-height:140px;overflow:auto;display:grid;grid-template-columns:repeat(auto-fit,minmax(115px,1fr));gap:6px;margin:8px 0;padding:8px;background:#f4f7fa;border:1px solid #dbe4ed;border-radius:5px;font-size:12px}.sku-scroll span{overflow-wrap:anywhere}button:focus-visible{outline:2px solid #387ac2}
</style>
