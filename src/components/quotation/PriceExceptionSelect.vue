<script setup lang="ts">
import { priceExceptionOptions, type PriceExceptionType } from '@/data/priceExceptions'
defineProps<{ modelValue: PriceExceptionType | ''; disabled?: boolean; label?: string }>()
defineEmits<{ 'update:modelValue': [value: PriceExceptionType | ''] }>()
</script>
<template>
  <label class="exception-type">异常类型 <span class="required">*</span>
    <select :value="modelValue" :disabled="disabled" :aria-label="label || '异常类型'" required @change="$emit('update:modelValue', ($event.target as HTMLSelectElement).value as PriceExceptionType | '')">
      <option disabled value="">请选择异常类型</option>
      <option v-for="option in priceExceptionOptions" :key="option.value" :value="option.value">{{ option.label }}</option>
    </select>
    <small v-if="modelValue">{{ priceExceptionOptions.find(option => option.value === modelValue)?.hint }}</small>
  </label>
</template>
<style scoped>
.exception-type{display:block!important;font-size:14px;color:#25364b}.required{color:#bc322b}.exception-type select{display:block;box-sizing:border-box;width:100%;margin-top:8px;padding:11px 12px;border:1px solid #ccd7e2;border-radius:6px;background:#fff;color:#25364b;font:inherit}.exception-type select:focus-visible{outline:2px solid #3782d6;outline-offset:2px}.exception-type small{display:block;margin-top:6px;color:#718193;font-size:12px;font-weight:normal}
</style>
