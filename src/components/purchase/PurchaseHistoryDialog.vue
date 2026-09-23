<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import { historyImage, historyValue, loadPurchaseHistory, type PurchaseHistoryEntry } from '@/services/purchaseHistory'

const props = defineProps<{ sku: string }>()
const emit = defineEmits<{ close: [] }>()
const entries = ref<PurchaseHistoryEntry[]>([])
const loading = ref(false)
const error = ref('')
const page = ref(0)
const total = ref(0)
const totalPages = ref(0)
let request = 0
async function load(target = 0) {
  const current = ++request
  loading.value = true; error.value = ''; entries.value = []
  try {
    const result = await loadPurchaseHistory(props.sku, target)
    if (current !== request) return
    entries.value = result.items; page.value = result.page; total.value = result.total; totalPages.value = result.totalPages
  } catch (cause) {
    if (current === request) error.value = cause instanceof Error ? cause.message : '修改记录加载失败'
  } finally { if (current === request) loading.value = false }
}
function time(value: string) { return new Date(value).toLocaleString('zh-CN', { hour12: false, timeZone: 'Asia/Shanghai' }) }
watch(() => props.sku, () => { page.value = 0; void load() }, { immediate: true })
onUnmounted(() => { request++ })
</script>

<template>
  <div class="history-mask" @keydown.esc.stop="emit('close')">
    <section class="history-dialog" role="dialog" aria-modal="true" aria-labelledby="purchase-history-title" tabindex="-1">
      <button class="close" aria-label="关闭修改记录" @click="emit('close')">×</button>
      <small>PRODUCT HISTORY</small>
      <h2 id="purchase-history-title">产品修改记录</h2>
      <p class="description"><b>{{ sku }}</b> · 记录已保存的维护操作，时间为北京时间。</p>
      <p class="history-note">字段明细从本功能启用后开始记录；更早的修改内容无法追溯。操作人为实际登录用户，非资料中的报价人。</p>
      <div class="history-content" aria-live="polite" :aria-busy="loading">
        <p v-if="loading" class="state">正在加载修改记录…</p>
        <div v-else-if="error" class="state" role="alert">{{ error }} <button @click="load(page)">重试</button></div>
        <p v-else-if="!entries.length" class="state">暂无修改记录</p>
        <article v-for="entry in entries" v-else :key="entry.id" class="history-entry">
          <header><strong>{{ entry.operation }}</strong><time>{{ time(entry.createdAt) }}</time></header>
          <p class="operator">操作人：{{ entry.actorName ? `${entry.actorName}（${entry.actorAccount}）` : entry.actorAccount }} <span>· {{ entry.sku }}</span></p>
          <table><thead><tr><th>修改字段</th><th>修改前</th><th>修改后</th></tr></thead>
            <tbody><tr v-for="change in entry.changes" :key="change.field"><th scope="row">{{ change.label }}</th>
              <td v-for="side in (['before', 'after'] as const)" :key="side">
                <a v-if="historyImage(change.field, change[side])" :href="historyImage(change.field, change[side])" target="_blank" rel="noopener noreferrer"><img :src="historyImage(change.field, change[side])" :alt="`${change.label}（${side === 'before' ? '修改前' : '修改后'}）`" loading="lazy">查看图片</a>
                <span v-else>{{ historyValue(change.field, change[side]) }}</span>
              </td>
            </tr></tbody>
          </table>
        </article>
      </div>
      <footer><span>共 {{ total }} 条</span><button :disabled="loading || page === 0" @click="load(page - 1)">上一页</button><span>{{ totalPages ? page + 1 : 0 }} / {{ totalPages }}</span><button :disabled="loading || page + 1 >= totalPages" @click="load(page + 1)">下一页</button><button @click="emit('close')">关闭</button></footer>
    </section>
  </div>
</template>

<style scoped>
.history-mask{position:fixed;inset:0;z-index:80;display:grid;place-items:center;padding:24px;background:rgba(17,24,39,.45)}
.history-dialog{position:relative;box-sizing:border-box;width:min(940px,100%);max-height:92vh;display:flex;flex-direction:column;padding:26px;border-radius:12px;background:#fff;box-shadow:0 24px 70px #11182740;color:#17212b}
.close{position:absolute;right:16px;top:12px;border:0;background:none;font-size:26px}.history-dialog>small{color:#c87400;font-size:10px;font-weight:800;letter-spacing:.15em}h2{margin:8px 0}.description{margin:4px 0 12px;font-size:13px;color:#61717d}.history-note{margin:0 0 16px;padding:10px 12px;border-radius:6px;background:#fff7e8;font-size:12px;color:#85632c;line-height:1.6}.history-content{overflow:auto;min-height:120px}.state{padding:32px;text-align:center;color:#657581}.history-entry{margin:0 0 18px;padding:16px;border:1px solid #e1e7eb;border-radius:8px}header{display:flex;justify-content:space-between;gap:12px}time,.operator{font-size:12px;color:#61717d}.operator span{color:#88949b}table{width:100%;table-layout:fixed;border-collapse:collapse;font-size:12px}th,td{text-align:left;padding:10px;vertical-align:top;border:1px solid #e5eaee;white-space:pre-wrap;overflow-wrap:anywhere}thead{background:#f6f8fa;color:#63737f}th:first-child{width:24%}tbody th{font-weight:500}td:last-child{background:#f6fbf8}td a{display:inline-grid;gap:5px;color:#956000}td img{width:80px;height:70px;object-fit:contain}footer{display:flex;align-items:center;justify-content:flex-end;gap:10px;margin-top:16px;padding-top:14px;border-top:1px solid #e3e8eb;font-size:12px}footer>span:first-child{margin-right:auto}button{cursor:pointer}footer button,.state button{padding:8px 12px;border:1px solid #dce3e8;border-radius:6px;background:#fff}button:disabled{opacity:.45;cursor:not-allowed}@media(max-width:600px){.history-mask{padding:10px}.history-dialog{padding:16px}.history-entry{padding:8px}header{flex-direction:column}th,td{padding:6px}footer{gap:6px}}
</style>
