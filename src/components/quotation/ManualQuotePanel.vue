<script setup lang="ts">
defineProps<{ shippingOnly: boolean; cost: string; weight: string; error: string; busy: boolean }>()
defineEmits<{ 'update:cost': [value: string]; 'update:weight': [value: string]; query: [] }>()
</script>

<template>
  <section class="manual-quote-panel" data-validation-field="manualPricing">
    <header><b><i>02</i> {{ shippingOnly ? '发货重量' : '成本与重量' }}</b><span>手动录入</span></header>
    <div class="manual-inputs">
      <label v-if="!shippingOnly"><span>成本价格（元 / 件）<em>必填</em></span><input aria-label="成本价格" inputmode="decimal" :value="cost" placeholder="输入总成本" @input="$emit('update:cost', ($event.target as HTMLInputElement).value)" @keyup.enter="$emit('query')"><small>已包含的费用不再重复叠加</small></label>
      <label><span>重量（g / 件）<em>必填</em></span><input aria-label="手填重量" inputmode="decimal" :value="weight" placeholder="输入重量" @input="$emit('update:weight', ($event.target as HTMLInputElement).value)" @keyup.enter="$emit('query')"><small>按填写重量参与渠道计算，不增加包材重量</small></label>
      <span v-if="shippingOnly" class="manual-note">客户自备商品 · 无需填写成本</span>
      <button type="button" :disabled="busy" @click="$emit('query')">{{ busy ? '正在查询…' : shippingOnly ? '查询代发报价' : '查询试算' }}</button>
    </div>
    <p v-if="error" role="alert">{{ error }}</p>
  </section>
</template>

<style scoped>
.manual-quote-panel{border:1px solid #cfe1f5;border-radius:9px;background:white;overflow:hidden;margin:12px 0}.manual-quote-panel header{display:flex;align-items:center;gap:18px;background:#edf6ff;padding:10px 14px}.manual-quote-panel header b{display:flex;align-items:center;gap:10px;font-size:15px}.manual-quote-panel header i{background:#66a8eb;color:white;border-radius:6px;padding:6px;font-style:normal;font-size:12px}.manual-quote-panel header>span,small{color:#7c8997;font-size:11px}.manual-inputs{display:flex;align-items:center;gap:24px;padding:16px}.manual-inputs label{display:grid;gap:7px;width:280px;font-size:12px}.manual-inputs em{font-size:10px;color:#cf7900;margin-left:10px;font-style:normal}.manual-inputs input{height:36px;border:1px solid #d5dfe8;border-radius:6px;padding:0 10px;font:inherit}.manual-inputs button{margin-left:auto;background:#f48a00;border:0;border-radius:6px;color:white;font-weight:700;min-width:130px;height:38px;cursor:pointer}.manual-inputs button:disabled{opacity:.55}.manual-note{color:#367fca;background:#edf6ff;padding:9px;border-radius:6px;font-size:12px}.manual-quote-panel p{color:#c43f31;margin:0 16px 12px;font-size:12px}@media(max-width:800px){.manual-inputs{flex-wrap:wrap}.manual-inputs label{width:100%}.manual-inputs button{width:100%}}
</style>
