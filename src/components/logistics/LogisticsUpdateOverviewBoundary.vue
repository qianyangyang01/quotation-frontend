<script setup lang="ts">
import { onErrorCaptured, ref } from 'vue'

const failed = ref(false)
const emit = defineEmits<{ close: [] }>()
onErrorCaptured(() => { failed.value = true; return false })
</script>

<template>
  <aside v-if="failed" class="overview-failure" role="alert">
    <span>更新概览暂时无法显示，可继续查看价格趋势和使用报价功能。</span>
    <button type="button" @click="failed = false">重试概览</button>
    <button type="button" @click="emit('close')">关闭提示</button>
  </aside>
  <slot v-else />
</template>

<style scoped>
.overview-failure { position: fixed; right: 24px; bottom: 24px; z-index: 1200; max-width: calc(100vw - 48px); display: flex; flex-wrap: wrap; align-items: center; gap: 12px; padding: 18px; color: #725017; background: #fff9ed; border: 1px solid #ebd4a8; border-radius: 12px; box-shadow: 0 6px 24px #23384c18; }
.overview-failure button { border: 1px solid #dbc29a; border-radius: 6px; padding: 6px 10px; background: white; color: #725017; cursor: pointer; }
</style>
