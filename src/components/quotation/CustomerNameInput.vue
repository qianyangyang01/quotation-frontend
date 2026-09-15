<script setup lang="ts">
import { computed, ref } from 'vue'
import type { CustomerOperationFee } from '@/data/customerOperationFees'
const props = defineProps<{ modelValue: string; selectedId: string; customers: CustomerOperationFee[] }>()
const emit = defineEmits<{ 'update:modelValue': [value: string]; select: [id: string] }>()
const open = ref(false)
const active = ref(-1)
const matches = computed(() => props.customers.filter(row => row.enabled && (!props.modelValue || props.selectedId || row.name.toLocaleLowerCase().includes(props.modelValue.trim().toLocaleLowerCase()))))
function input(value: string) { emit('update:modelValue', value); open.value = true; active.value = -1 }
function choose(id: string) { emit('select', id); open.value = false }
function keydown(event: KeyboardEvent) {
  if (event.isComposing) return
  if (event.key === 'Escape') { open.value = false; return }
  if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
    event.preventDefault()
    active.value = open.value ? Math.max(0, Math.min(matches.value.length - 1, active.value + (event.key === 'ArrowDown' ? 1 : -1))) : 0
    open.value = true
  }
  if (event.key === 'Enter' && open.value && matches.value[active.value]) { event.preventDefault(); choose(matches.value[active.value]!.id) }
}
</script>
<template>
  <div class="customer-picker" @focusout="open = false">
    <div class="entry"><input :value="modelValue" role="combobox" aria-label="客户名称" :aria-expanded="open" aria-controls="finance-customer-options" autocomplete="off" maxlength="120" placeholder="输入客户名称，或从下拉列表选择" @input="input(($event.target as HTMLInputElement).value)" @keydown="keydown"><button type="button" aria-label="展开客户列表" @mousedown.prevent @click="open = !open">⌄</button></div>
    <div v-if="open" id="finance-customer-options" role="listbox" class="options">
      <button v-for="(row,index) in matches" :key="row.id" type="button" role="option" :aria-selected="row.id === selectedId" :class="{ active: index === active }" @mousedown.prevent @click="choose(row.id)">{{ row.name }}<small>${{ row.feeUsd.toFixed(2) }}/单</small></button>
      <p v-if="!matches.length">暂无匹配客户，可直接使用输入的名称</p>
    </div>
    <button v-if="selectedId" class="manual" type="button" @click="input(modelValue); open = false">改为手动填写（不加操作费）</button>
  </div>
</template>
<style scoped>
.customer-picker{position:relative;min-width:0}.entry{display:flex;border:1px solid #d9e0e6;border-radius:7px;background:white}.entry:focus-within{border-color:#f3a12d}.entry input{min-width:0;width:100%;height:36px;border:0;background:transparent;padding:0 9px;outline:0;font:inherit}.entry>button{border:0;border-left:1px solid #edf0f3;background:transparent;width:32px;cursor:pointer}.options{position:absolute;z-index:30;top:38px;left:0;right:0;max-height:230px;overflow:auto;border:1px solid #d9e0e6;border-radius:7px;background:white;box-shadow:0 5px 20px #0002}.options button{display:flex;justify-content:space-between;gap:12px;width:100%;padding:11px;border:0;background:white;text-align:left;cursor:pointer}.options button:hover,.options button.active{background:#fff4e5}.options p{padding:10px}.manual{border:0;background:none;color:#975d0d;padding:5px 0;cursor:pointer;font-size:10px}
</style>
