<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import { appUpdate } from '@/services/appUpdate'
const state = appUpdate.state
const dialog = ref<HTMLDialogElement>()
watch(() => state.notesOpen, async open => {
  await nextTick()
  if (open && !dialog.value?.open) dialog.value?.showModal()
  else if (!open && dialog.value?.open) dialog.value.close()
})
function close() { appUpdate.closeReleaseNotes() }
</script>

<template>
  <dialog ref="dialog" class="release-notes" aria-labelledby="release-notes-title" @cancel="close" @close="close" @click="event => { if (event.target === dialog) close() }">
    <header><div><span>版本更新 · {{ state.release.publishedOn }}</span><h2 id="release-notes-title">{{ state.release.title }}</h2><small>{{ state.release.version }}{{ state.updateAvailable ? ' · 更新后可使用' : ' · 当前版本' }}</small></div><button type="button" aria-label="关闭更新说明" @click="close">×</button></header>
    <main><article v-for="feature in state.release.features" :key="feature.title"><h3>{{ feature.title }}</h3><p>{{ feature.description }}</p><small>功能位置：{{ feature.location }}</small></article></main>
    <footer><span>更新页面不会修改已保存的历史报价。</span><button type="button" @click="close">我知道了</button></footer>
  </dialog>
</template>

<style scoped>
.release-notes{box-sizing:border-box;width:min(600px,calc(100vw - 32px));max-height:85vh;padding:0;border:1px solid #e1e7eb;border-radius:14px;box-shadow:0 22px 80px #14243144;color:#20323f;font:14px/1.8 "Microsoft YaHei",sans-serif}.release-notes::backdrop{background:#162c3d66}.release-notes header{display:flex;justify-content:space-between;align-items:flex-start;padding:24px 28px;border-bottom:1px solid #e5eaee}.release-notes header span{color:#98590c;font-size:13px}.release-notes h2{font-size:22px;margin:6px 0}.release-notes small{color:#73828e;font-size:12px}.release-notes header button{font-size:24px;background:none;border:0;color:#74838e;cursor:pointer}.release-notes main{padding:0 28px}.release-notes article{padding:18px 0;border-bottom:1px solid #edf0f2}.release-notes h3{font-size:16px;margin:0 0 8px}.release-notes p{margin:0 0 10px;color:#596d7d}.release-notes footer{padding:20px 28px;display:flex;align-items:center;justify-content:space-between;gap:20px}.release-notes footer span{font-size:12px;color:#73828e}.release-notes footer button{flex-shrink:0;border:1px solid #e18b22;border-radius:7px;background:#ff9810;color:#30210f;padding:9px 18px;font:14px "Microsoft YaHei",sans-serif;cursor:pointer}.release-notes button:focus-visible{outline:3px solid #eea347;outline-offset:3px}
</style>
