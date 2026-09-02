<script setup lang="ts">
import { computed, ref } from 'vue'
import type { QuotationMatrixRow } from './types'
import QuoteTaxMeta from './QuoteTaxMeta.vue'

const props = withDefaults(defineProps<{
  cnyPrice: number
  usdPrice: number
  productCost: number
  logisticsCost: number
  domesticFreightCost: number
  profit: number
  coefficient: number
  grade: string
  status: string
  quoteOptions?: QuotationMatrixRow[]
  matrixModeLabel?: string
  customQuantity?: number
  exchangeRate?: number
  unitLabel?: string
  primaryLabel?: string
}>(), {
  quoteOptions: () => [],
  matrixModeLabel: '当前首选方案',
  customQuantity: 5,
  exchangeRate: 0,
  unitLabel: '件',
  primaryLabel: '—',
})

const drawerOpen = ref(false)
const options = computed(() => props.quoteOptions.filter(option => option.available !== false))
const hasOptions = computed(() => options.value.length > 0)
const countryCount = computed(() => new Set(options.value.map(option => option.country).filter(Boolean)).size)
const normalizedCustomQuantity = computed(() => Math.max(1, Math.floor(Number(props.customQuantity) || 1)))
const customQuantityLabel = computed(() => `${normalizedCustomQuantity.value}${props.unitLabel}`)

function etaRange(eta: string) {
  const values = eta.match(/\d+(?:\.\d+)?/g)?.map(Number) || []
  return [values[0] ?? Number.POSITIVE_INFINITY, values[1] ?? values[0] ?? Number.POSITIVE_INFINITY]
}
function compareEta(a: QuotationMatrixRow, b: QuotationMatrixRow) {
  const [aStart, aEnd] = etaRange(a.eta)
  const [bStart, bEnd] = etaRange(b.eta)
  return aStart - bStart || aEnd - bEnd || (a.quote1 ?? Number.POSITIVE_INFINITY) - (b.quote1 ?? Number.POSITIVE_INFINITY)
}
const lowest = computed(() => options.value
  .filter(option => option.quote1 != null)
  .slice()
  .sort((a, b) => (a.quote1 ?? Number.POSITIVE_INFINITY) - (b.quote1 ?? Number.POSITIVE_INFINITY) || compareEta(a, b))[0])
const fastest = computed(() => options.value
  .filter(option => Number.isFinite(etaRange(option.eta)[0]))
  .slice()
  .sort(compareEta)[0])
const groupedOptions = computed(() => {
  const groups = new Map<string, QuotationMatrixRow[]>()
  for (const option of options.value) {
    const group = groups.get(option.country)
    if (group) group.push(option)
    else groups.set(option.country, [option])
  }
  return [...groups.entries()].map(([country, rows]) => ({ country, rows }))
})

function formatUsd(value: number | null | undefined) { return value == null ? '—' : `$${value.toFixed(2)}` }
function formatCny(value: number | null | undefined) {
  if (value == null || !(props.exchangeRate > 0)) return '—'
  return `¥${(value * props.exchangeRate).toFixed(2)}`
}
function range(values: Array<number | null | undefined>, currency: 'USD' | 'CNY') {
  const sorted = [...new Set(values.filter((value): value is number => value != null && Number.isFinite(value)))].sort((a, b) => a - b)
  if (!sorted.length) return '—'
  const format = currency === 'USD' ? formatUsd : formatCny
  const first = format(sorted[0])
  return sorted.length === 1 ? first : `${first} ~ ${format(sorted[sorted.length - 1])}`
}
const quote1UsdRange = computed(() => range(options.value.map(option => option.quote1), 'USD'))
const quote1CnyRange = computed(() => range(options.value.map(option => option.quote1), 'CNY'))
const customUsdRange = computed(() => range(options.value.map(option => option.quoteCustom), 'USD'))

function rowKey(row: QuotationMatrixRow) { return `${row.country}|${row.channelKey || ''}|${row.rule}|${row.carrier}|${row.transport}` }
function isPrimary(row: QuotationMatrixRow) {
  const label = props.primaryLabel.trim()
  return !!label && label.includes(row.country) && [row.carrier, row.transport, row.rule].some(value => value && label.includes(value))
}
</script>

<template>
  <aside class="price-summary">
    <header>
      <div><span>报价方案概览</span><small>{{ matrixModeLabel }} · {{ status }}</small></div>
      <i :class="{ pending:!hasOptions }"></i>
    </header>

    <div v-if="hasOptions" class="coverage"><b>{{ countryCount }}</b><span>个国家</span><b>{{ options.length }}</b><span>条渠道</span></div>
    <div v-else class="fallback-tip"><i>!</i><span><b>尚未选择矩阵渠道</b><small>以下显示当前试算首选方案</small></span></div>

    <section v-if="hasOptions" class="overview-grid">
      <article class="price-range"><span>1{{ unitLabel }}报价区间</span><b>{{ quote1UsdRange }}</b><small>{{ quote1CnyRange }}</small></article>
      <article class="custom-range"><span>{{ customQuantityLabel }}报价区间</span><b>{{ customUsdRange }}</b><small>按当前自定义数量</small></article>
      <article><span>最低报价</span><b>{{ formatUsd(lowest?.quote1) }}</b><small>{{ lowest ? `${lowest.country} · ${lowest.carrier}｜${lowest.transport}` : '暂无有效报价' }}</small></article>
      <article><span>最快时效</span><b>{{ fastest?.eta || '—' }}</b><small>{{ fastest ? `${fastest.country} · ${fastest.carrier}｜${fastest.transport}` : '暂无有效时效' }}</small></article>
    </section>

    <section class="primary-option">
      <span>当前首选方案</span><b>{{ primaryLabel }}</b>
      <p><strong>¥{{ cnyPrice.toFixed(2) }}</strong><small>${{ usdPrice.toFixed(2) }}</small></p>
    </section>

    <dl class="cost-grid">
      <div><dt>商品成本</dt><dd>¥{{ productCost.toFixed(2) }}</dd></div>
      <div><dt>物流费用</dt><dd>¥{{ logisticsCost.toFixed(2) }}</dd></div>
      <div><dt>国内运费</dt><dd>¥{{ domesticFreightCost.toFixed(2) }}</dd></div>
      <div><dt>首选方案利润</dt><dd class="profit">¥{{ profit.toFixed(2) }}</dd></div>
    </dl>
    <div class="coefficient"><span>{{ grade }}级客户系数</span><b>× {{ coefficient.toFixed(2) }}</b></div>
    <button class="open-overview" :disabled="!hasOptions" @click="drawerOpen=true">查看报价单概览 <i>→</i></button>
  </aside>

  <Teleport to="body">
    <div v-if="drawerOpen" class="overview-mask" @click.self="drawerOpen=false">
      <aside class="overview-drawer" role="dialog" aria-modal="true" aria-labelledby="quote-overview-title">
        <header><div><small>QUOTATION OVERVIEW</small><h2 id="quote-overview-title">报价单方案概览</h2><p>{{ matrixModeLabel }} · {{ countryCount }} 个国家 · {{ options.length }} 条渠道</p></div><button aria-label="关闭" @click="drawerOpen=false">×</button></header>
        <section class="drawer-summary">
          <div><span>1{{ unitLabel }}报价区间</span><b>{{ quote1UsdRange }}</b><small>{{ quote1CnyRange }}</small></div>
          <div><span>{{ customQuantityLabel }}报价区间</span><b>{{ customUsdRange }}</b><small>自定义数量报价</small></div>
          <div><span>当前首选方案</span><b>{{ primaryLabel }}</b><small>¥{{ cnyPrice.toFixed(2) }} / ${{ usdPrice.toFixed(2) }}</small></div>
        </section>
        <div class="drawer-table-head"><span>物流渠道 / 服务商</span><span>预计时效</span><span>1{{ unitLabel }}</span><span>2{{ unitLabel }}</span><span>3{{ unitLabel }}</span><span>{{ customQuantityLabel }}</span></div>
        <section class="country-groups">
          <details v-for="(group,index) in groupedOptions" :key="group.country" :open="index===0 || group.rows.some(isPrimary)">
            <summary><span><b>{{ group.country }}</b><small>{{ group.rows.length }} 条渠道</small></span><i>⌄</i></summary>
            <article v-for="row in group.rows" :key="rowKey(row)" :class="{ primary:isPrimary(row) }">
              <span><span class="channel-name-line"><b>{{ row.carrier }}｜{{ row.transport }}</b><QuoteTaxMeta :row="row" /></span><small>渠道编码：{{ row.channelCode || '—' }} · 计费规则：{{ row.rule }}<template v-if="row.quoteRegion"> · {{ row.quoteRegion }}</template></small><em v-if="isPrimary(row)">首选</em></span>
              <strong>{{ row.eta }}</strong>
              <span class="quote"><b>{{ formatUsd(row.quote1) }}</b><small>{{ formatCny(row.quote1) }}</small><QuoteTaxMeta :row="row" mode="price" tier="1" /></span>
              <span class="quote"><b>{{ formatUsd(row.quote2) }}</b><small>{{ formatCny(row.quote2) }}</small><QuoteTaxMeta :row="row" mode="price" tier="2" /></span>
              <span class="quote"><b>{{ formatUsd(row.quote3) }}</b><small>{{ formatCny(row.quote3) }}</small><QuoteTaxMeta :row="row" mode="price" tier="3" /></span>
              <span class="quote custom"><b>{{ formatUsd(row.quoteCustom) }}</b><small>{{ formatCny(row.quoteCustom) }}</small><QuoteTaxMeta :row="row" mode="price" tier="custom" /></span>
            </article>
          </details>
        </section>
        <footer><span>本页仅用于核对报价方案，保存请使用页面底部统一入口。</span><button @click="drawerOpen=false">关闭</button></footer>
      </aside>
    </div>
  </Teleport>
</template>

<style scoped>
.price-summary{position:sticky;top:88px;display:flex;height:auto;box-sizing:border-box;flex-direction:column;overflow:hidden;border:1px solid #dfe6eb;border-radius:12px;background:#fff;color:#17232d;box-shadow:0 14px 34px rgba(23,35,45,.1)}.price-summary>header{display:flex;align-items:center;justify-content:space-between;padding:16px 17px;border-bottom:1px solid #e7ecef}.price-summary>header div{display:grid;gap:3px}.price-summary>header span{font-size:14px;font-weight:900}.price-summary>header small{color:#7f8c96;font-size:8px}.price-summary>header>i{width:8px;height:8px;border-radius:50%;background:#28ad6b}.price-summary>header>i.pending{background:#e9a122}.coverage{display:flex;align-items:baseline;justify-content:center;gap:5px;padding:10px;background:#f2f8f4;color:#42705a;font-size:9px}.coverage b{color:#168553;font-size:17px}.coverage span+ b{margin-left:12px}.fallback-tip{display:flex;align-items:center;gap:9px;margin:12px 14px 0;padding:10px;border:1px solid #f0d9b2;border-radius:8px;background:#fff8eb}.fallback-tip>i{width:24px;height:24px;display:grid;place-items:center;border-radius:50%;background:#ffebc8;color:#b56a00;font-style:normal;font-weight:900}.fallback-tip span{display:grid;gap:2px}.fallback-tip b{font-size:10px}.fallback-tip small{color:#8b7c66;font-size:8px}.overview-grid{display:grid;grid-template-columns:1fr 1fr;gap:7px;padding:11px 13px}.overview-grid article{display:grid;gap:3px;min-width:0;padding:9px;border-radius:8px;background:#f5f7f9}.overview-grid article.price-range{background:#fff3df}.overview-grid article.custom-range{background:#edf7ff}.overview-grid span{color:#76838d;font-size:8px}.overview-grid b{overflow:hidden;font-size:12px;text-overflow:ellipsis;white-space:nowrap}.overview-grid small{overflow:hidden;color:#8b969f;font-size:7px;text-overflow:ellipsis;white-space:nowrap}.primary-option{display:grid;grid-template-columns:1fr auto;gap:4px 8px;margin:0 13px;padding:10px 11px;border:1px solid #ffd59b;border-radius:8px;background:#fffaf1}.primary-option>span{grid-column:1/-1;color:#a66305;font-size:8px;font-weight:800}.primary-option>b{align-self:center;overflow:hidden;font-size:10px;text-overflow:ellipsis;white-space:nowrap}.primary-option p{display:flex;align-items:baseline;gap:5px;margin:0}.primary-option strong{color:#d67100;font-size:15px}.primary-option small{color:#7a8791;font-size:8px}.cost-grid{display:grid;grid-template-columns:1fr 1fr;gap:0;margin:11px 13px 0;padding:8px 0;border-top:1px solid #e8ecef;border-bottom:1px solid #e8ecef}.cost-grid div{display:flex;justify-content:space-between;gap:5px;padding:5px 7px}.cost-grid dt{color:#84909a;font-size:8px}.cost-grid dd{margin:0;font-size:9px;font-weight:800}.cost-grid .profit{color:#168653}.coefficient{display:flex;justify-content:space-between;margin:9px 19px;color:#7b8791;font-size:9px}.coefficient b{color:#26343f}.open-overview{display:flex;align-items:center;justify-content:center;gap:12px;height:38px;margin:0 13px 13px;border:0;border-radius:7px;background:#172735;color:#fff;font-size:10px;font-weight:850;cursor:pointer}.open-overview i{font-style:normal}.open-overview:disabled{background:#e6eaed;color:#98a2aa;cursor:not-allowed}.overview-mask{position:fixed;z-index:140;inset:0;background:rgba(16,24,32,.46);backdrop-filter:blur(3px)}.overview-drawer{position:absolute;top:0;right:0;display:flex;width:min(720px,100vw);height:100%;box-sizing:border-box;flex-direction:column;overflow:hidden;background:#fff;color:#17232d;box-shadow:-18px 0 50px rgba(10,20,29,.22)}.overview-drawer>header{display:flex;align-items:flex-start;justify-content:space-between;padding:23px 25px 18px;border-bottom:1px solid #e3e8ec}.overview-drawer>header small{color:#d67600;font-size:8px;font-weight:900;letter-spacing:.16em}.overview-drawer h2{margin:5px 0;font-size:22px}.overview-drawer>header p{margin:0;color:#7c8993;font-size:10px}.overview-drawer>header button{border:0;background:none;color:#50606c;font-size:25px;cursor:pointer}.drawer-summary{display:grid;grid-template-columns:1fr 1fr 1.15fr;gap:9px;padding:14px 24px;background:#f6f8fa}.drawer-summary div{display:grid;gap:4px;min-width:0;padding:11px;border:1px solid #e4e9ec;border-radius:8px;background:#fff}.drawer-summary span{color:#7b8791;font-size:8px}.drawer-summary b{overflow:hidden;color:#293842;font-size:13px;text-overflow:ellipsis;white-space:nowrap}.drawer-summary small{overflow:hidden;color:#8a969f;font-size:8px;text-overflow:ellipsis;white-space:nowrap}.drawer-table-head,.country-groups article{display:grid;grid-template-columns:minmax(185px,1.45fr) .65fr repeat(4,.63fr);align-items:center;gap:8px}.drawer-table-head{padding:9px 24px;background:#edf2f5;color:#6d7b86;font-size:8px}.country-groups{min-height:0;flex:1;overflow:auto}.country-groups details{border-bottom:1px solid #e5eaed}.country-groups summary{display:flex;align-items:center;justify-content:space-between;padding:11px 24px;background:#fafbfc;cursor:pointer;list-style:none}.country-groups summary::-webkit-details-marker{display:none}.country-groups summary>span{display:flex;align-items:center;gap:8px}.country-groups summary b{font-size:12px}.country-groups summary small{padding:3px 7px;border-radius:10px;background:#edf1f4;color:#6c7983;font-size:8px}.country-groups summary>i{font-style:normal;transition:transform .16s}.country-groups details[open] summary>i{transform:rotate(180deg)}.country-groups article{min-height:51px;padding:7px 24px;border-top:1px solid #edf1f3;font-size:9px}.country-groups article.primary{background:#fff9ed;box-shadow:inset 3px 0 #ff9900}.country-groups article>span:first-child{position:relative;display:grid;min-width:0;gap:2px}.country-groups article>span:first-child b,.country-groups article>span:first-child small{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.country-groups article>span:first-child small{color:#84919a;font-size:7px}.country-groups article>span:first-child em{position:absolute;right:0;top:0;padding:2px 5px;border-radius:8px;background:#ffebc9;color:#aa6200;font-size:7px;font-style:normal;font-weight:800}.country-groups article>strong{font-size:9px}.quote{display:grid;gap:2px}.quote b{font-size:9px}.quote small{color:#85919a;font-size:7px}.quote.custom{padding:5px;border-radius:5px;background:#fff1da;color:#b76300}.overview-drawer>footer{display:flex;align-items:center;justify-content:space-between;gap:14px;padding:15px 24px;border-top:1px solid #dfe5e9}.overview-drawer>footer span{color:#7a8791;font-size:9px}.overview-drawer>footer button{height:38px;padding:0 22px;border:0;border-radius:7px;background:#ff9900;color:#17212b;font-size:10px;font-weight:850;cursor:pointer}@media(max-width:980px){.price-summary{position:static;height:auto}}@media(max-width:680px){.drawer-summary{grid-template-columns:1fr}.drawer-table-head,.country-groups article{min-width:670px}.overview-drawer{overflow-x:auto}.country-groups{min-width:670px}.overview-drawer>footer{min-width:670px}.overview-drawer>footer span{display:none}}
.channel-name-line{display:flex!important;align-items:center;gap:7px;min-width:0}.channel-name-line>b{min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
</style>
