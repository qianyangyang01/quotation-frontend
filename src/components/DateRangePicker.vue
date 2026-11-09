<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { calendarMonthDays, isoDate, parseIsoDate } from './dateFilter'

const props = defineProps<{ from: string; to: string }>()
const emit = defineEmits<{ change: [range: { from: string; to: string }] }>()
const trigger = ref<HTMLButtonElement>()
const panel = ref<HTMLElement>()
const open = ref(false)
const draftFrom = ref(''), draftTo = ref(''), hover = ref('')
const pickingEnd = ref(false)
const today = ref(businessToday())
const cursor = ref(parseIsoDate(today.value)!)
const position = ref({ top: '0px', left: '0px' })
const weekdays = ['一', '二', '三', '四', '五', '六', '日']
const shortcuts = ['今天', '昨天', '近7天', '近30天', '本月', '上月']
function businessToday() {
  const parts = new Intl.DateTimeFormat('en', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(new Date())
  return ['year', 'month', 'day'].map(type => parts.find(part => part.type === type)!.value).join('-')
}
const caption = computed(() => !props.from && !props.to ? '选择日期范围' : `${props.from || '不限'}  至  ${props.to || '不限'}`)
const error = computed(() => {
  if ((draftFrom.value && !parseIsoDate(draftFrom.value)) || (draftTo.value && !parseIsoDate(draftTo.value))) return '请输入有效日期，格式为 YYYY-MM-DD'
  return draftFrom.value && draftTo.value && draftFrom.value > draftTo.value ? '报价开始日期不能晚于结束日期' : ''
})
const months = computed(() => [0, 1].map(offset => {
  const date = new Date(cursor.value.getFullYear(), cursor.value.getMonth() + offset, 1)
  return { key: isoDate(date), title: `${date.getFullYear()} 年 ${date.getMonth() + 1} 月`, days: calendarMonthDays(date) }
}))
const years = computed(() => {
  const current = Number(today.value.slice(0, 4))
  const first = Math.min(current - 10, cursor.value.getFullYear())
  const last = Math.max(current + 1, cursor.value.getFullYear())
  return Array.from({ length: last - first + 1 }, (_, index) => first + index)
})
const highlighted = computed(() => {
  const end = pickingEnd.value && hover.value ? hover.value : draftTo.value
  return draftFrom.value && end ? [draftFrom.value, end].sort() : []
})
function inRange(value: string) { return highlighted.value.length === 2 && value >= highlighted.value[0]! && value <= highlighted.value[1]! }
function place() {
  if (!open.value || !trigger.value) return
  const rect = trigger.value.getBoundingClientRect()
  const width = Math.min(612, window.innerWidth - 24)
  const height = panel.value?.getBoundingClientRect().height || 420
  const below = window.innerHeight - rect.bottom - 8
  position.value = {
    left: `${Math.max(12, Math.min(rect.left, window.innerWidth - width - 12))}px`,
    top: `${Math.max(12, below >= height || rect.top < height ? Math.min(rect.bottom + 8, window.innerHeight - height - 12) : rect.top - height - 8)}px`,
  }
}
async function show() {
  if (open.value) { close(); return }
  today.value = businessToday()
  draftFrom.value = props.from; draftTo.value = props.to; pickingEnd.value = false; hover.value = ''
  cursor.value = parseIsoDate(props.from || props.to) || parseIsoDate(today.value)!
  open.value = true
  await nextTick(); place(); panel.value?.focus()
}
function close() { open.value = false; trigger.value?.focus() }
function apply() {
  if (error.value) return
  emit('change', { from: draftFrom.value, to: draftTo.value }); close()
}
function clear() { draftFrom.value = ''; draftTo.value = ''; apply() }
function pick(value: string) {
  if (!pickingEnd.value) { draftFrom.value = value; draftTo.value = ''; pickingEnd.value = true }
  else { [draftFrom.value, draftTo.value] = [draftFrom.value, value].sort() as [string, string]; pickingEnd.value = false }
  hover.value = ''
}
function preset(name: string) {
  const end = parseIsoDate(today.value)!, start = new Date(end)
  if (name === '昨天') { start.setDate(start.getDate() - 1); end.setDate(end.getDate() - 1) }
  if (name === '近7天') start.setDate(start.getDate() - 6)
  if (name === '近30天') start.setDate(start.getDate() - 29)
  if (name === '本月') start.setDate(1)
  if (name === '上月') { start.setDate(1); start.setMonth(start.getMonth() - 1); end.setDate(0) }
  draftFrom.value = isoDate(start); draftTo.value = isoDate(end); apply()
}
function move(offset: number) { cursor.value = new Date(cursor.value.getFullYear(), cursor.value.getMonth() + offset, 1) }
function changeMonth(event: Event) { cursor.value = new Date(cursor.value.getFullYear(), Number((event.target as HTMLSelectElement).value), 1) }
function changeYear(event: Event) { cursor.value = new Date(Number((event.target as HTMLSelectElement).value), cursor.value.getMonth(), 1) }
function outside(event: PointerEvent) { if (open.value && !panel.value?.contains(event.target as Node) && !trigger.value?.contains(event.target as Node)) open.value = false }
function escape(event: KeyboardEvent) { if (open.value && event.key === 'Escape') { event.preventDefault(); close() } }
onMounted(() => { document.addEventListener('pointerdown', outside); document.addEventListener('keydown', escape); window.addEventListener('resize', place); window.addEventListener('scroll', place, true) })
onUnmounted(() => { document.removeEventListener('pointerdown', outside); document.removeEventListener('keydown', escape); window.removeEventListener('resize', place); window.removeEventListener('scroll', place, true) })
</script>

<template>
  <button ref="trigger" class="range-trigger" :class="{ selected: from || to }" type="button" aria-label="选择报价日期范围" aria-haspopup="dialog" :aria-expanded="open" @click="show">
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" aria-hidden="true"><rect x="3" y="5" width="18" height="16" rx="3" /><path d="M7 2v6M17 2v6M3 11h18" /></svg>
    <span>{{ caption }}</span><span class="chevron" aria-hidden="true">⌄</span>
  </button>
  <Teleport to="body">
    <section v-if="open" ref="panel" class="range-panel" role="dialog" aria-label="报价日期范围选择器" tabindex="-1" :style="position">
      <header class="range-heading"><div><b>选择报价日期</b><small>点选开始和结束日期，支持跨月选择</small></div><button type="button" aria-label="关闭日期选择器" @click="close">×</button></header>
      <nav class="range-shortcuts" aria-label="快捷日期范围"><button v-for="name in shortcuts" :key="name" type="button" @click="preset(name)">{{ name }}</button><button type="button" @click="clear">不限日期</button></nav>
      <div class="range-inputs">
        <label>开始日期<input v-model="draftFrom" aria-label="报价开始日期" placeholder="YYYY-MM-DD" maxlength="10" @input="pickingEnd=false;hover=''" /></label>
        <span>→</span>
        <label>结束日期<input v-model="draftTo" aria-label="报价结束日期" placeholder="YYYY-MM-DD" maxlength="10" @input="pickingEnd=false;hover=''" /></label>
      </div>
      <div class="month-navigation"><button type="button" aria-label="上个月" @click="move(-1)">‹</button><div><select aria-label="选择年份" :value="cursor.getFullYear()" @change="changeYear"><option v-for="year in years" :key="year" :value="year">{{ year }} 年</option></select><select aria-label="选择月份" :value="cursor.getMonth()" @change="changeMonth"><option v-for="month in 12" :key="month" :value="month-1">{{ month }} 月</option></select></div><button type="button" aria-label="下个月" @click="move(1)">›</button></div>
      <div class="range-months">
        <section v-for="month in months" :key="month.key" class="range-month" :aria-label="month.title"><h3>{{ month.title }}</h3><div class="calendar-week"><span v-for="day in weekdays" :key="day">{{ day }}</span></div><div class="calendar-days" @mouseleave="hover=''">
          <template v-for="(day,index) in month.days" :key="day?.value || index"><span v-if="!day"></span><button v-else type="button" :aria-label="day.value" :aria-pressed="day.value===draftFrom || day.value===draftTo" :class="{ edge: day.value===draftFrom || day.value===draftTo, between: inRange(day.value), today: day.value===today }" @click="pick(day.value)" @mouseenter="hover=day.value">{{ day.day }}</button></template>
        </div></section>
      </div>
      <p v-if="error" class="range-error" role="alert">{{ error }}</p>
      <footer class="range-footer"><small>{{ pickingEnd ? '请选择结束日期；同一天再点一次即可选单日' : '包含起止当天；留空一端表示不限' }}</small><button type="button" @click="close">取消</button><button class="range-apply" type="button" :disabled="Boolean(error)" @click="apply">确定筛选</button></footer>
    </section>
  </Teleport>
</template>

<style scoped>
.range-trigger{display:inline-flex;align-items:center;gap:8px;min-width:240px;height:34px!important;padding:0 10px;border:1px solid #dce3e8;border-radius:7px;background:#fff;color:#596771;font:inherit;cursor:pointer;white-space:nowrap}.range-trigger.selected{color:#273540}.range-trigger:hover,.range-trigger[aria-expanded=true]{border-color:#e8a03c;box-shadow:0 0 0 3px #fff3df}.chevron{margin-left:auto;color:#8a959e}.range-panel{position:fixed;z-index:100;box-sizing:border-box;width:min(612px,calc(100vw - 24px));max-height:calc(100vh - 24px);overflow:auto;padding:18px;border:1px solid #e3e7eb;border-radius:14px;background:#fff;box-shadow:0 12px 44px #172b4d26;color:#293844;font:13px "Microsoft YaHei",Arial,sans-serif;outline:none}.range-panel button,.range-panel input,.range-panel select{font:inherit;box-sizing:border-box}.range-panel button{cursor:pointer}.range-heading{display:flex;align-items:center;justify-content:space-between}.range-heading b{font-size:15px}.range-heading small{display:block;margin-top:5px;color:#85919a;font-size:11px}.range-heading>button{width:28px;height:28px;border:0;background:#f5f7f9;border-radius:6px;color:#74808a;font-size:20px}.range-shortcuts{display:flex;flex-wrap:wrap;gap:6px;margin:16px 0}.range-shortcuts button{padding:6px 10px;border:1px solid #e5e9ed;border-radius:6px;background:#f8fafb;color:#596771;font-size:12px}.range-shortcuts button:hover{border-color:#efb666;background:#fff5e6;color:#a96000}.range-inputs{display:flex;align-items:center;gap:12px}.range-inputs label{display:grid;flex:1;min-width:0;gap:6px;font-size:11px;color:#7c8790}.range-inputs input{width:100%;height:34px;padding:7px 10px;border:1px solid #dce3e8;border-radius:6px;background:#fff;color:#293844;font-size:12px}.range-inputs>span{margin-top:18px;color:#8d98a0}.month-navigation{display:flex;justify-content:space-between;align-items:center;margin-top:14px}.month-navigation>button{width:30px;height:28px;border:1px solid #e5e9ed;border-radius:6px;background:#fff;color:#596771;font-size:20px}.month-navigation select{padding:4px 5px;border:0;background:#fff;color:#394955;cursor:pointer}.range-months{display:grid;grid-template-columns:1fr 1fr;gap:22px}.range-month h3{text-align:center;margin:10px 0;font-size:12px;font-weight:600}.calendar-week,.calendar-days{display:grid;grid-template-columns:repeat(7,minmax(0,1fr));gap:3px}.calendar-week span{padding:7px 0;text-align:center;color:#95a0a8;font-size:11px}.calendar-days>button,.calendar-days>span{height:31px;min-width:0}.calendar-days button{position:relative;border:0;border-radius:6px;background:#fff;color:#354550;font-size:12px}.calendar-days button.between{background:#fff0d7;color:#b46800}.calendar-days button:hover{box-shadow:inset 0 0 0 1px #eda23a;background:#fff3df}.calendar-days button.today:after{content:'';position:absolute;bottom:3px;left:calc(50% - 2px);width:4px;height:4px;background:#df8a17;border-radius:50%}.calendar-days button.edge{background:#f29a18;color:#fff;font-weight:700}.calendar-days button.edge:after{background:#fff}.range-error{color:#b42318;margin:10px 0;font-size:12px}.range-footer{display:flex;align-items:center;gap:8px;margin-top:16px;padding-top:14px;border-top:1px solid #edf0f2}.range-footer small{flex:1;font-size:11px;color:#85919a;line-height:1.5}.range-footer button{padding:7px 12px;border:1px solid #dce3e8;border-radius:6px;background:#fff;color:#596771;font-size:12px;white-space:nowrap}.range-footer .range-apply{background:#f29a18;border-color:#f29a18;color:#fff;font-weight:700}.range-footer button:disabled{opacity:.45;cursor:not-allowed}.range-panel button:focus-visible,.range-panel select:focus-visible,.range-panel input:focus-visible{outline:2px solid #eda23a;outline-offset:2px}@media(max-width:560px){.range-panel{padding:12px}.range-months{grid-template-columns:1fr;gap:8px}.range-footer{position:sticky;bottom:-12px;padding-bottom:12px;background:#fff}.range-shortcuts{gap:5px}.range-shortcuts button{padding:6px 8px}}
</style>
