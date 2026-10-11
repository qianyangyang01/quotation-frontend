<script setup lang="ts">
import { ref } from 'vue'
import { loadFobRevision } from '@/services/fobCorrections'
import type { FobQuotationSnapshot } from '@/services/fobQuotationRecords'
import type { FobSheetRow } from '@/services/fobQuotation'
const priceRows=(item:FobQuotationSnapshot)=>(item.displayMode==='tiers'?(item.ranges??[]):[item.current]).filter((r):r is FobSheetRow=>!!r)
const props=defineProps<{id:string;revision:number}>()
const selected=ref(0),snapshot=ref<FobQuotationSnapshot>(),loading=ref(false),error=ref('')
async function load(){loading.value=true;error.value='';snapshot.value=undefined;try{snapshot.value=await loadFobRevision(props.id,selected.value)}catch(e){error.value=(e as Error).message}finally{loading.value=false}}
</script>
<template><details class="revisions"><summary>原报价及修订记录（当前修订 {{ revision }}）</summary><p>每次修正另存，原始报价不覆盖。历史版本仅供核对，不作为当前审核通过的报价单。</p><label>查看版本 <select v-model.number="selected" :disabled="loading"><option v-for="i in revision+1" :key="i" :value="i-1">{{ i===1?'原始报价':'修订 '+(i-1) }}</option></select></label><button :disabled="loading" @click="load">{{ loading?'读取中…':'查看保存快照' }}</button><p v-if="error" role="alert">{{ error }}</p><div v-if="snapshot" class="scroll"><table><thead><tr><th>SKU</th><th>数量范围</th><th>报关 / 不报关 $</th></tr></thead><tbody><template v-for="(item,index) in snapshot.items" :key="index"><tr v-for="(row,i) in priceRows(item)" :key="i"><td>{{ item.product.sku }}</td><td>{{ row.minQty }}{{ row.maxQty===row.minQty?'':row.maxQty==null?'+':'–'+row.maxQty }}{{ row.unit }}</td><td>{{ row.declaredUsd }} / {{ row.undeclaredUsd }}</td></tr></template></tbody></table></div></details></template>
<style scoped>.revisions{margin:16px 0;padding:12px;border:1px solid #dce5ed;border-radius:6px;font-size:13px}summary{cursor:pointer}button,select{padding:7px;margin:8px;border:1px solid #ccd9e5;border-radius:5px;background:white}.scroll{overflow:auto}table{width:100%;border-collapse:collapse}td,th{padding:10px;border:1px solid #dce5ed;text-align:left}</style>
