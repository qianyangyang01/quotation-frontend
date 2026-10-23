<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import { appUpdate, refreshUpdatedApplication } from '@/services/appUpdate'
const state = appUpdate.state
const confirmingRefresh = ref(false)
const refreshButton = ref<HTMLButtonElement>(), cancelButton = ref<HTMLButtonElement>()
async function beginRefresh() {
  if (state.activeOperations > 0) return
  confirmingRefresh.value = true
  await nextTick()
  cancelButton.value?.focus()
}
async function cancelRefresh() {
  confirmingRefresh.value = false
  await nextTick()
  refreshButton.value?.focus()
}
function confirmRefresh() {
  if (!confirmingRefresh.value || state.activeOperations > 0) return
  refreshUpdatedApplication(true)
}
watch(() => state.updateRequired || state.unavailable, visible => { if (!visible) confirmingRefresh.value = false })
</script>

<template>
  <aside v-if="state.updateRequired || state.unavailable" class="app-update-notice" role="alert" aria-live="polite">
    <div>
      <strong>{{ state.updateRequired ? state.reason === 'exchange-rate' ? '财务汇率已更新，请刷新后重新报价' : '系统已更新，请刷新后继续操作' : '暂时无法确认系统版本' }}</strong>
      <p v-if="state.updateRequired">新报价、审核及资料修改已暂停。请先保留未保存内容；刷新后重新查询，再使用新的报价结果。</p>
      <p v-else>版本检查暂时不可用，系统会自动重试，正常操作和当前输入不受影响。</p>
      <small v-if="state.activeOperations">正在提交或上传，请等待完成后刷新。</small>
      <small v-else>刷新会清除未保存的输入；已保存的历史报价不受影响。</small>
      <p v-if="confirmingRefresh" id="app-update-refresh-confirmation">请确认需要的内容已经复制保留，或当前草稿已经保存。确认刷新后，尚未保存的输入将被清除。</p>
    </div>
    <div class="update-actions">
      <button v-if="!state.updateRequired" type="button" :disabled="state.checking" @click="appUpdate.check()">{{ state.checking ? '正在检查…' : '重新检查' }}</button>
      <button v-if="!confirmingRefresh" ref="refreshButton" type="button" :disabled="state.activeOperations > 0" @click="beginRefresh">保留内容后刷新</button>
      <div v-else class="update-actions" role="group" aria-label="确认刷新页面" aria-describedby="app-update-refresh-confirmation" @keydown.esc="cancelRefresh">
        <button ref="cancelButton" type="button" @click="cancelRefresh">取消</button>
        <button type="button" :disabled="state.activeOperations > 0" @click="confirmRefresh">已保留内容，确认刷新</button>
      </div>
    </div>
  </aside>
</template>

<style scoped>
.app-update-notice{position:sticky;top:0;z-index:10000;display:flex;align-items:center;justify-content:space-between;gap:20px;padding:14px 24px;border-bottom:1px solid #e8b065;background:#fff4df;color:#754000;font:14px/1.6 "Microsoft YaHei",sans-serif;box-shadow:0 2px 8px #0000000d}.app-update-notice strong{font-size:15px}.app-update-notice p{margin:3px 0}.app-update-notice small{font-size:12px}.update-actions{display:flex;flex-shrink:0;gap:10px}.update-actions button{padding:9px 14px;border:1px solid #ad6400;border-radius:7px;background:#fff;color:#754000;font:inherit;cursor:pointer}.update-actions button:disabled{opacity:.55;cursor:wait}.update-actions button:focus-visible{outline:3px solid #ad6400;outline-offset:3px}@media(max-width:1000px){.app-update-notice{align-items:flex-start;flex-direction:column;gap:12px;padding:12px 16px}.update-actions{flex-wrap:wrap}}
</style>
