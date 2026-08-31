<script setup lang="ts">
import type { QuotationProduct } from './types'
const props = defineProps<{ product: QuotationProduct; chargeWeight: number; domesticFreight: number; purchaseTierLabel: string }>()
const emit = defineEmits<{ weightChange: [] }>()

const grams = (weightKg: number) => Math.ceil((Number.isFinite(weightKg) ? weightKg : 0) * 1000)
const actualWeight = () => props.product.weightSource === 'manual' ? props.product.manualWeight : props.product.netWeight * Math.max(1, props.product.quantity)
const volumeWeight = () => props.product.volumetricEnabled
  ? props.product.packageLengthCm * props.product.packageWidthCm * props.product.packageHeightCm * Math.max(1, props.product.quantity) / Math.max(1, props.product.volumeDivisor || 8000)
  : 0

function updateManualWeight(event: Event) {
  const input = event.target as HTMLInputElement
  const inputGrams = Number(input.value)
  props.product.manualWeight = Number.isFinite(inputGrams) ? Math.max(0, Math.ceil(inputGrams)) / 1000 : 0
  emit('weightChange')
}
function updateDivisor(event: Event) {
  const value = Number((event.target as HTMLInputElement).value)
  props.product.volumeDivisor = Number.isFinite(value) ? Math.max(1, Math.round(value)) : 8000
  emit('weightChange')
}
function purchasePricingLabel() {
  const product = props.product
  if (!product.purchaseInvoiceTaxApplied) return `${props.purchaseTierLabel}原价 ¥${product.purchaseBaseUnitPrice.toFixed(2)} · 旧草稿沿用原规则`
  if (product.purchaseInvoiceRatePercent <= 0 && Math.abs(product.purchase - product.purchaseBaseUnitPrice) > 0.001) return `票点暂无数据 · 使用含票价 ¥${product.purchase.toFixed(2)}`
  if (product.purchaseInvoiceRatePercent <= 0) return `${props.purchaseTierLabel}原价 ¥${product.purchaseBaseUnitPrice.toFixed(2)} · 未配置采购票率`
  return `${props.purchaseTierLabel}原价 ¥${product.purchaseBaseUnitPrice.toFixed(2)} × ${(1 + product.purchaseInvoiceRatePercent / 100).toFixed(2)}（${product.purchaseInvoiceType}）= 计入成本 ¥${product.purchase.toFixed(2)}`
}
</script>

<template>
  <section class="module-card">
    <header><div><i>02</i><span><b>成本与重量</b><small>采购数据与计费重量</small></span></div><em>采购资料</em></header>
    <div class="fields">
      <label>计入成本单价（CNY）<input v-model.number="product.purchase" disabled><small class="tier-match">{{ purchasePricingLabel() }}</small></label>
      <label>商品重量（g）<input :value="grams(product.netWeight)" disabled></label>
      <label>重量来源<select v-model="product.weightSource" @change="$emit('weightChange')"><option value="purchase">使用采购表重量</option><option value="manual">业务员指定重量</option></select></label>
      <label v-if="product.weightSource==='manual'">指定重量（g）<input :value="grams(product.manualWeight)" type="number" min="0" step="1" inputmode="numeric" @input="updateManualWeight"></label>
      <label>国内运费/件（CNY）<input :value="product.purchaseFreightPerUnit.toFixed(2)" disabled></label>
    </div>
    <div v-if="product.volumetricEnabled" class="volumetric-card">
      <div class="volumetric-title">
        <span class="check">✓</span>
        <span><b>计抛产品</b><small>采购资料已维护完整长宽高，系统自动参与计抛</small></span>
        <em>采购尺寸</em>
      </div>
      <div class="dimensions">
        <label>长（cm）<input :value="product.packageLengthCm" disabled></label>
        <label>宽（cm）<input :value="product.packageWidthCm" disabled></label>
        <label>高（cm）<input :value="product.packageHeightCm" disabled></label>
        <label>计抛除数<input :value="product.volumeDivisor" type="number" min="1" step="100" inputmode="numeric" @input="updateDivisor"></label>
      </div>
      <div class="weight-comparison">
        <p><span>实际重量</span><b>{{ grams(actualWeight()) }} g</b><small>商品重量 × 数量</small></p>
        <i>对比</i>
        <p><span>体积重公式</span><b>{{ product.packageLengthCm }} × {{ product.packageWidthCm }} × {{ product.packageHeightCm }} × {{ Math.max(1, product.quantity) }}</b><small>÷ {{ product.volumeDivisor || 8000 }}</small></p>
        <p><span>体积重量</span><b>{{ grams(volumeWeight()) }} g</b><small>采购尺寸自动计算</small></p>
        <p class="charge-result"><span>最终计费重量</span><b>{{ grams(chargeWeight) }} g</b><small>实重与体积重取最大值</small></p>
      </div>
    </div>
    <div class="highlights"><p><span>计费重量</span><b>{{ grams(chargeWeight) }} g</b><small v-if="product.volumetricEnabled">实际 {{ grams(actualWeight()) }} g / 体积 {{ grams(volumeWeight()) }} g，取较大值</small><small v-else>{{ product.weightSource==='manual' ? '业务指定' : '采购重量自动计算' }}</small></p><p><span>国内运费成本</span><b>¥{{ domesticFreight.toFixed(2) }}</b><small>10件运费平摊，不计采购票点</small></p></div>
  </section>
</template>

<style scoped>
.module-card{padding:20px;border:1px solid #e3e8ed;border-radius:12px;background:#fff;box-shadow:0 7px 22px rgba(17,24,39,.04)}header{display:flex;align-items:center;justify-content:space-between;margin-bottom:18px}header>div{display:flex;align-items:center;gap:10px}header i{width:29px;height:29px;display:grid;place-items:center;border-radius:8px;background:#eef2f5;color:#6c7883;font-size:10px;font-style:normal}header span{display:grid;gap:2px}header b{font-size:16px}header small{color:#909aa4;font-size:10px}header em{padding:4px 8px;border-radius:12px;background:#eef6f8;color:#547981;font-size:9px;font-style:normal}.fields{display:grid;grid-template-columns:1fr 1fr;gap:14px 16px}.fields label,.dimensions label{display:grid;gap:6px;color:#697580;font-size:11px}.fields input,.fields select,.dimensions input{width:100%;height:40px;box-sizing:border-box;border:1px solid #dbe2e7;border-radius:7px;background:#fff;padding:0 10px;color:#26323b;font-size:13px}.fields input:disabled,.dimensions input:disabled{background:#f4f6f8;color:#687580}.volumetric-card{margin-top:18px;padding:14px;border:1px solid #f2c778;border-radius:9px;background:#fffaf0}.volumetric-title{display:flex;align-items:center;gap:10px}.volumetric-title .check{width:18px;height:18px;display:grid;place-items:center;border-radius:5px;background:#ff9700;color:#fff;font-size:12px;font-weight:900}.volumetric-title>span:nth-child(2){display:grid;gap:2px}.volumetric-title b{font-size:12px}.volumetric-title small{color:#8b744e;font-size:9px}.volumetric-title em{margin-left:auto;padding:4px 8px;border-radius:12px;background:#fff1d7;color:#a76800;font-size:9px;font-style:normal}.dimensions{display:grid;grid-template-columns:repeat(4,1fr);gap:12px;margin-top:12px}.weight-comparison{display:grid;grid-template-columns:1fr auto 1.4fr 1fr 1.2fr;align-items:stretch;gap:10px;margin-top:12px}.weight-comparison p{display:grid;gap:3px;margin:0;padding:11px 12px;border:1px solid #eadfc9;border-radius:8px;background:#fff}.weight-comparison span,.weight-comparison small{color:#8c7957;font-size:9px}.weight-comparison b{font-size:13px}.weight-comparison i{align-self:center;color:#a38d67;font-size:9px;font-style:normal}.weight-comparison .charge-result{border-color:#f2b54b;background:#fff3dc}.charge-result b{color:#d97900;font-size:16px}.highlights{display:grid;grid-template-columns:repeat(2,1fr);gap:10px;margin-top:18px}.highlights p{display:grid;gap:4px;margin:0;padding:13px;border-radius:8px;background:#f6f8fa}.highlights span{color:#7d8993;font-size:10px}.highlights b{font-size:15px}.highlights small{color:#a0a9b1;font-size:9px}@media(max-width:900px){.weight-comparison{grid-template-columns:1fr 1fr}.weight-comparison i{display:none}.dimensions{grid-template-columns:1fr 1fr}}@media(max-width:620px){.fields,.highlights,.dimensions,.weight-comparison{grid-template-columns:1fr}.volumetric-title{align-items:flex-start}.volumetric-title em{white-space:nowrap}}
.tier-match{color:#b76800!important;font-size:9px!important;font-weight:800}
</style>
