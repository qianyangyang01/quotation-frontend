<script setup lang="ts">
import { weightSourceLabel, type QuotationWeightSnapshot, type QuotationWeightItem } from '@/data/quotationWeightSnapshot'
import { decimal } from '@/services/quotationDecimal'
defineProps<{ snapshot?: QuotationWeightSnapshot; bundle?: boolean }>()
const grams = (kg: number) => decimal(kg).times(1000).toString()
function difference(item: QuotationWeightItem) {
  if (item.purchaseWeightKg === undefined) return ''
  const delta = decimal(item.baseWeightKg).minus(item.purchaseWeightKg).times(1000)
  return delta.isZero() ? '与采购原重相同' : `较采购原重 ${delta.isPositive() ? '+' : ''}${delta.toString()}g`
}
</script>
<template>
  <section class="weight-trace"><h3>包材与重量快照</h3>
    <template v-if="snapshot">
      <div class="weight-sources" aria-label="商品重量来源">
        <div v-for="(item,index) in snapshot.items" :key="index" class="weight-source" :class="item.weightSource || 'unknown'">
          <strong>{{ weightSourceLabel(item.weightSource) }}</strong>
          <span v-if="bundle || snapshot.items.length > 1">{{ item.sku }} × {{ item.quantityPerSet }}</span>
          <span v-if="item.weightSource === 'manual'">采购原重（单件）：{{ item.purchaseWeightKg === undefined ? '未记录' : `${grams(item.purchaseWeightKg)}g` }} → 报价采用（单件）：{{ grams(item.baseWeightKg) }}g<template v-if="item.purchaseWeightKg !== undefined">（{{ difference(item) }}）</template></span>
          <span v-else-if="item.weightSource === 'purchase'">报价采用采购表重量（单件）：{{ grams(item.baseWeightKg) }}g</span>
          <span v-else>报价采用（单件）：{{ grams(item.baseWeightKg) }}g；历史记录未保存来源，无法判断是否由业务修改。</span>
        </div>
      </div>
      <p>普通包材：每件商品每 50g 加 1g，不足 50g 向上取整；特殊包装 {{ snapshot.specialPackagingGrams }}g／票，只加一次。</p>
      <div class="scroll"><table><thead><tr><th>数量</th><th>商品重量（g）</th><th>普通包材（g）</th><th>特殊包装（g）</th><th>整票含包材（g）</th></tr></thead><tbody><tr v-for="row in snapshot.quantities" :key="row.quantity"><td>{{ row.quantity }}{{ bundle ? '套' : '件' }}</td><td>{{ grams(row.baseWeightKg) }}</td><td>{{ grams(row.standardPackagingWeightKg) }}</td><td>{{ grams(row.specialPackagingWeightKg) }}</td><td>{{ grams(row.weightKg) }}</td></tr></tbody></table></div>
      <details><summary>商品重量依据</summary><p v-for="(item,index) in snapshot.items" :key="index">{{ item.sku }} × {{ item.quantityPerSet }}：单件基础 {{ grams(item.baseWeightKg) }}g，普通包材 {{ grams(item.standardPackagingWeightKg) }}g</p></details>
      <small>重量及来源均按报价保存时的快照展示；物流起重等规则可能使运费计费重量更高。</small>
    </template>
    <p v-else>旧记录未保存完整包材规则及重量来源，无法判断是否由业务修改；保留原重量和报价，不按当前规则回算。</p>
  </section>
</template>
<style scoped>
.weight-trace{margin:16px 0;padding:14px;border:1px solid #e3e8ed;border-radius:8px;background:#fafbfc;font-size:11px;color:#52616d}.weight-trace h3{margin:0 0 8px;color:#26323b;font-size:13px}.weight-trace p{line-height:1.6}.scroll{overflow-x:auto}.weight-trace table{width:100%;border-collapse:collapse;white-space:nowrap}.weight-trace th,.weight-trace td{padding:7px;border:1px solid #e3e8ed;text-align:center}.weight-trace small{display:block;margin-top:10px;color:#73818c}.weight-trace details{margin-top:10px}
</style>
<style scoped>
.weight-sources{display:grid;gap:8px;margin-bottom:12px}.weight-source{display:flex;align-items:center;flex-wrap:wrap;gap:6px 10px;padding:9px 11px;border:1px solid #dce5ec;border-radius:6px;background:#f2f6fa;line-height:1.6;overflow-wrap:anywhere}.weight-source strong{flex-shrink:0;padding:2px 7px;border-radius:4px;background:#e1eaf3;color:#315675}.weight-source.manual{border-color:#f0d29c;background:#fff8e9}.weight-source.manual strong{background:#ffe8bd;color:#915300}.weight-source.unknown{background:#f3f4f5;border-color:#e0e3e6}.weight-source.unknown strong{background:#e5e8eb;color:#596771}
</style>
