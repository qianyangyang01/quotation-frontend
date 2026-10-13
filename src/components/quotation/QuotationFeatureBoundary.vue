<script setup lang="ts">
import { onErrorCaptured, ref } from 'vue'
import { isPageResourceError } from '@/router/navigationRecovery'

withDefaults(defineProps<{ label: string; active?: boolean }>(), { active: true })
const emit = defineEmits<{ close: [] }>()
const failed = ref(false)
const resourceFailed = ref(false)
function reloadPage() { window.location.reload() }
onErrorCaptured(error => {
  resourceFailed.value = isPageResourceError(error)
  failed.value = true
  return false
})
</script>

<template>
  <section v-if="failed" v-show="active" class="feature-error" role="alert">
    <template v-if="resourceFailed">
      <p>{{ label }}加载失败，可能是页面版本已更新或网络中断。请先保留未保存的内容，再刷新整个页面；仅关闭后重新打开无法恢复旧版文件。</p>
      <button type="button" @click="reloadPage">刷新页面</button>
    </template>
    <p v-else>{{ label }}暂时无法使用，请关闭后重新打开。其他报价功能可继续使用。</p>
    <button type="button" @click="emit('close')">关闭当前功能</button>
  </section>
  <slot v-else />
</template>

<style scoped>
.feature-error{padding:16px;margin:12px 0;border:1px solid #edc8b9;border-radius:8px;background:#fff5ef;color:#963e26;font-size:14px}.feature-error p{margin:0 0 12px}.feature-error button{padding:8px 12px;border:1px solid #d9b9ab;border-radius:6px;background:#fff;color:inherit;cursor:pointer}
</style>
