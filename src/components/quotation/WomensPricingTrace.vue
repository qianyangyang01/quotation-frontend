<script setup lang="ts">
import { canViewFullPricing, canViewPricingFactors } from '@/data/quotationVisibility'
import { isBundleQuotation, isWomensQuotation } from '@/data/quotationModes'
import { womensPolicyLabel, womensTierLabel } from '@/data/womensPricing'
import type { QuotationRecord } from '@/data/quotationRecords'
defineProps<{ record: QuotationRecord }>()
</script>
<template>
  <section v-if="isWomensQuotation(record.quoteMode)" class="womens-trace">
    <header><b>女装自动系数 · {{ isBundleQuotation(record.quoteMode) ? '组合 SKU' : '单品 SKU' }}</b><span v-if="record.womensPricing">规则版本 V{{ record.womensPricing.version + 1 }}</span></header>
    <p>本报价按各渠道、各件数／套数的综合成本折美元自动选档，替代客户等级系数。税费、附加费、操作费、佣金阈值及取整沿用原流程。</p>
    <p>以下为保存时的规则与计算依据，不随财务设置更新。再次发起按当前规则生成新报价；本记录及其审核、成交状态保留。</p>
    <p v-if="!record.womensPricing">此记录未保存女装规则快照，不能使用当前规则推算历史系数。</p>
    <template v-else>
      <p v-if="canViewPricingFactors">{{ womensPolicyLabel(record.womensPricing) }}</p>
      <div v-if="canViewFullPricing" class="trace-scroll"><table><thead><tr><th>国家 / 渠道</th><th>数量</th><th>综合成本 CNY</th><th>选档金额 USD</th><th>命中区间</th><th>系数</th><th>基础售价 CNY</th></tr></thead><tbody>
        <template v-for="option in record.quoteOptions" :key="option.id"><tr v-for="sample in option.womensPricingSamples || []" :key="sample.quantity"><td>{{ option.country }} · {{ option.carrier }} · {{ option.channel }}</td><td>{{ sample.quantity }}{{ isBundleQuotation(record.quoteMode) ? '套' : '件' }}</td><td>{{ sample.costCny.toFixed(4) }}</td><td>{{ (sample.costCny / record.exchangeRate).toFixed(6) }}</td><td>{{ womensTierLabel(sample) }}</td><td>{{ sample.coefficient }}</td><td>{{ sample.baseQuoteCny.toFixed(4) }}</td></tr></template>
      </tbody></table><p>金额列仅为展示精度；选档按未取整的金额判断。基础售价不含后续财务费用。</p></div>
    </template>
  </section>
</template>
<style scoped>
.womens-trace{margin:14px 0;border:1px solid #eadbc6;border-radius:9px;padding:15px;background:#fffaf2}.womens-trace header{display:flex;justify-content:space-between;gap:12px;font-size:13px}.womens-trace p{font-size:12px;line-height:1.7;color:#6b7781}.trace-scroll{overflow-x:auto}.womens-trace table{width:100%;border-collapse:collapse;font-size:12px}.womens-trace th,.womens-trace td{padding:9px;text-align:left;border-bottom:1px solid #e9e1d7}.womens-trace td{font-variant-numeric:tabular-nums}
</style>
