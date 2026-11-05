<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import CustomerQuoteSheet from './CustomerQuoteSheet.vue'
import { batchSheetEditorSource, captureBatchSheet } from '@/services/batchSingleSheetEditor'
import type { BatchSingleDocument, BatchSingleItem } from '@/services/batchSingleQuotation'
import { isWomensQuotation } from '@/data/quotationModes'

const props = defineProps<{ document: BatchSingleDocument; calculateItem?: (sku: string, quantities: number[]) => Promise<BatchSingleItem> }>()
const emit = defineEmits<{ busy: [value: boolean] }>()
// Parent mounts a new editor only when its generated/selected SKU set changes.
const original = JSON.parse(JSON.stringify(props.document)) as BatchSingleDocument
const source = batchSheetEditorSource(original)
const editor = ref<InstanceType<typeof CustomerQuoteSheet>>()
const system = ref(source.initialSystemQuote), busy = ref(false), error = ref('')
let disposed = false, pending: number[] | undefined, requested: number[] = []
const extraPrices = new Map<number, Map<string, number | null>>()
for (const [index, quantity] of system.value.quantities.entries()) extraPrices.set(quantity, new Map(system.value.rows.map(row => [row.optionId, row.prices[index] ?? null])))
const sourcePending = computed(() => busy.value || !!error.value)
function pricingBasis(input: BatchSingleItem['input']) {
  return JSON.stringify([
    input.quoteMode, input.customerGrade, input.womensPricing,
    input.exchangeRate, input.financeVersions, input.purchaseVersions, input.logisticsRevision,
    input.purchaseUnitPriceCny, input.domesticFreightPerUnitCny, input.customerOperation,
    input.weightSnapshot?.items, input.weightSnapshot?.specialPackagingGrams,
    (input.quoteOptions ?? []).map(row => [row.id,row.quote1Usd,row.quote2Usd,row.quote3Usd]).sort((a,b)=>String(a[0]).localeCompare(String(b[0]))),
  ])
}
async function quantitiesChanged(quantities: number[]) {
  requested = quantities; pending = quantities
  if (busy.value || !props.calculateItem) return
  busy.value = true; emit('busy', true); error.value = ''
  try {
    while (pending && !disposed) {
      const next = pending; pending = undefined
      const missing = next.filter(quantity => !extraPrices.has(quantity))
      if (missing.length) {
        const loaded = new Map(missing.map(quantity => [quantity, new Map<string, number | null>()]))
        const sampleUpdates: Array<() => void> = []
        for (const [index, item] of original.items.entries()) {
          const fresh = await props.calculateItem(item.input.primarySku, next)
          if (disposed) return
          // A quantity edit must not silently replace the previously generated pricing basis.
          if (pricingBasis(item.input) !== pricingBasis(fresh.input)) throw new Error(`${item.input.primarySku} 的计价资料已变化，请修改条件后重新生成整批报价`)
          if (isWomensQuotation(item.input.quoteMode)) {
            for (const option of item.input.quoteOptions ?? []) {
              const updated = fresh.input.quoteOptions?.find(row => row.id === option.id)
              if (!updated) throw new Error(`${item.input.primarySku} 的报价渠道已变化，请重新生成`)
              for (const quantity of missing) {
                const price = fresh.input.systemQuantityQuotes?.rows.find(row => row.optionId === option.id)?.prices[fresh.input.systemQuantityQuotes.quantities.indexOf(quantity)]
                if (price != null && (!updated.womensPricingSamples?.some(row => row.quantity === quantity) || !updated.logisticsSamples?.some(row => row.quantity === quantity))) throw new Error(`${item.input.primarySku} 的新增数量缺少女装计价依据，请重新生成`)
              }
              const mergeSamples = <T extends { quantity: number }>(previous: T[] = [], added: T[] = []) => {
                const byQuantity = new Map(previous.map(row => [row.quantity, row]))
                for (const row of added) {
                  const existing = byQuantity.get(row.quantity)
                  if (existing && JSON.stringify(existing) !== JSON.stringify(row)) throw new Error(`${item.input.primarySku} 的计价依据已变化，请重新生成整批报价`)
                  byQuantity.set(row.quantity, row)
                }
                return [...byQuantity.values()]
              }
              const womens = mergeSamples(option.womensPricingSamples, updated.womensPricingSamples)
              const logistics = mergeSamples(option.logisticsSamples, updated.logisticsSamples)
              sampleUpdates.push(() => { option.womensPricingSamples = womens; option.logisticsSamples = logistics })
            }
          }
          for (const entry of source.entries.filter(entry => entry.index === index)) for (const q of missing) {
            const snapshot = fresh.input.systemQuantityQuotes
            loaded.get(q)!.set(entry.row.channelKey, snapshot?.rows.find(row => row.optionId === entry.option.id)?.prices[snapshot.quantities.indexOf(q)] ?? null)
          }
        }
        // Apply only after every SKU passed, preserving the generated snapshots on failure.
        sampleUpdates.forEach(update => update())
        for (const [quantity, prices] of loaded) extraPrices.set(quantity, prices)
      }
      const quantities = [...extraPrices.keys()]
      system.value = { quantities, rows: source.entries.map(entry => ({ optionId: entry.row.channelKey, prices: quantities.map(q => extraPrices.get(q)!.get(entry.row.channelKey) ?? null) })) }
    }
  } catch (e) { if (!disposed) error.value = e instanceof Error ? e.message : '新增数量计算失败，请重试' }
  finally { if (!disposed) { busy.value = false; emit('busy', false) } }
}
async function capture() {
  if (!editor.value || sourcePending.value) throw new Error(error.value || '报价数量仍在计算，请稍后保存')
  return captureBatchSheet(original, source, await editor.value.captureForSave(), editor.value.captureSheet())
}
onBeforeUnmount(() => { disposed = true })
defineExpose({ capture })
</script>
<template>
  <div class="batch-sheet-editor">
    <p v-if="busy" role="status">正在按各 SKU 的成本与重量计算新增数量…</p>
    <p v-if="error" role="alert">{{ error }} <button @click="quantitiesChanged(requested)">重试数量计算</button></p>
    <CustomerQuoteSheet ref="editor" current-quote batch-average :rows="source.rows" :countries="[]" :salesperson="original.sheet.agent" context-key="batch-sheet" :custom-quantity="0" :bundle="false" :source-pending="sourcePending" :initial-quote="source.initialQuote" :initial-system-quote="system" :initial-sheet="source.initialSheet" record-mode paginated hide-back-to-top preserve-prices-while-pending quote-mode="single" :quantity-help="calculateItem ? '新增数量会按各 SKU 分别计算；可编辑客户报价、图片、行列和渠道平均报价。' : undefined" @quantities-change="quantitiesChanged" />
  </div>
</template>
<style scoped>
.batch-sheet-editor :deep(.customer-sheet){margin:12px 0}.batch-sheet-editor>p{padding:10px;color:#805c2d;background:#fff8ed}.batch-sheet-editor :deep(.sheet-editor-scroll){max-height:650px}
</style>
