<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import FobQuoteSheet from './FobQuoteSheet.vue'
import { fobQuantityQuote, fobTierQuotes, loadFobQuoteProduct, type FobQuoteProduct, type FobSmallOrderPolicy, type FobSourceMode } from '@/services/fobQuotation'

const props = defineProps<{ rate: number; financePending?: boolean; financeError?: string; policy: FobSmallOrderPolicy | null; salesperson?: string }>()
const emit = defineEmits<{ status: [value: string]; retryFinance: [] }>()
const showQuery = ref(true), sku = ref(''), source = ref<FobSourceMode>('auto'), busy = ref(false), error = ref(''), copied = ref('')
const quantity = ref(''), product = ref<FobQuoteProduct | null>(null), skuInput = ref<HTMLInputElement | null>(null)
const rulesOpen = ref(false), rulesElement = ref<HTMLElement | null>(null)
let generation = 0, controller: AbortController | undefined
function invalidate() { generation++; controller?.abort(); busy.value = false; error.value = ''; product.value = null; copied.value = ''; emit('status', '待查询SKU') }
watch([sku, source], invalidate)
watch(showQuery, async open => { if (open) { await nextTick(); skuInput.value?.focus() } }, { immediate: true })
onBeforeUnmount(() => { generation++; controller?.abort() })
async function query() {
  const normalized = sku.value.trim().toUpperCase().replace(/\s+/g, '')
  if (!normalized) { error.value = '请输入SKU'; return }
  const request = ++generation
  controller?.abort(); controller = new AbortController(); busy.value = true; error.value = ''; product.value = null; copied.value = ''; emit('status', '查询中')
  try {
    const loaded = await loadFobQuoteProduct(normalized, source.value, controller.signal)
    if (request !== generation) return
    product.value = loaded; quantity.value = String(Math.ceil(loaded.parsed.minOrderQty / loaded.parsed.orderMultiple) * loaded.parsed.orderMultiple)
    showQuery.value = false; emit('status', '已读取最新采购资料')
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
  : `${props.policy.scope === 'single-price' ? '仅单一采购价商品' : '所有商品'}：数量×对应成本价不足200元时，每件加2元，${props.policy.calculation === 'before-coefficient' ? '加在成本上后乘系数' : '在乘系数后加收'}，再除以汇率。`)
function openQuery() { showQuery.value = true }
async function openRules() { rulesOpen.value = true; await nextTick(); rulesElement.value?.scrollIntoView?.({ block: 'nearest' }) }
defineExpose({ openQuery, openRules })
function quantityRange(min: number, max: number | null, unit: string) { return max == null ? `${min}${unit}起` : `${min}—${max}${unit}` }
async function copy() {
  if (!product.value || !selected.value.result) return
  const p = product.value, quote = selected.value.result
  const text = `FOB（批发）报价\nSKU\t数量\t报关 USD/${quote.row.unit}\t不报关 USD/${quote.row.unit}\n${p.sku}\t${quote.quantity}\t${quote.declaredUsd}\t${quote.undeclaredUsd}\n汇率：${props.rate} CNY/USD；${quote.extraCny ? '已含每件2元小额订单加价' : '无小额订单加价'}`
  try { await navigator.clipboard.writeText(text); copied.value = '已复制当前数量的两种报价' }
  catch { copied.value = '复制失败，请选择表格内容手动复制' }
}
</script>

<template>
  <section class="fob-card" aria-label="FOB批发报价">
    <header><div><h2><i>02</i> FOB阶梯报价</h2><p v-if="product">{{ product.sku }} · {{ product.category || '未分类' }} · 克重：{{ product.weight }}<span class="source">{{ product.source === 'fob' ? 'FOB数据 · 待验证' : '新采购资料' }}</span></p><p v-else>查询SKU后，按采购表的实际档数展示报价。</p></div><button type="button" @click="openQuery">{{ product ? '更换 / 重新查询SKU' : '查询SKU' }}</button></header>
    <div class="fob-parameters"><span>票点 <b>10%</b></span><span>汇率 <b>{{ rate }} CNY/USD</b></span><span>报关 <b>×1.14</b></span><span>不报关 <b>×1.1628</b></span></div>
    <p v-if="calculation.error" class="error" role="alert">{{ calculation.error }} <button v-if="financeError" type="button" :disabled="financePending" @click="emit('retryFinance')">重新读取财务汇率</button></p>
    <template v-if="product && !calculation.error">
      <div class="fob-table-wrap"><table><thead><tr><th>采购阶梯</th><th>数量区间</th><th>不含票 ¥/单位</th><th>含票价 ¥/单位</th><th>国内运费 ¥/单位</th><th>成本价 ¥/单位</th><th class="quote-price">报关 $/单位</th><th class="quote-price">不报关 $/单位</th></tr></thead><tbody><tr v-for="(row,i) in calculation.rows" :key="i" :class="{ selected: selected.result?.row === row }"><td>阶梯{{ i + 1 }}</td><td>{{ quantityRange(row.minQty,row.maxQty,row.unit) }}</td><td>{{ row.purchaseCny }}</td><td>{{ row.taxIncludedCny }}</td><td>{{ row.freightCny }}</td><td>{{ row.costCny }}</td><td class="quote-price">{{ row.declaredUsd }}</td><td class="quote-price">{{ row.undeclaredUsd }}</td></tr></tbody></table></div>
      <p class="help">上表为基础阶梯报价，未含不足200元的加价；输入实际数量查看最终报价。运费统一采用：{{ product.parsed.freight.basis }}。</p>
      <p v-for="notice in product.notices" :key="notice" class="notice">{{ notice }}</p>
      <div class="quantity-quote"><label>报价数量 <input v-model="quantity" aria-label="FOB报价数量" inputmode="numeric" placeholder="填写件数"></label><span>起订量 {{ product.parsed.minOrderQty }}<template v-if="product.parsed.orderMultiple > 1"> · 按 {{ product.parsed.orderMultiple }} 的倍数下单</template></span><button type="button" :disabled="!selected.result" @click="copy">复制当前数量报价</button></div>
      <p v-if="selected.error" class="error" role="alert">{{ selected.error }}</p>
      <div v-if="selected.result" class="final-quote"><span>{{ selected.result.quantity }}{{ selected.result.row.unit }} · {{ selected.result.extraCny ? '每件已加2元' : '无小额订单加价' }}</span><strong>报关 ${{ selected.result.declaredUsd }}/{{ selected.result.row.unit }}</strong><strong>不报关 ${{ selected.result.undeclaredUsd }}/{{ selected.result.row.unit }}</strong></div>
      <p v-if="calculation.rows.length === 1" class="help">按本档成本达到200元且满足起订量及下单倍数的最低数量：{{ calculation.rows[0]?.thresholdQty ?? '本档成本或数量范围无法达到金额门槛' }}<template v-if="calculation.rows[0]?.thresholdQty != null">{{ calculation.rows[0]?.unit }}</template>。</p>
      <p v-if="copied" role="status" class="help">{{ copied }}</p>
    </template>
    <details ref="rulesElement" :open="rulesOpen" class="fob-rules"><summary>FOB计算规则</summary><p>含票价＝不含票采购价×1.1；成本价＝含票价＋批量均摊运费；报关＝成本价×1.14÷汇率；不报关＝成本价×1.14×1.02÷汇率。中间计算不提前取整，美元单价四舍五入保留两位。</p><p>{{ policyText }}</p></details>
  </section>
  <FobQuoteSheet v-if="product && !calculation.error" :key="product.sku" :product="product" :rate="rate" :quantity="quantity" :policy="policy" :salesperson="salesperson" />
  <Teleport to="body"><div v-if="showQuery" class="fob-query-mask" @keydown.esc.stop.prevent="showQuery=false" @click.self="showQuery=false"><form class="fob-query" role="dialog" aria-modal="true" aria-labelledby="fob-query-title" @submit.prevent="query"><header><h2 id="fob-query-title">查询FOB报价</h2><button type="button" aria-label="关闭FOB查询" @click="showQuery=false">×</button></header><label>SKU<input ref="skuInput" v-model="sku" aria-label="FOB查询SKU" placeholder="例如 PF2600053" autocomplete="off"></label><label>采购数据来源<select v-model="source" aria-label="FOB采购数据来源"><option value="auto">自动：优先FOB资料，其次新采购数据</option><option value="fob">FOB数据（粘贴更新）</option><option value="standard">系统新采购数据</option></select></label><p>FOB报价按国内采购成本计算，无需选择目的国家和渠道。</p><p v-if="error" class="error" role="alert">{{ error }}</p><footer><button type="button" @click="showQuery=false">取消</button><button type="submit" class="primary" :disabled="busy || !sku.trim()">{{ busy ? '查询中…' : '查询并生成阶梯报价' }}</button></footer></form></div></Teleport>
</template>

<style scoped>
.fob-card{margin-top:10px;border:1px solid #dce7ee;border-radius:12px;background:#fff;padding:12px 14px;color:#233442}.fob-card header,.fob-query header{display:flex;justify-content:space-between;align-items:center;gap:16px}.fob-card h2,.fob-query h2{font-size:16px;margin:0}.fob-card h2 i{display:inline-grid;place-items:center;width:27px;height:27px;margin-right:8px;background:#e5f2ff;color:#368fe0;border-radius:6px;font-size:10px;font-style:normal}.fob-card header p{font-size:12px;color:#73828d}.source{display:inline-block;margin-left:10px;padding:3px 7px;border-radius:8px;background:#edf6f0;color:#25855d;font-size:10px}.fob-card button,.fob-query button{border:1px solid #d3dfe7;background:#fff;color:#33536b;border-radius:6px;padding:9px 12px;cursor:pointer;font:inherit;font-size:12px}.fob-parameters{display:flex;flex-wrap:wrap;gap:24px;padding:8px 0;font-size:12px;color:#71818e}.fob-parameters b{color:#334a60}.fob-table-wrap{overflow:auto}.fob-card table{border-collapse:collapse;width:100%;min-width:850px;font-size:12px;text-align:center}.fob-card th,.fob-card td{padding:8px 9px;border:1px solid #dce5eb}.fob-card th{background:#f0f6f8;font-size:11px}.fob-card .quote-price{background:#fff8dc;font-weight:800}.selected td{box-shadow:inset 0 2px #a6d3ed,inset 0 -2px #a6d3ed}.help,.notice,.fob-rules p{font-size:12px;line-height:1.8;color:#71818e}.notice{color:#9a640e}.quantity-quote{display:flex;gap:18px;flex-wrap:wrap;align-items:center;padding:8px 0;border-top:1px solid #e6edf1;font-size:12px}.quantity-quote input{width:100px;margin-left:8px;padding:9px;border:1px solid #d3dfe7;border-radius:6px}.quantity-quote span{color:#7e8e99}.quantity-quote button{margin-left:auto}.final-quote{display:flex;flex-wrap:wrap;gap:24px;align-items:center;padding:10px 12px;background:#fff8df;border-radius:8px;font-size:13px}.final-quote strong{color:#66451c;font-size:18px}.error{color:#b73126;font-size:12px;line-height:1.8}.updated{color:#82939e;font-size:11px}.fob-rules{margin-top:8px;font-size:12px}.fob-rules summary{cursor:pointer;color:#8e651d}.fob-query-mask{position:fixed;inset:0;z-index:1100;background:#13263b80;display:grid;place-items:center;padding:20px}.fob-query{box-sizing:border-box;width:min(480px,100%);padding:24px;background:#fff;border-radius:12px;color:#233442;box-shadow:0 20px 70px #14253830}.fob-query header{margin-bottom:24px}.fob-query label{display:grid;gap:8px;margin:16px 0;font-size:12px}.fob-query input,.fob-query select{box-sizing:border-box;width:100%;padding:10px;border:1px solid #ccdbe5;border-radius:6px;font:inherit}.fob-query p{font-size:12px;color:#7b8b98;line-height:1.8}.fob-query .error{color:#b73126}.fob-query footer{display:flex;justify-content:flex-end;gap:10px;margin-top:22px}.fob-query .primary{background:#f39017;color:#fff;border-color:#f39017}.fob-card button:disabled,.fob-query button:disabled{opacity:.5;cursor:not-allowed}@media(max-width:600px){.fob-card{padding:14px}.fob-card header{align-items:flex-start;flex-direction:column}.final-quote{gap:14px}.quantity-quote button{margin-left:0}}
</style>
