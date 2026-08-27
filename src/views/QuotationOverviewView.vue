<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import AppTopbar from '@/components/AppTopbar.vue'
import DateFilter from '@/components/DateFilter.vue'
import {
  buildCategoryPerformance,
  buildDashboardSummary,
  buildSalespersonRanking,
  filterQuotationRecords,
  quotationDetailsCsv,
  quotationSkus,
  recordCountries,
  resolveRecordCategory,
  type DashboardFilters,
} from '@/data/quotationAnalytics'
import { loadPurchaseProducts, type PurchaseProductRecord } from '@/data/purchaseStore'
import { loadQuotationRecords, type QuotationRecord } from '@/data/quotationRecords'

const records = ref<QuotationRecord[]>([])
const purchases = ref<PurchaseProductRecord[]>([])
const purchaseLoadFailed = ref(false)
const globalSearch = ref('')
const startDate = ref('')
const endDate = ref('')
const countryFilter = ref('')
const salespersonFilter = ref('')
const categoryFilter = ref('')
const rankingExpanded = ref(false)
const categorySearch = ref('')
const categoryMode = ref<'all' | 'volume'>('all')
const categorySort = ref<'amount' | 'quotes' | 'sku'>('amount')
const categoryPage = ref(1)
const categoryPageSize = ref(8)
const detailSearch = ref('')
const detailPage = ref(1)
const detailPageSize = ref(20)
const notice = ref('')

const purchaseBySku = computed(() => new Map(purchases.value.map(item => [item.sku.toUpperCase(), item])))
const allCountries = computed(() => [...new Set(records.value.flatMap(recordCountries))].sort((a, b) => a.localeCompare(b, 'zh-CN')))
const allSalespeople = computed(() => [...new Set(records.value.map(record => record.salespersonName).filter(Boolean))].sort((a, b) => a.localeCompare(b, 'zh-CN')))
const allCategories = computed(() => [...new Set([
  ...purchases.value.map(item => item.category.trim() || '未分类'),
  ...records.value.map(record => resolveRecordCategory(record, purchaseBySku.value)),
])].sort((a, b) => a.localeCompare(b, 'zh-CN')))
const filters = computed<DashboardFilters>(() => ({
  keyword: globalSearch.value,
  startDate: startDate.value,
  endDate: endDate.value,
  country: countryFilter.value,
  salesperson: salespersonFilter.value,
  category: categoryFilter.value,
}))
const filteredRecords = computed(() => filterQuotationRecords(records.value, filters.value, purchases.value))
const summary = computed(() => buildDashboardSummary(filteredRecords.value))
const ranking = computed(() => buildSalespersonRanking(filteredRecords.value))
const visibleRanking = computed(() => rankingExpanded.value ? ranking.value : ranking.value.slice(0, 5))

const categoryRows = computed(() => {
  const keyword = categorySearch.value.trim().toLowerCase()
  let rows = buildCategoryPerformance(filteredRecords.value, purchases.value).filter(row => !keyword || row.category.toLowerCase().includes(keyword) || row.skus.some(sku => sku.toLowerCase().includes(keyword)))
  if (categoryMode.value === 'volume') rows = rows.filter(row => row.quotationCount > 0).sort((a, b) => b.quotationCount - a.quotationCount)
  if (categorySort.value === 'amount') rows.sort((a, b) => b.quoteCny - a.quoteCny)
  if (categorySort.value === 'quotes') rows.sort((a, b) => b.quotationCount - a.quotationCount)
  if (categorySort.value === 'sku') rows.sort((a, b) => b.skuCount - a.skuCount)
  return rows
})
const categoryPageCount = computed(() => Math.max(1, Math.ceil(categoryRows.value.length / categoryPageSize.value)))
const pagedCategories = computed(() => categoryRows.value.slice((categoryPage.value - 1) * categoryPageSize.value, categoryPage.value * categoryPageSize.value))

const detailRows = computed(() => {
  const keyword = detailSearch.value.trim().toLowerCase()
  return filteredRecords.value.filter(record => {
    const category = resolveRecordCategory(record, purchaseBySku.value)
    return !keyword || [record.no, record.customerName, record.primarySku, record.salespersonName, category].join(' ').toLowerCase().includes(keyword)
  })
})
const detailPageCount = computed(() => Math.max(1, Math.ceil(detailRows.value.length / detailPageSize.value)))
const pagedDetails = computed(() => detailRows.value.slice((detailPage.value - 1) * detailPageSize.value, detailPage.value * detailPageSize.value))

const moneyUsd = (value: number) => new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 2 }).format(value)
const moneyCny = (value: number) => new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY', maximumFractionDigits: 2 }).format(value)
const percent = (value: number | null) => value == null ? '—' : `${value.toFixed(2)}%`
const dateTime = (value: string) => {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }).format(date)
}
const initials = (name: string) => name.trim().slice(0, 2) || '—'
const categoryFor = (record: QuotationRecord) => resolveRecordCategory(record, purchaseBySku.value)

function resetFilters() {
  globalSearch.value = ''; startDate.value = ''; endDate.value = ''; countryFilter.value = ''; salespersonFilter.value = ''; categoryFilter.value = ''
}

function exportDetails() {
  if (!detailRows.value.length) { toast('当前筛选条件下没有可导出的报价明细'); return }
  const blob = new Blob([quotationDetailsCsv(detailRows.value, purchases.value)], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `米莱诺报价明细-${new Date().toISOString().slice(0, 10)}.csv`
  link.click()
  URL.revokeObjectURL(url)
  toast(`已导出 ${detailRows.value.length} 条报价明细`)
}

function toast(message: string) {
  notice.value = message
  window.setTimeout(() => { if (notice.value === message) notice.value = '' }, 2400)
}

watch([filters, detailSearch, detailPageSize], () => { detailPage.value = 1 })
watch([filters, categorySearch, categoryMode, categorySort, categoryPageSize], () => { categoryPage.value = 1 })
watch(categoryPageCount, count => { if (categoryPage.value > count) categoryPage.value = count })
watch(detailPageCount, count => { if (detailPage.value > count) detailPage.value = count })

onMounted(async () => {
  const [recordResult, purchaseResult] = await Promise.allSettled([
    loadQuotationRecords('company'),
    loadPurchaseProducts(),
  ])
  records.value = recordResult.status === 'fulfilled' ? recordResult.value : []
  if (purchaseResult.status === 'fulfilled') purchases.value = purchaseResult.value
  else { purchaseLoadFailed.value = true; purchases.value = [] }
})
</script>

<template>
  <div class="overview-app">
    <AppTopbar />
    <main>
      <header class="page-heading">
        <div><p>QUOTATION ANALYTICS</p><h1>报价情况预览</h1><span>基于现有采购资料和已保存报价，查看公司报价经营数据</span></div>
        <button class="export" type="button" @click="exportDetails">⇩ 导出报表</button>
      </header>

      <section class="global-filters">
        <label class="global-search">⌕<input v-model="globalSearch" placeholder="搜索报价、客户、SKU、业务员"></label>
        <DateFilter v-model="startDate" label="开始日期" :max="endDate || undefined" />
        <DateFilter v-model="endDate" label="结束日期" :min="startDate || undefined" />
        <label>国家<select v-model="countryFilter"><option value="">全部</option><option v-for="country in allCountries" :key="country">{{ country }}</option></select></label>
        <label>业务员<select v-model="salespersonFilter"><option value="">全部</option><option v-for="name in allSalespeople" :key="name">{{ name }}</option></select></label>
        <label>品类<select v-model="categoryFilter"><option value="">全部</option><option v-for="category in allCategories" :key="category">{{ category }}</option></select></label>
        <button type="button" @click="resetFilters">重置</button>
      </section>

      <section class="kpis">
        <article><i class="blue">▤</i><span><small>报价单数</small><b>{{ summary.quotationCount }}</b><em>当前筛选范围</em></span></article>
        <article><i class="violet">◇</i><span><small>报价 SKU</small><b>{{ summary.quotedSkuCount }}</b><em>去重后的商品数量</em></span></article>
        <article><i class="green">$</i><span><small>报价金额</small><b>{{ moneyUsd(summary.quoteUsd) }}</b><em>{{ moneyCny(summary.quoteCny) }}</em></span></article>
      </section>

      <section class="ranking-section">
        <article class="card ranking-card">
          <header><div><h2>业务员报价排行</h2><span>按成交比例、成交品数和报价品数综合排序</span></div><button v-if="ranking.length > 5" type="button" @click="rankingExpanded=!rankingExpanded">{{ rankingExpanded ? '收起' : '查看全部' }}</button></header>
          <div class="table-scroll"><table><thead><tr><th>排名</th><th>业务员</th><th>报价客户数</th><th>报价品数</th><th>成交品数</th><th>成交比例</th><th>最近报价</th></tr></thead><tbody>
            <tr v-for="(row,index) in visibleRanking" :key="row.key"><td><i :class="['rank',`rank-${index+1}`]">{{ index + 1 }}</i></td><td><span class="person"><i>{{ initials(row.name) }}</i><span><b>{{ row.name }}</b><small>{{ row.account }}</small></span></span></td><td>{{ row.customerCount }}</td><td>{{ row.quotedProductCount }}</td><td>{{ row.wonProductCount }}</td><td><em class="conversion">{{ percent(row.conversionRate) }}</em></td><td>{{ dateTime(row.latestQuoteAt) }}</td></tr>
            <tr v-if="!visibleRanking.length"><td colspan="7" class="empty">当前筛选范围内暂无业务员报价</td></tr>
          </tbody></table></div>
        </article>
      </section>

      <section class="card category-card">
        <header><div><h2>产品品类表现</h2><span>采购 SKU 总量与当前筛选范围内的报价表现</span></div><strong>共 {{ categoryRows.length }} 个品类</strong></header>
        <div v-if="purchaseLoadFailed" class="data-warning">采购数据暂时无法读取，品类仍按报价记录统计；SKU 总数和采购均价可能不完整。</div>
        <div class="category-toolbar">
          <label>⌕<input v-model="categorySearch" placeholder="输入品类名称或 SKU"></label>
          <div><button :class="{active:categoryMode==='all'}" @click="categoryMode='all'">全部</button><button :class="{active:categoryMode==='volume'}" @click="categoryMode='volume'">高报价量</button></div>
          <label>排序<select v-model="categorySort"><option value="amount">报价金额降序</option><option value="quotes">报价次数降序</option><option value="sku">SKU 数量降序</option></select></label>
        </div>
        <div class="table-scroll"><table><thead><tr><th>品类名称</th><th>SKU 总数</th><th>已报价 SKU</th><th>采购均价</th><th>报价次数</th><th>报价金额</th></tr></thead><tbody>
          <tr v-for="row in pagedCategories" :key="row.category"><td><b>{{ row.category }}</b><small>{{ row.skus.slice(0,3).join(' · ') || '报价记录品类' }}</small></td><td>{{ row.skuCount }}</td><td>{{ row.quotedSkuCount }}</td><td>{{ row.averagePurchasePriceCny == null ? '—' : moneyCny(row.averagePurchasePriceCny) }}</td><td>{{ row.quotationCount }}</td><td><b>{{ moneyUsd(row.quoteUsd) }}</b><small>{{ moneyCny(row.quoteCny) }}</small></td></tr>
          <tr v-if="!pagedCategories.length"><td colspan="6" class="empty">没有找到匹配的产品品类</td></tr>
        </tbody></table></div>
        <footer class="pagination"><span>共 {{ categoryRows.length }} 条</span><label>每页 <select v-model.number="categoryPageSize"><option :value="8">8</option><option :value="20">20</option><option :value="50">50</option></select> 条</label><button :disabled="categoryPage===1" @click="categoryPage--">‹</button><b>{{ categoryPage }} / {{ categoryPageCount }} 页</b><button :disabled="categoryPage===categoryPageCount" @click="categoryPage++">›</button></footer>
      </section>

      <section class="card detail-card">
        <header><div><h2>报价明细</h2><span>查看筛选范围内的全部保存报价</span></div><strong>共 {{ detailRows.length }} 条</strong></header>
        <div class="detail-toolbar"><label>⌕<input v-model="detailSearch" placeholder="报价编号、客户名称、SKU"></label><span>国家：{{ countryFilter || '全部' }}</span><span>业务员：{{ salespersonFilter || '全部' }}</span><span>品类：{{ categoryFilter || '全部' }}</span></div>
        <div class="table-scroll"><table><thead><tr><th>报价编号 / 时间</th><th>客户</th><th>业务员</th><th>国家</th><th>主 SKU / 品类</th><th>成本(RMB)</th><th>报价(USD)</th><th>报价(RMB)</th><th>操作</th></tr></thead><tbody>
          <tr v-for="row in pagedDetails" :key="row.id"><td><b>{{ row.no }}</b><small>{{ dateTime(row.createdAt) }}</small></td><td>{{ row.customerName }}</td><td><b>{{ row.salespersonName }}</b><small>{{ row.salespersonAccount }}</small></td><td>{{ recordCountries(row).join('、') || '—' }}</td><td><b>{{ quotationSkus(row).join('、') || '—' }}</b><small>{{ categoryFor(row) }}</small></td><td>{{ moneyCny(row.totalCostCny) }}</td><td>{{ moneyUsd(row.systemQuoteUsd) }}</td><td>{{ moneyCny(row.systemQuoteCny) }}</td><td><RouterLink :to="{path:'/quotation/records',query:{record:row.id}}">查看详情</RouterLink></td></tr>
          <tr v-if="!pagedDetails.length"><td colspan="9" class="empty">没有找到匹配的报价明细</td></tr>
        </tbody></table></div>
        <footer class="pagination"><span>共 {{ detailRows.length }} 条</span><label>每页 <select v-model.number="detailPageSize"><option :value="20">20</option><option :value="50">50</option><option :value="100">100</option></select> 条</label><button :disabled="detailPage===1" @click="detailPage--">‹</button><b>{{ detailPage }} / {{ detailPageCount }} 页</b><button :disabled="detailPage===detailPageCount" @click="detailPage++">›</button></footer>
      </section>
    </main>
    <Transition><div v-if="notice" class="toast">{{ notice }}</div></Transition>
  </div>
</template>

<style scoped>
:global(body){margin:0}.overview-app{--orange:#ff9810;--navy:#10283f;--ink:#182630;--muted:#7b8790;--line:#e1e7eb;min-height:100vh;background:#f4f6f8;color:var(--ink);font-family:Inter,"PingFang SC","Microsoft YaHei",sans-serif}.overview-app main{width:min(1500px,94vw);margin:auto;padding:30px 0 70px}.page-heading{display:flex;align-items:flex-end;justify-content:space-between;margin-bottom:20px}.page-heading p{margin:0 0 7px;color:#d77800;font-size:9px;font-weight:900;letter-spacing:.2em}.page-heading h1{margin:0 0 5px;font-size:28px}.page-heading span{color:var(--muted);font-size:11px}.export{height:40px;padding:0 18px;border:0;border-radius:7px;background:var(--navy);color:#fff;font-weight:900;cursor:pointer}.global-filters{display:flex;align-items:flex-end;gap:8px;margin-bottom:13px;padding:12px;border:1px solid var(--line);border-radius:9px;background:#fff}.global-filters label{display:grid;gap:5px;color:#78848d;font-size:8px}.global-filters input,.global-filters select{box-sizing:border-box;height:36px;border:1px solid #dbe2e7;border-radius:6px;background:#fff;padding:0 9px;color:#34424c;outline:0}.global-search{display:flex!important;width:min(330px,28vw);height:36px;align-items:center;gap:7px;padding:0 10px;border:1px solid #dbe2e7;border-radius:6px}.global-search input{width:100%;height:32px;border:0;padding:0}.global-filters>button{height:36px;border:1px solid #dbe2e7;border-radius:6px;background:#fff;padding:0 13px}.kpis{display:grid;grid-template-columns:repeat(3,1fr);gap:12px;margin-bottom:12px}.kpis article{display:flex;align-items:center;gap:13px;min-height:82px;padding:12px 16px;border:1px solid var(--line);border-radius:9px;background:#fff;box-shadow:0 5px 18px rgba(25,40,54,.035)}.kpis>article>i{display:grid;width:42px;height:42px;flex:0 0 42px;place-items:center;border-radius:50%;background:#eef3ff;color:#2372d7;font-size:18px;font-style:normal;font-weight:900}.kpis>article>i.violet{background:#f1efff;color:#755add}.kpis>article>i.green{background:#e7f7ee;color:#15915a}.kpis span{display:grid;gap:2px}.kpis small{color:#77838d;font-size:9px}.kpis b{font-size:19px}.kpis em{color:#8d979f;font-size:8px;font-style:normal}.card{overflow:hidden;border:1px solid var(--line);border-radius:9px;background:#fff;box-shadow:0 7px 24px rgba(25,40,54,.04)}.card>header{display:flex;align-items:center;justify-content:space-between;padding:14px 16px;border-bottom:1px solid #e7ecef}.card>header>div{display:grid;gap:3px}.card h2{margin:0;font-size:14px}.card header span{color:#87929a;font-size:8px}.card header button{border:0;background:none;color:#c66f00;font-weight:800}.card header>strong{color:#77848e;font-size:9px}.ranking-section{margin-bottom:12px}.table-scroll{overflow:auto}table{width:100%;border-collapse:collapse;white-space:nowrap}th{padding:10px 12px;background:#f8fafb;color:#6f7c86;font-size:8px;text-align:left}td{padding:9px 12px;border-top:1px solid #edf1f3;color:#475660;font-size:9px}td>b{display:block;color:#25343e}td small{display:block;margin-top:3px;color:#8b969e;font-size:7px}.rank{display:grid;width:22px;height:22px;place-items:center;border-radius:50%;background:#eef1f3;color:#5f6c76;font-style:normal;font-weight:900}.rank-1{background:#ffb31b;color:#fff}.rank-2{background:#aeb8c1;color:#fff}.rank-3{background:#d88a4c;color:#fff}.person{display:flex;align-items:center;gap:8px}.person>i{display:grid;width:29px;height:29px;place-items:center;border-radius:50%;background:#142b40;color:#fff;font-size:8px;font-style:normal;font-weight:900}.person span{display:grid}.conversion{color:#168653;font-style:normal;font-weight:900}.empty{padding:36px!important;color:#8a969e!important;text-align:center!important}.category-card,.detail-card{margin-top:12px}.data-warning{padding:10px 15px;border-bottom:1px solid #f1d7ab;background:#fff8ea;color:#93601b;font-size:9px}.category-toolbar,.detail-toolbar{display:flex;align-items:center;gap:9px;padding:10px 14px;border-bottom:1px solid #e8edf0}.category-toolbar>label:first-child,.detail-toolbar>label{display:flex;width:270px;height:34px;align-items:center;gap:6px;padding:0 9px;border:1px solid #dbe2e6;border-radius:6px}.category-toolbar input,.detail-toolbar input{width:100%;border:0;outline:0}.category-toolbar>div{display:flex;gap:4px}.category-toolbar button{height:32px;border:0;border-radius:5px;background:#f0f3f5;color:#65727c;padding:0 11px}.category-toolbar button.active{background:#176ed0;color:#fff;font-weight:900}.category-toolbar>label:last-child{display:flex;width:auto;height:auto;align-items:center;gap:6px;margin-left:auto;border:0}.category-toolbar select{height:34px;border:1px solid #dbe2e6;border-radius:6px;background:#fff;padding:0 9px}.detail-toolbar span{padding:6px 9px;border-radius:10px;background:#f0f3f5;color:#65727b;font-size:8px}.detail-card td a{color:#1672d3;font-weight:900;text-decoration:none}.pagination{display:flex;align-items:center;justify-content:flex-end;gap:8px;padding:10px 14px;border-top:1px solid #e7ecef;background:#fafbfc;color:#687680;font-size:8px}.pagination>span{margin-right:auto}.pagination select,.pagination button{height:29px;border:1px solid #dbe2e7;border-radius:5px;background:#fff;padding:0 8px}.pagination button:disabled{opacity:.4}.pagination b{min-width:58px;text-align:center}.toast{position:fixed;right:24px;bottom:24px;z-index:100;padding:13px 18px;border-radius:8px;background:#14283b;color:#fff;font-size:10px;box-shadow:0 12px 30px rgba(0,0,0,.2)}.v-enter-active,.v-leave-active{transition:.2s}.v-enter-from,.v-leave-to{opacity:0;transform:translateY(7px)}
@media(max-width:1180px){.overview-app main{width:calc(100% - 28px)}.global-filters{flex-wrap:wrap}.global-search{width:100%;max-width:none}.kpis{grid-template-columns:1fr 1fr}}
@media(max-width:700px){.overview-app main{width:calc(100% - 16px);padding-top:20px}.page-heading{align-items:flex-start}.page-heading h1{font-size:24px}.page-heading span{display:none}.kpis{grid-template-columns:1fr}.global-filters label:not(.global-search){flex:1;min-width:130px}.category-toolbar,.detail-toolbar{align-items:stretch;flex-wrap:wrap}.category-toolbar>label:first-child,.detail-toolbar>label{box-sizing:border-box;width:100%}.category-toolbar>label:last-child{width:100%;margin-left:0}.card .table-scroll table{min-width:900px}.pagination{position:sticky;left:0}}
</style>
