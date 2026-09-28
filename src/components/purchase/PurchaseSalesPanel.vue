<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { filterPurchaseSales, loadPurchaseSales, matchPurchaseSales, reminderLabels, salesCsv, type Reminder, type SalesData } from '@/services/purchaseSales'

const props = defineProps<{ refreshKey?: number }>()
const emit = defineEmits<{ edit: [sku: string] }>()
const data = ref<SalesData | null>(null)
const loading = ref(false)
const error = ref('')
const expanded = ref(false)
const dialog = ref<HTMLDialogElement>()
const scope = ref<'all' | 'missing' | 'unmatched'>('all')
const query = ref('')
const owner = ref('')
const selected = ref<Reminder[]>([])
const differenceOnly = ref(false)
const groupByOwner = ref(false)
const monthly = ref(false)
const page = ref(1)
const fieldFilters = reminderLabels.filter(label => label !== '未匹配')
let controller: AbortController | undefined
let request = 0
async function refresh() {
  const id = ++request
  controller?.abort()
  controller = new AbortController()
  loading.value = true
  error.value = ''
  try { const result = await loadPurchaseSales(controller.signal); if (id === request) data.value = result }
  catch (reason) { if (id === request && !controller.signal.aborted) error.value = reason instanceof Error ? reason.message : '销量匹配读取失败' }
  finally { if (id === request) loading.value = false }
}
onMounted(refresh)
onUnmounted(() => { request++; controller?.abort() })
watch(() => props.refreshKey, refresh)
watch(expanded, open => { if (open) dialog.value?.showModal(); else dialog.value?.close() }, { flush: 'post' })
const rows = computed(() => data.value ? matchPurchaseSales(data.value) : [])
const matched = computed(() => rows.value.filter(row => row.product).length)
const pending = computed(() => rows.value.filter(row => row.product && row.reminders.length).length)
const discrepancies = computed(() => rows.value.filter(row => row.salesDifference !== 0).length)
const owners = computed(() => [...new Set(rows.value.filter(row => row.product).map(row => row.owner))].sort((a, b) => a.localeCompare(b, 'zh-CN')))
const counts = computed(() => Object.fromEntries(fieldFilters.map(label => [label, rows.value.filter(row => row.reminders.includes(label)).length])))
const filtered = computed(() => filterPurchaseSales(rows.value, query.value, owner.value, selected.value, false, differenceOnly.value)
  .filter(row => scope.value === 'unmatched' ? !row.product : scope.value === 'missing' ? row.product && row.reminders.length : true)
  .sort((a, b) => (groupByOwner.value ? a.owner.localeCompare(b.owner, 'zh-CN') : 0) || b.total - a.total || a.sku.localeCompare(b.sku)))
const pages = computed(() => Math.max(1, Math.ceil(filtered.value.length / 20)))
const visible = computed(() => filtered.value.slice((page.value - 1) * 20, page.value * 20))
const ownerTotals = computed(() => {
  const groups = new Map<string, number>()
  filtered.value.forEach(row => groups.set(row.owner, (groups.get(row.owner) || 0) + 1))
  return groups
})
const hasFilters = computed(() => query.value || owner.value || selected.value.length || differenceOnly.value)
watch([query, owner, selected, scope, differenceOnly, groupByOwner], () => { page.value = 1 }, { deep: true })
watch(pages, value => { page.value = Math.min(page.value, value) })
function clear() { query.value = ''; owner.value = ''; selected.value = []; differenceOnly.value = false }
function chooseScope(value: typeof scope.value) { scope.value = value; clear() }
function openScope(value: typeof scope.value) { chooseScope(value); expanded.value = true }
function edit(sku: string) { dialog.value?.close(); expanded.value = false; emit('edit', sku) }
function exportRows() {
  const url = URL.createObjectURL(new Blob([salesCsv(filtered.value)], { type: 'text/csv;charset=utf-8' }))
  const link = document.createElement('a'); link.href = url; link.download = '在售SKU销量匹配-6-8月.csv'; link.click()
  window.setTimeout(() => URL.revokeObjectURL(url), 1000)
}
const number = (value: number) => value.toLocaleString('zh-CN')
</script>

<template>
  <section class="sales-panel" aria-label="在售90天销量 SKU">
    <div class="sales-overview">
      <div class="entry-title"><strong>在售90天销量 SKU</strong><small>6—8月销量 · 仅主 SKU</small></div>
      <div class="entry-total"><b>{{ data ? number(rows.length) : loading ? '读取中…' : '—' }}</b><span>个主 SKU</span></div>
      <div v-if="data" class="sales-summary">
        <span>已匹配 <b>{{ number(matched) }}</b></span>
        <button @click="openScope('missing')">待补资料 <b>{{ number(pending) }}</b></button>
        <button @click="openScope('unmatched')">未匹配 <b>{{ number(rows.length - matched) }}</b></button>
      </div>
      <button class="sales-entry primary" :aria-expanded="expanded" aria-controls="sales-details" @click="openScope('all')">查看明细 →</button>
    </div>
    <p v-if="error && !expanded" class="error" role="alert">{{ error }}。{{ data ? '当前显示上次成功匹配结果，请刷新后再处理。' : '暂未取得匹配结果，请重试。' }} <button :disabled="loading" @click="refresh">重试</button></p>
    <dialog id="sales-details" ref="dialog" class="sales-dialog" aria-labelledby="sales-title" @close="expanded = false" @cancel="expanded = false">
      <header class="dialog-header">
        <div><h2 id="sales-title">在售 SKU 销量</h2><p>6—8月销量 · 仅按主 SKU 匹配采购资料</p></div>
        <div class="header-actions">
          <button :disabled="loading" @click="refresh">{{ loading ? '匹配中…' : '刷新匹配' }}</button>
          <button :disabled="!filtered.length || !data" @click="exportRows">导出结果</button>
          <button class="close-button" aria-label="关闭销量明细" @click="expanded = false">×</button>
        </div>
      </header>
      <p v-if="error && expanded" class="error" role="alert">{{ error }}。{{ data ? '当前显示上次成功匹配结果，请刷新后再处理。' : '暂未取得匹配结果，请重试。' }} <button :disabled="loading" @click="refresh">重试</button></p>
      <template v-if="data">
        <nav class="scope-tabs" aria-label="销量分类">
          <button :class="{ active: scope === 'all' }" :aria-pressed="scope === 'all'" @click="chooseScope('all')">全部 <b>{{ number(rows.length) }}</b></button>
          <button :class="{ active: scope === 'missing' }" :aria-pressed="scope === 'missing'" @click="chooseScope('missing')">待补资料 <b>{{ number(pending) }}</b></button>
          <button :class="{ active: scope === 'unmatched' }" :aria-pressed="scope === 'unmatched'" @click="chooseScope('unmatched')">未匹配 <b>{{ number(rows.length - matched) }}</b></button>
        </nav>
        <div class="sales-filters">
          <input v-model="query" aria-label="搜索销量SKU" placeholder="搜索主 SKU">
          <select v-model="owner" :disabled="scope === 'unmatched'" aria-label="采购报价人"><option value="">全部采购报价人</option><option v-for="item in owners" :key="item">{{ item }}</option></select>
          <details v-if="scope !== 'unmatched'" class="filter-menu">
            <summary>缺失项目{{ selected.length ? ' · ' + selected.length : '' }} <span>⌄</span></summary>
            <div class="filter-popover"><p>多选时，同时满足所选条件</p><label v-for="label in fieldFilters" :key="label"><input v-model="selected" type="checkbox" :value="label">{{ label }}<small>{{ number(counts[label] || 0) }}</small></label></div>
          </details>
          <button v-if="hasFilters" class="text-button" @click="clear">清空筛选</button>
          <div class="view-options"><label><input v-model="groupByOwner" type="checkbox">按报价人分组</label><label><input v-model="monthly" type="checkbox">月度明细</label></div>
        </div>
        <div class="list-meta">
          <span>{{ number(filtered.length) }} 个主 SKU · {{ groupByOwner ? '按报价人分组，组内' : '' }}销量从高到低<span v-if="differenceOnly" class="warning"> · 仅看销量差异</span></span>
          <details class="data-notes"><summary><i v-if="discrepancies || data.source.monthlyOnlySkus.length"></i>数据说明 ⌄</summary><div class="notes-popover">
            <p>来源：{{ data.source.sourceFile }} · {{ data.source.sourceSheet }}。统计期间：{{ data.source.period }}，并非滚动90天。</p>
            <p>只匹配“商品编码_细”主 SKU。子 SKU 不参与匹配，也不替代主 SKU 采购资料。待补资料仅统计已匹配的 SKU。</p>
            <p>已确认的 0% 票点、0 元运费有效；不同缺失项目可能重叠。</p>
            <p v-if="discrepancies || data.source.monthlyOnlySkus.length">{{ discrepancies }} 个 SKU 的汇总销量与月度合计不同；月表另有 {{ data.source.monthlyOnlySkus.length }} 个编码未进入汇总表。清单保留汇总表销量。</p>
            <label><input v-model="differenceOnly" type="checkbox">仅看汇总与月度合计有差异的 SKU</label>
            <p class="muted">匹配时间：{{ new Date(data.matchedAt).toLocaleString('zh-CN') }}</p>
          </div></details>
        </div>
        <div class="sales-table-wrap" :aria-busy="loading">
          <table>
            <thead><tr><th>主 SKU</th><th>采购报价人</th><template v-if="monthly"><th>6月</th><th>7月</th><th>8月</th></template><th>6—8月销量</th><th>克重</th><th>票点</th><th>资料提醒</th><th>操作</th></tr></thead>
            <tbody>
              <template v-for="(row, index) in visible" :key="row.sku">
                <tr v-if="groupByOwner && (!index || visible[index - 1]?.owner !== row.owner)" class="owner-row"><th :colspan="monthly ? 10 : 7">{{ row.owner }} <span>{{ ownerTotals.get(row.owner) }} 个 SKU</span></th></tr>
                <tr>
                  <td class="sku-cell"><b>{{ row.sourceSku }}</b><small v-if="row.productName" class="product-name" :title="row.productName">{{ row.productName }}</small></td>
                  <td>{{ row.owner }}</td>
                  <template v-if="monthly"><td v-for="(quantity, month) in row.months" :key="month">{{ number(quantity) }}</td></template>
                  <td><strong>{{ number(row.total) }}</strong><small v-if="row.salesDifference" class="warning" :title="'月度合计 ' + number(row.monthlyTotal)">销量待核对</small></td>
                  <td>{{ row.product?.weightG == null ? '—' : row.product.weightG + ' g' }}</td>
                  <td>{{ row.product?.taxPoint == null ? '—' : Number((row.product.taxPoint * 100).toFixed(4)) + '%' }}<small v-if="row.product?.invoiceType === '待确认'" class="warning">待确认</small></td>
                  <td class="reminders-cell"><template v-if="row.product"><span v-for="reason in row.reminders.slice(0, 2)" :key="reason" class="reminder">{{ reason }}</span><details v-if="row.reminders.length > 2" class="more-reminders"><summary>另 {{ row.reminders.length - 2 }} 项</summary><span v-for="reason in row.reminders.slice(2)" :key="reason" class="reminder">{{ reason }}</span></details><span v-if="!row.reminders.length" class="complete">资料齐全</span></template><span v-else class="muted">未匹配采购资料</span></td>
                  <td><button v-if="row.product" class="row-action" @click="edit(row.sku)">{{ row.reminders.length ? '去补全' : '查看 / 编辑' }}</button><span v-else class="muted">需核对主 SKU</span></td>
                </tr>
              </template>
              <tr v-if="!visible.length"><td :colspan="monthly ? 10 : 7" class="empty">没有符合筛选条件的 SKU</td></tr>
            </tbody>
          </table>
        </div>
        <footer><span>每页 20 条</span><div><button :disabled="page <= 1" @click="page--">上一页</button><span>{{ page }} / {{ pages }}</span><button :disabled="page >= pages" @click="page++">下一页</button></div></footer>
      </template>
      <p v-else class="empty">{{ loading ? '正在读取销量并匹配采购资料…' : '暂无匹配结果' }}</p>
    </dialog>
  </section>
</template>

<style scoped>
.sales-panel{margin:0 0 18px;color:#243443;font-size:13px}
.sales-overview{display:flex;align-items:center;gap:28px;padding:22px 24px;border:1px solid #dfe5eb;border-left:3px solid #ee980d;border-radius:9px;background:#fff}
.entry-title{display:grid;gap:7px}.entry-title strong{font-size:16px}.entry-title small{font-size:12px;color:#84909a}.entry-total{display:flex;align-items:baseline;gap:7px;padding-right:28px;border-right:1px solid #e8edf1}.entry-total b{font-size:27px;letter-spacing:-.5px}.entry-total span{font-size:12px;color:#84909a}
.sales-summary{display:flex;align-items:center;gap:22px;color:#7c8790}.sales-summary b{margin-left:5px;color:#334555;font-weight:600}.sales-entry{margin-left:auto;white-space:nowrap}
.sales-panel button,.sales-panel input,.sales-panel select{font:inherit}.sales-panel button{cursor:pointer;border:1px solid #dfe5eb;border-radius:6px;background:#fff;padding:8px 12px;color:#526575}.sales-panel button:hover:not(:disabled){background:#f5f7f9;border-color:#c6d0d9}.sales-panel button:active:not(:disabled){transform:translateY(1px)}.sales-panel button:disabled{opacity:.45;cursor:default}.sales-panel button:focus-visible,summary:focus-visible{outline:2px solid #e99318;outline-offset:3px}
.sales-panel .primary{background:#f39800;border-color:#f39800;color:#fff;padding:10px 16px;font-weight:600}.sales-panel .primary:hover{background:#e88b00;border-color:#e88b00}
.sales-summary button{border:0;padding:5px 0;background:transparent;color:#7c8790}.sales-summary button:hover:not(:disabled){background:transparent;color:#b16b00}
.sales-dialog{box-sizing:border-box;width:min(1320px,calc(100vw - 48px));height:min(860px,calc(100dvh - 56px));max-width:none;max-height:none;margin:auto;padding:0;border:1px solid #dfe5eb;border-radius:14px;color:#243443;background:#fff;box-shadow:0 24px 90px #10283a30;overflow:hidden;font-size:13px}
.sales-dialog[open]{display:flex;flex-direction:column}.sales-dialog:not([open]){display:none}.sales-dialog::backdrop{background:#14283870}
.dialog-header{display:flex;justify-content:space-between;align-items:center;gap:16px;padding:24px 28px 16px;flex-shrink:0}.dialog-header h2{margin:0;font-size:22px;letter-spacing:-.5px}.dialog-header p{margin:7px 0 0;color:#85919b;font-size:12px}.header-actions{display:flex;align-items:center;gap:9px}.header-actions .close-button{margin-left:10px;border:0;font-size:25px;font-weight:300;padding:0 8px;color:#87929c}
.scope-tabs{display:flex;gap:30px;border-bottom:1px solid #e7ebef;padding:0 28px;flex-shrink:0}.scope-tabs button{border:0;border-radius:0;padding:17px 0 13px;background:transparent;color:#7b8792;border-bottom:3px solid transparent}.scope-tabs b{margin-left:6px;font-size:12px;font-weight:500}.scope-tabs button.active{color:#a86200;border-bottom-color:#ee990c;font-weight:600}.scope-tabs button:hover:not(:disabled){background:transparent}
.sales-filters{display:flex;align-items:center;gap:10px;padding:20px 28px 12px;flex-shrink:0}.sales-filters>input,.sales-filters select{height:36px;box-sizing:border-box;padding:0 11px;border:1px solid #dfe5eb;border-radius:6px;background:#fff;color:#526575}.sales-filters>input{width:210px}.sales-filters select{width:174px}.sales-filters select:disabled{opacity:.5}
.filter-menu,.data-notes{position:relative}.filter-menu>summary{display:flex;align-items:center;justify-content:space-between;gap:16px;cursor:pointer;list-style:none;padding:9px 12px;border:1px solid #dfe5eb;border-radius:6px;color:#526575}.filter-menu>summary::-webkit-details-marker,.data-notes>summary::-webkit-details-marker{display:none}
.filter-popover,.notes-popover{position:absolute;z-index:5;top:calc(100% + 9px);padding:14px 16px;border:1px solid #e1e6ec;border-radius:8px;background:#fff;box-shadow:0 8px 30px #182c4220}
.filter-popover{left:0;width:245px}.filter-popover p{margin:0 0 12px;font-size:12px;color:#87929c}.filter-popover label{display:flex;align-items:center;gap:8px;padding:7px 0}.filter-popover small{margin-left:auto;color:#87929c}.sales-panel input[type=checkbox]{accent-color:#e88d00;margin:0 5px 0 0}
.view-options{display:flex;align-items:center;gap:18px;margin-left:auto;color:#6f7e8a;white-space:nowrap;font-size:12px}.view-options label{display:flex;align-items:center}.sales-panel .text-button{border:0;padding:6px;color:#8a6d40}
.list-meta{display:flex;justify-content:space-between;align-items:center;padding:0 28px 14px;color:#8a96a0;font-size:12px;flex-shrink:0}.data-notes>summary{list-style:none;cursor:pointer;color:#6f7e8a}.data-notes i{display:inline-block;width:5px;height:5px;border-radius:50%;background:#dca14c;vertical-align:middle;margin-right:6px}.notes-popover{right:0;width:min(380px,70vw);color:#596b79;font-size:12px;line-height:1.8}.notes-popover p{margin:0 0 10px}.notes-popover p:last-child{margin:12px 0 0}.notes-popover label{display:flex;align-items:center}
.sales-table-wrap{overflow:auto;flex:1;min-height:0;border-top:1px solid #e8edf1}table{border-collapse:separate;border-spacing:0;width:100%;min-width:930px;text-align:left}th,td{padding:14px 18px;border-bottom:1px solid #edf0f3;vertical-align:middle}th:first-child,td:first-child{padding-left:28px}thead th{position:sticky;top:0;z-index:1;background:#f7f9fb;color:#85929d;font-size:12px;font-weight:500;white-space:nowrap}td{font-variant-numeric:tabular-nums;font-size:13px}tbody tr:hover{background:#fcfcfd}td b{font-weight:600;font-size:13px}td strong{font-weight:600}td small{display:block;margin-top:5px;font-size:11px;color:#8a96a0}.sku-cell{max-width:250px;min-width:155px}.product-name{white-space:nowrap;text-overflow:ellipsis;overflow:hidden;max-width:250px}.reminders-cell{min-width:180px;max-width:280px}.reminder{display:inline-block;padding:4px 7px;border-radius:4px;background:#fff5e9;color:#b37a33;font-size:11px;margin:2px 5px 2px 0}.complete{color:#6b9784;font-size:12px}.muted{color:#909ba4;font-size:12px}.warning{color:#be8840!important}.more-reminders{display:inline-block;color:#8b959d;font-size:11px;vertical-align:middle}.more-reminders summary{cursor:pointer}.more-reminders[open]{display:block}.owner-row th{background:#f2f5f8;color:#526575;font-size:12px}.owner-row span{margin-left:12px;color:#96a0aa;font-weight:400}.sales-panel .row-action{border:0;padding:6px 0;background:transparent;color:#b07524;font-size:12px;white-space:nowrap}.sales-panel .row-action:hover{background:transparent;color:#d08213}
footer{display:flex;align-items:center;justify-content:space-between;padding:15px 28px;border-top:1px solid #e8edf1;color:#8996a1;font-size:12px;flex-shrink:0}footer>div{display:flex;align-items:center;gap:16px}footer button{font-size:12px!important;padding:6px 10px!important}.empty{text-align:center;padding:40px;color:#8a96a0}.error{padding:10px 14px;margin:10px 28px;background:#fff4e3;color:#925600;border-radius:6px;line-height:1.8;flex-shrink:0}
@media(max-width:1050px){.sales-overview{flex-wrap:wrap;gap:16px}.entry-total{border-right:0;padding-right:0}.sales-summary{gap:16px}.sales-filters{flex-wrap:wrap}.view-options{margin-left:0}}
@media(max-width:650px){.sales-overview{padding:18px}.entry-title{flex:1}.entry-total b{font-size:23px}.sales-summary{width:100%;font-size:12px}.sales-entry{width:100%}.sales-dialog{width:calc(100vw - 16px);height:calc(100dvh - 24px);border-radius:10px}.dialog-header{padding:18px 16px 12px;align-items:flex-start}.dialog-header h2{font-size:19px}.dialog-header p{max-width:160px;line-height:1.6}.header-actions{flex-wrap:wrap;justify-content:flex-end;gap:5px}.header-actions button{font-size:11px;padding:7px}.header-actions .close-button{margin-left:0}.scope-tabs{padding:0 16px;gap:22px}.sales-filters{padding:16px}.sales-filters>input{width:calc(50% - 5px)}.sales-filters select{width:calc(50% - 5px)}.list-meta{padding:0 16px 12px;gap:8px}.list-meta>span{max-width:65%}footer{padding:12px 16px}.view-options{gap:12px}.error{margin:8px 16px}}
@media(prefers-reduced-motion:reduce){.sales-panel button:active:not(:disabled){transform:none}}
</style>
