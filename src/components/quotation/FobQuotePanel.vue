<script setup lang="ts">
import { canViewPurchaseCost, canViewPricingFactors, canViewFullPricing } from '@/data/quotationVisibility'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import FobQuoteSheet from './FobQuoteSheet.vue'
import { FOB_SMALL_ORDER_EXTRA_CNY, fobQuantityQuote, fobTierQuotes, loadFobQuoteProduct, type FobQuoteProduct, type FobSmallOrderPolicy, type FobSourceMode } from '@/services/fobQuotation'

const props = defineProps<{ rate: number; financePending?: boolean; financeError?: string; policy: FobSmallOrderPolicy | null; salesperson?: string; initial?: {sku:string;source:FobSourceMode;customer:string;quantity:number} }>()
const emit = defineEmits<{ status: [value: string]; retryFinance: [] }>()
const sku = ref(props.initial?.sku || ''), source = ref<FobSourceMode>(props.initial?.source || 'auto'), busy = ref(false), error = ref(''), copied = ref('')
const quantity = ref(''), product = ref<FobQuoteProduct | null>(null), skuInput = ref<HTMLInputElement | null>(null)
const rulesOpen = ref(false), rulesElement = ref<HTMLElement | null>(null)
let generation = 0, controller: AbortController | undefined
function invalidate() { generation++; controller?.abort(); busy.value = false; error.value = ''; product.value = null; copied.value = ''; emit('status', '待查询SKU') }
watch([sku, source], invalidate)
onMounted(() => {skuInput.value?.focus({ preventScroll: true });if(props.initial)void query()})
onBeforeUnmount(() => { generation++; controller?.abort() })
async function query() {
  const normalized = sku.value.trim().toUpperCase().replace(/\s+/g, '')
  if (!normalized) { error.value = '请输入SKU'; return }
  const request = ++generation
  controller?.abort(); controller = new AbortController(); busy.value = true; error.value = ''; product.value = null; copied.value = ''; emit('status', '查询中')
  try {
    const loaded = await loadFobQuoteProduct(normalized, source.value, controller.signal)
    if (request !== generation) return
    product.value = loaded; quantity.value = String(props.initial?.sku === normalized ? props.initial.quantity : Math.ceil(loaded.parsed.minOrderQty / loaded.parsed.orderMultiple) * loaded.parsed.orderMultiple)
    emit('status', '已读取最新采购资料')
  } catch (e) { if (request === generation) { error.value = e instanceof Error ? e.message : '查询失败，请重试'; emit('status', '查询失败') } }
  finally { if (request === generation) busy.value = false }
}
const calculation = computed(() => {
  if (!product.value) return { rows: [], error: '' }
  if (props.financePending || props.financeError) return { rows: [], error: props.financeError || '正在读取财务汇率，请稍候' }
  try { return { rows: fobTierQuotes(product.value, props.rate), error: '' } }
  catch (e) { return { rows: [], error: e instanceof Error ? e.message : '报价计算失败' } }
})
const selected = computed(() => {
  if (!product.value || calculation.value.error) return { result: null, error: '' }
  try { return { result: fobQuantityQuote(product.value, calculation.value.rows, quantity.value, props.rate, props.policy), error: '' } }
  catch (e) { return { result: null, error: e instanceof Error ? e.message : '数量无效' } }
})
const policyText = computed(() => !props.policy ? '不足200元的加价口径待确认；基础阶梯价可查看，低于门槛的最终报价暂不生成。'
  : `${props.policy.scope === 'single-price' ? '仅单一采购价商品' : '所有商品'}：数量×对应成本价不足200元时，每件加${FOB_SMALL_ORDER_EXTRA_CNY}元，${props.policy.calculation === 'before-coefficient' ? '加在成本上后乘系数' : '在乘系数后加收'}，再除以汇率。`)
function openQuery() { skuInput.value?.focus(); skuInput.value?.select() }
async function openRules() { if (!canViewFullPricing.value) return; rulesOpen.value = true; await nextTick(); rulesElement.value?.scrollIntoView?.({ block: 'nearest' }) }
defineExpose({ openQuery, openRules })
function quantityRange(min: number, max: number | null, unit: string) { return max == null ? `${min}${unit}起` : `${min}—${max}${unit}` }
async function copy() {
  if (!product.value || !selected.value.result) return
  const p = product.value, quote = selected.value.result
  const text = `FOB（批发）报价\nSKU\t数量\t报关 USD/${quote.row.unit}\t不报关 USD/${quote.row.unit}\n${p.sku}\t${quote.quantity}\t${quote.declaredUsd}\t${quote.undeclaredUsd}\n汇率：${props.rate} CNY/USD；${quote.extraCny ? `已含每件${quote.extraCny}元小额订单加价` : '无小额订单加价'}`
  try { await navigator.clipboard.writeText(text); copied.value = '已复制当前数量的两种报价' }
  catch { copied.value = '复制失败，请选择表格内容手动复制' }
}
</script>

<template>
  <section class="fob-card" aria-label="FOB批发报价">
    <header><h2><i>02</i> FOB阶梯报价</h2></header>
    <form class="fob-query" aria-label="FOB查询" @submit.prevent="query">
      <label class="query-sku">SKU<input ref="skuInput" v-model="sku" aria-label="FOB查询SKU" placeholder="输入SKU，例如 PF2600053" autocomplete="off"></label>
      <label class="query-source">采购数据来源<select v-model="source" aria-label="FOB采购数据来源"><option value="auto">自动：优先FOB资料，其次新采购数据</option><option value="fob">FOB数据（粘贴更新）</option><option value="standard">系统新采购数据</option></select></label>
      <button type="submit" class="query-submit" :disabled="busy || !sku.trim()">{{ busy ? '查询中…' : '查询报价' }}</button>
    </form>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-if="product" class="product-summary"><strong class="fob-sku">{{ product.sku }}</strong> · {{ product.category || '未分类' }} · 克重：{{ product.weight }}<span class="source">{{ product.source === 'fob' ? 'FOB数据 · 待验证' : '新采购资料' }}</span></p>
    <div class="fob-parameters"><span v-if="canViewPurchaseCost">票点 <b>10%</b></span><span>汇率 <b>{{ rate }} CNY/USD</b></span><span v-if="canViewPricingFactors">报关 <b>×1.14</b></span><span v-if="canViewPricingFactors">不报关 <b>×1.1628</b></span></div>
    <p v-if="calculation.error" class="error" role="alert">{{ calculation.error }} <button v-if="financeError" type="button" :disabled="financePending" @click="emit('retryFinance')">重新读取财务汇率</button></p>
    <template v-if="product && !calculation.error">
      <div class="quantity-quote">
        <div class="quantity-controls"><label>报价数量 <input v-model="quantity" aria-label="FOB报价数量" inputmode="numeric" placeholder="填写件数"></label><span class="quantity-minimum">起订量 {{ product.parsed.minOrderQty }}<template v-if="product.parsed.orderMultiple > 1"> · 按 {{ product.parsed.orderMultiple }} 的倍数下单</template></span></div>
        <div v-if="selected.result" class="quantity-status">已匹配阶梯{{ calculation.rows.indexOf(selected.result.row) + 1 }}<span> · {{ selected.result.extraCny ? `最终价已含每件${selected.result.extraCny}元小额订单加价` : '无小额订单加价' }}</span></div>
        <button type="button" :disabled="!selected.result" @click="copy">复制当前数量报价</button>
      </div>
      <p v-if="selected.error" class="error" role="alert">{{ selected.error }}</p>
      <div class="fob-table-wrap"><table aria-label="FOB基础阶梯与本次数量最终报价">
        <thead>
          <tr>
            <th rowspan="2" scope="col">采购阶梯</th><th rowspan="2" scope="col">数量区间</th>
            <template v-if="canViewPurchaseCost"><th rowspan="2" scope="col">不含票 ¥/单位</th><th rowspan="2" scope="col">含票价 ¥/单位</th><th rowspan="2" scope="col">国内运费 ¥/单位</th><th rowspan="2" scope="col">成本价 ¥/单位</th></template>
            <th colspan="2" scope="colgroup" class="base-heading">基础单价<small>未含小额订单加价 · 仅供核对</small></th>
            <th colspan="2" scope="colgroup" class="current-heading current-start">本次数量最终报价<small v-if="selected.result">{{ selected.result.quantity }}{{ selected.result.row.unit }} · 对外报价请用此处</small><small v-else>请核对数量或报价规则</small></th>
          </tr>
          <tr><th scope="col" class="base-heading">报关 $/单位</th><th scope="col" class="base-heading">不报关 $/单位</th><th scope="col" class="current-heading current-start">报关单价</th><th scope="col" class="current-heading">不报关单价</th></tr>
        </thead>
        <tbody><tr v-for="(row,i) in calculation.rows" :key="i" :class="{ selected: selected.result?.row === row }">
          <td>阶梯{{ i + 1 }}<span v-if="selected.result?.row === row" class="active-tier">当前档</span></td><td>{{ quantityRange(row.minQty,row.maxQty,row.unit) }}</td>
          <template v-if="canViewPurchaseCost"><td>{{ row.purchaseCny }}</td><td>{{ row.taxIncludedCny }}</td><td>{{ row.freightCny }}</td><td>{{ row.costCny }}</td></template><td class="quote-price">{{ row.declaredUsd }}</td><td class="quote-price">{{ row.undeclaredUsd }}</td>
          <template v-if="i===0">
            <td :rowspan="calculation.rows.length" class="current-price current-start"><div v-if="selected.result" class="final-quote" aria-live="polite"><span class="sr-only">报关 </span><strong>${{ selected.result.declaredUsd }}</strong><small>/{{ selected.result.row.unit }}</small></div><span v-else class="price-pending">暂无有效报价</span></td>
            <td :rowspan="calculation.rows.length" class="current-price"><div v-if="selected.result" class="final-quote" aria-live="polite"><span class="sr-only">不报关 </span><strong>${{ selected.result.undeclaredUsd }}</strong><small>/{{ selected.result.row.unit }}</small></div><span v-else class="price-pending">暂无有效报价</span></td>
          </template>
        </tr></tbody>
      </table></div>
      <p class="help"><template v-if="selected.result">右侧蓝色区域为本次数量的最终报价，已计入适用的小额订单加价。</template><template v-else>最终报价暂不可用，请先处理上方提示。</template><template v-if="canViewPurchaseCost">运费依据：{{ product.parsed.freight.basis }}。</template></p>
      <template v-if="canViewPurchaseCost"><p v-for="notice in product.notices" :key="notice" class="notice">{{ notice }}</p></template>
      <p v-if="canViewPurchaseCost && calculation.rows.length === 1" class="help">按本档成本达到200元且满足起订量及下单倍数的最低数量：{{ calculation.rows[0]?.thresholdQty ?? '本档成本或数量范围无法达到金额门槛' }}<template v-if="calculation.rows[0]?.thresholdQty != null">{{ calculation.rows[0]?.unit }}</template>。</p>
      <p v-if="copied" role="status" class="help">{{ copied }}</p>
    </template>
    <details v-if="canViewFullPricing" ref="rulesElement" :open="rulesOpen" class="fob-rules"><summary>FOB计算规则</summary><p>含票价＝不含票采购价×1.1；成本价＝含票价＋批量均摊运费；报关＝成本价×1.14÷汇率；不报关＝成本价×1.14×1.02÷汇率。中间计算不提前取整，美元单价四舍五入保留两位。</p><p>{{ policyText }}</p></details>
  </section>
  <FobQuoteSheet v-if="product && !calculation.error" :key="product.sku" :product="product" :rate="rate" :quantity="quantity" :policy="policy" :salesperson="salesperson" :initial-customer="initial?.customer" />
</template>

<style scoped>
.fob-sku{font-size:20px;font-weight:800;line-height:1.4;color:#233442;overflow-wrap:anywhere}
.fob-card{margin-top:10px;border:1px solid #dce7ee;border-radius:12px;background:#fff;padding:12px 14px;color:#233442}.fob-card header{display:flex;justify-content:space-between;align-items:center;gap:16px}.fob-card h2{font-size:16px;margin:0}.fob-card h2 i{display:inline-grid;place-items:center;width:27px;height:27px;margin-right:8px;background:#e5f2ff;color:#368fe0;border-radius:6px;font-size:10px;font-style:normal}.source{display:inline-block;margin-left:10px;padding:3px 7px;border-radius:8px;background:#edf6f0;color:#25855d;font-size:10px}.fob-card button,.fob-query button{border:1px solid #d3dfe7;background:#fff;color:#33536b;border-radius:6px;padding:9px 12px;cursor:pointer;font:inherit;font-size:12px}.fob-parameters{display:flex;flex-wrap:wrap;gap:24px;padding:8px 0;font-size:12px;color:#71818e}.fob-parameters b{color:#334a60}.fob-table-wrap{overflow:auto}.fob-card table{border-collapse:collapse;width:100%;min-width:850px;font-size:12px;text-align:center}.fob-card th,.fob-card td{padding:8px 9px;border:1px solid #dce5eb}.fob-card th{background:#f0f6f8;font-size:11px}.help,.notice,.fob-rules p{font-size:12px;line-height:1.8;color:#71818e}.notice{color:#9a640e}.quantity-quote{display:grid;grid-template-columns:auto minmax(0,1fr) auto;gap:8px 12px;align-items:center;padding:8px 12px;background:#fff8df;border-radius:8px;font-size:12px}.quantity-controls{display:flex;flex-wrap:wrap;align-items:center;gap:8px 12px;min-width:0;grid-column:1;grid-row:1}.quantity-controls label{display:flex;align-items:center;white-space:nowrap}.quantity-quote input{box-sizing:border-box;width:76px;margin-left:8px;padding:9px;border:1px solid #d3dfe7;border-radius:6px;background:#fff;color:#233442}.quantity-minimum{color:#71818e}.quantity-quote button{grid-column:3;grid-row:1;align-self:start;white-space:nowrap}.error{color:#b73126;font-size:12px;line-height:1.8}.updated{color:#82939e;font-size:11px}.fob-rules{margin-top:8px;font-size:12px}.fob-rules summary{cursor:pointer;color:#8e651d}.fob-query{display:flex;flex-wrap:wrap;align-items:flex-end;gap:12px;margin-top:12px;padding:12px;background:#f7f9fb;border:1px solid #e6edf1;border-radius:8px}.fob-query label{display:grid;gap:6px;font-size:12px;color:#607587}.query-sku,.query-source{flex:1 1 0;min-width:0}.fob-query input,.fob-query select{box-sizing:border-box;width:100%;height:38px;padding:8px 10px;border:1px solid #ccdbe5;border-radius:6px;background:#fff;color:#233442;font:inherit}.fob-query input{font-size:16px;font-weight:600}.fob-query input::placeholder{font-size:13px;font-weight:400;color:#8a99a6}.fob-query input:focus,.fob-query select:focus{outline:2px solid #f6b96a;outline-offset:1px}.fob-card .query-submit{height:38px;min-width:108px;background:#f39017;color:#fff;border-color:#f39017;font-weight:700}.product-summary{margin:10px 0 2px;font-size:12px;color:#73828d}.fob-card button:disabled,.fob-query button:disabled{opacity:.5;cursor:not-allowed}@media(max-width:600px){.query-sku,.query-source{flex:1 1 100%}.fob-card .query-submit{flex:1 1 100%}.fob-card{padding:14px}.fob-card header{align-items:flex-start;flex-direction:column}.quantity-quote{grid-template-columns:minmax(0,1fr) auto;gap:8px 10px;padding:10px}.quantity-quote input{width:64px}.quantity-quote button{grid-column:2}}
/* Keep reference prices quiet and the actual quantity quote visually distinct. */
.fob-card .base-heading{background:#f5f7f9;color:#708092;font-weight:500}
.fob-card th small{display:block;margin-top:5px;font-size:10px;font-weight:400;white-space:nowrap}
.fob-card .quote-price{background:#fafbfc;color:#7b8996;font-weight:400}
.fob-card .current-heading{background:#e8f2fc;color:#1d5e94;font-size:13px}
.fob-card .current-heading small{font-size:11px;color:#3c709c}
.fob-card .current-start{border-left:2px solid #8fb7dc}
.fob-card .current-price{background:#f3f8ff;min-width:102px;vertical-align:middle;box-shadow:none;padding:16px 10px}
.fob-card .selected td:not(.current-price){background:#f1f7fc;box-shadow:none}
.active-tier{display:block;margin-top:4px;color:#387cae;font-size:10px}
.quantity-quote{margin:5px 0 12px;background:#f7f9fb;border:1px solid #e3eaf0;border-radius:6px}
.quantity-status{font-size:12px;color:#47718f;grid-column:2;grid-row:1}
.quantity-status span{color:#748695}
.fob-card .quantity-quote button{background:#246599;color:#fff;border-color:#246599}
.current-price .final-quote{display:block;text-align:center;white-space:nowrap}
.current-price .final-quote strong{font-size:27px;line-height:1.5;color:#185b93;font-weight:750;font-variant-numeric:tabular-nums}
.current-price .final-quote small{font-size:12px;color:#6483a0;margin-left:3px}
.price-pending{font-size:11px;color:#7b8996}
.sr-only{position:absolute;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip-path:inset(50%);white-space:nowrap;border:0}
@media(max-width:600px){.quantity-status{grid-column:1 / -1;grid-row:2}.fob-card .current-price{min-width:96px}.current-price .final-quote strong{font-size:24px}}
</style>
