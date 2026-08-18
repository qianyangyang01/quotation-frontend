<script setup lang="ts">
import type { QuotationProduct } from './types'
const props = defineProps<{ product: QuotationProduct; chargeWeight: number; productCost: number; domesticFreight: number; purchaseTierLabel: string }>()
const emit = defineEmits<{ weightChange: [] }>()

const grams = (weightKg: number) => Math.ceil((Number.isFinite(weightKg) ? weightKg : 0) * 1000)
const actualWeight = () => props.product.weightSource === 'manual' ? props.product.manualWeight : props.product.netWeight * Math.max(1, props.product.quantity)
const volumeWeight = () => props.product.volumetricEnabled
  ? props.product.packageLengthCm * props.product.packageWidthCm * props.product.packageHeightCm * Math.max(1, props.product.quantity) / 8000
  : 0

function updateManualWeight(event: Event) {
  const input = event.target as HTMLInputElement
  const inputGrams = Number(input.value)
  props.product.manualWeight = Number.isFinite(inputGrams) ? Math.max(0, Math.ceil(inputGrams)) / 1000 : 0
  emit('weightChange')
}
function updateDimension(field: 'packageLengthCm' | 'packageWidthCm' | 'packageHeightCm', event: Event) {
  const value = Number((event.target as HTMLInputElement).value)
  props.product[field] = Number.isFinite(value) ? Math.max(0, value) : 0
  emit('weightChange')
}
</script>

<template>
  <section class="module-card">
    <header><div><i>02</i><span><b>成本与重量</b><small>采购数据与计费重量</small></span></div><em>采购资料</em></header>
    <div class="fields">
      <label>采购单价（CNY）<input v-model.number="product.purchase" disabled><small class="tier-match">已匹配：{{ purchaseTierLabel }}</small></label>
      <label>商品重量（g）<input :value="grams(product.netWeight)" disabled></label>
      <label>重量来源<select v-model="product.weightSource" @change="$emit('weightChange')"><option value="purchase">使用采购表重量</option><option value="manual">业务员指定重量</option></select></label>
      <label v-if="product.weightSource==='manual'">指定重量（g）<input :value="grams(product.manualWeight)" type="number" min="0" step="1" inputmode="numeric" @input="updateManualWeight"></label>
      <label>国内运费/件（CNY）<input :value="product.purchaseFreightPerUnit.toFixed(2)" disabled></label>
    </div>
    <div class="volumetric-switch">
      <label><input v-model="product.volumetricEnabled" type="checkbox" @change="$emit('weightChange')"><span><b>计抛商品</b><small>体积重与实际重量比较，按较大值计费</small></span></label>
      <em>默认计抛系数 8000；渠道已维护系数时以渠道为准</em>
    </div>
    <div v-if="product.volumetricEnabled" class="dimensions">
      <label>长（cm）<input :value="product.packageLengthCm || ''" type="number" min="0" step="0.1" placeholder="请输入长度" @input="updateDimension('packageLengthCm',$event)"></label>
      <label>宽（cm）<input :value="product.packageWidthCm || ''" type="number" min="0" step="0.1" placeholder="请输入宽度" @input="updateDimension('packageWidthCm',$event)"></label>
      <label>高（cm）<input :value="product.packageHeightCm || ''" type="number" min="0" step="0.1" placeholder="请输入高度" @input="updateDimension('packageHeightCm',$event)"></label>
      <p><span>默认体积重</span><b>{{ grams(volumeWeight()) }} g</b><small>长×宽×高÷8000</small></p>
    </div>
    <div class="highlights"><p><span>商品采购成本</span><b>¥{{ productCost.toFixed(2) }}</b><small>{{ purchaseTierLabel }}计算</small></p><p><span>计费重量</span><b>{{ grams(chargeWeight) }} g</b><small v-if="product.volumetricEnabled">实际 {{ grams(actualWeight()) }} g / 体积 {{ grams(volumeWeight()) }} g，取较大值</small><small v-else>{{ product.weightSource==='manual' ? '业务指定' : '采购重量自动计算' }}</small></p><p><span>国内运费成本</span><b>¥{{ domesticFreight.toFixed(2) }}</b><small>10件运费平摊</small></p></div>
  </section>
</template>

<style scoped>
.module-card{padding:20px;border:1px solid #e3e8ed;border-radius:12px;background:#fff;box-shadow:0 7px 22px rgba(17,24,39,.04)}header{display:flex;align-items:center;justify-content:space-between;margin-bottom:18px}header>div{display:flex;align-items:center;gap:10px}header i{width:29px;height:29px;display:grid;place-items:center;border-radius:8px;background:#eef2f5;color:#6c7883;font-size:10px;font-style:normal}header span{display:grid;gap:2px}header b{font-size:16px}header small{color:#909aa4;font-size:10px}header em{padding:4px 8px;border-radius:12px;background:#eef6f8;color:#547981;font-size:9px;font-style:normal}.fields{display:grid;grid-template-columns:1fr 1fr;gap:14px 16px}.fields label,.dimensions label{display:grid;gap:6px;color:#697580;font-size:11px}.fields input,.fields select,.dimensions input{width:100%;height:40px;box-sizing:border-box;border:1px solid #dbe2e7;border-radius:7px;background:#fff;padding:0 10px;color:#26323b;font-size:13px}.fields input:disabled{background:#f4f6f8;color:#687580}.volumetric-switch{display:flex;align-items:center;justify-content:space-between;gap:18px;margin-top:18px;padding:12px 14px;border:1px solid #f2c778;border-radius:8px;background:#fffaf0}.volumetric-switch label{display:flex;align-items:center;gap:10px}.volumetric-switch label>input{width:16px;height:16px;accent-color:#ff9700}.volumetric-switch label>span{display:grid;gap:2px}.volumetric-switch label b{font-size:12px}.volumetric-switch label small,.volumetric-switch>em{color:#8b744e;font-size:9px;font-style:normal}.dimensions{display:grid;grid-template-columns:repeat(3,1fr) minmax(145px,.8fr);gap:12px;margin-top:12px;padding:14px;border-radius:8px;background:#f7f9fb}.dimensions p{display:grid;gap:3px;margin:0;padding:8px 12px;border-left:1px solid #dde4e9}.dimensions p span,.dimensions p small{color:#7d8993;font-size:9px}.dimensions p b{font-size:14px}.highlights{display:grid;grid-template-columns:repeat(3,1fr);gap:10px;margin-top:18px}.highlights p{display:grid;gap:4px;margin:0;padding:13px;border-radius:8px;background:#f6f8fa}.highlights span{color:#7d8993;font-size:10px}.highlights b{font-size:15px}.highlights small{color:#a0a9b1;font-size:9px}@media(max-width:800px){.dimensions{grid-template-columns:1fr 1fr}.dimensions p{border-left:0}}@media(max-width:620px){.fields,.highlights,.dimensions{grid-template-columns:1fr}.volumetric-switch{align-items:flex-start;flex-direction:column}.dimensions p{padding-inline:0}}
.tier-match{color:#b76800!important;font-size:9px!important;font-weight:800}
</style>
