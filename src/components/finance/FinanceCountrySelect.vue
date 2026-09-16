<script setup lang="ts">
import { computed, nextTick, ref, useId } from 'vue'
const props = defineProps<{ modelValue: string; countries: Array<{ country: string; code?: string; continent?: string; stageLabel?: string }> }>()
const emit = defineEmits<{ 'update:modelValue': [country: string] }>()
const open = ref(false), query = ref(''), active = ref(0)
const root = ref<HTMLElement>(), search = ref<HTMLInputElement>(), trigger = ref<HTMLButtonElement>()
const listId = useId()
const filtered = computed(() => props.countries.filter(item => `${item.country} ${item.code || ''} ${item.continent || ''}`.toLowerCase().includes(query.value.trim().toLowerCase())))
function show() { open.value = !open.value; query.value = ''; active.value = 0; if (open.value) void nextTick(() => search.value?.focus()) }
function choose(country: string) { emit('update:modelValue', country); open.value = false; void nextTick(() => trigger.value?.focus()) }
function key(event: KeyboardEvent) {
  if (event.key === 'Escape') { event.preventDefault(); open.value = false; trigger.value?.focus() }
  else if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
    event.preventDefault(); active.value = Math.max(0, Math.min(filtered.value.length - 1, active.value + (event.key === 'ArrowDown' ? 1 : -1)))
    void nextTick(() => document.getElementById(`${listId}-${active.value}`)?.scrollIntoView({ block: 'nearest' }))
  } else if (event.key === 'Enter') { event.preventDefault(); const item = filtered.value[active.value]; if (item) choose(item.country) }
}
</script>
<template>
  <div ref="root" class="finance-country-select" @focusout="event => { if (!root?.contains(event.relatedTarget as Node)) open = false }">
    <button ref="trigger" type="button" class="country-trigger" aria-label="选择国家" aria-haspopup="listbox" :aria-expanded="open" :aria-controls="listId" @click="show">{{ modelValue || '选择国家' }} <span>⌄</span></button>
    <div v-if="open" class="country-menu">
      <input ref="search" v-model="query" role="combobox" aria-label="搜索国家或代码" :aria-controls="listId" :aria-expanded="open" :aria-activedescendant="filtered.length ? `${listId}-${active}` : undefined" placeholder="搜索国家 / 代码" @input="active=0" @keydown="key">
      <div :id="listId" class="country-options" role="listbox" aria-label="可选国家">
        <button v-for="(item,index) in filtered" :id="`${listId}-${index}`" :key="item.country" type="button" role="option" :aria-selected="item.country === modelValue" :class="{ active:index === active }" @click="choose(item.country)"><b>{{ item.country }}</b><small>{{ item.code }} · {{ item.stageLabel }}</small></button>
        <p v-if="!filtered.length">没有匹配的国家</p>
      </div>
      <small class="country-total">匹配 {{ filtered.length }} / {{ countries.length }} 个国家</small>
    </div>
  </div>
</template>
<style scoped>
.finance-country-select{position:relative;min-width:0}.country-trigger{display:flex;align-items:center;justify-content:space-between;gap:8px;width:100%;min-height:36px;padding:8px;border:1px solid #d9e0e5;border-radius:7px;background:#fff;color:#27343e;font-size:12px;font-weight:700;text-align:left;white-space:normal}.country-menu{position:absolute;top:calc(100% + 5px);left:0;z-index:30;width:260px;max-width:75vw;padding:10px;background:white;border:1px solid #d9e0e5;border-radius:8px;box-shadow:0 8px 24px #19273326}.country-menu input{box-sizing:border-box;width:100%;height:36px;padding:8px;border:1px solid #ccd7e1;border-radius:6px;font-size:13px}.country-options{max-height:240px;overflow-y:auto;margin-top:6px}.country-options button{display:flex;justify-content:space-between;align-items:center;gap:10px;width:100%;padding:9px 7px;border:0;border-radius:4px;background:white;text-align:left;color:#27343e;font-size:12px}.country-options button:hover,.country-options button.active,.country-options button[aria-selected=true]{background:#fff3e2}.country-options small,.country-total{font-size:10px;color:#73818e}.country-total{display:block;padding-top:8px}.country-options p{font-size:12px;color:#73818e}
</style>
