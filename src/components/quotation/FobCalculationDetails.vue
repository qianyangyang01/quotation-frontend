<script setup lang="ts">
import { computed } from 'vue'
import { canViewPurchaseCost } from '@/data/quotationVisibility'
import { canViewFobCoefficient } from '@/services/fobClassification'
import { needsFobInvoiceVerification, type FobQuoteProduct, type FobQuoteRow } from '@/services/fobQuotation'

const props = withDefaults(defineProps<{product:FobQuoteProduct;rate:number;row?:FobQuoteRow|null;quantity?:number;extraCny?:number;finalUsd?:string;showNotice?:boolean;showCalculation?:boolean}>(),{showNotice:true,showCalculation:true,extraCny:0})
const legacy=computed(()=>props.product.invoicePricing?.mode==='legacy-ten-point')
const needsVerification=computed(()=>needsFobInvoiceVerification(props.product))
const sourceLabel=computed(()=>props.product.source==='fob'?'原FOB专用资料':'系统新采购数据')
const point=computed(()=>props.product.invoicePricing?.effectiveTaxPoint ?? (legacy.value?0.1:undefined))
const percent=(n:number)=>Number((n*100).toFixed(6))
const money=(n:number)=>Number(n.toFixed(4)).toLocaleString('en-US',{minimumFractionDigits:2,maximumFractionDigits:4,useGrouping:false})
</script>
<template>
  <div v-if="showNotice && needsVerification" class="legacy-invoice-notice" role="note">
    <strong>{{product.invoicePricing?.basisLabel || '老 FOB 资料 · 系统统一按10点专票计价'}}</strong><span class="legacy-badge">可报价 · 待采购确认</span>
    <p class="procurement-reminder"><strong>采购核实提醒：本次可正常报价，请联系采购确认票据类型和票点。</strong>系统默认10点不代表采购已确认；实际资料维护后，请重新查询报价。</p>
    <p class="procurement-reminder">请采购更新【{{sourceLabel}}】中 SKU <strong>{{product.sku}}</strong> 的票类型和票点。提醒以该来源的实际资料为准，刷新、重新登录或重复报价不会消除；补齐并重新查询后，新报价才解除提醒。已保存记录保留当时的计价依据。</p>
    <p v-if="product.invoicePricing?.missingFields?.length">当前缺少：{{product.invoicePricing.missingFields.join('、')}}。</p>
    <p><template v-if="product.invoicePricing?.mode==='included'">各档已标记含票，直接使用原阶梯价，不再加10点。</template><template v-else>本次报价按各档原采购价 × 1.10 计算。</template>此为系统计价口径，未修改或回填{{product.source==='fob'?' FOB ':''}}采购数据。</p>
  </div>
  <section v-if="showCalculation && product.classification && row && finalUsd" class="fob-calculation" aria-label="FOB报价计算过程">
    <header><strong>本次数量的计算过程</strong><span>{{quantity}}{{row.unit}} · {{canViewFobCoefficient?'管理员视图':'报价明细'}}</span></header>
    <div class="calculation-steps">
      <div v-if="canViewPurchaseCost" class="step"><span>① {{product.classification==='ordinary-fob'?'计价采购价':'含专票采购价'}}</span>
        <b v-if="point!=null && product.invoicePricing?.mode!=='included'">{{money(row.purchaseCny)}} ×（1 + {{percent(point)}}%）= ¥{{money(row.taxIncludedCny)}}</b>
        <b v-else-if="product.invoicePricing?.mode==='ratio' && canViewFobCoefficient">{{money(row.purchaseCny)}} ×（{{money(product.invoicePricing.baseIncludedCny!)}} ÷ {{money(product.invoicePricing.basePurchaseCny)}}）= ¥{{money(row.taxIncludedCny)}}</b>
        <b v-else>¥{{money(row.taxIncludedCny)}}</b>
        <small>{{product.invoicePricing?.mode==='included'?'阶梯价已含票，直接使用':needsVerification?'默认10点只计入采购价，待采购补齐票据资料':product.classification==='ordinary-fob'?'沿用普通FOB原10点成本口径':point!=null?'采用数据源实际票点，不从专票名称推算':product.invoicePricing?.mode==='ratio'?'历史快照：按保存时含票比例换算':'按本档采购价格计价'}}</small>
      </div>
      <div v-if="canViewPurchaseCost" class="step"><span>② 单件综合成本</span><b>{{money(row.taxIncludedCny)}} + {{money(row.freightCny)}} = ¥{{money(row.costCny)}}</b><small>国内运费直接相加，不加票点</small></div>
      <div class="step"><span>{{canViewPurchaseCost?'③ ':''}}本次数量最终报价</span>
        <b v-if="canViewFobCoefficient && canViewPurchaseCost && product.pricing?.fobCoefficient"><template v-if="extraCny">（{{money(row.costCny)}} + {{money(extraCny)}}）</template><template v-else>{{money(row.costCny)}}</template> × {{product.pricing.fobCoefficient}} ÷ {{money(rate)}} = <em>${{finalUsd}} / {{row.unit}}</em></b>
        <b v-else>${{finalUsd}} / {{row.unit}}</b>
        <small>{{extraCny?`含每件 ¥${money(extraCny)} 小额订单加价`:'无小额订单加价'}} · 汇率 {{rate}} CNY/USD</small>
      </div>
    </div>
    <p>按所选报价分类计算，最终美元单价四舍五入保留2位；报价系数及含系数的计算步骤仅管理员可见。</p>
  </section>
</template>
<style scoped>
.legacy-invoice-notice .procurement-reminder{color:#b42318}

.legacy-invoice-notice{padding:13px 16px;margin:10px 0 13px;background:#fff1f0;border:1px solid #f2b8b5;border-left:4px solid #d92d20;border-radius:7px;color:#b42318;font-size:12px;line-height:1.6}.legacy-invoice-notice strong{font-size:14px}.legacy-invoice-notice p{margin:8px 0 0}.legacy-badge{margin-left:12px;border-radius:20px;padding:3px 9px;background:#e4f4eb;color:#248664}.fob-calculation{background:#f6f9fc;border:1px solid #d8e3ed;border-radius:8px;padding:16px;margin:14px 0;color:#204868}.fob-calculation header{display:flex;justify-content:space-between;gap:12px;margin-bottom:13px}.fob-calculation header strong{font-size:14px}.fob-calculation header span,.fob-calculation p{font-size:12px;color:#738698}.calculation-steps{display:flex;flex-wrap:wrap;gap:12px}.step{flex:1 1 240px;background:white;border:1px solid #e1e9ef;border-radius:6px;padding:12px;display:flex;flex-direction:column;gap:9px}.step span,.step small{font-size:12px;color:#698195}.step b{font-size:15px;line-height:1.5}.step em{font-style:normal;color:#176b9f}.fob-calculation p{margin:12px 0 0}
</style>
