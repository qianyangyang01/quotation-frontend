<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import FinanceCountrySelect from '@/components/finance/FinanceCountrySelect.vue'
import catalog from '@/data/logisticsPriceCountryCatalog.json'
import { priceHistoryAttributes } from '@/data/logisticsMajorChannelHistory'
import { publicationTime, type RealPriceSnapshot } from '@/data/logisticsRealPriceHistory'
import { priceUpdateGroups, updateAmount, updateCounts, updatePercent, updateStatusLabels, type UpdateStatus, type PriceUpdateGroup } from '@/data/logisticsUpdateOverview'

const props = defineProps<{ snapshot: RealPriceSnapshot; initialCountry: string; initialAttribute: string; initialWeight: number; refreshing: boolean; error: string }>()
const emit = defineEmits<{ close: [] }>()
const dialog = ref<HTMLDialogElement>()
const batchSelect = ref<HTMLSelectElement>()
const country = ref(props.initialCountry), attribute = ref(props.initialAttribute), weight = ref(props.initialWeight)
const selectedBatch = ref(''), search = ref(''), filter = ref<'changed' | 'all' | UpdateStatus>('changed')
const originalBatch = ref('')
const originalDetails = ref<HTMLDetailsElement>()
const downloading = ref(false), downloadError = ref('')
const countries = catalog.countries.map(item => ({ country: item.name, code: item.code }))
const countryCode = computed(() => catalog.countries.find(item => item.name === country.value)?.code || '')
const attributes = computed(() => priceHistoryAttributes(props.snapshot))
const batches = computed(() => priceUpdateGroups(props.snapshot, countryCode.value, weight.value, attribute.value))
watch(batches, values => { if (!values.some(batch => batch.id === selectedBatch.value)) selectedBatch.value = values[0]?.id || '' }, { immediate: true })
const batch = computed(() => batches.value.find(item => item.id === selectedBatch.value))
watch(selectedBatch, () => { originalBatch.value = '' })
const sourceBatches = computed(() => {
  const group = batch.value
  if (!group) return []
  if (group.imports.length) return group.imports.map(item => ({ id: `import:${item.id}`, importId: item.id, importedAt: item.importedAt, operator: item.operator, source: group.batches.find(source => source.importBatchId === item.id) }))
  return group.batches.map(source => ({ id: source.id, importId: source.importBatchId, importedAt: '', operator: '', source }))
})
const scopedRows = computed(() => originalBatch.value ? batch.value?.batches.find(item => item.id === originalBatch.value)?.rows || [] : batch.value?.rows || [])
watch(sourceBatches, values => { if (originalBatch.value && !values.some(item => item.id === originalBatch.value && item.source)) originalBatch.value = '' })
const searchedRows = computed(() => {
  const terms = search.value.trim().toLocaleLowerCase().split(/\s+/).filter(Boolean)
  return scopedRows.value.filter(row => terms.every(term => `${row.channel.name} ${row.channel.provider} ${row.channel.code} ${row.region}`.toLocaleLowerCase().includes(term)))
})
const counts = computed(() => updateCounts(searchedRows.value))
const changes = computed(() => counts.value.up + counts.value.down)
const exceptional = computed(() => counts.value.first + counts.value.added + counts.value.removed + counts.value.unavailable)
// Changed view includes additions/removals and incomparable rows so they can never disappear behind the headline numbers.
const visibleRows = computed(() => searchedRows.value.filter(row => filter.value === 'all' || (filter.value === 'changed' ? row.status !== 'same' : row.status === filter.value)))
const price = (value: number | null | undefined) => value == null ? '—' : `¥ ${value.toFixed(2)}`
function viewOriginal(id: string) {
  originalBatch.value = id
  if (originalDetails.value) originalDetails.value.open = false
}
const batchLabel = (value: PriceUpdateGroup) => {
  const providers = value.providers.length > 2 ? `${value.providers.slice(0, 2).join('、')}等 ${value.providers.length} 家` : value.providers.join('、')
  if (value.windowStartAt) return `${publicationTime(value.windowStartAt)} · ${value.operator} · ${value.imports.length} 个上传批次`
  const original = value.batches[0]!
  return `${publicationTime(value.startedAt)} · ${providers} · ${original.importBatchId ? '原始批次 ' + original.importBatchId.slice(0, 8) : (value.rows[0]?.rollback ? '回滚' : '单次发布') + ' V' + value.rows[0]?.after.number + ' · ' + value.rows[0]?.channel.name + ' · ' + value.id.slice(-6)}`
}
let previousFocus: HTMLElement | null = null
onMounted(() => {
  previousFocus = document.activeElement as HTMLElement
  dialog.value?.showModal()
  batchSelect.value?.focus()
})
onBeforeUnmount(() => { dialog.value?.close(); previousFocus?.focus() })
function backdropClick(event: MouseEvent) {
  if (event.target !== dialog.value) return
  const rect = dialog.value.getBoundingClientRect()
  if (event.clientX < rect.left || event.clientX > rect.right || event.clientY < rect.top || event.clientY > rect.bottom) emit('close')
}
async function download() {
  if (!batch.value || !visibleRows.value.length || downloading.value) return
  downloading.value = true; downloadError.value = ''
  const filterLabel = filter.value === 'all' ? '全部' : filter.value === 'changed' ? '有变动及待核对' : updateStatusLabels[filter.value]
  const data = { batch: batch.value, rows: [...visibleRows.value], country: country.value, attribute: attribute.value || '全部属性', weight: weight.value, filter: `${originalBatch.value ? '原始批次 ' + originalBatch.value.replace(/^import:/, '') : '整组'} · ${filterLabel}`, search: search.value.trim() }
  try {
    const { downloadPriceUpdateOverview } = await import('@/data/logisticsUpdateOverviewExport')
    await downloadPriceUpdateOverview(data)
  } catch { downloadError.value = '概览图下载失败，请重试' }
  finally { downloading.value = false }
}
</script>

<template>
  <dialog ref="dialog" class="update-overview" aria-modal="true" aria-labelledby="update-overview-title" @cancel.prevent="emit('close')" @click="backdropClick">
    <header class="overview-header">
      <div><h2 id="update-overview-title">价格更新概览 <span>真实已发布价格</span></h2><p>每个渠道涨了多少、降了多少，一眼看清。</p></div>
      <button type="button" class="overview-close" aria-label="关闭更新概览" @click="emit('close')">×</button>
    </header>
    <div class="overview-body">
      <p v-if="error" class="overview-warning" role="alert">{{ error }}；当前保留上次成功读取的数据。</p>
      <p v-if="refreshing" class="overview-hint" role="status">正在刷新价格，当前保留已读取的更新记录。</p>
      <div class="overview-filters">
        <label class="batch-field">更新批次 · 4小时汇总<select ref="batchSelect" v-model="selectedBatch" aria-label="更新批次" :disabled="!batches.length"><option v-if="!batches.length" value="">当前范围暂无更新</option><option v-for="item in batches" :key="item.id" :value="item.id">{{ batchLabel(item) }}</option></select></label>
        <div class="country-field"><span>国家/地区</span><FinanceCountrySelect v-model="country" :countries="countries" /></div>
        <label>物流属性<select v-model="attribute" aria-label="概览物流属性"><option value="">全部属性</option><option v-for="item in attributes" :key="item" :value="item">{{ item }}</option></select></label>
        <label>试算重量<select v-model="weight" aria-label="概览试算重量"><option :value="0.5">0.5 kg</option><option :value="1">1 kg</option><option :value="2">2 kg</option></select></label>
      </div>
      <p class="overview-context">{{ country }} · {{ attribute || '全部属性' }} · {{ weight }} kg <span>价格单位：元 / 票</span></p>
      <div v-if="batch" class="batch-context">
        <span v-if="batch.windowStartAt">操作人：<b>{{ batch.operator }}</b> · 导入窗口：{{ publicationTime(batch.windowStartAt) }} — {{ publicationTime(batch.windowEndAt!) }}（结束时刻另起一组）</span>
        <span v-else>{{ batch.batches[0]?.importBatchId ? '未留完整操作人 / 导入时间，原始批次独立展示' : '无导入批次 / 回滚，独立展示' }}</span>
      </div>
      <details v-if="batch" :key="batch.id" ref="originalDetails" class="original-batches">
        <summary>展开原始上传批次（{{ sourceBatches.length }} 个）</summary>
        <p class="overview-hint">同一操作人从第一笔导入起固定4小时，不随后续上传延长。下列批次均保留原始记录；只展示其中已发布的价格。</p>
        <article v-for="item in sourceBatches" :key="item.id" class="original-batch-item" :data-batch="item.id">
          <div><b>{{ item.importedAt ? publicationTime(item.importedAt) : '未记录导入时间' }}<template v-if="item.operator"> · {{ item.operator }}</template></b><small>{{ item.importId ? '原始上传批次：' + item.importId : '版本：' + item.source?.rows[0]?.after.id }}</small><small v-for="file in item.source?.files || []" :key="file">{{ file }}</small><small v-if="item.source">本次筛选内 {{ item.source.rows.length }} 条已发布价格 · 发布时间 {{ publicationTime(item.source.startedAt) }}<template v-if="publicationTime(item.source.startedAt) !== publicationTime(item.source.publishedAt)"> — {{ publicationTime(item.source.publishedAt) }}</template></small><small v-else>当前国家、属性和重量下暂无已发布价格</small></div>
          <button type="button" :disabled="!item.source" :aria-pressed="originalBatch === item.id" @click="viewOriginal(item.id)">查看该批价格</button>
        </article>
      </details>
      <div v-if="originalBatch" class="original-scope" role="status">正在查看原始批次：{{ originalBatch.replace(/^import:/, '') }} <button type="button" class="text-button" @click="originalBatch = ''">返回整组汇总</button></div>
      <div class="overview-stats" aria-label="更新统计">
        <div class="stat-total"><span>本次涉及</span><b>{{ searchedRows.length }} <small>条</small></b></div>
        <div class="stat-up"><span>↑ 涨价</span><b>{{ counts.up }} <small>条</small></b></div>
        <div class="stat-down"><span>↓ 降价</span><b>{{ counts.down }} <small>条</small></b></div>
        <div class="stat-same"><span>— 不变</span><b>{{ counts.same }} <small>条</small></b></div>
      </div>
      <p class="overview-hint">统计{{ originalBatch ? '当前原始批次' : '本组' }}筛选范围的渠道 / 分区 / 发布版本；同一渠道多次更新逐次展示。<template v-if="exceptional">另有 <b>{{ exceptional }} 条</b>首次记录、新增、移除或暂不可比记录，不计入涨跌。</template></p>
      <div class="overview-toolbar">
        <div class="overview-tabs" aria-label="筛选价格变动">
          <button type="button" :class="{ active: filter === 'changed' }" :aria-pressed="filter === 'changed'" @click="filter = 'changed'">有变动 {{ changes + exceptional }}</button>
          <button type="button" :class="{ active: filter === 'all' }" :aria-pressed="filter === 'all'" @click="filter = 'all'">全部 {{ searchedRows.length }}</button>
          <button type="button" :class="{ active: filter === 'up' }" :aria-pressed="filter === 'up'" @click="filter = 'up'">涨价 {{ counts.up }}</button>
          <button type="button" :class="{ active: filter === 'down' }" :aria-pressed="filter === 'down'" @click="filter = 'down'">降价 {{ counts.down }}</button>
          <button type="button" :class="{ active: filter === 'same' }" :aria-pressed="filter === 'same'" @click="filter = 'same'">不变 {{ counts.same }}</button>
        </div>
        <input v-model="search" aria-label="搜索更新渠道" placeholder="搜索渠道、物流商或分区" />
      </div>
      <div class="overview-table-scroll" tabindex="0" role="region" aria-label="渠道更新价格表">
        <table class="overview-table">
          <thead><tr><th scope="col">渠道名称 / 分区</th><th scope="col">更新前价格</th><th scope="col">更新后价格</th><th scope="col">具体涨跌金额</th><th scope="col">涨跌幅</th></tr></thead>
          <tbody><tr v-for="row in visibleRows" :key="row.key" :data-status="row.status">
            <td class="channel-cell"><b>{{ row.channel.name }}</b><small>{{ row.channel.provider }} · {{ row.region }} · {{ row.channel.attribute }}</small><small v-if="row.importBatchId" :title="row.importBatchId">原始批次 {{ row.importBatchId.slice(0, 8) }}</small><small v-if="!row.channel.enabled || !row.channel.quoteReady">当前暂不可报价 · 历史发布记录</small><span v-if="row.rollback" class="rollback-tag">回滚发布</span></td>
            <td><span>{{ price(row.before?.price) }}</span><small v-if="row.before">V{{ row.before.number }} · {{ publicationTime(row.before.timestamp) }}</small><small v-else>{{ row.reason }}</small><small v-if="row.before?.price === null">{{ row.before.reason }}</small></td>
            <td class="after-cell"><strong>{{ price(row.after.price) }}</strong><small>V{{ row.after.number }} · {{ publicationTime(row.after.timestamp) }}</small><small v-if="row.after.price === null">{{ row.after.reason }}</small></td>
            <td class="amount-cell" :class="row.status"><b>{{ updateAmount(row) }}</b><small v-if="row.change === null">{{ row.reason }}</small></td>
            <td class="percent-cell" :class="row.status">{{ updatePercent(row) }}<small v-if="row.before?.price === 0 && row.change !== null">原价为 0，不计算涨跌幅</small></td>
          </tr></tbody>
        </table>
        <div v-if="!visibleRows.length" class="overview-empty" role="status"><b>{{ !batch ? '当前范围暂无已发布更新记录' : searchedRows.length ? '当前筛选下没有此类变动' : '没有匹配的渠道' }}</b><p>{{ !batch ? '可切换国家、物流属性或重量查看。' : '可切换“全部”或调整搜索条件查看。' }}</p></div>
      </div>
      <p v-if="filter === 'changed' && counts.same" class="overview-hint">已隐藏 {{ counts.same }} 条不变记录 · <button type="button" class="text-button" @click="filter = 'all'">展开全部</button></p>
      <p class="overview-hint">金额差 = 更新后价格 − 更新前价格，含渠道原生固定费，不含财务折扣、关税及其他附加费。</p>
      <p class="overview-hint">同一操作人从首笔导入起按固定4小时归组，按导入时间而非发布时间划分；原始上传批次可展开。无完整导入信息及回滚独立展示。名称和属性按当前资料显示。</p>
      <p v-if="downloadError" class="overview-warning" role="alert">{{ downloadError }}</p>
    </div>
    <footer class="overview-footer"><span>仅查看已发布价格历史</span><div><button type="button" @click="emit('close')">关闭</button><button type="button" class="primary" :disabled="!visibleRows.length || downloading" @click="download">{{ downloading ? '生成中…' : '下载概览图' }}</button></div></footer>
  </dialog>
</template>

<style scoped>
.original-batches{margin:0 0 16px;border:1px solid #dce5ee;border-radius:7px;background:#f8fafc;padding:10px 14px}.original-batches>summary{cursor:pointer;color:#2169c4;font-size:13px;font-weight:600}.original-batch-item{display:flex;align-items:center;justify-content:space-between;gap:16px;border-top:1px solid #e0e7ef;padding:12px 0}.original-batch-item>div{min-width:0;overflow-wrap:anywhere}.original-batch-item b{font-size:13px}.original-batch-item small{display:block;color:#6d8296;font-size:12px;margin-top:4px}.original-batch-item button{flex-shrink:0;font-size:12px}.original-scope{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:14px;padding:10px 14px;border:1px solid #ccdef2;border-radius:6px;background:#eef5ff;color:#315b83;font-size:12px;overflow-wrap:anywhere}.original-scope button{flex-shrink:0}.original-batch-item button[aria-pressed=true]{background:#2169c4;color:#fff}.batch-context{line-height:1.8}@media(max-width:600px){.original-batch-item{align-items:flex-start;flex-direction:column;gap:8px}.original-scope{align-items:flex-start;flex-direction:column}}

.update-overview{box-sizing:border-box;width:min(1240px,calc(100vw - 40px));max-height:92vh;max-height:92dvh;padding:0;border:1px solid #d7e2ec;border-radius:14px;background:#fff;color:#233f57;box-shadow:0 24px 80px #142d4c40;font:14px/1.5 Arial,"Microsoft YaHei",sans-serif;overflow:auto}.update-overview::backdrop{background:#192c4590}.overview-header{display:flex;justify-content:space-between;align-items:flex-start;gap:20px;padding:24px 28px;border-bottom:1px solid #e0e7ef}.overview-header h2{font-size:26px;margin:0;font-weight:750}.overview-header h2 span{display:inline-block;font-size:12px;vertical-align:middle;font-weight:400;background:#eaf6f3;color:#167c75;padding:4px 8px;margin-left:10px;border-radius:4px}.overview-header p{margin:6px 0 0;color:#617b94}.overview-close{font-size:29px!important;line-height:1;border:0!important;padding:2px 8px!important}.overview-body{padding:20px 28px 10px}.overview-filters{display:grid;grid-template-columns:minmax(260px,2.3fr) minmax(150px,1fr) minmax(150px,1fr) minmax(100px,.7fr);gap:16px}.overview-filters label,.country-field{display:flex;flex-direction:column;gap:7px;color:#59738c}.overview-filters select,.overview-toolbar input{box-sizing:border-box;border:1px solid #cad8e5;border-radius:6px;background:#fff;color:#244461;min-height:40px;padding:8px 10px;font:inherit;width:100%;min-width:0}.overview-filters :deep(.country-trigger){font:inherit;min-height:40px;border-color:#cad8e5;color:#244461}.overview-context{color:#59738c;margin:16px 0 7px}.overview-context>span{border-left:1px solid #cbd6df;margin-left:14px;padding-left:14px}.batch-context{display:flex;justify-content:space-between;gap:12px;color:#708399;font-size:12px;margin-bottom:16px}.batch-context details{max-width:55%;text-align:right;overflow-wrap:anywhere}.batch-context summary{cursor:pointer;color:#285f9a}.batch-context p{margin:5px 0}.overview-stats{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:16px}.overview-stats>div{padding:15px 22px;border-radius:8px;display:flex;flex-direction:column}.overview-stats b{font-size:32px;line-height:1.3;margin-top:4px;font-variant-numeric:tabular-nums}.overview-stats small{font-size:17px;font-weight:400}.stat-total{background:#edf5ff}.stat-up{background:#fff1f0;color:#cb4039}.stat-down{background:#eaf8f6;color:#148878}.stat-same{background:#f1f4f7;color:#60768a}.overview-hint{font-size:12px;color:#6d8296;margin:10px 0;line-height:1.8}.overview-toolbar{display:flex;align-items:center;justify-content:space-between;gap:12px;margin:16px 0 12px}.overview-tabs{display:flex;flex-wrap:wrap;gap:8px}.update-overview button{font:inherit;cursor:pointer;border:1px solid #d0ddeb;background:white;color:#34526d;border-radius:6px;padding:8px 16px}.update-overview button:focus-visible,.update-overview input:focus-visible,.update-overview select:focus-visible{outline:2px solid #2975d2;outline-offset:2px}.update-overview button.active,.update-overview button.primary{color:#fff;background:#2169c4;border-color:#2169c4}.update-overview button:disabled{opacity:.5;cursor:default}.overview-toolbar input{max-width:235px}.overview-table-scroll{overflow-x:auto;border:1px solid #dce5ee;border-radius:8px}.overview-table{width:100%;border-collapse:collapse;font-variant-numeric:tabular-nums;text-align:left;table-layout:fixed;min-width:850px}.overview-table th{background:#f3f6fa;font-weight:500;font-size:13px;color:#607990;padding:12px 14px}.overview-table th:first-child{width:26%}.overview-table th:nth-child(2),.overview-table th:nth-child(3){width:18%}.overview-table th:nth-child(4){width:25%}.overview-table th:last-child{width:13%}.overview-table td{border-top:1px solid #e4ebf2;padding:14px;vertical-align:middle;font-size:16px;overflow-wrap:anywhere}.overview-table small{display:block;font-size:11px;color:#788ba0;margin-top:4px;line-height:1.6;font-weight:400}.channel-cell b{font-size:15px}.overview-table .after-cell strong{font-size:21px}.overview-table .amount-cell b{font-size:21px;white-space:nowrap}.amount-cell.up{background:#fff3f1;color:#d63e37}.amount-cell.down{background:#eaf8f5;color:#158877}.amount-cell.same{color:#7a8d9c}.amount-cell.first,.amount-cell.added,.amount-cell.removed,.amount-cell.unavailable{background:#fffaf0;color:#9a702f}.overview-table .amount-cell.first b,.overview-table .amount-cell.added b,.overview-table .amount-cell.removed b,.overview-table .amount-cell.unavailable b{font-size:16px}.percent-cell.up{color:#d63e37}.percent-cell.down{color:#158877}.rollback-tag{display:inline-block;padding:1px 5px;border-radius:4px;background:#f1effa;color:#716292;font-size:11px;margin-top:5px}.overview-empty{text-align:center;padding:36px;color:#6c8296}.overview-empty b{font-size:16px}.overview-footer{position:sticky;bottom:0;padding:16px 28px;background:#fff;border-top:1px solid #e0e7ef;display:flex;justify-content:space-between;align-items:center;gap:10px}.overview-footer>span{font-size:12px;color:#75899a}.overview-footer>div{display:flex;gap:12px}.update-overview .text-button{border:0;background:transparent;color:#2169c4;padding:0}.overview-warning{padding:10px 14px;background:#fff2e9;color:#a65431;border-radius:6px}
@media(max-width:1050px){.overview-filters{grid-template-columns:2fr 1fr 1fr}.batch-field{grid-column:span 3}.overview-toolbar{flex-wrap:wrap}.overview-toolbar input{max-width:100%}.overview-header h2{font-size:23px}.overview-stats>div{padding:12px 16px}}
@media(max-width:600px){.update-overview{width:calc(100vw - 16px);max-height:96dvh;border-radius:10px}.overview-header{padding:16px;gap:10px}.overview-header h2{font-size:21px}.overview-header h2 span{font-size:10px;margin-left:5px}.overview-header p{font-size:12px}.overview-body{padding:16px 12px 8px}.overview-filters{grid-template-columns:repeat(2,minmax(0,1fr));gap:10px}.batch-field{grid-column:span 2}.country-field{grid-column:span 2}.overview-stats{grid-template-columns:repeat(2,minmax(0,1fr));gap:10px}.overview-stats>div{padding:10px 15px}.overview-stats b{font-size:27px}.overview-context{font-size:12px}.overview-context>span{display:block;border:0;margin:4px 0 0;padding:0}.batch-context{flex-direction:column}.batch-context details{text-align:left;max-width:100%}.overview-tabs{gap:6px}.overview-tabs button{padding:6px 10px;font-size:12px}.overview-footer{padding:12px}.overview-footer>span{display:none}.overview-footer>div{margin-left:auto}.overview-table-scroll:focus-visible{outline:2px solid #2975d2;outline-offset:2px}}
</style>
