<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { loadPurchaseRankings, rankingsCsv, type PurchaseRankings, type RankingScope } from '@/services/purchaseRankings'

const props = defineProps<{ refreshKey?: number }>()
const expanded = ref(false)
const scope = ref<RankingScope>('all')
const days = ref(30)
const data = ref<PurchaseRankings | null>(null)
const loading = ref(false)
const error = ref('')
let request = 0
let abort: AbortController | undefined
async function refresh() {
  if (!expanded.value) return
  const id = ++request
  abort?.abort()
  const controller = new AbortController(); abort = controller
  loading.value = true; error.value = ''; data.value = null
  try {
    const result = await loadPurchaseRankings(scope.value, days.value, controller.signal)
    if (id === request) data.value = result
  } catch (reason) {
    if (id === request && !controller.signal.aborted) error.value = reason instanceof Error ? reason.message : '统计读取失败，请重试'
  } finally { if (id === request) loading.value = false }
}
watch([expanded, scope, days, () => props.refreshKey], () => {
  if (expanded.value) void refresh()
  else { request++; abort?.abort(); loading.value = false }
})
onUnmounted(() => { request++; abort?.abort() })
const completion = computed(() => data.value?.completion.filter(row => row.owner) ?? [])
const exceptions = computed(() => data.value?.exceptions.filter(row => row.owner) ?? [])
const unassignedData = computed(() => data.value?.completion.find(row => !row.owner))
const unassignedExceptions = computed(() => data.value?.exceptions.find(row => !row.owner))
const highest = computed(() => completion.value[0])
const lowest = computed(() => completion.value.at(-1))
const most = computed(() => exceptions.value[0])
const least = computed(() => exceptions.value.at(-1))
const number = (n: number) => n.toLocaleString('zh-CN')
const rate = (n: number) => `${n.toFixed(1)}%`
function exportRanking() {
  if (!data.value || loading.value || error.value) return
  const url = URL.createObjectURL(new Blob([rankingsCsv(data.value)], { type: 'text/csv;charset=utf-8' }))
  const link = document.createElement('a'); link.href = url
  link.download = `采购人员排名-${scope.value === 'sales' ? '在售SKU' : '全部采购'}-${days.value ? `近${days.value}天` : '全部时间'}.csv`
  link.click(); window.setTimeout(() => URL.revokeObjectURL(url), 1000)
}
</script>

<template>
  <section class="rankings" :class="{ collapsed: !expanded }" aria-labelledby="purchase-rankings-title" :aria-busy="loading">
    <header class="ranking-header">
      <div><h2 id="purchase-rankings-title">采购人员排名</h2><p v-if="expanded">比较资料补全情况与采购价格异常次数</p></div>
      <div class="ranking-header-actions"><small v-if="expanded && data">更新于 {{ new Date(data.to).toLocaleString('zh-CN') }}</small><button class="ranking-toggle" :aria-expanded="expanded" aria-controls="purchase-rankings-content" @click="expanded = !expanded">{{ expanded ? '收起' : '展开' }} <span aria-hidden="true">{{ expanded ? '⌃' : '⌄' }}</span></button></div>
    </header>
    <div v-if="expanded" id="purchase-rankings-content">
    <div class="ranking-toolbar">
      <span>统计范围：</span><div class="scope-buttons"><button :class="{ active: scope === 'all' }" :aria-pressed="scope === 'all'" @click="scope = 'all'">全部采购</button><button :class="{ active: scope === 'sales' }" :aria-pressed="scope === 'sales'" @click="scope = 'sales'">在售 SKU</button></div>
      <div class="ranking-actions"><button :disabled="loading" @click="refresh">{{ loading ? '统计中…' : '↻ 刷新统计' }}</button><button :disabled="!data || loading || !!error" @click="exportRanking">导出排名</button></div>
    </div>
    <p v-if="error" class="ranking-error" role="alert">{{ error }} <button @click="refresh">重试</button></p>
    <div class="ranking-grid">
      <article class="ranking-card">
        <header><div><h3>资料补全率排名 <small class="current-badge">当前资料</small></h3><p>按补全率从高到低</p></div><div v-if="highest && lowest" class="highlights"><span class="good">最高 {{ highest.owner }} <b>{{ rate(highest.rate) }}</b></span><span class="warn">最低 {{ lowest.owner }} <b>{{ rate(lowest.rate) }}</b></span></div></header>
        <div class="table-scroll"><table><thead><tr><th>排名</th><th>采购人员</th><th>资料补全率</th><th>已完整 / 总 SKU</th><th>待补 SKU</th></tr></thead><tbody>
          <tr v-for="(row, index) in completion" :key="row.owner" :class="{ best: index === 0, pending: index === completion.length - 1 && index > 0 }"><td>{{ index + 1 }}</td><th scope="row">{{ row.owner }}</th><td><div class="bar-cell"><span class="bar"><i :style="{ width: `${row.rate}%` }" /></span><b>{{ rate(row.rate) }}</b></div></td><td>{{ number(row.complete) }} / {{ number(row.total) }}</td><td>{{ number(row.pending) }}</td></tr>
          <tr v-if="!completion.length"><td colspan="5" class="empty">{{ loading ? '正在读取统计…' : error ? '统计暂不可用' : '当前范围暂无已归属采购资料' }}</td></tr>
          <tr v-if="unassignedData" class="unassigned"><td>—</td><th scope="row">未归属采购人</th><td>{{ rate(unassignedData.rate) }}</td><td>{{ number(unassignedData.complete) }} / {{ number(unassignedData.total) }}</td><td>{{ number(unassignedData.pending) }}</td></tr>
        </tbody></table></div>
      </article>
      <article class="ranking-card exception-card">
        <header><div><h3>采购价格异常次数排名</h3><p>异常最多在前 · 以正式审核时间为准</p></div><label><select v-model.number="days" aria-label="异常排名时间范围"><option :value="7">近7天</option><option :value="30">近30天</option><option :value="90">近90天</option><option :value="0">全部时间</option></select></label></header>
        <div v-if="most && least" class="exception-highlights highlights"><span class="bad">最多 {{ most.owner }} <b>{{ number(most.count) }} 项</b></span><span class="good">最少 {{ least.owner }} <b>{{ number(least.count) }} 项</b></span></div>
        <div class="table-scroll"><table><thead><tr><th>排名</th><th>采购人员</th><th>异常项数</th><th>关联报价数</th><th>异常占比</th></tr></thead><tbody>
          <tr v-for="(row, index) in exceptions" :key="row.owner" :class="{ worst: index === 0 && row.count > 0, best: index === exceptions.length - 1 && index > 0 }"><td>{{ index + 1 }}</td><th scope="row">{{ row.owner }}</th><td><div class="bar-cell"><span class="bar"><i :style="{ width: `${most?.count ? row.count / most.count * 100 : 0}%` }" /></span><b>{{ number(row.count) }} 项</b></div></td><td>{{ number(row.relatedQuotes) }}</td><td :title="`${row.abnormalQuotes} 条异常报价 / ${row.relatedQuotes} 条关联报价`">{{ row.relatedQuotes ? rate(row.rate) : '—' }}</td></tr>
          <tr v-if="!exceptions.length"><td colspan="5" class="empty">{{ loading ? '正在读取统计…' : error ? '统计暂不可用' : '当前范围暂无采购人员统计' }}</td></tr>
          <tr v-if="unassignedExceptions" class="unassigned"><td>—</td><th scope="row">待归属</th><td>{{ number(unassignedExceptions.count) }} 项</td><td>{{ number(unassignedExceptions.relatedQuotes) }}</td><td>{{ unassignedExceptions.relatedQuotes ? rate(unassignedExceptions.rate) : '—' }}</td></tr>
        </tbody></table></div>
      </article>
    </div>
    <details class="ranking-help">
      <summary>统计说明 <span class="help-expand">点击展开</span><span class="help-collapse">收起</span></summary>
      <div class="help-content">
        <p><b>统计范围：</b>“全部采购”统计当前权限内全部采购资料；“在售 SKU”仅匹配已有销量表中的主 SKU，未匹配项不计入个人补全率。<template v-if="scope === 'sales' && data">当前销量期间：{{ data.salesPeriod || '已配置期间' }}，未匹配采购资料 {{ number(data.unmatchedSalesSkus) }} 个 SKU。</template></p>
        <p><b>资料补全：</b>补全率 = 资料完整 SKU ÷ 名下总 SKU。按重量、起订量、采购价、票点、运费判断；FOB 按重量原文及已解析价格、运费判断。与“可报价”资格分开统计。同一 SKU 只计一次，多份资料均完整才算补全。绿色突出最高补全率，浅橙色提示最低。</p>
        <p><b>异常统计：</b>仅统计正式提交的“采购价格异常”，以审核发生时间为准。同一报价同一 SKU 多次标记仅计 1 项，多 SKU 可计多项。异常占比 = 异常报价数 ÷ 同期已审核关联报价数，不代表采购责任认定。</p>
        <p><b>人员归属：</b>资料按当前采购报价人归属，归属不一致或未填写人员单列；采购异常按审核时保存的人员归属，非异常关联报价按当前采购人关联。</p>
        <p v-if="data"><b>历史记录：</b>未分类 {{ number(data.unclassified) }} 条报价；缺审核时间 {{ number(data.undated) }} 条（未计入时间排名）。</p>
      </div>
    </details>
    </div>
  </section>
</template>

<style scoped>
.rankings.collapsed{padding:12px 20px}.rankings.collapsed .ranking-header{flex-direction:row;align-items:center}.rankings.collapsed h2{font-size:16px}.ranking-header-actions{display:flex;align-items:center;gap:12px}.ranking-header-actions small{color:#708196;font-size:11px}.rankings .ranking-toggle{border-color:#ffd392;color:#a96000;white-space:nowrap;min-width:76px}.ranking-toggle span{margin-left:6px}@media(max-width:600px){.ranking-header-actions{width:100%;justify-content:space-between}.collapsed .ranking-header-actions{width:auto}.ranking-header-actions small{font-size:10px}}
.ranking-help{margin-top:10px;border-top:1px solid #e6edf3;color:#64788f;font-size:12px}.ranking-help summary{cursor:pointer;padding:10px 0 0;width:fit-content;line-height:20px}.ranking-help summary:focus-visible{outline:2px solid #ff9800;outline-offset:3px;border-radius:3px}.ranking-help summary span{margin-left:8px;color:#8a98a8;font-size:11px}.help-collapse,.ranking-help[open] .help-expand{display:none}.ranking-help[open] .help-collapse{display:inline}.help-content{padding:8px 12px;background:#f7f9fc;border-radius:6px;margin-top:8px;line-height:1.8}.help-content p{margin:5px 0}.help-content b{color:#425b76}
.rankings{margin:16px 0;padding:20px;border:1px solid #dce4ec;border-radius:10px;background:#fff;color:#17304a}.ranking-header,.ranking-card header{display:flex;justify-content:space-between;align-items:center;gap:12px}.ranking-header h2{margin:0;font-size:25px}.ranking-header p,.ranking-card p{margin:5px 0 0;color:#708196;font-size:12px}.ranking-header>small{color:#708196}.ranking-toolbar{display:flex;align-items:center;gap:12px;flex-wrap:wrap;border:1px solid #e3e9ef;border-radius:8px;padding:12px;margin-top:16px;font-size:12px}.rankings button,.rankings select{font:inherit;border:1px solid #dce4ec;border-radius:5px;background:white;color:#17304a;min-height:34px;padding:6px 12px;cursor:pointer}.rankings button:disabled{opacity:.5;cursor:default}.rankings button:focus-visible,.rankings select:focus-visible{outline:2px solid #ff9800;outline-offset:2px}.ranking-toolbar label{display:flex;align-items:center;gap:8px;margin-left:12px}.scope-buttons{display:flex}.scope-buttons button{border-radius:0}.scope-buttons button:first-child{border-radius:5px 0 0 5px}.scope-buttons button:last-child{border-radius:0 5px 5px 0}.scope-buttons .active{background:#ff9800;color:#fff;border-color:#ff9800;font-weight:700}.ranking-actions{display:flex;gap:8px;margin-left:auto}.ranking-grid{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1fr);gap:14px;margin-top:12px}.ranking-card{min-width:0;padding:14px;border:1px solid #e0e7ee;border-radius:8px}.ranking-card header{align-items:flex-start;flex-wrap:wrap;min-height:66px}.ranking-card h3{margin:0;font-size:18px}.current-badge{display:inline-block;padding:3px 7px;background:#edf1f5;border-radius:5px;font-size:10px;font-weight:500;vertical-align:middle}.highlights{display:flex;flex-wrap:wrap;gap:6px;font-size:11px}.highlights span{padding:7px 10px;border-radius:5px;display:flex;gap:9px;align-items:center}.highlights b{font-size:13px}.good{background:#e8f8ef;color:#08794d}.warn{background:#fff5e2;color:#9c5700}.bad{background:#fff0ef;color:#c42e22}.exception-highlights{margin:-6px 0 10px}.table-scroll{overflow:auto;max-height:430px}.rankings table{width:100%;border-collapse:collapse;font-size:12px;white-space:nowrap;text-align:left}.rankings th,.rankings td{padding:11px 8px;border-bottom:1px solid #e6edf3}.rankings thead th{position:sticky;top:0;background:#f5f7f9;color:#596b80;font-weight:600;font-size:11px}.rankings tbody th{max-width:130px;white-space:normal;overflow-wrap:anywhere}.bar-cell{display:flex;align-items:center;gap:10px;min-width:135px}.bar-cell b{min-width:48px}.bar{height:12px;border-radius:4px;background:#e2e8ef;display:block;flex:1;min-width:60px;overflow:hidden}.bar i{display:block;height:100%;background:linear-gradient(90deg,#16bb83,#0eaf6a);border-radius:4px}.exception-card .bar i{background:linear-gradient(90deg,#ff795e,#ff4d38)}.best{background:#ebf9f1}.pending{background:#fff8e9}.worst{background:#fff0ef}.rankings .empty{text-align:center;padding:35px 10px;color:#8490a0}.unassigned{color:#6d7784;background:#f5f6f8}.ranking-error{color:#b42318;background:#fff0ef;padding:10px;border-radius:5px;font-size:13px}@media(max-width:1150px){.ranking-grid{grid-template-columns:1fr}.ranking-card header{min-height:50px}.exception-highlights{margin:0 0 10px}}@media(max-width:600px){.rankings{padding:12px}.ranking-header{align-items:flex-start;flex-direction:column}.ranking-header h2{font-size:22px}.ranking-actions{margin-left:0;width:100%}.ranking-toolbar{gap:8px}.ranking-toolbar label{margin-left:0}.ranking-card{padding:10px}}
</style>
