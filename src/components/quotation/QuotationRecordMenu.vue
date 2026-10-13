<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
const props = defineProps<{ label: string; disabled?: boolean }>()
const trigger = ref<HTMLButtonElement>(), panel = ref<HTMLDivElement>(), expanded = ref(false)
function close() { panel.value?.hidePopover?.(); expanded.value = false }
watch(() => props.disabled, disabled => { if (disabled) close() })
function position() {
  if (!trigger.value || !panel.value) return
  const anchor = trigger.value.getBoundingClientRect(), height = panel.value.offsetHeight
  const below = window.innerHeight - anchor.bottom - 18, above = anchor.top - 18
  const upward = height > below && above > below
  panel.value.style.maxHeight = `${Math.max(100, upward ? above : below)}px`
  panel.value.style.left = `${Math.max(12, Math.min(anchor.right - 220, window.innerWidth - 232))}px`
  panel.value.style.top = `${Math.max(12, upward ? anchor.top - panel.value.offsetHeight - 6 : anchor.bottom + 6)}px`
}
async function toggle() {
  if (expanded.value) { close(); return }
  panel.value?.showPopover?.(); expanded.value = true
  await nextTick(); position()
  panel.value?.querySelector<HTMLElement>('button:not(:disabled),a')?.focus()
}
function picked(event: MouseEvent) { if ((event.target as HTMLElement).closest('button:not(:disabled),a')) close() }
function key(event: KeyboardEvent) {
  if (event.key === 'Escape') { close(); trigger.value?.focus(); return }
  if (!['ArrowDown', 'ArrowUp'].includes(event.key)) return
  event.preventDefault()
  const items = [...(panel.value?.querySelectorAll<HTMLElement>('button:not(:disabled),a') ?? [])]
  items[(items.indexOf(document.activeElement as HTMLElement) + (event.key === 'ArrowDown' ? 1 : -1) + items.length) % items.length]?.focus()
}
function scrolled(event: Event) { if (!panel.value?.contains(event.target as Node)) close() }
onMounted(() => { window.addEventListener('resize', close); document.addEventListener('scroll', scrolled, true) })
onBeforeUnmount(() => { close(); window.removeEventListener('resize', close); document.removeEventListener('scroll', scrolled, true) })
</script>
<template>
  <div class="record-more" @click.stop>
    <button ref="trigger" type="button" class="record-more-trigger" :aria-label="label" :aria-expanded="expanded" :disabled="disabled" @click="toggle">更多 <span aria-hidden="true">⌄</span></button>
    <div ref="panel" popover="auto" class="record-more-panel" role="group" :aria-label="label" @toggle="expanded = $event.newState === 'open'" @click="picked" @keydown="key">
      <slot />
    </div>
  </div>
</template>
<style scoped>
.record-more .record-more-trigger{position:relative;justify-content:center;text-align:center;min-width:72px;padding-inline:18px}.record-more-trigger span{position:absolute;right:6px;top:50%;transform:translateY(-50%)}
.record-more{display:inline-flex}.record-more-trigger{display:flex;align-items:center;gap:10px;min-height:34px;border:1px solid #dce3e9;border-radius:6px;background:#fff;color:#596776;padding:7px 12px;font-size:13px;cursor:pointer}.record-more-trigger:hover{background:#f4f7fa}.record-more-trigger:disabled{opacity:.5;cursor:wait}
.record-more-panel{position:fixed;inset:auto;margin:0;box-sizing:border-box;width:220px;max-width:calc(100vw - 24px);max-height:calc(100vh - 24px);overflow:auto;padding:7px;border:1px solid #dfe5eb;border-radius:10px;background:#fff;color:#344454;box-shadow:0 10px 35px #17212b26;font:13px 'Microsoft YaHei',sans-serif}
.record-more-panel :deep(.menu-group){display:grid;gap:2px;padding:4px 0}.record-more-panel :deep(.menu-group+.menu-group){border-top:1px solid #edf0f3;margin-top:4px;padding-top:8px}.record-more-panel :deep(.menu-heading){padding:3px 10px;color:#8b96a0;font-size:11px;font-weight:400}
.record-more-panel :deep(button),.record-more-panel :deep(a){display:flex;align-items:center;justify-content:space-between;box-sizing:border-box;width:100%;min-height:34px;height:auto!important;padding:8px 10px!important;border:0!important;border-radius:5px;background:#fff!important;color:#344454;font:inherit!important;text-align:left;text-decoration:none;white-space:normal!important;cursor:pointer;box-shadow:none!important}.record-more-panel :deep(button:hover),.record-more-panel :deep(a:hover){background:#f3f6fa!important}.record-more-panel :deep(button:focus-visible),.record-more-panel :deep(a:focus-visible){outline:2px solid #e6a03b;outline-offset:-2px}.record-more-panel :deep(button:disabled){opacity:.5;cursor:wait}.record-more-panel :deep(.danger){color:#c43f36}.record-more-panel :deep(.batch-count){color:#89949f;font-size:11px}.record-more-panel :deep(.spot-check){display:block;width:100%}
</style>
