<script setup lang="ts">
import { computed } from 'vue'
import { canViewPurchaseCost, canViewPricingFactors } from '@/data/quotationVisibility'
import type { QuotationRecord } from '@/data/quotationRecords'
import FobQuoteSheet from './FobQuoteSheet.vue'

const props = defineProps<{record:QuotationRecord}>()
const tiers = computed(() => props.record.fob?.tiers?.length ? props.record.fob.tiers : [null])
const quoteUnit = computed(() => props.record.fob?.current?.unit || props.record.fob?.tiers?.[0]?.unit || '单位')
</script>
<template>
  <section v-if="record.fob" class="fob-record" aria-label="FOB历史报价快照">
    <div class="fob-meta"><b>FOB（批发）报价 · {{ record.primarySku }}</b><span>客户：{{ record.customerName }}</span><span>报价人：{{ record.salespersonName }}</span><span>采购来源：{{ record.fob.product.source==='fob'?'FOB数据':'新采购资料' }}</span><span>报价数量：{{ record.fob.quantity }}</span><span class="finance-meta">汇率：{{ record.fob.rate }} CNY/USD<template v-if="canViewPurchaseCost"> · 票点10%</template><template v-if="canViewPricingFactors"> · 报关×1.14 · 不报关×1.1628</template></span></div>
    <p>以下为保存时的报价快照；采购资料和汇率更新不会改变此报价。</p>
    <div class="cost-table">
      <table aria-label="FOB基础价格与本次数量最终单价">
        <thead>
          <tr>
            <th rowspan="2" scope="col">数量</th>
            <template v-if="canViewPurchaseCost"><th rowspan="2" scope="col">采购价 ¥</th><th rowspan="2" scope="col">含票价 ¥</th><th rowspan="2" scope="col">国内运费 ¥</th><th rowspan="2" scope="col">成本价 ¥</th></template>
            <th colspan="2" scope="colgroup" class="base-heading">基础单价<small>未含小额订单加价</small></th>
            <th colspan="2" scope="colgroup" class="final-heading final-start">本次最终单价 · {{ record.fob.quantity }}{{ quoteUnit }}<small>{{ record.fob.current ? '已含适用的小额订单加价' : '此快照未保存最终单价' }}</small></th>
          </tr>
          <tr><th scope="col" class="base-heading">报关 $</th><th scope="col" class="base-heading">不报关 $</th><th scope="col" class="final-heading final-start">报关 $</th><th scope="col" class="final-heading">不报关 $</th></tr>
        </thead>
        <tbody>
          <tr v-for="(tier,index) in tiers" :key="tier?.minQty ?? 'missing'">
            <template v-if="tier">
              <td>{{ tier.minQty }}{{ tier.maxQty==null?'+':'–'+tier.maxQty }}{{ tier.unit }}</td>
              <template v-if="canViewPurchaseCost"><td>{{ tier.purchaseCny }}</td><td>{{ tier.taxIncludedCny }}</td><td>{{ tier.freightCny }}</td><td>{{ tier.costCny }}</td></template>
              <td class="base-price">{{ tier.declaredUsd }}</td><td class="base-price">{{ tier.undeclaredUsd }}</td>
            </template>
            <td v-else :colspan="canViewPurchaseCost ? 7 : 3" class="missing-base">此快照未保存基础价格明细</td>
            <template v-if="index===0">
              <td :rowspan="tiers.length" class="final-price final-start"><strong v-if="record.fob.current">{{ record.fob.current.declaredUsd }}</strong><span v-else>未保存</span></td>
              <td :rowspan="tiers.length" class="final-price"><strong v-if="record.fob.current">{{ record.fob.current.undeclaredUsd }}</strong><span v-else>未保存</span></td>
            </template>
          </tr>
        </tbody>
      </table>
    </div>
    <div class="price-notes"><p v-if="record.fob.current">对外报价请以右侧「本次最终单价」为准，单位：美元/{{ quoteUnit }}。</p><p v-if="canViewPurchaseCost">国内运费依据：{{ record.fob.product.parsed.freight.basis }}</p></div>
    <p v-if="!record.fob.current" class="missing-final" role="alert">此历史快照未保存本次数量的最终单价，请核对下方已保存客户报价单；基础阶梯单价不可直接作为最终报价。</p>
    <FobQuoteSheet :product="record.fob.product" :rate="record.fob.rate" :quantity="String(record.fob.quantity)" :policy="record.fob.policy" :saved-sheet="record.fob.sheet" />
  </section>
  <p v-else role="alert">此FOB报价缺少完整快照，无法还原报价单，请联系管理员核查。</p>
</template>
<style scoped>
.fob-record{padding:14px 24px}
.fob-meta{display:flex;flex-wrap:wrap;gap:12px;font-size:13px}
.fob-meta b,.finance-meta{width:100%}
.fob-record p{font-size:12px;color:#647588;line-height:1.6}
.cost-table{overflow:auto;margin-top:16px;border:1px solid #dce4ec;border-radius:6px}
.cost-table table{border-collapse:separate;border-spacing:0;width:100%;font-size:12px;text-align:center}
.cost-table td,.cost-table th{padding:10px 8px;white-space:nowrap;border-right:1px solid #e0e5eb;border-bottom:1px solid #e0e5eb}
.cost-table th{background:#fff5e9;font-weight:600;color:#3b4e61}
.cost-table th:last-child,.cost-table td:last-child{border-right:0}
.cost-table tbody tr:last-child td,.cost-table td[rowspan]{border-bottom:0}
.cost-table small{display:block;margin-top:4px;font-size:10px;font-weight:400;color:#788694}
.cost-table .base-heading{background:#f5f7f9;color:#617182}
.base-price{color:#758494}
.cost-table .final-heading{background:#eaf3fc;color:#205987}
.cost-table .final-heading small{color:#5a7894}
.cost-table .final-start{border-left:2px solid #94bce0}
.cost-table .final-price{background:#f5f9ff;min-width:78px;padding:12px 8px}
.final-price strong{font-size:22px;font-weight:700;color:#1d5e94;font-variant-numeric:tabular-nums}
.price-notes{display:flex;flex-wrap:wrap;justify-content:space-between;gap:3px 14px;margin:8px 0 16px}
.price-notes p{margin:0;font-size:11px}
.missing-base{color:#83909d}
.fob-record .missing-final{padding:12px;border:1px solid #efc889;border-radius:6px;background:#fff8eb;color:#704619}
@media(max-width:600px){.fob-record{padding:14px 12px}.cost-table td,.cost-table th{padding:9px 7px}.final-price strong{font-size:20px}}
</style>
