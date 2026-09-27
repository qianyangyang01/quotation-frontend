<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue'
import { currentAuthUser } from '@/data/authStore'
import { request } from '@/services/http'

type Run = { id: string; status: string; mode: string; current_sheet: string; next_row: number; started_at: string; finished_at: string | null; reason: string; processed: number; changed: number }
type State = { configured: boolean; enabled: boolean; running: boolean; intervalSeconds: number; sheets: string[]; runs: Run[]; counts: {status: string; total: number}[] }
type Item = {sku: string; sheet: string; source_row: number; status: string; reason: string; checked_at: string; synced_at: string | null}
const open = ref(false), busy = ref(false), error = ref(''), page = ref(0), total = ref(0)
const state = ref<State | null>(null), rows = ref<Item[]>([])
type Change = {id: string; sku: string; sheet: string; source_row: number; created_at: string; fields: {field: string; label: string; before: unknown; after: unknown}[]}
const changes = ref<Change[]>([]), changePage = ref(0), changeTotal = ref(0), weightOnly = ref(false)
const admin = computed(() => currentAuthUser.value.role === 'super_admin')
let timer: ReturnType<typeof setTimeout> | undefined
let generation = 0
const labels: Record<string, string> = { synced: '已同步', rolled_back: '已回退，待人工核对', pending: '待补齐或校验', conflict: '待人工核对', source_missing: '来源未找到', awaiting_daily: '等待中午更新', fetching: '读取石墨中', applying: '正在保存', fetch_failed: '读取失败，等待断点重试', apply_failed: '保存中断，等待重试', completed: '检查完成', paused: '已暂停' }
const modes: Record<string, string> = {incremental: '新增与补齐', daily: '中午完整更新', manual: '手动完整检查'}
function value(field: string, value: unknown) { if (value == null || value === '') return '空'; if (field === 'taxPoint' && typeof value === 'number') return `${+(value * 100).toFixed(6)}%`; return String(value) }
const time = (value: string | null) => value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '尚无记录'
async function refresh() {
  const id = ++generation
  clearTimeout(timer)
  try {
    const [s, list, log] = await Promise.all([request<State>('/purchase-shimo-sync'), request<{total: number; rows: Item[]}>(`/purchase-shimo-sync/items?page=${page.value}`), request<{total: number; rows: Change[]}>(`/purchase-shimo-sync/changes?page=${changePage.value}&weightOnly=${weightOnly.value}`)])
    if (id !== generation || !open.value) return
    state.value = s; rows.value = list.rows; total.value = list.total; error.value = ''
    changes.value = log.rows; changeTotal.value = log.total
  } catch (e) { if (id === generation) error.value = e instanceof Error ? e.message : '读取同步状态失败' }
  finally { if (open.value && id === generation) timer = setTimeout(() => void refresh(), 15000) }
}
function toggle() { open.value = !open.value; if (open.value) void refresh(); else { generation++; clearTimeout(timer) } }
async function action(path: string, body?: object) {
  busy.value = true
  try { await request(`/purchase-shimo-sync/${path}`, { method: 'POST', body: JSON.stringify(body ?? {}) }); await refresh() }
  catch (e) { error.value = e instanceof Error ? e.message : '操作失败' }
  finally { busy.value = false }
}
function move(delta: number) { page.value += delta; void refresh() }
function moveChanges(delta: number) { changePage.value += delta; void refresh() }
function filterChanges() { changePage.value = 0; void refresh() }
onUnmounted(() => { generation++; clearTimeout(timer) })
</script>

<template>
  <section class="shimo-sync">
    <button type="button" class="shimo-toggle" :aria-expanded="open" @click="toggle">石墨新版自动同步 {{ open ? '收起' : '查看' }}</button>
    <div v-if="open" class="shimo-body">
      <p v-if="error" role="alert">{{ error }}（原状态可能已过时）</p>
      <template v-if="state">
        <div class="shimo-actions">
          <strong>{{ !state.configured ? '尚未配置连接' : state.enabled ? '自动同步已开启' : '自动同步已暂停' }}</strong>
          <template v-if="admin">
            <button type="button" :disabled="busy || !state.configured" @click="action('enabled', { enabled: !state.enabled })">{{ state.enabled ? '暂停同步' : '启用同步' }}</button>
            <button type="button" :disabled="busy || !state.configured || !state.enabled || state.running" @click="action('run')">立即完整同步</button>
          </template>
          <button type="button" :disabled="busy" @click="refresh">刷新状态</button>
        </div>
        <p>仅同步“国际站2026询价新版”。每轮完成后等待 {{ state.intervalSeconds / 60 }} 分钟，检查新增与待补齐资料；已有商品每天北京时间 12:30–13:00 内启动完整更新，空白保留。同名 SKU 优先取“老数据更新”。</p>
        <p>缺克重或票点时整条暂不同步，补齐后自动重试；0% 是有效票点。历史报价保持不变。</p>
        <p>工作表：{{ state.sheets.join('、') || '待配置' }}</p>
        <div class="shimo-counts"><span v-for="count in state.counts" :key="count.status">{{ labels[count.status] || count.status }}：{{ count.total }}</span></div>
        <p v-if="state.runs[0]">最近一轮：{{ modes[state.runs[0].mode] }} · {{ labels[state.runs[0].status] || state.runs[0].status }} · {{ time(state.runs[0].started_at) }} · 检查 {{ state.runs[0].processed }} 条，变更 {{ state.runs[0].changed }} 条 <span v-if="state.runs[0].reason">· {{ state.runs[0].reason }}</span><br><span v-if="state.runs[0].current_sheet">读取位置：{{ state.runs[0].current_sheet }}，下一批从第 {{ state.runs[0].next_row }} 行核对</span></p>
        <h3>同步变更记录（{{ changeTotal }}）</h3>
        <label><input v-model="weightOnly" type="checkbox" @change="filterChanges"> 仅看已有商品的克重变化</label>
        <ul class="shimo-items">
          <li v-for="change in changes" :key="change.id"><strong>{{ change.sku }}</strong> · {{ change.sheet }} 第 {{ change.source_row }} 行 · {{ time(change.created_at) }}<br>
            <span v-for="field in change.fields.filter(f => ['weightG', 'taxPoint', 'purchasePriceCny', 'taxIncludedPriceCny', 'singleFreightCny', 'freight10Cny'].includes(f.field))" :key="field.field" class="shimo-change">{{ field.label }}：{{ value(field.field, field.before) }} → {{ value(field.field, field.after) }}</span>
            <details><summary>查看全部字段变化</summary><p v-for="field in change.fields" :key="field.field">{{ field.label }}：{{ value(field.field, field.before) }} → {{ value(field.field, field.after) }}</p></details>
          </li>
        </ul>
        <p v-if="!changes.length">当前没有符合条件的同步变更。</p>
        <div v-if="changeTotal > 20" class="shimo-actions"><button :disabled="changePage === 0" @click="moveChanges(-1)">上一页变更</button><span>第 {{ changePage + 1 }} 页</span><button :disabled="(changePage + 1) * 20 >= changeTotal" @click="moveChanges(1)">下一页变更</button></div>
        <h3>待处理清单（{{ total }}）</h3>
        <ul class="shimo-items">
          <li v-for="item in rows" :key="item.sku"><strong>{{ item.sku }}</strong> · {{ item.sheet }} 第 {{ item.source_row }} 行 · {{ labels[item.status] || item.status }}<br>{{ item.reason }}<br><small>最近检查：{{ time(item.checked_at) }}</small></li>
        </ul>
        <p v-if="!rows.length">当前没有待处理记录。</p>
        <div v-if="total > 50" class="shimo-actions"><button :disabled="page === 0" @click="move(-1)">上一页</button><span>第 {{ page + 1 }} 页</span><button :disabled="(page + 1) * 50 >= total" @click="move(1)">下一页</button></div>
      </template>
      <p v-else-if="!error">正在读取同步状态…</p>
    </div>
  </section>
</template>

<style scoped>
.shimo-change{display:block;padding:3px 0}
.shimo-sync{margin:14px 0;border:1px solid #dbe5de;border-radius:8px;background:#f6faf7;color:#24392c}.shimo-toggle{border:0;background:transparent;padding:12px 16px;text-align:left;cursor:pointer;font:inherit;font-weight:600}.shimo-body{padding:0 16px 16px;font-size:13px}.shimo-actions,.shimo-counts{display:flex;flex-wrap:wrap;gap:12px;align-items:center}.shimo-actions button{border:1px solid #cad6ce;border-radius:6px;padding:7px 12px;background:#fff;color:#24392c;cursor:pointer}.shimo-actions button:disabled{opacity:.5;cursor:default}.shimo-items{padding:0;list-style:none}.shimo-items li{padding:10px 0;border-bottom:1px solid #dbe5de;overflow-wrap:anywhere}.shimo-body p{overflow-wrap:anywhere}.shimo-body [role=alert]{color:#b42318}.shimo-items small{color:#52635a}
</style>
