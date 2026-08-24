<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { QuotationMatrixRow } from './types'
import QuoteTaxMeta from './QuoteTaxMeta.vue'
import QuoteTaxLegend from './QuoteTaxLegend.vue'

const props = withDefaults(defineProps<{
  rows: QuotationMatrixRow[]
  matrixModeLabel: string
  customerName: string
  productName: string
  sku: string
  customerGrade: string
  taxCustomerType: 'A' | 'B'
  coefficient: number
  customQuantity: number
  unitLabel: string
  exchangeRate: number
  primaryCountry: string
  primaryCarrier: string
  primaryRule: string
  primaryCnyPrice: number
  primaryUsdPrice: number
  blockReason?: string
  saving?: boolean
  validationIssues?: Array<{ key: string; label: string; message: string }>
}>(), { blockReason: '', saving: false, validationIssues: () => [] })

const emit = defineEmits<{ save: []; copy: [rows: QuotationMatrixRow[]]; locateIssue: [key: string] }>()
const expandedCountries = ref(new Set<string>())

const groups = computed(() => {
  const map = new Map<string, QuotationMatrixRow[]>()
  props.rows.forEach(row => map.set(row.country, [...(map.get(row.country) || []), row]))
  return [...map.entries()]
    .map(([country, rows]) => ({
      country,
      rows: [...rows].sort((a, b) => Number(isPrimary(b)) - Number(isPrimary(a))),
      hasPrimary: rows.some(isPrimary),
    }))
    .sort((a, b) => Number(b.hasPrimary) - Number(a.hasPrimary))
})
const countryCount = computed(() => groups.value.length)
const hasQuoteRows = computed(() => props.rows.length > 0)
const previewStatus = computed(() => {
  if (props.validationIssues.length) return `暂时无法保存，还需完成 ${props.validationIssues.length} 项`
  if (!hasQuoteRows.value) return '请先选择报价渠道'
  return '报价方案已准备完成'
})
const footerStatus = computed(() => {
  if (props.validationIssues.length) return `缺少 ${props.validationIssues.length} 项必填内容，请按上方提示补充`
  if (!hasQuoteRows.value) return '请先在上方报价矩阵中加入至少一条渠道'
  return '所有已选渠道均已完成报价计算，可以保存'
})
const quoteRange = computed(() => {
  const prices = props.rows.map(row => row.quote1).filter((value): value is number => value != null && Number.isFinite(value)).sort((a, b) => a - b)
  if (!prices.length) return '—'
  return prices.length === 1 ? `$${prices[0].toFixed(2)}` : `$${prices[0].toFixed(2)}～$${prices[prices.length - 1].toFixed(2)}`
})
const allExpanded = computed(() => groups.value.length > 0 && groups.value.every(group => expandedCountries.value.has(group.country)))

watch(groups, value => {
  const valid = new Set(value.map(group => group.country))
  const next = new Set([...expandedCountries.value].filter(country => valid.has(country)))
  const primaryGroup = value.find(group => group.hasPrimary)
  if (primaryGroup) next.add(primaryGroup.country)
  else if (!next.size && value[0]) next.add(value[0].country)
  expandedCountries.value = next
}, { immediate: true })

function toggleCountry(country: string) {
  const next = new Set(expandedCountries.value)
  if (next.has(country)) next.delete(country)
  else next.add(country)
  expandedCountries.value = next
}
function toggleAll() {
  expandedCountries.value = allExpanded.value ? new Set() : new Set(groups.value.map(group => group.country))
}
function formatUsd(value: number | null | undefined) { return value == null ? '—' : `$${value.toFixed(2)}` }
function formatCny(value: number | null | undefined) { return value == null || props.exchangeRate <= 0 ? '—' : `¥${(value * props.exchangeRate).toFixed(2)}` }
function rowKey(row: QuotationMatrixRow) { return `${row.country}|${row.quoteRegion || ''}|${row.channelKey || ''}|${row.rule}|${row.carrier}|${row.transport}` }
function isPrimary(row: QuotationMatrixRow) { return row.country === props.primaryCountry && row.rule === props.primaryRule && row.carrier === props.primaryCarrier }
</script>

<template>
  <section class="quote-preview" aria-labelledby="quotation-preview-title">
    <header class="preview-head">
      <div><p>STEP 04 · QUOTATION PREVIEW</p><h2 id="quotation-preview-title">报价单预览与保存</h2><span>核对本次报价包含的全部国家与渠道，确认后生成正式报价记录</span></div>
      <em :class="{ warning:blockReason || !hasQuoteRows }"><i></i>{{ previewStatus }}</em>
    </header>

    <div class="preview-kpis">
      <article><i>▤</i><span><b>1</b><small>张报价单</small></span></article>
      <article><i>◎</i><span><b>{{ countryCount }}</b><small>个国家</small></span></article>
      <article><i>⌘</i><span><b>{{ rows.length }}</b><small>条渠道</small></span></article>
      <article class="range"><i>$</i><span><small>1{{ unitLabel }}报价范围</small><b>{{ quoteRange }}</b></span></article>
    </div>

    <div class="preview-info">
      <section>
        <h3>报价基本信息</h3>
        <dl>
          <div><dt>客户</dt><dd>{{ customerName || '待填写' }}</dd></div>
          <div><dt>商品</dt><dd>{{ productName || '待查询' }}</dd></div>
          <div><dt>SKU</dt><dd>{{ sku || '—' }}</dd></div>
          <div><dt>报价模式</dt><dd>{{ matrixModeLabel }}</dd></div>
          <div><dt>客户等级</dt><dd>{{ customerGrade }}级客户 × {{ coefficient.toFixed(2) }}</dd></div>
          <div><dt>税费客户类型</dt><dd>{{ taxCustomerType === 'A' ? 'A类 · 固定/单' : 'B类 · 按件' }}</dd></div>
          <div><dt>自定义数量</dt><dd>{{ Math.max(1, customQuantity || 1) }}{{ unitLabel }}</dd></div>
        </dl>
      </section>
    </div>

    <div class="detail-title"><div><h3>国家与渠道报价明细</h3><span>按国家分组核对已选渠道及各数量报价</span></div><button v-if="groups.length" @click="toggleAll">{{ allExpanded ? '收起全部' : '展开全部' }}</button></div>
    <QuoteTaxLegend v-if="groups.length" />
    <div v-if="groups.length" class="country-groups">
      <section v-for="group in groups" :key="group.country" :class="{ open:expandedCountries.has(group.country) }">
        <button class="country-row" @click="toggleCountry(group.country)"><b>{{ group.country }} <i v-if="group.hasPrimary">整单首选</i></b><span>{{ group.rows.length }} 条渠道</span><em>{{ expandedCountries.has(group.country) ? '已展开' : '展开' }}⌄</em></button>
        <template v-if="expandedCountries.has(group.country)">
          <div class="quote-head"><span>物流渠道 / 服务商</span><span>预计时效</span><span>1{{ unitLabel }}</span><span>2{{ unitLabel }}</span><span>3{{ unitLabel }}</span><span>{{ Math.max(1, customQuantity || 1) }}{{ unitLabel }}</span></div>
          <article v-for="row in group.rows" :key="rowKey(row)" :class="{ primary:isPrimary(row) }">
            <span><span class="channel-name-line"><b>{{ row.transport }}</b><QuoteTaxMeta :row="row" /></span><small>{{ row.carrier }} · {{ row.rule }}<template v-if="row.quoteRegion"> · {{ row.quoteRegion }}</template></small><em v-if="isPrimary(row)">整单首选</em></span><strong>{{ row.eta }}</strong>
            <span><b>{{ formatUsd(row.quote1) }}</b><small>{{ formatCny(row.quote1) }}</small><QuoteTaxMeta :row="row" mode="price" tier="1" /></span><span><b>{{ formatUsd(row.quote2) }}</b><small>{{ formatCny(row.quote2) }}</small><QuoteTaxMeta :row="row" mode="price" tier="2" /></span><span><b>{{ formatUsd(row.quote3) }}</b><small>{{ formatCny(row.quote3) }}</small><QuoteTaxMeta :row="row" mode="price" tier="3" /></span><span class="custom"><b>{{ formatUsd(row.quoteCustom) }}</b><small>{{ formatCny(row.quoteCustom) }}</small><QuoteTaxMeta :row="row" mode="price" tier="custom" /></span>
          </article>
        </template>
      </section>
    </div>
    <div v-else class="empty">请先在上方报价矩阵中选择需要保存的国家与渠道</div>

    <section v-if="validationIssues.length" class="validation-summary" aria-live="polite">
      <header><span><i>!</i><b>暂时无法保存报价</b></span><em>请完成以下 {{ validationIssues.length }} 项必填内容</em></header>
      <div>
        <button v-for="(issue,index) in validationIssues" :key="issue.key" type="button" @click="emit('locateIssue',issue.key)">
          <i>{{ index + 1 }}</i><span><b>{{ issue.label }}</b><small>{{ issue.message }}</small></span><em>{{ issue.key === 'taxPolicy' ? '查看提示' : '去填写' }} →</em>
        </button>
      </div>
    </section>

    <footer><span :class="{ warning:blockReason || !hasQuoteRows }"><i></i>{{ footerStatus }}</span><div><button class="outline" :disabled="!rows.length || saving" @click="emit('copy',rows)">复制报价数据</button><button class="dark" :disabled="!rows.length || saving" @click="toggleAll">{{ allExpanded ? '收起报价单' : '查看完整报价单' }}</button><button class="save" :disabled="!!blockReason || !hasQuoteRows || saving" @click="emit('save')">{{ saving ? '正在校验物流版本…' : `保存 1 张报价单 · ${countryCount}国${rows.length}渠道` }}</button></div></footer>
  </section>
</template>

<style scoped>
.quote-preview{overflow:hidden;border:1px solid #dfe6eb;border-top:3px solid #ff9700;border-radius:13px;background:#fff;box-shadow:0 13px 32px rgba(20,34,45,.07);color:#17232d}.preview-head{display:flex;align-items:center;justify-content:space-between;gap:24px;padding:20px 22px;border-bottom:1px solid #e5eaee}.preview-head p{margin:0 0 4px;color:#d87500;font-size:9px;font-weight:900;letter-spacing:.15em}.preview-head h2{margin:0 0 5px;font-size:21px}.preview-head span{color:#7c8993;font-size:10px}.preview-head>em{display:flex;align-items:center;gap:7px;color:#188253;font-size:10px;font-style:normal}.preview-head>em i,.quote-preview footer>span i{width:8px;height:8px;border-radius:50%;background:#25ad6c}.preview-head>em.warning,.quote-preview footer>span.warning{color:#b16a00}.preview-head>em.warning i,.quote-preview footer>span.warning i{background:#e8a31d}.preview-kpis{display:grid;grid-template-columns:repeat(4,1fr);gap:11px;padding:17px 22px;background:#f6f8fa}.preview-kpis article{display:flex;align-items:center;gap:12px;min-height:55px;padding:10px 13px;border:1px solid #e2e8ec;border-radius:9px;background:#fff}.preview-kpis article>i{width:34px;height:34px;display:grid;place-items:center;border-radius:50%;background:#eef2ff;color:#3e61d4;font-style:normal;font-weight:900}.preview-kpis span{display:grid;gap:2px}.preview-kpis b{font-size:18px}.preview-kpis small{color:#7e8a94;font-size:9px}.preview-kpis .range>i{background:#fff0d7;color:#d47400}.preview-kpis .range small{order:-1}.preview-kpis .range b{font-size:15px}.preview-info{padding:18px 22px}.preview-info>section{padding:15px 17px;border:1px solid #e1e7eb;border-radius:9px}.preview-info h3,.detail-title h3{margin:0;font-size:13px}.preview-info dl{display:grid;grid-template-columns:repeat(3,1fr);gap:10px 22px;margin:14px 0 0}.preview-info dl div{display:flex;gap:8px}.preview-info dt{min-width:60px;color:#81909a;font-size:9px}.preview-info dd{margin:0;font-size:10px;font-weight:750}.detail-title{display:flex;align-items:center;justify-content:space-between;padding:0 22px 11px}.detail-title>div{display:grid;gap:4px}.detail-title span{color:#84909a;font-size:9px}.detail-title button{height:30px;border:1px solid #dce3e8;border-radius:6px;background:#fff;color:#52616c;font-size:9px}.country-groups{margin:0 22px;border:1px solid #dfe5e9;border-radius:9px;overflow:hidden}.country-groups>section+section{border-top:1px solid #e3e8eb}.country-row{width:100%;height:44px;display:grid;grid-template-columns:1fr auto auto;align-items:center;gap:18px;border:0;background:#fafbfc;padding:0 15px;text-align:left}.country-row b{display:flex;align-items:center;gap:8px;font-size:12px}.country-row b i{padding:3px 8px;border-radius:10px;background:#ff9700;color:#17232d;font-size:8px;font-style:normal}.country-row span{padding:3px 8px;border-radius:11px;background:#edf1f4;color:#697781;font-size:8px}.country-row em{color:#7a8791;font-size:9px;font-style:normal}.country-groups section.open .country-row{background:#fff8ed}.quote-head,.country-groups article{display:grid;grid-template-columns:minmax(230px,1.45fr) .65fr repeat(4,.62fr);align-items:center;gap:10px}.quote-head{height:34px;padding:0 15px;background:#eef2f5;color:#6c7a85;font-size:8px}.country-groups article{min-height:57px;padding:7px 15px;border-top:1px solid #edf0f2}.country-groups article.primary{background:#fff9ed;box-shadow:inset 3px 0 #ff9700}.country-groups article>span:first-child{position:relative;display:grid;gap:3px;min-width:0}.country-groups article>span:first-child b,.country-groups article>span:first-child small{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.country-groups article>span:first-child b{font-size:10px}.country-groups article>span:first-child small{color:#84909a;font-size:8px}.country-groups article>span:first-child em{position:absolute;right:5px;padding:2px 6px;border-radius:8px;background:#ff9700;color:#17232d;font-size:7px;font-style:normal}.country-groups article>strong{font-size:10px}.country-groups article>span:not(:first-child){display:grid;gap:2px}.country-groups article>span:not(:first-child) b{font-size:10px}.country-groups article>span:not(:first-child) small{color:#82909a;font-size:7px}.country-groups .custom{padding:6px;border-radius:6px;background:#fff0d8;color:#a95f00}.empty{margin:0 22px;padding:36px;border:1px dashed #d9e1e6;border-radius:9px;color:#87939d;text-align:center;font-size:10px}.quote-preview footer{display:flex;align-items:center;justify-content:space-between;gap:18px;margin-top:18px;padding:15px 22px;border-top:1px solid #e2e7ea;background:#fafbfc}.quote-preview footer>span{display:flex;align-items:center;gap:8px;color:#188253;font-size:9px}.quote-preview footer>div{display:flex;gap:9px}.quote-preview footer button{height:38px;padding:0 16px;border-radius:7px;font-size:10px;font-weight:800}.quote-preview footer .outline{border:1px solid #6fb58d;background:#fff;color:#25774c}.quote-preview footer .dark{border:1px solid #243440;background:#fff;color:#243440}.quote-preview footer .save{min-width:190px;border:0;background:#ff9700;color:#17232d}.quote-preview footer button:disabled{border-color:#dce2e6;background:#e9edef;color:#9aa4ab;cursor:not-allowed}@media(max-width:900px){.preview-kpis{grid-template-columns:1fr 1fr}.preview-info dl{grid-template-columns:1fr 1fr}.quote-head,.country-groups article{min-width:760px}.country-groups section.open{overflow-x:auto}.quote-preview footer{align-items:stretch;flex-direction:column}.quote-preview footer>div{display:grid;grid-template-columns:1fr 1fr}.quote-preview footer .save{grid-column:1/-1}}@media(max-width:560px){.preview-kpis{grid-template-columns:1fr}.preview-info dl{grid-template-columns:1fr}.quote-preview footer>div{grid-template-columns:1fr}.quote-preview footer .save{grid-column:auto}}
.channel-name-line{display:flex!important;align-items:center;gap:7px;min-width:0}.channel-name-line>b{min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.validation-summary{margin:18px 22px 0;border:1px solid #f1b45c;border-radius:9px;background:#fffaf1;overflow:hidden}.validation-summary>header{display:flex;align-items:center;justify-content:space-between;gap:18px;padding:11px 14px;border-bottom:1px solid #f4d8ae}.validation-summary>header span{display:flex;align-items:center;gap:8px}.validation-summary>header i{width:20px;height:20px;display:grid;place-items:center;border-radius:50%;background:#f09100;color:#fff;font-size:11px;font-style:normal;font-weight:900}.validation-summary>header b{color:#8f4e00;font-size:12px}.validation-summary>header em{color:#a16b29;font-size:9px;font-style:normal}.validation-summary>div{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px;padding:10px}.validation-summary button{display:grid;grid-template-columns:25px 1fr auto;align-items:center;gap:8px;min-width:0;padding:9px 10px;border:1px solid #ecd9bd;border-radius:7px;background:#fff;text-align:left;cursor:pointer}.validation-summary button:hover{border-color:#f09a18;background:#fffdf9}.validation-summary button>i{width:22px;height:22px;display:grid;place-items:center;border-radius:50%;background:#fff0d8;color:#c56a00;font-size:9px;font-style:normal;font-weight:850}.validation-summary button>span{display:grid;gap:2px;min-width:0}.validation-summary button b{font-size:10px}.validation-summary button small{overflow:hidden;color:#8b6a42;font-size:8px;text-overflow:ellipsis;white-space:nowrap}.validation-summary button>em{color:#d17100;font-size:9px;font-style:normal;font-weight:800;white-space:nowrap}@media(max-width:700px){.validation-summary>div{grid-template-columns:1fr}.validation-summary>header{align-items:flex-start;flex-direction:column;gap:5px}}
</style>
