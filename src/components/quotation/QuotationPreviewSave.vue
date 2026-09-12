<script setup lang="ts">
import { computed, ref } from 'vue'
import type { QuotationMatrixRow } from './types'
import CustomerQuoteSheet from './CustomerQuoteSheet.vue'
import type { QuoteSheetCountry, QuoteSheetPriceCalculator } from '@/data/customerQuoteSheet'

const props = withDefaults(defineProps<{
  rows: QuotationMatrixRow[]
  countries: QuoteSheetCountry[]
  salesperson: string
  contextKey: string
  sourcePending: boolean
  calculatePrice?: QuoteSheetPriceCalculator
  resetKey?: string
  matrixModeLabel: string
  customerName: string
  productName: string
  sku: string
  customerGrade: string
  coefficient: number
  customQuantity: number
  unitLabel: string
  exchangeRate: number
  primaryRegion?: string
  primaryCountry: string
  primaryCarrier: string
  primaryRule: string
  primaryCnyPrice: number
  primaryUsdPrice: number
  blockReason?: string
  saving?: boolean
  validationIssues?: Array<{ key: string; label: string; message: string }>
}>(), { blockReason: '', saving: false, validationIssues: () => [] })

const emit = defineEmits<{ save: []; locateIssue: [key: string] }>()
const customerSheet = ref<InstanceType<typeof CustomerQuoteSheet>>()
defineExpose({ capturePrices: () => customerSheet.value?.capturePrices() })
const countryCount = computed(() => new Set(props.rows.map(row => row.country)).size)
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
          <div><dt>客户等级</dt><dd>{{ customerGrade }}级客户 × {{ coefficient.toString() }}</dd></div>
          <div><dt>自定义数量</dt><dd>{{ Math.max(1, customQuantity || 1) }}{{ unitLabel }}</dd></div>
        </dl>
      </section>
    </div>

    <CustomerQuoteSheet ref="customerSheet" :rows="rows" :countries="countries" :salesperson="salesperson"
      :context-key="contextKey" :source-pending="sourcePending" :custom-quantity="customQuantity" :bundle="unitLabel === '套'" :calculate-price="calculatePrice" :reset-key="resetKey" />

    <section v-if="validationIssues.length" class="validation-summary" aria-live="polite">
      <header><span><i>!</i><b>暂时无法保存报价</b></span><em>请完成以下 {{ validationIssues.length }} 项必填内容</em></header>
      <div>
        <button v-for="(issue,index) in validationIssues" :key="issue.key" type="button" @click="emit('locateIssue',issue.key)">
          <i>{{ index + 1 }}</i><span><b>{{ issue.label }}</b><small>{{ issue.message }}</small></span><em>{{ issue.key === 'taxPolicy' ? '查看提示' : '去填写' }} →</em>
        </button>
      </div>
    </section>

    <footer><span :class="{ warning:blockReason || !hasQuoteRows }"><i></i>{{ footerStatus }}</span><div><button class="save" :disabled="!!blockReason || !hasQuoteRows || saving" @click="emit('save')">{{ saving ? '正在校验物流版本…' : `保存 1 张报价单 · ${countryCount}国${rows.length}渠道` }}</button></div></footer>
  </section>
</template>

<style scoped>
.quote-preview{overflow:hidden;border:1px solid #dfe6eb;border-top:3px solid #ff9700;border-radius:13px;background:#fff;box-shadow:0 13px 32px rgba(20,34,45,.07);color:#17232d}.preview-head{display:flex;align-items:center;justify-content:space-between;gap:24px;padding:20px 22px;border-bottom:1px solid #e5eaee}.preview-head p{margin:0 0 4px;color:#d87500;font-size:9px;font-weight:900;letter-spacing:.15em}.preview-head h2{margin:0 0 5px;font-size:21px}.preview-head span{color:#7c8993;font-size:10px}.preview-head>em{display:flex;align-items:center;gap:7px;color:#188253;font-size:10px;font-style:normal}.preview-head>em i,.quote-preview footer>span i{width:8px;height:8px;border-radius:50%;background:#25ad6c}.preview-head>em.warning,.quote-preview footer>span.warning{color:#b16a00}.preview-head>em.warning i,.quote-preview footer>span.warning i{background:#e8a31d}.preview-kpis{display:grid;grid-template-columns:repeat(4,1fr);gap:11px;padding:17px 22px;background:#f6f8fa}.preview-kpis article{display:flex;align-items:center;gap:12px;min-height:55px;padding:10px 13px;border:1px solid #e2e8ec;border-radius:9px;background:#fff}.preview-kpis article>i{width:34px;height:34px;display:grid;place-items:center;border-radius:50%;background:#eef2ff;color:#3e61d4;font-style:normal;font-weight:900}.preview-kpis span{display:grid;gap:2px}.preview-kpis b{font-size:18px}.preview-kpis small{color:#7e8a94;font-size:9px}.preview-kpis .range>i{background:#fff0d7;color:#d47400}.preview-kpis .range small{order:-1}.preview-kpis .range b{font-size:15px}.preview-info{padding:18px 22px}.preview-info>section{padding:15px 17px;border:1px solid #e1e7eb;border-radius:9px}.preview-info h3{margin:0;font-size:13px}.preview-info dl{display:grid;grid-template-columns:repeat(3,1fr);gap:10px 22px;margin:14px 0 0}.preview-info dl div{display:flex;gap:8px}.preview-info dt{min-width:60px;color:#81909a;font-size:9px}.preview-info dd{margin:0;font-size:10px;font-weight:750}.quote-preview footer{display:flex;align-items:center;justify-content:space-between;gap:18px;margin-top:18px;padding:15px 22px;border-top:1px solid #e2e7ea;background:#fafbfc}.quote-preview footer>span{display:flex;align-items:center;gap:8px;color:#188253;font-size:9px}.quote-preview footer>div{display:flex;gap:9px}.quote-preview footer button{height:38px;padding:0 16px;border-radius:7px;font-size:10px;font-weight:800}.quote-preview footer .outline{border:1px solid #6fb58d;background:#fff;color:#25774c}.quote-preview footer .dark{border:1px solid #243440;background:#fff;color:#243440}.quote-preview footer .save{min-width:190px;border:0;background:#ff9700;color:#17232d}.quote-preview footer button:disabled{border-color:#dce2e6;background:#e9edef;color:#9aa4ab;cursor:not-allowed}@media(max-width:900px){.preview-kpis{grid-template-columns:1fr 1fr}.preview-info dl{grid-template-columns:1fr 1fr}.quote-preview footer{align-items:stretch;flex-direction:column}.quote-preview footer>div{display:grid;grid-template-columns:1fr 1fr}.quote-preview footer .save{grid-column:1/-1}}@media(max-width:560px){.preview-kpis{grid-template-columns:1fr}.preview-info dl{grid-template-columns:1fr}.quote-preview footer>div{grid-template-columns:1fr}.quote-preview footer .save{grid-column:auto}}
.validation-summary{margin:18px 22px 0;border:1px solid #f1b45c;border-radius:9px;background:#fffaf1;overflow:hidden}.validation-summary>header{display:flex;align-items:center;justify-content:space-between;gap:18px;padding:11px 14px;border-bottom:1px solid #f4d8ae}.validation-summary>header span{display:flex;align-items:center;gap:8px}.validation-summary>header i{width:20px;height:20px;display:grid;place-items:center;border-radius:50%;background:#f09100;color:#fff;font-size:11px;font-style:normal;font-weight:900}.validation-summary>header b{color:#8f4e00;font-size:12px}.validation-summary>header em{color:#a16b29;font-size:9px;font-style:normal}.validation-summary>div{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px;padding:10px}.validation-summary button{display:grid;grid-template-columns:25px 1fr auto;align-items:center;gap:8px;min-width:0;padding:9px 10px;border:1px solid #ecd9bd;border-radius:7px;background:#fff;text-align:left;cursor:pointer}.validation-summary button:hover{border-color:#f09a18;background:#fffdf9}.validation-summary button>i{width:22px;height:22px;display:grid;place-items:center;border-radius:50%;background:#fff0d8;color:#c56a00;font-size:9px;font-style:normal;font-weight:850}.validation-summary button>span{display:grid;gap:2px;min-width:0}.validation-summary button b{font-size:10px}.validation-summary button small{overflow:hidden;color:#8b6a42;font-size:8px;text-overflow:ellipsis;white-space:nowrap}.validation-summary button>em{color:#d17100;font-size:9px;font-style:normal;font-weight:800;white-space:nowrap}@media(max-width:700px){.validation-summary>div{grid-template-columns:1fr}.validation-summary>header{align-items:flex-start;flex-direction:column;gap:5px}}
</style>
