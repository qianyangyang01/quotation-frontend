<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { usePersonalQuotationCustomers } from '@/composables/usePersonalQuotationCustomers'
import type { PersonalQuotationCustomer } from '@/data/personalQuotationCustomers'
const props=defineProps<{initialName:string;companyNames:string[]}>()
const emit=defineEmits<{close:[];changed:[];select:[row:PersonalQuotationCustomer]}>()
const customers=usePersonalQuotationCustomers()
const screen=ref<'manage'|'edit'|'remove'>('manage'), name=ref(props.initialName), search=ref('')
const target=ref<PersonalQuotationCustomer|null>(null), localError=ref(''), dialog=ref<HTMLDialogElement>(), nameInput=ref<HTMLInputElement>()
const rows=computed(()=>customers.rows.value.filter(row=>row.name.toLocaleLowerCase().includes(search.value.trim().toLocaleLowerCase())))
const sameCompany=computed(()=>props.companyNames.some(company=>company.trim().toLocaleLowerCase()===name.value.trim().toLocaleLowerCase()))
const duplicate=computed(()=>customers.rows.value.find(row=>row.id!==target.value?.id&&row.name.toLocaleLowerCase()===name.value.trim().toLocaleLowerCase()))
const title=computed(()=>screen.value==='manage'?'我的常用客户':screen.value==='edit'?'修改客户名称':screen.value==='remove'?'移出常用客户':'添加个人客户')
let previousFocus:HTMLElement|null=null, requestName='', requestKey=''
onMounted(()=>{previousFocus=document.activeElement as HTMLElement;dialog.value?.showModal();void customers.load();nameInput.value?.focus()})
onUnmounted(()=>{dialog.value?.close();previousFocus?.focus()})
function close(){if(!customers.busy.value)emit('close')}
async function show(value:'manage'|'edit'|'remove',row?:PersonalQuotationCustomer){screen.value=value;target.value=row||null;name.value=row?.name||'';localError.value='';customers.error.value='';await nextTick();nameInput.value?.focus()}
function cancel(){if(customers.busy.value)return;if(screen.value!=='manage')void show('manage');else close()}
async function submit(){
  if(customers.busy.value)return
  const value=name.value.trim();localError.value=''
  if(screen.value==='remove'&&target.value){if(await customers.remove(target.value)){emit('changed');await show('manage')}return}
  if(!value||value.length>120){localError.value='请填写客户名称，最多120字';return}
  if(duplicate.value){localError.value='已存在同名个人客户，请直接使用已有客户';return}
  if(value!==requestName||!requestKey){requestName=value;requestKey='personal-customer:'+crypto.randomUUID()}
  const saved=screen.value==='edit'&&target.value?await customers.rename(target.value,value):await customers.add(value,requestKey)
  if(!saved)return
  requestKey='';search.value='';emit('changed')
  await show('manage')
}
function selectRow(row:PersonalQuotationCustomer){if(!customers.busy.value){emit('select',row);emit('close')}}
function selectExisting(){if(duplicate.value)selectRow(duplicate.value)}
async function refresh(){
  await customers.load()
  if(!target.value||customers.error.value)return
  const latest=customers.rows.value.find(row=>row.id===target.value?.id)
  if(latest)target.value=latest
  else {await show('manage');localError.value='该客户已被移出，名单已刷新'}
}
</script>
<template>
  <Teleport to="body"><dialog ref="dialog" class="personal-customer-dialog" aria-labelledby="personal-customer-title" @cancel.prevent="cancel">
    <header><h2 id="personal-customer-title">{{ title }}</h2><button type="button" aria-label="关闭个人客户窗口" :disabled="customers.busy.value" @click="close">×</button></header>
    <template v-if="screen==='manage'">
      <p class="muted">仅本人可见；只管理名称，不设置费用</p>
      <form class="manage-tools" @submit.prevent="submit"><input ref="nameInput" v-model="name" aria-label="新增个人客户名称" placeholder="输入新客户名称" maxlength="120" :disabled="customers.busy.value"><button type="submit" :disabled="customers.busy.value||!name.trim()||!!duplicate">{{customers.busy.value?'正在保存…':'＋ 添加'}}</button></form>
      <p v-if="sameCompany" class="same-company">存在同名公司客户，个人名单不关联其操作费。</p>
      <p v-if="duplicate" class="same-company">已有同名客户。<button type="button" @click="selectExisting">选用已有客户</button></p>
      <input v-model="search" type="search" class="search-customers" aria-label="搜索个人客户" placeholder="搜索已添加客户">
      <p class="muted">共 {{ rows.length }} 位客户</p>
      <div class="customer-table"><table><thead><tr><th>客户名称</th><th>操作</th></tr></thead><tbody><tr v-for="row in rows" :key="row.id"><td>{{ row.name }}</td><td><button type="button" :disabled="customers.busy.value" @click="selectRow(row)">选用</button><button type="button" :disabled="customers.busy.value" @click="show('edit',row)">修改名称</button><button type="button" class="danger-text" :disabled="customers.busy.value" @click="show('remove',row)">移出</button></td></tr></tbody></table></div>
      <p v-if="!rows.length&&!customers.loading.value&&!customers.error.value" class="muted">{{ search?'没有匹配的个人客户':'还没有常用客户，请在上方输入名称添加' }}</p>
      <p class="info">添加只保存名单；点击“选用”带入当前报价。移出不影响历史报价。</p>
      <footer><button type="button" :disabled="customers.busy.value" @click="close">关闭</button></footer>
    </template>
    <form v-else @submit.prevent="submit">
      <template v-if="screen==='remove'"><p>确认将“<b>{{ target?.name }}</b>”移出个人常用名单？</p><p class="info">不会删除或修改历史报价，当前已填写的客户名称也会保留。</p></template>
      <template v-else>
        <label for="personal-customer-name">客户名称 <em>必填</em></label><input id="personal-customer-name" ref="nameInput" v-model="name" required maxlength="120" autocomplete="off" :disabled="customers.busy.value">
        <p class="muted">仅保存名称，供本人下次下拉选择</p>
        <p class="info">个人客户不关联公司操作费；客户等级、物流等报价规则照常计算。</p>
        <p v-if="sameCompany" class="same-company">存在同名公司客户；保存为个人客户不会关联其操作费，需要计费请在下拉中明确选择公司客户。</p>
        <p v-if="duplicate" class="same-company">已有同名个人客户。<button type="button" :disabled="customers.busy.value" @click="selectExisting">使用已有客户</button></p>
        <p v-if="screen==='edit'" class="muted">修改名单名称不自动替换当前输入，也不修改历史报价。</p>
      </template>
      <footer><button type="button" :disabled="customers.busy.value" @click="cancel">取消</button><button type="submit" class="primary" :class="{danger:screen==='remove'}" :disabled="customers.busy.value||(screen!=='remove'&&(!name.trim()||!!duplicate))">{{ customers.busy.value?'正在保存…':screen==='remove'?'确认移出':'保存' }}</button></footer>
    </form>
    <p v-if="customers.loading.value" role="status">正在加载个人客户…</p>
    <p v-if="localError||customers.error.value" role="alert" class="error">{{ localError||customers.error.value }} <button v-if="customers.error.value" type="button" :disabled="customers.busy.value" @click="refresh">刷新名单</button></p>
  </dialog></Teleport>
</template>
<style scoped>
.personal-customer-dialog{width:min(540px,calc(100vw - 32px));box-sizing:border-box;max-height:85vh;overflow:auto;padding:22px;border:1px solid #dce3ea;border-radius:10px;background:#fff;color:#25313b;box-shadow:0 18px 60px #18263133;font-size:13px}.personal-customer-dialog::backdrop{background:#17212b70}header{display:flex;justify-content:space-between;align-items:center;gap:12px}h2{font-size:18px;margin:0}header button{border:0;font-size:22px;padding:0 5px}button,input{font:inherit;box-sizing:border-box}button{padding:8px 12px;border:1px solid #dce3ea;border-radius:6px;background:#fff;cursor:pointer;color:#34434f}button:disabled{opacity:.5;cursor:not-allowed}input{height:38px;width:100%;border:1px solid #dce3ea;border-radius:6px;padding:8px 10px}.search-customers{margin-top:14px}.manage-tools{display:flex;gap:10px}.manage-tools input{min-width:0}.manage-tools button{white-space:nowrap;border-color:#f3a12d;color:#b96800}.muted{font-size:12px;color:#778591;line-height:1.6}.info{padding:12px;border-radius:7px;background:#f2f7fc;color:#59728b;line-height:1.7}.same-company{color:#975d0d;line-height:1.6;font-size:12px}label{display:block;margin:24px 0 8px}em{font-style:normal;color:#b96800;font-size:11px}.customer-table{max-height:330px;overflow:auto;border:1px solid #e4e9ee;border-radius:6px}table{width:100%;border-collapse:collapse;text-align:left;table-layout:fixed}th,td{padding:10px;border-bottom:1px solid #edf0f3;overflow-wrap:anywhere}th{background:#f5f7fa;font-size:12px}td button{padding:5px;border:0;color:#2375c5}.danger-text{color:#bd3c32}footer{display:flex;justify-content:flex-end;gap:12px;margin-top:20px}.primary{background:#f39800;border-color:#f39800;color:white}.primary.danger{background:#bd3c32;border-color:#bd3c32}.error{color:#bd3c32;line-height:1.6}button:focus-visible,input:focus-visible{outline:2px solid #f3a12d;outline-offset:2px}
</style>
