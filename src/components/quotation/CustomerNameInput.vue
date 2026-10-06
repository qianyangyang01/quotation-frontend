<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { currentAuthUser } from '@/data/authStore'
import { operationFeesLabel, type CustomerOperationFee } from '@/data/customerOperationFees'
import type { PersonalQuotationCustomer } from '@/data/personalQuotationCustomers'
import { usePersonalQuotationCustomers } from '@/composables/usePersonalQuotationCustomers'
import PersonalCustomerDialog from './PersonalCustomerDialog.vue'
const props = defineProps<{ modelValue: string; selectedId: string; customers: CustomerOperationFee[] }>()
const emit = defineEmits<{ 'update:modelValue': [value: string]; select: [id: string] }>()
const personal=usePersonalQuotationCustomers()
const open=ref(false), active=ref(-1), query=ref(''), managing=ref(false), personalName=ref('')
const companyMatches=computed(()=>props.customers.filter(row=>row.enabled&&row.name.toLocaleLowerCase().includes(query.value.trim().toLocaleLowerCase())))
const personalMatches=computed(()=>personal.rows.value.filter(row=>row.name.toLocaleLowerCase().includes(query.value.trim().toLocaleLowerCase())))
const choices=computed(()=>[...personalMatches.value.map(row=>({kind:'personal' as const,row})),...companyMatches.value.map(row=>({kind:'company' as const,row}))])
const sourceLabel=computed(()=>props.selectedId?'公司计费':personalName.value&&personalName.value===props.modelValue?'个人':'')
watch(()=>[currentAuthUser.value.id,currentAuthUser.value.account],()=>{close();managing.value=false;personalName.value=''})
watch(()=>[props.selectedId,props.modelValue],()=>{if(props.selectedId||props.modelValue!==personalName.value)personalName.value=''})
function close(){open.value=false;query.value='';active.value=-1}
function focusout(event:FocusEvent){if(event.currentTarget instanceof HTMLElement&&event.relatedTarget instanceof Node&&event.currentTarget.contains(event.relatedTarget))return;close()}
function browse(){const show=!open.value||Boolean(query.value);close();open.value=show;if(show)void personal.load()}
function input(value:string){query.value=value;personalName.value='';emit('update:modelValue',value);if(!open.value)void personal.load();open.value=true;active.value=-1}
function chooseCompany(id:string){personalName.value='';emit('select',id);close()}
function choosePersonal(row:PersonalQuotationCustomer){personalName.value=row.name;emit('update:modelValue',row.name);close();void personal.markUsed(row)}
function manage(){close();managing.value=true}
function keydown(event:KeyboardEvent){
  if(event.isComposing)return
  if(event.key==='Escape'){close();return}
  if(event.key==='ArrowDown'||event.key==='ArrowUp'){
    event.preventDefault();if(!open.value){query.value='';void personal.load()}
    active.value=open.value?Math.max(0,Math.min(choices.value.length-1,active.value+(event.key==='ArrowDown'?1:-1))):0;open.value=true
  }
  if(event.key==='Enter'&&open.value&&choices.value[active.value]){event.preventDefault();const option=choices.value[active.value]!;if(option.kind==='personal')choosePersonal(option.row);else chooseCompany(option.row.id)}
}
</script>
<template>
  <div class="customer-picker" @focusout="focusout">
    <div class="entry"><input :value="modelValue" role="combobox" aria-label="客户名称" :aria-expanded="open" aria-controls="finance-customer-options" autocomplete="off" maxlength="120" placeholder="输入客户名称，或从下拉列表选择" @input="input(($event.target as HTMLInputElement).value)" @keydown="keydown"><span v-if="sourceLabel" class="source" :class="{company:!!selectedId}">{{ sourceLabel }}</span><button type="button" aria-label="展开客户列表" @mousedown.prevent @click="browse">⌄</button></div>
    <div v-if="open" class="options">
      <div id="finance-customer-options" role="listbox" aria-label="客户列表">
        <div class="group-title">我的常用客户 <small>仅本人可见 · 不关联操作费</small></div>
        <p v-if="personal.loading.value" role="status">正在加载个人客户…</p>
        <p v-if="!personal.loading.value&&!personalMatches.length&&!personal.error.value" class="empty">{{ query?'暂无匹配的个人客户':'尚未添加个人客户' }}</p>
        <button v-for="(row,index) in personalMatches" :key="'personal-'+row.id" type="button" role="option" :aria-selected="!selectedId&&personalName===row.name" :class="{active:index===active}" @mousedown.prevent @click="choosePersonal(row)"><span class="option-name">{{ row.name }} <em>个人 · 仅名称</em></span></button>
        <div class="group-title company-title">公司客户（含操作费）</div>
        <button v-for="(row,index) in companyMatches" :key="'company-'+row.id" type="button" role="option" :aria-selected="row.id===selectedId" :class="{active:personalMatches.length+index===active}" @mousedown.prevent @click="chooseCompany(row.id)"><span class="option-name">{{ row.name }}<em class="company">公司计费</em></span><small>{{ operationFeesLabel(row) }}</small></button>
        <p v-if="!companyMatches.length" class="empty">暂无匹配公司客户，可直接使用输入的名称</p>
      </div>
      <p v-if="personal.error.value" role="alert" class="error">{{ personal.error.value }} <button type="button" @mousedown.prevent @click="personal.load">重试</button></p>
      <button type="button" class="manage-personal" @mousedown.prevent @click="manage">＋ 个人客户 · 添加与管理</button>
    </div>
    <button v-if="selectedId" class="manual" type="button" @click="input(modelValue); close()">改为手动填写（不加操作费）</button>
    <PersonalCustomerDialog v-if="managing" :initial-name="selectedId?'':modelValue" :company-names="customers.map(row=>row.name)" @close="managing=false" @changed="personal.load" @select="choosePersonal" />
  </div>
</template>
<style scoped>
.customer-picker{position:relative;min-width:0}.entry{display:flex;align-items:center;border:1px solid #d9e0e6;border-radius:7px;background:white}.entry:focus-within{border-color:#f3a12d}.entry input{min-width:0;width:100%;height:36px;border:0;background:transparent;padding:0 9px;outline:0;font:inherit}.entry>button{align-self:stretch;flex:0 0 32px;border:0;border-left:1px solid #edf0f3;background:transparent;cursor:pointer}.options{position:absolute;z-index:30;top:38px;left:0;min-width:100%;width:min(340px,calc(100vw - 64px));max-height:400px;overflow:auto;border:1px solid #d9e0e6;border-radius:7px;background:white;box-shadow:0 5px 20px #0002;font-size:12px}.options button[role=option]{display:flex;align-items:flex-start;flex-direction:column;gap:4px;width:100%;padding:10px 12px;border:0;background:white;text-align:left;cursor:pointer;font:inherit;color:#25313b}.options button[role=option]:hover,.options button.active{background:#fff4e5}.options p{padding:8px 12px;margin:0;font-size:11px}.manual{border:0;background:none;color:#975d0d;padding:5px 0;cursor:pointer;font-size:10px}.options small{white-space:normal;line-height:1.6}.group-title{padding:12px 12px 6px;font-weight:700;color:#27333d}.group-title small{display:block;font-weight:400;color:#778591;font-size:10px}.company-title{border-top:1px solid #edf0f3;margin-top:6px}.option-name{display:flex;align-items:center;gap:10px;overflow-wrap:anywhere}.option-name em,.source{font-size:10px;font-style:normal;background:#eef4fa;color:#527490;border-radius:4px;padding:3px 5px;white-space:nowrap;font-weight:400}.source{margin-right:5px}.option-name .company,.source.company{color:#ab6607;background:#fff1dc}.empty{color:#778591}.options .error{color:#b9382e}.options .error button{border:0;background:none;color:inherit;text-decoration:underline;cursor:pointer}.manage-personal{position:sticky;bottom:0;width:100%;padding:11px;border:0;border-top:1px solid #e5eaf0;background:#fff;color:#2375c5;text-align:center;font:inherit;cursor:pointer}.options button:focus-visible,.manual:focus-visible{outline:2px solid #f3a12d;outline-offset:-2px}
</style>
