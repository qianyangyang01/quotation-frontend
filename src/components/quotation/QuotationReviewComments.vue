<script setup lang="ts">
import { computed, nextTick, onUnmounted, ref, watch } from 'vue'
import { loadQuotationReviewHistory, reviewQuotationRecord, type QuotationRecord, type QuotationReviewEvent, type QuotationReviewState } from '@/data/quotationRecords'

const props = defineProps<{ record: QuotationRecord; state: QuotationReviewState; account: string; canReview: boolean; busy: boolean }>()
const emit = defineEmits<{ saved: [record: QuotationRecord]; viewed: [version: number] }>()
const dialog = ref<HTMLDialogElement | null>(null)
const opened = ref(false), loading = ref(false), saving = ref(false)
const note = ref(''), error = ref(''), loadError = ref('')
const history = ref<QuotationReviewEvent[]>([])
let generation = 0
const baseVersion = ref<number>(), baseReviewVersion = ref(0)
let disposed = false
const latest = computed(() => props.state.financeReviewLatestComment)
const count = computed(() => props.state.financeReviewCommentCount || (latest.value ? 1 : 0))
const canWrite = computed(() => props.canReview && (props.state.lifecycleState || 'active') === 'active')
const changed = computed(() => props.state._version !== baseVersion.value || (props.state._reviewVersion ?? 0) !== baseReviewVersion.value)
const time = (value: string) => value ? new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' }) : '历史时间未保存'
function close() { if (!saving.value) { dialog.value?.close(); opened.value = false } }
async function loadHistory() {
  const current = ++generation
  const displayedVersion = props.state._reviewVersion ?? 0
  loading.value = true; loadError.value = ''
  try {
    const rows = await loadQuotationReviewHistory(props.record.id)
    if (current !== generation) return
    history.value = rows.filter(row => ['comment', 'complete', 'legacy-review'].includes(row.action) && row.note?.trim()
      && !(row.action === 'legacy-review' && row.note === '保留的历史审核结果')).reverse()
    await nextTick()
    if(current === generation && opened.value) emit('viewed', displayedVersion)
  } catch { if (current === generation) loadError.value = '审核意见加载失败，请重试' }
  finally { if (current === generation) loading.value = false }
}
async function open() {
  baseVersion.value = props.state._version; baseReviewVersion.value = props.state._reviewVersion ?? 0
  error.value = ''; opened.value = true
  await nextTick()
  dialog.value?.showModal()
  void loadHistory()
}
async function save() {
  if (!canWrite.value || saving.value || props.busy || changed.value || !note.value.trim() || note.value.trim().length > 500) return
  const id = props.record.id, account = props.account
  saving.value = true; error.value = ''
  try {
    const saved = await reviewQuotationRecord(id, { action: 'comment', note: note.value.trim() }, baseVersion.value, baseReviewVersion.value)
    if (disposed || id !== props.record.id || account !== props.account) return
    note.value = ''; emit('saved', saved)
    dialog.value?.close(); opened.value = false
  } catch (cause) {
    if (id === props.record.id && account === props.account) error.value = cause instanceof Error ? cause.message : '保存失败，请重试'
  } finally { saving.value = false }
}
watch([() => props.record.id, () => props.account], () => {
  generation++; dialog.value?.close(); opened.value = false; note.value = ''; history.value = []; error.value = ''; loadError.value = ''
})
watch(() => props.state._reviewVersion, () => { if (opened.value) void loadHistory() })
onUnmounted(() => { disposed = true; generation++ })
</script>

<template>
  <div v-if="canWrite || count || $slots.default" class="review-comments" @click.stop>
    <div class="comment-actions">
      <slot />
      <button v-if="canWrite || count" type="button" class="comment-entry" :disabled="busy || saving" @click="open">{{ canWrite ? '审核意见' : '查看审核意见' }}<template v-if="count"> · {{ count }}</template></button>
    </div>
    <button v-if="latest" type="button" class="comment-preview" :title="latest.note" @click="open"><span>审核意见：{{ latest.note }}</span><small>{{ latest.actorName }} · {{ time(latest.at) }}</small></button>
  </div>
  <Teleport to="body">
    <dialog v-if="opened" ref="dialog" class="review-comments-dialog" aria-label="审核意见" @cancel="saving ? $event.preventDefault() : close()" @close="opened = false" @click="($event.target === dialog) && close()">
      <header><div><strong>审核意见</strong><p>{{ record.primarySku }} · {{ record.customerName }}</p></div><button type="button" aria-label="关闭审核意见" :disabled="saving" @click="close">×</button></header>
      <template v-if="canWrite">
        <label>新增意见（选填）<textarea v-model="note" aria-label="新增审核意见（选填）" maxlength="500" rows="4" :disabled="saving" placeholder="可填写建议、需要核实的问题等；不填写也可正常完成审核" /></label>
        <small class="comment-help">保存意见不改变审核状态，业务员可查看。<span>{{ note.length }}/500</span></small>
        <p v-if="changed" class="comment-error" role="alert">报价或审核记录已更新，请关闭并重新打开意见窗口后核对；已输入的内容会保留。</p>
      </template>
      <p v-if="error" class="comment-error" role="alert">{{ error }}</p>
      <section class="comment-history" aria-label="历史审核意见">
        <b>历史意见<template v-if="!loading && !loadError"> · {{ history.length }}</template></b>
        <p v-if="loading" role="status">正在加载意见…</p>
        <p v-else-if="loadError" class="comment-error" role="alert">{{ loadError }} <button type="button" @click="loadHistory">重新加载意见</button></p>
        <p v-else-if="!history.length">暂无审核意见</p>
        <article v-for="item in history" :key="item.id"><small>{{ item.actorName }} · {{ time(item.at) }}</small><p>{{ item.note }}</p></article>
      </section>
      <footer><button type="button" :disabled="saving" @click="close">{{ canWrite ? '取消' : '关闭' }}</button><button v-if="canWrite" type="button" class="comment-save" :disabled="saving || busy || changed || !note.trim() || note.trim().length > 500" :aria-busy="saving" @click="save">{{ saving ? '保存中…' : '保存意见' }}</button></footer>
    </dialog>
  </Teleport>
</template>

<style scoped>
.review-comments{width:100%;min-width:0}.comment-actions{display:flex;flex-wrap:wrap;gap:8px;align-items:center}.comment-entry{color:#2563a6;border-color:#b8cde6}.comment-preview{display:grid;gap:4px;width:100%;min-width:0;margin-top:6px;padding:7px 9px;text-align:left;background:#eef5fc;border:0;color:#31577c}.comment-preview span{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.comment-preview small{color:#65768a;font-size:11px;overflow-wrap:anywhere}button{padding:7px 10px;border:1px solid #d7dee7;border-radius:6px;background:#fff;color:#374151;font:inherit;font-size:13px;cursor:pointer}button:hover:not(:disabled){background:#edf5ff}button:active:not(:disabled){transform:translateY(1px)}button:focus-visible,textarea:focus-visible{outline:2px solid #3782d6;outline-offset:2px}button:disabled{opacity:.5;cursor:not-allowed}.review-comments-dialog{box-sizing:border-box;width:min(640px,calc(100vw - 32px));max-height:85vh;overflow:auto;border:1px solid #dce4ed;border-radius:12px;padding:24px;background:white;color:#25344a;box-shadow:0 20px 60px #17212b33;font:14px Arial,"Microsoft YaHei",sans-serif}.review-comments-dialog::backdrop{background:#17212b66}header{display:flex;justify-content:space-between;gap:16px;margin-bottom:20px}header strong{font-size:20px}header p{margin:8px 0 0;color:#65768a}header>button{align-self:flex-start;border:0;font-size:24px;padding:0 4px}label{display:grid;gap:8px;font-weight:600}textarea{box-sizing:border-box;width:100%;resize:vertical;min-height:112px;padding:10px;border:1px solid #cbd7e4;border-radius:6px;font:inherit;line-height:1.7}.comment-help{display:flex;justify-content:space-between;gap:12px;margin-top:8px;color:#65768a;line-height:1.5}.comment-help span{white-space:nowrap}.comment-history{margin-top:20px;padding-top:16px;border-top:1px solid #e2e8ef}.comment-history article{padding:14px 0;border-bottom:1px solid #edf0f4}.comment-history small{color:#65768a}.comment-history p{margin:8px 0;white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.7}.comment-error{color:#b52b25;line-height:1.6}footer{display:flex;justify-content:flex-end;gap:12px;margin-top:20px}footer button{min-width:88px;padding:9px 16px}.comment-save{background:#246de3;border-color:#246de3;color:white}.comment-save:hover:not(:disabled){background:#1558bf}@media(prefers-reduced-motion:reduce){button:active:not(:disabled){transform:none}}
</style>
