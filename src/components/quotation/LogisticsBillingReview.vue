<script setup lang="ts">
import { ref, watch } from 'vue'
import { logisticsRebuild, type BillingAcceptance } from '@/data/logisticsRebuild'
import { idempotencyKey } from '@/services/http'
const props = defineProps<{ versionId: string; readonly: boolean }>()
const emit = defineEmits<{ updated: [] }>()
const status = ref<BillingAcceptance | null>(null), samples = ref('[]'), note = ref(''), source = ref(''), confirmed = ref(false), busy = ref(false), error = ref('')
let generation = 0, key = idempotencyKey('billing-accept')
watch([samples, note, source, confirmed], () => { key = idempotencyKey('billing-accept') }, { flush: 'sync' })
watch(() => props.versionId, async () => { const current = ++generation; status.value = null; samples.value = '[]'; note.value = ''; source.value = ''; confirmed.value = false; error.value = ''; try { const result = await logisticsRebuild.billing(props.versionId); if (current === generation) status.value = result } catch (e) { if (current === generation) error.value = e instanceof Error ? e.message : '计费验收状态读取失败' } }, { immediate: true })
async function approve() {
  if (!status.value || busy.value) return
  const current = generation
  busy.value = true; error.value = ''
  try { const result = await logisticsRebuild.approveBilling(props.versionId, { fingerprint: status.value.fingerprint, engineVersion: status.value.engineVersion, samples: JSON.parse(samples.value), note: note.value, sourceReference: source.value, reviewConfirmed: confirmed.value }, key); if (current !== generation) return; status.value = result; key = idempotencyKey('billing-accept'); emit('updated') }
  catch (e) { if (current === generation) error.value = e instanceof Error ? e.message : '计费验收失败' } finally { busy.value = false }
}
</script>
<template><section class="billing-review"><h3>计费验收（独立于价格审核）</h3><p v-if="error" role="alert">{{ error }}</p><template v-if="status"><p>{{ status.pricePublished ? '价格已发布' : '价格尚未发布' }} · {{ status.quoteReady ? '当前版本允许报价' : '未通过计费验收' }} · {{ status.engineVersion }}</p><ul><li v-for="reason in status.unsupportedReasons" :key="reason">{{ reason }}</li></ul><details v-if="status.records.length"><summary>验收记录 {{ status.records.length }} 条</summary><p v-for="(r, i) in status.records" :key="i">{{ r.kind === 'legacy' ? '原库兼容记录（非新库验收）' : '完整版本验收' }} · {{ r.reviewed_by }} · {{ r.reviewed_at }}</p></details>
  <template v-if="!readonly && status.pricePublished && !status.unsupportedReasons.length"><p>每档至少两个不同重量的人工预期运费样本，并包含越界拒绝样本。未知规则不能通过清空备注代替适配。</p><label>原表及核算依据<input v-model="source" aria-label="原表及核算依据"></label><label>计费审核备注<input v-model="note" aria-label="计费审核备注"></label><label>人工核算样本 JSON<textarea v-model="samples" aria-label="人工核算样本 JSON" spellcheck="false" /></label><details><summary>样本格式（示意，不是本渠道预期）</summary><pre>[{"sourceReference":"原表/工作表/行及手算式","input":{"country":"US","weightKg":0.2,"marks":["普货"]},"expectedTotal":18},{"sourceReference":"超重拒绝","input":{"country":"US","weightKg":99,"marks":["普货"]},"expectRejected":true}]</pre></details><label><input v-model="confirmed" type="checkbox">已完整核对本渠道各国家、重量档位与收寄规则</label><button :disabled="busy || !confirmed || !note.trim() || !source.trim()" @click="approve">提交计费验收</button></template>
</template></section></template>
<style scoped>.billing-review{padding:18px;margin:20px 0;background:#f5f8fa;border:1px solid #dbe4ea;border-radius:8px}h3{margin:0 0 12px}p,li{line-height:1.7;color:#607582}label{display:block;margin:12px 0}input:not([type=checkbox]),textarea{display:block;box-sizing:border-box;width:100%;border:1px solid #bdcdd8;border-radius:5px;padding:10px}textarea{min-height:130px}pre{white-space:pre-wrap;overflow-wrap:anywhere}button{background:#416b82;color:white;border:0;border-radius:5px;padding:10px 15px;cursor:pointer}button:disabled{opacity:.5}summary{cursor:pointer}[role=alert]{color:#b72d20}</style>
