<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { companyChannels as service, type CompanyDirectory, type CompanyChannel, type RebuildJob, type RebuildPreview } from '@/data/companyChannels'
import { invalidatePublishedLogisticsCache } from '@/data/publishedLogisticsRepository'
import LogisticsPager from './LogisticsPager.vue'

const emit = defineEmits<{ changed: [directory: CompanyDirectory]; selectDataset: [id: string] }>()
const directory = ref<CompanyDirectory>({ revision: 0, enabled: false, entries: [] })
const job = ref<RebuildJob | null>(null), preview = ref<RebuildPreview | null>(null)
const query = ref(''), page = ref(0), size = ref(10), open = ref(false), busy = ref(false), error = ref(''), message = ref('')
const editing = ref<CompanyChannel | null>(null), aliases = ref(''), codes = ref(''), note = ref('')
const restoreConfirmed = ref(false)
const deleteConfirmed = ref(false), reviewConfirmed = ref(false), unavailableConfirmed = ref(false)
const matches = computed(() => directory.value.entries.filter(e => `${e.providerName} ${e.channelName} ${e.productCodes?.join(' ')}`.includes(query.value)))
const pages = computed(() => Math.max(1, Math.ceil(matches.value.length / size.value)))
const rows = computed(() => matches.value.slice(page.value * size.value, (page.value + 1) * size.value))
watch([query, size], () => { page.value = 0 })
const phaseNames: Record<string, string> = { paused: '报价已暂停，等待备份', 'backed-up': '备份已校验，等待清理', deleted: '旧价已清理，等待基准导入与审核', completed: '基准已启用，报价已恢复', restored: '已显式恢复校验备份' }
async function run(action: () => Promise<void>) { if (busy.value) return; busy.value = true; error.value = ''; message.value = ''; try { await action() } catch (e) { error.value = e instanceof Error ? e.message : '操作失败' } finally { busy.value = false } }
async function load() {
  directory.value = await service.list()
  if (directory.value.state?.rebuild_id) job.value = await service.job(directory.value.state.rebuild_id)
  emit('changed', directory.value)
}
function edit(entry?: CompanyChannel) {
  editing.value = entry ? JSON.parse(JSON.stringify(entry)) : { id: '', providerName: '', channelName: '', logisticsAttribute: '普货', enabled: true, aliases: [], productCodes: [], providerAliases: [] }
  aliases.value = editing.value!.aliases?.join('\n') || ''; codes.value = editing.value!.productCodes?.join('\n') || ''
}
const lines = (value: string) => [...new Set(value.split('\n').map(s => s.trim()).filter(Boolean))]
async function save() {
  if (!editing.value) return
  const entry = { ...editing.value, aliases: lines(aliases.value), productCodes: lines(codes.value) }
  const entries = directory.value.entries.map(e => e.id === entry.id ? entry : e)
  if (!entry.id) entries.push(entry)
  directory.value = await service.save({ ...directory.value, entries }); editing.value = null
  invalidatePublishedLogisticsCache(); emit('changed', directory.value); message.value = '公司清单已保存，后续新批次使用新版本。'
}
async function begin() { if (!preview.value) return; job.value = await service.begin(preview.value.previewToken, note.value); preview.value = null; invalidatePublishedLogisticsCache(); await load() }
async function step(action: 'backup' | 'purge' | 'cleanup' | 'finish' | 'restore') {
  if (!job.value) return
  job.value = await service.step(job.value.id, action, { note: note.value, restoreConfirmed: restoreConfirmed.value, deleteConfirmed: deleteConfirmed.value, reviewConfirmed: reviewConfirmed.value, unavailableConfirmed: unavailableConfirmed.value })
  invalidatePublishedLogisticsCache(); await load()
}
onMounted(() => run(load))
</script>

<template>
  <section class="company-directory">
    <header><div><h2>公司物流商与渠道清单</h2><p>{{ directory.entries.length }} 个渠道 · 清单版本 {{ directory.revision }} · {{ directory.enabled ? '按清单筛选导入' : '尚未建立公司清单' }}</p></div><button :aria-expanded="open" @click="open = !open">{{ open ? '收起' : '维护清单与重建' }}</button></header>
    <p v-if="directory.state?.paused" class="warning" role="status">物流价格正在重建，新报价提交已暂停。历史报价可继续查看和导出。</p>
    <p v-if="error" role="alert" class="warning">{{ error }}</p><p v-if="message" role="status">{{ message }}</p>
    <template v-if="open">
      <div class="toolbar"><input v-model="query" aria-label="搜索公司渠道" placeholder="物流商、渠道名称或代码"><button :disabled="busy || directory.state?.paused || !directory.enabled" @click="edit()">新增公司渠道</button><button :disabled="busy" @click="run(load)">刷新</button></div>
      <div class="scroll"><table><thead><tr><th>物流商</th><th>标准渠道名 / 产品代码</th><th>货物属性</th><th>状态</th><th>来源定位</th><th>操作</th></tr></thead><tbody><tr v-for="entry in rows" :key="entry.id"><td>{{ entry.providerName }}</td><td>{{ entry.channelName }}<small>{{ entry.productCodes?.join('、') }}</small></td><td>{{ entry.logisticsAttribute }}</td><td>{{ entry.enabled ? '启用' : '停用' }}</td><td><details v-if="entry.sources?.length"><summary>{{ entry.sources.length }} 处来源</summary><p v-for="(source, i) in entry.sources" :key="i">{{ source.file }} · {{ source.sheet }} · 第 {{ source.firstRow || source.row }}–{{ source.lastRow || source.row }} 行</p></details></td><td><button :disabled="busy || directory.state?.paused" @click="edit(entry)">编辑</button></td></tr><tr v-if="!rows.length"><td colspan="6">暂无匹配渠道。</td></tr></tbody></table></div>
      <LogisticsPager :page="page" :size="size" :total="matches.length" :total-pages="pages" :size-options="[10, 30, 50]" :loading="busy" @page-change="page = $event" @size-change="size = $event" />
      <form v-if="editing" class="edit" @submit.prevent="run(save)"><h3>维护公司渠道</h3><label>物流商<input v-model="editing.providerName" required maxlength="180"></label><label>标准渠道名<input v-model="editing.channelName" required maxlength="180"></label><label>货物属性<input v-model="editing.logisticsAttribute" required maxlength="180"></label><label>登记别名（每行一个）<textarea v-model="aliases" /></label><label>原产品代码（每行一个）<textarea v-model="codes" /></label><label><input v-model="editing.enabled" type="checkbox">启用</label><p>保留普货、带电、服装、经济等业务区别。同一物流商下名称、别名和代码必须唯一。</p><div><button :disabled="busy">保存新版本</button><button type="button" @click="editing = null">取消</button></div></form>
      <details class="rebuild"><summary>旧价格清理与 8.27 基准重建</summary><p>先暂停新报价并备份历史，再物理清理旧价格。新基准审核完成后显式恢复报价。</p><label>核对备注<input v-model="note" maxlength="1000" placeholder="填写本次核对说明"></label>
        <button v-if="!directory.state?.paused" :disabled="busy" @click="run(async () => { preview = await service.preview() })">预览清理范围</button>
        <div v-if="preview"><p>将清理 {{ preview.priceVersions }} 个旧价格版本，涉及 {{ preview.channels }} 个渠道、{{ preview.imports }} 个导入批次；保留 {{ preview.quotations }} 条历史报价。</p><button :disabled="busy || !note.trim()" @click="run(begin)">暂停新报价并建立重建任务</button></div>
        <div v-if="job"><h3>{{ phaseNames[job.phase] || job.phase }}</h3><p v-if="job.payload.backup">备份校验：{{ job.payload.backup.sha256 }}</p><button v-if="['paused', 'backed-up'].includes(job.phase)" :disabled="busy" @click="run(() => step('backup'))">备份并回读校验</button><template v-if="job.phase === 'backed-up'"><label><input v-model="deleteConfirmed" type="checkbox">已核对备份和清理范围，确认物理删除旧价格</label><button :disabled="busy || !deleteConfirmed" @click="run(() => step('purge'))">清理旧价格</button></template><template v-if="job.phase === 'deleted'"><p>已删除 {{ job.payload.removedVersions }} 个旧版本；历史校验 {{ job.payload.historyPreserved ? '通过' : '未通过' }}。</p><button :disabled="busy" @click="run(() => step('cleanup'))">清理旧价格运行副本</button><button :disabled="busy" @click="emit('selectDataset', job.payload.targetDatasetId)">打开基准库导入与审核</button><label><input v-model="reviewConfirmed" type="checkbox">所有公司渠道的导入与审核状态已逐项核对</label><label><input v-model="unavailableConfirmed" type="checkbox">确认有规则缺口的渠道继续阻断自动报价</label><button :disabled="busy || !reviewConfirmed || !note.trim()" @click="run(() => step('finish'))">启用审核后的基准并恢复报价</button><details><summary>重建失败：显式恢复旧价格备份</summary><p>此操作恢复已校验的旧价格，封存本次新基准库并恢复报价。历史报价与成交数据保持现值。</p><label><input v-model="restoreConfirmed" type="checkbox">明确确认恢复备份中的旧价格并恢复报价</label><button :disabled="busy || !restoreConfirmed || !note.trim()" @click="run(() => step('restore'))">恢复校验备份</button></details></template></div>
      </details>
    </template>
  </section>
</template>

<style scoped>
.company-directory{padding:20px;border:1px solid #dbe2e8;border-radius:12px;background:#fff;margin:16px 0;color:#294354}header,.toolbar{display:flex;justify-content:space-between;align-items:center;gap:12px}h2{font-size:18px;margin:0}p{font-size:13px;overflow-wrap:anywhere}.warning{background:#fff2dc;padding:12px;color:#854711}.scroll{overflow:auto}table{width:100%;border-collapse:collapse;margin-top:12px;font-size:13px}th,td{padding:10px;text-align:left;border-bottom:1px solid #e8edf0}small{display:block;color:#677c89}button,input,textarea{padding:8px;border:1px solid #bccbd5;border-radius:6px;background:white;color:inherit}button{cursor:pointer}button:disabled{opacity:.5;cursor:not-allowed}.edit,.rebuild{border-top:1px solid #dbe2e8;margin-top:18px;padding-top:16px}.edit{display:grid;gap:12px;max-width:700px}label{display:block;margin:10px 0}label>input:not([type=checkbox]),textarea{display:block;width:100%;box-sizing:border-box;margin-top:4px}summary{cursor:pointer}input[type=checkbox]{margin-right:8px}
</style>
