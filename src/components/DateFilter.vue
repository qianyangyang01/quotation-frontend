<script setup lang="ts">
import { computed, ref } from 'vue'
import { calendarMonthDays, isDateDisabled, isoDate, parseIsoDate } from './dateFilter'

const props = defineProps<{ label: string; modelValue: string; min?: string; max?: string }>()
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
const open = ref(false)
const cursor = ref(startOfMonth(parseIsoDate(props.modelValue) || new Date()))
const weekdays = ['一', '二', '三', '四', '五', '六', '日']

function startOfMonth(date: Date) { return new Date(date.getFullYear(), date.getMonth(), 1) }
function openPicker() { cursor.value = startOfMonth(parseIsoDate(props.modelValue) || new Date()); open.value = true }
function moveMonth(offset: number) { cursor.value = new Date(cursor.value.getFullYear(), cursor.value.getMonth() + offset, 1) }
function disabled(value: string) { return isDateDisabled(value, props.min, props.max) }
function selectDate(value: string) { if (!disabled(value)) { emit('update:modelValue', value); open.value = false } }
function selectToday() { selectDate(isoDate(new Date())) }
function clearDate() { emit('update:modelValue', ''); open.value = false }

const monthLabel = computed(() => `${cursor.value.getFullYear()} 年 ${cursor.value.getMonth() + 1} 月`)
const calendarDays = computed(() => calendarMonthDays(cursor.value))
</script>

<template>
  <div class="date-filter">
    <span>{{ label }}</span>
    <button class="date-trigger" type="button" :class="{ selected:modelValue }" :aria-expanded="open" @click="openPicker"><b>{{ modelValue || '年 - 月 - 日' }}</b><i aria-hidden="true">▦</i></button>
    <button v-if="open" class="date-backdrop" type="button" aria-label="关闭日期选择器" @click="open=false"></button>
    <section v-if="open" class="date-panel" role="dialog" :aria-label="`${label}选择器`">
      <header><button type="button" aria-label="上个月" @click="moveMonth(-1)">‹</button><b>{{ monthLabel }}</b><button type="button" aria-label="下个月" @click="moveMonth(1)">›</button></header>
      <div class="weekdays"><span v-for="day in weekdays" :key="day">{{ day }}</span></div>
      <div class="days"><template v-for="(day,index) in calendarDays" :key="day?.value || `blank-${index}`"><span v-if="!day"></span><button v-else type="button" :class="{ active:day.value===modelValue, today:day.value===isoDate(new Date()) }" :disabled="disabled(day.value)" @click="selectDate(day.value)">{{ day.day }}</button></template></div>
      <footer><button type="button" @click="clearDate">清除</button><button type="button" :disabled="disabled(isoDate(new Date()))" @click="selectToday">今天</button></footer>
    </section>
  </div>
</template>

<style scoped>
.date-filter{position:relative;display:grid;gap:5px;color:#78848d;font-size:8px}.date-trigger{box-sizing:border-box;display:flex;width:128px;height:36px;align-items:center;justify-content:space-between;border:1px solid #dbe2e7;border-radius:6px;background:#fff;padding:0 9px;color:#34424c;cursor:pointer}.date-trigger:hover,.date-trigger[aria-expanded=true]{border-color:#ff9810;box-shadow:0 0 0 3px rgba(255,152,16,.1)}.date-trigger b{font-size:12px;font-weight:400;white-space:nowrap}.date-trigger.selected b{font-weight:700}.date-trigger i{color:#172431;font-size:13px;font-style:normal}.date-backdrop{position:fixed;z-index:49;inset:0;border:0;background:transparent}.date-panel{position:absolute;z-index:50;top:calc(100% + 8px);left:0;width:286px;box-sizing:border-box;padding:12px;border:1px solid #dfe5e9;border-radius:10px;background:#fff;box-shadow:0 16px 44px rgba(20,34,46,.2);color:#24333e}.date-panel header{display:flex;align-items:center;justify-content:space-between;margin-bottom:9px}.date-panel header b{font-size:13px}.date-panel button{cursor:pointer}.date-panel header button{width:30px;height:30px;border:0;border-radius:6px;background:#f2f5f7;color:#34434e;font-size:19px}.weekdays,.days{display:grid;grid-template-columns:repeat(7,1fr);gap:3px}.weekdays span{display:grid;height:24px;place-items:center;color:#8a959d;font-size:9px}.days span,.days button{height:31px}.days button{border:0;border-radius:6px;background:transparent;color:#33424c;font-size:10px}.days button:hover:not(:disabled){background:#fff1dc;color:#a65b00}.days button.today{box-shadow:inset 0 0 0 1px #f1a33b}.days button.active{background:#ff9810;color:#fff;font-weight:900;box-shadow:none}.days button:disabled{color:#c8ced3;cursor:not-allowed}.date-panel footer{display:flex;justify-content:space-between;margin-top:10px;padding-top:9px;border-top:1px solid #edf0f2}.date-panel footer button{height:29px;border:0;border-radius:6px;background:#f2f5f7;padding:0 11px;color:#56646e;font-size:9px}.date-panel footer button:last-child{background:#172c40;color:#fff}.date-panel footer button:disabled{opacity:.4;cursor:not-allowed}@media(max-width:700px){.date-trigger{width:100%}}
</style>
