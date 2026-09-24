<script setup lang="ts">
import { computed, ref } from 'vue'
import { applyPurchasePaste, emptyPurchasePasteRow, PURCHASE_PASTE_COLUMNS, PURCHASE_PASTE_LIMIT, validatePurchasePaste, type PurchasePastePatch } from '@/data/purchasePaste'
import { readPurchaseClipboardResult } from '@/data/purchaseClipboard'
import { previewPurchasePaste, confirmPurchasePaste, type PastePreview, type PasteSavedCounts, type PasteSkipped } from '@/services/purchasePaste'

const emit = defineEmits<{ close: []; saved: [counts: PasteSavedCounts] }>()
const grid = ref(Array.from({ length: 10 }, emptyPurchasePasteRow))
const selected = ref({ row: 0, col: 0 })
const busy = ref(false)
const message = ref('')
const confirmClose = ref(false)
const savedShareText = ref('')
const savedCount = ref(0)
const copying = ref(false)
const showCopyFallback = ref(false)
const check = computed(() => validatePurchasePaste(grid.value, true))
const pending = ref<{ preview: PastePreview; rows: PurchasePastePatch[]; skipped: PasteSkipped[] } | null>(null)
const previewCounts = computed(() => ({
  added: pending.value?.preview.rows.filter(row => row.action === 'create').length || 0,
  updated: pending.value?.preview.rows.filter(row => row.action === 'update').length || 0,
  unchanged: pending.value?.preview.rows.filter(row => row.action === 'unchanged').length || 0,
}))
function displayValue(value: unknown, field: string) {
  if (value == null || value === '') return '（空）'
  return field === 'taxPoint' && typeof value === 'number' ? `${Number((value * 100).toFixed(8))}%` : String(value)
}
const errors = computed(() => new Map(check.value.issues.map(issue => [`${issue.row}:${issue.column}`, issue.message])))
const table = ref<HTMLElement | null>(null)
function paste(event: ClipboardEvent, row: number, col: number) {
  event.preventDefault()
  if (busy.value) return
  try {
    if (!event.clipboardData) throw new Error('未读取到剪贴板内容，请重新复制单元格区域')
    const { rows: cells, skippedImageColumns } = readPurchaseClipboardResult(event.clipboardData, col)
    grid.value = applyPurchasePaste(grid.value, cells, row, col)
    message.value = cells.length ? `已粘贴${cells.length}行${skippedImageColumns ? '，已过滤前3列图片/空白列' : ''}，空白单元格已跳过并保留列位置` : '剪贴板中没有表格文字'
  } catch (error) { message.value = error instanceof Error ? error.message : '粘贴失败' }
}
function focusCell(row: number, col: number) {
  const input = table.value?.querySelector<HTMLInputElement>(`[data-cell="${row}:${col}"]`)
  input?.focus(); input?.scrollIntoView({ block: 'nearest', inline: 'nearest' })
}
function move(event: KeyboardEvent, row: number, col: number) {
  if (event.key === 'Enter') { event.preventDefault(); focusCell(Math.min(grid.value.length - 1, row + (event.shiftKey ? -1 : 1)), col) }
}
function close() { if (busy.value) return; if (pending.value) { pending.value = null; return }; if (grid.value.some(row => row.some(cell => cell.trim()))) confirmClose.value = true; else emit('close') }
async function copySaved() {
  if (!savedShareText.value || busy.value || copying.value) return
  copying.value = true
  try {
    await navigator.clipboard.writeText(savedShareText.value)
    showCopyFallback.value = false
    message.value = `已复制上次成功新增/更新的${savedCount.value}条SKU和品类，可直接粘贴给业务员`
  } catch {
    showCopyFallback.value = true
    message.value = '浏览器未允许自动复制，请选中下方内容，按 Ctrl + C 复制'
  } finally { copying.value = false }
}
async function save() {
  if (!check.value.canSave || busy.value) return
  busy.value = true; message.value = '正在校验并预览，请稍候…'
  const rows = check.value.records
  const skipped = [...check.value.skippedRows]
  try {
    const preview = await previewPurchasePaste(rows)
    pending.value = { preview, rows, skipped }
    message.value = '请核对新增和更新内容，确认后统一保存。'
  } catch (error) { message.value = error instanceof Error ? error.message : '预览失败，数据已保留，请重试' }
  finally { busy.value = false }
}
async function confirmSave() {
  if (!pending.value || busy.value) return
  const snapshot = pending.value
  busy.value = true; message.value = '正在保存，请稍候…'
  try {
    const result = await confirmPurchasePaste(snapshot.rows, snapshot.preview.rows.map(row => row.expected))
    const saved = [...result.added, ...result.updated]
    const skipped = [...snapshot.skipped, ...result.skipped]
    const counts = { added: result.added.length, updated: result.updated.length, unchanged: result.unchanged.length, skipped: skipped.length }
    savedShareText.value = saved.map(record => [record.sku, record.category].map(value => String(value || '').replace(/[\t\r\n]+/g, ' ')).join('\t')).join('\n')
    savedCount.value = saved.length
    showCopyFallback.value = false
    grid.value = Array.from({ length: 10 }, emptyPurchasePasteRow)
    pending.value = null
    message.value = `已成功新增${counts.added}条，更新${counts.updated}条，无变化${counts.unchanged}条，同批重复跳过${counts.skipped}条；更新已计入修改记录。`
    emit('saved', counts)
  } catch (error) {
    pending.value = null
    message.value = error instanceof DOMException && (error.name === 'TimeoutError' || error.name === 'AbortError')
      ? '保存响应超时，数据已保留。请重新预览核对实际结果，再确认保存；已生效且相同的数据会显示无变化。'
      : `${error instanceof Error ? error.message : '保存失败'}。数据已保留，请重新预览并确认。`
  }
  finally { busy.value = false }
}
</script>

<template>
  <Teleport to="body">
    <div class="paste-overlay" @keydown.esc.prevent="close">
      <section class="paste-dialog" role="dialog" aria-modal="true" aria-labelledby="paste-title">
        <header><div><h2 id="paste-title">采购粘贴新增/更新</h2><p>支持整行复制，自动过滤原表前3列图片/空白列。无需表头，点击“报价日期”列后按 Ctrl + V；也可从原表报价日期开始复制。</p></div><button :disabled="busy" aria-label="关闭采购粘贴" @click="close">×</button></header>
        <div class="paste-tools"><span>当前：第{{ selected.row + 1 }}行 · {{ PURCHASE_PASTE_COLUMNS[selected.col]?.[0] }}</span><button :disabled="busy || !!pending || grid.length >= PURCHASE_PASTE_LIMIT" @click="grid.push(...Array.from({ length: Math.min(5, PURCHASE_PASTE_LIMIT-grid.length) }, emptyPurchasePasteRow))">增加5行</button><small>最多100行 · 已有SKU确认后更新，空白保留原值 · 向右滚动查看全部列</small></div>
        <p class="paste-help">已有SKU可只填写需更新的字段，空白保留原值，0及0%正常更新。新SKU必填：正式SKU、克重、起订量、基准采购单价、票点；未包邮时需1件及10件总运费。合并后统一校验，实际变更计入修改记录。</p>
        <div v-show="!pending" ref="table" class="paste-grid"><table><thead><tr><th class="row-index">行</th><th v-for="([label, field], c) in PURCHASE_PASTE_COLUMNS" :key="field"><small>{{ String.fromCharCode(65 + Math.floor(c / 26) - 1).replace('@', '') }}{{ String.fromCharCode(65 + c % 26) }}</small>{{ label }}</th><th>操作</th></tr></thead><tbody>
          <tr v-for="(row, r) in grid" :key="r"><th class="row-index">{{ r + 1 }}</th><td v-for="([label, field], c) in PURCHASE_PASTE_COLUMNS" :key="field" :class="{ invalid: errors.has(`${r}:${c}`) }"><input v-model="row[c]" :data-cell="`${r}:${c}`" :aria-label="`第${r + 1}行 ${label}`" :aria-invalid="errors.has(`${r}:${c}`)" :title="errors.get(`${r}:${c}`) || row[c]" :disabled="busy || !!pending" autocomplete="off" @focus="selected = { row:r, col:c }" @paste="paste($event, r, c)" @keydown="move($event, r, c)"><small v-if="errors.has(`${r}:${c}`)">{{ errors.get(`${r}:${c}`) }}</small></td><td><button :disabled="busy" :aria-label="`删除第${r + 1}行`" @click="grid.splice(r,1)">删除</button></td></tr>
        </tbody></table></div>
        <p v-if="check.skipped.length" class="paste-help">同批重复 SKU 保留第一条，跳过 {{ check.skipped.length }} 条：{{ check.skippedRows.map(row => `第${row.sourceRow}行 ${row.sku}`).join("、") }}</p>
        <section v-if="pending" class="paste-preview" aria-label="采购粘贴变更预览">
          <h3>确认新增 {{ previewCounts.added }} 条、更新 {{ previewCounts.updated }} 条、无变化 {{ previewCounts.unchanged }} 条</h3>
          <p>下列已有SKU将统一更新。空白保留原值，更新计入修改记录，历史报价保持不变。</p>
          <article v-for="row in pending.preview.rows" :key="row.sku">
            <h4>第{{ row.sourceRow }}行 · {{ row.sku }} · {{ row.action === 'create' ? '新增' : row.action === 'update' ? '更新' : '无变化' }}</h4>
            <p v-for="notice in row.notices" :key="notice" class="price-notice">{{ notice }}</p>
            <table v-if="row.changes.length"><thead><tr><th>字段</th><th>原值</th><th>保存后</th></tr></thead><tbody>
              <tr v-for="change in row.changes" :key="change.field"><th>{{ change.label }}</th><td>{{ displayValue(change.before, change.field) }}</td><td>{{ displayValue(change.after, change.field) }}</td></tr>
            </tbody></table>
            <p v-else>内容相同，不重复生成修改记录。</p>
          </article>
        </section>
        <div v-if="check.issues.length" class="paste-errors"><b>{{ check.issues.length }}处需要修正</b><button v-for="(issue,i) in check.issues.slice(0,5)" :key="i" @click="focusCell(issue.row,issue.column)">第{{ issue.row + 1 }}行 {{ PURCHASE_PASTE_COLUMNS[issue.column]?.[0] }}：{{ issue.message }}</button></div>
        <p v-if="message" class="paste-message" role="status">{{ message }}</p>
        <textarea v-if="showCopyFallback" class="copy-fallback" aria-label="已保存的SKU和品类" :value="savedShareText" readonly @focus="($event.target as HTMLTextAreaElement).select()" />
        <footer><span>已填写 {{ check.records.length }} 条<small>{{ savedCount ? `上次成功新增/更新 ${savedCount} 条，可复制SKU和品类。` : '预览后统一确认保存；已有SKU仅更新已填写字段。' }}</small></span><div><button :disabled="busy" @click="close">{{ pending ? '返回编辑' : savedCount && !check.records.length ? '完成' : '取消' }}</button><button v-if="pending" class="primary" :disabled="busy" @click="confirmSave">{{ busy ? '正在保存…' : '确认全部保存' }}</button><button v-else class="primary" :disabled="busy || !check.canSave" @click="save">{{ busy ? '正在校验…' : `预览并保存 ${check.records.length} 条` }}</button><button class="copy-saved" :disabled="busy || copying || !savedShareText" @click="copySaved">{{ copying ? '正在复制…' : '一键复制SKU和品类' }}</button></div></footer>
        <div v-if="busy" class="paste-progress" role="progressbar" aria-label="正在保存采购数据"><span /></div>
        <div v-if="confirmClose" class="paste-close-confirm" role="alert"><span>关闭后将丢弃尚未保存的粘贴数据。</span><button @click="confirmClose=false">继续编辑</button><button @click="emit('close')">丢弃并关闭</button></div>
      </section>
    </div>
  </Teleport>
</template>

<style scoped>
.paste-preview{overflow:auto;min-height:180px;padding:0 24px 16px;flex:1}.paste-preview h3{font-size:17px}.paste-preview h4{margin:18px 0 8px}.paste-preview table{width:100%;table-layout:fixed;border-collapse:collapse;font-size:13px}.paste-preview th,.paste-preview td{border:1px solid #dde6ee;padding:8px;text-align:left;white-space:pre-wrap;overflow-wrap:anywhere}.paste-preview th{background:#f1f5f9}.paste-preview .price-notice{padding:10px;background:#fff5df;color:#805000;margin-bottom:8px}

.paste-dialog .copy-saved{background:#eef1f4;border-color:#d4dbe2;color:#45586b}.copy-fallback{margin:0 24px 12px;min-height:70px}.paste-dialog footer div{flex-wrap:wrap}
.paste-overlay{position:fixed;inset:0;z-index:1100;background:#14253f80;display:flex;align-items:center;justify-content:center;padding:24px;color:#19334c}.paste-dialog{background:white;border-radius:14px;width:min(1500px,96vw);max-height:94vh;display:flex;flex-direction:column;overflow:hidden;box-shadow:0 20px 80px #10203833}.paste-dialog header{padding:22px 26px;display:flex;justify-content:space-between;border-bottom:1px solid #dde6ee}.paste-dialog h2{margin:0 0 8px}.paste-dialog p{font-size:13px;margin:0;color:#668097}.paste-dialog button{padding:9px 14px;border:1px solid #cbd9e5;border-radius:6px;background:white;color:#31526c;cursor:pointer}.paste-dialog button:disabled{opacity:.5;cursor:not-allowed}.paste-tools{display:flex;align-items:center;gap:16px;padding:14px 24px;font-size:13px}.paste-tools small{color:#71859a}.paste-dialog .paste-help{padding:0 24px 14px;line-height:1.7}.paste-grid{overflow:auto;margin:0 24px;min-height:200px;flex:1;border:1px solid #cedbe6}.paste-grid table{border-collapse:separate;border-spacing:0;font-size:12px}.paste-grid th,.paste-grid td{border-right:1px solid #dde6ee;border-bottom:1px solid #dde6ee;min-width:160px;vertical-align:top}.paste-grid th{background:#f1f5f9;text-align:left;padding:9px;position:sticky;top:0;z-index:2;white-space:nowrap}.paste-grid th small{display:block;color:#8b9cac;margin-bottom:5px}.paste-grid .row-index{min-width:42px;width:42px;position:sticky;left:0;z-index:1}.paste-grid thead .row-index{z-index:3}.paste-grid input{border:2px solid transparent;background:transparent;box-sizing:border-box;padding:12px 8px;width:100%;min-width:160px;font:inherit;color:#19334c;border-radius:0}.paste-grid input:focus{outline:none;border-color:#2677f0;background:#eef6ff}.paste-grid td.invalid{background:#fff1ef}.paste-grid td>small{display:block;color:#c13627;max-width:190px;padding:0 8px 8px}.paste-grid td>button{margin:5px}.paste-errors{padding:10px 24px;background:#fff5ee;font-size:12px;max-height:95px;overflow:auto}.paste-errors b{margin-right:14px;color:#ae3a22}.paste-errors button{border:0;background:none;color:#b44225;padding:4px 8px}.paste-dialog .paste-message{padding:10px 24px;color:#245a87}.paste-dialog footer{display:flex;align-items:center;justify-content:space-between;padding:18px 24px;border-top:1px solid #dde6ee;font-size:14px}.paste-dialog footer small{display:block;font-size:12px;color:#7890a3;margin-top:6px}.paste-dialog footer div{display:flex;gap:10px}.paste-dialog .primary{background:#ed861c;color:#fff;border-color:#ed861c}.paste-close-confirm{padding:16px 24px;background:#fff4e1;display:flex;align-items:center;gap:12px}.paste-progress{height:4px;background:#ffead2;overflow:hidden}.paste-progress span{display:block;height:100%;width:35%;background:#ec871c;animation:progress 1.2s ease-in-out infinite}@keyframes progress{from{transform:translateX(-100%)}to{transform:translateX(400%)}}@media(max-width:700px){.paste-overlay{padding:8px}.paste-dialog footer{gap:10px;flex-wrap:wrap}.paste-tools{flex-wrap:wrap}}
</style>
