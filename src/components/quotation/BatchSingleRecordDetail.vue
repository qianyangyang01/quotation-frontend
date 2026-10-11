<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { BatchSingleDocument } from '@/services/batchSingleQuotation'
import { canViewPurchaseCost, canViewPricingFactors } from '@/data/quotationVisibility'
import BatchSingleSheet from './BatchSingleSheet.vue'

const props = defineProps<{ document: BatchSingleDocument }>()
const emit = defineEmits<{ close: [] }>()
const query = ref(''), page = ref(0), pageSize = ref(10), tab = ref<'details' | 'sheet'>('details')
const expanded = ref(new Set(props.document.items.slice(0, 1).map(item => item.input.primarySku)))
const filtered = computed(() => props.document.items.filter(item => item.input.primarySku.toLowerCase().includes(query.value.trim().toLowerCase())))
const pages = computed(() => Math.max(1, Math.ceil(filtered.value.length / pageSize.value)))
const visible = computed(() => filtered.value.slice(page.value * pageSize.value, (page.value + 1) * pageSize.value))
const allExpanded = computed(() => visible.value.length > 0 && visible.value.every(item => expanded.value.has(item.input.primarySku)))
const panel = ref<HTMLElement>(), scroller = ref<HTMLElement>()
const currency = (value?: number | null) => value == null || !Number.isFinite(value) ? '—' : value.toFixed(2)
const date = (value?: string) => value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—'
function toggle(sku: string) { const next = new Set(expanded.value); if (next.has(sku)) next.delete(sku); else next.add(sku); expanded.value = next }
function togglePage() { const next = new Set(expanded.value), collapse = allExpanded.value; visible.value.forEach(item => collapse ? next.delete(item.input.primarySku) : next.add(item.input.primarySku)); expanded.value = next }
watch([query, pageSize], () => { page.value = 0 })
watch(page, () => { scroller.value?.scrollTo?.({ top: 0 }) })
watch(() => props.document.id, () => { query.value = ''; page.value = 0; expanded.value = new Set(); tab.value = 'details' })
let previousFocus: HTMLElement | null = null, previousOverflow = ''
function keydown(event: KeyboardEvent) {
  if (event.key === 'Escape') { event.preventDefault(); emit('close') }
  if (event.key !== 'Tab') return
  const items = panel.value?.querySelectorAll<HTMLElement>('button:not(:disabled),input,select,[tabindex="0"]')
  if (!items?.length) return
  if (event.shiftKey && document.activeElement === items[0]) { event.preventDefault(); items[items.length - 1]?.focus() }
  else if (!event.shiftKey && document.activeElement === items[items.length - 1]) { event.preventDefault(); items[0]?.focus() }
}
onMounted(() => { previousFocus = document.activeElement as HTMLElement; previousOverflow = document.body.style.overflow; document.body.style.overflow = 'hidden'; panel.value?.focus() })
onBeforeUnmount(() => { document.body.style.overflow = previousOverflow; previousFocus?.focus() })
</script>

<template>
  <Teleport to="body">
    <div class="batch-detail-mask" @click.self="emit('close')">
      <section ref="panel" class="batch-detail" role="dialog" aria-modal="true" aria-labelledby="batch-detail-title" tabindex="-1" @keydown="keydown">
        <header class="detail-header">
          <div><small>QUOTATION DOCUMENT</small><h2 id="batch-detail-title">{{ document.no }}</h2><p>{{ document.customerName }} · 单品批量 · {{ document.items.length }} 个 SKU · {{ document.sheet.rows.length }} 条渠道</p></div>
          <button class="close" aria-label="关闭批量报价详情" @click="emit('close')">关闭 ×</button>
        </header>
        <div class="snapshot-meta"><span>报价人：{{ document.salespersonName || document.sheet.agent }}</span><span>保存时间：{{ date(document.createdAt) }}</span><span>历史价格快照</span></div>
        <nav class="detail-tabs" aria-label="批量报价详情标签"><button :class="{ active: tab === 'details' }" @click="tab='details'">SKU 与渠道明细</button><button :class="{ active: tab === 'sheet' }" @click="tab='sheet'">客户报价单</button></nav>
        <template v-if="tab==='details'">
          <div class="detail-toolbar"><label>搜索 SKU <input v-model="query" type="search" aria-label="搜索批量明细SKU" placeholder="输入 SKU"></label><button :disabled="!visible.length" @click="togglePage">{{ allExpanded ? '收起本页渠道' : '展开本页渠道' }}</button><span>点击 SKU 展开渠道，价格按数量分列显示</span></div>
          <div ref="scroller" class="detail-scroll" tabindex="0" aria-label="批量SKU明细滚动区域">
            <table class="sku-detail-table"><thead><tr><th class="sticky-sku">SKU / 渠道</th><th v-if="canViewPurchaseCost">采购成本 ¥/件</th><th v-for="label in document.sheet.quantityLabels" :key="label">{{ label }} · USD</th><th>国家 / 分区</th><th>物流商 / 时效</th></tr></thead>
              <tbody v-for="item in visible" :key="item.input.primarySku" class="sku-group">
                <tr class="sku-summary"><th class="sticky-sku"><button :aria-expanded="expanded.has(item.input.primarySku)" :aria-label="`展开 ${item.input.primarySku} 渠道`" @click="toggle(item.input.primarySku)">{{ expanded.has(item.input.primarySku) ? '▾' : '▸' }} {{ item.input.primarySku }}</button><small>{{ item.sheet.rows.length }} 条渠道</small></th><td v-if="canViewPurchaseCost">{{ currency(item.input.purchaseUnitPriceCny) }}</td><td :colspan="document.sheet.quantityLabels.length + 2">{{ item.input.productSummary || item.input.productCategory || '单品报价' }}<span class="weight" v-if="canViewPricingFactors && item.input.exchangeRate">保存汇率 {{ item.input.exchangeRate }}</span></td></tr>
                <template v-if="expanded.has(item.input.primarySku)"><tr v-for="row in item.sheet.rows" :key="row.key"><td class="sticky-sku"><span class="channel-name">{{ row.sourceDescription || row.provider }}</span></td><td v-if="canViewPurchaseCost">—</td><td v-for="(price,i) in row.prices" :key="i" class="money">{{ currency(price) }}</td><td>{{ row.country }}<small v-if="row.region">{{ row.region }}</small></td><td>{{ row.provider }}<small>{{ row.shippingTime || '未提供时效' }}</small></td></tr></template>
              </tbody>
            </table>
            <p v-if="!filtered.length" class="empty">没有匹配的 SKU</p>
          </div>
          <div class="detail-pagination"><span>共 {{ filtered.length }} 个 SKU</span><label>每页 <select v-model.number="pageSize" aria-label="每页SKU数量"><option :value="10">10</option><option :value="20">20</option><option :value="50">50</option></select> 个</label><div><button :disabled="page===0" @click="page--">上一页</button><span>{{ page+1 }} / {{ pages }}</span><button :disabled="page+1>=pages" @click="page++">下一页</button></div></div>
        </template>
        <div v-else class="detail-scroll sheet-scroll"><BatchSingleSheet :sheet="document.sheet" /></div>
        <footer class="detail-footer"><span>按保存时的采购、物流和客户价格核对；当前资料变化不会覆盖此报价。</span><button class="primary" @click="tab=tab==='sheet'?'details':'sheet'">{{ tab==='sheet'?'返回 SKU 明细':'查看完整客户报价单' }}</button></footer>
      </section>
    </div>
  </Teleport>
</template>

<style scoped>
.batch-detail-mask{position:fixed;inset:0;z-index:1500;background:#10253866;display:flex;justify-content:flex-end}.batch-detail{width:min(1420px,94vw);height:100dvh;display:flex;flex-direction:column;background:#fff;color:#243647;box-shadow:-12px 0 40px #152c4c22;outline:none}.detail-header{padding:20px 24px 12px;display:flex;justify-content:space-between;align-items:flex-start;gap:12px;flex-shrink:0}.detail-header small{color:#ec881e;font-size:11px;letter-spacing:1px}.detail-header h2{margin:5px 0;font-size:22px;overflow-wrap:anywhere}.detail-header p{font-size:13px;color:#697c8c;margin:6px 0}.snapshot-meta{display:flex;gap:18px;flex-wrap:wrap;padding:10px 24px;background:#f3f7fa;color:#667987;font-size:12px;flex-shrink:0}.detail-tabs{display:flex;gap:22px;padding:0 24px;border-bottom:1px solid #dde5eb;flex-shrink:0}.detail-tabs button{border:0;border-bottom:3px solid transparent;border-radius:0;background:none;padding:13px 0}.detail-tabs button.active{border-color:#ef941e;color:#ae610d;font-weight:700}.detail-toolbar{display:flex;gap:12px;align-items:center;padding:14px 24px;flex-wrap:wrap;flex-shrink:0}.detail-toolbar label{font-size:13px}.detail-toolbar input{margin-left:8px;width:190px;padding:9px;border:1px solid #cdd8e0;border-radius:6px}.detail-toolbar span{font-size:12px;color:#72818e}.detail-scroll{flex:1;min-height:0;overflow:auto;overscroll-behavior:contain;margin:0 24px;border:1px solid #dee5eb;border-radius:6px}.sku-detail-table{border-collapse:separate;border-spacing:0;min-width:850px;width:100%;font-size:13px;white-space:nowrap}.sku-detail-table th,.sku-detail-table td{padding:12px 14px;border-bottom:1px solid #e3e9ed;text-align:left}.sku-detail-table thead th{position:sticky;top:0;background:#eaf1f8;z-index:3}.sku-detail-table .sticky-sku{position:sticky;left:0;background:#fff;min-width:185px;max-width:300px;z-index:1}.sku-detail-table thead .sticky-sku{z-index:4;background:#eaf1f8}.sku-detail-table small{display:block;font-size:11px;color:#71818d;margin-top:4px}.sku-summary td,.sku-summary .sticky-sku{background:#f6f8fa}.sku-summary button{border:0;background:none;padding:0;color:#223d53;font-weight:700;white-space:nowrap}.money{font-variant-numeric:tabular-nums;font-weight:650;color:#163f58}.weight{margin-left:24px;font-size:12px;color:#788893}.channel-name{display:block;white-space:normal;overflow-wrap:anywhere;font-size:12px}.detail-pagination{padding:12px 24px;display:flex;align-items:center;gap:16px;flex-wrap:wrap;font-size:13px;flex-shrink:0}.detail-pagination div{display:flex;align-items:center;gap:14px;margin-left:auto}.detail-pagination select{padding:7px;border:1px solid #ced9e0;border-radius:5px}.detail-footer{display:flex;justify-content:space-between;gap:16px;align-items:center;padding:16px 24px;border-top:1px solid #d9e1e7;background:#fff;box-shadow:0 -5px 18px #16314908;flex-shrink:0}.detail-footer span{color:#71818d;font-size:12px;line-height:1.6}.detail-footer button{min-height:44px;white-space:nowrap}.sheet-scroll{padding:12px}.empty{text-align:center;padding:35px;color:#6b7c89}button{padding:9px 13px;background:#fff;border:1px solid #cad6df;border-radius:6px;color:#334d61;cursor:pointer}button:disabled{opacity:.45;cursor:default}button.primary{background:#ff991c;border-color:#ff991c;color:#263440;font-weight:700}@media(max-width:700px){.batch-detail{width:100vw}.detail-header,.snapshot-meta,.detail-toolbar,.detail-pagination,.detail-footer{padding-left:12px;padding-right:12px}.detail-scroll{margin:0 12px}.detail-footer{align-items:flex-end}.detail-footer span{max-width:50%}.detail-tabs{padding:0 12px}}
</style>
