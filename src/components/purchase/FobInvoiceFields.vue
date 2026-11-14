<script setup lang="ts">
import { watch } from 'vue'
const props=defineProps<{record:{invoiceType?:string;purchasePriceBasis?:string;taxIncludedPriceCny?:number|null;taxPoint?:number|null;invoiceVerificationNote?:string;fobInvoiceRatioConfirmed?:boolean;fobInvoiceConfirmedFingerprint?:string};priceSignature:string;showInvoice?:boolean;showIncluded?:boolean}>()
watch(()=>props.priceSignature,()=>{props.record.fobInvoiceRatioConfirmed=false;delete props.record.fobInvoiceConfirmedFingerprint})
function included(event:Event){const value=(event.target as HTMLInputElement).value;props.record.taxIncludedPriceCny=value===''?null:Number(value)}
</script>
<template>
  <div class="invoice-fields">
    <label v-if="showInvoice">票据类型<input v-model="record.invoiceType" aria-label="FOB票据类型" placeholder="专票、专票13、普票、无票等"></label>
    <label v-if="showIncluded">起步含票价（元/件）<input :value="record.taxIncludedPriceCny" aria-label="FOB起步含票价" type="number" min="0" step="0.01" @input="included"></label>
    <label v-if="showInvoice">实际票点（8%填0.08）<input :value="record.taxPoint" aria-label="FOB票点" type="number" min="0" max="1" step="0.001" @input="record.taxPoint=($event.target as HTMLInputElement).value===''?null:Number(($event.target as HTMLInputElement).value)"></label>
    <label>FOB 阶梯价格口径<select v-model="record.purchasePriceBasis" aria-label="采购阶梯价格口径"><option value="">原始阶梯价（历史未标记）</option><option value="quoted">原始阶梯价，按实际票点计算</option><option value="tax_included">全部阶梯已含票，不再换算</option></select></label>
    <label class="wide">采购核实说明（选填）<textarea v-model="record.invoiceVerificationNote" aria-label="含票阶梯核实说明" maxlength="2000" placeholder="填写供应商票据类型及票点核实情况" /></label>
    <small class="wide">FOB前两类报价按各档原价 ×（1＋实际票点）计算；已含票阶梯不重复加票点。票类型或票点缺失时默认10点并持续提醒，补齐当前数据源SKU后重新查询才解除。已填含票价与实际票点冲突时须核实；专票名称中的数字不会代填票点。</small>
  </div>
</template>
<style scoped>
.invoice-fields{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px;padding:14px;border:1px solid #e4d9c4;border-radius:8px;background:#fffaf1;margin:12px 0;color:#334a60}.invoice-fields label{display:grid;gap:6px;font-size:12px}.wide{grid-column:1/-1}input,select,textarea{box-sizing:border-box;width:100%;padding:9px;border:1px solid #cdd9e2;border-radius:5px;background:white;color:#334a60;font:inherit}.confirmation{display:flex!important;align-items:center}.confirmation input{width:auto}small{color:#6b7987;line-height:1.7}@media(max-width:700px){.invoice-fields{grid-template-columns:1fr}}
</style>
