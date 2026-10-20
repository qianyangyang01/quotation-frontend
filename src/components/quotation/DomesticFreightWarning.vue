<script setup lang="ts">
import { canViewPurchaseCost } from '@/data/quotationVisibility'
import type { DomesticFreightWarning } from '@/domain/domesticFreightWarning'

defineProps<{ warning: DomesticFreightWarning; sku?: string }>()
const number = (value: number) => value.toLocaleString('zh-CN', { maximumFractionDigits: 6 })
const money = (value: number) => value.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 6 })
</script>

<template>
  <aside class="domestic-freight-warning" role="status" aria-live="polite" aria-label="国内运费预警">
    <div class="warning-heading">
      <svg class="warning-icon" viewBox="0 0 32 32" aria-hidden="true"><path d="M13.4 4.5a3 3 0 0 1 5.2 0l12 21A3 3 0 0 1 28 30H4a3 3 0 0 1-2.6-4.5z" fill="currentColor"/><path d="M16 11v8" stroke="white" stroke-width="3" stroke-linecap="round"/><circle cx="16" cy="24" r="1.6" fill="white"/></svg>
      <div><b v-if="sku" class="warning-sku">{{ sku }}</b><strong>运费超出合理范围，请联系采购确认是否有误！</strong></div>
    </div>
    <div v-if="canViewPurchaseCost" class="warning-values">
      <span>工厂地区：<b>{{ warning.regionLabel }}</b></span>
      <span>当前国内运费：<b>¥{{ money(warning.actualUnit) }} / 件</b></span>
      <span>单件参考运费：<b>¥{{ money(warning.expectedUnit) }} / 件</b></span>
      <span>每件高于参考价：<b class="warning-excess">¥{{ money(warning.excessUnit) }}</b></span>
    </div>
    <div class="warning-footer">
      <p v-if="canViewPurchaseCost" class="warning-calculation">计算依据：单件 {{ number(warning.weightG) }}g → 按 {{ warning.chargeableKg }}kg 计费；首重 5 元<span v-if="warning.chargeableKg > 1"> + 续重 {{ warning.chargeableKg - 1 }}kg × {{ warning.rate }} 元</span> = {{ number(warning.expectedUnit) }} 元/件。当前运费来源：{{ warning.quantity }}件总运费 ¥{{ money(warning.actualTotal) }} ÷ {{ warning.quantity }} = ¥{{ money(warning.actualUnit) }}/件。</p>
      <p class="warning-advisory">当前每件运费超过单件参考运费 50% 时预警。本提示仅供核对，可继续正常报价。</p>
      <details class="warning-rules">
        <summary>查看计算规则</summary>
        <div>
          <p>国内运费首重 1kg 为 5 元，不足 1kg 也按 1kg；超过部分向上取整到整公斤。工厂位于江苏、浙江、上海、安徽，续重每公斤 1.5 元；其他已确认地区为 3 元。</p>
          <p>按当前采购阶梯实际采用的运费档位，将总运费除以该档件数，得到当前每件运费；高阶梯缺失时跟随实际回退档位。参考运费按采购单件克重计算，10件、100件档位只用于分摊运费，不合并重量。当前每件运费严格大于单件参考运费的 1.5 倍时预警，正好超出 50% 不预警，不改变采购运费和报价。</p>
          <p>不使用国际运费、包材重量或业务员指定重量。判断资料不足时不作超额结论；首重以内无需地区也能核对。采购更新后重新查询商品，会按最新资料重新判断。</p>
        </div>
      </details>
    </div>
  </aside>
</template>

<style scoped>
.domestic-freight-warning{box-sizing:border-box;margin:14px 0 0;padding:16px 20px 12px;border:1px solid #ff6c75;border-radius:7px;background:linear-gradient(110deg,#fff5f3,#fff7f3);color:#596878;line-height:1.6}
.warning-heading{display:flex;gap:16px;align-items:center;color:#dc1828}.warning-icon{flex:0 0 32px;width:32px;height:32px}.warning-heading>div{display:flex;align-items:center;flex-wrap:wrap;gap:6px 12px}.warning-heading strong{font-size:19px;font-weight:750}.warning-sku{font-size:13px;border:1px solid #ffb8ba;border-radius:4px;padding:1px 6px}
.warning-values{display:flex;flex-wrap:wrap;gap:9px 0;margin:14px 0 10px;font-size:12px}.warning-values>span{padding:0 22px;border-right:1px solid #e7d8d5}.warning-values>span:first-child{padding-left:0}.warning-values>span:last-child{border:0;padding-right:0}.warning-values b{margin-left:4px;color:#243649}.warning-values .warning-excess{color:#dc1828;font-size:16px}
.warning-footer{display:flex;flex-wrap:wrap;gap:4px 16px;border-top:1px solid #efddda;padding-top:8px;font-size:12px}.warning-footer p{margin:0}.warning-calculation{flex:1 1 75%;color:#6a7887}.warning-advisory{flex:1 1 75%;color:#865958}.warning-rules{margin-left:auto;max-width:100%;align-self:flex-end}.warning-rules[open]{flex-basis:100%}.warning-rules summary{color:#345ed2;cursor:pointer;text-decoration:underline;list-style:none;text-align:right}.warning-rules summary::-webkit-details-marker{display:none}.warning-rules summary:focus-visible{outline:2px solid #345ed2;outline-offset:3px}.warning-rules>div{margin-top:10px;padding:10px 12px;border-radius:5px;background:#fff;color:#596878}.warning-rules>div p+p{margin-top:6px}
@media(max-width:700px){.domestic-freight-warning{padding:12px}.warning-heading{gap:10px;align-items:flex-start}.warning-heading strong{font-size:16px}.warning-icon{flex-basis:25px;width:25px;height:25px}.warning-values{display:grid;gap:7px}.warning-values>span{padding:0;border:0}.warning-rules{flex-basis:100%}}
</style>
