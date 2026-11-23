<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { currentAuthUser } from '@/data/authStore'
import { appUpdate } from '@/services/appUpdate'

const state = appUpdate.state
const root = ref<HTMLElement>(), trigger = ref<HTMLButtonElement>()
const open = ref(false), seen = ref(false)
const seenKey = computed(() => `milano.release-read:${currentAuthUser.value.account}:${state.release.version}`)
watch(seenKey, key => {
  try { seen.value = localStorage.getItem(key) === 'yes' } catch { seen.value = false }
  // Announce once without a backdrop, navigation, or taking focus from editing.
  open.value = !!currentAuthUser.value.account && !seen.value
}, { immediate: true })
function acknowledge() {
  seen.value = true
  try { localStorage.setItem(seenKey.value, 'yes') } catch { /* Storage is optional for this display preference. */ }
}
function close() { open.value = false; acknowledge() }
function toggle() { if (open.value) close(); else { open.value = true; acknowledge() } }
function details() { close(); appUpdate.showReleaseNotes() }
function outside(event: PointerEvent) { if (open.value && root.value && !root.value.contains(event.target as Node)) close() }
function escape(event: KeyboardEvent) {
  if (event.key !== 'Escape' || !open.value) return
  const focusInside = root.value?.contains(document.activeElement)
  close()
  if (focusInside) trigger.value?.focus()
}
onMounted(() => { document.addEventListener('pointerdown', outside); document.addEventListener('keydown', escape) })
onUnmounted(() => { document.removeEventListener('pointerdown', outside); document.removeEventListener('keydown', escape) })
</script>

<template>
  <div ref="root" class="release-menu">
    <button ref="trigger" type="button" class="release-tool" :aria-expanded="open" aria-controls="release-summary" @click="toggle">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" aria-hidden="true"><path d="M12 3v3m0 12v3M3 12h3m12 0h3M5.6 5.6l2.1 2.1m8.6 8.6 2.1 2.1m0-12.8-2.1 2.1m-8.6 8.6-2.1 2.1"/><circle cx="12" cy="12" r="3.5"/></svg>
      <span>新功能</span><i v-if="!seen" aria-label="有未读更新"></i>
    </button>
    <section v-if="open" id="release-summary" class="release-popover" aria-labelledby="release-summary-title">
      <header><div><span class="release-eyebrow">本次更新</span><h2 id="release-summary-title">{{ state.release.title }}</h2></div><button type="button" class="close-release" aria-label="收起更新卡片" @click="close">×</button></header>
      <p class="release-meta">{{ state.release.publishedOn }}<span>·</span>{{ state.updateAvailable ? '更新后可使用' : `${state.release.features.length} 项改进` }}</p>
      <div class="release-features"><article v-for="feature in state.release.features" :key="feature.title"><span class="item-symbol" aria-hidden="true">✓</span><div><h3>{{ feature.title }}</h3><p>{{ feature.description }}</p></div></article></div>
      <footer><button type="button" class="all-notes" @click="details">查看详情</button><span>可随时关闭，继续工作</span></footer>
    </section>
  </div>
</template>

<style scoped>
.release-menu{position:relative;align-self:center;color:#223645;font-family:"Microsoft YaHei",sans-serif}
.release-tool{display:flex;align-items:center;gap:7px;background:transparent;border:0;border-radius:7px;color:#52626f;padding:8px 4px;font:13px "Microsoft YaHei",sans-serif;white-space:nowrap;cursor:pointer}
.release-tool:hover{color:#a65f0b}.release-tool svg{width:18px;height:18px}.release-tool i{width:5px;height:5px;border-radius:50%;background:#f3a134;margin-left:1px}
.release-popover{position:absolute;z-index:50;right:-20px;top:48px;width:362px;max-height:calc(100dvh - 90px);overflow:auto;box-sizing:border-box;padding:23px;background:#fff;border:1px solid #e3e8ed;border-radius:12px;box-shadow:0 12px 38px #20374d20;overflow-wrap:anywhere}
.release-popover header{display:flex;align-items:flex-start;justify-content:space-between;gap:12px}.release-eyebrow{font-size:11px;letter-spacing:.08em;color:#aa6b20}.release-popover h2{font-size:18px;margin:8px 0 0;line-height:1.5}
.close-release{border:0;background:transparent;font:22px Arial;color:#83909b;cursor:pointer;padding:0 2px}.release-meta{font-size:12px;color:#7c8993;margin:8px 0 19px}.release-meta span{margin:0 7px}
.release-popover article{display:flex;gap:12px;padding:15px 0;border-top:1px solid #edf0f3}.item-symbol{display:grid;place-items:center;flex-shrink:0;width:28px;height:28px;border-radius:7px;background:#f3f6f9;color:#708598;font:15px Arial}.release-popover h3{font-size:13px;font-weight:600;margin:0 0 6px}.release-popover article p{font-size:12px;color:#71818d;line-height:1.8;margin:0}
.release-popover footer{border-top:1px solid #edf0f3;padding-top:18px;display:flex;justify-content:space-between;align-items:center;gap:12px}.all-notes{border:0;border-radius:6px;background:#fff0dc;color:#985a10;font:13px "Microsoft YaHei",sans-serif;padding:10px 13px;cursor:pointer;white-space:nowrap}.release-popover footer span{font-size:11px;color:#7a8994}.release-menu button:focus-visible{outline:2px solid #d87900;outline-offset:3px}
@media(max-width:640px){.release-popover{position:fixed;top:68px;right:12px;width:calc(100vw - 24px);max-width:362px;padding:20px;max-height:calc(100dvh - 84px)}}
</style>
