<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { loadPurchaseRankings, rankingsCsv, completionRankings, rankingSummary, type CompletionOrder, type PurchaseRankings, type RankingScope } from '@/services/purchaseRankings'

const expanded = ref(false)
const scope = ref<RankingScope>('all')
const days = ref(30)
const order = ref<CompletionOrder>('quantity')
type Snapshot = { data: PurchaseRankings | null; loading: boolean; error: string }
const snapshots = reactive(new Map<string, Snapshot>())
const controllers = new Set<AbortController>()
const selection = computed(() => `${scope.value}:${days.value}`)
const current = computed(() => snapshots.get(selection.value))
const data = computed(() => current.value?.data ?? null)
const loading = computed(() => current.value?.loading ?? false)
const error = computed(() => current.value?.error ?? '')
async function load(force = false) {
  if (!expanded.value) return
  const key = selection.value
  let snapshot = snapshots.get(key)
  // Keep each viewed selection until an explicit refresh, including failed reads.
  if (snapshot && (snapshot.loading || !force)) return
  if (!snapshot) {
    snapshots.set(key, { data: null, loading: false, error: '' })
    snapshot = snapshots.get(key)!
  }
  const controller = new AbortController(); controllers.add(controller)
  snapshot.loading = true; snapshot.error = ''
  try {
    const result = await loadPurchaseRankings(scope.value, days.value, controller.signal)
    if (!controller.signal.aborted) snapshot.data = result
  } catch (reason) {
    if (!controller.signal.aborted) snapshot.error = (snapshot.data ? '更新失败，仍显示上次结果。' : '') + (reason instanceof Error ? reason.message : '统计读取失败，请重试')
  } finally { snapshot.loading = false; controllers.delete(controller) }
}
function refresh() { void load(true) }
watch([expanded, scope, days], () => { if (expanded.value) void load() })
onUnmounted(() => { controllers.forEach(controller => controller.abort()); controllers.clear() })
const completion = computed(() => completionRankings(data.value?.completion ?? [], order.value))
const summary = computed(() => rankingSummary(completion.value))
const maxComplete = computed(() => Math.max(0, ...completion.value.map(row => row.complete)))
const exceptions = computed(() => data.value?.exceptions.filter(row => row.owner) ?? [])
const most = computed(() => exceptions.value[0])
const number = (n: number) => n.toLocaleString('zh-CN')
const rate = (n: number) => `${n.toFixed(1)}%`
function exportRanking() {
  if (!data.value || loading.value || error.value) return
  const url = URL.createObjectURL(new Blob([rankingsCsv(data.value, order.value)], { type: 'text/csv;charset=utf-8' }))
  const link = document.createElement('a'); link.href = url
  link.download = `采购人员排名-${scope.value === 'sales' ? '在售SKU' : '全部采购'}-${order.value === 'quantity' ? '已完整数量' : '完整度'}-${days.value ? `近${days.value}天` : '全部时间'}.csv`
  link.click(); window.setTimeout(() => URL.revokeObjectURL(url), 1000)
}
</script>

<template>
  <section class="rankings" :class="{ collapsed: !expanded }" aria-labelledby="purchase-rankings-title" :aria-busy="loading">
    <header class="ranking-header">
      <div><h2 id="purchase-rankings-title">采购人员排名</h2><p v-if="expanded">查看采购账号的资料完整度、已完整数量与采购价格异常</p></div>
      <div class="ranking-header-actions"><small v-if="expanded && data">更新于 {{ new Date(data.to).toLocaleString('zh-CN') }}</small><button class="ranking-toggle" :aria-expanded="expanded" aria-controls="purchase-rankings-content" @click="expanded = !expanded">{{ expanded ? '收起' : '展开' }} <span aria-hidden="true">{{ expanded ? '⌃' : '⌄' }}</span></button></div>
    </header>
    <div v-if="expanded" id="purchase-rankings-content">
    <div class="ranking-toolbar">
      <span>统计范围：</span><div class="scope-buttons"><button :class="{ active: scope === 'all' }" :aria-pressed="scope === 'all'" @click="scope = 'all'">全部采购</button><button :class="{ active: scope === 'sales' }" :aria-pressed="scope === 'sales'" @click="scope = 'sales'">在售 SKU</button></div>
      <div class="ranking-actions"><button :disabled="loading" @click="refresh">{{ loading ? (data ? '更新中…' : '统计中…') : '↻ 刷新统计' }}</button><button :disabled="!data || loading || !!error" @click="exportRanking">导出排名</button></div>
    </div>
    <p v-if="error" class="ranking-error" role="alert">{{ error }} <button @click="refresh">重试</button></p>
    <div v-if="data" class="ranking-summary" aria-label="采购排名汇总">
      <span>采购人员 <b>{{ number(summary.people) }} 人</b></span><span>名下 SKU <b>{{ number(summary.total) }}</b></span><span>已完整 <b class="summary-good">{{ number(summary.complete) }}</b></span><span>待补全 <b class="summary-warn">{{ number(summary.pending) }}</b></span><span>整体补全率 <b class="summary-good">{{ rate(summary.rate) }}</b></span>
    </div>
    <div class="ranking-grid">
      <article class="ranking-card">
        <header class="completion-header"><h3>资料补全情况 <small class="current-badge">当前资料</small></h3></header>
        <div class="scope-buttons ranking-order" role="group" aria-label="资料排名排序"><button :class="{ active: order === 'quality' }" :aria-pressed="order === 'quality'" @click="order = 'quality'">完整度排名</button><button :class="{ active: order === 'quantity' }" :aria-pressed="order === 'quantity'" @click="order = 'quantity'">已完整数量排名</button></div>
        <p class="order-description">{{ order === 'quantity' ? '按已完整 SKU 数从高到低 · 同数量按补全率' : '按补全率从高到低 · 同率按已完整数量' }}</p>
        <div class="table-scroll"><table aria-label="资料补全排名"><thead><tr><th>排名</th><th>采购人员</th><th aria-sort="descending">{{ order === 'quantity' ? '已完整 SKU ↓' : '资料补全率 ↓' }}</th><th>{{ order === 'quantity' ? '补全率' : '已完整 SKU' }}</th><th>总 SKU</th><th>待补全</th></tr></thead><tbody>
          <tr v-for="row in completion" :key="row.owner" :class="{ best: row.rank === 1 }"><td>{{ row.rank }}</td><th scope="row">{{ row.owner }}</th><td><div class="bar-cell"><span class="bar"><i :style="{ width: `${order === 'quantity' ? (maxComplete ? row.complete / maxComplete * 100 : 0) : row.rate}%` }" /></span><b>{{ order === 'quantity' ? number(row.complete) : rate(row.rate) }}</b></div></td><td>{{ order === 'quantity' ? rate(row.rate) : number(row.complete) }}</td><td>{{ number(row.total) }}</td><td>{{ number(row.pending) }}</td></tr>
          <tr v-if="!completion.length"><td colspan="6" class="empty">{{ loading ? '正在读取统计…' : error ? '统计暂不可用' : '当前范围暂无采购账号关联资料' }}</td></tr>
        </tbody></table></div>
      </article>
      <article class="ranking-card exception-card">
        <header><div><h3>采购价格异常次数排名</h3><p>异常最多在前 · 以正式审核时间为准</p></div><label><select v-model.number="days" aria-label="异常排名时间范围"><option :value="7">近7天</option><option :value="30">近30天</option><option :value="90">近90天</option><option :value="0">全部时间</option></select></label></header>
        <div class="table-scroll"><table><thead><tr><th>排名</th><th>采购人员</th><th>异常项数</th><th>关联报价数</th><th>异常占比</th></tr></thead><tbody>
          <tr v-for="(row, index) in exceptions" :key="row.owner" :class="{ worst: index === 0 && row.count > 0 }"><td>{{ index + 1 }}</td><th scope="row">{{ row.owner }}</th><td><div class="bar-cell"><span class="bar"><i :style="{ width: `${most?.count ? row.count / most.count * 100 : 0}%` }" /></span><b>{{ number(row.count) }} 项</b></div></td><td>{{ number(row.relatedQuotes) }}</td><td :title="`${row.abnormalQuotes} 条异常报价 / ${row.relatedQuotes} 条关联报价`">{{ row.relatedQuotes ? rate(row.rate) : '—' }}</td></tr>
          <tr v-if="!exceptions.length"><td colspan="5" class="empty">{{ loading ? '正在读取统计…' : error ? '统计暂不可用' : '当前范围暂无采购人员统计' }}</td></tr>
        </tbody></table></div>
      </article>
    </div>
    <details class="ranking-help">
      <summary>统计说明 <span class="help-expand">点击展开</span><span class="help-collapse">收起</span></summary>
      <div class="help-content">
        <p><b>统计范围：</b>“全部采购”统计当前权限内采购账号关联的采购资料；“在售 SKU”仅匹配已有销量表中的主 SKU，未匹配项不计入个人补全率。<template v-if="scope === 'sales' && data">当前销量期间：{{ data.salesPeriod || '已配置期间' }}，未匹配采购资料 {{ number(data.unmatchedSalesSkus) }} 个 SKU。</template></p>
        <p><b>资料补全：</b>补全率 = 资料完整 SKU ÷ 名下总 SKU。按重量、起订量、采购价、票点、运费判断；FOB 按重量原文及已解析价格、运费判断。与“可报价”资格分开统计。同一 SKU 只计一次，多份资料均完整才算补全。完整度榜按补全率、已完整数量排序；数量榜按已完整数量、补全率排序。按未四舍五入的补全率比较，指标相同并列。当前已完整数量不是本月新增工作量，整体补全率为已完整总数 ÷ SKU 总数。</p>
        <p><b>异常统计：</b>仅统计正式提交的“采购价格异常”，以审核发生时间为准。同一报价同一 SKU 多次标记仅计 1 项，多 SKU 可计多项。异常占比 = 异常报价数 ÷ 同期已审核关联报价数，不代表采购责任认定。</p>
        <p><b>人员归属：</b>仅账号当前角色为“采购”时参与，不以页面访问权限判断。已确认简称、全名和账号合并显示为姓名；非采购、未匹配、重名无法确定或多来源归属冲突均不计入。停用但角色仍为采购的账号仍统计。资料和非异常关联报价按当前采购人关联；采购异常按审核时保存的人员归属匹配账号，不改写历史。</p>
        <p v-if="data"><b>历史记录：</b>未分类 {{ number(data.unclassified) }} 条报价；缺审核时间 {{ number(data.undated) }} 条（未计入时间排名）。</p>
      </div>
    </details>
    </div>
  </section>
</template>

<style scoped>
.ranking-summary{display:flex;flex-wrap:wrap;gap:12px 24px;padding:12px 16px;margin-top:12px;background:#f7fafb;border-radius:6px;font-size:12px}.ranking-summary b{font-size:17px;margin-left:7px}.summary-good{color:#09865b}.summary-warn{color:#b77600}.ranking-card .completion-header{min-height:30px}.ranking-order{margin:6px 0;font-size:12px}.ranking-card .order-description{margin:8px 0 12px}
.rankings.collapsed{padding:12px 20px}.rankings.collapsed .ranking-header{flex-direction:row;align-items:center}.rankings.collapsed h2{font-size:16px}.ranking-header-actions{display:flex;align-items:center;gap:12px}.ranking-header-actions small{color:#708196;font-size:11px}.rankings .ranking-toggle{border-color:#ffd392;color:#a96000;white-space:nowrap;min-width:76px}.ranking-toggle span{margin-left:6px}@media(max-width:600px){.ranking-header-actions{width:100%;justify-content:space-between}.collapsed .ranking-header-actions{width:auto}.ranking-header-actions small{font-size:10px}}
.ranking-help{margin-top:10px;border-top:1px solid #e6edf3;color:#64788f;font-size:12px}.ranking-help summary{cursor:pointer;padding:10px 0 0;width:fit-content;line-height:20px}.ranking-help summary:focus-visible{outline:2px solid #ff9800;outline-offset:3px;border-radius:3px}.ranking-help summary span{margin-left:8px;color:#8a98a8;font-size:11px}.help-collapse,.ranking-help[open] .help-expand{display:none}.ranking-help[open] .help-collapse{display:inline}.help-content{padding:8px 12px;background:#f7f9fc;border-radius:6px;margin-top:8px;line-height:1.8}.help-content p{margin:5px 0}.help-content b{color:#425b76}
.rankings{margin:16px 0;padding:20px;border:1px solid #dce4ec;border-radius:10px;background:#fff;color:#17304a}.ranking-header,.ranking-card header{display:flex;justify-content:space-between;align-items:center;gap:12px}.ranking-header h2{margin:0;font-size:25px}.ranking-header p,.ranking-card p{margin:5px 0 0;color:#708196;font-size:12px}.ranking-header>small{color:#708196}.ranking-toolbar{display:flex;align-items:center;gap:12px;flex-wrap:wrap;border:1px solid #e3e9ef;border-radius:8px;padding:12px;margin-top:16px;font-size:12px}.rankings button,.rankings select{font:inherit;border:1px solid #dce4ec;border-radius:5px;background:white;color:#17304a;min-height:34px;padding:6px 12px;cursor:pointer}.rankings button:disabled{opacity:.5;cursor:default}.rankings button:focus-visible,.rankings select:focus-visible{outline:2px solid #ff9800;outline-offset:2px}.ranking-toolbar label{display:flex;align-items:center;gap:8px;margin-left:12px}.scope-buttons{display:flex}.scope-buttons button{border-radius:0}.scope-buttons button:first-child{border-radius:5px 0 0 5px}.scope-buttons button:last-child{border-radius:0 5px 5px 0}.scope-buttons .active{background:#ff9800;color:#fff;border-color:#ff9800;font-weight:700}.ranking-actions{display:flex;gap:8px;margin-left:auto}.ranking-grid{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1fr);gap:14px;margin-top:12px}.ranking-card{min-width:0;padding:14px;border:1px solid #e0e7ee;border-radius:8px}.ranking-card header{align-items:flex-start;flex-wrap:wrap;min-height:66px}.ranking-card h3{margin:0;font-size:18px}.current-badge{display:inline-block;padding:3px 7px;background:#edf1f5;border-radius:5px;font-size:10px;font-weight:500;vertical-align:middle}.table-scroll{overflow:auto;max-height:430px}.rankings table{width:100%;border-collapse:collapse;font-size:12px;white-space:nowrap;text-align:left}.rankings th,.rankings td{padding:11px 8px;border-bottom:1px solid #e6edf3}.rankings thead th{position:sticky;top:0;background:#f5f7f9;color:#596b80;font-weight:600;font-size:11px}.rankings tbody th{max-width:130px;white-space:normal;overflow-wrap:anywhere}.bar-cell{display:flex;align-items:center;gap:10px;min-width:135px}.bar-cell b{min-width:48px}.bar{height:12px;border-radius:4px;background:#e2e8ef;display:block;flex:1;min-width:60px;overflow:hidden}.bar i{display:block;height:100%;background:linear-gradient(90deg,#16bb83,#0eaf6a);border-radius:4px}.exception-card .bar i{background:linear-gradient(90deg,#ff795e,#ff4d38)}.best{background:#ebf9f1}.worst{background:#fff0ef}.rankings .empty{text-align:center;padding:35px 10px;color:#8490a0}.ranking-error{color:#b42318;background:#fff0ef;padding:10px;border-radius:5px;font-size:13px}@media(max-width:1150px){.ranking-grid{grid-template-columns:1fr}.ranking-card header{min-height:50px}}@media(max-width:600px){.rankings{padding:12px}.ranking-header{align-items:flex-start;flex-direction:column}.ranking-header h2{font-size:22px}.ranking-actions{margin-left:0;width:100%}.ranking-toolbar{gap:8px}.ranking-toolbar label{margin-left:0}.ranking-card{padding:10px}}
</style>
