<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { currentAuthUser } from '@/data/authStore'
import type { DashboardFilters } from '@/data/quotationAnalytics'
import { loadChannelRanking, type ChannelRankingResult } from '@/services/quotationChannelRanking'

const props = defineProps<{ filters: DashboardFilters }>()
const data = ref<ChannelRankingResult | null>(null), loading = ref(true), error = ref('')
const expanded = ref(false), page = ref(0), pageSize = ref(20)
const size = computed(() => expanded.value ? pageSize.value : 5)
let controller: AbortController | undefined, timer: ReturnType<typeof setTimeout> | undefined, generation = 0
const dateTime = (value: string) => new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }).format(new Date(value))
function resetRequest() {
  clearTimeout(timer); controller?.abort(); generation++; data.value = null; error.value = ''; loading.value = true
}
async function refresh() {
  clearTimeout(timer); controller?.abort()
  const id = ++generation, account = currentAuthUser.value.account
  controller = new AbortController()
  loading.value = true; error.value = ''; data.value = null
  try {
    const result = await loadChannelRanking({ ...props.filters }, page.value, size.value, false, controller.signal)
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
function expand() { expanded.value = !expanded.value; page.value = 0; void refresh() }
function goPage(next: number) { page.value = next; void refresh() }
function changeSize(event: Event) { pageSize.value = Number((event.target as HTMLSelectElement).value); page.value = 0; void refresh() }
function exportPage() {
  if (!data.value) return
  const csv = (value: unknown) => {
    const text = String(value ?? '')
    return '"' + (/^[=+@\-\t\r]/.test(text) ? "'" : '') + text.replace(/"/g, '""') + '"'
  }
  const rows = [['国家', '物流商', '渠道', '使用次数', '占比%', '最近报价', '开始日期', '结束日期'],
    ...data.value.items.map(r => [r.country, r.provider, r.channel, r.uses, r.share, r.latestAt, data.value!.startDate, data.value!.endDate])]
  const url = URL.createObjectURL(new Blob(['\uFEFF' + rows.map(row => row.map(csv).join(',')).join('\r\n')], { type: 'text/csv;charset=utf-8' }))
  const link = document.createElement('a'); link.href = url; link.download = '报价渠道排行-本页.csv'; link.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
</script>
<template>
  <section class="channel-ranking" aria-label="报价渠道排行榜" :aria-busy="loading">
    <header><div><h2>报价渠道排行榜</h2></div><button :disabled="loading" @click="refresh">刷新排行</button></header>
    <p v-if="loading" class="ranking-message" role="status">正在统计渠道使用情况…</p>
    <div v-else-if="error" class="ranking-message ranking-error" role="alert">{{error}} <button @click="refresh">重新读取排行</button></div>
    <template v-else-if="data">
      <div class="ranking-summary"><span>国家渠道使用 <b>{{data.totalUses.toLocaleString()}}</b> 次</span><span>前 5 条占 <b>{{data.totalUses ? data.topFiveShare+'%' : '—'}}</b></span></div>
      <div class="ranking-scroll"><table><colgroup><col class="rank-column"><col class="country-column"><col class="channel-column"><col class="usage-column"></colgroup><thead><tr><th>排名</th><th>国家</th><th>物流商 / 渠道</th><th>使用次数 / 占比</th></tr></thead><tbody>
        <tr v-for="(row,i) in data.items" :key="row.key"><td><i :class="{top:data.page*data.size+i<3}">{{data.page*data.size+i+1}}</i></td><td>{{row.country}}</td><td><b>{{row.provider}}</b><small>{{row.channel}}</small></td><td><strong>{{row.uses}}</strong><span class="ranking-share">{{row.share}}%</span></td></tr>
        <tr v-if="!data.items.length"><td colspan="4" class="ranking-empty">当前筛选范围暂无有效渠道使用记录。</td></tr>
      </tbody></table></div>
      <footer><span>{{data.startDate}} 至 {{data.endDate}} · 更新 {{dateTime(data.generatedAt)}}</span><button v-if="data.total>5 || expanded" @click="expand">{{expanded ? '收起为前 5 条' : '查看全部 '+data.total+' 条'}} →</button></footer>
      <div v-if="expanded" class="ranking-pagination"><span>共 {{data.total}} 条</span><label>每页 <select :value="pageSize" @change="changeSize"><option :value="20">20</option><option :value="50">50</option></select> 条</label><button :disabled="data.page===0" @click="goPage(data.page-1)">上一页</button><span>{{data.totalPages ? data.page+1 : 0}} / {{data.totalPages}}</span><button :disabled="data.page+1>=data.totalPages" @click="goPage(data.page+1)">下一页</button><button :disabled="!data.items.length" @click="exportPage">导出本页</button></div>
    </template>
  </section>
</template>
<style scoped>
.channel-ranking{margin:16px 0;background:#fff;border:1px solid #dce4e9;border-radius:10px;overflow:hidden;color:#2d4353;font-size:14px}.channel-ranking>header{display:flex;align-items:center;justify-content:space-between;padding:20px 24px}.channel-ranking h2{font-size:21px;margin:0}.channel-ranking button{font:inherit;cursor:pointer;border:1px solid #dfe5e9;background:#fff;border-radius:6px;color:#526d7f;padding:8px 12px}.channel-ranking button:disabled{opacity:.45;cursor:default}.ranking-summary{display:flex;gap:32px;margin:18px 24px;padding:15px;background:#f6f8fa;border:1px solid #e6ebef;border-radius:7px;color:#80909b}.ranking-summary b{font-size:24px;color:#344f62;margin:0 5px}.ranking-scroll{overflow:auto}.channel-ranking table{width:100%;border-collapse:collapse;table-layout:fixed;text-align:left;white-space:nowrap}.channel-ranking .rank-column{width:80px}.channel-ranking .country-column{width:20%}.channel-ranking .usage-column{width:210px}.channel-ranking th:first-child,.channel-ranking td:first-child{text-align:center}.channel-ranking td:first-child i{margin:auto}.channel-ranking th:last-child,.channel-ranking td:last-child{text-align:right}.channel-ranking td:nth-child(3){white-space:normal;overflow-wrap:anywhere}.channel-ranking td{vertical-align:middle}.channel-ranking th{padding:13px 20px;color:#83939f;font-size:12px;font-weight:500;background:#f8fafb}.channel-ranking td{padding:16px 20px;border-top:1px solid #eaf0f3;font-size:15px}.channel-ranking td small{display:block;margin-top:4px;color:#8395a2;font-size:13px}.channel-ranking td i{display:grid;place-items:center;width:27px;height:27px;border-radius:6px;background:#f0f3f5;color:#85959f;font-style:normal;font-size:13px}.channel-ranking td i.top{background:#fff0d9;color:#af7126}.channel-ranking td strong{font-size:23px;font-variant-numeric:tabular-nums}.ranking-share{display:inline-block;min-width:55px;margin-left:16px;font-variant-numeric:tabular-nums;color:#96a0a8;font-size:12px}.channel-ranking>footer{display:flex;align-items:center;justify-content:space-between;border-top:1px solid #e5ebef;padding:14px 24px;font-size:11px;color:#87959f}.channel-ranking>footer button{border:0;color:#ac742a;font-size:13px}.ranking-pagination{display:flex;align-items:center;justify-content:flex-end;gap:12px;padding:12px 24px;border-top:1px solid #e7ecf0;font-size:12px;color:#7f919e}.ranking-pagination>span:first-child{margin-right:auto}.ranking-pagination select{border:1px solid #dce5eb;border-radius:4px;padding:5px;color:inherit}.ranking-message{padding:30px 24px;color:#80909d;min-height:120px}.ranking-error{color:#a66c20;background:#fff9ed}.channel-ranking td.ranking-empty{padding:32px;text-align:center;font-size:13px;color:#8698a5}@media(max-width:1000px){.ranking-summary{gap:12px;flex-wrap:wrap}.channel-ranking table{min-width:580px}.ranking-pagination{flex-wrap:wrap}.channel-ranking>header{padding:18px}.channel-ranking>footer{gap:12px;align-items:flex-start}}
</style>
