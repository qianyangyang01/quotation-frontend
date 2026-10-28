<script setup lang="ts">
import { onMounted, onUnmounted, ref, shallowRef } from 'vue'
import LogisticsPriceChanges from '@/components/logistics/LogisticsPriceChanges.vue'
import LogisticsPriceDisplayBoundary from '@/components/logistics/LogisticsPriceDisplayBoundary.vue'
import {assertPriceSnapshot} from '@/data/logisticsPriceSnapshotValidation'
import type { RealPriceSnapshot } from '@/data/logisticsRealPriceHistory'
import {subscribeLogisticsPriceChanges,type LogisticsPriceEvent} from '@/data/logisticsPriceEvents'
import {request} from '@/services/http'
const props=withDefaults(defineProps<{source?:'preview'|'system'}>(),{source:'preview'})
const snapshot = shallowRef<RealPriceSnapshot | null>(null)
const refreshing = ref(false)
const error = ref('')
const checkedAt = ref('')
let timer: ReturnType<typeof setTimeout> | undefined
let disposed = false
let controller: AbortController | undefined
let unsubscribe: (()=>void) | undefined
let pendingEvent: LogisticsPriceEvent | undefined
const emit = defineEmits<{ workspace: [mode: 'base' | 'rules'] }>()
function scheduleEventRefresh() {
  if(disposed||refreshing.value||!pendingEvent)return
  clearTimeout(timer)
  // Coalesce the notifications of one completed import/publication operation.
  timer=setTimeout(()=>{pendingEvent=undefined;void load('sync')},250)
}
async function load(mode: 'cached' | 'sync' | 'manual' = 'cached') {
  if (refreshing.value || disposed) return
  clearTimeout(timer)
  if(mode!=='cached')pendingEvent=undefined
  refreshing.value = true
  error.value = ''
  controller=new AbortController()
  const active=controller
  const timeout=setTimeout(()=>active.abort(new Error('价格展示读取超时，请稍后手动刷新')),props.source==='system'?8000:30000)
  const aborted=new Promise<never>((_,reject)=>active.signal.addEventListener('abort',()=>reject(active.signal.reason),{once:true}))
  try {
    const result=await Promise.race([read(mode,active.signal),aborted])
    if(disposed||active.signal.aborted)return
    if(result.data){
      assertPriceSnapshot(result.data)
      if(!snapshot.value||snapshot.value.sourceRevision!==result.data.sourceRevision||(props.source==='preview'&&snapshot.value.capturedAt!==result.data.capturedAt))snapshot.value=result.data
    }
    if(result.checkedAt)checkedAt.value=result.checkedAt
  } catch (e) { if(!disposed)error.value = e instanceof Error ? e.message : '价格展示读取失败，请稍后手动刷新' }
  finally { clearTimeout(timeout);refreshing.value = false;scheduleEventRefresh() }
}
async function read(mode:'cached'|'sync'|'manual',signal:AbortSignal):Promise<{data?:RealPriceSnapshot;checkedAt?:string}>{
  if(props.source==='system'){
    const data=await request<RealPriceSnapshot>('/logistics/price-history',{signal})
    if(!data?.sourceRevision)throw new Error('价格展示数据有误，请稍后手动刷新')
    return {data,checkedAt:data.capturedAt}
  }
  const suffix=mode==='cached'?'':(mode==='manual'?'/refresh':'/sync')+'?capturedAt='+encodeURIComponent(snapshot.value?.capturedAt||'')
  const response=await fetch('/__local-logistics/history'+suffix,{
    method:mode==='cached'?'GET':'POST',headers:{'X-Local-Logistics':'read-only'},cache:'no-store',signal,
  })
  if(!response.ok)throw new Error(`价格展示读取失败（${response.status}），请稍后手动刷新`)
  if(response.status===204)return {checkedAt:response.headers.get('X-Logistics-Checked-At')||undefined}
  const data=await response.json()
  return {data,checkedAt:mode!=='cached'?response.headers.get('X-Logistics-Checked-At')||data?.capturedAt:undefined}
}

onMounted(async() => {
  unsubscribe=subscribeLogisticsPriceChanges(event=>{pendingEvent=event;scheduleEventRefresh()})
  await load()
  // A saved completion event already covered by the cached snapshot needs no remote read.
  if(pendingEvent&&snapshot.value&&pendingEvent.at<=Date.parse(snapshot.value.capturedAt)){pendingEvent=undefined;clearTimeout(timer)}
})
onUnmounted(()=>{
  disposed=true;clearTimeout(timer);controller?.abort();unsubscribe?.()
})
</script>
<template>
  <LogisticsPriceDisplayBoundary @workspace="emit('workspace', $event)">
  <LogisticsPriceChanges v-if="snapshot" :snapshot="snapshot" :refreshing="refreshing" :error="error" :checked-at="checkedAt" @refresh="load('manual')" @workspace="emit('workspace', $event)" />
  <div v-else style="padding:40px" role="status"><button @click="emit('workspace','rules')">返回运费规则列表</button><p>{{error || '正在加载真实渠道及已发布历史价格…'}}</p><button v-if="error" @click="load('manual')">刷新最新价格</button><p v-if="error&&source==='preview'">请使用本地价格服务：127.0.0.1:5210</p></div>
  </LogisticsPriceDisplayBoundary>
</template>
