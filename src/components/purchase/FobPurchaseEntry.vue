<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { emptyFobManualDraft, fobManualPatch } from '@/data/fobManualEntry'
import { FOB_COLUMNS, type FobPatch } from '@/data/fobClipboard'
import { confirmFobPaste, previewFobPaste, readFobWorkbook, uploadFobImage, type FobPreview, type FobWorkbookRows } from '@/services/fobPurchase'
import type { PasteSavedCounts } from '@/services/purchasePaste'
import { decimal } from '@/services/quotationDecimal'
import FobPurchasePreview from './FobPurchasePreview.vue'

const props = defineProps<{ active: boolean }>()
const emit = defineEmits<{ saved: [counts: PasteSavedCounts]; busy: [value: boolean]; dirty: [value: boolean] }>()
const draft = ref(emptyFobManualDraft())
const method = ref<'manual' | 'excel'>('manual')
const busy = ref(false), message = ref(''), fileName = ref('')
const workbook = ref<FobWorkbookRows | null>(null)
const pending = ref<{ rows: FobPatch[]; preview: FobPreview } | null>(null)
const freight = computed(() => {
  if (!draft.value.freightQuantity.trim() || !draft.value.freightTotal.trim()) return '待填写'
  const qty = Number(draft.value.freightQuantity), amount = Number(draft.value.freightTotal)
  return Number.isFinite(qty) && qty > 0 && Number.isFinite(amount) && amount >= 0 ? `¥${decimal(amount).div(qty).toFixed(4)} / 件` : '待填写'
})
watch(busy, value => emit('busy', value), { flush: 'sync' })
watch(draft, () => emit('dirty', true), { deep: true })
watch(() => props.active, () => { pending.value = null; message.value = '' })
onBeforeUnmount(() => emit('busy', false))
function setMethod(value: 'manual' | 'excel') { if (busy.value) return; method.value = value; pending.value = null; message.value = '' }
function addTier() {
  const last = draft.value.tiers.at(-1)
  draft.value.tiers.push({ minQty: last?.maxQty ? String(Number(last.maxQty) + 1) : '', maxQty: '', price: '' })
}
async function selectFile(event: Event) {
  const input = event.target as HTMLInputElement, file = input.files?.[0]
  if (!file || busy.value) return
  pending.value = null; message.value = ''
  if (!/\.xlsx?$/i.test(file.name) || file.size > 10 * 1024 * 1024 || !file.size) { message.value = '请选择不超过10MB的 .xlsx 或 .xls 文件'; input.value = ''; return }
  busy.value = true
  try {
    const result = await readFobWorkbook(file)
    workbook.value = result; fileName.value = file.name; emit('dirty', true)
    message.value = `已读取${result.rows.length}条，请预览核对；尚未保存。`
  } catch (error) { message.value = `${error instanceof Error ? error.message : '文件读取失败'}；原有待录入内容已保留。` }
  finally { busy.value = false; input.value = '' }
}
async function selectImage(event: Event, field: 'productImage' | 'physicalImage') {
  const input = event.target as HTMLInputElement, file = input.files?.[0]
  if (!file || busy.value) return
  if (!file.size || file.size > 5 * 1024 * 1024) { message.value = '图片须非空且不超过5MB'; input.value = ''; return }
  busy.value = true; message.value = ''
  try { draft.value[field] = (await uploadFobImage(file)).url }
  catch (error) { message.value = error instanceof Error ? error.message : '图片上传失败' }
  finally { busy.value = false; input.value = '' }
}
async function preview() {
  if (busy.value) return
  message.value = ''; busy.value = true
  try {
    const rows = method.value === 'manual' ? [fobManualPatch(draft.value)] : workbook.value?.rows
    if (!rows?.length) throw new Error('请先选择FOB Excel文件')
    const snapshot = rows.map(row => ({ ...row }))
    pending.value = { rows: snapshot, preview: await previewFobPaste(snapshot) }
    message.value = pending.value.preview.canSave ? '请核对资料和修改差异，确认后保存到FOB资料库。' : '存在待修正资料，本批次尚未保存。请返回编辑。'
  } catch (error) { message.value = error instanceof Error ? error.message : '预览失败，输入已保留' }
  finally { busy.value = false }
}
async function save() {
  if (busy.value || !pending.value?.preview.canSave) return
  busy.value = true
  try {
    const result = await confirmFobPaste(pending.value.rows, pending.value.preview)
    pending.value = null; draft.value = emptyFobManualDraft(); workbook.value = null
    emit('dirty', false); emit('saved', result)
  } catch (error) {
    pending.value = null
    message.value = `${error instanceof Error ? error.message : '保存结果未确认'}。输入已保留，请重新预览核对实际结果后再保存。`
  } finally { busy.value = false }
}
</script>
<template>
  <div class="fob-entry">
    <div class="methods"><button type="button" :disabled="busy" :aria-pressed="method==='manual'" @click="setMethod('manual')">手工填写</button><button type="button" :disabled="busy" :aria-pressed="method==='excel'" @click="setMethod('excel')">Excel 文件导入</button></div>
    <div class="entry-scroll">
      <template v-if="!pending">
        <form v-if="method==='manual'" @submit.prevent="preview">
          <fieldset :disabled="busy"><div class="fields">
            <label>SKU *<input v-model="draft.sku" aria-label="FOB SKU" placeholder="请输入正式 SKU"></label><label>类别<input v-model="draft.category" aria-label="FOB 类别"></label>
            <label>报价人<input v-model="draft.quotationOwner"></label><label>报价日期<input v-model="draft.quotationDate" type="date"></label>
          </div>
          <details><summary>图片及产品信息 <span>产品图片、实物图、克重、尺寸、材质</span></summary><div class="fields">
            <label>产品图片（最大5MB）<input type="file" accept="image/png,image/jpeg,image/gif,image/webp" aria-label="FOB 产品图片" @change="selectImage($event,'productImage')"><img v-if="draft.productImage" class="product-image" :src="draft.productImage" alt="待保存的产品图片"><button v-if="draft.productImage" type="button" class="text-button" @click="draft.productImage=''">取消本次图片</button></label>
            <label>实物图（最大5MB）<input type="file" accept="image/png,image/jpeg,image/gif,image/webp" aria-label="FOB 实物图" @change="selectImage($event,'physicalImage')"><img v-if="draft.physicalImage" class="product-image" :src="draft.physicalImage" alt="待保存的实物图"><button v-if="draft.physicalImage" type="button" class="text-button" @click="draft.physicalImage=''">取消本次图片</button></label>
            <label>克重（g）<input v-model="draft.weightRaw"></label><label>尺码<input v-model="draft.size"></label><label>颜色<input v-model="draft.color"></label><label>材质<input v-model="draft.material"></label><label class="wide">工厂信息<textarea v-model="draft.factoryInfo" rows="2"></textarea></label>
          </div></details>
          <h3>FOB 采购与运费 <small>采购价填写未税金额</small></h3>
          <div class="fields"><label>起订量（件）*<input v-model="draft.minOrderQty" aria-label="FOB 起订量" type="text" inputmode="numeric" @change="draft.tiers.length===1 && !draft.tiers[0]!.minQty && (draft.tiers[0]!.minQty=draft.minOrderQty)"></label><label>下单倍数（件）*<input v-model="draft.orderMultiple" aria-label="FOB 下单倍数" type="text" inputmode="numeric"></label></div>
          <div class="tier-heading"><span>采购阶梯 *</span><button type="button" class="text-button" @click="addTier">＋ 添加阶梯</button></div>
          <div class="tier-labels"><span>数量起点（件）</span><span>数量终点（件）</span><span>未税单价（元/件）</span><span></span></div>
          <div v-for="(tier,i) in draft.tiers" :key="i" class="tier-row"><input v-model="tier.minQty" :aria-label="`第${i+1}档数量起点`" type="text" inputmode="numeric"><input v-model="tier.maxQty" :aria-label="`第${i+1}档数量终点`" type="text" inputmode="numeric" placeholder="不限"><input v-model="tier.price" :aria-label="`第${i+1}档未税单价`" type="text" inputmode="decimal"><button type="button" :disabled="draft.tiers.length===1" :aria-label="`删除第${i+1}档`" @click="draft.tiers.splice(i,1)">×</button></div>
          <p class="hint">各档数量须连续；最后一档终点留空表示不限。</p>
          <div class="fields"><label>运费对应数量（件）*<input v-model="draft.freightQuantity" aria-label="FOB 运费对应数量" type="text" inputmode="numeric"></label><label>该批国内运费（元）*<input v-model="draft.freightTotal" aria-label="FOB 国内总运费" type="text" inputmode="decimal"></label></div>
          <div class="freight">均摊国内运费 <strong>{{ freight }}</strong></div><p class="hint">沿用现有 FOB 规则，填写100件或更大批量的总运费；包邮请明确填写0。</p>
          <label>备注<textarea v-model="draft.notes" rows="2"></textarea></label>
          </fieldset>
        </form>
        <section v-else class="file-entry">
          <div class="file-box"><h3>导入 FOB 采购表</h3><p>支持 .xlsx / .xls，最大10MB，每批最多100条。</p><label>选择 Excel 文件<input type="file" accept=".xlsx,.xls" :disabled="busy" aria-label="选择FOB Excel文件" @change="selectFile"></label><p v-if="fileName">当前文件：{{ fileName }}</p></div>
          <p class="hint">支持带表头的国际站采购表或精简表：SKU、起订量、单价（或采购价格原文）、运费原文。单元格可填写多档价格。图片不导入。</p>
          <template v-if="workbook"><p v-for="notice in workbook.notices" :key="notice" class="hint">{{ notice }}</p>
            <details><summary>编辑已读取的 {{ workbook.rows.length }} 条资料</summary><div class="file-grid"><table><thead><tr><th>来源行</th><th v-for="[label,field] in FOB_COLUMNS" :key="field">{{ label }}</th></tr></thead><tbody><tr v-for="(row,i) in workbook.rows" :key="i"><th>{{ workbook.locations[i]?.sheet }} · {{ workbook.locations[i]?.row }}</th><td v-for="[label,field] in FOB_COLUMNS" :key="field"><textarea v-model="row[field]" :disabled="busy" :aria-label="`导入第${i+1}条 ${label}`"></textarea></td></tr></tbody></table></div></details>
          </template>
        </section>
      </template>
      <FobPurchasePreview v-else :preview="pending.preview" :locations="method==='excel' ? workbook?.locations : undefined" />
      <p class="hint">同 SKU 的 FOB 资料将更新；空白字段保留原值。普通采购资料和已保存的历史报价保持原样。</p>
      <p v-if="message" class="message" role="status">{{ message }}</p>
    </div>
    <footer><span>保存类型：FOB数据</span><button v-if="pending" :disabled="busy" @click="pending=null;message=''">返回编辑</button><button v-if="pending" class="primary" :disabled="busy || !pending.preview.canSave" @click="save">{{ busy ? '正在保存…' : '确认保存 FOB 资料' }}</button><button v-else class="primary" :disabled="busy" @click="preview">{{ busy ? '正在校验…' : '预览并保存 FOB 资料' }}</button></footer>
  </div>
</template>
<style scoped>
.fob-entry{display:flex;flex-direction:column;min-height:0;font-size:12px;color:#50606d}.methods{display:flex;gap:22px;border-bottom:1px solid #e2e7eb;margin-bottom:16px}.methods button{padding:9px 0;border:0;border-bottom:2px solid transparent;border-radius:0;color:#71808d}.methods button[aria-pressed=true]{color:#b26a07;border-bottom-color:#ee941c;font-weight:700}.entry-scroll{overflow:auto;min-height:0;max-height:55vh;padding:0 8px 4px 0}fieldset{padding:0;border:0;margin:0}.fields{display:grid;grid-template-columns:1fr 1fr;gap:12px}.wide{grid-column:1/-1}label{display:block}input,textarea{display:block;box-sizing:border-box;width:100%;min-width:0;padding:9px 10px;margin-top:5px;border:1px solid #dce3e8;border-radius:6px;background:#fff;color:#24323e;font:inherit}textarea{resize:vertical}button{padding:9px 13px;border:1px solid #dce3e8;border-radius:6px;background:#fff;color:#344958;font:inherit;cursor:pointer}button:disabled{opacity:.45;cursor:not-allowed}details{margin:16px 0}summary{padding:10px 0;cursor:pointer;border-block:1px solid #edf0f2}summary span{margin-left:6px;color:#7e8b96;font-size:11px}details .fields{margin-top:12px}h3{display:flex;justify-content:space-between;align-items:center;gap:10px;font-size:14px;margin:16px 0 12px;color:#23313d}h3 small{font-size:11px;font-weight:400;color:#7a8792}.tier-heading{display:flex;justify-content:space-between;align-items:center;margin:12px 0 8px}.text-button{padding:4px 0;color:#aa6607;border:0}.tier-labels,.tier-row{display:grid;grid-template-columns:1fr 1fr 1.1fr 32px;gap:8px;align-items:center}.tier-labels{font-size:11px;color:#73818c}.tier-row{margin-top:6px}.tier-row input{margin:0}.tier-row button{border:0;padding:5px}.hint{color:#7b8790;font-size:11px;line-height:1.7;white-space:pre-wrap;overflow-wrap:anywhere}.freight{display:flex;justify-content:space-between;gap:10px;background:#f6f8fa;border-radius:6px;padding:9px 11px;margin-top:9px}.freight strong{color:#2c6e59}.message{background:#fff5e5;color:#8b5b13;padding:11px;border-radius:6px;white-space:pre-wrap}footer{display:flex;align-items:center;gap:8px;padding-top:16px;margin-top:12px;border-top:1px solid #e1e6eb}footer>span{margin-right:auto;color:#79858f;font-size:11px}.primary{background:#ee920e;border-color:#ee920e;color:#fff;font-weight:700}.file-box{border:1px dashed #d3dce2;background:#fafbfc;border-radius:8px;padding:25px 18px;text-align:center}.file-box h3{justify-content:center}.file-box input{background:#fff}.file-box p{overflow-wrap:anywhere}.file-grid{overflow:auto}table{border-collapse:collapse;font-size:11px}th,td{border:1px solid #dce5ec;padding:5px}td textarea{width:140px;min-height:80px;margin:0}@media(max-width:600px){.fields{grid-template-columns:1fr}.tier-labels,.tier-row{gap:4px;grid-template-columns:1fr 1fr 1fr 26px}.tier-row input{padding:8px 4px}footer{flex-wrap:wrap}footer>span{width:100%}h3{flex-wrap:wrap}}
.product-image{display:block;max-width:120px;max-height:100px;object-fit:contain;margin-top:8px}
</style>
