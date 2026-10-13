<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import type { QuotationRecord } from '@/data/quotationRecords'
import { lifecycleLabel, type LifecycleAction } from '@/data/quotationLifecycle'

const props = defineProps<{ action: LifecycleAction; rows: QuotationRecord[]; busy: boolean; error: string; batchNo?: string; blocked?: string[]; loadingFailed?: boolean; skipped?: QuotationRecord[] }>()
const emit = defineEmits<{ cancel: []; confirm: [reason: string]; retry: [] }>()
const reason = ref('')
const dialog = ref<HTMLDialogElement>()
const input = ref<HTMLTextAreaElement>()
let previousFocus: HTMLElement | null = null
const title = computed(() => props.action === 'trash' ? (props.batchNo ? '整批移入回收站' : '移入回收站') : props.action === 'archive' ? '批量归档' : '恢复报价记录')
onMounted(() => { previousFocus = document.activeElement as HTMLElement; dialog.value?.showModal(); input.value?.focus() })
onBeforeUnmount(() => { dialog.value?.close(); previousFocus?.focus() })
function cancel(event: Event) { event.preventDefault(); if (!props.busy) emit('cancel') }
</script>

<template>
  <dialog ref="dialog" class="lifecycle-dialog" aria-labelledby="lifecycle-dialog-title" @cancel="cancel">
    <form @submit.prevent="!loadingFailed && rows.length && !busy && !blocked?.length && reason.trim() && emit('confirm', reason)">
      <header><h2 id="lifecycle-dialog-title">{{ title }}</h2><button type="button" aria-label="关闭清理确认" :disabled="busy" @click="emit('cancel')">×</button></header>
      <template v-if="loadingFailed">
        <p v-if="batchNo">提交批次：{{ batchNo }}</p>
        <div role="alert" class="lifecycle-error"><p><b>无法整批移入回收站，本次未移入任何记录。</b></p><p>{{ error }}</p></div>
        <p>请先按提示处理，再重新检查整批记录；也可以关闭此窗口，单独处理符合条件的记录。</p>
        <footer><button type="button" @click="emit('cancel')">关闭</button><button type="button" :disabled="busy" @click="emit('retry')">重新检查</button></footer>
      </template>
      <template v-else>
      <p>本次处理 <b>{{ rows.length }}</b> 条报价，请核对以下明细。</p>
      <p v-if="batchNo">提交批次：{{ batchNo }}（检查本批所有记录，不受分页和筛选条件限制）</p>
      <div v-if="skipped?.length" class="lifecycle-skipped"><b>已跳过 {{ skipped.length }} 条，以下记录保持原状态：</b><ul><li v-for="row in skipped" :key="row.id">{{ row.no }} · {{ row.primarySku }}：{{ row.lifecycleState === 'withdrawn' ? '已撤回重新编辑' : '已在回收站' }}</li></ul></div>
      <p v-if="!rows.length" role="alert" class="lifecycle-error">本批已无可移入回收站的记录，请关闭窗口并刷新列表。</p>
      <div class="lifecycle-confirm-rows"><table><thead><tr><th>报价单号 / 客户</th><th>SKU</th><th>{{ action === 'restore' ? '恢复到' : '当前分类' }}</th></tr></thead>
        <tbody><tr v-for="row in rows" :key="row.id"><td>{{ row.no }}<small>{{ row.customerName }}</small></td><td>{{ row.primarySku }}</td><td>{{ lifecycleLabel(action === 'restore' ? (row.lifecycleState === 'trashed' ? row.lifecyclePreviousState : 'active') : row.lifecycleState) }}</td></tr></tbody>
      </table></div>
      <p class="lifecycle-explanation">{{ action === 'trash' ? '移入后将从“我的报价记录”和管理员“报价记录”的正常列表及审核队列中移除，不能继续审核，不计入业务统计。仅在回收站查看或恢复，原报价和审核历史保留。' : action === 'archive' ? '归档后从当前列表隐藏，仍计入历史业务统计，可随时恢复。' : '回收站记录恢复到移入前的分类；已归档记录恢复到当前记录。业务统计同步恢复。' }}</p>
      <div v-if="blocked?.length" role="alert" class="lifecycle-error"><p>以下记录不能移入回收站，本批不会执行：</p><ul><li v-for="message in blocked" :key="message">{{ message }}</li></ul></div>
      <label for="lifecycle-reason">操作原因 <small>必填，最多200字</small></label>
      <textarea id="lifecycle-reason" ref="input" v-model="reason" maxlength="200" required :disabled="busy" placeholder="例如：测试数据清理、误操作恢复"></textarea>
      <p v-if="error" role="alert" class="lifecycle-error">{{ error }}</p>
      <footer><button type="button" :disabled="busy" @click="emit('cancel')">取消</button><button type="submit" :class="{danger:action==='trash'}" :disabled="busy || !rows.length || !reason.trim() || !!blocked?.length">{{ busy ? '正在处理…' : action === 'trash' ? '确认移入' : action === 'archive' ? '确认归档' : '确认恢复' }}</button></footer>
      </template>
    </form>
  </dialog>
</template>

<style scoped>
.lifecycle-skipped{max-height:130px;overflow:auto;padding:10px;background:#f4f6f8;border-radius:6px;color:#586571;font-size:13px;line-height:1.7}.lifecycle-skipped ul{margin:6px 0 0;padding-left:20px}
.lifecycle-dialog form{display:flex;flex-direction:column;max-height:calc(85vh - 48px)}.lifecycle-dialog form>*{flex-shrink:0}.lifecycle-dialog .lifecycle-confirm-rows{flex-shrink:1;min-height:70px}.lifecycle-dialog .lifecycle-error{max-height:100px;overflow:auto}@media(max-height:600px){.lifecycle-dialog form{max-height:none}}
.lifecycle-dialog{box-sizing:border-box;width:min(680px,calc(100vw - 32px));max-height:85vh;overflow:auto;border:1px solid #e0e5eb;border-radius:12px;padding:24px;color:#25313b;background:#fff;box-shadow:0 20px 70px #17212b30}.lifecycle-dialog::backdrop{background:#17212b66}.lifecycle-dialog header{display:flex;align-items:center;justify-content:space-between}.lifecycle-dialog h2{margin:0;font-size:20px}.lifecycle-dialog p{font-size:13px;line-height:1.7}.lifecycle-dialog small{display:block;color:#75818b;font-size:12px}.lifecycle-confirm-rows{max-height:240px;overflow:auto;border:1px solid #e3e8ed;border-radius:6px}table{border-collapse:collapse;width:100%;font-size:12px}th,td{text-align:left;padding:10px;border-bottom:1px solid #edf0f3;overflow-wrap:anywhere}th{background:#f7f8fa;position:sticky;top:0}.lifecycle-explanation{background:#fff8ed;padding:10px;border-radius:6px}.lifecycle-dialog label{font-size:13px;font-weight:600}.lifecycle-dialog textarea{box-sizing:border-box;display:block;width:100%;height:80px;margin:8px 0;border:1px solid #dce2e8;border-radius:6px;padding:10px;font:inherit;resize:vertical}.lifecycle-dialog footer{display:flex;justify-content:flex-end;gap:10px;margin-top:18px}.lifecycle-dialog button{border:1px solid #dce2e8;border-radius:6px;padding:9px 16px;background:#fff;font:inherit;cursor:pointer}.lifecycle-dialog button[type=submit]{background:#ffac32;border-color:#ffac32;font-weight:700}.lifecycle-dialog button.danger{background:#c94036;border-color:#c94036;color:#fff}.lifecycle-dialog button:disabled{opacity:.5;cursor:not-allowed}.lifecycle-error{color:#bb342b}
</style>
