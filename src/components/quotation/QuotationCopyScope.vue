<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
const props = defineProps<{ count?: number; label: string; disabled?: boolean; primary?: boolean }>()
const emit = defineEmits<{ current: []; batch: [] }>()
const root = ref<HTMLElement>(), arrow = ref<HTMLButtonElement>(), open = ref(false)
function close(focus = false) { open.value = false; if (focus) arrow.value?.focus() }
async function toggle() { open.value = !open.value; await nextTick(); if (open.value) root.value?.querySelector<HTMLButtonElement>('[role="menuitem"]')?.focus() }
function move(direction: number) {
  const buttons = [...(root.value?.querySelectorAll<HTMLButtonElement>('[role="menuitem"]') ?? [])]
  buttons[(buttons.findIndex(button => button === document.activeElement) + direction + buttons.length) % buttons.length]?.focus()
}
function outside(event: PointerEvent) { if (!root.value?.contains(event.target as Node)) close() }
watch(() => props.disabled, value => { if (value) close() })
onMounted(() => document.addEventListener('pointerdown', outside))
onBeforeUnmount(() => document.removeEventListener('pointerdown', outside))
</script>
<template>
  <div ref="root" class="copy-scope" :class="{ 'has-batch': count, primary }" @keydown.esc.stop.prevent="close(true)" @focusout="!root?.contains($event.relatedTarget as Node) && close()">
    <slot />
    <button v-if="count" ref="arrow" class="scope-arrow" type="button" :aria-label="`${label}范围`" aria-haspopup="menu" :aria-expanded="open" :disabled="disabled" @click="toggle">▾</button>
    <div v-if="open" class="scope-menu" role="menu" :aria-label="`${label}范围`" @keydown.down.prevent="move(1)" @keydown.up.prevent="move(-1)">
      <button type="button" role="menuitem" @click="close(true); emit('current')"><span>复制当前记录</span><small>1 条</small></button>
      <button type="button" role="menuitem" @click="close(true); emit('batch')"><span>复制本批全部记录</span><small>{{ count }} 条</small></button>
      <p>同次提交的全部记录，不受分页限制</p>
    </div>
  </div>
</template>
<style scoped>
.copy-scope{display:inline-flex;position:relative}.has-batch :deep(>button:first-child){border-radius:7px 0 0 7px}.copy-scope>button.scope-arrow{align-self:stretch;height:auto;padding:0;width:29px;border:1px solid #f58220;border-left:0;border-radius:0 7px 7px 0;background:#fff8f1;color:#a6530c;cursor:pointer}.copy-scope.primary>button.scope-arrow{background:#f58220;color:#fff;border-left:1px solid #ffffff66}.scope-menu{position:absolute;right:0;bottom:calc(100% + 8px);z-index:10;width:260px;max-width:85vw;padding:7px;border:1px solid #ead7bf;border-radius:10px;background:white;box-shadow:0 8px 28px #27324026}.copy-scope .scope-menu button{display:flex;align-items:center;justify-content:space-between;gap:12px;width:100%;height:44px;padding:0 12px;border:0;border-radius:6px;background:white;color:#233044;text-align:left;cursor:pointer;font-size:12px}.copy-scope .scope-menu button:hover,.copy-scope .scope-menu button:focus-visible{background:#fff2df;color:#ad5a0a;outline:2px solid #f5b16c;outline-offset:-2px}.scope-menu small{font-size:11px;color:#7d8792}.scope-menu p{margin:7px 12px 5px;color:#86909c;font-size:11px;line-height:1.5}.copy-scope>button:disabled{opacity:.5;cursor:wait}
</style>
