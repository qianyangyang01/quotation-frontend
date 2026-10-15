<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import PriceExceptionSelect from './PriceExceptionSelect.vue'
import type { PriceExceptionType } from '@/data/priceExceptions'
import { currentAuthUser, hasPermission } from '@/data/authStore'
import { batchItemReviews, batchReviewLabels, reviewBatchSingle, type BatchReviewResult, type BatchSingleDocument } from '@/services/batchSingleQuotation'
import { idempotencyKey } from '@/services/http'
const props = defineProps<{ document: BatchSingleDocument; selected: number[]; hiddenSelected: number }>()
const emit = defineEmits<{ saved: [document: BatchSingleDocument]; clear: []; reload: [] }>()
const exceptionTypes = ref<Record<number, PriceExceptionType | ''>>({})
const busy = ref(false), error = ref(''), note = ref(''), result = ref<BatchReviewResult>('approved'), reasons = ref<Record<number, string>>({}), common = ref('')
const dialog = ref<HTMLDialogElement>(), returning = ref<number[]>([])
const canReview = computed(() => ['super_admin', 'finance'].includes(currentAuthUser.value.role) && hasPermission('allRecords'))
const own = computed(() => props.document.review?.status === 'reviewing' && props.document.review.claimantAccount === currentAuthUser.value.account)
const rejected = computed(() => props.selected.some(i => batchItemReviews(props.document)[i]?.status === 'rejected'))
const hasUnsavedChanges = computed(() => !!note.value.trim() || !!common.value.trim() || Object.values(reasons.value).some(s => !!s.trim()) || Object.values(exceptionTypes.value).some(Boolean))
defineExpose({ busy, hasUnsavedChanges })
let signature = '', retryKey = ''
watch(() => [props.document.id, props.document._reviewVersion, currentAuthUser.value.account], () => { note.value = ''; common.value = ''; reasons.value = {}; exceptionTypes.value = {}; returning.value = []; dialog.value?.close(); error.value = '' })
function openReturn() { if (busy.value || !own.value || !props.selected.length) return; returning.value = [...props.selected]; error.value = ''; dialog.value?.showModal() }
async function submit(action: 'claim' | 'cancel' | 'complete', returns = false) {
  if (busy.value || !canReview.value || (action !== 'claim' && !own.value)) return
  const indexes = returns ? returning.value : props.selected
  if (action === 'complete' && !indexes.length) return

  if (returns && indexes.some(i => !exceptionTypes.value[i])) { error.value = '请为每个退回SKU选择异常类型'; return }
  const options = action === 'complete' ? { result: returns ? 'rejected' as const : result.value, note: note.value.trim(), items: indexes.map(index => ({ index, reason: returns ? (reasons.value[index] || '').trim() : '', ...(returns && exceptionTypes.value[index] ? { exceptionType: exceptionTypes.value[index] as PriceExceptionType } : {}) })) } : {}
  const next = JSON.stringify({ id: props.document.id, version: props.document._reviewVersion, account: currentAuthUser.value.account, action, options })
  if (signature !== next) { signature = next; retryKey = idempotencyKey('batch-review') }
  const account = currentAuthUser.value.account, id = props.document.id
  busy.value = true; error.value = ''
  try { const saved = await reviewBatchSingle(props.document, action, retryKey, options); if (account !== currentAuthUser.value.account || id !== props.document.id) return; note.value = ''; reasons.value = {}; exceptionTypes.value = {}; returning.value = []; dialog.value?.close(); emit('saved', saved); emit('clear') }
  catch (e) { error.value = e instanceof Error ? e.message : '审核提交失败，请重试' }
  finally { busy.value = false }
}
</script>
<template>
  <section v-if="canReview" class="batch-review-actions">
    <p v-if="error" role="alert">{{ error }} <button :disabled="busy" @click="emit('reload')">重新加载</button></p>
    <template v-if="own">
      <div class="selection"><b>已选 {{ selected.length }} 个SKU</b><button :disabled="busy" @click="emit('clear')">清空选择</button><span v-if="hiddenSelected">其中 {{ hiddenSelected }} 项不在本页，仍会提交</span><button class="cancel" :disabled="busy" @click="submit('cancel')">取消审核</button></div>
      <div class="action-row"><fieldset :disabled="busy"><legend>审核结论</legend><label v-for="value in (['channel-exempt','logistics-exempt','approved','rejected'] as const)" :key="value" :class="{ active: result === value }"><input v-model="result" type="radio" :value="value">{{ batchReviewLabels[value] }}</label></fieldset><div class="buttons"><button class="return" :disabled="busy || !selected.length" @click="openReturn">退回所选（{{ selected.length }}）</button><button class="primary" :disabled="busy || !selected.length || (result !== 'rejected' && rejected)" @click="result === 'rejected' ? openReturn() : submit('complete')">{{ busy ? '正在提交…' : `确认审核（${selected.length}）` }}</button></div></div>
      <label class="note">审核意见<input v-model="note" :disabled="busy" maxlength="500" placeholder="选填，最多500字"></label><small>仅处理勾选SKU；退回项须修正重提后才能通过。</small>
    </template>
    <button v-else-if="document.review?.status !== 'reviewing'" class="primary" :disabled="busy" @click="submit('claim')">开始逐项审核</button><span v-else>{{ document.review.claimantName }}审核中</span>
    <Teleport to="body"><dialog ref="dialog" class="batch-return-dialog" aria-label="单品批量退回原因" @cancel="busy && $event.preventDefault()"><h3>退回所选 {{ returning.length }} 个SKU</h3><div class="common"><textarea v-model="common" :disabled="busy" maxlength="500" aria-label="统一退回原因" placeholder="统一原因（可选）" /><button :disabled="busy || !common.trim()" @click="returning.forEach(i => reasons[i] = common.trim())">应用到所选SKU</button></div><div class="reason-scroll"><div class="return-item" v-for="index in returning" :key="index">{{ document.items[index]?.input.primarySku }}<PriceExceptionSelect :model-value="exceptionTypes[index] || ''" :disabled="busy" :label="`第${index + 1}项异常类型`" @update:model-value="exceptionTypes[index] = $event" /><textarea v-model="reasons[index]" :disabled="busy" maxlength="500" :aria-label="`第${index + 1}项退回原因`" placeholder="退回原因（选填）" /></div></div><p v-if="error" role="alert">{{ error }}</p><footer><button :disabled="busy" @click="dialog?.close()">返回修改</button><button class="return" :disabled="busy" @click="submit('complete', true)">{{ busy ? '正在退回…' : `确认退回${returning.length}项` }}</button></footer></dialog></Teleport>
  </section>
</template>
<style scoped>
.return-item{display:grid;gap:8px}

.batch-review-actions{display:grid;gap:9px;width:100%;font-size:12px}.selection,.action-row,.buttons,fieldset,.common{display:flex;align-items:center;gap:10px;flex-wrap:wrap}.cancel{margin-left:auto}fieldset{border:0;padding:0;margin:0}legend{position:absolute;clip-path:inset(50%);width:1px;height:1px;overflow:hidden}fieldset label{display:flex;align-items:center;gap:5px;border:1px solid #d2dbe5;border-radius:5px;min-height:44px;padding:7px;box-sizing:border-box}.active{background:#fff5e6;border-color:#f90}button{font:inherit;padding:9px 12px;min-height:36px;background:white;color:#284563;border:1px solid #ccd7e3;border-radius:5px;cursor:pointer}button:disabled{opacity:.5;cursor:default}.buttons{margin-left:auto}.buttons button{min-width:130px;min-height:46px;font-weight:700}.return{color:#bb332e;border-color:#d84942}.primary{background:#ef8d00;color:#fff;border-color:#ef8d00}.note{display:flex;gap:8px;align-items:center}.note input{width:min(600px,80%);padding:7px;border:1px solid #ccd7e3;border-radius:5px}p{color:#b33;margin:0}small{color:#71818d}.batch-return-dialog{width:min(620px,calc(100vw - 24px));box-sizing:border-box;max-height:85dvh;padding:20px;border:1px solid #d5dee8;border-radius:10px;color:#25364b;font:14px Arial,'Microsoft YaHei',sans-serif}.batch-return-dialog::backdrop{background:#152a4066}.reason-scroll{max-height:40dvh;overflow:auto;display:grid;gap:12px;margin:14px 0}.reason-scroll label{display:grid;gap:5px}textarea{font:inherit;min-height:58px;padding:8px;border:1px solid #ccd7e3;border-radius:5px;box-sizing:border-box;resize:vertical}.common textarea{flex:1}.batch-return-dialog footer{display:flex;gap:14px;justify-content:flex-end}.batch-return-dialog footer button{min-height:44px}@media(max-width:700px){.action-row{display:grid}fieldset{display:grid;grid-template-columns:1fr 1fr;gap:5px}.buttons{width:100%}.buttons button{flex:1;min-width:0}.note{display:none}.batch-review-actions{gap:5px}}button:focus-visible,input:focus-visible,textarea:focus-visible{outline:2px solid #367db9;outline-offset:2px}
</style>
