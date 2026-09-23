<script setup lang="ts">
import { ref, watch } from 'vue'
import { loadQuotationReviewHistory, financeReviewLabel, type QuotationReviewEvent } from '@/data/quotationRecords'
const props=defineProps<{ id:string; version?:number; account:string }>()
const rows=ref<QuotationReviewEvent[]>([]),error=ref('')
let generation=0
watch(()=>[props.id,props.version,props.account],async()=>{
  const current=++generation;rows.value=[];error.value=''
  try {const result=await loadQuotationReviewHistory(props.id);if(current===generation)rows.value=result.slice().reverse()}
  catch {if(current===generation)error.value='审核历史加载失败，请重新打开详情重试'}
},{immediate:true})
const labels:Record<string,string>={claim:'开始审核',cancel:'取消审核',release:'管理员解除占用',complete:'审核完成','content-changed':'报价变更，需重新核对','legacy-review':'历史审核结果'}
</script>
<template>
  <section class="review-history"><b>财务审核记录</b><p v-if="error" role="alert">{{ error }}</p><p v-else-if="!rows.length">暂无新增审核操作；此前审核记录保留在修改记录中。</p><article v-for="row in rows" :key="row.id"><span>{{ row.actorName }}（{{ row.actorAccount }}） · {{ row.at ? new Date(row.at).toLocaleString('zh-CN',{timeZone:'Asia/Shanghai'}) : '历史时间未保存' }}</span><strong>{{ labels[row.action] || row.action }} · {{ financeReviewLabel(row.after) }}</strong><p v-if="row.note">{{ row.note }}</p></article></section>
</template>
<style scoped>
.review-history{padding:16px 24px;border-top:1px solid #e2e7ee;color:#526071;font-size:13px}.review-history article{display:flex;flex-direction:column;gap:6px;border-top:1px solid #edf0f4;padding:12px 0}.review-history p{white-space:pre-wrap;line-height:1.5}.review-history b{display:block;margin-bottom:12px;color:#25344a}
</style>
