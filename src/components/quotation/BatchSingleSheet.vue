<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { customerQuoteSheetTsv, quoteSheetTextTable, type CustomerQuoteSheet } from '@/data/customerQuoteSheet'
import { copyQuoteSheetImage, renderCustomerQuoteSheet, type QuoteSheetImage } from '@/services/customerQuoteSheetRenderer'
const props = defineProps<{ sheet: CustomerQuoteSheet; readOnly?: boolean }>()
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
  <section class="batch-sheet">
    <div v-if="!readOnly" class="toolbar"><button :disabled="busy" @click="preview">{{ busy ? '正在生成…' : '预览报价图片' }}</button><button @click="copy('data')">复制报价数据</button><button v-if="images.length === 1" @click="copy('image')">复制报价图片</button></div>
    <p v-if="error" role="alert">{{ error }}</p>
    <div class="table-scroll"><table><thead><tr><th v-for="(cell,i) in quoteSheetTextTable(sheet)[0]" :key="i">{{ cell }}</th></tr></thead><tbody><tr v-for="(row,i) in quoteSheetTextTable(sheet).slice(1)" :key="i"><td v-for="(cell,j) in row" :key="j">{{ cell }}</td></tr></tbody></table></div>
    <div v-for="(image,i) in images" :key="image.url" class="image"><a :href="image.url" :download="`批量单品报价-${i+1}.png`">下载第 {{ i+1 }} 页高清图片</a><img :src="image.url" :alt="`合并报价单第${i+1}页`"></div>
  </section>
</template>
<style scoped>
.toolbar{display:flex;gap:10px;flex-wrap:wrap;margin:14px 0}button,a{font-size:13px}button{padding:10px 16px;background:white;border:1px solid #ddd;border-radius:6px;cursor:pointer}button:first-child{background:#ff9000;color:white;border:0}button:disabled{opacity:.5}.table-scroll{overflow:auto}table{width:100%;border-collapse:collapse;font:13px Arial,sans-serif;white-space:nowrap}th{background:#f8801b;color:white;padding:12px}td{padding:12px;border:1px solid #e4e4e4;text-align:center}tbody tr:nth-child(even){background:#fff3e7}.image{margin-top:20px}.image img{display:block;width:100%;margin-top:10px}p{color:#b33}
</style>
