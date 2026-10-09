<script setup lang="ts">
import type { QuotationRecord } from '@/data/quotationRecords'
import FobQuoteSheet from './FobQuoteSheet.vue'
defineProps<{record:QuotationRecord}>()
</script>
<template>
  <section v-if="record.fob" class="fob-record" aria-label="FOB历史报价快照">
    <div class="fob-meta"><b>FOB（批发）报价 · {{ record.primarySku }}</b><span>客户：{{ record.customerName }}</span><span>报价人：{{ record.salespersonName }}</span><span>采购来源：{{ record.fob.product.source==='fob'?'FOB数据':'新采购资料' }}</span><span>报价数量：{{ record.fob.quantity }}</span><span>汇率：{{ record.fob.rate }} CNY/USD · 票点10% · 报关×1.14 · 不报关×1.1628</span></div>
    <p>以下为保存时的报价快照；采购资料和汇率更新不会改变此报价。</p>
    <div class="cost-table"><table><thead><tr><th>数量</th><th>采购价 ¥</th><th>含票价 ¥</th><th>国内运费 ¥</th><th>成本价 ¥</th><th>报关 $</th><th>不报关 $</th></tr></thead><tbody><tr v-for="tier in record.fob.tiers" :key="tier.minQty"><td>{{ tier.minQty }}{{ tier.maxQty==null?'+':'–'+tier.maxQty }}{{ tier.unit }}</td><td>{{ tier.purchaseCny }}</td><td>{{ tier.taxIncludedCny }}</td><td>{{ tier.freightCny }}</td><td>{{ tier.costCny }}</td><td>{{ tier.declaredUsd }}</td><td>{{ tier.undeclaredUsd }}</td></tr></tbody></table></div>
    <p v-if="record.fob.current">当前数量最终报价：报关 ${{ record.fob.current.declaredUsd }} / 不报关 ${{ record.fob.current.undeclaredUsd }}；{{ record.fob.product.parsed.freight.basis }}</p>
    <FobQuoteSheet :product="record.fob.product" :rate="record.fob.rate" :quantity="String(record.fob.quantity)" :policy="record.fob.policy" :saved-sheet="record.fob.sheet" />
  </section>
  <p v-else role="alert">此FOB报价缺少完整快照，无法还原报价单，请联系管理员核查。</p>
</template>
<style scoped>
.fob-record{padding:14px 24px}.fob-meta{display:flex;flex-wrap:wrap;gap:12px;font-size:13px}.fob-meta b{width:100%}.fob-record p{font-size:12px;color:#647588}.cost-table{overflow:auto;margin-top:12px}.cost-table table{border-collapse:collapse;width:100%;font-size:12px;text-align:center}.cost-table td,.cost-table th{border:1px solid #e0e5eb;padding:8px;white-space:nowrap}.cost-table th{background:#fff3e7}
</style>
