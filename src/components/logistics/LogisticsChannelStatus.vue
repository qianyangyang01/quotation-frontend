<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { Channel } from '@/data/logisticsRebuild'
import { setLogisticsChannelStatus, type LogisticsChannelRecord } from '@/data/logisticsRepository'

const props = defineProps<{ channel: Channel; disabled?: boolean }>()
const emit = defineEmits<{ changed: [channel: LogisticsChannelRecord]; refresh: []; pending: [value: boolean] }>()
const confirming = ref(false), saving = ref(false), error = ref('')
const enabled = computed(() => props.channel.enabled !== false)
watch(() => [props.channel.id, props.channel._version], () => { confirming.value = false; error.value = '' })
async function confirm() {
  if (saving.value || props.disabled || props.channel.archived) return
  if (!Number.isSafeInteger(props.channel._version)) { error.value = '渠道资料不完整，请刷新后重试'; return }
  const channel = { id: props.channel.id, _version: props.channel._version!, currentVersionId: props.channel.currentVersionId }
  const desired = !enabled.value
  saving.value = true; error.value = ''; emit('pending', true)
  try {
    const result = await setLogisticsChannelStatus(channel, desired)
    emit('changed', result)
    confirming.value = false
  } catch (e) {
    if (props.channel.id === channel.id) error.value = e instanceof Error ? e.message : '更新失败，请刷新后重试'
    emit('refresh')
  } finally { saving.value = false; emit('pending', false) }
}
</script>

<template>
  <div class="channel-status-action">
    <span class="channel-enabled" :class="{ off: !enabled }">{{ channel.archived ? '已归档' : enabled ? '已启用' : '已禁用' }}</span>
    <button type="button" :disabled="disabled || channel.archived || saving" :aria-label="`${enabled ? '禁用' : '启用'}渠道 ${channel.name}`" @click="confirming = true">{{ saving ? '更新中…' : enabled ? '禁用' : '启用' }}</button>
    <small v-if="error" role="alert">{{ error }}</small>
    <Teleport to="body">
      <div v-if="confirming" class="channel-status-mask">
        <section role="dialog" aria-modal="true" :aria-label="`${enabled ? '禁用' : '启用'}渠道确认`">
          <h3>{{ enabled ? '禁用' : '启用' }}渠道：{{ channel.name }}</h3>
          <p v-if="enabled">禁用后，该渠道停止参与新报价。已有模板和草稿保留渠道引用，重新计价时提示不可用；已保存的历史报价不变。</p>
          <p v-else>启用后，该渠道按当前已发布价格和财务授权参与报价；未发布价格或未授权的渠道仍不可报价。</p>
          <p>价格版本、财务授权和历史记录均保留。</p>
          <small v-if="error" role="alert">{{ error }}</small>
          <footer><button type="button" :disabled="saving" @click="confirming = false">取消</button><button type="button" :disabled="saving || disabled" @click="confirm">{{ saving ? '更新中…' : enabled ? '确认禁用' : '确认启用' }}</button></footer>
        </section>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.channel-status-action{display:flex;align-items:center;gap:8px;flex-wrap:wrap}.channel-enabled{font-size:11px;color:#168153;white-space:nowrap}.channel-enabled.off{color:#8b6570}.channel-status-action>button{border:0;background:none;color:#cb6b00;cursor:pointer;font-size:12px}.channel-status-action small,.channel-status-mask small{color:#b63333;overflow-wrap:anywhere}.channel-status-mask{position:fixed;inset:0;z-index:250;background:#16212b66;display:grid;place-items:center;padding:20px}.channel-status-mask section{width:min(480px,100%);box-sizing:border-box;border-radius:12px;padding:24px;background:white;color:#172735;box-shadow:0 12px 40px #0002}.channel-status-mask h3{margin:0 0 16px}.channel-status-mask p{font-size:14px;line-height:1.7}.channel-status-mask footer{display:flex;justify-content:flex-end;gap:12px;margin-top:20px}.channel-status-mask button{padding:9px 18px;border:1px solid #d8e0e7;border-radius:6px;background:white;cursor:pointer}.channel-status-mask footer button:last-child{background:#ff9400;border-color:#ff9400;color:#172735}button:disabled{opacity:.5;cursor:not-allowed}
</style>
