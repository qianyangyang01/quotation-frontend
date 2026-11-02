<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import FinanceCountrySelect from '@/components/finance/FinanceCountrySelect.vue'
import { priceHistoryAttributes } from '@/data/logisticsMajorChannelHistory'
import { publicationTime, type RealPriceSnapshot } from '@/data/logisticsRealPriceHistory'
import { allCountryPriceUpdateGroups, matchesUpdateAttribute, updateAmount, updateCounts, updatePercent, updateStatusLabels, type UpdateStatus, type PriceUpdateGroup, type PriceUpdateRow } from '@/data/logisticsUpdateOverview'

const props = defineProps<{ snapshot: RealPriceSnapshot; initialCountry: string; initialAttribute: string; initialWeight: number; refreshing: boolean; error: string }>()
const emit = defineEmits<{ close: [] }>()
const dialog = ref<HTMLDialogElement>()
const batchSearchInput = ref<HTMLInputElement>()
const batchSearch = ref('')
const tableScroll = ref<HTMLElement>()
const drawer = ref<'uploads' | 'row' | ''>('')
const detailKey = ref('')
const drawerClose = ref<HTMLButtonElement>()
let detailTrigger: HTMLElement | null = null
const country = ref('全部国家'), attribute = ref(''), weight = ref(props.initialWeight)
const selectedBatch = ref(''), search = ref(''), filter = ref<'changed' | 'all' | 'exceptional' | UpdateStatus>('changed')
const originalBatch = ref('')
const downloading = ref(false), downloadError = ref('')
const countryCode = computed(() => countries.value.find(item => item.country === country.value)?.code || '')
const attributes = computed(() => priceHistoryAttributes(props.snapshot))
const batches = computed(() => allCountryPriceUpdateGroups(props.snapshot, weight.value))
const matchedBatches = computed(() => {
  const terms = batchSearch.value.trim().toLocaleLowerCase().split(/\s+/).filter(Boolean)
  return batches.value.filter(item => terms.every(term => batchLabel(item).toLocaleLowerCase().includes(term)))
})
const batchPageSize = 10
const batchPageCount = computed(() => Math.max(1, Math.ceil(matchedBatches.value.length / batchPageSize)))
const batchPage = computed(() => Math.floor(Math.max(0, matchedBatches.value.findIndex(item => item.id === selectedBatch.value)) / batchPageSize) + 1)
const pageBatches = computed(() => matchedBatches.value.slice((batchPage.value - 1) * batchPageSize, batchPage.value * batchPageSize))
function changeBatchPage(page: number) {
  if (page < 1 || page > batchPageCount.value) return
  const first = matchedBatches.value[(page - 1) * batchPageSize]
  if (first) selectedBatch.value = first.id
}
const batch = computed(() => batches.value.find(item => item.id === selectedBatch.value))
watch(selectedBatch, () => { originalBatch.value = ''; drawer.value = ''; country.value = '全部国家'; search.value = '' })
const sourceBatches = computed(() => {
  const group = batch.value
  if (!group) return []
  if (group.imports.length) return group.imports.map(item => ({ id: `import:${item.id}`, importId: item.id, importedAt: item.importedAt, operator: item.operator, source: group.batches.find(source => source.importBatchId === item.id) }))
  return group.batches.map(source => ({ id: source.id, importId: source.importBatchId, importedAt: '', operator: '', source }))
})
const scopedRows = computed(() => originalBatch.value ? batch.value?.batches.find(item => item.id === originalBatch.value)?.rows || [] : batch.value?.rows || [])
watch(sourceBatches, values => { if (originalBatch.value && !values.some(item => item.id === originalBatch.value && item.source)) originalBatch.value = '' })
const filteredRows = computed(() => scopedRows.value.filter(row => (!countryCode.value || row.countryCode === countryCode.value) && matchesUpdateAttribute(row.channel, attribute.value)))
const searchedRows = computed(() => {
  const terms = search.value.trim().toLocaleLowerCase().split(/\s+/).filter(Boolean)
  return filteredRows.value.filter(row => terms.every(term => `${row.countryName} ${row.countryCode} ${row.channel.name} ${row.channel.provider} ${row.channel.code} ${row.region}`.toLocaleLowerCase().includes(term)))
})
const counts = computed(() => updateCounts(searchedRows.value))
const changes = computed(() => counts.value.up + counts.value.down)
const exceptional = computed(() => counts.value.first + counts.value.added + counts.value.removed + counts.value.unavailable)
// Default is comparable rises and falls; exceptional records have their own always-visible count and filter.
const visibleRows = computed(() => searchedRows.value.filter(row => filter.value === 'all' || (filter.value === 'changed' ? row.status === 'up' || row.status === 'down' : filter.value === 'exceptional' ? row.change === null : row.status === filter.value)))
const rowPage = ref(1)
const pageSize = ref(10)
const rowPageCount = computed(() => Math.max(1, Math.ceil(visibleRows.value.length / pageSize.value)))
const pageRows = computed(() => visibleRows.value.slice((rowPage.value - 1) * pageSize.value, rowPage.value * pageSize.value))
const countrySummary = computed(() => {
  const rowsInScope = scopedRows.value.filter(row => matchesUpdateAttribute(row.channel, attribute.value))
  const codes = [...new Set(rowsInScope.map(row => row.countryCode))]
  return codes.map(code => {
    const rows = rowsInScope.filter(row => row.countryCode === code)
    const counts = updateCounts(rows)
    return { code, name: rows[0]!.countryName!, total: rows.length, ...counts, exceptional: counts.first + counts.added + counts.removed + counts.unavailable }
  }).sort((a, b) => (b.up + b.down) - (a.up + a.down) || a.name.localeCompare(b.name, 'zh-CN'))
})
const channelCount = computed(() => new Set(searchedRows.value.map(row => row.channel.id)).size)
const countries = computed(() => [{ country: '全部国家', code: '' }, ...countrySummary.value.map(item => ({ country: item.name, code: item.code }))])
const quickCountries = computed(() => countrySummary.value.slice(0, 5))
watch([selectedBatch, originalBatch, country, attribute, weight, search, filter, pageSize], () => { rowPage.value = 1; if (tableScroll.value) tableScroll.value.scrollTop = 0 })
watch(rowPage, () => { if (tableScroll.value) tableScroll.value.scrollTop = 0 })
watch(rowPageCount, count => { rowPage.value = Math.min(rowPage.value, count) })
watch(countries, values => { if (!values.some(item => item.country === country.value)) country.value = '全部国家' })
const detailRow = computed(() => batch.value?.rows.find(row => row.key === detailKey.value))
watch(detailRow, row => { if (!row && drawer.value === 'row') drawer.value = '' })
function openDrawer(mode: 'uploads' | 'row', row?: PriceUpdateRow) {
  detailTrigger = document.activeElement as HTMLElement
  detailKey.value = row?.key || ''
  drawer.value = mode
  void nextTick(() => drawerClose.value?.focus())
}
function closeDrawer() {
  drawer.value = ''
  void nextTick(() => detailTrigger?.isConnected && detailTrigger.focus())
}
function cancel() { if (drawer.value) closeDrawer(); else emit('close') }
const price = (value: number | null | undefined) => value == null ? '—' : `¥ ${value.toFixed(2)}`
function viewOriginal(id: string) {
  originalBatch.value = id
  country.value = '全部国家'
  search.value = ''
  closeDrawer()
}
function batchLabel(value: PriceUpdateGroup) {
  const providers = value.providers.length > 2 ? `${value.providers.slice(0, 2).join('、')}等 ${value.providers.length} 家` : value.providers.join('、')
  if (value.windowStartAt) return `${publicationTime(value.windowStartAt)} · ${value.operator} · ${value.imports.length} 个上传批次`
  const original = value.batches[0]!
  return `${publicationTime(value.startedAt)} · ${providers} · ${original.importBatchId ? '原始批次 ' + original.importBatchId.slice(0, 8) : (value.rows[0]?.rollback ? '回滚' : '单次发布') + ' V' + value.rows[0]?.after.number + ' · ' + value.rows[0]?.channel.name + ' · ' + value.id.slice(-6)}`
}
watch(matchedBatches, values => { if (!values.some(item => item.id === selectedBatch.value)) selectedBatch.value = values[0]?.id || '' }, { immediate: true })
let previousFocus: HTMLElement | null = null
onMounted(() => {
  previousFocus = document.activeElement as HTMLElement
  dialog.value?.showModal()
  batchSearchInput.value?.focus()
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
  const filterLabel = filter.value === 'all' ? '全部' : filter.value === 'changed' ? '有涨跌' : filter.value === 'exceptional' ? '新增 / 移除 / 无可比价' : updateStatusLabels[filter.value]
  const data = { batch: batch.value, rows: [...visibleRows.value], country: country.value, attribute: attribute.value || '全部属性', weight: weight.value, filter: `${originalBatch.value ? '原始批次 ' + originalBatch.value.replace(/^import:/, '') : '整组'} · ${filterLabel}`, search: search.value.trim() }
  try {
    const { downloadPriceUpdateOverview } = await import('@/data/logisticsUpdateOverviewExport')
    await downloadPriceUpdateOverview(data)
  } catch { downloadError.value = '概览图下载失败，请重试' }
  finally { downloading.value = false }
}
</script>

<template>
  <dialog ref="dialog" class="update-overview" aria-modal="true" aria-labelledby="update-overview-title" @cancel.prevent="cancel" @click="backdropClick">
    <header class="overview-header" :inert="!!drawer">
      <div><h2 id="update-overview-title">价格更新概览 <span>真实已发布价格</span></h2><p>选择一次更新，查看全部国家和渠道的价格变化</p></div>
      <div class="header-actions"><button type="button" class="primary" :disabled="!visibleRows.length || downloading" @click="download">{{ downloading ? '生成中…' : '下载概览图' }}</button><button type="button" class="overview-close" aria-label="关闭更新概览" @click="emit('close')">×</button></div>
    </header>
    <div class="overview-layout" :inert="!!drawer">
      <aside class="batch-sidebar" aria-label="更新批次列表">
        <div class="sidebar-title"><h3>更新批次</h3><span>每页10批</span></div>
        <input ref="batchSearchInput" v-model="batchSearch" aria-label="搜索更新批次" placeholder="搜索日期 / 操作人" />
        <div class="batch-list">
          <button v-for="item in pageBatches" :key="item.id" type="button" class="batch-card" :class="{ active: selectedBatch === item.id }" :aria-pressed="selectedBatch === item.id" :aria-label="batchLabel(item)" :data-batch-id="item.id" @click="selectedBatch = item.id">
            <b>{{ publicationTime(item.windowStartAt || item.startedAt) }}</b>
            <span v-if="item.windowStartAt">{{ item.operator }} · {{ item.imports.length }} 个上传批次</span>
            <span v-else>{{ item.providers.join('、') }} · {{ item.batches[0]?.importBatchId ? '原始批次独立展示' : item.rows[0]?.rollback ? '回滚发布' : '单次发布' }}</span>
            <small v-if="item.windowEndAt">至 {{ publicationTime(item.windowEndAt) }}</small><small v-else>{{ item.id.replace(/^(import|version):/, '').slice(0, 8) }} · {{ item.rows[0]?.channel.name }}</small>
          </button>
          <p v-if="!pageBatches.length" class="overview-empty">{{ batchSearch ? '没有匹配的更新批次' : '暂无已发布更新记录' }}</p>
        </div>
        <nav v-if="matchedBatches.length" class="batch-pagination" aria-label="更新批次分页"><span>共 {{ matchedBatches.length }} 批</span><div><button type="button" aria-label="上一页更新批次" :disabled="batchPage === 1" @click="changeBatchPage(batchPage - 1)">上一页</button><span aria-live="polite">{{ batchPage }} / {{ batchPageCount }}</span><button type="button" aria-label="下一页更新批次" :disabled="batchPage === batchPageCount" @click="changeBatchPage(batchPage + 1)">下一页</button></div></nav>
        <p class="sidebar-note">同一操作人 · 首笔起固定4小时<br>原始上传批次保留，可展开追溯</p>
      </aside>
      <main class="overview-main">
        <p v-if="error" class="overview-warning" role="alert">{{ error }}；当前保留上次成功读取的数据。</p>
        <p v-if="refreshing" class="overview-hint" role="status">正在刷新价格，当前保留已读取的更新记录。</p>
        <div class="batch-heading">
          <div><h3 v-if="batch">{{ publicationTime(batch.windowStartAt || batch.startedAt) }}<template v-if="batch.windowEndAt"> — {{ publicationTime(batch.windowEndAt).slice(11) }}</template></h3><h3 v-else>选择更新批次</h3><p v-if="batch">{{ batch.operator || '独立发布记录' }} <span v-if="!batch.windowStartAt">· 无完整导入信息或回滚，独立展示</span></p></div>
          <button v-if="batch" type="button" class="text-button" @click="openDrawer('uploads')">原始上传批次（{{ sourceBatches.length }}） ›</button>
        </div>
        <p class="overview-stats" aria-label="更新统计">{{ country }} · {{ attribute || '全部属性' }} · 涉及 {{ new Set(searchedRows.map(row => row.countryCode)).size }} 个国家、{{ channelCount }} 个渠道、{{ searchedRows.length }} 条价格记录</p>
        <div v-if="originalBatch" class="original-scope" role="status"><span>原始批次 {{ originalBatch.replace(/^import:/, '').slice(0, 8) }}</span><button type="button" class="text-button" @click="originalBatch = ''">返回整组汇总</button></div>
        <div class="overview-tabs" aria-label="筛选价格变动">
          <button type="button" :class="{ active: filter === 'changed' }" :aria-pressed="filter === 'changed'" @click="filter = 'changed'">有涨跌 <b>{{ changes }}</b></button>
          <button type="button" class="stat-up" :class="{ active: filter === 'up' }" :aria-pressed="filter === 'up'" @click="filter = 'up'">涨价 <b>{{ counts.up }}</b></button>
          <button type="button" class="stat-down" :class="{ active: filter === 'down' }" :aria-pressed="filter === 'down'" @click="filter = 'down'">降价 <b>{{ counts.down }}</b></button>
          <button type="button" :class="{ active: filter === 'same' }" :aria-pressed="filter === 'same'" @click="filter = 'same'">不变 <b>{{ counts.same }}</b></button>
          <button type="button" class="stat-exceptional" :class="{ active: filter === 'exceptional' }" :aria-pressed="filter === 'exceptional'" @click="filter = 'exceptional'">新增 / 移除 / 无可比 <b>{{ exceptional }}</b></button>
          <button type="button" :class="{ active: filter === 'all' }" :aria-pressed="filter === 'all'" @click="filter = 'all'">全部 <b>{{ searchedRows.length }}</b></button>
        </div>
        <div class="country-strip" aria-label="国家快捷筛选">
          <div class="country-chips"><button type="button" :class="{ active: country === '全部国家' }" :aria-pressed="country === '全部国家'" @click="country = '全部国家'">全部国家</button><button v-for="item in quickCountries" :key="item.code" type="button" :class="{ active: country === item.name }" :aria-pressed="country === item.name" :aria-label="`查看${item.name}明细`" @click="country = item.name"><b>{{ item.name }}</b><small><span class="up">↑ {{ item.up }}</span><span class="down">↓ {{ item.down }}</span></small></button></div>
          <div class="country-search"><label>全部 {{ countrySummary.length }} 国 · 可搜索<FinanceCountrySelect v-model="country" :countries="countries" /></label></div>
        </div>
        <div class="overview-toolbar">
          <input v-model="search" aria-label="搜索更新渠道" placeholder="搜索国家、渠道或物流商" />
          <label><span class="sr-only">物流属性</span><select v-model="attribute" aria-label="概览物流属性"><option value="">全部属性</option><option v-for="item in attributes" :key="item" :value="item">{{ item }}</option></select></label>
          <label>试算重量 <select v-model="weight" aria-label="概览试算重量"><option :value="0.5">0.5 kg</option><option :value="1">1 kg</option><option :value="2">2 kg</option></select></label><span class="unit">金额：元/票</span>
        </div>
        <div ref="tableScroll" class="overview-table-scroll" tabindex="0" role="region" aria-label="渠道更新价格表">
          <table class="overview-table">
            <thead><tr><th scope="col">国家/地区</th><th scope="col">渠道 / 属性 / 分区</th><th scope="col">更新前</th><th scope="col">更新后</th><th scope="col">涨跌金额</th><th scope="col">涨跌幅</th><th scope="col">详情</th></tr></thead>
            <tbody><tr v-for="row in pageRows" :key="row.key" :data-status="row.status">
              <td><b>{{ row.countryName }}</b></td><td class="channel-cell"><b>{{ row.channel.name }}</b><small>{{ row.channel.provider }} · {{ row.channel.attribute }} · {{ row.region }}</small><small v-if="!row.channel.enabled || !row.channel.quoteReady">当前暂不可报价 · 历史记录</small><small v-if="row.rollback">回滚发布</small></td>
              <td>{{ price(row.before?.price) }}</td><td class="after-cell"><strong>{{ price(row.after.price) }}</strong></td>
              <td class="amount-cell" :class="row.status"><b :aria-label="updateAmount(row)">{{ row.change === null ? updateStatusLabels[row.status] : row.change === 0 ? '— 0.00' : `${row.change > 0 ? '↑' : '↓'} ${Math.abs(row.change).toFixed(2)}` }}</b></td>
              <td :class="row.status">{{ updatePercent(row) }}</td><td><button type="button" class="text-button" :aria-label="`查看${row.countryName}${row.channel.name}价格详情`" @click="openDrawer('row', row)">查看</button></td>
            </tr></tbody>
          </table>
          <div v-if="!visibleRows.length" class="overview-empty" role="status"><b>{{ !batch ? '当前范围暂无已发布更新记录' : !searchedRows.length ? '没有匹配的渠道' : '当前筛选下没有此类变动' }}</b><p v-if="batch">{{ filter === 'changed' && exceptional ? `另有 ${exceptional} 条新增、移除或无可比记录，可切换上方分类查看。` : '可切换“全部”或调整搜索条件查看。' }}</p></div>
        </div>
        <nav class="row-pagination" aria-label="渠道明细分页"><div>共 {{ visibleRows.length }} 条 <label><select v-model="pageSize" aria-label="每页明细条数"><option :value="10">每页10条</option><option :value="20">每页20条</option><option :value="50">每页50条</option></select></label></div><div><button type="button" aria-label="上一页渠道明细" :disabled="rowPage === 1" @click="rowPage--">上一页</button><span aria-live="polite">{{ rowPage }} / {{ rowPageCount }}</span><button type="button" aria-label="下一页渠道明细" :disabled="rowPage === rowPageCount" @click="rowPage++">下一页</button></div></nav>
        <p class="overview-hint">按 {{ weight }} kg 比较已发布价格，含渠道原生固定费；新增、移除及无可比价单独列示。下载包含全部筛选结果。</p>
        <p v-if="downloadError" class="overview-warning" role="alert">{{ downloadError }}</p>
      </main>
    </div>
    <footer class="overview-footer" :inert="!!drawer">仅查看价格历史，不修改报价数据 <span>同一渠道多次更新逐次保留 · 不含财务折扣、关税及其他附加费</span></footer>
    <div v-if="drawer" class="drawer-shade" @click.self="closeDrawer">
      <section class="detail-drawer" role="region" :aria-label="drawer === 'uploads' ? '原始上传批次详情' : '价格变动详情'">
        <header><h3>{{ drawer === 'uploads' ? '原始上传批次' : '价格变动详情' }}</h3><button ref="drawerClose" type="button" aria-label="关闭详情" @click="closeDrawer">×</button></header>
        <div class="drawer-body">
          <template v-if="drawer === 'uploads'">
            <p class="overview-hint">同一操作人从首笔导入起固定4小时，不随后续上传延长。原始批次及源文件记录完整保留。</p>
            <article v-for="item in sourceBatches" :key="item.id" class="original-batch-item" :data-batch="item.id"><b>{{ item.importedAt ? publicationTime(item.importedAt) : '未记录导入时间' }} · {{ item.operator || '未记录操作人' }}</b><small>{{ item.importId ? '原始上传批次：' + item.importId : '版本：' + item.source?.rows[0]?.after.id }}</small><small v-for="file in item.source?.files || []" :key="file">{{ file }}</small><small>{{ item.source ? `全量 ${item.source.rows.length} 条已发布价格` : '当前重量下暂无已发布价格' }}</small><button type="button" :disabled="!item.source" :aria-pressed="originalBatch === item.id" @click="viewOriginal(item.id)">查看该批价格</button></article>
          </template>
          <template v-else-if="detailRow">
            <h4>{{ detailRow.countryName }} · {{ detailRow.channel.name }}</h4><p>{{ detailRow.channel.provider }} · {{ detailRow.channel.attribute }} · {{ detailRow.region }} · {{ weight }} kg</p>
            <div class="detail-prices"><div><small>更新前价格</small><strong>{{ price(detailRow.before?.price) }}</strong><small v-if="detailRow.before">V{{ detailRow.before.number }} · {{ publicationTime(detailRow.before.timestamp) }}</small><small>{{ detailRow.before?.reason || (!detailRow.before ? detailRow.reason : '') }}</small></div><div><small>更新后价格</small><strong>{{ price(detailRow.after.price) }}</strong><small>V{{ detailRow.after.number }} · {{ publicationTime(detailRow.after.timestamp) }}</small><small>{{ detailRow.after.reason }}</small></div></div>
            <p class="detail-amount" :class="detailRow.status">{{ updateAmount(detailRow) }} <span>{{ updatePercent(detailRow) }}</span></p><p v-if="detailRow.reason">{{ detailRow.reason }}</p><p v-if="detailRow.before?.price === 0">原价为0，不计算涨跌幅。</p>
            <dl><dt>更新前源文件</dt><dd>{{ detailRow.before?.file || '无更早记录' }}</dd><dt>更新后源文件</dt><dd>{{ detailRow.after.file }}</dd><dt>原始上传批次</dt><dd>{{ detailRow.importBatchId || '无导入批次 / 独立发布' }}</dd><dt>发布版本</dt><dd>{{ detailRow.after.id }}</dd></dl><p v-if="detailRow.rollback">此条为回滚发布记录，独立保留。</p>
            <button type="button" class="text-button" @click="drawer = 'uploads'">查看本组原始上传批次 ›</button>
          </template>
        </div>
      </section>
    </div>
  </dialog>
</template>

<style scoped>
.update-overview{box-sizing:border-box;width:min(1560px,calc(100vw - 32px));height:min(960px,94dvh);max-width:none;max-height:96dvh;padding:0;border:1px solid #d7e2ec;border-radius:12px;background:#fff;color:#233f57;box-shadow:0 24px 80px #142d4c40;font:14px/1.5 Arial,"Microsoft YaHei",sans-serif;overflow:hidden}.update-overview[open]{display:flex;flex-direction:column}.update-overview::backdrop{background:#192c4590}.update-overview *{box-sizing:border-box}.update-overview button,.update-overview input,.update-overview select{font:inherit;border:1px solid #d5e0ed;border-radius:6px;background:#fff;color:#34526d;padding:8px 12px;min-width:0}.update-overview button{cursor:pointer}.update-overview button:disabled{opacity:.45;cursor:default}.update-overview :is(button,input,select,[tabindex]):focus-visible{outline:2px solid #2975d2;outline-offset:2px}.update-overview button.active,.update-overview button.primary{background:#2169c4;color:white;border-color:#2169c4}.update-overview .text-button{border:0;background:none;color:#2169c4;padding:4px 0}.overview-header{display:flex;justify-content:space-between;align-items:center;padding:18px 24px;gap:18px;border-bottom:1px solid #e1e8f0;flex-shrink:0}.overview-header h2{font-size:25px;margin:0}.overview-header h2 span{font-size:11px;font-weight:400;color:#167c75;background:#eaf6f3;border-radius:4px;padding:4px 7px;vertical-align:middle;margin-left:10px}.overview-header p{margin:4px 0 0;color:#72849a;font-size:13px}.header-actions{display:flex;align-items:center;gap:14px;flex-shrink:0}.update-overview .overview-close{font-size:27px;line-height:1;border:0;padding:4px 8px}.overview-layout{display:grid;grid-template-columns:245px minmax(0,1fr);flex:1;min-height:0}.batch-sidebar{display:flex;flex-direction:column;min-height:0;padding:18px 16px 10px;background:#f6f8fb;border-right:1px solid #e1e8f0;gap:12px}.sidebar-title{display:flex;align-items:center;justify-content:space-between;gap:10px}.sidebar-title h3{margin:0;font-size:18px}.sidebar-title>span,.sidebar-note{color:#7d8ba0;font-size:12px}.batch-list{overflow:auto;flex:1;min-height:0;padding:2px}.batch-list .batch-card{display:flex;flex-direction:column;gap:4px;text-align:left;width:100%;padding:12px;margin-bottom:9px;overflow-wrap:anywhere;border-left:3px solid transparent}.batch-card b{font-size:15px}.batch-card span{font-size:12px}.batch-card small{font-size:11px;color:#8594a5}.batch-list .batch-card.active{background:#eaf3ff;color:#2169c4;border-color:#bdd6f5;border-left-color:#2169c4}.batch-pagination{font-size:12px;color:#708399}.batch-pagination>span{display:block;margin-bottom:6px}.batch-pagination>div,.row-pagination,.row-pagination>div{display:flex;align-items:center;justify-content:space-between;gap:8px}.batch-pagination button{font-size:12px;padding:6px 8px}.sidebar-note{margin:0}.overview-main{min-width:0;min-height:0;padding:18px 20px 10px;display:flex;flex-direction:column;gap:10px}.batch-heading{display:flex;align-items:flex-start;justify-content:space-between;gap:10px}.batch-heading h3{font-size:21px;margin:0}.batch-heading p{font-size:12px;color:#73859b;margin:2px 0 0}.batch-heading>button{flex-shrink:0;font-size:12px}.overview-stats{margin:0;font-size:13px;color:#526c85}.overview-tabs{display:flex;gap:8px;flex-shrink:0}.overview-tabs button{flex:1;white-space:nowrap;padding:9px 8px;font-size:13px;border-color:transparent;background:#f2f5f9}.overview-tabs b{margin-left:6px}.overview-tabs .stat-up{color:#ca423c;background:#fff2f1}.overview-tabs .stat-down{color:#178778;background:#eaf8f6}.overview-tabs .stat-exceptional{color:#a47132;background:#fff8ed;font-size:12px}.country-strip{display:flex;align-items:center;gap:12px;padding:3px 0 8px;border-bottom:1px solid #ecf0f5;min-width:0}.country-chips{display:flex;gap:7px;overflow:auto;flex:1;min-width:0;padding:3px}.country-chips button{flex:1;min-width:72px;padding:5px 9px;white-space:nowrap;font-size:12px}.country-chips small{display:flex;gap:12px;justify-content:center;font-size:11px}.country-chips .active :is(.up,.down){color:white}.country-search{width:155px;flex-shrink:0}.country-search label{font-size:11px;color:#74889b}.country-search :deep(.country-trigger){font-size:12px;min-height:33px;padding:5px 8px}.country-search :deep(.country-menu){left:auto;right:0}.overview-toolbar{display:flex;align-items:center;gap:10px;flex-shrink:0}.overview-toolbar>input{flex:1;width:0;min-width:120px}.overview-toolbar label{display:flex;align-items:center;gap:5px;white-space:nowrap;font-size:12px}.overview-toolbar select{max-width:130px}.unit{font-size:12px;color:#74889b;white-space:nowrap}.overview-table-scroll{flex:1;min-height:100px;overflow:auto;border:1px solid #dce5ee;border-radius:7px}.overview-table{width:100%;min-width:820px;border-collapse:separate;border-spacing:0;table-layout:fixed;font-variant-numeric:tabular-nums;text-align:left}.overview-table th{position:sticky;top:0;z-index:1;background:#f1f5fa;color:#607990;font-size:12px;font-weight:500;padding:10px;border-bottom:1px solid #dde6ef}.overview-table th:nth-child(1){width:10%}.overview-table th:nth-child(2){width:26%}.overview-table th:nth-child(3),.overview-table th:nth-child(4){width:14%}.overview-table th:nth-child(5){width:17%}.overview-table th:nth-child(6){width:10%}.overview-table th:nth-child(7){width:9%}.overview-table td{padding:10px;border-bottom:1px solid #e7edf3;font-size:14px;overflow-wrap:anywhere;vertical-align:middle}.overview-table small{display:block;color:#8091a4;font-size:11px;line-height:1.5;margin-top:3px;font-weight:400}.channel-cell b{font-size:13px}.after-cell strong,.amount-cell b{font-size:17px;white-space:nowrap}.up{color:#ce423b}.down{color:#168878}.amount-cell.up{background:#fff3f1}.amount-cell.down{background:#eaf8f6}.amount-cell:is(.first,.added,.removed,.unavailable){color:#9c702e;background:#fff9ef}.amount-cell:is(.first,.added,.removed,.unavailable) b{font-size:13px}.same{color:#7c8e9e}.row-pagination{flex-shrink:0;font-size:12px;color:#6b8097}.row-pagination button,.row-pagination select{padding:6px 9px;font-size:12px}.row-pagination>div:first-child{justify-content:flex-start}.overview-hint{font-size:11px;color:#788ba0;margin:0;line-height:1.6}.overview-empty{text-align:center;padding:28px 12px;color:#7a8da1}.overview-empty b{font-size:15px}.overview-empty p{font-size:12px}.overview-footer{display:flex;justify-content:space-between;gap:16px;flex-shrink:0;border-top:1px solid #e1e8f0;padding:9px 22px;font-size:11px;color:#7b8b9e}.overview-warning{margin:0;padding:8px 12px;background:#fff2e9;color:#a65431;border-radius:6px;font-size:12px}.original-scope{display:flex;justify-content:space-between;align-items:center;gap:10px;background:#eef5ff;padding:6px 10px;font-size:12px;border-radius:5px}.sr-only{position:absolute;width:1px;height:1px;overflow:hidden;clip-path:inset(50%)}
.drawer-shade{position:absolute;inset:0;z-index:10;background:#20395445;display:flex;justify-content:flex-end}.detail-drawer{width:min(560px,100%);height:100%;background:#fff;box-shadow:-12px 0 40px #1e385326;display:flex;flex-direction:column;min-height:0}.detail-drawer>header{display:flex;align-items:center;justify-content:space-between;padding:18px 20px;border-bottom:1px solid #e1e8f0}.detail-drawer h3{margin:0;font-size:20px}.detail-drawer>header button{font-size:23px;border:0;padding:0 8px}.drawer-body{padding:20px;overflow:auto;overflow-wrap:anywhere}.drawer-body h4{font-size:18px;margin:0 0 8px}.drawer-body>p{color:#6b8097;font-size:13px}.original-batch-item{padding:16px 0;border-bottom:1px solid #e3eaf2}.original-batch-item b{font-size:14px}.original-batch-item small{display:block;color:#7b8da2;font-size:12px;margin-top:7px}.original-batch-item button{margin-top:10px;font-size:12px}.detail-prices{display:grid;grid-template-columns:1fr 1fr;gap:12px;margin-top:20px}.detail-prices>div{padding:14px;background:#f4f7fb;border-radius:8px;display:flex;flex-direction:column;gap:5px}.detail-prices small{font-size:12px;color:#7c8ea2}.detail-prices strong{font-size:25px}.drawer-body .detail-amount{font-size:22px;font-weight:700}.drawer-body .detail-amount.up{color:#ce423b}.drawer-body .detail-amount.down{color:#168878}.detail-amount span{font-size:15px;margin-left:12px}.drawer-body dt{font-size:12px;color:#8091a4;margin-top:16px}.drawer-body dd{margin:5px 0 0;font-size:13px}
@media(max-width:1150px){.overview-table{min-width:680px}.overview-table td{font-size:12px;padding:9px 7px}.overview-table th{padding:9px 7px;font-size:11px}.channel-cell b{font-size:12px}.after-cell strong,.amount-cell b{font-size:15px}.overview-table td:nth-child(3),.overview-table td:nth-child(4){white-space:nowrap}.overview-layout{grid-template-columns:205px minmax(0,1fr)}.overview-main{padding:14px;gap:8px}.overview-tabs{gap:5px;flex-wrap:wrap}.overview-tabs button{padding:7px;font-size:12px;flex:1 0 27%}.country-search{width:130px}.overview-header{padding:14px 18px}.batch-heading h3{font-size:18px}.unit{display:none}.overview-toolbar{gap:7px}.overview-toolbar label{font-size:11px}.overview-toolbar select{max-width:105px}.overview-footer span{display:none}.batch-sidebar{padding:14px 12px}}
@media(max-width:700px){.update-overview{width:calc(100vw - 12px);height:96dvh;overflow:auto;border-radius:9px}.overview-header{padding:12px;align-items:flex-start}.overview-header h2{font-size:20px}.overview-header h2 span{display:none}.overview-header p{font-size:11px}.header-actions{gap:6px}.header-actions .primary{font-size:11px;padding:7px}.overview-layout{display:flex;flex-direction:column;flex:none;min-height:auto}.batch-sidebar{max-height:260px;border-right:0;border-bottom:1px solid #e1e8f0;gap:7px;padding:10px 12px}.sidebar-title h3{font-size:15px}.batch-list{display:flex;gap:8px;overflow:auto;flex:none;padding:2px}.batch-list .batch-card{width:200px;flex-shrink:0;margin:0;padding:8px}.batch-card b{font-size:13px}.batch-pagination{display:flex;justify-content:space-between;align-items:center;gap:10px}.batch-pagination>span{margin:0}.sidebar-note{display:none}.overview-main{min-height:690px;padding:12px}.batch-heading{flex-wrap:wrap}.batch-heading h3{font-size:16px}.overview-stats{font-size:11px}.country-strip{align-items:flex-end}.country-search{width:120px}.country-chips button{min-width:68px}.overview-toolbar{flex-wrap:wrap}.overview-toolbar>input{flex-basis:100%;width:100%}.overview-table-scroll{height:330px;flex:none}.row-pagination{flex-wrap:wrap}.overview-footer{padding:10px 12px}.overview-header{flex-shrink:0}.detail-drawer{position:fixed;inset:0;width:100%;height:100dvh}.drawer-shade{position:fixed}.overview-tabs button{font-size:11px}}
</style>
