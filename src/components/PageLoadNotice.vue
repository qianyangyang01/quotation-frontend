<script setup lang="ts">
import { failedNavigation, reloadFailedPage } from '@/router/navigationRecovery'
</script>

<template>
  <aside v-if="failedNavigation" class="page-load-notice" role="alert">
    <span>页面加载失败，可能是版本已更新或网络中断。请先保留未保存的内容，再重新加载。</span>
    <button type="button" @click="reloadFailedPage">重新加载页面</button>
  </aside>
</template>

<style scoped>
.page-load-notice{position:sticky;top:0;z-index:101;display:flex;align-items:center;justify-content:space-between;gap:16px;padding:14px 24px;border-bottom:1px solid #f5bc73;background:#fff3df;color:#8c4300;font-size:14px}.page-load-notice button{flex-shrink:0;padding:8px 12px;border:1px solid #df7a00;border-radius:6px;background:#fff;color:#8c4300;cursor:pointer}@media(max-width:620px){.page-load-notice{align-items:flex-start;flex-direction:column;padding:12px 16px}}
</style>
