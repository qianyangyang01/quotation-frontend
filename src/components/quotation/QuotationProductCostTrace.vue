<script setup lang="ts">
import { computed } from 'vue'
import type { QuotationRecord } from '@/data/quotationRecords'
import { quotationProductCostSnapshot, snapshotMoney } from '@/data/quotationProductCostSnapshot'
const props = defineProps<{ record: QuotationRecord }>()
const snapshot = computed(() => quotationProductCostSnapshot(props.record))
</script>

<template>
  <section class="product-cost-trace">
    <h3>产品成本快照</h3>
    <p>按保存时的采购单价和商品数量展示，金额单位：人民币。计入采购价沿用保存时的票点处理结果。</p>
    <div class="scroll"><table>
      <thead><tr><th>SKU</th><th>{{ snapshot.bundle ? '每套件数' : '件数' }}</th><th>采购原价/件</th><th>采购发票</th><th>票点</th><th>计入采购价/件</th><th>国内运费/件</th></tr></thead>
      <tbody><tr v-for="(item, index) in snapshot.items" :key="index">
        <td>{{ item.sku }}</td><td>{{ item.count }}</td><td>{{ snapshotMoney(item.base) }}</td><td>{{ item.invoice || '未保存' }}</td><td>{{ item.rate == null ? '未保存' : `${item.rate}%` }}</td><td>{{ snapshotMoney(item.purchase) }}</td><td>{{ snapshotMoney(item.freight) }}</td>
      </tr><tr v-if="!snapshot.items.length"><td colspan="7">旧记录未保存组合商品成本明细</td></tr></tbody>
    </table></div>
    <div class="scroll totals"><table>
      <thead><tr><th>数量</th><th>采购成本</th><th>国内运费</th><th>产品成本合计</th></tr></thead>
      <tbody><tr v-for="row in snapshot.rows" :key="row.quantity"><td>{{ row.quantity }}{{ snapshot.unit }}</td><td>{{ snapshotMoney(row.purchase) }}</td><td>{{ snapshotMoney(row.freight) }}</td><td>{{ snapshotMoney(row.total) }}</td></tr></tbody>
    </table></div>
    <small>合计为采购成本＋国内运费，不含国际运费、关税或操作费。缺失项显示“未保存”，不按当前采购价回填。</small>
  </section>
</template>

<style scoped>
.product-cost-trace{margin:16px 0;padding:14px;border:1px solid #e3e8ed;border-radius:8px;background:#fafbfc;font-size:11px;color:#52616d}.product-cost-trace h3{margin:0 0 8px;color:#26323b;font-size:13px}.product-cost-trace p{line-height:1.6}.scroll{overflow-x:auto}.totals{margin-top:12px}.product-cost-trace table{width:100%;border-collapse:collapse;white-space:nowrap}.product-cost-trace th,.product-cost-trace td{padding:7px;border:1px solid #e3e8ed;text-align:center}.product-cost-trace small{display:block;margin-top:10px;line-height:1.6;color:#73818c}
</style>
