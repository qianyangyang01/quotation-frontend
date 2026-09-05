<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { QuotationCountrySummary, QuotationMatrixRow, QuotationPresetSelection } from './types'
import QuoteTaxMeta from './QuoteTaxMeta.vue'
import QuoteTaxLegend from './QuoteTaxLegend.vue'

const props = defineProps<{
  countries: QuotationCountrySummary[]
  quoteRowsForCountry: (country: string) => QuotationMatrixRow[]
  contextKey: string
  adoptedCountry: string
  adoptedRule: string
  adoptedCarrier: string
  exchangeRate: number
  unitLabel?: string
  customQuantity?: number
  presetSelection?: QuotationPresetSelection[]
  presetVersion?: number
}>()

const emit = defineEmits<{
  adopt: [row: QuotationMatrixRow]
  copy: [rows: QuotationMatrixRow[]]
  'update:customQuantity': [value: number]
  selectionChange: [rows: QuotationMatrixRow[]]
  quoteRegionChange: [payload: { country: string; region: string }]
  countryOrderChange: [countries: string[]]
}>()

const activeCountry = ref('')
const search = ref('')
const channelSearch = ref('')
const page = ref(1)
const pageSize = ref(5)
const sortMode = ref<'recommended' | 'price' | 'speed'>('recommended')
const selectedKeys = ref<string[]>([])
const draggedCountry = ref('')
const dragOverCountry = ref('')
let observedPresetVersion = -1
let pendingPreset = false

const commonCountries = computed(() => props.countries
  .filter(country => country.stage === 'common')
  .sort((a, b) => a.sortOrder - b.sortOrder || a.name.localeCompare(b.name, 'zh-CN')))

function availableRows(country: string) {
  void props.contextKey
  return props.quoteRowsForCountry(country).filter(row => row.available !== false)
}
function rowKey(row: QuotationMatrixRow) { return `${row.country}|||${row.quoteRegion || ''}|||${row.channelKey || `${row.rule}|||${row.carrier}|||${row.transport}`}` }
function presetMatchesRow(preset: QuotationPresetSelection, row: QuotationMatrixRow) {
  if (preset.country !== row.country) return false
  if (preset.quoteRegion && preset.quoteRegion !== row.quoteRegion) return false
  if (preset.channelKey?.trim()) return preset.channelKey.trim() === row.channelKey?.trim()
  return !!preset.rule && !!preset.carrier && !!preset.transport
    && preset.rule === row.rule && preset.carrier === row.carrier && preset.transport === row.transport
}
function applyPresetSelection() {
  const presets = props.presetSelection || []
  const allRows = commonCountries.value.flatMap(country => availableRows(country.name))
  if (!presets.length) {
    selectedKeys.value = []
    emit('selectionChange', [])
    pendingPreset = false
    return
  }
  const matched = presets.flatMap(preset => {
    const row = allRows.find(candidate => presetMatchesRow(preset, candidate))
    return row ? [row] : []
  })
  if (!allRows.length) return
  selectedKeys.value = [...new Set(matched.map(rowKey))]
  emit('selectionChange', selectedQuoteRows())
  activeCountry.value = matched[0]?.country || activeCountry.value
  pendingPreset = false
}
function isSelected(row: QuotationMatrixRow) { return selectedKeys.value.includes(rowKey(row)) }
function selectedQuoteRows() { return commonCountries.value.flatMap(country => availableRows(country.name)).filter(isSelected) }
function toggleSelection(row: QuotationMatrixRow) {
  const key = rowKey(row)
  const removing = isSelected(row)
  const removingPrimary = removing && props.adoptedCountry === row.country && props.adoptedRule === row.rule && props.adoptedCarrier === row.carrier
  selectedKeys.value = removing ? selectedKeys.value.filter(item => item !== key) : [...selectedKeys.value, key]
  const selected = selectedQuoteRows()
  emit('selectionChange', selected)
  const primaryStillSelected = selected.some(item => item.country === props.adoptedCountry && item.rule === props.adoptedRule && item.carrier === props.adoptedCarrier)
  if (!removing && !primaryStillSelected) emit('adopt', row)
  else if (removingPrimary && selected[0]) emit('adopt', selected[0])
}
function startCountryDrag(country: string, event: DragEvent) {
  draggedCountry.value = country
  dragOverCountry.value = ''
  event.dataTransfer?.setData('text/plain', country)
  if (event.dataTransfer) event.dataTransfer.effectAllowed = 'move'
}
function moveCountryOver(country: string, event: DragEvent) {
  if (!draggedCountry.value || draggedCountry.value === country) return
  event.preventDefault()
  dragOverCountry.value = country
  if (event.dataTransfer) event.dataTransfer.dropEffect = 'move'
}
function dropCountry(country: string, event: DragEvent) {
  event.preventDefault()
  const source = draggedCountry.value || event.dataTransfer?.getData('text/plain') || ''
  if (!source || source === country) return endCountryDrag()
  const order = commonCountries.value.map(item => item.name)
  const sourceIndex = order.indexOf(source)
  const targetIndex = order.indexOf(country)
  if (sourceIndex < 0 || targetIndex < 0) return endCountryDrag()
  order.splice(sourceIndex, 1)
  order.splice(targetIndex, 0, source)
  emit('countryOrderChange', order)
  endCountryDrag()
}
function endCountryDrag() {
  draggedCountry.value = ''
  dragOverCountry.value = ''
}
function etaRange(eta: string) {
  const values = eta.match(/\d+/g)?.map(Number) || []
  return [values[0] ?? Number.POSITIVE_INFINITY, values[1] ?? values[0] ?? Number.POSITIVE_INFINITY]
}
function fastestRow(rows: QuotationMatrixRow[]) {
  return rows.slice().sort((a, b) => {
    const [aStart, aEnd] = etaRange(a.eta)
    const [bStart, bEnd] = etaRange(b.eta)
    return aStart - bStart || aEnd - bEnd || (a.quote1 ?? Infinity) - (b.quote1 ?? Infinity)
  })[0]
}
function countryFlag(code: string) {
  if (!/^[A-Z]{2}$/i.test(code)) return '🌐'
  return [...code.toUpperCase()].map(letter => String.fromCodePoint(127397 + letter.charCodeAt(0))).join('')
}
function formatUsd(value: number | null) { return value == null ? '—' : `$${value.toFixed(2)}` }
function formatCny(value: number | null) { return value == null ? '—' : `¥${(value * props.exchangeRate).toFixed(2)}` }

watch([commonCountries, () => props.contextKey, () => props.presetVersion], () => {
  if ((props.presetVersion || 0) !== observedPresetVersion) {
    observedPresetVersion = props.presetVersion || 0
    pendingPreset = observedPresetVersion > 0
  }
  const preferred = commonCountries.value.find(country => country.name === props.adoptedCountry)?.name
  if (!commonCountries.value.some(country => country.name === activeCountry.value)) {
    activeCountry.value = preferred || commonCountries.value[0]?.name || ''
  }
  page.value = 1
  if (pendingPreset) applyPresetSelection()
}, { immediate: true })
watch([() => props.contextKey, () => props.customQuantity], () => {
  if (!selectedKeys.value.length) return
  const refreshed = selectedQuoteRows()
  const validKeys = new Set(refreshed.map(rowKey))
  selectedKeys.value = selectedKeys.value.filter(key => validKeys.has(key))
  emit('selectionChange', refreshed)
})

const filteredCountries = computed(() => {
  const query = search.value.trim().toLowerCase()
  return commonCountries.value.filter(country => !query || `${country.name} ${country.code}`.toLowerCase().includes(query))
})
const rows = computed(() => availableRows(activeCountry.value))
const sortedRows = computed(() => {
  const result = rows.value.slice()
  if (sortMode.value === 'price') {
    return result.sort((a, b) => {
      const [aStart, aEnd] = etaRange(a.eta)
      const [bStart, bEnd] = etaRange(b.eta)
      return (a.quote1 ?? Number.POSITIVE_INFINITY) - (b.quote1 ?? Number.POSITIVE_INFINITY)
        || aStart - bStart
        || aEnd - bEnd
        || a.transport.localeCompare(b.transport, 'zh-CN')
    })
  }
  if (sortMode.value === 'speed') {
    return result.sort((a, b) => {
      const [aStart, aEnd] = etaRange(a.eta)
      const [bStart, bEnd] = etaRange(b.eta)
      return aStart - bStart || aEnd - bEnd
        || (a.quote1 ?? Number.POSITIVE_INFINITY) - (b.quote1 ?? Number.POSITIVE_INFINITY)
        || a.transport.localeCompare(b.transport, 'zh-CN')
    })
  }
  return result
})
const lowest = computed(() => rows.value.slice().sort((a, b) => (a.quote1 ?? Number.POSITIVE_INFINITY) - (b.quote1 ?? Number.POSITIVE_INFINITY))[0])
const fastest = computed(() => fastestRow(rows.value))
const channelQuery = computed(() => channelSearch.value.trim().toLowerCase())
const filteredRows = computed(() => sortedRows.value.filter(row => !channelQuery.value
  || [row.carrier, row.transport, row.channelCode, row.rule].some(value => value?.toLowerCase().includes(channelQuery.value))))
const pageCount = computed(() => Math.max(1, Math.ceil(filteredRows.value.length / pageSize.value)))
const pagedRows = computed(() => filteredRows.value.slice((page.value - 1) * pageSize.value, page.value * pageSize.value))
const activeSummary = computed(() => commonCountries.value.find(country => country.name === activeCountry.value))

watch([activeCountry, pageSize, sortMode, channelQuery], () => { page.value = 1 })
watch(pageCount, count => { if (page.value > count) page.value = count })
</script>

<template>
  <section class="common-matrix">
    <header class="common-head">
      <div><p>MODE A · COMMON COUNTRY QUOTATION</p><h2>常用国家快速报价</h2><span>国家清单与财务设置同步，选择国家后查看该国全部可用渠道。</span></div>
      <div class="common-head-actions">
        <label class="quantity-field">自定义数量<input :value="customQuantity || 1" type="number" min="1" @input="$emit('update:customQuantity',Math.max(1,Number(($event.target as HTMLInputElement).value)||1))"><span>{{ unitLabel || '件' }}</span></label>
        <label class="country-search">⌕<input v-model="search" placeholder="搜索常用国家或代码"></label>
      </div>
    </header>

    <div v-if="filteredCountries.length" class="country-grid">
      <button v-for="country in filteredCountries" :key="country.name" draggable="true" :class="{ active:activeCountry===country.name, dragging:draggedCountry===country.name, 'drag-over':dragOverCountry===country.name }" title="按住卡片拖动排序" @click="activeCountry=country.name" @dragstart="startCountryDrag(country.name,$event)" @dragover="moveCountryOver(country.name,$event)" @drop="dropCountry(country.name,$event)" @dragend="endCountryDrag">
        <u aria-hidden="true">⋮⋮</u><i>{{ countryFlag(country.code) }}</i><span><b>{{ country.name }}</b><small>{{ country.code }} · {{ availableRows(country.name).length }} 条可用渠道</small></span><em v-if="activeCountry===country.name">已选择</em>
      </button>
    </div>
    <div v-else class="empty-countries">财务设置中暂未配置匹配的常用国家</div>

    <template v-if="activeCountry">
      <div class="country-summary">
        <div class="country-title"><b>{{ activeSummary?.code }}&nbsp; {{ activeCountry }}</b><span>{{ activeSummary?.quoteRegions?.length && !activeSummary.selectedQuoteRegion ? '请选择报价区域以匹配渠道' : `当前条件下 ${rows.length} 个可用渠道` }}</span><label v-if="activeSummary?.quoteRegions?.length" class="quote-region-select">报价区域<select :value="activeSummary.selectedQuoteRegion" @change="$emit('quoteRegionChange',{ country:activeCountry, region:($event.target as HTMLSelectElement).value })"><option disabled value="">请选择分区</option><option v-for="region in activeSummary.quoteRegions" :key="region" :value="region">{{ region }}</option></select></label></div>
        <div class="metric lowest"><i>¥</i><span><small>最低价渠道</small><b>{{ formatUsd(lowest?.quote1 ?? null) }}</b><em>{{ lowest ? `${lowest.carrier}｜${lowest.transport}` : '暂无可用渠道' }}</em></span></div>
        <div class="metric fastest"><i>⚡</i><span><small>最快渠道</small><b>{{ fastest?.eta || '—' }}</b><em>{{ fastest ? `${fastest.carrier}｜${fastest.transport}` : '暂无可用渠道' }}</em></span></div>
        <button :disabled="!rows.length" @click="$emit('copy',sortedRows)">▦ 复制当前国家</button>
      </div>
      <div class="sort-toolbar" aria-label="渠道排序方式">
        <div class="channel-filter">
          <label class="channel-search"><span aria-hidden="true">⌕</span><input v-model="channelSearch" type="search" aria-label="搜索物流渠道" placeholder="搜索物流商、渠道名称或编码"><button v-if="channelSearch" type="button" aria-label="清空渠道搜索" @click="channelSearch=''">清空</button></label>
          <span class="channel-match-count" role="status">{{ channelQuery ? `匹配 ${filteredRows.length} / ${rows.length} 条渠道` : `共 ${rows.length} 条渠道` }}</span>
        </div>
        <nav>
          <button :class="{ active:sortMode==='recommended' }" :aria-pressed="sortMode==='recommended'" @click="sortMode='recommended'">☷ 综合排序</button>
          <button :class="{ active:sortMode==='price' }" :aria-pressed="sortMode==='price'" @click="sortMode='price'">¥ 1{{ unitLabel || '件' }}价格从低到高</button>
          <button :class="{ active:sortMode==='speed' }" :aria-pressed="sortMode==='speed'" @click="sortMode='speed'">⚡ 速度从快到慢</button>
        </nav>
      </div>
      <QuoteTaxLegend />
      <div class="table-head"><span>物流渠道</span><span>预计时效</span><span>1{{ unitLabel || '件' }}报价<small>USD / CNY</small></span><span>2{{ unitLabel || '件' }}报价<small>USD / CNY</small></span><span>3{{ unitLabel || '件' }}报价<small>USD / CNY</small></span><span class="custom-head">{{ customQuantity || 1 }}{{ unitLabel || '件' }}报价<small>自定义</small></span><span>操作</span></div>
      <div v-if="pagedRows.length" class="quote-rows">
        <article v-for="row in pagedRows" :key="`${row.rule}|${row.carrier}|${row.transport}`" :class="{ adopted:adoptedCountry===activeCountry && adoptedRule===row.rule && adoptedCarrier===row.carrier, selected:isSelected(row) }">
          <div><span class="channel-name-line"><b>{{ row.carrier }}｜{{ row.transport }}</b><QuoteTaxMeta :row="row" /></span><small>渠道编码：{{ row.channelCode || '—' }} · 计费规则：{{ row.rule }}<template v-if="row.quoteRegion"> · {{ row.quoteRegion }}</template></small></div><b>{{ row.eta }}</b>
          <span><b>{{ formatUsd(row.quote1) }}</b><small>{{ formatCny(row.quote1) }}</small></span><span><b>{{ formatUsd(row.quote2) }}</b><small>{{ formatCny(row.quote2) }}</small></span><span><b>{{ formatUsd(row.quote3) }}</b><small>{{ formatCny(row.quote3) }}</small></span><span class="custom-price"><b>{{ formatUsd(row.quoteCustom) }}</b><small>{{ formatCny(row.quoteCustom) }}</small></span>
          <div class="selection-actions"><button @click="toggleSelection(row)">{{ isSelected(row) ? '已加入' : '加入报价单' }}</button><button v-if="isSelected(row)" class="primary-action" @click="$emit('adopt',row)">{{ adoptedCountry===activeCountry && adoptedRule===row.rule && adoptedCarrier===row.carrier ? '首选' : '设为首选' }}</button></div>
        </article>
      </div>
      <div v-else-if="channelQuery && rows.length" class="empty-rows">当前国家没有匹配“{{ channelSearch.trim() }}”的物流渠道，请更换关键词或清空搜索。</div>
      <div v-else class="empty-rows">{{ activeSummary?.quoteRegions?.length && !activeSummary.selectedQuoteRegion ? '请先选择报价区域，再查看该分区的渠道和价格' : '当前重量和物流属性下暂无可用渠道，请检查财务授权或调整报价条件' }}</div>
      <footer><span>{{ channelQuery ? `匹配 ${filteredRows.length} / ${rows.length} 条渠道` : `共 ${rows.length} 条渠道` }}</span><label>每页 <select v-model.number="pageSize"><option :value="5">5</option><option :value="10">10</option><option :value="20">20</option></select> 条</label><button :disabled="page<=1" @click="page--">上一页</button><b>{{ page }} / {{ pageCount }}</b><button :disabled="page>=pageCount" @click="page++">下一页</button></footer>
    </template>
  </section>
</template>

<style scoped>
.channel-filter{display:flex;align-items:center;gap:12px;flex-wrap:wrap;min-width:0}.channel-search{display:flex;align-items:center;gap:7px;width:300px;max-width:100%;box-sizing:border-box;min-height:36px;padding:0 10px;border:1px solid #d8e1e6;border-radius:7px;color:#7e8b94}.channel-search:focus-within{border-color:#ff9700;box-shadow:0 0 0 2px rgba(255,151,0,.12)}.channel-search input{flex:1;min-width:0;border:0;outline:0;background:transparent;font:inherit;font-size:11px}.channel-search button{height:26px;padding:0 5px;border:0;background:transparent;white-space:nowrap}.channel-match-count{color:#7d8992;font-size:10px;white-space:nowrap}.sort-toolbar{flex-wrap:wrap}
.common-matrix{overflow:hidden;border:1px solid #dfe6eb;border-radius:12px;background:#fff;box-shadow:0 10px 28px rgba(20,34,45,.05);color:#17232d}.common-head{display:flex;align-items:center;justify-content:space-between;gap:20px;padding:20px 22px;border-bottom:1px solid #e6ebef}.common-head p{margin:0 0 4px;color:#d97800;font-size:9px;font-weight:900;letter-spacing:.15em}.common-head h2{margin:0 0 4px;font-size:19px}.common-head span{color:#7d8992;font-size:10px}.common-head>label{display:flex;align-items:center;gap:7px;width:280px;height:36px;padding:0 10px;border:1px solid #d8e1e6;border-radius:7px;color:#7e8b94}.common-head input{min-width:0;flex:1;border:0;outline:0}.country-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:9px;padding:15px 18px;background:#f7f9fb}.country-grid button{display:flex;align-items:center;gap:9px;min-height:62px;padding:10px 12px;border:1px solid #dce4e9;border-radius:8px;background:#fff;text-align:left;cursor:pointer}.country-grid button.active{border-color:#ff9600;background:#fff8eb;box-shadow:0 0 0 2px rgba(255,150,0,.08)}.country-grid i{font-size:23px;font-style:normal}.country-grid span{display:grid;gap:3px;min-width:0}.country-grid b{font-size:12px}.country-grid small{overflow:hidden;color:#83909a;font-size:8px;text-overflow:ellipsis;white-space:nowrap}.country-grid em{margin-left:auto;color:#d87300;font-size:8px;font-style:normal;font-weight:800}.empty-countries,.empty-rows{padding:34px;text-align:center;color:#89959e;font-size:10px}.country-summary{display:grid;grid-template-columns:minmax(210px,1fr) 245px 245px auto;align-items:center;gap:10px;padding:13px 18px;border-top:1px solid #e5eaed;border-bottom:1px solid #e5eaed}.country-title{display:flex;align-items:center;gap:10px}.country-title>b{font-size:16px}.country-title span{padding:5px 9px;border-radius:13px;background:#eaf7ef;color:#188253;font-size:9px;font-weight:800}.metric{display:flex;align-items:center;gap:9px;padding:8px 11px;border-radius:8px}.metric.lowest{background:#fff3df;color:#a65b00}.metric.fastest{background:#eaf6ff;color:#126a9d}.metric>i{width:28px;height:28px;display:grid;place-items:center;border-radius:50%;background:#fff;font-style:normal;font-weight:900}.metric>span{display:grid;grid-template-columns:auto 1fr;gap:1px 8px}.metric small{grid-column:1/-1;font-size:8px}.metric b{font-size:13px}.metric em{align-self:center;overflow:hidden;max-width:135px;font-size:8px;font-style:normal;text-overflow:ellipsis;white-space:nowrap}.country-summary>button{height:35px;padding:0 12px;border:1px solid #4d9b68;border-radius:6px;background:#fff;color:#247442;font-size:9px;font-weight:800}.country-summary>button:disabled{opacity:.4}.table-head,.quote-rows article{display:grid;grid-template-columns:minmax(260px,1.5fr) .7fr repeat(3,.78fr) 80px;align-items:center;gap:12px}.table-head{padding:10px 18px;background:#f5f8fa;color:#71808b;font-size:8px}.table-head span{display:grid;gap:2px}.table-head small{font-size:7px}.quote-rows article{min-height:72px;padding:10px 18px;border-top:1px solid #edf0f2;font-size:10px}.quote-rows article.adopted{background:#fff8e9}.quote-rows article>div{display:grid;gap:3px;min-width:0}.quote-rows article>div b,.quote-rows article>div small{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.quote-rows small{color:#81909b;font-size:8px}.quote-rows article>span{display:grid;gap:2px}.quote-rows article>button{height:31px;border:0;border-radius:6px;background:#17232d;color:#fff;font-size:9px;font-weight:800}.quote-rows article.adopted>button{background:#ff9600;color:#17232d}.common-matrix>footer{display:flex;align-items:center;justify-content:flex-end;gap:8px;padding:11px 18px;border-top:1px solid #e3e9ed;color:#73808a;font-size:9px}.common-matrix>footer>span{margin-right:auto}.common-matrix>footer label{display:flex;align-items:center;gap:5px}.common-matrix>footer select,.common-matrix>footer button{height:28px;border:1px solid #d9e1e6;border-radius:5px;background:#fff;color:#566570;font-size:8px}.common-matrix>footer button:disabled{opacity:.4}@media(max-width:1100px){.country-grid{grid-template-columns:repeat(2,minmax(0,1fr))}.country-summary{grid-template-columns:1fr 1fr}.country-summary>button{justify-self:end}.table-head,.quote-rows article{min-width:900px}.common-matrix{overflow:auto}}@media(max-width:680px){.common-head{align-items:flex-start;flex-direction:column}.common-head>label{width:100%;box-sizing:border-box}.country-grid{grid-template-columns:1fr}.country-summary{grid-template-columns:1fr}.country-summary>button{justify-self:stretch}.common-matrix>footer{min-width:520px}}
.sort-toolbar{display:flex;align-items:center;justify-content:space-between;gap:14px;padding:10px 18px;border-bottom:1px solid #e5eaed;background:#fff}.sort-toolbar>span{display:grid;gap:2px}.sort-toolbar>span b{font-size:10px}.sort-toolbar>span small{color:#8a959d;font-size:8px}.sort-toolbar nav{display:flex;align-items:center;gap:7px}.sort-toolbar button{height:31px;padding:0 12px;border:1px solid #dbe3e8;border-radius:16px;background:#f7f9fa;color:#586773;font-size:9px;font-weight:800;cursor:pointer}.sort-toolbar button:hover{border-color:#efb35a;background:#fff9ee;color:#a75c00}.sort-toolbar button.active{border-color:#ff9700;background:#ff9700;color:#17232d;box-shadow:0 3px 9px rgba(255,151,0,.18)}@media(max-width:680px){.sort-toolbar{align-items:flex-start;flex-direction:column}.sort-toolbar nav{width:100%;overflow-x:auto}.sort-toolbar button{flex:0 0 auto}}
.table-head,.quote-rows article{grid-template-columns:minmax(220px,1.35fr) .58fr repeat(4,.68fr) 118px}
.quote-region-select{display:flex;align-items:center;gap:6px;margin-left:auto;padding:4px 7px;border:1px solid #ffbd63;border-radius:6px;background:#fff8eb;color:#a55a00;font-size:8px;font-weight:800}.quote-region-select select{border:0;background:transparent;color:#9b5300;font-size:9px;font-weight:850;outline:0}
.common-head-actions{display:flex;align-items:center;gap:10px}.common-head-actions label{box-sizing:border-box;display:flex;align-items:center;gap:7px;height:36px;padding:0 10px;border:1px solid #d8e1e6;border-radius:7px;color:#7e8b94;font-size:9px}.common-head-actions .quantity-field{white-space:nowrap}.quantity-field input{width:52px;border:0;border-left:1px solid #e2e7ea;outline:0;text-align:center}.quantity-field span{color:#566570}.common-head-actions .country-search{width:280px}.country-search input{min-width:0;flex:1;border:0;outline:0}.custom-head{color:#c66b00;font-weight:850}.custom-price{margin:-4px;padding:7px 5px;border-radius:6px;background:#fff3df;color:#c66b00}
.quote-rows article.selected{box-shadow:inset 3px 0 #ff9700}.selection-actions{display:grid;gap:4px}.selection-actions button{height:27px;border:0;border-radius:5px;background:#17232d;color:#fff;font-size:8px;font-weight:800;cursor:pointer}.selection-actions button:first-child{background:#ff9700;color:#17232d}.selection-actions .primary-action{height:auto;padding:2px 0;background:none;color:#7d8992}.selection-actions .primary-action:hover{color:#c66b00}
@media(max-width:680px){.common-head-actions{width:100%;align-items:stretch;flex-direction:column}.common-head-actions .country-search,.common-head-actions .quantity-field{width:100%}.quantity-field input{flex:1}}
.country-grid button{cursor:grab;transition:border-color .15s,background .15s,box-shadow .15s,opacity .15s,transform .15s}.country-grid button:active{cursor:grabbing}.country-grid button.dragging{opacity:.42;transform:scale(.98)}.country-grid button.drag-over{border-color:#ff8f00;background:#fff2dc;box-shadow:0 0 0 3px rgba(255,143,0,.15)}.country-grid u{color:#aeb8bf;font-size:13px;line-height:1;text-decoration:none;letter-spacing:-3px}
.channel-name-line{display:flex!important;align-items:center;gap:7px;min-width:0}.channel-name-line>b{min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
</style>
