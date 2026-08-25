<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { deletePurchaseProduct, loadPurchaseDeletionCheck, loadPurchaseProduct, loadPurchaseProductPage, loadPurchaseStats, normalizePurchaseRecord, promotePurchaseProduct, purchaseDisplayName, purchaseFreightChoices, setPurchaseProductCatalogState, upsertPurchaseProducts, type PurchaseDeletionCheck, type PurchaseProductRecord } from '@/data/purchaseStore'
import { confirmPurchaseImport, previewPurchaseWorkbook, type ServerPurchaseImportPreview } from '@/services/purchaseImports'
import { cancelPurchaseImportJob, confirmPurchaseImportJob, createPurchaseImportJob, loadPurchaseImportJob, loadPurchaseImportJobs, loadPurchaseImportRows, purchaseImportErrorsUrl, retryPurchaseImportJob, rollbackPurchaseImportJob, uploadPurchaseImagePart, type PurchaseImportJob, type PurchaseImportRowView } from '@/services/purchaseAsyncImports'
import { didPurchaseImportDataChange, shouldPollPurchaseImportJobs } from '@/services/purchaseImportPolling'
import ImageMigrationPanel from './ImageMigrationPanel.vue'

const TEMPLATE_URL = '/templates/米莱诺采购产品标准导入模板-新版.xlsx'
const records = ref<PurchaseProductRecord[]>([])
const loading = ref(true)
const search = ref('')
const fileInput = ref<HTMLInputElement | null>(null)
const importPreview = ref<ServerPurchaseImportPreview | null>(null)
const lastImport = ref<ServerPurchaseImportPreview | null>(null)
const parsing = ref(false)
const savingImport = ref(false)
const importMode = ref<'formal' | 'pending_template'>('formal')
const detail = ref<PurchaseProductRecord | null>(null)
const editor = ref<PurchaseProductRecord | null>(null)
const editingOriginalSku = ref('')
const notice = ref('')
const previewImage = ref<{ src: string; title: string } | null>(null)
const showImageMigration = ref(false)
const currentPage = ref(1)
const pageSize = ref(10)
const totalRecords = ref(0)
const serverTotalPages = ref(1)
const purchaseStats = ref({ total:0, ready:0, pending:0, generatedSku:0 })
const asyncFileInput = ref<HTMLInputElement | null>(null)
const imagePartInput = ref<HTMLInputElement | null>(null)
const asyncUploading = ref(false)
const imageUploading = ref(false)
const showTaskCenter = ref(false)
const importJobs = ref<PurchaseImportJob[]>([])
const activeJob = ref<PurchaseImportJob | null>(null)
const activeRows = ref<PurchaseImportRowView[]>([])
const activeRowStatus = ref('error')
const imagePartNumber = ref(1)
const pendingJobAction = ref<'confirm'|'retry'|'cancel'|'rollback'|null>(null)
const deleteTarget = ref<PurchaseProductRecord | null>(null)
const deleteCheck = ref<PurchaseDeletionCheck | null>(null)
const deleteConfirmation = ref('')
const catalogAction = ref<{ record:PurchaseProductRecord;state:'ready'|'disabled' } | null>(null)
const productActionBusy = ref(false)
let jobPollTimer = 0

const filtered = computed(() => records.value)
const readyCount = computed(() => purchaseStats.value.ready)
const pendingCount = computed(() => purchaseStats.value.pending)
const generatedCount = computed(() => purchaseStats.value.generatedSku)
const tieredCount = computed(() => records.value.filter(item => item.priceTiers.length > 1).length)
const totalPages = computed(() => Math.max(1, serverTotalPages.value))
const pageStart = computed(() => totalRecords.value ? (currentPage.value - 1) * pageSize.value : 0)
const pageEnd = computed(() => Math.min(pageStart.value + records.value.length, totalRecords.value))
const pagedRecords = computed(() => records.value)
const deleteConfirmationMatches = computed(() => deleteTarget.value != null && deleteConfirmation.value.trim().toUpperCase() === deleteTarget.value.sku)
const visiblePages = computed<(number | 'ellipsis-start' | 'ellipsis-end')[]>(() => {
  const total = totalPages.value
  if (total <= 7) return Array.from({ length: total }, (_, index) => index + 1)
  const start = Math.max(2, currentPage.value - 2)
  const end = Math.min(total - 1, currentPage.value + 2)
  const pages: (number | 'ellipsis-start' | 'ellipsis-end')[] = [1]
  if (start > 2) pages.push('ellipsis-start')
  for (let page = start; page <= end; page += 1) pages.push(page)
  if (end < total - 1) pages.push('ellipsis-end')
  pages.push(total)
  return pages
})

let searchTimer=0
watch(search, () => { currentPage.value = 1;window.clearTimeout(searchTimer);searchTimer=window.setTimeout(reload,250) })
watch(pageSize, () => { currentPage.value = 1;reload() })
watch(totalPages, total => {
  if (currentPage.value > total) currentPage.value = total
})

function goToPage(page: number) {
  currentPage.value = Math.min(Math.max(page, 1), totalPages.value)
  reload()
}
function resetFilters() {
  search.value = ''
  currentPage.value = 1
}

async function reload() {
  loading.value = true
  try { const [page,stats]=await Promise.all([loadPurchaseProductPage(search.value.trim(),currentPage.value-1,pageSize.value),loadPurchaseStats()]);records.value=page.items;totalRecords.value=page.total;serverTotalPages.value=page.totalPages;purchaseStats.value=stats }
  catch (error) { toast(error instanceof Error ? error.message : '采购数据读取失败') }
  finally { loading.value = false }
}
onMounted(() => { reload() })
onUnmounted(() => { stopJobPolling();window.clearTimeout(searchTimer) })

watch(showTaskCenter, open => {
  if (open) {
    void refreshImportJobs()
    startJobPolling()
  } else stopJobPolling()
})

const jobStatusLabel:Record<string,string>={queued:'排队中',parsing:'解析校验中',ready:'待确认', 'import-queued':'等待入库',importing:'批量入库中',completed:'已完成','completed-with-errors':'部分完成',failed:'失败',cancelled:'已取消','rollback-queued':'等待回滚','rolling-back':'回滚中','rolled-back':'已回滚'}
function importJobStatuses(){return [activeJob.value?.status,...importJobs.value.map(job=>job.status)]}
function startJobPolling(){stopJobPolling();jobPollTimer=window.setInterval(()=>{if(shouldPollPurchaseImportJobs(showTaskCenter.value,importJobStatuses()))void refreshImportJobs()},2000)}
function stopJobPolling(){window.clearInterval(jobPollTimer);jobPollTimer=0}
async function refreshImportJobs(){try{const previousStatus=activeJob.value?.status;const page=await loadPurchaseImportJobs();importJobs.value=page.content;if(activeJob.value){const fresh=await loadPurchaseImportJob(activeJob.value.id);activeJob.value=fresh;if(didPurchaseImportDataChange(previousStatus,fresh.status))await reload()}}catch{/* 页面主数据错误提示已独立处理，轮询失败等待下一次重试 */}}
async function selectImportJob(job:PurchaseImportJob){activeJob.value=await loadPurchaseImportJob(job.id);await refreshJobRows()}
async function refreshJobRows(){if(!activeJob.value)return;const page=await loadPurchaseImportRows(activeJob.value.id,activeRowStatus.value,0,50);activeRows.value=page.content}
async function chooseAsyncWorkbook(event:Event){const input=event.target as HTMLInputElement;const file=input.files?.[0];if(!file)return;asyncUploading.value=true;try{activeJob.value=await createPurchaseImportJob(file);showTaskCenter.value=true;await refreshImportJobs();toast('大批量导入任务已创建，正在后台解析')}catch(error){toast(error instanceof Error?error.message:'导入任务创建失败')}finally{asyncUploading.value=false;input.value=''}}
async function chooseImagePart(event:Event){const input=event.target as HTMLInputElement;const file=input.files?.[0];if(!file||!activeJob.value)return;imageUploading.value=true;try{activeJob.value=await uploadPurchaseImagePart(activeJob.value.id,imagePartNumber.value,file);imagePartNumber.value+=1;toast('图片分包上传成功')}catch(error){toast(error instanceof Error?error.message:'图片分包上传失败')}finally{imageUploading.value=false;input.value=''}}
async function executeJobAction(){if(!activeJob.value||!pendingJobAction.value)return;const id=activeJob.value.id;try{if(pendingJobAction.value==='confirm')await confirmPurchaseImportJob(id);else if(pendingJobAction.value==='retry')await retryPurchaseImportJob(id);else if(pendingJobAction.value==='cancel')await cancelPurchaseImportJob(id);else await rollbackPurchaseImportJob(id);await refreshImportJobs();toast('任务操作已提交')}catch(error){toast(error instanceof Error?error.message:'任务操作失败')}finally{pendingJobAction.value=null}}

let toastTimer = 0
function toast(message: string) {
  notice.value = message
  window.clearTimeout(toastTimer)
  toastTimer = window.setTimeout(() => { notice.value = '' }, 3200)
}

async function chooseWorkbook(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  parsing.value = true
  try { importPreview.value = await previewPurchaseWorkbook(file) }
  catch (error) { toast(error instanceof Error ? error.message : 'Excel 解析失败') }
  finally { parsing.value = false; input.value = '' }
}

async function confirmImport() {
  if (!importPreview.value || !importPreview.value.canConfirm) return
  savingImport.value = true
  try {
    await confirmPurchaseImport(importPreview.value.jobId, importMode.value)
    lastImport.value = importPreview.value
    importPreview.value = null
    await reload()
    toast(`导入完成：新增 ${lastImport.value.added} 条，覆盖 ${lastImport.value.updated} 条`)
  } catch (error) { toast(error instanceof Error ? error.message : '采购数据保存失败') }
  finally { savingImport.value = false }
}

function emptyRecord() {
  return normalizePurchaseRecord({ sourceRow: Date.now(), sku: '', skuOrigin: 'manual', catalogState: 'ready', stockStatus: '待确认', quotationDate: new Date().toISOString().slice(0, 10), importWarnings: [] })
}
function openEditor(record?: PurchaseProductRecord) {
  editingOriginalSku.value = record?.sku || ''
  editor.value = record ? normalizePurchaseRecord(JSON.parse(JSON.stringify(record)) as PurchaseProductRecord) : emptyRecord()
  detail.value = null
}
function requestCatalogState(record:PurchaseProductRecord,state:'ready'|'disabled'){detail.value=null;catalogAction.value={record,state}}
async function executeCatalogState(){
  if(!catalogAction.value||productActionBusy.value)return
  const {record,state}=catalogAction.value;productActionBusy.value=true
  try{await setPurchaseProductCatalogState(record.sku,state,record._version??-1);catalogAction.value=null;await reload();toast(state==='disabled'?`${record.sku} 已停用`:`${record.sku} 已重新启用`)}
  catch(error){toast(error instanceof Error?error.message:'采购资料状态修改失败')}
  finally{productActionBusy.value=false}
}
async function requestDelete(record:PurchaseProductRecord){
  detail.value=null;deleteTarget.value=record;deleteCheck.value=null;deleteConfirmation.value='';productActionBusy.value=true
  try{deleteCheck.value=await loadPurchaseDeletionCheck(record.sku)}catch(error){deleteTarget.value=null;toast(error instanceof Error?error.message:'删除检查失败')}
  finally{productActionBusy.value=false}
}
function closeDelete(force=false){if(productActionBusy.value&&!force)return;deleteTarget.value=null;deleteCheck.value=null;deleteConfirmation.value=''}
async function executeDelete(){
  if(!deleteTarget.value||!deleteCheck.value?.canDelete||!deleteConfirmationMatches.value||productActionBusy.value)return
  const target=deleteTarget.value;productActionBusy.value=true
  try{await deletePurchaseProduct(target.sku,deleteCheck.value.version);if(records.value.length===1&&currentPage.value>1)currentPage.value-=1;closeDelete(true);await reload();toast(`${target.sku} 已彻底删除`)}
  catch(error){toast(error instanceof Error?error.message:'采购资料删除失败')}
  finally{productActionBusy.value=false}
}
async function disableBlockedDelete(){if(!deleteTarget.value||deleteTarget.value.catalogState==='disabled')return;const target=deleteTarget.value;closeDelete();requestCatalogState(target,'disabled')}
async function saveEditor() {
  if (!editor.value) return
  const sku = editor.value.sku.trim().toUpperCase().replace(/\s+/g, '')
  if (!sku) { toast('请填写 SKU'); return }
  if (records.value.some(item => item.sku === sku && item.sku !== editingOriginalSku.value)) { toast(`SKU ${sku} 已存在`); return }
  const wasGenerated = editor.value.skuOrigin === 'system'
  const pendingTemplate = editor.value.catalogState === 'pending_template' && Boolean(editingOriginalSku.value)
  if (pendingTemplate && sku !== editingOriginalSku.value) { toast('模板SKU转正式请使用“确认转正式”按钮'); return }
  const record = normalizePurchaseRecord({ ...editor.value, sku, skuOrigin: wasGenerated && sku.startsWith('AUTO-') ? 'system' : 'manual' })
  try {
    if (editingOriginalSku.value && editingOriginalSku.value !== sku) await deletePurchaseProduct(editingOriginalSku.value, editor.value._version ?? -1)
    await upsertPurchaseProducts([record])
    editor.value = null
    await reload()
    toast(`${sku} 已保存${record.skuOrigin === 'system' ? '，请尽快修改系统生成 SKU' : ''}`)
  } catch (error) { toast(error instanceof Error ? error.message : '采购资料保存失败') }
}

async function promoteEditor() {
  if (!editor.value || editor.value.catalogState !== 'pending_template' || !editingOriginalSku.value) return
  const targetSku = editor.value.sku.trim().toUpperCase().replace(/\s+/g, '')
  if (!targetSku || /^(TESTP|TEST|DEMO|MOCK)/i.test(targetSku) || targetSku.startsWith('AUTO-')) { toast('请先把SKU修改为非 TEST/DEMO/AUTO 的真实业务SKU'); return }
  try {
    const pending = normalizePurchaseRecord({ ...editor.value, sku: editingOriginalSku.value, catalogState: 'pending_template' })
    await upsertPurchaseProducts([pending])
    const refreshed = await loadPurchaseProduct(editingOriginalSku.value)
    await promotePurchaseProduct(editingOriginalSku.value, targetSku, refreshed._version ?? -1)
    editor.value = null
    await reload()
    toast(`${targetSku} 已确认转为正式商品`)
  } catch (error) { toast(error instanceof Error ? error.message : '模板商品转正式失败') }
}

function handleImage(event: Event, field: 'productImage' | 'physicalImage') {
  const file = (event.target as HTMLInputElement).files?.[0]
  if (!file || !editor.value) return
  const reader = new FileReader()
  reader.onload = () => { if (editor.value) editor.value[field] = String(reader.result || '') }
  reader.readAsDataURL(file)
}
function showImage(src: string, title: string) { if (src) previewImage.value = { src, title } }
function value(value: unknown, suffix = '') { return value === '' || value == null ? '暂无数据' : `${value}${suffix}` }
function money(amount: number | null) { return amount == null ? '暂无数据' : `¥${amount.toFixed(2)}` }
function validUrl(link: string) { try { return Boolean(new URL(link)) } catch { return false } }
function unitFreight(record: PurchaseProductRecord, quantity: number) {
  const choice = purchaseFreightChoices(record).find(item => item.quantity === quantity)
  return choice ? `合计 ¥${choice.totalFreightCny.toFixed(2)} · 单件 ¥${choice.unitFreightCny.toFixed(2)}` : '暂无数据'
}

const detailFields = computed(() => detail.value ? [
  ['SKU*', detail.value.sku], ['类别*', detail.value.category], ['产品图片（嵌入本格）', detail.value.productImage ? '已上传' : '暂无数据'], ['实物图（嵌入本格）', detail.value.physicalImage ? '已上传' : '暂无数据'],
  ['报价人*', detail.value.quotationOwner], ['报价日期*', detail.value.quotationDate], ['尺码', detail.value.size], ['颜色', detail.value.color],
  ['克重(g)*', value(detail.value.weightG, ' g')], ['长(cm)*', value(detail.value.lengthCm, ' cm')], ['宽(cm)*', value(detail.value.widthCm, ' cm')], ['高(cm)*', value(detail.value.heightCm, ' cm')],
  ['起订量(件)*', value(detail.value.minOrderQty, ' 件')], ['基准采购单价(CNY/件)*', money(detail.value.purchasePriceCny)], ['阶梯价2起订量', value(detail.value.tier2MinQty, ' 件')], ['阶梯价2(CNY/件)', money(detail.value.tier2PriceCny)],
  ['阶梯价3起订量', value(detail.value.tier3MinQty, ' 件')], ['阶梯价3(CNY/件)', money(detail.value.tier3PriceCny)], ['1件总运费(CNY)', money(detail.value.singleFreightCny)], ['10件总运费(CNY)', money(detail.value.freight10Cny)],
  ['100件总运费(CNY)', money(detail.value.freight100Cny)], ['是否包邮', detail.value.freeShipping], ['含票价(CNY/件)', money(detail.value.taxIncludedPriceCny)], ['票类型', detail.value.invoiceType],
  ['是否有货*', detail.value.stockStatus], ['备注', detail.value.notes], ['工厂信息', detail.value.factoryInfo], ['货源链接1', detail.value.sourceLink1], ['货源链接2', detail.value.sourceLink2], ['货源链接3', detail.value.sourceLink3],
  ['相似货源', detail.value.similarSource], ['审核备注', detail.value.auditNotes],
] : [])
</script>

<template>
  <section class="purchase-heading">
    <div><p>PURCHASE DATA CENTER</p><h1>采购资料维护</h1><span>按标准 Excel 模板批量导入并维护采购商品资料。</span></div>
    <div class="heading-actions">
      <a :href="TEMPLATE_URL" download>下载标准模板</a>
      <button class="outline" :disabled="asyncUploading" @click="asyncFileInput?.click()">{{ asyncUploading ? '上传中…' : '大批量导入' }}</button>
      <button class="outline" @click="showTaskCenter=true">导入任务</button>
      <button class="outline" :disabled="parsing" @click="fileInput?.click()">{{ parsing ? '解析中…' : '小文件导入' }}</button>
      <button class="outline" @click="showImageMigration=true">图片迁移</button>
      <button class="primary" @click="openEditor()">＋ 新增采购资料</button>
      <input ref="fileInput" hidden type="file" accept=".xlsx" @change="chooseWorkbook">
      <input ref="asyncFileInput" hidden type="file" accept=".xlsx" @change="chooseAsyncWorkbook">
    </div>
  </section>

  <section class="stats">
    <article><small>采购资料</small><b>{{ purchaseStats.total }}</b><span>报价服务器数据库</span></article>
    <article><small>可参与报价</small><b>{{ readyCount }}</b><span>关键成本资料完整</span></article>
    <article><small>待补充资料</small><b class="orange">{{ pendingCount }}</b><span>空值显示“暂无数据”</span></article>
    <article><small>系统生成 SKU</small><b class="orange">{{ generatedCount }}</b><span>修改后才可参与报价</span></article>
  </section>

  <section class="toolbar">
    <label>⌕ <input v-model="search" placeholder="搜索 SKU、类别、报价人、尺码、颜色或工厂"></label>
    <button @click="resetFilters">重置筛选</button>
    <button v-if="lastImport" class="result-link" @click="importPreview=lastImport">查看最近导入结果</button>
    <span>共 {{ totalRecords }} 条 · 当前页 {{ tieredCount }} 条含阶梯价<span v-if="totalRecords"> · 当前 {{ pageStart + 1 }}–{{ pageEnd }} 条</span></span>
  </section>

  <section class="table-card">
    <div v-if="loading" class="empty">正在读取采购数据…</div>
    <div v-else-if="!filtered.length" class="empty"><b>暂无采购数据</b><span>请下载标准模板填写后，通过“Excel 导入”添加采购资料。</span></div>
    <table v-else>
      <thead><tr><th>类别 / SKU</th><th>采购阶梯价格</th><th>重量与尺寸</th><th>国内运费</th><th>尺码 / 颜色</th><th>资料状态</th><th>操作</th></tr></thead>
      <tbody><tr v-for="record in pagedRecords" :key="record.sku">
        <td><div class="product"><button v-if="record.productImage" @click="showImage(record.productImage,`${record.sku} 产品图片`)"><img :src="record.productImage" :alt="record.sku"></button><i v-else>{{ record.category.slice(0,1) || '?' }}</i><span><b>{{ purchaseDisplayName(record) }}</b><small>{{ record.sku }}</small><em v-if="record.skuOrigin==='system'">系统生成，请修改</em><small>报价人：{{ record.quotationOwner || '暂无数据' }}</small></span></div></td>
        <td><div v-if="record.priceTiers.length" class="purchase-tiers"><span v-for="(tier,index) in record.priceTiers" :key="`${tier.minQty}-${tier.maxQty}`" :class="{ base:index===0 }"><small>第{{ index+1 }}档 · {{ tier.maxQty == null ? `${tier.minQty}件起` : `${tier.minQty}–${tier.maxQty}件` }}</small><b>¥{{ tier.unitPriceCny.toFixed(2) }}/件</b></span></div><span v-else class="no-data">暂无采购价格</span></td>
        <td><b>{{ value(record.weightG,' g') }}</b><small>{{ value(record.lengthCm) }} × {{ value(record.widthCm) }} × {{ value(record.heightCm) }} cm</small><small>起订 {{ value(record.minOrderQty,' 件') }}</small></td>
        <td><small>1件 {{ unitFreight(record,1) }}</small><small>10件 {{ unitFreight(record,10) }}</small><small>100件 {{ unitFreight(record,100) }}</small></td>
        <td><b>{{ record.size || '暂无数据' }}</b><small>{{ record.color || '暂无数据' }}</small><small>实物图：{{ record.physicalImage ? '已上传' : '暂无数据' }}</small></td>
        <td><em :class="{ ready:record.quoteReady, warn:!record.quoteReady }">{{ record.status }}</em><small>库存：{{ record.stockStatus || '暂无数据' }}</small></td>
        <td class="actions"><button @click="detail=record">查看详情</button><button @click="openEditor(record)">编辑</button><button v-if="record.catalogState!=='disabled'" @click="requestCatalogState(record,'disabled')">停用</button><button v-else @click="requestCatalogState(record,'ready')">启用</button><button class="danger-link" @click="requestDelete(record)">删除</button></td>
      </tr></tbody>
    </table>
    <footer v-if="totalRecords" class="pagination" aria-label="采购资料分页">
      <span>第 {{ currentPage }} / {{ totalPages }} 页</span>
      <nav>
        <button :disabled="currentPage===1" @click="goToPage(currentPage-1)">上一页</button>
        <template v-for="page in visiblePages" :key="page">
          <i v-if="typeof page !== 'number'">…</i>
          <button v-else :class="{ active:page===currentPage }" :aria-current="page===currentPage ? 'page' : undefined" @click="goToPage(page)">{{ page }}</button>
        </template>
        <button :disabled="currentPage===totalPages" @click="goToPage(currentPage+1)">下一页</button>
      </nav>
      <label>每页<select v-model.number="pageSize"><option :value="10">10 条</option><option :value="20">20 条</option><option :value="50">50 条</option></select></label>
    </footer>
  </section>

  <div v-if="importPreview" class="mask" @click.self="importPreview=null"><section class="modal import-modal">
    <button class="close" @click="importPreview=null">×</button><small>EXCEL IMPORT PREVIEW</small><h2>采购数据导入预览</h2><p>{{ importPreview.fileName }}</p>
    <div class="import-stats"><span><b>{{ importPreview.totalRows }}</b>读取行</span><span><b>{{ importPreview.added }}</b>新增</span><span><b>{{ importPreview.updated }}</b>覆盖</span><span><b>{{ importPreview.generatedSku }}</b>临时SKU</span><span><b>{{ importPreview.productImages }}</b>产品图</span><span><b>{{ importPreview.physicalImages }}</b>实物图</span></div>
    <label class="import-mode">导入用途<select v-model="importMode"><option value="formal">正式采购数据</option><option value="pending_template">模板待补全目录（不可报价）</option></select><small v-if="importMode==='pending_template'">保留模板参考值，但所有SKU将被服务端锁定为不可报价。</small></label>
    <div class="issues"><b>导入提示（阻断 {{ importPreview.errorCount }} · 警告 {{ importPreview.warningCount }}）</b><p v-if="!importPreview.issues.length">模板检查通过，没有发现异常。</p><p v-else-if="!importPreview.canConfirm" class="blocking">存在阻断错误，当前文件不能确认导入。请修正 Excel 后重新上传。</p><article v-for="(issue,index) in importPreview.issues" :key="`${issue.row}-${index}`" :class="issue.level"><em>第 {{ issue.row }} 行 · {{ issue.field }}</em><span>{{ issue.message }}</span></article></div>
    <footer><button @click="importPreview=null">取消</button><button class="primary" :disabled="savingImport || !importPreview.records.length || !importPreview.canConfirm" @click="confirmImport">{{ savingImport ? '正在导入…' : importPreview.canConfirm ? `确认导入 ${importPreview.records.length} 条` : '存在阻断错误，无法导入' }}</button></footer>
  </section></div>

  <div v-if="showTaskCenter" class="mask" @click.self="showTaskCenter=false"><section class="modal task-center-modal">
    <button class="close" @click="showTaskCenter=false">×</button><small>ASYNC PURCHASE IMPORT</small><h2>大批量导入任务中心</h2><p>Excel 后台流式解析，合格数据一次确认后分批入库。</p>
    <div class="task-center-grid">
      <aside class="job-list"><button v-for="job in importJobs" :key="job.id" :class="{ active:activeJob?.id===job.id }" @click="selectImportJob(job)"><b>{{ job.sourceName }}</b><span>{{ jobStatusLabel[job.status] || job.status }} · {{ job.progressPercent }}%</span><small>{{ new Date(job.createdAt).toLocaleString() }}</small></button><p v-if="!importJobs.length">暂无大批量导入任务</p></aside>
      <main v-if="activeJob" class="job-detail">
        <header><div><b>{{ activeJob.sourceName }}</b><span :class="['job-status',activeJob.status]">{{ jobStatusLabel[activeJob.status] || activeJob.status }}</span></div><strong>{{ activeJob.progressPercent }}%</strong></header>
        <div class="progress"><i :style="{ width:`${activeJob.progressPercent}%` }"></i></div>
        <div class="job-stats"><span><b>{{ activeJob.totalRows }}</b>总行数</span><span><b>{{ activeJob.validRows }}</b>合格</span><span><b>{{ activeJob.errorRows }}</b>错误</span><span><b>{{ activeJob.addedRows }}</b>新增</span><span><b>{{ activeJob.updatedRows }}</b>覆盖</span><span><b>{{ activeJob.conflictRows }}</b>冲突</span></div>
        <p v-if="activeJob.error" class="job-error">{{ activeJob.error }}</p>
        <div v-if="['queued','parsing','ready','failed'].includes(activeJob.status)" class="image-part-upload"><label>图片ZIP分包编号 <input v-model.number="imagePartNumber" type="number" min="1"></label><button :disabled="imageUploading" @click="imagePartInput?.click()">{{ imageUploading?'上传中…':'上传图片分包' }}</button><small>命名：SKU-product.jpg / SKU-physical.jpg；已上传 {{ activeJob.imageParts }} 包</small><input ref="imagePartInput" hidden type="file" accept=".zip" @change="chooseImagePart"></div>
        <div v-if="activeJob.imagePartDetails?.length" class="image-part-list"><button v-for="part in activeJob.imagePartDetails" :key="part.partNumber" :class="part.status" :title="part.error || part.fileName" @click="part.status==='failed' && (imagePartNumber=part.partNumber)"><b>分包 {{ part.partNumber }}</b><span>{{ part.status==='completed'?'已完成':part.status==='failed'?'失败，点击选择替换':'待处理' }}</span><small v-if="part.error">{{ part.error }}</small></button></div>
        <div class="row-filter"><button :class="{active:activeRowStatus==='error'}" @click="activeRowStatus='error';refreshJobRows()">错误行</button><button :class="{active:activeRowStatus==='conflict'}" @click="activeRowStatus='conflict';refreshJobRows()">冲突行</button><a :href="purchaseImportErrorsUrl(activeJob.id)" download>下载错误清单</a></div>
        <div class="job-rows"><p v-if="!activeRows.length">当前没有对应记录</p><article v-for="row in activeRows" :key="row.sourceRow"><b>第 {{ row.sourceRow }} 行 · {{ row.sku }}</b><span>{{ row.error || '暂无错误说明' }}</span></article></div>
        <div v-if="pendingJobAction" class="action-confirm"><span>确认执行“{{ pendingJobAction==='confirm'?'批次入库':pendingJobAction==='rollback'?'整批回滚':pendingJobAction==='retry'?'重试任务':'取消任务' }}”吗？</span><button @click="pendingJobAction=null">返回</button><button class="primary" @click="executeJobAction">确认执行</button></div>
        <footer v-else><button v-if="['queued','parsing','ready'].includes(activeJob.status)" @click="pendingJobAction='cancel'">取消任务</button><button v-if="activeJob.status==='failed'" @click="pendingJobAction='retry'">重试</button><button v-if="['completed','completed-with-errors'].includes(activeJob.status)" @click="pendingJobAction='rollback'">整批回滚</button><button v-if="activeJob.status==='ready'" class="primary" :disabled="!activeJob.validRows" @click="pendingJobAction='confirm'">确认入库 {{ activeJob.validRows }} 条</button></footer>
      </main>
      <main v-else class="job-detail empty">请选择一个导入任务</main>
    </div>
  </section></div>

  <div v-if="detail" class="mask" @click.self="detail=null"><section class="modal detail-modal">
    <button class="close" @click="detail=null">×</button><small>PURCHASE SOURCE DETAIL</small><h2>采购资料详情</h2><p>按标准模板 32 列顺序展示</p>
    <div class="detail-images"><button v-if="detail.productImage" @click="showImage(detail.productImage,`${detail.sku} 产品图片`)"><img :src="detail.productImage"><span>产品图片</span></button><button v-if="detail.physicalImage" @click="showImage(detail.physicalImage,`${detail.sku} 实物图`)"><img :src="detail.physicalImage"><span>实物图</span></button><i v-if="!detail.productImage && !detail.physicalImage">暂无图片</i></div>
    <div class="detail-grid"><div v-for="field in detailFields" :key="field[0]" :class="{ wide:['备注','工厂信息','审核备注'].includes(String(field[0])) }"><small>{{ field[0] }}</small><a v-if="String(field[0]).includes('货源') && validUrl(String(field[1]))" :href="String(field[1])" target="_blank" rel="noopener">打开链接</a><p v-else>{{ field[1] || '暂无数据' }}</p></div></div>
    <footer><button @click="detail=null">关闭</button><button class="danger-link" @click="requestDelete(detail)">删除</button><button v-if="detail.catalogState!=='disabled'" @click="requestCatalogState(detail,'disabled')">停用</button><button v-else @click="requestCatalogState(detail,'ready')">启用</button><button class="primary" @click="openEditor(detail)">编辑资料</button></footer>
  </section></div>

  <div v-if="catalogAction" class="mask" @click.self="!productActionBusy && (catalogAction=null)"><section class="modal product-action-modal">
    <button class="close" :disabled="productActionBusy" @click="catalogAction=null">×</button><small>PURCHASE CATALOG STATE</small><h2>{{ catalogAction.state==='disabled'?'停用采购资料':'重新启用采购资料' }}</h2>
    <p><b>{{ catalogAction.record.sku }}</b></p><div class="action-warning">{{ catalogAction.state==='disabled'?'停用后保留历史资料和图片，但不能参与新报价。':'启用后将重新按资料完整度决定是否可参与报价。' }}</div>
    <footer><button :disabled="productActionBusy" @click="catalogAction=null">取消</button><button class="primary" :disabled="productActionBusy" @click="executeCatalogState">{{ productActionBusy?'处理中…':catalogAction.state==='disabled'?'确认停用':'确认启用' }}</button></footer>
  </section></div>

  <div v-if="deleteTarget" class="mask" @click.self="closeDelete()"><section class="modal product-action-modal">
    <button class="close" :disabled="productActionBusy" @click="closeDelete()">×</button><small>PURCHASE SAFE DELETE</small><h2>删除采购资料</h2><p><b>{{ deleteTarget.sku }}</b></p>
    <div v-if="!deleteCheck" class="delete-loading">正在检查供应商、报价、草稿、模板和导入批次引用…</div>
    <template v-else-if="!deleteCheck.canDelete">
      <div class="action-warning danger">该商品存在业务引用，不能彻底删除，只能停用。</div>
      <div class="reference-grid"><span><b>{{ deleteCheck.supplierLinks }}</b>供应商关联</span><span><b>{{ deleteCheck.quotationRecords }}</b>报价记录</span><span><b>{{ deleteCheck.drafts }}</b>报价草稿</span><span><b>{{ deleteCheck.templates }}</b>报价模板</span><span><b>{{ deleteCheck.importBatches }}</b>未回滚导入批次</span><span><b>{{ deleteCheck.imageCount }}</b>图片关系</span></div>
      <p v-if="deleteCheck.importBatches" class="rollback-hint">该商品来自仍可回滚的导入批次，请从“导入任务”执行整批回滚。</p>
    </template>
    <template v-else>
      <div class="action-warning danger">此操作不可恢复，将删除商品及 {{ deleteCheck.imageCount }} 个图片关系；无其他引用的图片会进入清理队列。</div>
      <label class="delete-confirmation">请输入完整 SKU <b>{{ deleteTarget.sku }}</b> 以确认<input v-model="deleteConfirmation" autocomplete="off" :placeholder="deleteTarget.sku"></label>
    </template>
    <footer><button :disabled="productActionBusy" @click="closeDelete()">取消</button><button v-if="deleteCheck&&!deleteCheck.canDelete&&deleteTarget.catalogState!=='disabled'" :disabled="productActionBusy" @click="disableBlockedDelete">停用该商品</button><button v-if="deleteCheck?.canDelete" class="danger-button" :disabled="productActionBusy||!deleteConfirmationMatches" @click="executeDelete">{{ productActionBusy?'删除中…':'确认彻底删除' }}</button></footer>
  </section></div>

  <div v-if="editor" class="mask"><section class="modal editor-modal">
    <button class="close" @click="editor=null">×</button><small>PURCHASE DATA EDITOR</small><h2>{{ editingOriginalSku ? '编辑采购资料' : '新增采购资料' }}</h2><p>字段顺序与标准 Excel 模板一致；空字段保存后显示“暂无数据”。</p>
    <div v-if="editor.skuOrigin==='system'" class="generated-warning">系统生成 SKU 必须修改成真实 SKU 后，才可以参与报价。</div>
    <div v-else-if="editor.catalogState==='pending_template'" class="generated-warning">当前是模板待补全目录。保存不会解除锁定；改成真实业务SKU并点击“确认转正式”后才可参与报价。</div>
    <div class="form-grid">
      <label>1. SKU*<input v-model="editor.sku" :class="{ alert:editor.skuOrigin==='system' }"></label><label>2. 类别*<input v-model="editor.category"></label>
      <label class="wide">3. 产品图片（嵌入本格）<div class="upload"><img v-if="editor.productImage" :src="editor.productImage"><input type="file" accept="image/*" @change="handleImage($event,'productImage')"><button v-if="editor.productImage" @click.prevent="editor.productImage=''">移除</button></div></label>
      <label class="wide">4. 实物图（嵌入本格）<div class="upload"><img v-if="editor.physicalImage" :src="editor.physicalImage"><input type="file" accept="image/*" @change="handleImage($event,'physicalImage')"><button v-if="editor.physicalImage" @click.prevent="editor.physicalImage=''">移除</button></div></label>
      <label>5. 报价人*<input v-model="editor.quotationOwner"></label><label>6. 报价日期*<input v-model="editor.quotationDate" type="date"></label>
      <label>7. 尺码<input v-model="editor.size"></label><label>8. 颜色<input v-model="editor.color"></label>
      <label>9. 克重(g)*<input v-model.number="editor.weightG" type="number" min="0" step="0.01"></label><label>10. 长(cm)*<input v-model.number="editor.lengthCm" type="number" min="0" step="0.01"></label>
      <label>11. 宽(cm)*<input v-model.number="editor.widthCm" type="number" min="0" step="0.01"></label><label>12. 高(cm)*<input v-model.number="editor.heightCm" type="number" min="0" step="0.01"></label>
      <label>13. 起订量(件)*<input v-model.number="editor.minOrderQty" type="number" min="1" step="1"></label><label>14. 基准采购单价(CNY/件)*<input v-model.number="editor.purchasePriceCny" type="number" min="0" step="0.01"></label>
      <label>15. 阶梯价2起订量<input v-model.number="editor.tier2MinQty" type="number" min="1" step="1"></label><label>16. 阶梯价2(CNY/件)<input v-model.number="editor.tier2PriceCny" type="number" min="0" step="0.01"></label>
      <label>17. 阶梯价3起订量<input v-model.number="editor.tier3MinQty" type="number" min="1" step="1"></label><label>18. 阶梯价3(CNY/件)<input v-model.number="editor.tier3PriceCny" type="number" min="0" step="0.01"></label>
      <label>19. 1件总运费(CNY)<input v-model.number="editor.singleFreightCny" type="number" min="0" step="0.01"></label><label>20. 10件总运费(CNY)<input v-model.number="editor.freight10Cny" type="number" min="0" step="0.01"></label>
      <label>21. 100件总运费(CNY)<input v-model.number="editor.freight100Cny" type="number" min="0" step="0.01"></label><label>22. 是否包邮<select v-model="editor.freeShipping"><option value="">暂无数据</option><option>是</option><option>否</option></select></label>
      <label>23. 含票价(CNY/件)<input v-model.number="editor.taxIncludedPriceCny" type="number" min="0" step="0.01"></label><label>24. 票类型<select v-model="editor.invoiceType"><option value="">暂无数据</option><option>普票1%</option><option>普票3%</option><option>普票6%</option><option>专票13%</option><option>增值税专用发票</option><option>增值税普通发票</option><option>收据</option><option>不开票</option></select></label>
      <label>25. 是否有货*<select v-model="editor.stockStatus"><option value="">暂无数据</option><option>有货</option><option>无货</option><option>待确认</option></select></label><label class="wide">26. 备注<textarea v-model="editor.notes"></textarea></label>
      <label class="wide">27. 工厂信息<textarea v-model="editor.factoryInfo"></textarea></label><label>28. 货源链接1<input v-model="editor.sourceLink1"></label><label>29. 货源链接2<input v-model="editor.sourceLink2"></label>
      <label>30. 货源链接3<input v-model="editor.sourceLink3"></label><label>31. 相似货源<input v-model="editor.similarSource"></label><label class="wide">32. 审核备注<textarea v-model="editor.auditNotes"></textarea></label>
    </div>
    <footer><button @click="editor=null">取消</button><button @click="saveEditor">保存资料</button><button v-if="editor.catalogState==='pending_template'" class="primary" @click="promoteEditor">确认转正式</button></footer>
  </section></div>

  <div v-if="previewImage" class="image-preview" @click.self="previewImage=null"><button @click="previewImage=null">×</button><figure><img :src="previewImage.src"><figcaption>{{ previewImage.title }}</figcaption></figure></div>
  <div v-if="showImageMigration" class="mask" @click.self="showImageMigration=false"><ImageMigrationPanel @close="showImageMigration=false" /></div>
  <Transition name="toast"><div v-if="notice" class="toast">{{ notice }}</div></Transition>
</template>

<style scoped>
.purchase-heading{display:flex;align-items:end;justify-content:space-between;margin-bottom:22px}.purchase-heading p,.modal>small{margin:0 0 7px;color:#d87600;font-size:10px;font-weight:900;letter-spacing:.18em}.purchase-heading h1{margin:0 0 8px;font-size:29px}.purchase-heading span,.modal>p{color:#74808a;font-size:13px}.heading-actions{display:flex;gap:10px}.heading-actions a,.heading-actions button,.primary{box-sizing:border-box;height:40px;padding:0 16px;border:1px solid #ff9900;border-radius:8px;background:#fff;color:#a96000;font-size:12px;font-weight:900;text-decoration:none;line-height:38px}.heading-actions .primary,.primary{border:0;background:#ff9900;color:#17212b}.stats{display:grid;grid-template-columns:repeat(4,1fr);gap:16px;margin-bottom:16px}.stats article{display:grid;gap:5px;padding:16px 18px;border:1px solid #dfe5e9;border-radius:10px;background:#fff}.stats small,.stats span{color:#7c8790;font-size:11px}.stats b{font-size:25px}.orange{color:#d87600!important}.toolbar{display:flex;align-items:center;gap:10px;margin-bottom:12px;padding:12px 14px;border:1px solid #dfe5e9;border-radius:9px;background:#fff}.toolbar label{display:flex;align-items:center;width:360px;height:36px;gap:7px;border:1px solid #dce3e8;border-radius:6px;padding:0 10px;color:#77838d}.toolbar input{width:100%;border:0;outline:0}.toolbar button{height:36px;border:1px solid #dce3e8;border-radius:6px;background:#fff;color:#596771}.toolbar .result-link{border-color:#ffb452;color:#a96000}.toolbar>span{margin-left:auto;color:#78858f;font-size:11px}.table-card{overflow:auto;border:1px solid #dfe5e9;border-radius:10px;background:#fff}.table-card table{width:100%;min-width:1180px;border-collapse:collapse}.table-card th{padding:12px 14px;background:#f7f9fa;color:#71808a;font-size:10px;text-align:left}.table-card td{padding:13px 14px;border-top:1px solid #e8ecef;font-size:11px;vertical-align:middle}.table-card td>small,.table-card td>span,.table-card td>b{display:block;margin-top:4px}.product{display:flex;align-items:center;min-width:220px;gap:10px}.product>button,.product>i{display:grid;place-items:center;width:48px;height:48px;flex:0 0 48px;border:0;border-radius:8px;overflow:hidden;background:#fff1da;color:#a96000;font-style:normal;font-weight:900}.product img{width:100%;height:100%;object-fit:cover}.product span{display:grid;gap:3px}.product span small{color:#87939c}.product span em{width:max-content;padding:3px 6px;border-radius:9px;background:#fff0d8;color:#b56600;font-size:8px;font-style:normal}.price{color:#c56d00;font-size:15px}.table-card td>em{display:inline-block;padding:5px 8px;border-radius:10px;font-size:9px;font-style:normal}.table-card td>em.ready{background:#e8f7ee;color:#16824e}.table-card td>em.warn{background:#fff1de;color:#b46800}.actions{white-space:nowrap}.actions button{border:0;background:none;color:#a96000;font-weight:800}.empty{display:grid;justify-items:center;gap:7px;padding:70px;color:#7f8b94}.mask{position:fixed;z-index:60;inset:0;overflow:auto;padding:35px;background:rgba(17,24,39,.45);backdrop-filter:blur(3px)}.modal{position:relative;box-sizing:border-box;width:min(980px,96vw);margin:auto;padding:25px;border-radius:12px;background:#fff;box-shadow:0 24px 70px rgba(17,24,39,.25)}.modal h2{margin:4px 0 6px}.close{position:absolute;top:15px;right:16px;border:0;background:none;font-size:24px}.modal footer{display:flex;justify-content:flex-end;gap:10px;margin-top:20px;padding-top:16px;border-top:1px solid #e3e8eb}.modal footer button{height:40px;padding:0 18px;border:1px solid #dce3e8;border-radius:7px;background:#fff;font-weight:800}.modal footer .primary{border:0;background:#ff9900}.import-stats{display:grid;grid-template-columns:repeat(6,1fr);gap:8px;margin:18px 0}.import-stats span{display:grid;gap:4px;padding:12px;border-radius:8px;background:#f6f8f9;color:#74808a;font-size:10px}.import-stats b{color:#17212b;font-size:20px}.issues{max-height:330px;overflow:auto;border:1px solid #e3e8eb;border-radius:8px}.issues>b,.issues>p,.issues article{display:block;padding:10px 13px}.issues article{display:grid;grid-template-columns:150px 1fr;border-top:1px solid #edf0f2;font-size:10px}.issues article em{color:#b56700;font-style:normal;font-weight:800}.detail-images{display:flex;gap:12px;margin:16px 0}.detail-images button{display:grid;gap:5px;border:1px solid #e1e6e9;border-radius:8px;background:#fff;padding:8px}.detail-images img{width:110px;height:110px;object-fit:cover}.detail-images span{font-size:10px}.detail-grid{display:grid;grid-template-columns:repeat(3,1fr);max-height:53vh;overflow:auto;border:1px solid #e2e7ea;border-radius:8px}.detail-grid>div{min-height:48px;padding:10px 12px;border-right:1px solid #edf0f2;border-bottom:1px solid #edf0f2}.detail-grid .wide{grid-column:span 3}.detail-grid small{color:#85919a}.detail-grid p{margin:6px 0 0;word-break:break-word}.detail-grid a{display:block;margin-top:6px;color:#a96000}.editor-modal{width:min(1040px,96vw)}.form-grid{display:grid;grid-template-columns:1fr 1fr;gap:12px;max-height:62vh;overflow:auto;margin-top:17px;padding-right:7px}.form-grid label{display:grid;gap:6px;color:#4e5c66;font-size:11px}.form-grid .wide{grid-column:1/-1}.form-grid input,.form-grid select,.form-grid textarea{box-sizing:border-box;width:100%;min-height:38px;border:1px solid #dce3e8;border-radius:6px;padding:8px;font:inherit}.form-grid textarea{min-height:70px;resize:vertical}.form-grid input.alert{border-color:#f29a23;background:#fff9ef}.upload{display:flex;align-items:center;gap:10px;padding:8px;border:1px dashed #d9e0e5;border-radius:7px}.upload img{width:75px;height:75px;border-radius:6px;object-fit:cover}.upload button{border:0;background:none;color:#d24c43}.generated-warning{margin-top:14px;padding:10px 12px;border:1px solid #f6c779;border-radius:7px;background:#fff7e8;color:#a75c00;font-size:11px;font-weight:800}.image-preview{position:fixed;z-index:90;inset:0;display:grid;place-items:center;background:rgba(8,14,20,.82)}.image-preview>button{position:absolute;top:25px;right:30px;border:0;background:none;color:#fff;font-size:30px}.image-preview figure{margin:0;text-align:center}.image-preview img{max-width:82vw;max-height:78vh;border-radius:10px}.image-preview figcaption{margin-top:10px;color:#fff}.toast{position:fixed;right:25px;bottom:25px;z-index:100;padding:13px 18px;border-radius:8px;background:#17212b;color:#fff;font-size:12px}.toast-enter-active,.toast-leave-active{transition:.2s}.toast-enter-from,.toast-leave-to{opacity:0;transform:translateY(7px)}@media(max-width:850px){.purchase-heading{align-items:start;flex-direction:column;gap:15px}.heading-actions{flex-wrap:wrap}.stats{grid-template-columns:1fr 1fr}.toolbar{flex-wrap:wrap}.toolbar label{width:100%}.toolbar>span{margin-left:0}.import-stats{grid-template-columns:repeat(3,1fr)}.detail-grid{grid-template-columns:1fr 1fr}.detail-grid .wide{grid-column:span 2}.form-grid{grid-template-columns:1fr}.form-grid .wide{grid-column:auto}}
.purchase-tiers{display:grid;min-width:158px;gap:4px}.purchase-tiers span{display:flex;align-items:center;justify-content:space-between;gap:10px;padding:5px 7px;border-radius:6px;background:#f4f6f7}.purchase-tiers span.base{background:#fff1db}.purchase-tiers small{color:#687681;font-size:9px;white-space:nowrap}.purchase-tiers b{color:#bd6800;font-size:11px;white-space:nowrap}.no-data{color:#929da5}
.issues .blocking{margin:0;border-top:1px solid #f3c7c3;background:#fff1ef;color:#b62f27;font-weight:800}.issues article.error{background:#fff8f7}.issues article.error em{color:#c4362d}.primary:disabled{cursor:not-allowed;opacity:.5}
.import-mode{display:grid;gap:6px;margin:12px 0;padding:12px;border:1px solid #f1d19a;border-radius:8px;background:#fff8eb;color:#6c5b42;font-size:11px}.import-mode select{height:36px;border:1px solid #dfc392;border-radius:6px;background:#fff;padding:0 9px}.import-mode small{color:#a35f00}
.pagination{display:flex;min-width:1180px;align-items:center;justify-content:space-between;gap:18px;padding:14px 16px;border-top:1px solid #e3e8eb;background:#fafbfc;color:#74808a;font-size:11px}.pagination nav{display:flex;align-items:center;gap:6px}.pagination button{min-width:32px;height:32px;padding:0 10px;border:1px solid #dce3e8;border-radius:6px;background:#fff;color:#596771;font-size:11px;font-weight:800}.pagination button.active{border-color:#ff9900;background:#ff9900;color:#17212b}.pagination button:disabled{cursor:not-allowed;opacity:.42}.pagination nav i{display:grid;width:24px;place-items:center;color:#9aa4ac;font-style:normal}.pagination label{display:flex;align-items:center;gap:7px}.pagination select{height:32px;border:1px solid #dce3e8;border-radius:6px;background:#fff;padding:0 8px;color:#596771}
.task-center-modal{width:min(1180px,97vw)}.task-center-grid{display:grid;grid-template-columns:280px 1fr;min-height:520px;margin-top:18px;border:1px solid #dfe5e9;border-radius:9px;overflow:hidden}.job-list{overflow:auto;border-right:1px solid #dfe5e9;background:#f7f9fa}.job-list>button{display:grid;width:100%;gap:5px;padding:14px;border:0;border-bottom:1px solid #e5eaed;background:transparent;text-align:left}.job-list>button.active{background:#fff3df;box-shadow:inset 3px 0 #ff9900}.job-list span,.job-list small{color:#74808a;font-size:10px}.job-detail{min-width:0;padding:20px}.job-detail>header{display:flex;align-items:center;justify-content:space-between}.job-detail>header>div{display:grid;gap:7px}.job-detail>header strong{font-size:28px}.job-status{width:max-content;padding:4px 8px;border-radius:10px;background:#edf1f3;color:#596771;font-size:10px}.job-status.ready,.job-status.completed{background:#e7f6ec;color:#16764a}.job-status.failed,.job-status.completed-with-errors{background:#fff0e7;color:#ad5700}.progress{height:8px;margin:15px 0;border-radius:8px;overflow:hidden;background:#edf1f3}.progress i{display:block;height:100%;background:#ff9900;transition:width .25s}.job-stats{display:grid;grid-template-columns:repeat(6,1fr);gap:8px}.job-stats span{display:grid;gap:4px;padding:10px;border-radius:7px;background:#f6f8f9;color:#74808a;font-size:9px}.job-stats b{color:#17212b;font-size:18px}.job-error{padding:10px;border-radius:6px;background:#fff0ee;color:#b3362e}.image-part-upload{display:flex;align-items:center;gap:10px;margin:14px 0;padding:12px;border:1px dashed #d8dfe4;border-radius:7px}.image-part-upload label{display:flex;align-items:center;gap:7px}.image-part-upload input{width:70px;height:30px;border:1px solid #d7dee3}.image-part-upload button,.row-filter button,.row-filter a{height:32px;padding:0 10px;border:1px solid #dce3e8;border-radius:5px;background:#fff;color:#596771;text-decoration:none;line-height:30px}.image-part-upload small{margin-left:auto;color:#7b8790}.image-part-list{display:flex;max-height:100px;gap:6px;overflow:auto}.image-part-list button{display:grid;min-width:115px;gap:3px;padding:7px;border:1px solid #dfe5e9;border-radius:6px;background:#f7f9fa;text-align:left}.image-part-list button.completed{border-color:#bfe4ce;background:#edf9f1}.image-part-list button.failed{border-color:#f0c5aa;background:#fff4ec;cursor:pointer}.image-part-list span,.image-part-list small{font-size:9px;color:#74808a}.image-part-list small{max-width:160px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.row-filter{display:flex;gap:7px;margin:14px 0}.row-filter button.active{border-color:#ff9900;color:#a96000}.row-filter a{margin-left:auto}.job-rows{max-height:180px;overflow:auto;border:1px solid #e2e7ea;border-radius:7px}.job-rows article{display:grid;gap:4px;padding:9px 11px;border-top:1px solid #edf0f2;font-size:10px}.job-rows article:first-child{border-top:0}.job-rows span{color:#a34c00}.action-confirm{display:flex;align-items:center;justify-content:flex-end;gap:8px;margin-top:15px;padding:12px;border-radius:7px;background:#fff4df}.action-confirm span{margin-right:auto;font-weight:800}.action-confirm button{height:34px;padding:0 12px;border:1px solid #dce3e8;border-radius:5px;background:#fff}.action-confirm .primary{border:0;background:#ff9900}@media(max-width:900px){.task-center-grid{grid-template-columns:1fr}.job-list{max-height:180px;border-right:0;border-bottom:1px solid #dfe5e9}.job-stats{grid-template-columns:repeat(3,1fr)}}
.actions{display:flex;flex-wrap:wrap;align-items:center;gap:4px}.actions button:disabled{cursor:not-allowed;opacity:.45}.danger-link{color:#c43f36!important}.product-action-modal{width:min(580px,96vw)}.action-warning{margin:16px 0;padding:13px 14px;border:1px solid #f1d19a;border-radius:8px;background:#fff8eb;color:#775628;line-height:1.6}.action-warning.danger{border-color:#edc0bc;background:#fff3f1;color:#a6322a}.reference-grid{display:grid;grid-template-columns:repeat(3,1fr);gap:8px;margin:14px 0}.reference-grid span{display:grid;gap:5px;padding:11px;border-radius:7px;background:#f5f7f8;color:#74808a;font-size:10px}.reference-grid b{color:#17212b;font-size:20px}.rollback-hint{padding:10px 12px;border-left:3px solid #ff9900;background:#fff8ea;color:#8a570d;font-size:11px}.delete-confirmation{display:grid;gap:7px;margin-top:16px;color:#45535e;font-size:11px;font-weight:800}.delete-confirmation input{box-sizing:border-box;width:100%;height:40px;border:1px solid #d9e0e5;border-radius:7px;padding:0 10px;font:inherit;text-transform:uppercase}.delete-confirmation input:focus{border-color:#dc4b41;outline:2px solid rgba(220,75,65,.12)}.delete-loading{display:grid;place-items:center;min-height:120px;color:#74808a}.modal footer .danger-button{border-color:#c43f36;background:#c43f36;color:#fff}.modal footer button:disabled{cursor:not-allowed;opacity:.45}@media(max-width:600px){.reference-grid{grid-template-columns:repeat(2,1fr)}}
</style>
