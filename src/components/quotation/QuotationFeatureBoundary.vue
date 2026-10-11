<script setup lang="ts">
import { onErrorCaptured, ref } from 'vue'

withDefaults(defineProps<{ label: string; active?: boolean }>(), { active: true })
const emit = defineEmits<{ close: [] }>()
const failed = ref(false)
onErrorCaptured(() => {
  failed.value = true
  return false
})
</script>

<template>
  <section v-if="failed" v-show="active" class="feature-error" role="alert">
    <p>{{ label }}暂时无法使用，请关闭后重新打开。其他报价功能可继续使用。</p>
    <button type="button" @click="emit('close')">关闭当前功能</button>
  </section>
  <slot v-else />
</template>

<style scoped>
.feature-error{padding:16px;margin:12px 0;border:1px solid #edc8b9;border-radius:8px;background:#fff5ef;color:#963e26;font-size:14px}.feature-error p{margin:0 0 12px}.feature-error button{padding:8px 12px;border:1px solid #d9b9ab;border-radius:6px;background:#fff;color:inherit;cursor:pointer}
</style>
