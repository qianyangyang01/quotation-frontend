<script setup lang="ts">
import { computed, inject, nextTick, ref, useAttrs, useId, type Ref } from 'vue'
import { parseQuotePriceInput } from '@/data/quotePriceExpression'

defineOptions({ inheritAttrs: false })
const props = defineProps<{ value?: string | number; inline?: boolean; disabled?: boolean; amount?: boolean }>()
const emit = defineEmits<{ input: [event: Event]; blur: [event: FocusEvent]; keydown: [event: KeyboardEvent]; start: []; cancel: [] }>()
const attrs = useAttrs()
const id = useId()
const active = inject<Ref<string | null>>('quote-sheet-active-cell', ref(null))
const input = ref<HTMLInputElement>()
const trigger = ref<HTMLButtonElement>()
const editing = computed(() => !props.inline || active.value === id)
let cancelled = false
const text = computed(() => {
  const value = String(props.value ?? '')
  if (props.amount) {
    const parsed = parseQuotePriceInput(value)
    if (!parsed.error && parsed.value !== null) return parsed.value.toFixed(2)
  }
  return value || '—'
})
async function start() {
  if (props.disabled) return
  cancelled = false
  emit('start')
  active.value = id
  await nextTick()
  input.value?.focus()
  input.value?.select()
}
function finish(event: FocusEvent) {
  if (!cancelled) emit('blur', event)
  if (props.inline && active.value === id) active.value = null
}
async function keydown(event: KeyboardEvent) {
  if (event.isComposing) return
  if (props.inline && event.key === 'Escape') {
    event.preventDefault()
    event.stopPropagation()
    cancelled = true
    emit('cancel')
    active.value = null
    await nextTick()
    trigger.value?.focus()
    return
  }
  emit('keydown', event)
  if (props.inline && event.key === 'Enter') {
    event.preventDefault()
    if (props.amount && parseQuotePriceInput(String(props.value ?? '')).error) return
    input.value?.blur()
    await nextTick()
    trigger.value?.focus()
  }
}
</script>

<template>
  <input v-if="editing" ref="input" v-bind="attrs" :value="value" :disabled="disabled" :class="{ 'sheet-cell-input': inline }" @input="emit('input', $event)" @blur="finish" @keydown="keydown">
  <button v-else ref="trigger" type="button" class="sheet-cell-text" :class="attrs.class" :style="attrs.style" :aria-label="String(attrs['aria-label'] ?? '')" :aria-invalid="attrs['aria-invalid'] === true || attrs['aria-invalid'] === 'true'" :disabled="disabled" title="点击编辑，Enter 确认，Esc 取消" @click="start">{{ text }}</button>
</template>
