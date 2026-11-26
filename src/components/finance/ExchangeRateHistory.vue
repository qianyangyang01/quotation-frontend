<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { exchangeHistoryTime, loadExchangeRateHistory, type ExchangeCurrency, type ExchangeHistoryLatest, type ExchangeHistoryPage } from '@/services/exchangeRateHistory'
const props = defineProps<{ revision: number }>()
const emit = defineEmits<{ latest: [value: ExchangeHistoryLatest] }>()
const currency = ref<ExchangeCurrency | 'all'>('all')
const page = ref(0)
const result = ref<ExchangeHistoryPage>()
const loading = ref(false)
const error = ref('')
const pages = computed(() => Math.max(1, Math.ceil((result.value?.total ?? 0) / 10)))
let requestId = 0
let controller: AbortController | undefined
async function load() {
  const id = ++requestId
  controller?.abort(); controller = new AbortController()
  loading.value = true; error.value = ''; result.value = undefined
  try {
    const data = await loadExchangeRateHistory(currency.value, page.value, controller.signal)
    if (id !== requestId) return
    result.value = data; page.value = data.page; emit('latest', data.latest)
  } catch (e) {
    if (id === requestId) error.value = e instanceof Error ? e.message : '修改记录读取失败'
  } finally { if (id === requestId) loading.value = false }
}
function filter(value: ExchangeCurrency | 'all') { currency.value = value; page.value = 0; void load() }
function turn(value: number) { page.value = value; void load() }
watch(() => props.revision, () => { page.value = 0; void load() }, { immediate: true })
onBeforeUnmount(() => { requestId++; controller?.abort() })
</script>

<template>
  <div class="exchange-history">
    <div class="history-head"><div><h2>汇率修改记录</h2><p>自动记录修改前后汇率、修改时间及修改人，最新记录在前。</p></div>
      <div class="history-filter" aria-label="筛选汇率记录">
        <button :class="{ active: currency === 'all' }" @click="filter('all')">全部汇率</button>
        <button :class="{ active: currency === 'usdCny' }" @click="filter('usdCny')">美元 → 人民币</button>
        <button :class="{ active: currency === 'eurUsd' }" @click="filter('eurUsd')">欧元 → 美元</button>
      </div>
    </div>
    <div class="history-table" :aria-busy="loading">
      <table><thead><tr><th>修改时间（北京时间）</th><th>汇率类型</th><th>修改前</th><th>修改后</th><th>修改人</th></tr></thead>
        <tbody>
          <tr v-if="loading"><td colspan="5" class="history-state" role="status">正在读取修改记录…</td></tr>
          <tr v-else-if="error"><td colspan="5" class="history-state" role="alert">{{ error }} <button @click="load">重试</button></td></tr>
          <tr v-else-if="!result?.items.length"><td colspan="5" class="history-state">暂无修改记录</td></tr>
          <tr v-for="item in result?.items ?? []" v-else :key="item.id">
            <td>{{ exchangeHistoryTime(item.modifiedAt) }}</td>
            <td>{{ item.currency === 'usdCny' ? '美元 → 人民币' : '欧元 → 美元' }}<small>{{ item.currency === 'usdCny' ? 'USD / CNY' : 'EUR / USD' }}</small></td>
            <td><strong>{{ item.before ?? '未设置' }}</strong></td><td><strong class="history-value">{{ item.after ?? '未设置' }}</strong></td>
            <td>{{ item.actorName || item.actorAccount }}<small v-if="item.actorName && item.actorName !== item.actorAccount">{{ item.actorAccount }}</small></td>
          </tr>
        </tbody>
      </table>
    </div>
    <div class="history-footer"><span>仅展示记录启用后的修改；历史报价保留原汇率快照。</span>
      <div class="history-pages"><span>共 {{ result?.total ?? '—' }} 条 · 每页 10 条</span><button :disabled="loading || !!error || page === 0" @click="turn(page - 1)">上一页</button><span>{{ page + 1 }} / {{ pages }}</span><button :disabled="loading || !!error || page + 1 >= pages" @click="turn(page + 1)">下一页</button></div>
    </div>
  </div>
</template>

<style scoped>
.exchange-history{margin-top:26px;padding-top:24px;border-top:1px solid #e6ebef}.history-head{display:flex;align-items:center;justify-content:space-between;gap:16px;margin-bottom:16px}.history-head h2{margin:0;font-size:17px}.history-head p{margin:6px 0 0;color:#89949d;font-size:11px}.history-filter,.history-pages{display:flex;align-items:center;gap:8px}.exchange-history button{height:32px;padding:0 12px;border:1px solid #dce3e8;border-radius:6px;background:#fff;color:#697783;font-size:11px;cursor:pointer}.exchange-history button.active{border-color:#f1a33b;background:#fff6e7;color:#9b5900}.exchange-history button:disabled{opacity:.45;cursor:default}.history-table{max-height:365px;overflow:auto;border:1px solid #e1e7ec;border-radius:8px}.history-table table{width:100%;border-collapse:collapse;text-align:left;white-space:nowrap}.history-table th{position:sticky;top:0;background:#f8fafb;color:#7c8892;font-size:11px;font-weight:500;padding:13px 20px}.history-table td{border-top:1px solid #e9eef2;padding:13px 20px;color:#53616c;font-size:12px}.history-table small{display:block;color:#97a1aa;font-size:10px;margin-top:4px}.history-table strong{color:#192c3b;font-size:14px}.history-table .history-value{color:#b96a00}.history-table .history-state{padding:35px;text-align:center;color:#89949d}.history-footer{display:flex;align-items:center;justify-content:space-between;gap:16px;margin-top:13px;color:#89949d;font-size:10px}@media(max-width:900px){.history-head,.history-footer{align-items:flex-start;flex-direction:column}.history-pages{flex-wrap:wrap}}
</style>
