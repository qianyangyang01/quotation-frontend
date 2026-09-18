<script setup lang="ts">
defineProps<{ modelValue: string; error?: string }>()
defineEmits<{ 'update:modelValue': [value: string] }>()
</script>
<template>
  <div class="special-packaging">
    <span>普通包材：每件商品每 50g 加 1g，不足 50g 按 50g 计算</span>
    <label>特殊包装（g／票）<input :value="modelValue" type="text" inputmode="numeric" maxlength="6" aria-label="特殊包装克重" :aria-invalid="Boolean(error)" placeholder="0" @input="$emit('update:modelValue', ($event.target as HTMLInputElement).value)"></label>
    <small>整票只加一次，不随件数或套数增加，也不再计算普通包材。</small>
    <small v-if="error" class="error" role="alert">{{ error }}</small>
  </div>
</template>
<style scoped>
.special-packaging{display:flex;align-items:center;flex-wrap:wrap;gap:10px;margin-top:14px;padding:12px;border:1px solid #e3e8ed;border-radius:8px;background:#fafbfc;font-size:11px;color:#697580}.special-packaging label{display:flex;align-items:center;gap:8px;color:#26323b}.special-packaging input{width:100px;height:34px;box-sizing:border-box;border:1px solid #dbe2e7;border-radius:6px;padding:0 8px}.special-packaging small{line-height:1.5}.special-packaging .error{color:#b42318;width:100%}.special-packaging input[aria-invalid=true]{border-color:#b42318}
</style>
