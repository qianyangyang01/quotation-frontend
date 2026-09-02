<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { logisticsRebuild, type RequiredChannels } from '@/data/logisticsRebuild'
import { idempotencyKey } from '@/services/http'
const props = defineProps<{ datasetId: string; preparing: boolean; refreshKey?: number }>()
const emit = defineEmits<{ updated: [] }>()
const state = ref<RequiredChannels | null>(null), selected = ref<string[]>([]), note = ref(''), confirmed = ref(false), dirty = ref(false), busy = ref(false), error = ref(''), message = ref('')
const groups = computed(() => [...new Set(state.value?.channels.map(c => c.providerName) || [])].map(name => ({ name, channels: state.value!.channels.filter(c => c.providerName === name) })))
const saveHelp = computed(() => selected.value.length === 0 ? '请至少选择一个日常必用渠道。' : !note.value.trim() ? '请填写核对备注，说明本次必用渠道的选择依据。' : !dirty.value ? (state.value?.confirmed ? '必用清单已保存并确认。' : '当前选择已保存。') : '信息已填写完整，可以保存。')
const canSave = computed(() => !busy.value && dirty.value && selected.value.length > 0 && Boolean(note.value.trim()))
let generation = 0, loadedDatasetId = '', key = idempotencyKey('required')
watch(confirmed, () => { key = idempotencyKey('required') }, { flush: 'sync' })
watch(() => [props.datasetId, props.refreshKey], async () => {
  const current = ++generation, datasetChanged = loadedDatasetId !== props.datasetId
  loadedDatasetId = props.datasetId; state.value = null; error.value = ''
  if (datasetChanged) message.value = ''
  try { const result = await logisticsRebuild.required(props.datasetId); if (current !== generation) return; state.value = result; selected.value = [...result.channelIds]; confirmed.value = result.confirmed; note.value = result.note || ''; dirty.value = false; key = idempotencyKey('required') }
  catch (e) { if (current === generation) error.value = e instanceof Error ? e.message : '必用清单读取失败' }
}, { immediate: true })
function changed() { confirmed.value = false; dirty.value = true; key = idempotencyKey('required'); message.value = '' }
function noteChanged() { dirty.value = true; key = idempotencyKey('required'); message.value = '' }
function confirmationChanged() { dirty.value = true; key = idempotencyKey('required'); message.value = '' }
function channelStatus(channel: RequiredChannels['channels'][number]) {
  if (channel.quoteReady) return '可自动报价'
  if (!channel.currentVersionId) return '价格草稿待审核发布'
  return '价格已发布，待计费验收'
}
async function save() {
  if (!state.value || busy.value) return
  const current = generation
  busy.value = true; error.value = ''
  try { const result = await logisticsRebuild.saveRequired(props.datasetId, { revision: state.value.revision, confirmed: confirmed.value, channelIds: selected.value, note: note.value }, key); if (current !== generation) return; state.value = result; dirty.value = false; key = idempotencyKey('required'); message.value = result.confirmed ? '必用清单已确认；仍须全部通过计费验收才能切换。' : '选择已保存，尚未确认上线范围。'; emit('updated') }
  catch (e) { if (current === generation) error.value = e instanceof Error ? e.message : '保存失败' } finally { busy.value = false }
}
</script>
<template>
  <section class="required-panel">
    <h2>日常必用渠道核对</h2>
    <p>请逐项勾选日常必用渠道。勾选只确定上线范围，不会自动发布价格或代替计费验收；全部必用渠道完成“价格发布 + 计费验收”后才能切换生产库。</p>
    <p v-if="error" role="alert">{{ error }}</p><p v-if="message" role="status">{{ message }}</p>
    <template v-if="state">
      <p>清单版本 {{ state.revision }} · {{ state.confirmed ? '已确认' : '未确认' }} · 已选 {{ selected.length }} / {{ state.channels.length }} 个渠道</p>
      <p v-if="state.confirmedBy">最近保存：{{ state.confirmedBy }} · {{ state.confirmedAt }}</p>
      <details v-for="group in groups" :key="group.name"><summary>{{ group.name }} · {{ group.channels.length }} 个渠道</summary>
        <div class="scroll"><table><thead><tr><th>必用</th><th>渠道</th><th>国家 / 分区</th><th>价格行</th><th>价格与报价状态</th></tr></thead><tbody>
          <tr v-for="channel in group.channels" :key="channel.id"><td><input v-model="selected" type="checkbox" :value="channel.id" :disabled="!preparing || busy || channel.archived" :aria-label="`必用渠道 ${group.name} ${channel.name}`" @change="changed"></td><td>{{ channel.name }}</td><td><details><summary>{{ channel.countries.length }} 个国家 · {{ channel.zones.length }} 个分区标记</summary><p>{{ channel.countries.join('、') }}</p><p>{{ channel.zones.join('；') || '无分区' }}</p></details></td><td>{{ channel.priceRows }}</td><td><b>{{ channelStatus(channel) }}</b><details v-if="channel.pendingReasons.length"><summary>原表规则核对项</summary><p v-for="reason in channel.pendingReasons" :key="reason">{{ reason }}</p></details></td></tr>
        </tbody></table></div>
      </details>
      <template v-if="preparing"><label>清单核对备注 <span class="required">必填</span><textarea v-model="note" maxlength="1000" placeholder="例如：已核对本次日常必用渠道，确认作为上线前验收范围。" aria-describedby="required-channel-note-tip" @input="noteChanged" /></label><p id="required-channel-note-tip" class="form-tip" :class="{ warning: dirty && !canSave }">{{ saveHelp }}</p><label><input v-model="confirmed" type="checkbox" :disabled="selected.length === 0 || busy" @change="confirmationChanged">我已核对并确认这些是上线前必须可用的渠道</label><button :disabled="!canSave" :title="canSave ? '' : saveHelp" @click="save">{{ busy ? '保存中…' : dirty ? '保存必用清单' : '已保存' }}</button></template>
    </template>
  </section>
</template>
<style scoped>
.required-panel{padding:22px;background:#fff;border:1px solid #dfe6eb;border-radius:10px;margin-bottom:20px}.required-panel h2{margin:0 0 12px;font-size:18px}.required-panel p{color:#647785;line-height:1.7}details{margin:12px 0}summary{cursor:pointer;color:#324f60}.scroll{overflow:auto}table{width:100%;border-collapse:collapse;font-size:13px}th,td{text-align:left;padding:12px;border-bottom:1px solid #e5ebef;vertical-align:top}th{background:#f3f6f8}label{display:block;margin:14px 0}textarea{display:block;width:100%;box-sizing:border-box;min-height:65px;border:1px solid #cbd6df;border-radius:6px;padding:10px}.required{color:#b85d28;font-size:12px}.form-tip{margin:-8px 0 10px!important;font-size:12px}.form-tip.warning{color:#b85d28}.form-tip:not(.warning){color:#26784b}button{padding:10px 18px;border:0;border-radius:6px;background:#d58237;color:#fff;cursor:pointer}button:disabled{opacity:.5;cursor:not-allowed}[role=alert]{color:#b4332a}[role=status]{color:#26784b}input{accent-color:#d58237}
</style>
