<script setup lang="ts">
import { computed, ref } from 'vue'
import { FOB_COLUMNS, readFobClipboard, type FobPatch } from '@/data/fobClipboard'
import { previewFobPaste, confirmFobPaste, loadFobRecord, loadFobHistory, type FobPreview, type FobRecord, type FobHistory } from '@/services/fobPurchase'
import type { PasteSavedCounts } from '@/services/purchasePaste'

const emit = defineEmits<{ close: []; saved: [counts: PasteSavedCounts] }>()
const rows = ref<FobPatch[]>([])
const pending = ref<{ rows: FobPatch[]; preview: FobPreview } | null>(null)
const busy = ref(false), message = ref(''), confirmClose = ref(false), lookupSku = ref('')
const savedRecord = ref<FobRecord | null>(null), history = ref<FobHistory[]>([])
const counts = computed(() => ({ added: pending.value?.preview.rows.filter(r => r.action === 'create').length || 0,
  updated: pending.value?.preview.rows.filter(r => r.action === 'update').length || 0,
  unchanged: pending.value?.preview.rows.filter(r => r.action === 'unchanged').length || 0 }))
function paste(event: ClipboardEvent) {
  event.preventDefault()
  if (busy.value || pending.value) return
  try {
    if (!event.clipboardData) throw new Error('请直接复制 Excel/WPS 单元格后粘贴')
    const next = readFobClipboard(event.clipboardData)
    if (rows.value.length + next.length > 100) throw new Error('合计超过100行，请先保存本批次，或移除不需要的行')
    rows.value.push(...next); savedRecord.value = null; history.value = []
    message.value = `已追加${next.length}行，共${rows.value.length}行；尚未保存，请预览识别结果。`
  } catch (e) { message.value = e instanceof Error ? e.message : '粘贴失败' }
}
async function preview() {
  if (busy.value || !rows.value.length) return
  busy.value = true; message.value = ''
  try {
    const snapshot = rows.value.map(row => ({ ...row }))
    const result = await previewFobPaste(snapshot)
    pending.value = { rows: snapshot, preview: result }
    message.value = result.canSave ? '请核对识别结果和更新差异，确认后统一保存。' : '标红行存在待修正资料；请返回编辑后重新预览，本批次尚未保存。'
  } catch (e) { message.value = e instanceof Error ? e.message : '预览失败，数据已保留' }
  finally { busy.value = false }
}
async function save() {
  if (busy.value || !pending.value?.preview.canSave) return
  busy.value = true
  try {
    const result = await confirmFobPaste(pending.value.rows, pending.value.preview)
    message.value = `保存完成：新增${result.added}条，更新${result.updated}条，无变化${result.unchanged}条，同批重复跳过${result.skipped}条。`
    rows.value = []; pending.value = null; emit('saved', result)
  } catch (e) {
    pending.value = null
    message.value = `${e instanceof Error ? e.message : '保存未完成'}。数据已保留，请重新预览核对实际结果，再确认保存。`
  } finally { busy.value = false }
}
async function lookup() {
  if (busy.value || !lookupSku.value.trim()) return
  busy.value = true; savedRecord.value = null; history.value = []; message.value = ''
  try {
    const sku = lookupSku.value.toUpperCase().replace(/\s+/g, '')
    savedRecord.value = await loadFobRecord(sku)
    history.value = await loadFobHistory(sku)
  } catch (e) { message.value = e instanceof Error ? e.message : '查询失败' }
  finally { busy.value = false }
}
function close() {
  if (busy.value) return
  if (pending.value) { pending.value = null; return }
  if (rows.value.length) { confirmClose.value = true; return }
  emit('close')
}
</script>

<template>
  <Teleport to="body">
    <div class="fob-overlay" @keydown.esc.stop.prevent="close">
      <section class="fob-dialog" role="dialog" aria-modal="true" aria-labelledby="fob-paste-title">
        <header><div><h2 id="fob-paste-title">FOB 粘贴新增/更新</h2><p>直接复制国际站批发采购表中的一行或多行。已有FOB SKU更新，空白保留原值。</p></div><button :disabled="busy" aria-label="关闭FOB粘贴" @click="close">×</button></header>
        <div class="instructions">与采购粘贴新增一致，自动过滤图片，只导入文字。支持原表整行、从SKU至运费列的连续区域，或带表头的部分列。最多100行，可分次追加；单元格内的多档采购价自动解析并完整保留原文。</div>
        <div class="fob-content">
          <template v-if="!pending">
            <textarea class="paste-target" aria-label="FOB表格粘贴区域" placeholder="点击这里，按 Ctrl + V 粘贴 Excel/WPS 商品行" :disabled="busy" @paste="paste" @input="($event.target as HTMLTextAreaElement).value=''" />
            <p class="help">采购价、运费填写新原文后会整组替换旧阶梯；留空则保留。通过此入口保存的数据统一标记为“FOB数据”，不覆盖普通采购资料及历史报价。</p>
            <div v-if="rows.length" class="grid"><table><thead><tr><th>行</th><th v-for="[label, field] in FOB_COLUMNS" :key="field">{{ label }}</th><th>操作</th></tr></thead><tbody><tr v-for="(row, i) in rows" :key="i"><th>{{ i + 1 }}</th><td v-for="[label, field] in FOB_COLUMNS" :key="field"><textarea v-model="row[field]" :aria-label="`第${i + 1}行 ${label}`" :disabled="busy" /></td><td><button :disabled="busy" :aria-label="`移除第${i + 1}行`" @click="rows.splice(i,1)">移除</button></td></tr></tbody></table></div>
          </template>
          <section v-else aria-label="FOB识别和变更预览">
            <h3>新增 {{ counts.added }} 条 · 更新 {{ counts.updated }} 条 · 无变化 {{ counts.unchanged }} 条</h3>
            <p v-if="pending.preview.skipped.length" class="warning">同批内容完全相同的重复行已跳过：{{ pending.preview.skipped.map(r => `第${r.sourceRow}行 ${r.sku}`).join('、') }}</p>
            <article v-for="row in pending.preview.rows" :key="row.sku" :class="{ invalid: row.issues.length }">
              <h4>第{{ row.sourceRow }}行 · {{ row.sku }} · {{ row.action === 'create' ? '新增' : row.action === 'update' ? '更新' : '无变化' }} <span class="fob-source">FOB数据</span></h4>
              <p v-for="issue in row.issues" :key="issue" class="error">{{ issue }}</p>
              <p v-for="notice in row.notices" :key="notice" class="warning">{{ notice }}</p>
              <template v-if="row.effective.parsed">
                <p>起订量 {{ row.effective.parsed.minOrderQty }} · 下单倍数 {{ row.effective.parsed.orderMultiple }} · 运费采用：{{ row.effective.parsed.freight.basis }}＝¥{{ row.effective.parsed.freight.unitFreightCny }}/单位</p>
                <table><thead><tr><th>采购阶梯</th><th>数量区间</th><th>不含票采购价</th><th>均摊运费</th></tr></thead><tbody><tr v-for="(tier,i) in row.effective.parsed.priceTiers" :key="i"><td>阶梯{{ i + 1 }}</td><td>{{ tier.minQty }}{{ tier.maxQty == null ? '起' : `—${tier.maxQty}` }}{{ tier.unit }}</td><td>¥{{ tier.unitPriceCny }}</td><td>¥{{ row.effective.parsed.freight.unitFreightCny }}</td></tr></tbody></table>
              </template>
              <details v-if="row.changes.length" :open="row.action === 'update'"><summary>查看 {{ row.changes.length }} 项修改</summary><table><thead><tr><th>字段</th><th>原值</th><th>保存后</th></tr></thead><tbody><tr v-for="change in row.changes" :key="change.field"><th>{{ change.label }}</th><td>{{ change.before || '（空）' }}</td><td>{{ change.after || '（空）' }}</td></tr></tbody></table></details>
              <p v-else>原文无变化，相同内容不重复生成修改记录。</p>
            </article>
          </section>
          <details v-if="!pending" class="lookup"><summary>查询已保存的FOB资料与更新记录</summary><div><input v-model="lookupSku" aria-label="查询已保存FOB SKU" placeholder="输入 SKU" :disabled="busy" @keyup.enter="lookup"><button :disabled="busy || !lookupSku.trim()" @click="lookup">查询已存资料</button></div><article v-if="savedRecord"><h4>{{ savedRecord.sku }} · {{ savedRecord.updatedAt }} <span class="fob-source">FOB数据</span></h4><p>采购价原文</p><pre>{{ savedRecord.priceRaw }}</pre><p>运费原文</p><pre>{{ savedRecord.freightRaw }}</pre><p v-if="savedRecord.parsed">已保存 {{ savedRecord.parsed.priceTiers.length }} 档 · {{ savedRecord.parsed.freight.basis }}＝¥{{ savedRecord.parsed.freight.unitFreightCny }}/单位</p><details v-for="item in history" :key="item.createdAt"><summary>{{ item.createdAt }} · {{ item.actorAccount }}</summary><p v-for="(c,i) in item.changes" :key="i">{{ c.label }}：{{ c.before || '（空）' }} → {{ c.after }}</p></details></article></details>
        </div>
        <p v-if="message" class="message" role="status">{{ message }}</p>
        <footer><span>本批次 {{ rows.length }} 行 · 确认后统一保存</span><div><button :disabled="busy" @click="close">{{ pending ? '返回编辑' : '关闭' }}</button><button v-if="pending" class="primary" :disabled="busy || !pending.preview.canSave" @click="save">{{ busy ? '正在保存…' : '确认全部保存' }}</button><button v-else class="primary" :disabled="busy || !rows.length" @click="preview">{{ busy ? '正在校验…' : '预览识别与变更' }}</button></div></footer>
        <div v-if="confirmClose" class="discard" role="alert">关闭将丢弃尚未保存的粘贴内容。<button @click="confirmClose=false">继续编辑</button><button @click="emit('close')">丢弃并关闭</button></div>
      </section>
    </div>
  </Teleport>
</template>

<style scoped>
.fob-overlay{position:fixed;inset:0;z-index:1100;background:#14253f80;display:flex;align-items:center;justify-content:center;padding:24px;color:#19334c}.fob-dialog{display:flex;flex-direction:column;width:min(1500px,96vw);max-height:94vh;background:#fff;border-radius:14px;box-shadow:0 20px 80px #10203833}.fob-dialog header{display:flex;justify-content:space-between;gap:20px;padding:20px 24px;border-bottom:1px solid #dde6ee}.fob-dialog h2{margin:0 0 8px;font-size:21px}.fob-dialog p{font-size:13px;line-height:1.6;white-space:pre-wrap;overflow-wrap:anywhere}.fob-dialog header p{margin:0;color:#668097}.instructions,.help{color:#667c8c;font-size:12px;padding:12px 24px;line-height:1.7}.help{padding:0}.fob-content{padding:0 24px 16px;overflow:auto;min-height:160px}.paste-target{box-sizing:border-box;width:100%;min-height:70px;border:2px dashed #e9ab51;background:#fffbf3;border-radius:8px;padding:16px;resize:vertical;font:inherit}.grid{overflow:auto;max-height:42vh}.fob-dialog table{width:100%;border-collapse:collapse;font-size:12px;table-layout:fixed}.fob-dialog th,.fob-dialog td{padding:8px;border:1px solid #dce5ec;text-align:left;vertical-align:top;white-space:pre-wrap;overflow-wrap:anywhere}.fob-dialog th{background:#f2f6f8}.grid table{min-width:2200px;table-layout:auto}.grid td{padding:0}.grid textarea{width:160px;min-height:105px;box-sizing:border-box;border:0;padding:8px;resize:vertical;font:inherit}.grid th:nth-child(4),.grid th:nth-child(5){min-width:210px}.fob-dialog button{padding:9px 14px;border:1px solid #cbd9e5;border-radius:6px;background:#fff;color:#31526c;cursor:pointer}.fob-dialog button:disabled{opacity:.5;cursor:not-allowed}.fob-dialog .primary{background:#ed861c;border-color:#ed861c;color:#fff}.fob-dialog article{margin:12px 0;padding:12px;border:1px solid #dce5ec;border-radius:8px}.fob-dialog h4{margin:0 0 8px}.fob-dialog .invalid{border-color:#d76452;background:#fff9f7}.error{color:#b92c20}.warning{color:#9c5c06}.fob-dialog details{margin-top:12px}.fob-dialog summary{cursor:pointer;font-size:13px;color:#805900}.message{margin:0;padding:10px 24px;background:#f1f7fc}.fob-dialog footer{display:flex;justify-content:space-between;gap:10px;padding:16px 24px;border-top:1px solid #dde6ee;font-size:13px}.fob-dialog footer div{display:flex;gap:10px}.lookup>div{display:flex;gap:10px;margin:10px 0}.lookup input{min-width:220px;padding:8px;border:1px solid #cbd9e5;border-radius:6px}.lookup pre{white-space:pre-wrap;font:inherit;font-size:13px}.discard{padding:14px 24px;background:#fff3df;font-size:13px}.discard button{margin-left:10px}@media(max-width:700px){.fob-overlay{padding:8px}.fob-dialog footer{flex-wrap:wrap}.fob-dialog header,.fob-content{padding:14px}.instructions{padding:10px 14px}}
.fob-source{display:inline-block;margin-left:8px;padding:3px 8px;border-radius:8px;background:#e9f5ef;color:#207b54;font-size:11px;font-weight:600}</style>
