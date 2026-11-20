<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import { currentAuthUser } from '@/data/authStore'
import { loadRecord } from '@/data/quotationRecordQuery'
import type { QuotationRecord } from '@/data/quotationRecords'
import { quotationRecordQuoteSheetSource } from '@/data/quotationRecordQuoteSheet'
import CustomerQuoteSheet from './CustomerQuoteSheet.vue'

const props = defineProps<{ record: QuotationRecord; disabled?: boolean }>()
const busy = ref(false)
const status = ref<{ message: string; failed: boolean }>()
const captured = shallowRef<QuotationRecord>()
const sheet = ref<InstanceType<typeof CustomerQuoteSheet>>()
const source = computed(() => captured.value ? quotationRecordQuoteSheetSource(captured.value) : undefined)
let generation = 0, disposed = false
function cancel() { generation++; captured.value = undefined; status.value = undefined }
watch(() => [props.record.id, props.record._version, props.record.updatedAt, currentAuthUser.value.account], cancel, { flush: 'sync' })
onBeforeUnmount(() => { disposed = true; cancel() })

async function copy() {
  if (busy.value || props.disabled || props.record.quoteMode === 'fob') return
  const token = ++generation, id = props.record.id
  busy.value = true; status.value = undefined
  try {
    // List responses can be summaries: read the complete saved snapshot before copying.
    const fresh = await loadRecord(id)
    if (disposed || token !== generation) return
    if (!fresh || fresh.id !== id) throw new Error('报价记录不存在或无权查看，请刷新后重试')
    if (fresh.quoteMode === 'fob') throw new Error('FOB 报价请进入详情复制独立报价单')
    captured.value = fresh
    await nextTick()
    if (disposed || token !== generation) return
    const result = await sheet.value?.copyCompact()
    if (!disposed && token === generation) status.value = result ?? { message: '报价图片尚未就绪，请重试', failed: true }
  } catch (error) {
    if (!disposed && token === generation) status.value = { message: error instanceof Error ? error.message : '复制失败，请重试', failed: true }
  } finally {
    captured.value = undefined
    busy.value = false
  }
}
</script>

<template>
  <div v-if="record.quoteMode !== 'fob'" class="record-table-copy">
    <button type="button" :disabled="disabled || busy" :aria-label="'复制图文表格 ' + record.no" title="复制本条已保存报价和图片，可直接粘贴到 WPS" @click="copy">{{ busy ? '正在复制…' : '复制图文表格' }}</button>
    <small v-if="status" :role="status.failed ? 'alert' : 'status'" :class="{ failed: status.failed }">{{ status.message }}</small>
    <div v-if="captured && source" hidden><CustomerQuoteSheet :key="captured.id" ref="sheet" v-bind="source" :context-key="`list-copy:${captured.id}:${captured._version ?? ''}`" :source-pending="false" hide-back-to-top /></div>
  </div>
</template>

<style scoped>
.record-table-copy{display:flex;flex-direction:column;align-items:flex-start;gap:5px}.record-table-copy button{padding:5px 9px;border:1px solid #cfd8e3;border-radius:6px;background:#fff;color:#255d8a;font:inherit;font-size:12px;font-weight:600;cursor:pointer}.record-table-copy button:disabled{opacity:.55;cursor:wait}.record-table-copy small{max-width:240px;color:#287a4d;font-size:11px;line-height:1.5}.record-table-copy small.failed{color:#b42318}
</style>
