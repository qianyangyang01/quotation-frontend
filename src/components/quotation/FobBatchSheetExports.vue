<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'
import type { CustomerQuoteSheet } from '@/data/customerQuoteSheet'
import { fobSheetPrintHtml, fobSheetWorkbook } from '@/services/fobBatchExports'
import { renderCustomerQuoteSheet } from '@/services/customerQuoteSheetRenderer'
import JSZip from 'jszip'
const props = defineProps<{sheet:CustomerQuoteSheet}>()
const busy = ref(false), error = ref('')
let frame: HTMLIFrameElement | undefined, disposed = false
function download(blob: Blob, name: string) { const url=URL.createObjectURL(blob),a=document.createElement('a');a.href=url;a.download=name;a.click();setTimeout(()=>URL.revokeObjectURL(url),1000) }
async function exportFile(kind:'xlsx'|'images') {
  busy.value=true;error.value=''
  try {
    if(kind==='xlsx') { const blob=await fobSheetWorkbook(props.sheet);if(!disposed)download(blob,'FOB报价单.xlsx') }
    else {const images=await renderCustomerQuoteSheet(props.sheet,()=>disposed),zip=new JSZip();for(let i=0;i<images.length;i++)zip.file(`FOB-${String(i+1).padStart(3,'0')}.png`,images[i]!.blob);const blob=await zip.generateAsync({type:'blob'});if(!disposed)download(blob,'FOB报价单分页图片.zip')}
  } catch(e) {error.value=e instanceof Error?e.message:'导出失败，请重试'} finally {busy.value=false}
}
function print() {
  error.value='';frame?.remove();frame=document.createElement('iframe');frame.style.cssText='position:fixed;width:0;height:0;border:0';frame.title='FOB报价单打印';frame.srcdoc=fobSheetPrintHtml(props.sheet)
  frame.onload=()=>{try{frame?.contentWindow?.focus();frame?.contentWindow?.print()}catch{error.value='无法打开打印窗口，请使用Excel或图片导出'}}
  document.body.append(frame)
}
onBeforeUnmount(()=>{disposed=true;frame?.remove()})
</script>
<template><div class="exports"><button :disabled="busy" @click="exportFile('xlsx')">导出Excel</button><button :disabled="busy" @click="print">打印 / 另存PDF</button><button :disabled="busy" @click="exportFile('images')">下载分页图片</button><span v-if="busy" role="status">正在导出…</span><span v-if="error" role="alert">{{ error }}</span></div></template>
<style scoped>.exports{display:flex;gap:10px;flex-wrap:wrap;margin:12px 0;font-size:12px}.exports button{padding:9px 12px;border:1px solid #cfdeeb;border-radius:6px;background:white;color:#235681;cursor:pointer}.exports button:disabled{opacity:.5}</style>
