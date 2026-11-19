<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { currentAuthUser, hasPermission } from '@/data/authStore'
import type { DashboardFilters } from '@/data/quotationAnalytics'
import { loadChannelRanking, type ChannelRankingResult, type ChannelRankingRow, type RankingState } from '@/services/quotationChannelRanking'

const props = defineProps<{ filters: DashboardFilters }>()
const data = ref<ChannelRankingResult | null>(null), loading = ref(true), error = ref('')
const expanded = ref(false), pendingOnly = ref(false), page = ref(0), pageSize = ref(20)
const selected = ref<ChannelRankingRow | null>(null)
const size = computed(() => expanded.value ? pageSize.value : 5)
let controller: AbortController | undefined, timer: ReturnType<typeof setTimeout> | undefined, generation = 0
const labels: Record<RankingState, string> = { 'pending-version': '渠道有待审版本', unverified: '待核查', unmatched: '待匹配渠道', historical: '历史渠道' }
const dateTime = (value: string) => new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }).format(new Date(value))
function resetRequest() {
  clearTimeout(timer); controller?.abort(); generation++; data.value = null; selected.value = null; error.value = ''; loading.value = true
}
async function refresh() {
  clearTimeout(timer); controller?.abort()
  const id = ++generation, account = currentAuthUser.value.account
  controller = new AbortController()
  loading.value = true; error.value = ''; data.value = null; selected.value = null
  try {
    const result = await loadChannelRanking({ ...props.filters }, page.value, size.value, pendingOnly.value, controller.signal)
    if (id !== generation || account !== currentAuthUser.value.account) return
    data.value = result
  } catch (e) {
    if (id !== generation || controller.signal.aborted || account !== currentAuthUser.value.account) return
    error.value = e instanceof Error ? e.message : '渠道排行加载失败，请重试'
  } finally { if (id === generation) loading.value = false }
}
watch(() => [props.filters, currentAuthUser.value.account], () => {
  resetRequest(); page.value = 0
  timer = setTimeout(() => void refresh(), 300)
}, { immediate: true, deep: true, flush: 'sync' })
onUnmounted(() => { resetRequest() })
function mode(pending: boolean) { pendingOnly.value = pending; page.value = 0; void refresh() }
function expand() { expanded.value = !expanded.value; page.value = 0; void refresh() }
function goPage(next: number) { page.value = next; void refresh() }
function changeSize(event: Event) { pageSize.value = Number((event.target as HTMLSelectElement).value); page.value = 0; void refresh() }
function exportPage() {
  if (!data.value) return
  const csv = (value: unknown) => {
    const text = String(value ?? '')
    return '"' + (/^[=+@\-\t\r]/.test(text) ? "'" : '') + text.replace(/"/g, '""') + '"'
  }
  const rows = [['国家', '物流商', '渠道', '使用次数', '占比%', '核查提醒', '最近报价', '开始日期', '结束日期'],
    ...data.value.items.map(r => [r.country, r.provider, r.channel, r.uses, r.share, labels[r.state], r.latestAt, data.value!.startDate, data.value!.endDate])]
  const url = URL.createObjectURL(new Blob(['\uFEFF' + rows.map(row => row.map(csv).join(',')).join('\r\n')], { type: 'text/csv;charset=utf-8' }))
  const link = document.createElement('a'); link.href = url; link.download = '报价渠道排行-本页.csv'; link.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
</script>
<template>
  <section class="channel-ranking" aria-label="报价渠道排行榜" :aria-busy="loading">
    <header><div><h2>报价渠道排行榜</h2><p>按已保存报价统计常报国家与渠道，安排重点核查</p></div><button :disabled="loading" @click="refresh">刷新排行</button></header>
    <div class="ranking-toolbar"><nav><button :class="{active:!pendingOnly}" :disabled="loading" @click="mode(false)">常报排行</button><button :class="{active:pendingOnly}" :disabled="loading" @click="mode(true)">有待审版本 <b v-if="data">{{data.pendingGroups}}</b></button></nav><span>{{filters.country || '全部国家'}} · 与顶部筛选联动</span></div>
    <p v-if="loading" class="ranking-message" role="status">正在统计渠道使用情况…</p>
    <div v-else-if="error" class="ranking-message ranking-error" role="alert">{{error}} <button @click="refresh">重新读取排行</button></div>
    <template v-else-if="data">
      <div class="ranking-summary"><span>国家渠道使用 <b>{{data.totalUses.toLocaleString()}}</b> 次</span><span>前 5 条占 <b>{{data.totalUses ? data.topFiveShare+'%' : '—'}}</b></span><span>有待审版本 <b>{{data.pendingGroups}}</b> 条</span></div>
      <p v-if="data.unmatchedGroups" class="matching-notice">{{data.unmatchedGroups}} 条历史国家渠道尚未匹配当前资料，使用次数保留；请核实渠道身份后再核价。</p>
      <div class="ranking-scroll"><table><thead><tr><th>排名</th><th>国家</th><th>物流商 / 渠道</th><th>使用次数 / 占比</th><th>核查提醒</th><th>操作</th></tr></thead><tbody>
        <tr v-for="(row,i) in data.items" :key="row.key"><td><i :class="{top:data.page*data.size+i<3}">{{data.page*data.size+i+1}}</i></td><td>{{row.country}}</td><td><b>{{row.provider}}</b><small>{{row.channel}}</small></td><td><strong>{{row.uses}}</strong><span class="ranking-share">{{row.share}}%</span></td><td><span class="ranking-status" :class="row.state">{{labels[row.state]}}</span></td><td><button class="ranking-link" @click="selected=row">查看重点 →</button></td></tr>
        <tr v-if="!data.items.length"><td colspan="6" class="ranking-empty">{{pendingOnly ? '当前范围没有已识别的待审渠道版本；其他渠道仍可按频次核查。' : '当前筛选范围暂无有效渠道使用记录。'}}</td></tr>
      </tbody></table></div>
      <footer><span>{{data.startDate}} 至 {{data.endDate}} · 更新 {{dateTime(data.generatedAt)}}<small>同单同国家同渠道只计一次；占比按当前业务筛选范围全部线路计算。</small></span><button v-if="data.total>5 || expanded" @click="expand">{{expanded ? '收起为前 5 条' : '查看全部 '+data.total+' 条'}} →</button></footer>
      <div v-if="expanded" class="ranking-pagination"><span>共 {{data.total}} 条</span><label>每页 <select :value="pageSize" @change="changeSize"><option :value="20">20</option><option :value="50">50</option></select> 条</label><button :disabled="data.page===0" @click="goPage(data.page-1)">上一页</button><span>{{data.totalPages ? data.page+1 : 0}} / {{data.totalPages}}</span><button :disabled="data.page+1>=data.totalPages" @click="goPage(data.page+1)">下一页</button><button :disabled="!data.items.length" @click="exportPage">导出本页</button></div>
      <p class="ranking-footnote">“待核查”表示尚无独立的线路核查证据；“有待审版本”为渠道级提示，具体国家影响需核对原价表。不改变报价审核状态。</p>
    </template>
  </section>
  <div v-if="selected" class="ranking-overlay" @click.self="selected=null" @keydown.esc="selected=null">
    <section class="ranking-dialog" role="dialog" aria-modal="true" aria-label="渠道核查重点" tabindex="-1"><button class="close" @click="selected=null">关闭 ×</button><small>当前筛选范围 · 真实报价统计</small><h2>{{selected.country}} · {{selected.provider}}</h2><h3>{{selected.channel}}</h3><p>使用 <b>{{selected.uses}}</b> 次 · 占比 {{selected.share}}%<br>最近报价：{{dateTime(selected.latestAt)}}（北京时间）</p><p>{{labels[selected.state]}}。{{selected.state==='pending-version' ? '该渠道存在未发布草稿，请核实本国家是否受影响。' : selected.state==='unmatched' ? '无法唯一匹配当前渠道，需先核实原报价的渠道身份。' : selected.state==='historical' ? '原报价渠道不在当前生效库，历史使用仍保留统计。' : '没有独立核查结论，不代表报价有误。'}}</p><ul><li>核对原始价表、渠道代码、国家区域和生效日期。</li><li>抽查常报重量、最低计费、重量段边界和进位方式。</li><li>核对运费、挂号费及独立税费、附加费。</li></ul><p class="ranking-footnote">本页只读统计，不重算历史报价，不自动审核或发布物流版本。</p><RouterLink v-if="hasPermission('logistics') && selected.channelId" :to="{path:'/quotation/logistics',query:{dataset:selected.datasetId,rankingChannel:selected.channelId}}">前往物流核对 →</RouterLink></section>
  </div>
</template>
<style scoped>
.channel-ranking{margin:16px 0;background:#fff;border:1px solid #dce4e9;border-radius:10px;overflow:hidden;color:#2d4353;font-size:14px}.channel-ranking>header{display:flex;align-items:center;justify-content:space-between;padding:20px 24px}.channel-ranking h2{font-size:21px;margin:0 0 8px}.channel-ranking header p{margin:0;color:#86949d;font-size:12px}.channel-ranking button{font:inherit;cursor:pointer;border:1px solid #dfe5e9;background:#fff;border-radius:6px;color:#526d7f;padding:8px 12px}.channel-ranking button:disabled{opacity:.45;cursor:default}.ranking-toolbar{display:flex;align-items:center;justify-content:space-between;padding:0 24px;border-bottom:1px solid #e5ebef}.ranking-toolbar nav{display:flex;gap:24px}.ranking-toolbar button{border:0;border-radius:0;border-bottom:3px solid transparent;padding:13px 0}.ranking-toolbar button.active{color:#aa6c20;border-bottom-color:#f49c28;font-weight:700}.ranking-toolbar b{border-radius:10px;padding:2px 6px;background:#fff1db;font-size:11px;margin-left:4px}.ranking-toolbar>span{font-size:12px;color:#81939f}.ranking-summary{display:flex;gap:32px;margin:18px 24px;padding:15px;background:#f6f8fa;border:1px solid #e6ebef;border-radius:7px;color:#80909b}.ranking-summary b{font-size:24px;color:#344f62;margin:0 5px}.ranking-scroll{overflow:auto}.channel-ranking table{width:100%;border-collapse:collapse;text-align:left;white-space:nowrap}.channel-ranking th{padding:13px 20px;color:#83939f;font-size:12px;font-weight:500;background:#f8fafb}.channel-ranking td{padding:16px 20px;border-top:1px solid #eaf0f3;font-size:15px}.channel-ranking td small{display:block;margin-top:4px;color:#8395a2;font-size:13px}.channel-ranking td i{display:grid;place-items:center;width:27px;height:27px;border-radius:6px;background:#f0f3f5;color:#85959f;font-style:normal;font-size:13px}.channel-ranking td i.top{background:#fff0d9;color:#af7126}.channel-ranking td strong{font-size:23px;font-variant-numeric:tabular-nums}.ranking-share{margin-left:12px;color:#96a0a8;font-size:12px}.ranking-status{font-size:12px;border-radius:5px;padding:5px 8px;display:inline-block;background:#f0f3f6;color:#7d8f9c}.ranking-status.pending-version{background:#fff0da;color:#ac6e22}.ranking-status.unmatched{background:#fff4e5;color:#a17c40}.channel-ranking .ranking-link{border:0;background:none;padding:0;font-size:13px;color:#b37729}.channel-ranking>footer{display:flex;align-items:center;justify-content:space-between;border-top:1px solid #e5ebef;padding:14px 24px;font-size:11px;color:#87959f}.channel-ranking>footer small{display:block;font-size:11px;margin-top:5px}.channel-ranking>footer button{border:0;color:#ac742a;font-size:13px}.ranking-pagination{display:flex;align-items:center;justify-content:flex-end;gap:12px;padding:12px 24px;border-top:1px solid #e7ecf0;font-size:12px;color:#7f919e}.ranking-pagination>span:first-child{margin-right:auto}.ranking-pagination select{border:1px solid #dce5eb;border-radius:4px;padding:5px;color:inherit}.ranking-footnote{color:#8b98a2;font-size:11px;line-height:1.8;padding:0 24px 12px;margin:0}.ranking-message{padding:30px 24px;color:#80909d;min-height:120px}.ranking-error{color:#a66c20;background:#fff9ed}.matching-notice{padding:0 24px;font-size:12px;color:#aa7c36}.channel-ranking td.ranking-empty{padding:32px;text-align:center;font-size:13px;color:#8698a5}.ranking-overlay{position:fixed;inset:0;z-index:100;background:#1c314469;display:grid;place-items:center}.ranking-dialog{width:560px;max-width:94vw;max-height:90vh;overflow:auto;background:#fff;border-radius:12px;padding:28px;color:#345064;box-shadow:0 20px 70px #152e4740}.ranking-dialog .close{float:right;background:none;border:0;color:#8c9da8;cursor:pointer}.ranking-dialog>small{color:#a47c43}.ranking-dialog h2{font-size:23px}.ranking-dialog h3{font-size:16px;color:#7d929f}.ranking-dialog p,.ranking-dialog li{font-size:14px;line-height:1.9}.ranking-dialog .ranking-footnote{padding:12px;background:#f6f8fa;font-size:12px}.ranking-dialog a{display:inline-block;color:#aa762a;margin-top:16px;text-decoration:none;font-size:14px}@media(max-width:1000px){.ranking-summary{gap:12px;flex-wrap:wrap}.ranking-toolbar{gap:12px;flex-wrap:wrap}.channel-ranking table{min-width:850px}.ranking-pagination{flex-wrap:wrap}.channel-ranking>header{padding:18px}.channel-ranking>footer{gap:12px;align-items:flex-start}}
</style>
