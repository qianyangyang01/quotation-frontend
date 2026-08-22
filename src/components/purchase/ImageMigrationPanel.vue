<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'
import { completeImageMigration, createImageMigration, getImageMigration, uploadImageMigrationPart, type ImageMigrationJob } from '@/services/imageMigrations'

const emit = defineEmits<{ close: [] }>()
const jobId = ref('')
const job = ref<ImageMigrationJob | null>(null)
const busy = ref(false)
const message = ref('')
let pollTimer = 0

function report(error: unknown) { message.value = error instanceof Error ? error.message : '迁移任务操作失败' }
async function chooseManifest(event: Event) {
  const input = event.target as HTMLInputElement; const file = input.files?.[0]; if (!file) return
  busy.value = true; message.value = ''
  try { job.value = await createImageMigration(file); jobId.value = job.value.id; message.value = '清单校验通过，请按顺序上传 ZIP 分卷。' }
  catch (error) { report(error) }
  finally { busy.value = false; input.value = '' }
}
async function query() {
  if (!jobId.value.trim()) return
  busy.value = true
  try { job.value = await getImageMigration(jobId.value.trim()); startPolling() }
  catch (error) { report(error) }
  finally { busy.value = false }
}
async function chooseParts(event: Event) {
  const input = event.target as HTMLInputElement; const files = [...(input.files || [])]; if (!job.value || !files.length) return
  busy.value = true; message.value = ''
  try {
    let part = job.value.uploadedParts + 1
    for (const file of files) { job.value = await uploadImageMigrationPart(job.value.id, part, file); message.value = `已上传分卷 ${part}/${job.value.uploadedParts}`; part += 1 }
  } catch (error) { report(error) }
  finally { busy.value = false; input.value = '' }
}
async function complete() {
  if (!job.value) return
  busy.value = true
  try { job.value = await completeImageMigration(job.value.id); message.value = '后台校验与关联处理中，可关闭窗口后凭任务编号继续查询。'; startPolling() }
  catch (error) { report(error) }
  finally { busy.value = false }
}
function startPolling() {
  window.clearInterval(pollTimer)
  if (job.value?.status !== 'processing') return
  pollTimer = window.setInterval(async () => {
    if (!job.value) return
    try { job.value = await getImageMigration(job.value.id); if (job.value.status !== 'processing') window.clearInterval(pollTimer) } catch { window.clearInterval(pollTimer) }
  }, 3000)
}
onBeforeUnmount(() => window.clearInterval(pollTimer))
</script>

<template>
  <section class="migration" role="dialog" aria-modal="true" aria-label="采购图片批量迁移">
    <header><div><small>RESUMABLE IMAGE MIGRATION</small><h2>采购图片批量迁移</h2><p>使用“SKU、图片类型、文件名、SHA256”清单和独立 ZIP 分卷；任务失败不会发布半成品。</p></div><button @click="emit('close')">×</button></header>
    <div class="steps">
      <article><b>1. 新建或恢复任务</b><label>上传迁移清单<input :disabled="busy" type="file" accept=".xlsx" @change="chooseManifest"></label><div><input v-model="jobId" placeholder="粘贴历史任务 UUID"><button :disabled="busy || !jobId" @click="query">查询</button></div></article>
      <article><b>2. 上传 ZIP 分卷</b><p>每卷最多 512MB；同一任务按分卷编号断点续传。</p><input :disabled="busy || !job" type="file" accept=".zip" multiple @change="chooseParts"></article>
      <article><b>3. 开始校验和关联</b><p>真实类型、SHA-256、路径穿越、压缩体积和重复对象均在服务器校验。</p><button :disabled="busy || !job || job.uploadedParts < 1 || !['uploading','awaiting-parts'].includes(job.status)" @click="complete">开始后台处理</button></article>
    </div>
    <div v-if="job" class="status"><span><small>任务编号</small><b>{{ job.id }}</b></span><span><small>状态</small><b>{{ job.status }}</b></span><span><small>清单 / 分卷</small><b>{{ job.summary.manifestRows || 0 }} / {{ job.uploadedParts }}</b></span><span><small>完成 / 失败 / 待处理</small><b>{{ job.completed }} / {{ job.failed }} / {{ job.pending }}</b></span><p v-if="job.error">{{ job.error }}</p></div>
    <footer><span>{{ busy ? '正在处理，请勿重复提交…' : message }}</span><button @click="emit('close')">关闭</button></footer>
  </section>
</template>

<style scoped>
.migration{position:relative;width:min(980px,96vw);margin:auto;padding:24px;border-radius:14px;background:#fff;box-shadow:0 24px 70px #11182740}.migration>header{display:flex;justify-content:space-between}.migration small{color:#d87600;font-size:10px;font-weight:900;letter-spacing:.15em}.migration h2{margin:5px 0}.migration p{color:#75818a;font-size:12px}.migration header>button{border:0;background:none;font-size:26px}.steps{display:grid;grid-template-columns:repeat(3,1fr);gap:12px;margin:20px 0}.steps article{display:grid;align-content:start;gap:11px;padding:16px;border:1px solid #e1e6ea;border-radius:10px}.steps label{display:grid;gap:7px;font-size:11px}.steps div{display:flex;gap:7px}.steps input{min-width:0;padding:8px;border:1px solid #dbe2e7;border-radius:6px}.steps button,.migration footer button{padding:9px 13px;border:0;border-radius:7px;background:#ff9900;font-weight:800}.steps button:disabled{opacity:.45}.status{display:grid;grid-template-columns:2fr 1fr 1fr 1.2fr;gap:8px;padding:13px;border-radius:9px;background:#f6f8fa}.status span{display:grid;gap:5px}.status b{font-size:12px;word-break:break-all}.status p{grid-column:1/-1;color:#b64239}.migration footer{display:flex;justify-content:space-between;align-items:center;margin-top:18px;padding-top:15px;border-top:1px solid #e5e9ec;color:#697680;font-size:12px}@media(max-width:800px){.steps{grid-template-columns:1fr}.status{grid-template-columns:1fr 1fr}}
</style>
