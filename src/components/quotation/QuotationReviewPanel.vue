<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { financeReviewLabel, type QuotationRecord, type QuotationReviewState, type ReviewAction } from '@/data/quotationRecords'
const props=defineProps<{ record:QuotationRecord; state:QuotationReviewState; account:string; canReview:boolean; admin:boolean; busy:boolean; compact?:boolean }>()
const emit=defineEmits<{ action:[value:ReviewAction]; open:[]; reload:[] }>()
const note=ref('')
watch(()=>[props.record.id,props.state.financeReviewStatus,props.state.financeReviewClaimedAccount],()=>{note.value=''})
const active=computed(()=>props.state.financeReviewStatus==='reviewing')
const own=computed(()=>active.value&&props.state.financeReviewClaimedAccount===props.account)
const stale=computed(()=>(props.state._version??0)>(props.record._version??0))
const claimChanged=computed(()=>(props.state._reviewVersion??0)!==(props.record._reviewVersion??0))
const time=(value?:string)=>value?new Date(value).toLocaleString('zh-CN',{timeZone:'Asia/Shanghai'}):''
</script>

<template>
  <section class="review-panel" :class="{compact}" @click.stop>
    <strong class="finance-review" :class="state.financeReviewStatus" role="status">{{ active ? `${state.financeReviewClaimedBy || '其他财务'}审核中` : financeReviewLabel(state.financeReviewStatus) }}</strong>
    <small v-if="active">开始于 {{ time(state.financeReviewStartedAt) }}</small>
    <small v-else-if="state.financeReviewedBy">{{ state.financeReviewedBy }} · {{ time(state.financeReviewedAt) }}</small>
    <p v-if="!compact&&state.financeReviewNote">审核备注：{{ state.financeReviewNote }}</p>
    <template v-if="canReview">
      <p v-if="!compact&&(stale||claimChanged)" class="changed" role="alert">{{ stale?'报价内容已更新':'审核占用已变化' }}，请重新加载并核对后完成审核。<button type="button" :disabled="busy" @click="emit('reload')">重新加载详情</button></p>
      <button v-if="!active" type="button" :disabled="busy" @click="emit('action',{action:'claim'})">{{ state.financeReviewStatus==='pending' ? '开始审核' : '重新审核' }}</button>
      <button v-else-if="compact" type="button" :disabled="busy" @click="emit('open')">{{ own?'继续审核':'查看详情' }}</button>
      <template v-else>
        <template v-if="own">
          <label>审核备注<textarea v-model="note" maxlength="500" placeholder="可填写审核意见；价格有误时必须填写原因" /></label>
          <div class="actions">
            <button type="button" :disabled="busy||stale||claimChanged" @click="emit('action',{action:'complete',financeReviewStatus:'channel-exempt',note})">同渠道免审 · 可报价</button>
            <button type="button" class="approve" :disabled="busy||stale||claimChanged" @click="emit('action',{action:'complete',financeReviewStatus:'approved',note})">审核完成 · 可报价</button>
            <button type="button" :disabled="busy||stale||claimChanged||!note.trim()" @click="emit('action',{action:'complete',financeReviewStatus:'rejected',note})">审核完成 · 价格有误</button>
            <button type="button" :disabled="busy||claimChanged" @click="emit('action',{action:'cancel'})">取消审核</button>
          </div>
          <small>关闭页面会保留审核占用，稍后可继续；不再处理请取消审核。</small>
        </template>
        <template v-else-if="admin">
          <label>解除原因<textarea v-model="note" maxlength="500" placeholder="请填写解除占用的原因" /></label>
          <button type="button" :disabled="busy||claimChanged||!note.trim()" @click="emit('action',{action:'release',note})">解除占用</button>
        </template>
        <small v-else>由当前审核人完成或取消；需要交接时请联系超级管理员。</small>
      </template>
    </template>
  </section>
</template>

<style scoped>
.review-panel{display:flex;flex-direction:column;align-items:flex-start;gap:8px;min-width:0;color:#566376;font-size:13px}.review-panel.compact{width:100%;gap:6px}.finance-review{display:block;box-sizing:border-box;padding:7px 9px;border:1px solid #d9e1e7;border-radius:6px;background:#f7f9fb;font-size:13px;line-height:1.5;white-space:normal}.compact .finance-review{width:100%}.finance-review.reviewing{color:#916000;background:#fff6db;border-color:#ebcc78}.finance-review.channel-exempt{color:#17659b;background:#edf6ff;border-color:#9bc8e8}.finance-review.approved{color:#078347;background:#e7f7ee;border-color:#9ad8b4}.finance-review.rejected{color:#b52b25;background:#fff0ef;border-color:#efb0ac}button{cursor:pointer;border:1px solid #d7dee7;border-radius:6px;background:white;padding:7px 10px;font-size:13px;color:#374151}button:disabled{opacity:.5;cursor:not-allowed}.approve{background:#078347;color:white;border-color:#078347}.actions{display:flex;flex-wrap:wrap;gap:8px}label{display:flex;flex-direction:column;gap:5px;width:100%}textarea{box-sizing:border-box;width:100%;min-height:60px;border:1px solid #d7dee7;border-radius:6px;padding:8px;font:inherit}small{line-height:1.5;overflow-wrap:anywhere}.changed{color:#b15b09}p{margin:0;line-height:1.6;white-space:pre-wrap}
</style>
