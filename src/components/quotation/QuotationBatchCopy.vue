<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import type { QuotationRecord } from '@/data/quotationRecords'
import type { CustomerQuoteSheet as Sheet } from '@/data/customerQuoteSheet'
import { canViewFullPricing, canViewPurchaseCost } from '@/data/quotationVisibility'
import { quotationRecordQuoteSheetSource } from '@/data/quotationRecordQuoteSheet'
import { batchRecordCopyLayout, loadQuotationBatchRecords, mergeSavedBatchSheets, type BatchCopyKind } from '@/services/quotationBatchCopy'
import { copyQuotationText } from '@/services/customerQuoteSheetClipboard'
import CustomerQuoteSheet from './CustomerQuoteSheet.vue'
import BatchSingleSheet from './BatchSingleSheet.vue'
const props = defineProps<{ record: QuotationRecord; version?: 'visible' | 'full' }>()
const busy = ref(false), message = ref(''), failed = ref(false), lastKind = ref<BatchCopyKind>('full')
const records = ref<QuotationRecord[]>([]), combined = ref<Sheet>(), dialog = ref<HTMLDialogElement>()
const sources = computed(() => records.value.filter(record => record.quoteMode !== 'fob').map(record => ({ id: record.id, source: quotationRecordQuoteSheetSource(record) })))
const sheets = new Map<string, InstanceType<typeof CustomerQuoteSheet>>(), preview = ref<InstanceType<typeof BatchSingleSheet>>()
function setSheet(id: string, instance: unknown) { if (instance) sheets.set(id, instance as InstanceType<typeof CustomerQuoteSheet>); else sheets.delete(id) }
let generation = 0
function reset() { generation++; records.value = []; combined.value = undefined; busy.value = false; dialog.value?.close(); message.value = '' }
watch(() => [props.record.id, props.record._version, props.record.updatedAt], reset)
onBeforeUnmount(() => { generation++ })
async function run(kind: BatchCopyKind) {
  if (busy.value) return
  const token = ++generation
  busy.value = true; failed.value = false; message.value = ''; lastKind.value = kind
  try {
    const members = await loadQuotationBatchRecords(props.record)
    if (token !== generation) return
    if (kind !== 'image') {
      const layout = batchRecordCopyLayout(members, kind, props.version ?? 'visible', { includeCost: canViewPurchaseCost.value, includeDetails: canViewFullPricing.value })
      await copyQuotationText(layout.text, layout.html)
      if (token === generation) message.value = `已复制本批全部 ${members.length} 条记录，可粘贴到 Excel / WPS`
    } else {
      records.value = members
      await nextTick()
      if (token !== generation) return
      const captured = members.map(record => {
        if (record.quoteMode === 'fob') {
          if (!record.fob?.sheet) throw new Error('本批存在缺少报价单快照的FOB记录')
          return record.fob.sheet
        }
        const sheet = sheets.get(record.id)?.captureSheet()
        if (!sheet) throw new Error('报价单尚未就绪，请重试')
        return { ...sheet, customerName: record.customerName }
      })
      combined.value = mergeSavedBatchSheets(captured)
      await nextTick()
      if (token !== generation) return
      dialog.value?.showModal()
      await preview.value?.preview()
    }
  } catch (error) {
    if (token === generation) { failed.value = true; message.value = error instanceof Error ? error.message : '整批复制失败，请重试' }
  } finally { if (token === generation) { busy.value = false; records.value = [] } }
}
function close() { dialog.value?.close(); combined.value = undefined }
defineExpose({ run, busy })
</script>
<template>
  <p v-if="busy" role="status" class="batch-copy-message">正在读取本批全部记录…</p>
  <p v-else-if="message" role="status" class="batch-copy-message" :class="{ failed }">{{ message }} <button v-if="failed" type="button" @click="run(lastKind)">重试整批复制</button></p>
  <div hidden aria-hidden="true"><CustomerQuoteSheet v-for="entry in sources" :key="entry.id" :ref="instance => setSheet(entry.id, instance)" v-bind="entry.source" :initial-photos="[]" :show-all-rows="version === 'full'" :context-key="`batch-copy:${entry.id}`" :source-pending="false" /></div>
  <Teleport to="body"><dialog ref="dialog" class="batch-copy-dialog" aria-label="本批全部报价图片" @cancel.prevent="close">
    <header><div><strong>本批全部报价 · {{ record.quotationBatch?.count }} 条</strong><p>使用各条记录已保存的报价；长报价单自动分成多张图片。</p></div><button type="button" aria-label="关闭整批报价图片" @click="close">×</button></header>
    <BatchSingleSheet v-if="combined" ref="preview" :sheet="combined" />
  </dialog></Teleport>
</template>
<style scoped>
.batch-copy-message{font-size:12px;color:#287a4d;margin:8px 0}.batch-copy-message.failed{color:#a65410}.batch-copy-dialog{width:min(1500px,95vw);box-sizing:border-box;max-height:92vh;padding:20px;border:1px solid #dfe5eb;border-radius:12px;color:#233044}.batch-copy-dialog::backdrop{background:#17212b88}.batch-copy-dialog header{display:flex;justify-content:space-between;align-items:center;gap:20px}.batch-copy-dialog header p{font-size:12px;color:#72808a}.batch-copy-dialog header button{font-size:24px;background:#f3f5f7;border:0;border-radius:5px;cursor:pointer}
</style>
