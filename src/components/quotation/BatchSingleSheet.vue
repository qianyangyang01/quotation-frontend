<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { customerQuoteSheetTsv, quoteSheetTextTable, type CustomerQuoteSheet } from '@/data/customerQuoteSheet'
import { copyQuoteSheetImage, renderCustomerQuoteSheet, type QuoteSheetImage } from '@/services/customerQuoteSheetRenderer'
const props = defineProps<{ sheet: CustomerQuoteSheet; readOnly?: boolean; paginated?: boolean }>()
const search = ref(''), page = ref(0), pageSize = ref(12)
const filtered = computed(() => props.sheet.rows.filter(row => !props.paginated || (row.sku || '').toUpperCase().includes(search.value.trim().toUpperCase())))
const pageCount = computed(() => Math.max(1, Math.ceil(filtered.value.length / pageSize.value)))
const displayTable = computed(() => quoteSheetTextTable({ ...props.sheet, rows: props.paginated ? filtered.value.slice(page.value * pageSize.value, (page.value + 1) * pageSize.value) : props.sheet.rows }))
watch([search, pageSize, () => props.sheet], () => { page.value = 0 })
watch(pageCount, count => { page.value = Math.min(page.value, count - 1) })
const images = ref<Array<QuoteSheetImage & { url: string }>>([]), busy = ref(false), error = ref('')
let generation = 0
function clear() { for (const image of images.value) URL.revokeObjectURL(image.url); images.value = [] }
watch(() => props.sheet, () => { generation++; clear(); error.value = ''; busy.value = false })
async function preview() {
  const token = ++generation; busy.value = true; error.value = ''; clear()
  try { const result = await renderCustomerQuoteSheet(props.sheet, () => token !== generation); if (token === generation) images.value = result.map(image => ({ ...image, url: URL.createObjectURL(image.blob) })) }
  catch (e) { if (token === generation) error.value = e instanceof Error ? e.message : '图片生成失败，可先复制报价数据' }
  finally { if (token === generation) busy.value = false }
}
async function copy(kind: 'data' | 'image') {
  error.value = ''
  try { if (kind === 'data') await navigator.clipboard.writeText(customerQuoteSheetTsv(props.sheet)); else if (images.value[0]) await copyQuoteSheetImage(images.value[0].blob) }
  catch (e) { error.value = e instanceof Error ? e.message : '复制失败，请使用下载图片' }
}
onBeforeUnmount(() => { generation++; clear() })
</script>
<template>
  <section class="batch-sheet" :class="{ paginated }">
    <div v-if="!readOnly" class="toolbar"><button :disabled="busy" @click="preview">{{ busy ? '正在生成…' : '预览报价图片' }}</button><button @click="copy('data')">复制报价数据</button><button v-if="images.length === 1" @click="copy('image')">复制报价图片</button></div>
    <p v-if="error" role="alert">{{ error }}</p>
    <div v-if="paginated" class="filter"><input v-model="search" type="search" placeholder="搜索 SKU" aria-label="搜索报价单 SKU"><span>共 {{ sheet.rows.length }} 行明细 · 搜索与分页仅影响屏幕显示，复制和图片包含全部明细</span></div>
    <div class="table-scroll"><table><thead><tr><th v-for="(cell,i) in displayTable[0]" :key="i">{{ cell }}</th></tr></thead><tbody><tr v-for="(row,i) in displayTable.slice(1)" :key="i"><td v-for="(cell,j) in row" :key="j">{{ cell }}</td></tr></tbody></table></div>
    <p v-if="paginated && !filtered.length" class="empty">未找到匹配的 SKU</p>
    <nav v-if="paginated" class="pagination" aria-label="合并报价单分页"><span>匹配 {{ filtered.length }} 行 · 当前显示 {{ filtered.length ? page * pageSize + 1 : 0 }}–{{ Math.min((page + 1) * pageSize, filtered.length) }} 行</span><label>每页 <select v-model.number="pageSize" aria-label="每页报价明细数"><option :value="12">12</option><option :value="30">30</option><option :value="60">60</option></select> 行</label><div><button :disabled="page === 0" @click="page--">上一页</button><span>{{ page + 1 }} / {{ pageCount }}</span><button :disabled="page + 1 >= pageCount" @click="page++">下一页</button></div></nav>
    <div v-for="(image,i) in images" :key="image.url" class="image"><a :href="image.url" :download="`批量单品报价-${i+1}.png`">下载第 {{ i+1 }} 页高清图片</a><img :src="image.url" :alt="`合并报价单第${i+1}页`"></div>
  </section>
</template>
<style scoped>
.batch-sheet.paginated th{background:#fff0e2;color:#a44e00;border:1px solid #e6e0da;font-weight:500}.batch-sheet.paginated td{border-color:#e0e6ed}.batch-sheet.paginated tbody tr:nth-child(even){background:white}.batch-sheet.paginated .pagination button{background:white;color:#29445f;border:1px solid #d5dfea}
.filter,.pagination,.pagination>div{display:flex;align-items:center;gap:14px;flex-wrap:wrap}.filter{margin:14px 0;font-size:12px;color:#718198}.filter input{padding:9px 12px;border:1px solid #d5dfea;border-radius:5px;max-width:100%}.pagination{justify-content:space-between;margin:16px 0;font-size:12px;color:#718198}.pagination button{padding:7px 12px}.pagination select{padding:6px;border:1px solid #d5dfea;border-radius:5px}.empty{color:#718198}
.toolbar{display:flex;gap:10px;flex-wrap:wrap;margin:14px 0}button,a{font-size:13px}button{padding:10px 16px;background:white;border:1px solid #ddd;border-radius:6px;cursor:pointer}button:first-child{background:#ff9000;color:white;border:0}button:disabled{opacity:.5}.table-scroll{overflow:auto}table{width:100%;border-collapse:collapse;font:13px Arial,sans-serif;white-space:nowrap}th{background:#f8801b;color:white;padding:12px}td{padding:12px;border:1px solid #e4e4e4;text-align:center}tbody tr:nth-child(even){background:#fff3e7}.image{margin-top:20px}.image img{display:block;width:100%;margin-top:10px}p{color:#b33}
</style>
