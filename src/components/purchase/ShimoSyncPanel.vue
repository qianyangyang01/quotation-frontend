<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue'
import { currentAuthUser } from '@/data/authStore'
import { request } from '@/services/http'

type Run = { id: string; status: string; mode: string; current_sheet: string; next_row: number; started_at: string; finished_at: string | null; reason: string; processed: number; changed: number }
type State = { configured: boolean; enabled: boolean; running: boolean; intervalSeconds: number; sheets: string[]; runs: Run[]; counts: {status: string; total: number}[] }
type Item = {sku: string; sheet: string; source_row: number; status: string; reason: string; checked_at: string; synced_at: string | null}
type Field = {field: string; label: string; before: unknown; after: unknown}
type Change = {id: string; sku: string; sheet: string; source_row: number; created_at: string; fields: Field[]}
const open = ref(false), busy = ref(false), loading = ref(false), error = ref('')
const state = ref<State | null>(null), rows = ref<Item[]>([]), changes = ref<Change[]>([])
const tab = ref<'changes' | 'pending'>('changes'), page = ref(0), size = ref(10), total = ref(0)
const startDate = ref(''), endDate = ref(''), appliedStart = ref(''), appliedEnd = ref('')
const weightOnly = ref(false), expanded = ref<string | null>(null)
const admin = computed(() => currentAuthUser.value.role === 'super_admin')
const pages = computed(() => Math.ceil(total.value / size.value))
const pendingCount = computed(() => state.value?.counts.filter(c => c.status !== 'synced').reduce((sum, c) => sum + Number(c.total), 0) ?? 0)
const latest = computed(() => state.value?.runs[0])
let timer: ReturnType<typeof setTimeout> | undefined
let generation = 0
const labels: Record<string, string> = { synced: '已同步', rolled_back: '已回退', pending: '待补齐或校验', conflict: '待人工核对', source_missing: '来源未找到', awaiting_daily: '等待批量更新', fetching: '读取石墨中', applying: '正在保存', fetch_failed: '读取失败，等待断点重试', apply_failed: '保存中断，等待重试', completed: '检查完成', paused: '已暂停' }
const modes: Record<string, string> = {incremental: '新增与补齐', daily: '完整更新', daily_noon: '12:00 批量更新', daily_evening: '18:00 批量更新', manual: '手动完整检查'}
const shortLabels: Record<string, string> = {weightG: '克重(g)', taxPoint: '票点', purchasePriceCny: '采购价', taxIncludedPriceCny: '含票价', singleFreightCny: '1件运费', freight10Cny: '10件运费'}
function value(field: string, v: unknown) { if (v == null || v === '') return '空'; if (field === 'taxPoint' && typeof v === 'number') return +(v * 100).toFixed(6) + '%'; return String(v) }
const time = (v: string | null) => v ? new Date(v).toLocaleString('zh-CN', { hour12: false, timeZone: 'Asia/Shanghai' }) : '尚无记录'
function keyFields(change: Change) { return change.fields.filter(f => ['weightG', 'taxPoint', 'purchasePriceCny', 'taxIncludedPriceCny'].includes(f.field)) }
async function refresh() {
  const id = ++generation
  clearTimeout(timer); loading.value = true
  const query = new URLSearchParams({page: String(page.value), size: String(size.value)})
  if (appliedStart.value) query.set('startDate', appliedStart.value)
  if (appliedEnd.value) query.set('endDate', appliedEnd.value)
  if (tab.value === 'changes') query.set('weightOnly', String(weightOnly.value))
  else query.set('pending', 'true')
  const active = tab.value
  try {
    const [s, list] = await Promise.all([request<State>('/purchase-shimo-sync'), request<{total: number; rows: (Change | Item)[]}>('/purchase-shimo-sync/' + (active === 'changes' ? 'changes' : 'items') + '?' + query)])
    if (id !== generation || !open.value) return
    state.value = s; total.value = list.total; error.value = ''
    if (page.value > 0 && page.value >= Math.ceil(list.total / size.value)) { page.value = Math.max(0, Math.ceil(list.total / size.value) - 1); await refresh(); return }
    if (active === 'changes') changes.value = list.rows as Change[]
    else rows.value = list.rows as Item[]
  } catch (e) { if (id === generation) error.value = e instanceof Error ? e.message : '读取同步状态失败' }
  finally { if (id === generation) { loading.value = false; if (open.value) timer = setTimeout(() => void refresh(), 15000) } }
}
function toggle() { open.value = !open.value; if (open.value) void refresh(); else { generation++; clearTimeout(timer) } }
async function action(path: string, body?: object) {
  busy.value = true
  try { await request('/purchase-shimo-sync/' + path, { method: 'POST', body: JSON.stringify(body ?? {}) }); await refresh() }
  catch (e) { error.value = e instanceof Error ? e.message : '操作失败' }
  finally { busy.value = false }
}
function resetPage() { page.value = 0; expanded.value = null; void refresh() }
function selectTab(value: 'changes' | 'pending') { if (tab.value === value) return; tab.value = value; total.value = 0; resetPage() }
function applyDates() {
  if (startDate.value && endDate.value && startDate.value > endDate.value) { error.value = '开始日期不能晚于结束日期'; return }
  appliedStart.value = startDate.value; appliedEnd.value = endDate.value; resetPage()
}
function preset(days: number) {
  const today = new Intl.DateTimeFormat('sv-SE', {timeZone: 'Asia/Shanghai'}).format(new Date())
  endDate.value = days ? today : ''
  startDate.value = days ? new Date(Date.parse(today + 'T00:00:00Z') - (days - 1) * 86400000).toISOString().slice(0, 10) : ''
  applyDates()
}
function move(delta: number) { page.value += delta; expanded.value = null; void refresh() }
onUnmounted(() => { generation++; clearTimeout(timer) })
</script>

<template>
  <section class="shimo-sync">
    <button type="button" class="shimo-toggle" :aria-expanded="open" @click="toggle">石墨新版自动同步 {{ open ? '收起' : '查看' }}</button>
    <div v-if="open" class="shimo-body">
      <p v-if="error" role="alert">{{ error }}（原结果可能已过时）</p>
      <template v-if="state">
        <div class="shimo-actions">
          <strong class="shimo-state">{{ !state.configured ? '尚未配置连接' : state.enabled ? '自动同步已开启' : '自动同步已暂停' }}</strong>
          <span class="shimo-schedule">新增与补齐：每轮后等待 {{ state.intervalSeconds / 60 }} 分钟 · 批量更新：每天 12:00 / 18:00（北京时间）</span>
          <template v-if="admin">
            <button type="button" :disabled="busy || !state.configured" @click="action('enabled', { enabled: !state.enabled })">{{ state.enabled ? '暂停同步' : '启用同步' }}</button>
            <button type="button" :disabled="busy || !state.configured || !state.enabled || state.running" @click="action('run')">立即完整同步</button>
          </template>
          <button type="button" :disabled="busy || loading" @click="refresh">刷新状态</button>
        </div>
        <div v-if="latest" class="shimo-latest">
          <span>{{ modes[latest.mode] || latest.mode }} · {{ labels[latest.status] || latest.status }}</span>
          <span>{{ time(latest.started_at) }}</span><span>检查 <b>{{ latest.processed }}</b> 条 / 变更 <b>{{ latest.changed }}</b> 条</span>
          <span v-if="latest.reason" class="shimo-error">{{ latest.reason }}</span>
          <span v-if="latest.current_sheet && latest.status !== 'completed'">{{ latest.current_sheet }} · 下一批第 {{ latest.next_row }} 行</span>
        </div>
        <details class="shimo-rules"><summary>同步范围与规则</summary>
          <p>仅同步“国际站2026询价新版”；同名 SKU 优先“老数据更新”。按粘贴规则同步整行已填字段，空白保留，历史报价保持不变。</p>
          <p>缺克重或票点时整条暂不同步，补齐后自动重试；0% 是有效票点。失败保存断点，间隔 10 分钟重试；各账号读取同一份采购数据。</p>
          <p>工作表：{{ state.sheets.join('、') || '待配置' }}</p>
        </details>
        <div class="shimo-tabs" role="tablist" aria-label="同步记录分类">
          <button type="button" role="tab" :aria-selected="tab === 'changes'" @click="selectTab('changes')">同步变更</button>
          <button type="button" role="tab" :aria-selected="tab === 'pending'" @click="selectTab('pending')">待处理 <span>{{ pendingCount }}</span></button>
        </div>
        <form class="shimo-filters" @submit.prevent="applyDates">
          <label>开始日期<input v-model="startDate" type="date" aria-label="同步开始日期"></label>
          <label>结束日期<input v-model="endDate" type="date" aria-label="同步结束日期"></label>
          <button type="submit" :disabled="loading">查询</button>
          <button type="button" @click="preset(1)">今天</button><button type="button" @click="preset(7)">近 7 天</button><button type="button" @click="preset(0)">全部时间</button>
          <label v-if="tab === 'changes'" class="shimo-weight"><input v-model="weightOnly" type="checkbox" @change="resetPage">仅看已有商品的克重变化</label>
        </form>
        <div class="shimo-list-heading"><span>{{ tab === 'changes' ? '按变更时间' : '按最近检查时间' }}筛选，包含结束日期全天（北京时间）</span><span role="status">{{ loading ? '正在查询…' : '共 ' + total + ' 条' }}</span></div>
        <div class="shimo-table-scroll" :aria-busy="loading">
          <table v-if="tab === 'changes'" class="shimo-table" aria-label="同步变更记录">
            <thead><tr><th>SKU</th><th>来源工作表 / 行</th><th>关键变化</th><th>变更时间</th><th>明细</th></tr></thead>
            <tbody>
              <template v-for="change in changes" :key="change.id">
                <tr><td><strong>{{ change.sku }}</strong></td><td>{{ change.sheet }}<small>第 {{ change.source_row }} 行</small></td>
                  <td><div class="shimo-key-fields"><span v-for="field in keyFields(change)" :key="field.field">{{ shortLabels[field.field] }} <b>{{ value(field.field, field.before) }} → {{ value(field.field, field.after) }}</b></span><span v-if="!keyFields(change).length">{{ change.fields.length }} 个字段变化</span></div></td>
                  <td class="shimo-time">{{ time(change.created_at) }}</td><td><button type="button" :aria-expanded="expanded === change.id" :aria-label="(expanded === change.id ? '收起 ' : '查看 ') + change.sku + ' 的字段变化'" @click="expanded = expanded === change.id ? null : change.id">{{ expanded === change.id ? '收起' : '详情' }}</button></td></tr>
                <tr v-if="expanded === change.id" class="shimo-detail"><td colspan="5"><dl><div v-for="field in change.fields" :key="field.field"><dt>{{ field.label }}</dt><dd>{{ value(field.field, field.before) }} → {{ value(field.field, field.after) }}</dd></div></dl></td></tr>
              </template>
              <tr v-if="!changes.length && !loading && !error"><td colspan="5" class="shimo-empty">当前筛选范围内没有同步变更。</td></tr>
            </tbody>
          </table>
          <table v-else class="shimo-table" aria-label="同步待处理记录">
            <thead><tr><th>SKU</th><th>来源工作表 / 行</th><th>状态</th><th>待处理原因</th><th>最近检查</th></tr></thead>
            <tbody><tr v-for="item in rows" :key="item.sku"><td><strong>{{ item.sku }}</strong></td><td>{{ item.sheet }}<small>第 {{ item.source_row }} 行</small></td><td>{{ labels[item.status] || item.status }}</td><td>{{ item.reason }}</td><td class="shimo-time">{{ time(item.checked_at) }}</td></tr>
              <tr v-if="!rows.length && !loading && !error"><td colspan="5" class="shimo-empty">当前筛选范围内没有待处理记录。</td></tr>
            </tbody>
          </table>
        </div>
        <nav class="shimo-pagination" aria-label="石墨同步记录分页">
          <span>共 {{ total }} 条 · 第 {{ pages ? page + 1 : 0 }} / {{ pages }} 页</span>
          <label>每页<select v-model.number="size" aria-label="同步每页条数" @change="resetPage"><option :value="10">10 条</option><option :value="20">20 条</option><option :value="50">50 条</option></select></label>
          <button type="button" :disabled="loading || page === 0" @click="move(-1)">上一页</button><button type="button" :disabled="loading || page + 1 >= pages" @click="move(1)">下一页</button>
        </nav>
      </template>
      <p v-else-if="!error">正在读取同步状态…</p>
    </div>
  </section>
</template>

<style scoped>
.shimo-sync{margin:14px 0;border:1px solid #dbe5de;border-radius:10px;background:#fff;color:#24392c}.shimo-toggle{border:0;background:transparent;padding:14px 16px;text-align:left;cursor:pointer;font:inherit;font-weight:650}.shimo-body{padding:0 16px 16px;font-size:13px}.shimo-body button,.shimo-body input,.shimo-body select{font:inherit}.shimo-body button{border:1px solid #d4dfd7;border-radius:6px;padding:7px 12px;background:#fff;color:#24392c;cursor:pointer}.shimo-body button:disabled{opacity:.5;cursor:default}.shimo-actions{display:flex;flex-wrap:wrap;gap:8px;align-items:center}.shimo-state{color:#237448}.shimo-schedule{flex:1;min-width:260px;color:#627168;font-size:12px}.shimo-latest{display:flex;flex-wrap:wrap;gap:8px 18px;margin:12px 0;padding:10px 12px;background:#f2f7f3;border-radius:6px;font-size:12px}.shimo-rules{font-size:12px;color:#627168;margin-bottom:14px}.shimo-rules summary{cursor:pointer}.shimo-rules p{margin:8px 0;line-height:1.6}.shimo-tabs{display:flex;gap:8px;border-bottom:1px solid #e3e9e5;margin-top:12px}.shimo-tabs button{border:0;border-radius:0;padding:10px 16px;color:#627168;border-bottom:2px solid transparent}.shimo-tabs button[aria-selected=true]{color:#21764b;border-bottom-color:#21764b;font-weight:650}.shimo-tabs span{margin-left:5px;background:#eef3ef;border-radius:9px;padding:1px 6px;font-size:11px}.shimo-filters{display:flex;flex-wrap:wrap;align-items:end;gap:8px;margin:14px 0 10px}.shimo-filters label{display:flex;flex-direction:column;gap:5px;font-size:12px;color:#627168}.shimo-filters input[type=date],.shimo-pagination select{height:32px;border:1px solid #d4dfd7;border-radius:5px;padding:0 7px;background:#fff;color:#24392c}.shimo-filters .shimo-weight{flex-direction:row;align-items:center;align-self:center;margin-left:auto}.shimo-list-heading{display:flex;justify-content:space-between;gap:10px;color:#768178;font-size:12px;margin-bottom:8px}.shimo-table-scroll{max-height:min(420px,45vh);overflow:auto;border:1px solid #e3e9e5;border-radius:7px}.shimo-table{width:100%;border-collapse:collapse;text-align:left;font-size:12px}.shimo-table th{position:sticky;top:0;background:#f4f7f5;color:#627168;font-weight:600;z-index:1}.shimo-table th,.shimo-table td{padding:10px 12px;border-bottom:1px solid #edf0ee;vertical-align:middle}.shimo-table td:first-child{white-space:nowrap}.shimo-table small{display:block;margin-top:3px;color:#859087}.shimo-key-fields{display:grid;grid-template-columns:repeat(2,minmax(130px,1fr));gap:5px 12px;min-width:280px;color:#768178}.shimo-key-fields b{font-weight:500;color:#304c3b}.shimo-time{white-space:nowrap;color:#768178}.shimo-table td button{padding:5px 9px;white-space:nowrap}.shimo-detail td{background:#f8faf8}.shimo-detail dl{display:grid;grid-template-columns:repeat(auto-fit,minmax(240px,1fr));gap:10px 24px;margin:4px 0}.shimo-detail dt{color:#768178;font-size:11px}.shimo-detail dd{margin:4px 0;overflow-wrap:anywhere}.shimo-empty{text-align:center;padding:28px!important;color:#859087}.shimo-pagination{display:flex;align-items:center;justify-content:flex-end;gap:10px;margin-top:12px;color:#627168;font-size:12px}.shimo-pagination>span{margin-right:auto}.shimo-pagination label{display:flex;align-items:center;gap:6px}.shimo-error,.shimo-body [role=alert]{color:#b42318}.shimo-body [aria-busy=true]{opacity:.65}@media(max-width:700px){.shimo-filters .shimo-weight{margin-left:0}.shimo-pagination{flex-wrap:wrap}.shimo-list-heading{flex-wrap:wrap}.shimo-key-fields{grid-template-columns:1fr;min-width:190px}}
</style>