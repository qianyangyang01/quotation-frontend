<script setup lang="ts">
import type { QuotationProduct } from './types'
defineProps<{ product: QuotationProduct; rules: string[]; grade: string; coefficient: number; exchangeRate: number }>()
defineEmits<{ calculate: []; ruleChange: [] }>()
function usdFromCny(value: number, rate: number) { return rate > 0 ? (value / rate).toFixed(2) : '0.00' }
</script>

<template>
  <section class="module-card logistics-card">
    <header><div><i>03</i><span><b>国家与物流</b><small>按财务策略匹配可用方案</small></span></div><em>{{ product.country }}</em></header>
    <div class="route"><span>当前采用路线</span><strong><b>{{ product.country || '未选国家' }}</b><i>→</i><b>{{ product.channel || '未选物流商' }}</b></strong></div>
    <div class="fields">
      <label class="wide">运费规则<select v-model="product.rule" @change="$emit('ruleChange')"><option v-if="!rules.length" value="">当前条件无可用规则</option><option v-for="rule in rules" :key="rule">{{ rule }}</option></select></label>
      <label>客户条件<input :value="`${grade}级客户 · 系数 ${coefficient.toFixed(2)}`" disabled></label>
    </div>
    <footer><div class="freight-summary"><span>国际运费</span><label class="freight-cny"><em>CNY ¥</em><input v-model.number="product.freight" disabled type="number"></label><strong>USD ${{ usdFromCny(product.freight, exchangeRate) }}</strong><button @click="$emit('calculate')">运费试算</button></div></footer>
  </section>
</template>

<style scoped>
.module-card{padding:20px;border:1px solid #e3e8ed;border-radius:12px;background:#fff;box-shadow:0 7px 22px rgba(17,24,39,.04)}header{display:flex;align-items:center;justify-content:space-between;margin-bottom:16px}header>div{display:flex;align-items:center;gap:10px}header i{width:29px;height:29px;display:grid;place-items:center;border-radius:8px;background:#eef2f5;color:#6c7883;font-size:10px;font-style:normal}header span{display:grid;gap:2px}header b{font-size:16px}header small{color:#909aa4;font-size:10px}header>em{padding:5px 10px;border-radius:12px;background:#fff1dc;color:#a45e00;font-size:10px;font-style:normal}.route{display:grid;gap:10px;margin-bottom:16px;padding:16px 17px;border:1px solid #e8edf1;border-radius:9px;background:#fafbfc}.route>span{color:#7b8792;font-size:11px;font-weight:700}.route strong{display:grid;grid-template-columns:minmax(0,1fr) auto minmax(0,1fr);align-items:center;width:100%;color:#17232d;font-size:22px;font-weight:900;line-height:1.3}.route strong b{min-width:0;font-size:inherit;white-space:nowrap}.route strong b:last-child{text-align:right}.route strong i{margin:0 18px;color:#f39915;font-size:20px;font-style:normal}.fields{display:grid;grid-template-columns:1fr 1fr;gap:14px 16px}.fields .wide{grid-column:1/-1}.fields label{display:grid;gap:6px;color:#697580;font-size:11px}.field-hint{color:#a26a23;font-size:9px}.fields input,.fields select{width:100%;height:40px;box-sizing:border-box;border:1px solid #dbe2e7;border-radius:7px;background:#fff;padding:0 10px;color:#26323b;font-size:13px}.fields input:disabled{background:#f4f6f8;color:#687580}.logistics-card footer{display:flex;align-items:stretch;flex-direction:column;gap:11px;margin-top:18px;padding-top:14px;border-top:1px dashed #dfe5e9;color:#697580;font-size:11px}.logistics-card footer>label{white-space:nowrap}.logistics-card footer>div{display:flex;align-items:center;justify-content:flex-end;gap:7px}.freight-summary>span{margin-right:auto;white-space:nowrap}.freight-cny{display:flex;align-items:center;border:1px solid #dbe2e7;border-radius:6px;background:#fff;overflow:hidden}.freight-cny em{padding-left:8px;color:#7c8791;font-size:9px;font-style:normal;font-weight:800;white-space:nowrap}.logistics-card footer .freight-cny input{width:62px;height:34px;border:0;padding:0 8px}.freight-summary>strong{min-width:72px;padding:7px 8px;border-radius:6px;background:#eaf5fb;color:#086a94;font-size:11px;white-space:nowrap}.logistics-card footer button{height:34px;border:0;border-radius:6px;background:#17232d;color:#fff;font-size:11px}@media(max-width:620px){.fields{grid-template-columns:1fr}.fields .wide{grid-column:auto}.route strong{font-size:18px}.route strong i{margin:0 9px;font-size:17px}.freight-summary{flex-wrap:wrap}}
</style>
