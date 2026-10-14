<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { currentAuthUser } from '@/data/authStore'
import { idempotencyKey } from '@/services/http'
import { loadTemplateSyncAccounts, loadTemplateSyncSource, syncQuotationTemplates,
  type TemplateSyncAccount, type TemplateSyncRequest, type TemplateSyncResult } from '@/services/quotationTemplateSync'
import type { QuotationPersonalTemplate } from '@/data/quotationTemplates'
const emit = defineEmits<{ close: []; synced: [] }>()
const accounts = ref<TemplateSyncAccount[]>([])
const source = ref('')
const templates = ref<QuotationPersonalTemplate[]>([])
const selected = ref<string[]>([])
const targets = ref<string[]>([])
const prefix = ref('')
const search = ref('')
const loadingAccounts = ref(false)
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const result = ref<TemplateSyncResult | null>(null)
let generation = 0
let disposed = false
let retry: { signature: string; key: string } | null = null
const sourceAccounts = computed(() => accounts.value.filter(u => u.templateCount > 0))
const recipients = computed(() => accounts.value.filter(u => u.account !== source.value))
const filteredRecipients = computed(() => recipients.value.filter(u => `${u.name} ${u.account}`.toLowerCase().includes(search.value.trim().toLowerCase())))
const selectedTemplates = computed(() => templates.value.filter(t => selected.value.includes(t.id)))
const maximum = computed(() => selected.value.length * targets.value.length)
const invalidName = computed(() => selectedTemplates.value.some(t => (prefix.value.trim() + t.name).length > 120))
const canSubmit = computed(() => !!source.value && !loading.value && !loadingAccounts.value && !busy.value && !result.value && maximum.value > 0 && maximum.value <= 10000 && !invalidName.value)
const body = computed<TemplateSyncRequest>(() => ({ sourceAccount: source.value, prefix: prefix.value.trim(),
  templates: selectedTemplates.value.map(t => ({ id: t.id, version: t._version! })), targetAccounts: [...targets.value].sort() }))
watch(() => JSON.stringify(body.value), () => { result.value = null; retry = null })
async function loadAccounts() {
  loadingAccounts.value = true; error.value = ''
  try {
    const loaded = await loadTemplateSyncAccounts()
    if (disposed) return
    accounts.value = loaded
    source.value = sourceAccounts.value.find(u => u.account === currentAuthUser.value.account)?.account || sourceAccounts.value[0]?.account || ''
  } catch (e) { if (!disposed) error.value = e instanceof Error ? e.message : '账号加载失败' }
  finally { if (!disposed) loadingAccounts.value = false }
}
async function loadSource() {
  const request = ++generation
  templates.value = []; selected.value = []; result.value = null; retry = null; error.value = ''
  targets.value = targets.value.filter(account => account !== source.value)
  prefix.value = (accounts.value.find(u => u.account === source.value)?.name.slice(0, 1) || '') + '-'
  if (!source.value) { loading.value = false; return }
  loading.value = true
  try {
    const loaded = await loadTemplateSyncSource(source.value)
    if (disposed || request !== generation) return
    templates.value = loaded; selected.value = loaded.map(t => t.id)
  } catch (e) { if (request === generation && !disposed) error.value = e instanceof Error ? e.message : '来源模板加载失败' }
  finally { if (request === generation && !disposed) loading.value = false }
}
watch(source, loadSource)
function selectAllTemplates(event: Event) { selected.value = (event.target as HTMLInputElement).checked ? templates.value.map(t => t.id) : [] }
function selectAllPeople(event: Event) {
  const visible = filteredRecipients.value.map(u => u.account)
  targets.value = (event.target as HTMLInputElement).checked ? [...new Set([...targets.value, ...visible])] : targets.value.filter(a => !visible.includes(a))
}
async function submit() {
  if (!canSubmit.value) return
  const request = body.value
  if (request.templates.some(t => t.version == null)) { error.value = '模板版本缺失，请重新加载来源模板'; return }
  const signature = JSON.stringify(request)
  if (!retry || retry.signature !== signature) retry = { signature, key: idempotencyKey('template-sync') }
  busy.value = true; error.value = ''
  try {
    const saved = await syncQuotationTemplates(request, retry.key)
    if (disposed) return
    result.value = saved; emit('synced')
  } catch (e) { if (!disposed) error.value = e instanceof Error ? e.message : '同步结果未确认，请保持选择不变重试' }
  finally { if (!disposed) busy.value = false }
}
onMounted(loadAccounts)
onBeforeUnmount(() => { disposed = true; generation++ })
</script>

<template>
  <div class="sync-mask" @click.self="!busy && emit('close')" @keydown.esc="!busy && emit('close')">
    <section class="sync-dialog" role="dialog" aria-modal="true" aria-labelledby="template-sync-title">
      <header><div><h2 id="template-sync-title">同步报价模板 <small>管理员专用</small></h2><p>复制到接收人的个人模板，保留原有模板和历史报价。</p></div><button aria-label="关闭同步模板" :disabled="busy" @click="emit('close')">×</button></header>
      <div class="sync-body">
        <p v-if="loadingAccounts" role="status">正在加载账号…</p>
        <p v-else-if="!sourceAccounts.length">暂无可同步的来源模板。<button @click="loadAccounts">重新加载账号</button></p>
        <fieldset :disabled="busy || loadingAccounts">
          <section class="sync-source">
            <h3>1 选择来源与模板</h3>
            <label class="field">来源账号<select v-model="source" aria-label="来源账号"><option v-for="u in sourceAccounts" :key="u.account" :value="u.account">{{ u.name }}（{{ u.account }}）</option></select></label>
            <div class="list-heading"><label><input type="checkbox" :checked="!!templates.length && selected.length === templates.length" :disabled="loading || !templates.length" @change="selectAllTemplates">全选模板</label><span>已选 {{ selected.length }} / {{ templates.length }}</span></div>
            <p v-if="loading" role="status">正在加载模板…</p>
            <div v-else class="source-list"><label v-for="t in templates" :key="t.id"><input v-model="selected" type="checkbox" :value="t.id"><b>{{ t.name }}</b><span>{{ t.items.length }} 条渠道</span></label></div>
            <button class="reload-source" :disabled="loading || !source" @click="loadSource">重新加载来源模板</button>
          </section>
          <section class="sync-targets">
            <h3>2 选择接收人员</h3>
            <input v-model="search" class="search" placeholder="搜索姓名或账号" aria-label="搜索接收人员">
            <div class="list-heading"><label><input type="checkbox" :checked="!!filteredRecipients.length && filteredRecipients.every(u => targets.includes(u.account))" :disabled="!source || !filteredRecipients.length" @change="selectAllPeople">全选{{ search.trim() ? '搜索结果' : '' }}</label><span>已选 {{ targets.length }} 人</span></div>
            <div class="people-list"><label v-for="u in filteredRecipients" :key="u.account" :title="u.account"><input v-model="targets" type="checkbox" :value="u.account"><span>{{ u.name }}<small>{{ u.account }}</small></span></label></div>
            <h3>3 设置名称</h3><label class="field">模板名称前缀<input v-model="prefix" maxlength="40" aria-label="模板名称前缀" placeholder="例如：魏-"></label>
            <p class="name-example">同步后示例：{{ prefix.trim() }}{{ selectedTemplates[0]?.name || '模板名称' }}</p>
            <p class="sync-note">原有模板全部保留；同名模板自动跳过。同步后各人独立维护，不随来源模板自动更新。</p>
          </section>
        </fieldset>
        <p v-if="invalidName" role="alert" class="error">添加前缀后名称超过120字，请缩短前缀。</p>
        <p v-if="maximum > 10000" role="alert" class="error">单次最多新增10000个副本，请减少选择。</p>
        <p v-if="error" role="alert" class="error">{{ error }}</p>
        <section v-if="result" class="sync-result" aria-label="模板同步结果" role="status">
          <h3>同步完成：新增 {{ result.created }} 个，跳过 {{ result.skipped }} 个</h3>
          <details v-for="u in result.recipients" :key="u.account"><summary>{{ u.name }}（{{ u.account }}） · 新增 {{ u.created }} · 跳过 {{ u.skipped }}</summary><p v-for="t in u.templates" :key="t.sourceId">{{ t.name }}：{{ t.status === 'created' ? '已新增' : t.reason }}</p></details>
        </section>
        <div v-else class="sync-preview"><span>来源模板 <b>{{ selected.length }}</b> 个</span><span>接收人员 <b>{{ targets.length }}</b> 人</span><span>最多新增 <b>{{ maximum }}</b> 个</span></div>
      </div>
      <footer><span>仅同步模板配置，不修改已保存报价。</span><button :disabled="busy" @click="emit('close')">{{ result ? '完成' : '取消' }}</button><button v-if="!result" class="primary" :disabled="!canSubmit" @click="submit">{{ busy ? '正在同步…' : '一键同步' }}</button></footer>
    </section>
  </div>
</template>

<style scoped>
.sync-mask{position:fixed;inset:0;z-index:145;display:grid;place-items:center;padding:20px;background:rgba(17,27,36,.5);backdrop-filter:blur(3px)}.sync-dialog{width:min(1100px,96vw);max-height:92vh;display:flex;flex-direction:column;border-radius:12px;background:#fff;overflow:hidden;color:#26343e;box-shadow:0 28px 80px #08121b59;font-size:14px}.sync-dialog header{display:flex;justify-content:space-between;align-items:flex-start;padding:20px 24px;border-bottom:1px solid #e1e7eb}.sync-dialog h2{margin:0;font-size:22px}.sync-dialog h2 small{margin-left:10px;padding:4px 8px;border:1px solid #ffd097;border-radius:5px;color:#ad6200;background:#fff6e7;font-size:12px}.sync-dialog header p{margin:8px 0 0;color:#657681}.sync-dialog header>button{border:0;font-size:24px}.sync-body{overflow:auto;padding:20px 24px}.sync-dialog fieldset{border:0;padding:0;margin:0;display:grid;grid-template-columns:1fr 1.2fr;gap:18px;min-width:0}.sync-dialog section.sync-source,.sync-targets{min-width:0;padding:16px;border:1px solid #e1e7eb;border-radius:8px;background:#fafcfd}.sync-dialog h3{margin:0 0 16px;font-size:16px}.sync-dialog input,.sync-dialog select,.sync-dialog button{font:inherit}.sync-dialog input:not([type=checkbox]),.sync-dialog select{width:100%;min-width:0;box-sizing:border-box;padding:10px 12px;border:1px solid #cbd5df;border-radius:6px;background:#fff;color:#26343e}.field{display:grid;gap:8px}.list-heading{display:flex;justify-content:space-between;gap:10px;margin:18px 0 10px;color:#526170}.list-heading label,.source-list label,.people-list label{display:flex;align-items:center;gap:8px}.sync-dialog input[type=checkbox]{accent-color:#ef8900;width:16px;height:16px;flex-shrink:0}.source-list{display:grid;gap:6px;max-height:420px;overflow:auto}.source-list label,.people-list label{padding:10px;border:1px solid #e1e7eb;border-radius:6px;background:#fff}.source-list b{font-weight:600;overflow-wrap:anywhere}.source-list span{margin-left:auto;white-space:nowrap;color:#657681;font-size:12px}.people-list{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:7px;max-height:280px;overflow:auto;margin-bottom:22px}.people-list span{min-width:0;overflow-wrap:anywhere}.people-list small{display:block;font-size:10px;color:#73818c;margin-top:4px}.name-example{color:#657681;font-size:12px}.sync-note{padding:12px;border-radius:7px;color:#9b5700;background:#fff4e3;line-height:1.7}.sync-dialog button{padding:9px 15px;background:#fff;border:1px solid #cbd5df;border-radius:6px;color:#425563;cursor:pointer}.sync-dialog button:disabled{opacity:.45;cursor:not-allowed}.sync-dialog button.primary{background:#ff9700;border-color:#ff9700;color:#17232d;font-weight:700}.reload-source{margin-top:12px}.sync-preview{display:flex;justify-content:space-around;gap:12px;background:#f3f6f8;border-radius:8px;padding:18px;margin-top:18px}.sync-preview b{font-size:22px;margin:0 4px}.error{padding:12px;color:#ad3427;background:#fff0ed;border-radius:6px}.sync-result{margin-top:20px;padding:16px;background:#f1faf5;border:1px solid #bcdccd;border-radius:8px}.sync-result details{padding:8px 0}.sync-result summary{cursor:pointer}.sync-result p{font-size:13px;margin:6px 12px}.sync-dialog footer{display:flex;align-items:center;gap:10px;border-top:1px solid #e1e7eb;padding:16px 24px}.sync-dialog footer span{margin-right:auto;color:#657681;font-size:12px}@media(max-width:760px){.sync-mask{padding:8px}.sync-dialog fieldset{grid-template-columns:1fr}.sync-body{padding:12px}.people-list{grid-template-columns:repeat(2,minmax(0,1fr))}.sync-dialog h2{font-size:18px}.sync-dialog footer{flex-wrap:wrap;padding:12px}.sync-dialog footer span{flex-basis:100%}.sync-preview{flex-wrap:wrap;font-size:12px}}
</style>
