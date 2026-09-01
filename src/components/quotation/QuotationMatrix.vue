<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { QuotationCountrySummary, QuotationMatrixRow, QuotationPresetSelection } from './types'
import QuoteTaxMeta from './QuoteTaxMeta.vue'
import QuoteTaxLegend from './QuoteTaxLegend.vue'

const DEFAULT_COUNTRIES = ['美国', '英国', '加拿大', '澳大利亚']
const props = defineProps<{
  countries: QuotationCountrySummary[]
  quoteRowsForCountry: (country: string) => QuotationMatrixRow[]
  contextKey: string
  customQuantity: number
  adoptedCountry: string
  adoptedRule: string
  adoptedCarrier: string
  exchangeRate: number
  unitLabel?: string
  variant?: 'specified' | 'template'
  presetSelection?: QuotationPresetSelection[]
  presetVersion?: number
}>()

const emit = defineEmits<{
  'update:customQuantity': [value: number]
  'selectionChange': [rows: QuotationMatrixRow[]]
  adopt: [row: QuotationMatrixRow]
  copy: [rows: QuotationMatrixRow[]]
  presetApplied: [valid: number, missing: number]
  quoteRegionChange: [payload: { country: string; region: string }]
}>()

const selectedCountries = ref<string[]>([])
const selectedChannelKeys = ref<Record<string, string[]>>({})
const showCountryPicker = ref(false)
const countrySearch = ref('')
const channelPickerCountry = ref('')
const channelSearch = ref('')
const channelFilter = ref('系统推荐')
const pendingChannelKeys = ref<string[]>([])
const channelPage = ref(1)
const channelPageSize = 8

function fallbackRowKey(row: Pick<QuotationMatrixRow, 'rule' | 'carrier' | 'transport'>) {
  return `${row.rule}|||${row.carrier}|||${row.transport}`
}
function rowKey(row: Pick<QuotationMatrixRow, 'channelKey' | 'rule' | 'carrier' | 'transport'>) {
  return row.channelKey?.trim() || fallbackRowKey(row)
}
function presetFallbackMatchesRow(preset: QuotationPresetSelection, row: QuotationMatrixRow) {
  return (!preset.quoteRegion || preset.quoteRegion === row.quoteRegion)
    && !!preset.rule && !!preset.carrier && !!preset.transport
    && preset.rule === row.rule
    && preset.carrier === row.carrier
    && preset.transport === row.transport
}
function findPresetRow(preset: QuotationPresetSelection, rows: QuotationMatrixRow[]) {
  const presetChannelKey = preset.channelKey?.trim()
  if (presetChannelKey) {
    const stableMatch = rows.find(row => row.channelKey?.trim() === presetChannelKey && (!preset.quoteRegion || preset.quoteRegion === row.quoteRegion))
    if (stableMatch) return stableMatch
  }
  return rows.find(row => presetFallbackMatchesRow(preset, row))
}
function availableRows(country: string) {
  void props.contextKey
  return props.quoteRowsForCountry(country).filter(row => row.available !== false)
}
function selectedRows(country: string) {
  const keys = new Set(selectedChannelKeys.value[country] || [])
  return availableRows(country).filter(row => keys.has(rowKey(row)))
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
function recommendedRows(country: string) {
  const rows = availableRows(country)
  const fastest = fastestRow(rows)
  const result = [rows[0], fastest, rows[1]].filter((row): row is QuotationMatrixRow => !!row)
  return [...new Map(result.map(row => [rowKey(row), row])).values()].slice(0, 3)
}
function resetDefaultSelection() {
  const names = new Set(props.countries.map(country => country.name))
  selectedCountries.value = DEFAULT_COUNTRIES.filter(country => names.has(country))
  const next: Record<string, string[]> = {}
  selectedCountries.value.forEach(country => {
    const first = availableRows(country)[0]
    next[country] = first ? [rowKey(first)] : []
  })
  selectedChannelKeys.value = next
  closeChannelPicker()
  showCountryPicker.value = false
}

function applyPresetSelection() {
  const presetSelection = props.presetSelection || []
  const knownCountries = new Set(props.countries.map(country => country.name))
  const nextCountries: string[] = []
  const nextChannelKeys: Record<string, string[]> = {}
  let valid = 0
  let missing = 0

  presetSelection.forEach(preset => {
    if (!preset.country || !knownCountries.has(preset.country)) {
      missing += 1
      return
    }
    if (!nextCountries.includes(preset.country)) nextCountries.push(preset.country)
    const match = findPresetRow(preset, availableRows(preset.country))
    if (!match) {
      missing += 1
      return
    }
    const key = rowKey(match)
    const countryKeys = nextChannelKeys[preset.country] || []
    if (!countryKeys.includes(key)) {
      nextChannelKeys[preset.country] = [...countryKeys, key]
      valid += 1
    }
  })

  selectedCountries.value = nextCountries
  selectedChannelKeys.value = nextChannelKeys
  closeChannelPicker()
  showCountryPicker.value = false
  emit('presetApplied', valid, missing)
}

function resetSelectionForMode() {
  if (props.variant === 'template') applyPresetSelection()
  else resetDefaultSelection()
}

watch(
  [() => props.contextKey, () => props.presetVersion, () => props.variant],
  resetSelectionForMode,
  { immediate: true },
)

const allSelectedRows = computed(() => {
  void props.contextKey
  return selectedCountries.value.flatMap(country => selectedRows(country))
})
const selectedRowsSignature = computed(() => allSelectedRows.value.map(row => [
  row.country,
  row.quoteRegion,
  row.rule,
  row.carrier,
  row.transport,
  row.quote1,
  row.quote2,
  row.quote3,
  row.quoteCustom,
].join('|')).join('||'))
watch(selectedRowsSignature, () => emit('selectionChange', allSelectedRows.value), { immediate: true })

const countryOptions = computed(() => {
  const query = countrySearch.value.trim().toLowerCase()
  return props.countries.filter(country => !selectedCountries.value.includes(country.name)
    && (!query || `${country.name} ${country.code} ${country.continent}`.toLowerCase().includes(query)))
})
const pickerRows = computed(() => availableRows(channelPickerCountry.value))
const recommendationKeys = computed(() => new Set(recommendedRows(channelPickerCountry.value).map(rowKey)))
const lowestKey = computed(() => pickerRows.value[0] ? rowKey(pickerRows.value[0]) : '')
const fastestKey = computed(() => {
  const row = fastestRow(pickerRows.value)
  return row ? rowKey(row) : ''
})
const filteredPickerRows = computed(() => {
  const query = channelSearch.value.trim().toLowerCase()
  return pickerRows.value.filter(row => {
    const matchesSearch = !query || `${row.transport} ${row.carrier} ${row.rule}`.toLowerCase().includes(query)
    if (!matchesSearch) return false
    if (channelFilter.value === '系统推荐') return recommendationKeys.value.has(rowKey(row))
    if (channelFilter.value === '最低价') return rowKey(row) === lowestKey.value
    if (channelFilter.value === '最快') return rowKey(row) === fastestKey.value
    if (channelFilter.value === '普货' || channelFilter.value === '带电') return `${row.transport} ${row.rule}`.includes(channelFilter.value)
    return true
  })
})
const channelPageCount = computed(() => Math.max(1, Math.ceil(filteredPickerRows.value.length / channelPageSize)))
const pagedPickerRows = computed(() => filteredPickerRows.value.slice((channelPage.value - 1) * channelPageSize, channelPage.value * channelPageSize))

watch([channelSearch, channelFilter], () => { channelPage.value = 1 })

function addCountry(country: string) {
  if (!country || selectedCountries.value.includes(country)) return
  selectedCountries.value = [...selectedCountries.value, country]
  selectedChannelKeys.value = { ...selectedChannelKeys.value, [country]: [] }
  showCountryPicker.value = false
  countrySearch.value = ''
  openChannelPicker(country)
}
function removeCountry(country: string) {
  if (props.variant !== 'template' && DEFAULT_COUNTRIES.includes(country)) return
  selectedCountries.value = selectedCountries.value.filter(item => item !== country)
  const next = { ...selectedChannelKeys.value }
  delete next[country]
  selectedChannelKeys.value = next
}
function openChannelPicker(country: string) {
  channelPickerCountry.value = country
  channelSearch.value = ''
  channelFilter.value = '系统推荐'
  pendingChannelKeys.value = []
  channelPage.value = 1
}
function closeChannelPicker() {
  channelPickerCountry.value = ''
  pendingChannelKeys.value = []
}
function isAlreadyAdded(row: QuotationMatrixRow) {
  return (selectedChannelKeys.value[channelPickerCountry.value] || []).includes(rowKey(row))
}
function togglePending(row: QuotationMatrixRow) {
  if (isAlreadyAdded(row)) return
  const key = rowKey(row)
  pendingChannelKeys.value = pendingChannelKeys.value.includes(key)
    ? pendingChannelKeys.value.filter(item => item !== key)
    : [...pendingChannelKeys.value, key]
}
function selectRecommended() {
  pendingChannelKeys.value = recommendedRows(channelPickerCountry.value)
    .filter(row => !isAlreadyAdded(row))
    .map(rowKey)
}
function addPendingChannels() {
  const country = channelPickerCountry.value
  const current = selectedChannelKeys.value[country] || []
  selectedChannelKeys.value = { ...selectedChannelKeys.value, [country]: [...new Set([...current, ...pendingChannelKeys.value])] }
  closeChannelPicker()
}
function removeChannel(country: string, row: QuotationMatrixRow) {
  selectedChannelKeys.value = {
    ...selectedChannelKeys.value,
    [country]: (selectedChannelKeys.value[country] || []).filter(key => key !== rowKey(row)),
  }
}
function reason(row: QuotationMatrixRow) {
  const key = rowKey(row)
  if (key === lowestKey.value && key === fastestKey.value) return '综合推荐'
  if (key === lowestKey.value) return '最低价'
  if (key === fastestKey.value) return '最快'
  if (recommendationKeys.value.has(key)) return '系统推荐'
  return ''
}
function countrySummary(country: string) { return props.countries.find(item => item.name === country) }
function countryFlag(code: string) {
  if (!/^[A-Z]{2}$/i.test(code)) return '🌐'
  return [...code.toUpperCase()].map(letter => String.fromCodePoint(127397 + letter.charCodeAt(0))).join('')
}
function formatUsd(value: number | null) { return value == null ? '—' : `$${value.toFixed(2)}` }
function formatCny(value: number | null) { return value == null ? '—' : `¥${(value * props.exchangeRate).toFixed(2)}` }
</script>

<template>
  <section class="specified-card">
    <header class="specified-head">
      <div v-if="variant === 'template'"><p>MODE C · TEMPLATE QUOTATION MATRIX</p><h2>报价模板应用清单</h2><span>已按个人模板带出国家与渠道；本次可临时增删，不会改动原模板。</span></div>
      <div v-else><p>MODE B · SPECIFIED QUOTATION</p><h2>指定报价清单</h2><span>美、英、加、澳默认展示，也可按客户要求增加国家和渠道。</span></div>
      <div class="head-actions"><label>自定义数量 <input :value="customQuantity" type="number" min="1" @input="$emit('update:customQuantity',Number(($event.target as HTMLInputElement).value))"> {{ unitLabel || '件' }}</label><button @click="showCountryPicker=true">＋ 添加国家</button></div>
    </header>
    <QuoteTaxLegend />
    <div class="country-card-grid">
      <article v-for="country in selectedCountries" :key="country" class="country-card">
        <header>
          <div class="country-name"><i>{{ countryFlag(countrySummary(country)?.code || '') }}</i><b>{{ country }}</b><em>{{ countrySummary(country)?.code }}</em><span>{{ selectedRows(country).length }} 条已选 · {{ availableRows(country).length }} 条可用</span></div>
          <div class="country-actions"><label v-if="countrySummary(country)?.quoteRegions?.length" class="quote-region-select">报价区域<select :value="countrySummary(country)?.selectedQuoteRegion" @change="$emit('quoteRegionChange',{ country, region:($event.target as HTMLSelectElement).value })"><option disabled value="">请选择分区</option><option v-for="region in countrySummary(country)?.quoteRegions" :key="region" :value="region">{{ region }}</option></select></label><button v-if="variant === 'template' || !DEFAULT_COUNTRIES.includes(country)" class="remove-country" @click="removeCountry(country)">移除国家</button><button @click="openChannelPicker(country)">＋ 添加渠道</button></div>
        </header>
        <div v-if="availableRows(country).length" class="country-metrics"><span>最低 <b>{{ formatUsd(availableRows(country)[0]?.quote1 ?? null) }}</b> · {{ availableRows(country)[0]?.transport }}</span><span>最快 <b>{{ fastestRow(availableRows(country))?.eta }}</b> · {{ fastestRow(availableRows(country))?.transport }}</span></div>
        <div class="quote-head"><span>物流渠道</span><span>预计时效</span><span>1{{ unitLabel || '件' }}报价</span><span>2{{ unitLabel || '件' }}报价</span><span>3{{ unitLabel || '件' }}报价</span><span class="custom-quote-head">{{ customQuantity }}{{ unitLabel || '件' }}报价<small>自定义</small></span><span>操作</span></div>
        <div v-if="selectedRows(country).length" class="selected-channels">
          <section v-for="row in selectedRows(country)" :key="rowKey(row)" :class="{ adopted:adoptedCountry===country && adoptedRule===row.rule && adoptedCarrier===row.carrier }">
            <div><span class="channel-name-line"><b>{{ row.transport }}</b><QuoteTaxMeta :row="row" /></span><small>{{ row.carrier }} · {{ row.rule }}</small></div><b>{{ row.eta }}</b>
            <span><b>{{ formatUsd(row.quote1) }}</b><small>{{ formatCny(row.quote1) }}</small><QuoteTaxMeta :row="row" mode="price" tier="1" /></span><span><b>{{ formatUsd(row.quote2) }}</b><small>{{ formatCny(row.quote2) }}</small><QuoteTaxMeta :row="row" mode="price" tier="2" /></span><span><b>{{ formatUsd(row.quote3) }}</b><small>{{ formatCny(row.quote3) }}</small><QuoteTaxMeta :row="row" mode="price" tier="3" /></span><span class="custom-price"><b>{{ formatUsd(row.quoteCustom) }}</b><small>{{ formatCny(row.quoteCustom) }}</small><QuoteTaxMeta :row="row" mode="price" tier="custom" /></span>
            <div class="row-actions"><button @click="$emit('adopt',row)">{{ adoptedCountry===country && adoptedRule===row.rule && adoptedCarrier===row.carrier ? '首选' : '设为首选' }}</button><button @click="removeChannel(country,row)">移出报价单</button></div>
          </section>
        </div>
        <button v-else class="empty-channel" @click="openChannelPicker(country)">{{ availableRows(country).length ? '＋ 添加该国家的指定渠道' : '当前条件暂无可用渠道' }}</button>
      </article>
    </div>

    <button class="add-country-empty" @click="showCountryPicker=true"><b>{{ variant === 'template' ? '本次还需要临时增加其他国家？' : '还需要报价其他国家？' }}</b><span>＋ 添加其他国家</span></button>
    <footer><span v-if="variant === 'template'">本次应用 {{ selectedCountries.length }} 个国家 · {{ allSelectedRows.length }} 条模板渠道（临时调整不会修改模板）</span><span v-else>共 {{ selectedCountries.length }} 个国家 · {{ allSelectedRows.length }} 条指定渠道</span><button class="copy" :disabled="!allSelectedRows.length" @click="$emit('copy',allSelectedRows)">▦ 复制表格数据</button></footer>
  </section>

  <div v-if="showCountryPicker" class="dialog-mask" @click.self="showCountryPicker=false">
    <section class="country-dialog"><header><div><small>ADD COUNTRY</small><h2>添加报价国家</h2><p>搜索国家名称、代码或所属大洲。</p></div><button @click="showCountryPicker=false">×</button></header><label class="dialog-search">⌕<input v-model="countrySearch" placeholder="搜索国家、代码或大洲"></label><div class="country-option-list"><button v-for="country in countryOptions" :key="country.name" @click="addCountry(country.name)"><span><b>{{ countryFlag(country.code) }} {{ country.name }}</b><small>{{ country.code }} · {{ country.continent }}</small></span><em>{{ country.channelCount ? `${country.channelCount}条可用渠道` : '暂无渠道' }}</em></button><p v-if="!countryOptions.length">没有可添加的国家</p></div></section>
  </div>

  <div v-if="channelPickerCountry" class="dialog-mask" @click.self="closeChannelPicker">
    <section class="channel-dialog">
      <header><div><h2>为{{ channelPickerCountry }}添加物流渠道</h2><p>可搜索并批量选择渠道，价格按当前商品与重量自动试算。</p></div><button @click="closeChannelPicker">×</button></header>
      <label class="dialog-search">⌕<input v-model="channelSearch" placeholder="搜索渠道名称、物流商或渠道代码"></label>
      <div class="channel-tools"><nav><button v-for="filter in ['全部','系统推荐','最低价','最快','普货','带电']" :key="filter" :class="{ active:channelFilter===filter }" @click="channelFilter=filter">{{ filter }}</button></nav><button class="recommended-add" @click="selectRecommended">＋ 添加系统推荐3条</button></div>
      <div class="picker-head"><span></span><span>物流渠道</span><span>物流商</span><span>预计时效</span><span>1{{ unitLabel || '件' }}报价</span><span>2{{ unitLabel || '件' }}报价</span><span>3{{ unitLabel || '件' }}报价</span><span class="custom-quote-head">{{ customQuantity }}{{ unitLabel || '件' }}报价<small>自定义</small></span><span>推荐理由</span></div>
      <div class="picker-list"><label v-for="row in pagedPickerRows" :key="rowKey(row)" :class="{ selected:pendingChannelKeys.includes(rowKey(row)), disabled:isAlreadyAdded(row) }"><input type="checkbox" :checked="pendingChannelKeys.includes(rowKey(row))" :disabled="isAlreadyAdded(row)" @change="togglePending(row)"><span><span class="channel-name-line"><b>{{ row.transport }}</b><QuoteTaxMeta :row="row" /></span><small>{{ row.rule }}</small></span><b>{{ row.carrier }}</b><b>{{ row.eta }}</b><span><b>{{ formatUsd(row.quote1) }}</b><small>{{ formatCny(row.quote1) }}</small></span><span><b>{{ formatUsd(row.quote2) }}</b><small>{{ formatCny(row.quote2) }}</small></span><span><b>{{ formatUsd(row.quote3) }}</b><small>{{ formatCny(row.quote3) }}</small></span><span class="custom-price"><b>{{ formatUsd(row.quoteCustom) }}</b><small>{{ formatCny(row.quoteCustom) }}</small></span><em v-if="isAlreadyAdded(row)" class="added">已添加</em><em v-else-if="reason(row)">{{ reason(row) }}</em><i v-else>—</i></label><p v-if="!pagedPickerRows.length">没有匹配的可用渠道</p></div>
      <div class="picker-pagination"><span>共 {{ filteredPickerRows.length }} 条</span><button :disabled="channelPage<=1" @click="channelPage--">上一页</button><b>{{ channelPage }} / {{ channelPageCount }}</b><button :disabled="channelPage>=channelPageCount" @click="channelPage++">下一页</button></div>
      <footer><b>已选择 <em>{{ pendingChannelKeys.length }}</em> 条渠道</b><span><button @click="closeChannelPicker">取消</button><button class="batch-add" :disabled="!pendingChannelKeys.length" @click="addPendingChannels">批量添加渠道（{{ pendingChannelKeys.length }}）</button></span></footer>
    </section>
  </div>
</template>

<style scoped>
.specified-card{overflow:hidden;border:1px solid #dfe6eb;border-radius:12px;background:#fff;box-shadow:0 10px 28px rgba(20,34,45,.05);color:#17232d}.specified-head{display:flex;align-items:center;justify-content:space-between;padding:20px 22px;border-bottom:1px solid #e6ebef}.specified-head p{margin:0 0 4px;color:#d97800;font-size:9px;font-weight:900;letter-spacing:.15em}.specified-head h2{margin:0 0 4px;font-size:19px}.specified-head span{color:#7d8992;font-size:10px}.head-actions{display:flex;align-items:center;gap:10px}.head-actions label{color:#65727c;font-size:10px}.head-actions input{width:58px;height:34px;border:1px solid #d7e0e5;border-radius:6px;text-align:center}.head-actions button,.country-actions button{height:35px;padding:0 13px;border:1px solid #f3a52f;border-radius:6px;background:#fff;color:#b86400;font-size:10px;font-weight:850;cursor:pointer}.country-card-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px;padding:16px;background:#f7f9fb}.country-card{min-width:0;overflow:hidden;border:1px solid #dce4e9;border-radius:9px;background:#fff}.country-card>header{display:flex;align-items:center;justify-content:space-between;gap:10px;padding:12px 14px;border-bottom:1px solid #e5eaed}.country-name{display:flex;align-items:center;gap:7px;min-width:0}.country-name>i{font-size:21px;font-style:normal}.country-name>b{font-size:14px}.country-name>em{padding:3px 6px;border-radius:5px;background:#eef2f5;color:#667580;font-size:8px;font-style:normal}.country-name>span{color:#188856;font-size:9px;font-weight:800}.country-actions{display:flex;gap:6px}.country-actions .remove-country{border-color:#e5e9ec;color:#8a959d}.country-metrics{display:grid;grid-template-columns:1fr 1fr;gap:6px;padding:7px 12px;background:#fff9ef;color:#596873;font-size:8px}.country-metrics span{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.country-metrics b{color:#c56800}.quote-head,.selected-channels section{display:grid;grid-template-columns:minmax(125px,1.45fr) .62fr repeat(4,.7fr) 72px;align-items:center;gap:7px}.quote-head{padding:8px 12px;background:#f7f9fa;color:#79868f;font-size:8px}.quote-head>span{min-width:0}.custom-quote-head{display:grid;gap:1px;color:#c66b00;font-weight:850}.custom-quote-head small{color:#a8712c;font-size:6px;font-weight:700}.selected-channels section{min-height:56px;padding:8px 12px;border-top:1px solid #edf0f2;font-size:9px}.selected-channels section.adopted{background:#fff8e9}.selected-channels section>div:first-child{display:grid;min-width:0;gap:3px}.selected-channels section>div:first-child b,.selected-channels section>div:first-child small{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.selected-channels small{color:#83909a;font-size:7px}.selected-channels section>span{display:grid;gap:2px}.selected-channels .custom-price{margin:-4px;padding:6px 4px;border-radius:5px;background:#fff3df;color:#c66b00}.row-actions{display:flex;align-items:center;gap:4px}.row-actions button{border:0;background:none;color:#b75042;font-size:8px;cursor:pointer}.row-actions button:first-child{padding:5px 7px;border-radius:5px;background:#17232d;color:#fff}.adopted .row-actions button:first-child{background:#ff9900;color:#17212b}.empty-channel{width:calc(100% - 24px);height:46px;margin:10px 12px;border:1px dashed #d7dfe4;border-radius:6px;background:#fafbfc;color:#9a650e;font-size:9px;cursor:pointer}.add-country-empty{display:flex;align-items:center;justify-content:center;gap:18px;width:calc(100% - 32px);height:56px;margin:0 16px 16px;border:1px dashed #d7dfe4;border-radius:8px;background:#fbfcfd;color:#263641;cursor:pointer}.add-country-empty b{font-size:11px}.add-country-empty span{padding:7px 10px;border:1px solid #f1ae46;border-radius:6px;color:#b86500;font-size:9px}.specified-card>footer{display:flex;align-items:center;justify-content:flex-end;gap:9px;padding:14px 18px;border-top:1px solid #e4eaee}.specified-card>footer>span{margin-right:auto;color:#62707b;font-size:10px}.specified-card>footer button{height:36px;padding:0 15px;border-radius:6px;font-size:10px;font-weight:850}.specified-card>footer .copy{border:1px solid #53a66f;background:#fff;color:#227644}.specified-card>footer button:disabled{opacity:.45}.dialog-mask{position:fixed;z-index:120;inset:0;display:grid;place-items:center;padding:22px;background:rgba(17,27,36,.48);backdrop-filter:blur(3px)}.country-dialog,.channel-dialog{position:relative;overflow:hidden;border-radius:12px;background:#fff;box-shadow:0 25px 70px rgba(8,18,27,.32)}.country-dialog{width:min(760px,92vw);padding:22px}.country-dialog>header,.channel-dialog>header{display:flex;align-items:flex-start;justify-content:space-between}.country-dialog h2,.channel-dialog h2{margin:0 0 5px;font-size:20px}.country-dialog header small{color:#d27600;font-size:8px;font-weight:900;letter-spacing:.16em}.country-dialog header p,.channel-dialog header p{margin:0;color:#7b8892;font-size:10px}.country-dialog header>button,.channel-dialog header>button{border:0;background:none;color:#50606d;font-size:23px;cursor:pointer}.dialog-search{display:flex;align-items:center;gap:8px;height:39px;margin:15px 0 12px;padding:0 11px;border:1px solid #d6dfe4;border-radius:7px;color:#7f8b95}.dialog-search input{min-width:0;flex:1;border:0;outline:0}.country-option-list{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:7px;max-height:54vh;overflow:auto}.country-option-list button{display:flex;align-items:center;justify-content:space-between;padding:10px;border:1px solid #e0e6ea;border-radius:7px;background:#fafbfc;text-align:left;cursor:pointer}.country-option-list button:hover{border-color:#f1a22b;background:#fff8ec}.country-option-list span{display:grid;gap:3px}.country-option-list small{color:#89949d}.country-option-list em{color:#278257;font-size:8px;font-style:normal}.channel-dialog{width:min(1240px,98vw)}.channel-dialog>header{padding:21px 24px 0}.channel-dialog>.dialog-search{margin:15px 24px 10px}.channel-tools{display:flex;align-items:center;justify-content:space-between;padding:0 24px 10px}.channel-tools nav{display:flex;gap:6px}.channel-tools button{height:30px;padding:0 11px;border:0;border-radius:15px;background:#eef2f5;color:#52616d;font-size:9px;font-weight:800;cursor:pointer}.channel-tools button.active{background:#ff9900;color:#fff}.channel-tools .recommended-add{border:1px solid #efaa40;border-radius:5px;background:#fff;color:#b46500}.picker-head,.picker-list label{display:grid;grid-template-columns:24px minmax(170px,1.4fr) .75fr .62fr repeat(4,.68fr) .7fr;align-items:center;gap:7px}.picker-head{padding:9px 24px;background:#f4f7f9;color:#687681;font-size:8px}.picker-head .custom-quote-head small{display:block}.picker-list{height:360px;overflow:auto}.picker-list label{min-height:44px;padding:7px 24px;border-bottom:1px solid #edf0f2;font-size:9px;cursor:pointer}.picker-list label.selected{background:#fff8e9}.picker-list label.disabled{background:#f5f6f7;color:#9ba4aa;cursor:not-allowed}.picker-list input{width:14px;height:14px;accent-color:#ff9900}.picker-list label>span{display:grid;min-width:0;gap:2px}.picker-list label>span b,.picker-list label>span small{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.picker-list label>span small{color:#84919a;font-size:7px}.picker-list .custom-price{margin:-3px;padding:5px 4px;border-radius:5px;background:#fff3df;color:#c66b00}.picker-list em{justify-self:start;padding:4px 6px;border-radius:10px;background:#e9f7ef;color:#208554;font-size:8px;font-style:normal;font-weight:800}.picker-list em.added{background:#e9edf0;color:#6e7a83}.picker-list i{color:#adb5bb;font-style:normal}.picker-list>p{padding:50px;text-align:center;color:#87939c}.picker-pagination{display:flex;align-items:center;justify-content:flex-end;gap:7px;padding:9px 24px;border-top:1px solid #e8ecef;color:#7a8790;font-size:9px}.picker-pagination>span{margin-right:auto}.picker-pagination button{height:26px;border:1px solid #dce2e6;border-radius:5px;background:#fff;color:#52616c;font-size:8px}.picker-pagination button:disabled{opacity:.4}.channel-dialog>footer{display:flex;align-items:center;justify-content:space-between;padding:14px 24px;border-top:1px solid #e1e7eb}.channel-dialog>footer>b{font-size:11px}.channel-dialog>footer>b em{color:#ed7900;font-size:15px;font-style:normal}.channel-dialog>footer>span{display:flex;gap:8px}.channel-dialog>footer button{height:38px;padding:0 18px;border:1px solid #d8e0e5;border-radius:6px;background:#fff;font-size:10px;font-weight:800}.channel-dialog>footer .batch-add{border:0;background:#ff8800;color:#fff}.channel-dialog>footer .batch-add:disabled{opacity:.4}@media(max-width:1000px){.country-card-grid{grid-template-columns:1fr}.picker-head,.picker-list label{min-width:1040px;grid-template-columns:24px minmax(170px,1.4fr) .75fr .62fr repeat(4,.68fr) .7fr}.channel-dialog{overflow:auto}}@media(max-width:680px){.specified-head{align-items:flex-start;flex-direction:column;gap:12px}.head-actions{width:100%;justify-content:space-between}.quote-head{display:none}.selected-channels section{grid-template-columns:1fr 1fr 1fr 1fr}.selected-channels section>div:first-child{grid-column:1/-1}.row-actions{grid-column:1/-1;justify-content:flex-end}.country-name>span,.country-metrics{display:none}.country-option-list{grid-template-columns:1fr}.channel-dialog{width:96vw}.picker-head,.picker-list label{min-width:1040px}.channel-tools{align-items:flex-start;gap:8px;flex-direction:column}.channel-tools nav{flex-wrap:wrap}}
@media(min-width:1001px){.country-card-grid{grid-template-columns:1fr}.country-card-grid:has(.country-card:nth-child(2)){grid-template-columns:1fr}}
.quote-region-select{display:flex;align-items:center;gap:5px;padding:4px 7px;border:1px solid #ffbd63;border-radius:6px;background:#fff8eb;color:#a55a00;font-size:8px;font-weight:800;white-space:nowrap}.quote-region-select select{border:0;background:transparent;color:#9b5300;font-size:9px;font-weight:850;outline:0}
.channel-name-line{display:flex!important;align-items:center;gap:7px;min-width:0}.channel-name-line>b{min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
</style>
