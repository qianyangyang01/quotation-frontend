<script setup lang="ts">
import { computed, ref } from 'vue'

export type ChannelTag = { key: string; name: string; ruleName: string }

const props = defineProps<{ provider: string; channels: ChannelTag[] }>()
const expanded = ref(false)
const visibleChannels = computed(() => expanded.value ? props.channels : props.channels.slice(0, 4))
</script>

<template>
  <article class="channel-group">
    <header>
      <div><span class="provider-dot"></span><strong>{{ provider }}</strong><em>{{ channels.length }}</em></div>
      <button v-if="channels.length > 4" type="button" @click="expanded = !expanded">{{ expanded ? '收起 ↑' : `展开更多 ↓` }}</button>
    </header>
    <div class="channel-tags">
      <span v-for="channel in visibleChannels" :key="channel.key" :title="channel.ruleName">{{ channel.name }}</span>
      <button v-if="!expanded && channels.length > 4" type="button" @click="expanded = true">+{{ channels.length - 4 }}</button>
    </div>
  </article>
</template>

<style scoped>
.channel-group{display:grid;grid-template-columns:27% minmax(0,73%);gap:10px;padding:10px 12px;border:1px solid #e7ebef;border-radius:8px;background:#fff}.channel-group+article{margin-top:8px}.channel-group header{display:grid;align-content:start;gap:7px;margin:0}.channel-group header>div{display:flex;align-items:center;gap:7px;min-width:0}.provider-dot{width:7px;height:7px;flex:0 0 7px;border-radius:50%;background:#ff9900;box-shadow:0 0 0 3px #fff0d8}.channel-group strong{overflow:hidden;color:#27323b;font-size:11px;text-overflow:ellipsis;white-space:nowrap}.channel-group em{padding:2px 6px;border-radius:999px;background:#f0f3f5;color:#7a858e;font-size:9px;font-style:normal}.channel-group header button{width:max-content;padding:0;border:0;background:none;color:#b86600;font-size:9px;white-space:nowrap}.channel-tags{display:flex;align-content:flex-start;flex-wrap:wrap;gap:6px}.channel-tags span,.channel-tags button{max-width:100%;padding:5px 9px;border:0;border-radius:12px;background:#f5f7fa;color:#52606b;font-size:10px;line-height:1.25;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.channel-tags button{background:#fff0d7;color:#a45a00;font-weight:800;cursor:pointer}@media(max-width:620px){.channel-group{grid-template-columns:1fr}.channel-group header{display:flex;align-items:center;justify-content:space-between}}
</style>
