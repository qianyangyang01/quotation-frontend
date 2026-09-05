<script setup lang="ts">
import { computed, ref } from 'vue'
import { request } from '@/services/http'
import { applyPurchasePaste, emptyPurchasePasteRow, PURCHASE_PASTE_COLUMNS, PURCHASE_PASTE_LIMIT, validatePurchasePaste } from '@/data/purchasePaste'
import { readPurchaseClipboard } from '@/data/purchaseClipboard'

const emit = defineEmits<{ close: []; saved: [count: number] }>()
const grid = ref(Array.from({ length: 10 }, emptyPurchasePasteRow))
const selected = ref({ row: 0, col: 0 })
const busy = ref(false)
const message = ref('')
const confirmClose = ref(false)
const check = computed(() => validatePurchasePaste(grid.value))
const errors = computed(() => new Map(check.value.issues.map(issue => [`${issue.row}:${issue.column}`, issue.message])))
const table = ref<HTMLElement | null>(null)
function paste(event: ClipboardEvent, row: number, col: number) {
  event.preventDefault()
  if (busy.value) return
  try {
    if (!event.clipboardData) throw new Error('未读取到剪贴板内容，请重新复制单元格区域')
    const cells = readPurchaseClipboard(event.clipboardData, col)
    grid.value = applyPurchasePaste(grid.value, cells, row, col)
    message.value = cells.length ? `已粘贴${cells.length}行，空白单元格已跳过并保留列位置` : '剪贴板中没有表格文字'
  } catch (error) { message.value = error instanceof Error ? error.message : '粘贴失败' }
}
function focusCell(row: number, col: number) {
  const input = table.value?.querySelector<HTMLInputElement>(`[data-cell="${row}:${col}"]`)
  input?.focus(); input?.scrollIntoView({ block: 'nearest', inline: 'nearest' })
}
function move(event: KeyboardEvent, row: number, col: number) {
  if (event.key === 'Enter') { event.preventDefault(); focusCell(Math.min(grid.value.length - 1, row + (event.shiftKey ? -1 : 1)), col) }
}
function close() { if (busy.value) return; if (check.value.records.length) confirmClose.value = true; else emit('close') }
async function save() {
  if (!check.value.canSave || busy.value) return
  busy.value = true; message.value = '正在校验并保存，请稍候…'
  const count = check.value.records.length
  try {
    await request('/purchase-products/paste', { method: 'POST', body: JSON.stringify(check.value.records), signal: AbortSignal.timeout(60_000) })
    grid.value = Array.from({ length: 10 }, emptyPurchasePasteRow)
    emit('saved', count)
  } catch (error) { message.value = error instanceof DOMException && (error.name === 'TimeoutError' || error.name === 'AbortError') ? '保存响应超时，数据已保留。请先在采购列表核对SKU是否已保存，再重试；已有SKU不会重复新增。' : error instanceof Error ? error.message : '保存失败，数据已保留，请重试' }
  finally { busy.value = false }
}
</script>

<template>
  <Teleport to="body">
    <div class="paste-overlay" @keydown.esc.prevent="close">
      <section class="paste-dialog" role="dialog" aria-modal="true" aria-labelledby="paste-title">
        <header><div><h2 id="paste-title">采购粘贴新增</h2><p>按采购新模版的32列表头录入。只复制数据，点击起始单元格后按 Ctrl + V。</p></div><button :disabled="busy" aria-label="关闭粘贴新增" @click="close">×</button></header>
        <div class="paste-tools"><span>当前：第{{ selected.row + 1 }}行 · {{ PURCHASE_PASTE_COLUMNS[selected.col]?.[0] }}</span><button :disabled="busy || grid.length >= PURCHASE_PASTE_LIMIT" @click="grid.push(...Array.from({ length: Math.min(5, PURCHASE_PASTE_LIMIT-grid.length) }, emptyPurchasePasteRow))">增加5行</button><small>最多100行 · 向右滚动查看全部列</small></div>
        <p class="paste-help">必填：正式SKU、克重、起订量、基准采购单价；未包邮时需1件及10件总运费。其他列可留空，长宽高、阶梯价填写时须完整。表头星号沿用原表，保存以本页校验为准。</p>
        <div ref="table" class="paste-grid"><table><thead><tr><th class="row-index">行</th><th v-for="([label, field], c) in PURCHASE_PASTE_COLUMNS" :key="field"><small>{{ String.fromCharCode(65 + Math.floor(c / 26) - 1).replace('@', '') }}{{ String.fromCharCode(65 + c % 26) }}</small>{{ label }}</th><th>操作</th></tr></thead><tbody>
          <tr v-for="(row, r) in grid" :key="r"><th class="row-index">{{ r + 1 }}</th><td v-for="([label, field], c) in PURCHASE_PASTE_COLUMNS" :key="field" :class="{ invalid: errors.has(`${r}:${c}`) }"><input v-model="row[c]" :data-cell="`${r}:${c}`" :aria-label="`第${r + 1}行 ${label}`" :aria-invalid="errors.has(`${r}:${c}`)" :title="errors.get(`${r}:${c}`) || row[c]" :disabled="busy" autocomplete="off" @focus="selected = { row:r, col:c }" @paste="paste($event, r, c)" @keydown="move($event, r, c)"><small v-if="errors.has(`${r}:${c}`)">{{ errors.get(`${r}:${c}`) }}</small></td><td><button :disabled="busy" :aria-label="`删除第${r + 1}行`" @click="grid.splice(r,1)">删除</button></td></tr>
        </tbody></table></div>
        <div v-if="check.issues.length" class="paste-errors"><b>{{ check.issues.length }}处需要修正</b><button v-for="(issue,i) in check.issues.slice(0,5)" :key="i" @click="focusCell(issue.row,issue.column)">第{{ issue.row + 1 }}行 {{ PURCHASE_PASTE_COLUMNS[issue.column]?.[0] }}：{{ issue.message }}</button></div>
        <p v-if="message" class="paste-message" role="status">{{ message }}</p>
        <footer><span>已填写 {{ check.records.length }} 条<small>空行不保存；新增时遇到已有SKU会提示，不覆盖原商品。</small></span><div><button :disabled="busy" @click="close">取消</button><button class="primary" :disabled="busy || !check.canSave" @click="save">{{ busy ? '正在保存…' : `保存新增 ${check.records.length} 条` }}</button></div></footer>
        <div v-if="busy" class="paste-progress" role="progressbar" aria-label="正在保存采购数据"><span /></div>
        <div v-if="confirmClose" class="paste-close-confirm" role="alert"><span>关闭后将丢弃尚未保存的粘贴数据。</span><button @click="confirmClose=false">继续编辑</button><button @click="emit('close')">丢弃并关闭</button></div>
      </section>
    </div>
  </Teleport>
</template>

<style scoped>
.paste-overlay{position:fixed;inset:0;z-index:1100;background:#14253f80;display:flex;align-items:center;justify-content:center;padding:24px;color:#19334c}.paste-dialog{background:white;border-radius:14px;width:min(1500px,96vw);max-height:94vh;display:flex;flex-direction:column;overflow:hidden;box-shadow:0 20px 80px #10203833}.paste-dialog header{padding:22px 26px;display:flex;justify-content:space-between;border-bottom:1px solid #dde6ee}.paste-dialog h2{margin:0 0 8px}.paste-dialog p{font-size:13px;margin:0;color:#668097}.paste-dialog button{padding:9px 14px;border:1px solid #cbd9e5;border-radius:6px;background:white;color:#31526c;cursor:pointer}.paste-dialog button:disabled{opacity:.5;cursor:not-allowed}.paste-tools{display:flex;align-items:center;gap:16px;padding:14px 24px;font-size:13px}.paste-tools small{color:#71859a}.paste-dialog .paste-help{padding:0 24px 14px;line-height:1.7}.paste-grid{overflow:auto;margin:0 24px;min-height:200px;flex:1;border:1px solid #cedbe6}.paste-grid table{border-collapse:separate;border-spacing:0;font-size:12px}.paste-grid th,.paste-grid td{border-right:1px solid #dde6ee;border-bottom:1px solid #dde6ee;min-width:160px;vertical-align:top}.paste-grid th{background:#f1f5f9;text-align:left;padding:9px;position:sticky;top:0;z-index:2;white-space:nowrap}.paste-grid th small{display:block;color:#8b9cac;margin-bottom:5px}.paste-grid .row-index{min-width:42px;width:42px;position:sticky;left:0;z-index:1}.paste-grid thead .row-index{z-index:3}.paste-grid input{border:2px solid transparent;background:transparent;box-sizing:border-box;padding:12px 8px;width:100%;min-width:160px;font:inherit;color:#19334c;border-radius:0}.paste-grid input:focus{outline:none;border-color:#2677f0;background:#eef6ff}.paste-grid td.invalid{background:#fff1ef}.paste-grid td>small{display:block;color:#c13627;max-width:190px;padding:0 8px 8px}.paste-grid td>button{margin:5px}.paste-errors{padding:10px 24px;background:#fff5ee;font-size:12px;max-height:95px;overflow:auto}.paste-errors b{margin-right:14px;color:#ae3a22}.paste-errors button{border:0;background:none;color:#b44225;padding:4px 8px}.paste-dialog .paste-message{padding:10px 24px;color:#245a87}.paste-dialog footer{display:flex;align-items:center;justify-content:space-between;padding:18px 24px;border-top:1px solid #dde6ee;font-size:14px}.paste-dialog footer small{display:block;font-size:12px;color:#7890a3;margin-top:6px}.paste-dialog footer div{display:flex;gap:10px}.paste-dialog .primary{background:#ed861c;color:#fff;border-color:#ed861c}.paste-close-confirm{padding:16px 24px;background:#fff4e1;display:flex;align-items:center;gap:12px}.paste-progress{height:4px;background:#ffead2;overflow:hidden}.paste-progress span{display:block;height:100%;width:35%;background:#ec871c;animation:progress 1.2s ease-in-out infinite}@keyframes progress{from{transform:translateX(-100%)}to{transform:translateX(400%)}}@media(max-width:700px){.paste-overlay{padding:8px}.paste-dialog footer{gap:10px;flex-wrap:wrap}.paste-tools{flex-wrap:wrap}}
</style>
