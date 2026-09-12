<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'

const anchor = ref<HTMLElement>()
const visible = ref(false)
function scrollContainer(): HTMLElement | Window {
  for (let element = anchor.value?.parentElement; element; element = element.parentElement) {
    if (/(auto|scroll)/.test(getComputedStyle(element).overflowY) && element.scrollHeight > element.clientHeight) return element
  }
  return window
}
function update() {
  const dialog = anchor.value?.closest('dialog')
  if (dialog && !dialog.open) { visible.value = false; return }
  const container = scrollContainer()
  visible.value = (container === window ? window.scrollY : (container as HTMLElement).scrollTop) > 500
}
function goTop() {
  const reducedMotion = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
  scrollContainer().scrollTo({ top: 0, behavior: reducedMotion ? 'instant' : 'smooth' })
}
onMounted(() => {
  window.addEventListener('scroll', update, true)
  window.addEventListener('resize', update)
  update()
})
onBeforeUnmount(() => {
  window.removeEventListener('scroll', update, true)
  window.removeEventListener('resize', update)
})
</script>

<template>
  <span ref="anchor"><button v-if="visible" class="quote-back-top" type="button" aria-label="回到顶部" title="回到顶部" @click="goTop"><span aria-hidden="true">↑</span>回到顶部</button></span>
</template>

<style scoped>
.quote-back-top{position:fixed;right:24px;bottom:28px;z-index:40;display:grid;justify-items:center;gap:3px;border:1px solid #f58220;border-radius:10px;background:#fff8f1;color:#a6530c;padding:9px 12px;font-size:12px;box-shadow:0 3px 14px #20253218;cursor:pointer}.quote-back-top span{font-size:21px;line-height:1}.quote-back-top:hover{background:#ffead8}.quote-back-top:focus-visible{outline:2px solid #f58220;outline-offset:3px}@media(max-width:600px){.quote-back-top{right:12px;bottom:16px}}
</style>
