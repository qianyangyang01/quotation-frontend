<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { updateQuotationRecord, type QuotationRecord } from '@/data/quotationRecords'
import { quoteSheetRowKey } from '@/data/customerQuoteSheet'
import { quotationRecordQuoteSheetSource } from '@/data/quotationRecordQuoteSheet'
import { quotationRecordCopyLayout } from '@/data/quotationRecordCopyLayout'
import { quotationRecordQuoteOnlyLayout } from '@/data/quotationRecordQuoteOnlyLayout'
import { copyQuotationText } from '@/services/customerQuoteSheetClipboard'
import CustomerQuoteSheet from './CustomerQuoteSheet.vue'

const props = defineProps<{ record: QuotationRecord; canEdit?:boolean; refreshRecord?: (id: string) => Promise<QuotationRecord | null> }>()
const emit=defineEmits<{saved:[record:QuotationRecord]}>()
const saving=ref(false)
const source = computed(() => quotationRecordQuoteSheetSource(props.record))
const contextKey = computed(() => `record:${props.record.id}:${props.record._version ?? ''}:${props.record.updatedAt}`)
const sheet = ref<InstanceType<typeof CustomerQuoteSheet> | null>(null)
const dialog = ref<HTMLDialogElement | null>(null)
const imageButton = ref<HTMLButtonElement | null>(null)
const status = ref<{ message: string; failed: boolean }>()
const copyingData = ref(false)
const copyMode = ref<'full' | 'quote'>('full')
let opening = 0

watch(contextKey, () => { opening++; status.value = undefined; dialog.value?.close() })
onBeforeUnmount(() => { opening++ })
async function openImage() {
  if (copyingData.value || sheet.value?.copying) return
  const token = ++opening
  const context = contextKey.value
  status.value = undefined
  dialog.value?.showModal()
  await nextTick()
  if (token !== opening || context !== contextKey.value || !dialog.value?.open) return
  await sheet.value?.preview()
}
function closeImage() {
  if (sheet.value?.copying || saving.value) return
  opening++
  sheet.value?.invalidate() // Release PNGs on close; retain only in-page text edits.
  dialog.value?.close()
  imageButton.value?.focus()
}
async function savePrices() {
  if (!props.canEdit || saving.value || sheet.value?.copying) return
  const id=props.record.id, version=props.record._version
  saving.value=true; status.value=undefined
  try {
    const captured=sheet.value?.capturePrices()
    if (!captured) throw new Error('报价单尚未就绪')
    const customerQuote={ quantities:captured.quantities, rows:captured.rows.map(row=>{
      const sourceRow=source.value.rows.find(source=>quoteSheetRowKey(source)===row.key)
      if (!sourceRow) throw new Error('客户报价渠道不匹配')
      return {optionId:sourceRow.channelKey!,prices:row.prices}
    }) }
    const record=await updateQuotationRecord(id,{customerQuote},version)
    if (props.record.id!==id) return
    if (!record) throw new Error('保存失败，请重试')
    if ((record._version ?? -1)<(props.record._version ?? -1)) return
    emit('saved',record)
    status.value={message:'客户报价已保存',failed:false}
  } catch(error) {if (props.record.id===id) status.value={message:error instanceof Error?error.message:'保存失败',failed:true}}
  finally {saving.value=false}
}
async function copyData(mode: 'full' | 'quote' = 'full') {
  if (copyingData.value) return
  const context = contextKey.value
  copyingData.value = true
  copyMode.value = mode
  status.value = undefined
  try {
    const latest = props.refreshRecord ? await props.refreshRecord(props.record.id) : props.record
    if (context !== contextKey.value) return
    if (!latest) throw new Error('报价记录已不可用，请刷新后重试')
    const layout = mode === 'quote' ? quotationRecordQuoteOnlyLayout(latest) : quotationRecordCopyLayout(latest)
    await copyQuotationText(layout.text, layout.html)
    if (context === contextKey.value) status.value = { message: mode === 'quote' ? '已复制报价单（含物流渠道），可直接粘贴到 Excel' : '已复制完整对账明细（横向报价表），可直接粘贴到 Excel', failed: false }
  }
  catch (error) { if (context === contextKey.value) status.value = { message: error instanceof Error ? error.message : '复制失败，请重试', failed: true } }
  finally { copyingData.value = false }
}
</script>

<template>
  <div class="record-copy-actions">
    <div class="record-copy-buttons">
      <button type="button" :disabled="copyingData" title="仅复制客户、SKU、国家、物流商与渠道、各数量报价及预计时效" @click="copyData('quote')">{{ copyingData && copyMode === 'quote' ? '正在复制…' : '仅复制报价单' }}</button>
      <button ref="imageButton" class="copy-image" type="button" :disabled="copyingData" @click="openImage">复制报价图片</button>
      <button type="button" :disabled="copyingData" title="复制横向报价表：国家、运输、1—8件（组合为套）及已保存的其他数量，附产品成本、运费与税费明细；粘贴到 Excel 可保留排版" @click="copyData('full')">{{ copyingData && copyMode === 'full' ? '正在复制…' : '复制报价数据' }}</button>
    </div>
    <p v-if="status" role="status" :class="{ failed: status.failed }">{{ status.message }}</p>
    <button v-if="status?.failed" type="button" class="record-edit-retry" :disabled="copyingData" @click="copyData(copyMode)">{{ copyMode === 'quote' ? '重新复制报价单' : '重新复制对账明细' }}</button>
    <Teleport to="body">
      <dialog ref="dialog" class="record-quote-dialog" aria-label="报价记录客户报价单" @cancel.prevent="closeImage">
        <header><div><strong>客户报价单</strong><p>使用本条记录保存的渠道与报价；预览后复制图片，或直接复制表格数据。</p></div><button type="button" :disabled="sheet?.copying" aria-label="关闭客户报价单" @click="closeImage">×</button></header>
        <p v-if="status?.failed" role="alert">{{ status.message }}</p>
        <fieldset :disabled="saving" style="border:0;padding:0;margin:0"><CustomerQuoteSheet ref="sheet" v-bind="source" :context-key="contextKey" :source-pending="false" /></fieldset>
        <footer v-if="canEdit" style="padding:16px 22px;text-align:right"><button type="button" :disabled="saving || sheet?.copying" @click="savePrices">{{ saving?'正在保存…':'保存客户报价' }}</button></footer>
      </dialog>
    </Teleport>
  </div>
</template>

<style scoped>
.record-copy-actions{display:flex;flex-direction:column;align-items:flex-end;gap:8px}.record-copy-buttons{display:flex;gap:10px;flex-wrap:wrap;justify-content:flex-end}.record-copy-buttons button{height:40px;padding:0 16px;border:1px solid #f58220;border-radius:7px;background:#fff8f1;color:#a6530c;font-size:12px;font-weight:700;cursor:pointer}.record-copy-buttons button.copy-image{background:#f58220;color:#fff}.record-copy-buttons button:disabled{opacity:.5;cursor:wait}.record-copy-actions>p{margin:0;max-width:420px;font-size:12px;line-height:1.6;color:#287a4d}.record-copy-actions>p.failed{color:#a65410}.record-quote-dialog{box-sizing:border-box;width:min(1600px,96vw);max-width:96vw;max-height:92vh;padding:0;border:1px solid #e0e4e8;border-radius:12px;background:#fff;color:#202532;font-family:Arial,"Microsoft YaHei",sans-serif;box-shadow:0 20px 60px #17212b33}.record-quote-dialog::backdrop{background:#17212b88}.record-quote-dialog>header{display:flex;justify-content:space-between;gap:20px;padding:18px 22px;margin-bottom:18px;border-bottom:1px solid #e5e9ed}.record-quote-dialog>header strong{font-size:16px}.record-quote-dialog>header p{margin:6px 0 0;font-size:12px;color:#72808a;line-height:1.6}.record-quote-dialog>header button{align-self:flex-start;width:32px;height:32px;border:0;border-radius:6px;background:#f3f5f7;font-size:24px;cursor:pointer}
</style>
