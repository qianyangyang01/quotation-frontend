<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { api, idempotencyKey } from '@/services/http'
import { normalizeQuotationRecord, type QuotationRecord } from '@/data/quotationRecords'
import { currentAuthUser } from '@/data/authStore'
import { loadFobQuoteProduct, type FobSmallOrderPolicy } from '@/services/fobQuotation'
import { fobBatchLookupError, fobBatchSnapshot } from '@/services/fobBatchQuotation'
import { validateFobQuotation, type FobQuotationSnapshot } from '@/services/fobQuotationRecords'
import { correctionChanged, fobItemReviews, submitFobCorrections } from '@/services/fobCorrections'
import { localQuoteDate } from '@/data/customerQuoteSheet'
import { refreshReviewNotifications, unreadReview, acknowledgeReview } from '@/data/reviewNotifications'
const props=defineProps<{id:string;rate:number;policy:FobSmallOrderPolicy|null;financePending?:boolean;financeError?:string}>()
const emit=defineEmits<{close:[]}>()
interface Line {index:number;old:FobQuotationSnapshot;reason:string;quantity:string;selected:boolean;loading:boolean;error:string;result?:FobQuotationSnapshot;confirmUnchanged?:boolean}
const dialog=ref<HTMLDialogElement>(),record=ref<QuotationRecord>(),lines=ref<Line[]>([]),error=ref(''),loading=ref(true),querying=ref(false),saving=ref(false),submitted=ref(false),key=ref('')
let generation=0,controller:AbortController|undefined,alive=true
const financeIssue=computed(()=>props.financePending?'财务参数加载中，请稍候':props.financeError||(!props.policy||props.rate<=0?'财务参数未就绪，请刷新后查询':''))
function unchanged(line:Line){return !!line.result&&!correctionChanged(line.old,line.result)}
const ready=computed(()=>lines.value.filter(l=>l.selected&&l.result&&!l.error&&!l.loading&&(!unchanged(l)||l.confirmUnchanged)))
function invalidate(line:Line){line.result=undefined;line.confirmUnchanged=false;line.error='修改数量后请重新查询';key.value=''}
watch(()=>[props.rate,JSON.stringify(props.policy)],()=>{lines.value.forEach(l=>{l.result=undefined;l.error='财务参数已变化，请重新查询'});key.value='';generation++;controller?.abort();querying.value=false;lines.value.forEach(l=>l.loading=false)})
async function load(){
  loading.value=true;error.value='';submitted.value=false;key.value='';record.value=undefined;lines.value=[]
  try{
    const value=normalizeQuotationRecord(await api.get<QuotationRecord>(`/quotations/${props.id}`,{cache:'no-store',signal:AbortSignal.timeout(30000)}))!
    if(!alive)return
    if(value.salespersonAccount!==currentAuthUser.value.account)throw new Error('只能处理自己的退回报价')
    if(value.lifecycleState!=='active'||value.fob?.schemaVersion!==2)throw new Error('此报价当前不能重新提交')
    record.value=value
    lines.value=fobItemReviews(value).filter(s=>s.status==='rejected').map(s=>({index:s.index,old:value.fob!.items![s.index]!,reason:s.reason,quantity:String(value.fob!.items![s.index]!.quantity),selected:true,loading:false,error:''}))
    const notification=unreadReview(props.id);await nextTick();if(alive&&notification)void acknowledgeReview(notification,value._reviewVersion??0)
    if(!lines.value.length)error.value='当前没有待处理退回项，可能已由其他页面提交。'
  }catch(e){if(alive)error.value=(e as Error).message}finally{if(alive)loading.value=false}
}
async function query(only?:Line){
  if(querying.value||saving.value||financeIssue.value||loading.value||!record.value)return
  error.value='';submitted.value=false;key.value='';querying.value=true
  const run=++generation;controller=new AbortController();const signal=controller.signal,targets=only?[only]:lines.value
  targets.forEach(l=>{l.result=undefined;l.confirmUnchanged=false;l.error='';l.loading=true})
  let cursor=0
  async function worker(){while(cursor<targets.length&&run===generation){const line=targets[cursor++]!
    try{
      const product=await loadFobQuoteProduct(line.old.product.sku,line.old.product.source,AbortSignal.any([signal,AbortSignal.timeout(20000)]),true)
      if(run!==generation||!alive)return
      const batch=fobBatchSnapshot([{id:line.index+1,sku:product.sku,quantity:line.quantity,selected:true,formatError:'',product}],line.old.displayMode,props.rate,props.policy!,{title:record.value!.fob!.sheet.title||'JerryFulfillment Quote Sheet',agent:record.value!.salespersonName,date:localQuoteDate(),whatsapp:record.value!.fob!.sheet.whatsapp||''})
      const canonical=await validateFobQuotation(record.value!.customerName,batch.items![0]!)
      if(run!==generation||!alive)return
      line.result=canonical
    }catch(e){if(run===generation&&alive)line.error=fobBatchLookupError(e)}finally{if(run===generation&&alive)line.loading=false}
  }}
  await Promise.all(Array.from({length:Math.min(4,targets.length)},worker))
  if(run===generation&&alive)querying.value=false
}
async function submit(){
  if(!record.value||saving.value||querying.value||!ready.value.length||financeIssue.value)return
  saving.value=true;error.value='';key.value ||= idempotencyKey('fob-correction')
  const items=ready.value.map(l=>({index:l.index,snapshot:l.result!,confirmUnchanged:unchanged(l)&&!!l.confirmUnchanged}))
  try{
    const saved=await submitFobCorrections(record.value,items,key.value)
    if(!alive)return
    record.value=saved;lines.value=lines.value.filter(l=>!items.some(i=>i.index===l.index));submitted.value=true;key.value=''
    void refreshReviewNotifications()
  }catch(e){if(alive)error.value=(e as Error).message+'。未确认成功前不要重复新建报价；可原样重试，或刷新记录确认最新状态。'}finally{if(alive)saving.value=false}
}
function close(){if(saving.value)return;generation++;controller?.abort();dialog.value?.close();emit('close')}
onMounted(async()=>{await nextTick();dialog.value?.showModal();await load()})
onBeforeUnmount(()=>{alive=false;generation++;controller?.abort()})
</script>
<template>
  <dialog ref="dialog" class="correction-dialog" aria-label="批量粘贴报价 · 异常项重新报价" @cancel.prevent="close">
    <header><h2>批量粘贴报价 · 异常项重新报价</h2><button :disabled="saving" aria-label="关闭并返回报价记录" @click="close">返回报价记录</button></header>
    <p v-if="record">客户：{{ record.customerName }} · 原报价：{{ record.no }} · 修订 {{ record.fobRevision||0 }} · 本次仅处理 {{ lines.length }} 项</p>
    <p>仅重新查询退回项；其他商品保留原价和审核结果。若为误退回，查询后勾选“已核实无误”，即可按原价重新提交审核。</p>
    <p v-if="loading" role="status">正在读取最新退回项…</p><p v-if="error" role="alert" class="error">{{ error }}</p><p v-if="financeIssue" role="alert" class="error">{{ financeIssue }}</p>
    <p v-if="submitted" role="status" class="success">修正项已提交复核，尚未审核通过。剩余退回项可继续处理；原始报价完整保留。</p>
    <div class="scroll"><table><thead><tr><th>选择</th><th>SKU</th><th>数量</th><th>退回原因</th><th>原最终价 $<small>报关 / 不报关</small></th><th>重算后最终价 $<small>报关 / 不报关</small></th><th>状态 / 操作</th></tr></thead><tbody>
      <tr v-for="line in lines" :key="line.index"><td><input v-model="line.selected" type="checkbox" :disabled="saving||querying" :aria-label="`提交 ${line.old.product.sku}`" @change="key=''"/></td><td>{{ line.old.product.sku }}<small>{{ line.old.product.source==='fob'?'FOB资料':'新采购资料' }}</small></td><td><input v-model="line.quantity" :disabled="saving||querying||line.old.displayMode==='tiers'" :aria-label="`${line.old.product.sku} 报价数量`" inputmode="numeric" @input="invalidate(line)"><small v-if="line.old.displayMode==='tiers'">全部阶梯</small></td><td>{{ line.reason }}</td><td><div v-for="(q,i) in (line.old.displayMode==='tiers'?line.old.ranges:[line.old.current])" :key="i"><small>{{ q?.minQty }}{{ q?.maxQty===q?.minQty?'':q?.maxQty==null?'+':'–'+q.maxQty }}{{ q?.unit }}</small>{{ q?.declaredUsd }} / {{ q?.undeclaredUsd }}</div></td><td class="price"><template v-if="line.result"><div v-for="(q,i) in (line.result.displayMode==='tiers'?line.result.ranges:[line.result.current])" :key="i"><small>{{ q?.minQty }}{{ q?.maxQty===q?.minQty?'':q?.maxQty==null?'+':'–'+q.maxQty }}{{ q?.unit }}</small>{{ q?.declaredUsd }} / {{ q?.undeclaredUsd }}</div><small>汇率 {{ line.result.rate }}</small></template><span v-else>—</span></td><td><span :class="line.error?'error':'success'">{{ line.loading?'正在查询…':line.error|| (unchanged(line)?'资料未变化；如核实无误，可按原价重新提交审核':line.result?'已重新查询，待提交':'待重新查询') }}</span><label v-if="unchanged(line)" class="unchanged-confirm"><input v-model="line.confirmUnchanged" type="checkbox" :disabled="saving||querying" :aria-label="`${line.old.product.sku} 已核实无误，按原价重新提交审核`" @change="key=''" />已核实无误，按原价重新提交审核</label><button :disabled="saving||querying||loading||!!financeIssue" @click="query(line)">重新查询</button></td></tr>
    </tbody></table></div>
    <footer><button :disabled="saving||querying||loading" @click="load">刷新退回记录</button><span>已选有效 {{ ready.length }} 项；未成功项继续保留待处理</span><button :disabled="saving||querying||loading||!lines.length||!!financeIssue" @click="query()">{{ querying?'查询中…':'重新查询异常项' }}</button><button class="primary" :disabled="saving||querying||loading||!ready.length||!!financeIssue" @click="submit">{{ saving?'提交中…':`提交${ready.length}项重新审核` }}</button></footer>
  </dialog>
</template>
<style scoped>
.correction-dialog{box-sizing:border-box;width:min(1400px,96vw);max-height:94vh;overflow:auto;border:1px solid #dce5ee;border-radius:10px;padding:22px;color:#263c50}.correction-dialog::backdrop{background:#172c4166}header,footer{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap}h2{font-size:21px;margin:0}p{font-size:13px;line-height:1.6}.scroll{overflow:auto;margin:18px 0}table{width:100%;border-collapse:collapse;font-size:13px}td,th{border:1px solid #dce5ee;padding:12px;text-align:left}th{background:#f2f6fa}td{min-width:100px}td:first-child{min-width:32px}small{display:block;color:#72869a;font-size:11px;margin-top:5px}.price{background:#edf6ff;font-weight:700;color:#145586}.unchanged-confirm{display:flex;align-items:flex-start;gap:6px;margin-top:10px;line-height:1.5;color:#263c50}.unchanged-confirm input{flex:none;margin-top:3px}.error{color:#ad302b}.success{color:#16804b}input,button{font:inherit;padding:8px;border:1px solid #ccd9e4;border-radius:5px;background:#fff}input:not([type=checkbox]){width:80px}button{cursor:pointer}button:disabled{opacity:.5;cursor:not-allowed}.primary{background:#fa8a12;color:#fff;border-color:#fa8a12}td button{display:block;margin-top:8px}footer{position:sticky;bottom:-22px;background:#fff;padding:16px 0;border-top:1px solid #dce5ee;font-size:13px}
</style>
