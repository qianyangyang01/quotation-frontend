<script setup lang="ts">
import { computed, ref } from 'vue'
import AppTopbar from '@/components/AppTopbar.vue'
import { ApiError } from '@/services/http'
import { approveMigrationBatch, executeMigrationBatch, migrationEntryKey, rollbackMigrationBatch, uploadMigrationSource, type MigrationBatch } from '@/services/businessMigrations'

const sourceType = ref<MigrationBatch['sourceType']>('legacy-browser-report')
const file = ref<File | null>(null)
const batch = ref<MigrationBatch | null>(null)
const approved = ref<string[]>([])
const ownerMappingsText = ref('{}')
const conflictResolutionsText = ref('{}')
const loading = ref(false)
const message = ref('')
const error = ref('')
const entries = computed(() => batch.value?.report.entries || [])

function selected(event: Event) { file.value = (event.target as HTMLInputElement).files?.[0] || null }
function parseMap(value: string, label: string) { const parsed = JSON.parse(value) as unknown; if (!parsed || Array.isArray(parsed) || typeof parsed !== 'object') throw new Error(`${label}必须是JSON对象`); return parsed as Record<string, string> }
function failed(reason: unknown) { error.value = reason instanceof ApiError ? `${reason.message}（requestId: ${reason.requestId}）` : reason instanceof Error ? reason.message : '操作失败' }
async function upload() { if (!file.value) { error.value = '请选择迁移源文件'; return } loading.value = true; error.value = ''; message.value = ''; try { batch.value = await uploadMigrationSource(sourceType.value, file.value); approved.value = entries.value.filter(item => item.decision === 'migrate').map(migrationEntryKey); message.value = batch.value.errors.length ? '解析完成，但存在阻断错误，请先处理差异。' : '解析完成，请确认白名单。' } catch (reason) { failed(reason) } finally { loading.value = false } }
async function approve() { if (!batch.value) return; loading.value = true; error.value = ''; try { batch.value = await approveMigrationBatch(batch.value.id, approved.value, parseMap(ownerMappingsText.value, '账号映射'), parseMap(conflictResolutionsText.value, '冲突处理')); message.value = '白名单已审批，可以在UAT执行迁移。' } catch (reason) { failed(reason) } finally { loading.value = false } }
async function execute() { if (!batch.value) return; loading.value = true; error.value = ''; try { batch.value = await executeMigrationBatch(batch.value.id); message.value = '迁移执行完成，请核对数量与业务数据。' } catch (reason) { failed(reason) } finally { loading.value = false } }
async function rollback() { if (!batch.value) return; loading.value = true; error.value = ''; try { batch.value = await rollbackMigrationBatch(batch.value.id); message.value = '本迁移批次创建的数据已回滚。' } catch (reason) { failed(reason) } finally { loading.value = false } }
</script>

<template>
  <AppTopbar />
  <main class="migration-page"><header><small>DATA MIGRATION CONTROL</small><h1>真实业务数据迁移</h1><p>仅超级管理员可用。所有数据先预览、审批并在UAT重复验证，再进入生产。</p></header>
    <section class="upload"><label>数据来源<select v-model="sourceType"><option value="legacy-browser-report">本地浏览器JSON盘点报告</option><option value="sumao-logistics-zip">速猫ERP物流ZIP</option></select></label><label>源文件<input type="file" :accept="sourceType==='legacy-browser-report'?'.json':'.zip'" @change="selected"></label><button :disabled="loading||!file" @click="upload">{{ loading ? '处理中…' : '上传并重新校验' }}</button></section>
    <p v-if="message" class="success">{{ message }}</p><p v-if="error" class="error">{{ error }}</p>
    <template v-if="batch"><section class="summary"><article><b>{{ batch.status }}</b><span>批次状态</span></article><article><b>{{ batch.counts.total || 0 }}</b><span>数据条目</span></article><article><b>{{ batch.errors.length }}</b><span>阻断错误</span></article><article><b>{{ batch.diff.actualPriceRows || 0 }}</b><span>物流价格段</span></article></section>
      <section v-if="batch.errors.length" class="errors"><h2>阻断错误</h2><ul><li v-for="item in batch.errors" :key="`${item.source}-${item.message}`"><b>{{ item.source || 'source' }}</b> {{ item.message }}</li></ul></section>
      <section><h2>迁移白名单</h2><table><thead><tr><th>选择</th><th>类别</th><th>来源</th><th>数量</th><th>默认决策</th><th>原因</th></tr></thead><tbody><tr v-for="item in entries" :key="migrationEntryKey(item)"><td><input v-model="approved" type="checkbox" :value="migrationEntryKey(item)" :disabled="item.decision==='exclude'"></td><td>{{ item.category }}</td><td>{{ item.container }}/{{ item.key }}</td><td>{{ item.count }}</td><td>{{ item.decision }}</td><td>{{ item.reason }}</td></tr></tbody></table></section>
      <section class="maps"><label>账号映射JSON<textarea v-model="ownerMappingsText" rows="4"></textarea></label><label>冲突处理JSON<textarea v-model="conflictResolutionsText" rows="4"></textarea></label></section>
      <footer><button :disabled="loading||batch.status!=='pending_review'||batch.errors.length>0||approved.length===0" @click="approve">确认白名单</button><button :disabled="loading||!['approved','failed'].includes(batch.status)" @click="execute">执行迁移</button><button class="danger" :disabled="loading||batch.status!=='completed'" @click="rollback">回滚本批次</button></footer>
    </template>
  </main>
</template>

<style scoped>
.migration-page{box-sizing:border-box;min-height:calc(100vh - 72px);padding:32px max(28px,calc((100vw - 1400px)/2));background:#f4f7f9;color:#172431;font-family:"Microsoft YaHei",sans-serif}.migration-page>header small{color:#d87600;font-weight:900;letter-spacing:.16em}.migration-page h1{margin:7px 0}.migration-page p{color:#66747e}.upload,.maps,footer{display:flex;gap:14px;align-items:end;margin:20px 0;padding:18px;border:1px solid #dfe6ea;border-radius:12px;background:#fff}.upload label,.maps label{display:grid;flex:1;gap:7px;font-size:12px;font-weight:800}select,input,textarea{box-sizing:border-box;width:100%;padding:10px;border:1px solid #ccd7de;border-radius:8px;background:#fff}button{min-height:40px;padding:0 18px;border:0;border-radius:8px;background:#ff9810;font-weight:900;cursor:pointer}button:disabled{cursor:not-allowed;opacity:.45}.danger{background:#c7483d;color:#fff}.success,.error,.errors{padding:12px 16px;border-radius:9px}.success{background:#e9f8ef;color:#197243}.error,.errors{background:#fff0ef;color:#ad342d}.summary{display:grid;grid-template-columns:repeat(4,1fr);gap:12px}.summary article{display:grid;gap:5px;padding:16px;border:1px solid #dfe6ea;border-radius:10px;background:#fff}.summary b{font-size:21px}.summary span{color:#788690;font-size:11px}section{margin-top:20px}h2{font-size:16px}table{width:100%;border-collapse:collapse;background:#fff;font-size:11px}th,td{padding:10px;border:1px solid #dfe6ea;text-align:left;vertical-align:top}th{background:#edf2f5}.maps textarea{font-family:Consolas,monospace}footer{justify-content:flex-end}@media(max-width:800px){.upload,.maps{align-items:stretch;flex-direction:column}.summary{grid-template-columns:1fr 1fr}table{display:block;overflow:auto}}
</style>
