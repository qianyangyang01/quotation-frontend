<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { canViewPurchaseCost } from '@/data/quotationVisibility'
import { localQuoteDate } from '@/data/customerQuoteSheet'
import { fobQuantityQuote, fobSheetRows, fobTierQuotes, loadFobQuoteProduct, type FobSourceMode, type FobSmallOrderPolicy } from '@/services/fobQuotation'
import { fobBatchIssue, fobBatchLookupError, fobBatchSnapshot, parseFobBatch, type FobBatchLine, type FobBatchMode } from '@/services/fobBatchQuotation'
import { saveFobQuotation, validateFobQuotation, type FobQuotationSnapshot } from '@/services/fobQuotationRecords'
import { idempotencyKey } from '@/services/http'
import FobQuoteSheet from './FobQuoteSheet.vue'
import FobBatchSheetExports from './FobBatchSheetExports.vue'

const props = defineProps<{ rate: number; policy: FobSmallOrderPolicy | null; financePending?: boolean; financeError?: string; salesperson?: string; initial?: { text: string; mode: FobBatchMode; customer: string; sources?: FobSourceMode[] } }>()
const emit = defineEmits<{ close: [] }>()
const dialog = ref<HTMLDialogElement>(), paste = ref(props.initial?.text || ''), mode = ref<FobBatchMode>(props.initial?.mode || 'quantity'), source = ref<FobSourceMode>('auto')
const lines = ref<FobBatchLine[]>([]), busy = ref(false), error = ref(''), preview = ref<FobQuotationSnapshot>(), saving = ref(false), savedNo = ref(''), querying = ref(0)
const customer = ref(props.initial?.customer || ''), title = ref('JerryFulfillment Quote Sheet'), agent = ref(props.salesperson || ''), date = ref(localQuoteDate()), whatsapp = ref('')
const filter = ref('all'), stale = ref(false), saveKey = ref('')
const originalSources = ref(props.initial?.sources)
watch(paste,()=>{originalSources.value=undefined})
watch(customer,()=>{if(preview.value)preview.value.sheet.customerName=customer.value.trim()})
let generation = 0, controller: AbortController | undefined, restoreFocus: HTMLElement | null = null
const financeIssue = computed(() => props.financePending ? '财务参数加载中，请稍候' : props.financeError || (!props.policy ? '报价规则尚未就绪，请关闭弹窗后刷新财务参数' : !Number.isFinite(props.rate) || props.rate <= 0 ? '汇率无效' : ''))
function finalRows(line: FobBatchLine) {
  if (issue(line) || !line.product) return []
  try { if(mode.value==='tiers')return fobSheetRows(line.product,props.rate,props.policy).map(r=>({...r,label:`${r.minQty}${r.maxQty==null?'+':'–'+r.maxQty}${r.unit}`}))
    const q=fobQuantityQuote(line.product,fobTierQuotes(line.product,props.rate),line.quantity,props.rate,props.policy);return [{...q,label:`${q.quantity}${q.row.unit}`}]; } catch {return []}
}
function costRows(line: FobBatchLine) { try { return line.product ? fobTierQuotes(line.product, props.rate) : [] } catch { return [] } }
function issue(line: FobBatchLine) { return fobBatchIssue(line, lines.value, mode.value, props.rate, props.policy) }
const valid = computed(() => lines.value.filter(l => !issue(l)))
const selected = computed(() => valid.value.filter(l => l.selected))
const invalid = computed(() => lines.value.filter(l => !!issue(l)))
const visible = computed(() => lines.value.filter(l => filter.value === 'all' || (filter.value === 'valid' ? !issue(l) : !!issue(l))))
const previewChanged = computed(() => !!preview.value && (preview.value.rate !== props.rate || JSON.stringify(preview.value.policy) !== JSON.stringify(props.policy)))
function discardPreview() { preview.value = undefined; savedNo.value = ''; saveKey.value = ''; stale.value = false }
watch([mode, source], () => { discardPreview(); if (source.value) { error.value = '' } })
watch(source, () => { cancelQueries(); lines.value.forEach(l => { l.product = undefined; l.error = ''; l.loading = false }) })
function cancelQueries() { generation++; controller?.abort(); busy.value = false }
function changedSku(line: FobBatchLine) { line.sku = line.sku.trim().toUpperCase(); line.product = undefined; line.error = ''; line.formatError = ''; discardPreview() }
async function query(parse = false, only?: FobBatchLine) {
  if (busy.value || saving.value) return
  error.value = ''
  if (parse) { try { lines.value = parseFobBatch(paste.value) } catch (e) { error.value = (e as Error).message; return } }
  if (!lines.value.length) return
  discardPreview(); const request = ++generation
  controller = new AbortController(); const signal = controller.signal
  busy.value = true; querying.value = 0
  const targets = only ? [only] : lines.value
  targets.forEach(l => { l.error = ''; l.product = undefined; l.loading = true })
  let cursor = 0
  const cache = new Map<string, ReturnType<typeof loadFobQuoteProduct>>()
  async function worker() {
    while (cursor < targets.length && request === generation) {
      const line = targets[cursor++]!
      try {
        if (line.formatError || !/^[A-Z0-9][A-Z0-9._/-]{0,95}$/.test(line.sku)) throw new Error(line.formatError || 'SKU格式无效')
        const rowSource = originalSources.value?.[line.id - 1] ?? source.value
        const key = `${rowSource}:${line.sku}`
        if (!cache.has(key)) cache.set(key, loadFobQuoteProduct(line.sku, rowSource, AbortSignal.any([signal, AbortSignal.timeout(20000)]), true))
        const product = await cache.get(key)!
        if (request === generation) line.product = product
      } catch (e) { if (request === generation) line.error = fobBatchLookupError(e) }
      finally { if (request === generation) { line.loading = false; querying.value++ } }
    }
  }
  await Promise.all(Array.from({ length: Math.min(4, targets.length) }, worker))
  if (request === generation) busy.value = false
}
async function generate() {
  error.value = ''
  saving.value = true
  try {
    if (financeIssue.value) throw new Error(financeIssue.value)
    const snapshot = fobBatchSnapshot(selected.value, mode.value, props.rate, props.policy!, { title: title.value, agent: agent.value, date: date.value, whatsapp: whatsapp.value })
    snapshot.sheet.customerName=customer.value.trim()
    preview.value = await validateFobQuotation(customer.value, snapshot)
    saveKey.value = idempotencyKey('fob-batch'); savedNo.value = ''; stale.value = false
  } catch (e) { error.value = (e as Error).message }
  finally { saving.value = false }
}
async function save() {
  if (!preview.value || saving.value || savedNo.value) return
  error.value = ''
  if (!customer.value.trim()) { error.value = '请填写客户名称'; return }
  if (financeIssue.value || previewChanged.value || stale.value) { error.value = '资料或财务参数已变化，请返回核对并重新查询后生成'; return }
  saving.value = true
  try { const record = await saveFobQuotation(customer.value.trim(), preview.value, saveKey.value); savedNo.value = record.no }
  catch (e) { error.value = e instanceof Error ? e.message : '保存失败，请重试'; if (error.value.includes('变化') || error.value.includes('更新') || error.value.includes('不一致')) stale.value = true }
  finally { saving.value = false }
}
function exportIssues() {
  const content = ['行号\tSKU\t数量\t原因', ...invalid.value.map(l => [l.id, l.sku, l.quantity, issue(l)].map(v => String(v).replace(/[\t\r\n]/g, ' ')).join('\t'))].join('\n')
  const url = URL.createObjectURL(new Blob([content], { type: 'text/plain;charset=utf-8' })), a = document.createElement('a')
  a.href = url; a.download = 'FOB报价异常清单.txt'; a.click(); setTimeout(() => URL.revokeObjectURL(url), 1000)
}
function close() { if (!saving.value) { cancelQueries(); dialog.value?.close(); emit('close') } }
onMounted(async () => { restoreFocus = document.activeElement as HTMLElement; dialog.value?.showModal(); await nextTick(); if (props.initial) void query(true) })
onBeforeUnmount(() => { cancelQueries(); restoreFocus?.focus() })
</script>
<template>
  <dialog ref="dialog" class="batch-dialog" aria-label="批量粘贴报价" @cancel.prevent="close">
    <header><div><h2>批量粘贴报价</h2><p>粘贴SKU清单，核对异常后合并生成一份FOB报价单</p></div><button type="button" aria-label="关闭批量报价" :disabled="saving" @click="close">×</button></header>
    <p v-if="error" role="alert" class="error">{{ error }}</p><p v-if="financeIssue" role="alert" class="error">{{ financeIssue }}</p>
    <fieldset :disabled="saving" class="batch-fieldset">
    <template v-if="!preview">
      <div class="batch-body">
        <aside><label for="fob-batch-paste"><b>从Excel复制一列或两列粘贴</b></label><p>每行一个SKU，可用空格、逗号或Tab分隔数量。最多100行。</p><textarea id="fob-batch-paste" v-model="paste" :disabled="saving || busy" placeholder="PF2600274  30&#10;PF2600166，50&#10;ZS2600437" />
          <label>报价方式<select v-model="mode" :disabled="saving || busy"><option value="quantity">按指定数量（漏写需补齐）</option><option value="tiers">全部阶梯（允许不填数量）</option></select></label>
          <label>采购来源<select v-model="source" :disabled="saving || busy || !!originalSources"><option value="auto">自动：优先FOB资料</option><option value="fob">仅FOB资料</option><option value="standard">仅新采购资料</option></select></label>
          <p v-if="originalSources">再次发起将按各商品原采购来源查询最新资料。</p>
          <button class="primary" type="button" :disabled="saving || busy || !!financeIssue" @click="query(true)">{{ busy ? `查询中 ${querying}/${lines.length}` : '查询粘贴清单' }}</button>
          <p>漏写数量不会自动按1报价。修改右侧SKU后，请点击该行“重新查询”。</p>
        </aside>
        <main><div class="summary"><b>共{{ lines.length }}行</b><span>可报价 {{ valid.length }}</span><span class="warning">待处理 {{ invalid.length }}</span></div>
          <div class="filters"><button :class="{active:filter==='all'}" @click="filter='all'">全部</button><button :class="{active:filter==='valid'}" @click="filter='valid'">可报价</button><button :class="{active:filter==='invalid'}" @click="filter='invalid'">异常 / 待处理</button><button :disabled="saving || busy || !lines.length" @click="query()">重新查询全部</button><button :disabled="saving || !invalid.length || busy" @click="exportIssues">导出异常清单</button></div>
          <div class="result-scroll"><table aria-label="批量报价核对结果"><thead><tr><th>选择</th><th>原行</th><th>SKU</th><th>数量</th><th>状态 / 原因</th><th class="price">报关最终价 $/单位</th><th class="price">不报关最终价 $/单位</th><th>操作</th></tr></thead><tbody>
            <template v-for="line in visible" :key="line.id"><tr :class="{invalid:!!issue(line)}"><td><input v-model="line.selected" type="checkbox" :aria-label="`选择第${line.id}行`" :disabled="saving || busy"></td><td>{{ line.id }}</td><td><input v-model="line.sku" :aria-label="`第${line.id}行SKU`" :disabled="saving || busy" maxlength="96" @input="changedSku(line)"></td><td><input v-model="line.quantity" :aria-label="`第${line.id}行数量`" :disabled="saving || busy || mode==='tiers'" inputmode="numeric" placeholder="请填数量" @input="discardPreview"></td><td :class="issue(line)?'warning':'success'">{{ issue(line) || '可报价' }}</td><td class="price"><div v-for="(q,i) in finalRows(line)" :key="i"><b>${{ q.declaredUsd }}</b><small>{{ q.label }}</small></div><span v-if="issue(line)">—</span></td><td class="price"><div v-for="(q,i) in finalRows(line)" :key="i"><b>${{ q.undeclaredUsd }}</b><small>{{ q.label }}</small></div><span v-if="issue(line)">—</span></td><td><button :disabled="saving || busy" @click="query(false,line)">重新查询</button></td></tr>
            <tr v-if="line.product && canViewPurchaseCost"><td colspan="8"><details><summary>{{ line.sku }} 采购成本明细（内部）</summary><p>{{ line.product.parsed.freight.basis }}</p><table><thead><tr><th>起订量</th><th>采购价 ¥</th><th>含票价 ¥</th><th>运费 ¥</th><th>成本 ¥</th></tr></thead><tbody><tr v-for="tier in costRows(line)" :key="tier.minQty"><td>{{ tier.minQty }}</td><td>{{ tier.purchaseCny }}</td><td>{{ tier.taxIncludedCny }}</td><td>{{ tier.freightCny }}</td><td>{{ tier.costCny }}</td></tr></tbody></table></details></td></tr></template>
            <tr v-if="!visible.length"><td colspan="8">{{ lines.length ? '当前筛选无结果' : '请先粘贴并查询SKU' }}</td></tr>
          </tbody></table></div>
        </main>
      </div>
      <div class="metadata"><label>客户名称<input v-model="customer" maxlength="120" placeholder="保存前填写客户名称"></label><label>报价单标题<input v-model="title" maxlength="80"></label><label>署名<input v-model="agent" maxlength="40"></label><label>日期<input v-model="date" type="date"></label><label>WhatsApp<input v-model="whatsapp" maxlength="40"></label></div>
      <footer><div><b>已选 {{ selected.length }} 行有效商品</b><p>{{ invalid.length }}行待处理、{{ valid.length-selected.length }}行未选择不会进入报价单；异常继续保留在清单中。</p></div><button class="primary" :disabled="saving || busy || !selected.length || !!financeIssue" @click="generate">{{ saving ? '正在校验最新价格…' : `生成${selected.length}行商品报价单` }}</button></footer>
    </template>
    <template v-else>
      <div class="preview-toolbar"><button :disabled="saving" @click="discardPreview">返回核对 / 修改</button><b>{{ preview.items?.length }}行商品 · {{ new Set(preview.items?.map(i=>i.product.sku)).size }}个SKU</b><label>客户名称<input v-model="customer" maxlength="120" :disabled="saving || !!savedNo" @input="saveKey=idempotencyKey('fob-batch')"></label><button class="primary" :disabled="saving || !!savedNo || stale || previewChanged || !!financeIssue" @click="save">{{ saving?'正在保存…':savedNo?'已保存':'保存合并报价单' }}</button></div>
      <p v-if="savedNo" role="status">已保存：{{ savedNo }}。可在报价记录中查看整张报价单。</p>
      <p v-if="stale || previewChanged" class="error" role="alert">资料或价格已变化，请返回核对并重新查询后生成，当前预览不可用于报价。</p>
      <template v-else><FobBatchSheetExports :sheet="preview.sheet" /><FobQuoteSheet :product="preview.product" :rate="preview.rate" :quantity="String(preview.quantity)" :policy="preview.policy" :saved-sheet="preview.sheet" :preview-only="!savedNo" /></template>
    </template>
    </fieldset>
  </dialog>
</template>
<style scoped>
.batch-fieldset{border:0;margin:0;padding:0;min-width:0}.price{background:#eef6ff;color:#1c629a;min-width:92px}.price b{font-size:17px}.price small{display:block;font-size:10px;font-weight:400;color:#617d94}.price div+div{margin-top:8px}
.batch-dialog{box-sizing:border-box;width:min(1420px,96vw);max-height:94vh;border:1px solid #dbe5ee;border-radius:12px;padding:20px;color:#233b50;background:#fff;overflow:auto}.batch-dialog::backdrop{background:#152a4166}header,footer,.preview-toolbar,.summary,.filters{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap}h2{margin:0;font-size:21px}p{font-size:12px;line-height:1.6;color:#708092}button,input,select,textarea{font:inherit;box-sizing:border-box;border:1px solid #ccd9e5;border-radius:6px;padding:8px;color:#29465d;background:white}button{cursor:pointer;font-size:13px}button:disabled{opacity:.5;cursor:not-allowed}button.primary{background:#f58b15;border-color:#f58b15;color:white;font-weight:700;padding:11px 18px}header>button{font-size:24px}.batch-body{display:grid;grid-template-columns:270px minmax(0,1fr);gap:18px;margin-top:16px}aside{background:#f6f9fc;border:1px solid #e1e9f0;padding:14px;border-radius:8px}aside textarea{width:100%;height:260px;resize:vertical;line-height:1.8}label{display:grid;gap:6px;font-size:12px}aside label,aside button{margin-top:12px;width:100%}.summary{justify-content:flex-start;padding:12px;background:#f0f6fc;border-radius:6px}.filters{justify-content:flex-start;margin:12px 0}.active{color:#1764a4;background:#eef6ff}.result-scroll{overflow:auto;max-height:520px}table{border-collapse:collapse;width:100%;font-size:12px}td,th{border:1px solid #dce5ed;padding:8px;text-align:left}th{background:#f1f6fa}td input:not([type=checkbox]){width:100%;min-width:72px}td:nth-child(3){min-width:155px}td:nth-child(4){width:95px}.warning{color:#a55b08}.success{color:#17805a}.error{padding:12px;background:#fff0ed;color:#a83324}.invalid{background:#fffaf1}.metadata{display:flex;flex-wrap:wrap;gap:12px;border-top:1px solid #e2e9ef;padding:16px 0;margin-top:16px}.metadata label{flex:1;min-width:145px}.metadata input{width:100%}footer{position:sticky;bottom:-20px;background:#fff;padding:16px 0;border-top:1px solid #e1e7ed;z-index:1}.preview-toolbar{justify-content:flex-start;margin:16px 0}summary{cursor:pointer;color:#71879b}details table{margin-top:8px}@media(max-width:760px){.batch-body{grid-template-columns:1fr}aside textarea{height:150px}.batch-dialog{padding:12px}footer{bottom:-12px}}
</style>
