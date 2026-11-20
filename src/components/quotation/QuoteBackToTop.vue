<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'

const anchor = ref<HTMLElement>()
const visible = ref(false)
const position = ref<{ left: number; top: number }>()
const dragging = ref(false)
let gesture: { pointerId: number; startX: number; startY: number; left: number; top: number; button: HTMLButtonElement } | undefined
let suppressClick = false
let size = { width: 76, height: 64 }
function clampPosition(left: number, top: number, button?: HTMLButtonElement) {
  if (button) size = button.getBoundingClientRect()
  return {
    left: Math.max(8, Math.min(left, Math.max(8, window.innerWidth - size.width - 8))),
    top: Math.max(8, Math.min(top, Math.max(8, window.innerHeight - size.height - 8))),
  }
}
function startDrag(event: PointerEvent) {
  if (event.button !== 0 || event.isPrimary === false || gesture) return
  const button = event.currentTarget as HTMLButtonElement
  const rect = button.getBoundingClientRect()
  suppressClick = false
  gesture = { pointerId: event.pointerId, startX: event.clientX, startY: event.clientY, left: rect.left, top: rect.top, button }
  button.setPointerCapture?.(event.pointerId)
}
function moveDrag(event: PointerEvent) {
  if (!gesture || gesture.pointerId !== event.pointerId) return
  const dx = event.clientX - gesture.startX, dy = event.clientY - gesture.startY
  if (!dragging.value && Math.hypot(dx, dy) < 6) return
  event.preventDefault()
  dragging.value = true
  suppressClick = true
  position.value = clampPosition(gesture.left + dx, gesture.top + dy, gesture.button)
}
function endDrag(event?: PointerEvent) {
  if (!gesture || (event && event.pointerId !== gesture.pointerId)) return
  const { button, pointerId } = gesture
  gesture = undefined
  dragging.value = false
  if (button.hasPointerCapture?.(pointerId)) button.releasePointerCapture(pointerId)
}
function resize() {
  endDrag()
  const button = anchor.value?.querySelector('button')
  if (position.value) position.value = clampPosition(position.value.left, position.value.top, button ?? undefined)
  update()
}
function scrollContainer(): HTMLElement | Window {
  for (let element = anchor.value?.parentElement; element; element = element.parentElement) {
    if (/(auto|scroll)/.test(getComputedStyle(element).overflowY) && element.scrollHeight > element.clientHeight) return element
  }
  return window
}
function update() {
  const dialog = anchor.value?.closest('dialog')
  if (dialog && !dialog.open) { visible.value = false; endDrag(); return }
  const container = scrollContainer()
  visible.value = (container === window ? window.scrollY : (container as HTMLElement).scrollTop) > 500
  if (!visible.value) endDrag()
}
function goTop(event: MouseEvent) {
  // A pointer click after dragging must not scroll. Keyboard activation still works.
  if (suppressClick && event.detail !== 0) { suppressClick = false; return }
  suppressClick = false
  const reducedMotion = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
  scrollContainer().scrollTo({ top: 0, behavior: reducedMotion ? 'instant' : 'smooth' })
}
onMounted(() => {
  window.addEventListener('scroll', update, true)
  window.addEventListener('resize', resize)
  update()
})
onBeforeUnmount(() => {
  window.removeEventListener('scroll', update, true)
  window.removeEventListener('resize', resize)
  endDrag()
})
</script>

<template>
  <span ref="anchor"><button v-if="visible" class="quote-back-top" :class="{ dragging }" :style="position ? { left: position.left + 'px', top: position.top + 'px', right: 'auto', bottom: 'auto' } : undefined" type="button" aria-label="回到顶部" title="点击回到顶部；按住可拖动位置" @pointerdown="startDrag" @pointermove="moveDrag" @pointerup="endDrag" @pointercancel="endDrag" @lostpointercapture="endDrag" @click="goTop"><span aria-hidden="true">↑</span>回到顶部</button></span>
</template>

<style scoped>
.quote-back-top{touch-action:none;user-select:none;cursor:grab!important}.quote-back-top.dragging{cursor:grabbing!important}
.quote-back-top{position:fixed;right:24px;bottom:104px;z-index:40;display:grid;justify-items:center;gap:3px;border:1px solid #f58220;border-radius:10px;background:#fff8f1;color:#a6530c;padding:9px 12px;font-size:12px;box-shadow:0 3px 14px #20253218;cursor:pointer}.quote-back-top span{font-size:21px;line-height:1}.quote-back-top:hover{background:#ffead8}.quote-back-top:focus-visible{outline:2px solid #f58220;outline-offset:3px}@media(max-width:600px){.quote-back-top{right:12px}}
</style>
