<script setup lang="ts">
import { ref } from 'vue'
import { loadCustomerOperationSettings, saveCustomerOperationSettings, type CustomerOperationSettings } from '@/data/customerOperationFees'
const emit = defineEmits<{ saved: [] }>()
const settings = ref<CustomerOperationSettings>(loadCustomerOperationSettings())
const saving = ref(false), message = ref(''), failed = ref(false)
function add() { settings.value.customers.push({ id: crypto.randomUUID(), name: '', feeUsd: 0, enabled: true }) }
async function save() {
  if (saving.value) return
  saving.value = true; message.value = ''; failed.value = false
  try { settings.value = await saveCustomerOperationSettings({ customers: settings.value.customers.map(row => ({ ...row })) }); message.value = '客户操作费已保存'; emit('saved') }
  catch (error) { failed.value = true; message.value = error instanceof Error ? error.message : '保存失败，请重试' }
  finally { saving.value = false }
}
</script>
<template>
  <section class="customer-fees">
    <header><div><h3>客户操作费</h3><p>公司内部人工操作费用，按美元／单收取，不参与客户等级系数计算。</p></div><button :disabled="saving" @click="save">{{ saving ? '保存中…' : '保存客户操作费' }}</button></header>
    <p>业务员从下拉列表选择客户后，原报价增加该操作费；每个数量列只加一次。手动填写客户名称不加费。最终报价沿用0.05美元向上取整规则。</p>
    <p v-if="message" role="status" :class="{ error:failed }">{{ message }}</p>
    <fieldset :disabled="saving"><table><thead><tr><th>客户名称</th><th>操作费（USD／单）</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-for="(row,index) in settings.customers" :key="row.id"><td><input v-model="row.name" :aria-label="`客户名称${index+1}`" maxlength="120" placeholder="输入客户名称"></td><td><input v-model.number="row.feeUsd" :aria-label="`操作费${index+1}`" type="number" min="0" max="1000000" step="0.01"></td><td><label><input v-model="row.enabled" type="checkbox"> 启用</label></td><td><button class="delete" @click="settings.customers.splice(index,1)">删除</button></td></tr><tr v-if="!settings.customers.length"><td colspan="4">暂未设置客户操作费</td></tr></tbody></table><button class="add" :disabled="settings.customers.length >= 1000" @click="add">＋ 添加客户</button></fieldset>
    <small>修改仅用于之后重新计算的报价，历史报价保留当时的操作费。停用或删除的客户不能继续使用旧设置保存新报价。</small>
  </section>
</template>
<style scoped>
.customer-fees{padding:22px;background:white;border:1px solid #e0e6ec;border-radius:12px}.customer-fees header{display:flex;justify-content:space-between;align-items:center;gap:20px}.customer-fees h3{margin:0}.customer-fees p,.customer-fees small{color:#718096;font-size:12px;line-height:1.7}.customer-fees button{border:1px solid #ffa000;border-radius:7px;background:#ff9900;color:#15222e;padding:10px 16px;cursor:pointer}.customer-fees fieldset{border:0;padding:0;min-width:0}.customer-fees table{width:100%;border-collapse:collapse;margin:16px 0}.customer-fees th,.customer-fees td{padding:12px;text-align:left;border-bottom:1px solid #e6ebf0}.customer-fees input:not([type=checkbox]){width:100%;box-sizing:border-box;border:1px solid #d9e0e6;border-radius:6px;padding:10px}.customer-fees .delete{background:white;border-color:#e6ebf0;color:#c34d3c}.customer-fees .add{background:white;margin-bottom:16px}.customer-fees .error{color:#c43f31}.customer-fees button:disabled{opacity:.5;cursor:default}
</style>
