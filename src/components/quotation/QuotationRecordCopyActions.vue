<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { updateQuotationRecord, type QuotationRecord } from '@/data/quotationRecords'
import { quoteSheetRowKey } from '@/data/customerQuoteSheet'
import { recordHiddenOptionIds } from '@/data/customerQuotePrices'
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
const imageChoice = ref<HTMLDivElement | null>(null)
const imageMenuOpen = ref(false)
const status = ref<{ message: string; failed: boolean }>()
const copyingData = ref(false)
const copyMode = ref<'full' | 'quote'>('full')
const sheetVersion = ref<'full' | 'visible'>('visible')
const hiddenCount = computed(() => recordHiddenOptionIds(props.record).filter(id => props.record.quoteOptions?.some(option => option.id === id)).length)
watch(() => props.record.id, () => { sheetVersion.value = 'visible' })
watch(sheetVersion, async () => {
  status.value = undefined
  await nextTick()
  if (dialog.value?.open) await sheet.value?.preview()
})
let opening = 0

watch(contextKey, () => { opening++; imageMenuOpen.value = false; status.value = undefined; dialog.value?.close() })
function dismissImageMenu(event: PointerEvent) {
  if (!imageChoice.value?.contains(event.target as Node)) imageMenuOpen.value = false
}
onMounted(() => document.addEventListener('pointerdown', dismissImageMenu))
onBeforeUnmount(() => { opening++; document.removeEventListener('pointerdown', dismissImageMenu) })
async function imageAction() {
  if (!hiddenCount.value) return openImage()
  imageMenuOpen.value = !imageMenuOpen.value
  await nextTick()
  if (imageMenuOpen.value) imageChoice.value?.querySelector<HTMLButtonElement>('[role="menuitem"]')?.focus()
}
function closeImageMenu() {
  imageMenuOpen.value = false
  imageButton.value?.focus()
}
function moveImageChoice(direction: number) {
  const choices = [...(imageChoice.value?.querySelectorAll<HTMLButtonElement>('[role="menuitem"]') ?? [])]
  const index = choices.findIndex(choice => choice === document.activeElement)
  choices[(index + direction + choices.length) % choices.length]?.focus()
}
async function chooseImageVersion(version: 'full' | 'visible') {
  const context = contextKey.value
  imageMenuOpen.value = false
  sheetVersion.value = version
  await nextTick()
  if (context === contextKey.value) await openImage()
}
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
    const customerQuote={ hiddenOptionIds: source.value.rows.filter(row => captured.hiddenRowKeys?.includes(quoteSheetRowKey(row))).map(row => row.channelKey!), contact:captured.contact, quantities:captured.quantities, rows:captured.rows.map(row=>{
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
    const layout = mode === 'quote' ? quotationRecordQuoteOnlyLayout(latest, sheetVersion.value) : quotationRecordCopyLayout(latest, sheetVersion.value)
    await copyQuotationText(layout.text, layout.html)
    if (context === contextKey.value) status.value = { message: (mode === 'quote' ? '已复制报价单（含物流渠道），可直接粘贴到 Excel' : '已复制完整对账明细（横向报价表），可直接粘贴到 Excel') + (hiddenCount.value ? `；${sheetVersion.value === 'full' ? '完整报价单' : '隐藏行后的报价单'}` : ''), failed: false }
  }
  catch (error) { if (context === contextKey.value) status.value = { message: error instanceof Error ? error.message : '复制失败，请重试', failed: true } }
  finally { copyingData.value = false }
}
</script>

<template>
  <div class="record-copy-actions">
    <div class="record-copy-buttons">
      <button type="button" :disabled="copyingData" title="复制客户、客户等级、SKU、成本与重量摘要、国家、物流商与渠道、各数量报价及预计时效" @click="copyData('quote')">{{ copyingData && copyMode === 'quote' ? '正在复制…' : '仅复制报价单' }}</button>
      <div ref="imageChoice" class="record-image-choice" @keydown.esc.stop.prevent="closeImageMenu" @focusout="!imageChoice?.contains($event.relatedTarget as Node) && (imageMenuOpen = false)">
        <button ref="imageButton" class="copy-image" type="button" aria-label="复制报价图片" :aria-haspopup="hiddenCount ? 'menu' : undefined" :aria-expanded="hiddenCount ? imageMenuOpen : undefined" :disabled="copyingData || saving" @click="imageAction">复制报价图片<span v-if="hiddenCount" aria-hidden="true" class="image-choice-arrow">▾</span></button>
        <div v-if="imageMenuOpen" class="record-image-menu" role="menu" aria-label="报价图片版本" @keydown.down.prevent="moveImageChoice(1)" @keydown.up.prevent="moveImageChoice(-1)">
          <button type="button" role="menuitem" @click="chooseImageVersion('full')"><strong>完整报价单</strong><small>全部 {{ record.quoteOptions?.length }} 行</small></button>
          <button type="button" role="menuitem" @click="chooseImageVersion('visible')"><strong>隐藏行后的报价单</strong><small>隐藏 {{ hiddenCount }} 行</small></button>
        </div>
      </div>
      <button type="button" :disabled="copyingData" title="复制横向报价表：国家与分区、运输及实际有报价的数量，附产品成本、运费与税费明细；粘贴到 Excel 可保留排版" @click="copyData('full')">{{ copyingData && copyMode === 'full' ? '正在复制…' : '复制报价数据' }}</button>
      <slot />
    </div>
    <p v-if="status" role="status" :class="{ failed: status.failed }">{{ status.message }}</p>
    <button v-if="status?.failed" type="button" class="record-edit-retry" :disabled="copyingData" @click="copyData(copyMode)">{{ copyMode === 'quote' ? '重新复制报价单' : '重新复制对账明细' }}</button>
    <Teleport to="body">
      <dialog ref="dialog" class="record-quote-dialog" aria-label="报价记录客户报价单" @cancel.prevent="closeImage">
        <header><div><strong>客户报价单</strong><p>使用本条记录保存的渠道与报价；预览后复制图片，或直接复制表格数据。</p></div><button type="button" :disabled="sheet?.copying" aria-label="关闭客户报价单" @click="closeImage">×</button></header>
        <p v-if="status?.failed" role="alert">{{ status.message }}</p>
        <label v-if="hiddenCount" class="record-sheet-version dialog-version">报价单版本
          <select v-model="sheetVersion" aria-label="图片报价单版本" :disabled="copyingData || saving || sheet?.copying">
            <option value="visible">隐藏行后的报价单（隐藏 {{ hiddenCount }} 行）</option>
            <option value="full">完整报价单（全部 {{ record.quoteOptions?.length }} 行）</option>
          </select>
        </label>
        <fieldset :disabled="saving" style="border:0;padding:0;margin:0"><CustomerQuoteSheet ref="sheet" v-bind="source" :show-all-rows="sheetVersion === 'full'" :context-key="contextKey" :source-pending="false" /></fieldset>
        <footer v-if="canEdit" style="padding:16px 22px;text-align:right"><button type="button" :disabled="saving || sheet?.copying" @click="savePrices">{{ saving?'正在保存…':'保存客户报价' }}</button></footer>
      </dialog>
    </Teleport>
  </div>
</template>

<style scoped>
.record-image-choice{position:relative}.image-choice-arrow{margin-left:8px;font-size:11px}.record-image-menu{position:absolute;right:0;bottom:calc(100% + 8px);z-index:5;width:230px;padding:6px;border:1px solid #edd6bc;border-radius:9px;background:#fff;box-shadow:0 8px 28px #27324026}.record-copy-buttons .record-image-menu button{display:flex;align-items:center;justify-content:space-between;gap:10px;width:100%;height:44px;padding:0 10px;border:0;border-radius:5px;background:#fff;color:#26313b;text-align:left}.record-copy-buttons .record-image-menu button:hover,.record-copy-buttons .record-image-menu button:focus-visible{background:#fff2e4;outline:2px solid #f5b16c;outline-offset:-2px}.record-image-menu strong{font-size:12px}.record-image-menu small{font-size:11px;font-weight:400;color:#8a755e}
.record-sheet-version{display:flex;align-items:center;gap:10px;font-size:12px;color:#53616c}.record-sheet-version select{height:36px;max-width:100%;padding:0 10px;border:1px solid #dfb586;border-radius:7px;background:#fff8f1;color:#8b4b12;font:inherit}.dialog-version{margin:0 22px 16px}
.record-copy-actions{display:flex;flex-direction:column;align-items:flex-end;gap:8px}.record-copy-buttons{display:flex;gap:10px;flex-wrap:wrap;justify-content:flex-end}.record-copy-buttons button{height:40px;padding:0 16px;border:1px solid #f58220;border-radius:7px;background:#fff8f1;color:#a6530c;font-size:12px;font-weight:700;cursor:pointer}.record-copy-buttons button.copy-image{background:#f58220;color:#fff}.record-copy-buttons button:disabled{opacity:.5;cursor:wait}.record-copy-actions>p{margin:0;max-width:420px;font-size:12px;line-height:1.6;color:#287a4d}.record-copy-actions>p.failed{color:#a65410}.record-quote-dialog{box-sizing:border-box;width:min(1600px,96vw);max-width:96vw;max-height:92vh;padding:0;border:1px solid #e0e4e8;border-radius:12px;background:#fff;color:#202532;font-family:Arial,"Microsoft YaHei",sans-serif;box-shadow:0 20px 60px #17212b33}.record-quote-dialog::backdrop{background:#17212b88}.record-quote-dialog>header{display:flex;justify-content:space-between;gap:20px;padding:18px 22px;margin-bottom:18px;border-bottom:1px solid #e5e9ed}.record-quote-dialog>header strong{font-size:16px}.record-quote-dialog>header p{margin:6px 0 0;font-size:12px;color:#72808a;line-height:1.6}.record-quote-dialog>header button{align-self:flex-start;width:32px;height:32px;border:0;border-radius:6px;background:#f3f5f7;font-size:24px;cursor:pointer}
</style>
