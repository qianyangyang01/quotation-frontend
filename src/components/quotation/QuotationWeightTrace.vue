<script setup lang="ts">
import type { QuotationWeightSnapshot } from '@/data/quotationWeightSnapshot'
import { decimal } from '@/services/quotationDecimal'
defineProps<{ snapshot?: QuotationWeightSnapshot; bundle?: boolean }>()
const grams = (kg: number) => decimal(kg).times(1000).toString()
</script>
<template>
  <section class="weight-trace"><h3>包材与重量快照</h3>
    <template v-if="snapshot">
      <p>普通包材：每件商品每 50g 加 1g，不足 50g 向上取整；特殊包装 {{ snapshot.specialPackagingGrams }}g／票，只加一次。</p>
      <div class="scroll"><table><thead><tr><th>数量</th><th>商品重量（g）</th><th>普通包材（g）</th><th>特殊包装（g）</th><th>整票含包材（g）</th></tr></thead><tbody><tr v-for="row in snapshot.quantities" :key="row.quantity"><td>{{ row.quantity }}{{ bundle ? '套' : '件' }}</td><td>{{ grams(row.baseWeightKg) }}</td><td>{{ grams(row.standardPackagingWeightKg) }}</td><td>{{ grams(row.specialPackagingWeightKg) }}</td><td>{{ grams(row.weightKg) }}</td></tr></tbody></table></div>
      <details><summary>商品重量依据</summary><p v-for="(item,index) in snapshot.items" :key="index">{{ item.sku }} × {{ item.quantityPerSet }}：单件基础 {{ grams(item.baseWeightKg) }}g，普通包材 {{ grams(item.standardPackagingWeightKg) }}g</p></details>
      <small>按保存时的重量展示；物流起重等规则可能使运费计费重量更高。</small>
    </template>
    <p v-else>旧记录未保存完整包材规则及特殊包装快照，保留原重量和报价，不按当前规则回算。</p>
  </section>
</template>
<style scoped>
.weight-trace{margin:16px 0;padding:14px;border:1px solid #e3e8ed;border-radius:8px;background:#fafbfc;font-size:11px;color:#52616d}.weight-trace h3{margin:0 0 8px;color:#26323b;font-size:13px}.weight-trace p{line-height:1.6}.scroll{overflow-x:auto}.weight-trace table{width:100%;border-collapse:collapse;white-space:nowrap}.weight-trace th,.weight-trace td{padding:7px;border:1px solid #e3e8ed;text-align:center}.weight-trace small{display:block;margin-top:10px;color:#73818c}.weight-trace details{margin-top:10px}
</style>
