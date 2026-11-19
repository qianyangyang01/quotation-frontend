<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import { prepareBatchExport, type BatchExportData } from '@/data/logisticsBatchExport'
import type { PriceUpdateGroup } from '@/data/logisticsUpdateOverview'
import { publicationTime, type RealPriceSnapshot } from '@/data/logisticsRealPriceHistory'
import { batchPriceWorkbook, loadExportVersionRows, saveBatchWorkbook } from '@/services/logisticsBatchWorkbook'
const props = defineProps<{ snapshot: RealPriceSnapshot; batch: PriceUpdateGroup }>()
const emit = defineEmits<{ close: [] }>()
const dialog = ref<HTMLDialogElement>(), data = shallowRef<BatchExportData>()
const loading = ref(true), saving = ref(false), error = ref(''), progress = ref(''), downloaded = ref(false)
const controller = new AbortController()
const counts = computed(() => data.value?.rows.reduce((v, r) => { v[r.category]++;return v }, { up: 0, down: 0, same: 0, other: 0 }))
let previousFocus: HTMLElement | null = null
function close() { controller.abort();emit('close') }
async function load() {
  loading.value = true;error.value = ''
  try { data.value = await prepareBatchExport(props.snapshot, props.batch, loadExportVersionRows, controller.signal, (done, total) => { progress.value = `${done} / ${total}` }) }
  catch (e) { if (!controller.signal.aborted) error.value = e instanceof Error ? e.message : '读取失败，请重试' }
  finally { loading.value = false }
}
async function download() {
  if (!data.value || saving.value) return
  saving.value = true;error.value = ''
  try { const blob = await batchPriceWorkbook(data.value);controller.signal.throwIfAborted();saveBatchWorkbook(blob, data.value);downloaded.value = true }
  catch (e) { if (!controller.signal.aborted) error.value = e instanceof Error ? e.message : '下载失败，请重试' }
  finally { saving.value = false }
}
onMounted(() => { previousFocus = document.activeElement as HTMLElement;dialog.value?.showModal();void load();window.addEventListener('quotation:account-changed', close);window.addEventListener('quotation:session-expired', close) })
onBeforeUnmount(() => { controller.abort();dialog.value?.close();previousFocus?.isConnected && previousFocus.focus();window.removeEventListener('quotation:account-changed', close);window.removeEventListener('quotation:session-expired', close) })
</script>
<template>
  <dialog ref="dialog" class="batch-export-dialog" aria-labelledby="batch-export-title" @cancel.prevent="close">
    <header><h3 id="batch-export-title">下载批次明细</h3><button aria-label="关闭下载窗口" @click="close">×</button></header>
    <main>
      <div class="batch"><b>{{ publicationTime(batch.windowStartAt || batch.startedAt) }}<template v-if="batch.windowEndAt"> — {{ publicationTime(batch.windowEndAt).slice(11) }}</template></b><p>{{ batch.operator || '独立发布' }} · {{ batch.imports.length || batch.batches.length }} 个原始批次</p></div>
      <p><b>范围：</b>当前批次全部国家、全部重量段</p><p class="hint">不受页面筛选、搜索和分页影响。</p>
      <p v-if="loading" role="status">正在读取重量段 {{ progress }}</p>
      <div v-if="counts" class="counts"><div class="up">涨价<strong>{{ counts.up }}</strong></div><div class="down">降价<strong>{{ counts.down }}</strong></div><div>不变<strong>{{ counts.same }}</strong></div><div class="other">其他变动<strong>{{ counts.other }}</strong></div></div>
      <p class="hint">Excel 分为汇总、涨价、降价、不变、其他变动五张表。表格居中对齐，表头可筛选。</p>
      <p class="hint">重量段调整后按前后区间对齐；规则调整、费用混合变化单独列出。</p>
      <p v-if="error" class="error" role="alert">{{ error }} <button v-if="!data" @click="load">重试</button></p>
      <p v-if="downloaded" class="down" role="status">已生成 Excel，请查看浏览器下载。</p>
    </main>
    <footer><span>{{ data ? `共 ${data.rows.length} 条重量段记录` : '格式：.xlsx' }}</span><div><button @click="close">关闭</button><button class="primary" :disabled="loading || saving || !data" @click="download">{{ saving ? '生成中…' : '下载 Excel' }}</button></div></footer>
  </dialog>
</template>
<style scoped>
.batch-export-dialog{width:min(590px,calc(100vw - 28px));border:1px solid #d5e0ed;border-radius:10px;padding:0;color:#233f57;background:white;font:14px/1.6 Arial,"Microsoft YaHei",sans-serif;box-sizing:border-box}.batch-export-dialog::backdrop{background:#192c4566}.batch-export-dialog header{display:flex;align-items:center;justify-content:space-between;padding:17px 22px;border-bottom:1px solid #e1e8f0}.batch-export-dialog h3{margin:0;font-size:20px}.batch-export-dialog button{border:1px solid #d5e0ed;border-radius:5px;background:white;padding:8px 16px;font:inherit;color:#34526d;cursor:pointer}.batch-export-dialog header button{border:0;font-size:25px;padding:0 8px}.batch-export-dialog main{padding:20px 22px}.batch{background:#f5f8fc;padding:12px 15px;border-radius:6px}.batch p{font-size:12px;color:#73859b;margin:4px 0 0}.hint{color:#73859b;font-size:13px;margin:10px 0}.counts{display:grid;grid-template-columns:repeat(4,1fr);gap:9px;margin:18px 0}.counts div{text-align:center;background:#f2f5f9;border-radius:5px;padding:10px}.counts strong{display:block;font-size:22px}.up{color:#ce423b}.down{color:#168878}.other{color:#a47132}.counts .up{background:#fff2f1}.counts .down{background:#eaf8f6}.counts .other{background:#fff8ed}.batch-export-dialog footer{display:flex;align-items:center;justify-content:space-between;padding:15px 22px;border-top:1px solid #e1e8f0;font-size:13px;color:#73859b}.batch-export-dialog footer button+button{margin-left:8px}.batch-export-dialog .primary{background:#2169c4;color:white;border-color:#2169c4}.batch-export-dialog button:disabled{opacity:.5;cursor:default}.error{color:#bd413a}.batch-export-dialog :focus-visible{outline:2px solid #2169c4;outline-offset:2px}
</style>
