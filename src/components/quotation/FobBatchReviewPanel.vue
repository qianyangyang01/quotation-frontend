<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { QuotationRecord, QuotationReviewState, ReviewAction } from '@/data/quotationRecords'
import { fobItemReviews, reviewFobItems } from '@/services/fobCorrections'
import { canViewPurchaseCost } from '@/data/quotationVisibility'
const props=defineProps<{record:QuotationRecord; state:QuotationReviewState; account:string; canReview:boolean; busy:boolean}>()
const emit=defineEmits<{saved:[record:QuotationRecord]; action:[action:ReviewAction]; reload:[]}>()
const checked=ref<number[]>([]), reasons=ref<Record<number,string>>({}), error=ref(''), sending=ref(false), approveOthers=ref(true)
const states=computed(()=>fobItemReviews(props.record))
const own=computed(()=>props.state.financeReviewStatus==='reviewing'&&props.state.financeReviewClaimedAccount===props.account)
const stale=computed(()=>props.record._reviewVersion!==props.state._reviewVersion||props.record._version!==props.state._version)
const disabled=computed(()=>sending.value||props.busy||!own.value||stale.value)
watch(()=>[props.record.id,props.record._reviewVersion],()=>{checked.value=[];reasons.value=Object.fromEntries(states.value.map(s=>[s.index,s.reason]));error.value=''}, {immediate:true})
async function review(action:'return'|'approve') {
  if(disabled.value||!checked.value.length)return
  const items=checked.value.map(index=>({index,reason:(reasons.value[index]||'').trim()}))
  if(action==='return'&&items.some(i=>!i.reason)){error.value='请为每个退回SKU填写原因';return}
  sending.value=true;error.value=''
  try{emit('saved',await reviewFobItems(props.record,action,items,approveOthers.value))}catch(e){error.value=(e as Error).message}finally{sending.value=false}
}
const labels={pending:'待审核',approved:'已通过',rejected:'已退回'}
</script>
<template>
  <section class="item-review" aria-label="批量FOB逐项审核">
    <header><b>逐项审核 · {{ record.fob?.items?.length }}项</b><span>已通过 {{ states.filter(s=>s.status==='approved').length }} · 已退回 {{ states.filter(s=>s.status==='rejected').length }} · 待审核 {{ states.filter(s=>s.status==='pending').length }}</span></header>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="stale" role="alert">报价或审核已变化，请重新加载后操作。<button @click="emit('reload')">重新加载</button></p>
    <div class="scroll"><table><thead><tr><th v-if="canReview">选择</th><th>SKU / 数量</th><th v-if="canViewPurchaseCost">含票价 / 运费 / 成本 ¥</th><th>最终价 $<small>报关 / 不报关</small></th><th>状态</th><th>退回原因（自定义填写）</th></tr></thead><tbody>
      <tr v-for="(item,index) in record.fob?.items" :key="index"><td v-if="canReview"><input v-model="checked" type="checkbox" :value="index" :disabled="disabled" :aria-label="`选择审核 ${item.product.sku} 第${index+1}项`"></td><td><b>{{ item.product.sku }}</b><small>{{ item.displayMode==='tiers'?'全部阶梯':item.quantity+(item.current?.unit||item.tiers?.[0]?.unit||'件') }} · 汇率 {{ item.rate }}</small></td><td v-if="canViewPurchaseCost"><div v-for="t in item.tiers" :key="t.minQty">{{ t.minQty }}起：{{ t.taxIncludedCny }} / {{ t.freightCny }} / {{ t.costCny }}</div></td><td class="price"><div v-for="(r,i) in (item.displayMode==='tiers'?item.ranges:[item.current])" :key="i"><small>{{ r?.minQty }}{{ r?.maxQty===r?.minQty?'':r?.maxQty==null?'+':'–'+r.maxQty }}{{ r?.unit }}</small>{{ r?.declaredUsd }} / {{ r?.undeclaredUsd }}</div></td><td :class="states[index]?.status">{{ labels[states[index]?.status || 'pending'] }}<small>{{ states[index]?.reviewedBy }}</small></td><td><textarea v-if="canReview&&own" v-model="reasons[index]" maxlength="500" :disabled="disabled" :aria-label="`${item.product.sku} 第${index+1}项退回原因`" placeholder="自行填写该商品的退回原因"/><span v-else>{{ states[index]?.reason || '—' }}</span></td></tr>
    </tbody></table></div>
    <footer v-if="canReview"><template v-if="own"><button :disabled="disabled" @click="checked=states.filter(s=>s.status==='pending').map(s=>s.index)">选择待审核项</button><label><input v-model="approveOthers" type="checkbox" :disabled="disabled">退回时，其余未选的待审核项一并通过</label><button :disabled="disabled||!checked.length" @click="review('return')">退回所选{{ checked.length }}项</button><button :disabled="disabled||!checked.length||checked.some(i=>states[i]?.status==='rejected')" @click="review('approve')">通过所选{{ checked.length }}项</button><button :disabled="disabled" @click="emit('action',{action:'cancel'})">取消审核</button></template><button v-else-if="state.financeReviewStatus!=='reviewing'" :disabled="busy||sending||stale" @click="emit('action',{action:'claim'})">开始逐项审核</button><span v-else>{{ state.financeReviewClaimedBy }}审核中</span></footer>
  </section>
</template>
<style scoped>
.item-review{margin:16px 24px;padding:16px;border:1px solid #dbe4ed;border-radius:8px}header,footer{display:flex;gap:12px;align-items:center;flex-wrap:wrap}header span,small{font-size:12px;color:#718193}small{display:block;margin-top:5px}.scroll{overflow:auto;margin:14px 0}table{border-collapse:collapse;width:100%;font-size:13px}td,th{border:1px solid #dbe4ed;padding:10px;text-align:left}th{background:#f1f5fa}.price{background:#edf5ff;color:#165787;font-weight:bold;white-space:nowrap}textarea{min-width:160px;min-height:52px;font:inherit;max-width:100%;border:1px solid #dbe4ed;border-radius:4px}.rejected,p[role=alert]{color:#ba3530}.approved{color:#18844d}button{border:1px solid #e8aa56;border-radius:5px;background:#fff8ed;padding:8px;color:#945412;cursor:pointer}button:disabled{opacity:.5;cursor:not-allowed}footer label{font-size:12px}
</style>
