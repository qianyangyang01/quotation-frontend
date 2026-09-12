<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import type { QuotationRecord } from '@/data/quotationRecords'
import { quotationRecordQuoteSheetSource } from '@/data/quotationRecordQuoteSheet'
import CustomerQuoteSheet from './CustomerQuoteSheet.vue'

const props = defineProps<{ record: QuotationRecord }>()
const source = computed(() => quotationRecordQuoteSheetSource(props.record))
const contextKey = computed(() => `record:${props.record.id}:${props.record._version ?? ''}:${props.record.updatedAt}`)
const sheet = ref<InstanceType<typeof CustomerQuoteSheet> | null>(null)
const dialog = ref<HTMLDialogElement | null>(null)
const imageButton = ref<HTMLButtonElement | null>(null)
const status = ref<{ message: string; failed: boolean }>()
const copyingData = ref(false)

watch(contextKey, () => { status.value = undefined; dialog.value?.close() })
async function openImage() {
  status.value = undefined
  dialog.value?.showModal()
  await nextTick()
  await sheet.value?.preview()
}
function closeImage() {
  if (sheet.value?.copying) return
  sheet.value?.invalidate() // Release PNGs on close; retain only in-page text edits.
  dialog.value?.close()
  imageButton.value?.focus()
}
async function copyData() {
  copyingData.value = true
  status.value = undefined
  try { status.value = await sheet.value?.copyData() }
  finally { copyingData.value = false }
}
</script>

<template>
  <div class="record-copy-actions">
    <div class="record-copy-buttons">
      <button ref="imageButton" type="button" :disabled="copyingData" @click="openImage">复制报价图片</button>
      <button type="button" :disabled="copyingData" @click="copyData">{{ copyingData ? '正在复制…' : '复制报价数据' }}</button>
    </div>
    <p v-if="status" role="status" :class="{ failed: status.failed }">{{ status.message }}</p>
    <Teleport to="body">
      <dialog ref="dialog" class="record-quote-dialog" aria-label="报价记录客户报价单" @cancel.prevent="closeImage">
        <header><div><strong>客户报价单</strong><p>使用本条记录保存的渠道与报价；预览后复制图片，或直接复制表格数据。</p></div><button type="button" :disabled="sheet?.copying" aria-label="关闭客户报价单" @click="closeImage">×</button></header>
        <CustomerQuoteSheet ref="sheet" v-bind="source" :context-key="contextKey" :source-pending="false" />
      </dialog>
    </Teleport>
  </div>
</template>

<style scoped>
.record-copy-actions{display:flex;flex-direction:column;align-items:flex-end;gap:8px}.record-copy-buttons{display:flex;gap:10px;flex-wrap:wrap;justify-content:flex-end}.record-copy-buttons button{height:40px;padding:0 16px;border:1px solid #f58220;border-radius:7px;background:#fff8f1;color:#a6530c;font-size:12px;font-weight:700;cursor:pointer}.record-copy-buttons button:first-child{background:#f58220;color:#fff}.record-copy-buttons button:disabled{opacity:.5;cursor:wait}.record-copy-actions>p{margin:0;max-width:420px;font-size:12px;line-height:1.6;color:#287a4d}.record-copy-actions>p.failed{color:#a65410}.record-quote-dialog{box-sizing:border-box;width:min(1600px,96vw);max-width:96vw;max-height:92vh;padding:0;border:1px solid #e0e4e8;border-radius:12px;background:#fff;color:#202532;font-family:Arial,"Microsoft YaHei",sans-serif;box-shadow:0 20px 60px #17212b33}.record-quote-dialog::backdrop{background:#17212b88}.record-quote-dialog>header{display:flex;justify-content:space-between;gap:20px;padding:18px 22px;margin-bottom:18px;border-bottom:1px solid #e5e9ed}.record-quote-dialog>header strong{font-size:16px}.record-quote-dialog>header p{margin:6px 0 0;font-size:12px;color:#72808a;line-height:1.6}.record-quote-dialog>header button{align-self:flex-start;width:32px;height:32px;border:0;border-radius:6px;background:#f3f5f7;font-size:24px;cursor:pointer}
</style>
