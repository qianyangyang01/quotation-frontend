<script setup lang="ts">
import { computed } from 'vue'
import type { SupplierRecordDraft } from '@/services/supplierRecords'
import {
  calculateSupplierScore,
  DELIVERY_OPTIONS,
  deliveryValueForRequest,
  INVOICE_TYPE_OPTIONS,
  invoiceNeedsTaxPoint,
  PRICE_LEVEL_OPTIONS,
  QUALITY_OPTIONS,
  SCORE_ITEM_LABELS,
  STOCKING_OPTIONS,
  type ScoreBreakdown,
} from './supplierRecordOptions'

defineProps<{ saving: boolean; error: string; licenseUrl?: string; pendingLicenseFile?: File | null; removeLicense?: boolean }>()
const model = defineModel<SupplierRecordDraft>({ required: true })
const emit = defineEmits<{ save: []; cancel: []; licenseSelect: [file: File | null]; removeLicense: [] }>()

const taxPointRequired = computed(() => invoiceNeedsTaxPoint(model.value.invoiceType))
const legacyStockingStrategy = computed(() => {
  const value = model.value.stockingStrategy.trim()
  return value && !(STOCKING_OPTIONS as readonly string[]).includes(value) ? value : ''
})
const scorePreview = computed(() => calculateSupplierScore({
  ...model.value,
  deliveryTerms: deliveryValueForRequest(model.value.deliveryTerms, model.value.legacyDeliveryTerms),
}))
const scoreRows = computed(() => (Object.keys(scorePreview.value.breakdown) as Array<keyof ScoreBreakdown>).map((key) => ({
  key,
  label: SCORE_ITEM_LABELS[key],
  score: scorePreview.value.breakdown[key],
})))

function selectLicense(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0] || null
  if (file && file.size > 20 * 1024 * 1024) { input.value = ''; return }
  emit('licenseSelect', file)
}

function chooseInvoiceType(value: string) {
  model.value.invoiceType = value
  if (value === '没票') model.value.taxPointPercent = ''
}
</script>

<template>
  <form class="supplier-editor" @submit.prevent="$emit('save')">
    <fieldset><legend>基本信息</legend>
      <label>供应商名称*<input v-model="model.name" maxlength="160" placeholder="请输入供应商名称"></label>
      <label>产业带<input v-model="model.industryBelt" maxlength="160" placeholder="如：广州产业带"></label>
      <label>老板姓名<input v-model="model.bossName" maxlength="80" placeholder="请输入老板姓名"></label>
      <label>联系电话 / 微信<input v-model="model.contactDetails" maxlength="160" placeholder="手机号、微信号或其他联系方式"></label>
    </fieldset>

    <fieldset><legend>财务信息</legend>
      <div class="wide field-block invoice-block">
        <span class="field-title">开票及票点</span>
        <div class="choice-grid invoice-choices">
          <button v-for="option in INVOICE_TYPE_OPTIONS" :key="option" type="button" :class="{ selected: model.invoiceType === option }" :aria-pressed="model.invoiceType === option" @click="chooseInvoiceType(option)">
            <b>{{ option }}</b><small>{{ option === '专票' ? '可填写增值税专票票点' : option === '普票' ? '可填写普通发票票点' : '不开发票，不填写票点' }}</small>
          </button>
        </div>
        <label v-if="taxPointRequired" class="tax-point-field">{{ model.invoiceType }}票点（%）*<input v-model.number="model.taxPointPercent" type="number" min="0" max="100" step="0.01" placeholder="请输入百分比，如：1"><small>直接填写百分比数字；例如 1 表示 1%。</small></label>
        <small v-else-if="model.invoiceType === '没票'" class="field-help">已选择没票，票点已清空。</small>
        <small v-else class="field-help">请先选择专票、普票或没票。</small>
      </div>
      <label>对公账户<input v-model="model.corporateAccount" maxlength="500" placeholder="请输入对公账户信息"></label>
      <label>开户银行<input v-model="model.corporateBank" maxlength="160" placeholder="请输入开户银行"></label>
      <label>预估每月采购额（元）<input v-model.number="model.monthlyPurchaseAmount" type="number" min="0" step="0.01" placeholder="0.00"></label>
      <label>价格水平<select v-model="model.priceLevel"><option value="">请选择</option><option v-for="option in PRICE_LEVEL_OPTIONS" :key="option">{{ option }}</option></select></label>
      <label class="license-field">营业执照
        <span v-if="pendingLicenseFile" class="license-file">待上传：{{ pendingLicenseFile.name }}</span>
        <span v-else-if="removeLicense" class="license-file removed">保存后移除当前图片</span>
        <a v-else-if="licenseUrl" :href="licenseUrl" target="_blank" rel="noopener"><img :src="licenseUrl" alt="营业执照预览"></a>
        <span v-else class="license-file">暂无图片</span>
        <span class="license-actions"><label class="upload-button">选择图片<input type="file" accept="image/png,image/jpeg,image/gif,image/webp" @change="selectLicense"></label><button v-if="licenseUrl || pendingLicenseFile" type="button" @click="emit('removeLicense')">移除</button></span>
        <small>支持 PNG、JPEG、GIF、WebP，单张不超过 20MB；点击“保存本项”后上传。</small>
      </label>
    </fieldset>

    <fieldset><legend>备选供应商询价</legend>
      <label class="wide">询价记录<input v-model="model.alternativeInquiry" maxlength="500" placeholder="可填写文字、日期或链接"></label>
    </fieldset>

    <fieldset><legend>履约能力</legend>
      <label>质量<select v-model="model.qualityGrade"><option value="">请选择</option><option v-for="option in QUALITY_OPTIONS" :key="option.value" :value="option.value">{{ option.label }}</option></select></label>
      <label>交期
        <select v-model="model.deliveryTerms">
          <option value="">请选择</option>
          <option v-for="option in DELIVERY_OPTIONS" :key="option.value" :value="option.value">{{ option.label }}（{{ option.score }}分）</option>
        </select>
        <small v-if="model.legacyDeliveryTerms" class="history-note">历史交期记录：“{{ model.legacyDeliveryTerms }}”（只读保留）。留空不会丢失，选择新交期并保存后才会替换。</small>
      </label>
      <label>产能 / 我司可分配量<input v-model="model.capacityOrder" maxlength="120" placeholder="如：5000件/天，我司可分配2000件"></label>
      <label>备货<select v-model="model.stockingStrategy"><option value="">请选择</option><option v-if="legacyStockingStrategy" :value="legacyStockingStrategy">历史记录（保留）</option><option v-for="option in STOCKING_OPTIONS" :key="option">{{ option }}</option></select><small v-if="legacyStockingStrategy" class="history-note">历史备货记录：{{ legacyStockingStrategy }}</small></label>
      <label>免费样品<select v-model="model.freeSample"><option :value="null">请选择</option><option :value="true">有</option><option :value="false">没有</option></select></label>
      <label>售后<select v-model="model.afterSalesAvailable"><option :value="null">请选择</option><option :value="true">有</option><option :value="false">没有</option></select><small v-if="model.afterSales.trim()" class="history-note">历史售后记录：{{ model.afterSales }}</small></label>
    </fieldset>

    <fieldset class="cooperation-fieldset"><legend>合作评价</legend>
      <label>爆品推荐<select v-model="model.hotProductRecommendation"><option :value="null">请选择</option><option :value="true">每月 1–5 个</option><option :value="false">没有</option></select></label>
      <label>评级<select v-model="model.rating"><option>待评价</option><option>A级</option><option>B级</option><option>C级</option></select></label>
      <section class="wide score-card" :class="{ complete: scorePreview.complete }">
        <header><div><small>系统自动计算</small><b>综合评分</b></div><strong>{{ scorePreview.total ?? '待评分' }}<small v-if="scorePreview.complete"> / 100</small></strong></header>
        <div class="score-breakdown"><span v-for="item in scoreRows" :key="item.key"><b>{{ item.label }}</b><em>{{ item.score == null ? '待补充' : `${item.score} 分` }}</em></span></div>
        <p v-if="scorePreview.missingItems.length">还需补充：{{ scorePreview.missingItems.join('、') }}。缺失项不会按 0 分计算。</p>
        <p v-else>评分已完整；保存后以后端计算结果为准。</p>
        <p v-if="model.cooperationScore !== '' && model.cooperationScore != null" class="history-score">历史人工总分：{{ model.cooperationScore }} 分（只读保留，不参与当前评分）</p>
      </section>
      <label class="wide">备注<textarea v-model="model.notes" maxlength="2000" placeholder="记录合作情况"></textarea></label>
      <label class="wide">建议<textarea v-model="model.suggestion" maxlength="2000" placeholder="记录后续建议"></textarea></label>
    </fieldset>
    <p v-if="error" class="supplier-form-error">{{ error }}</p>
    <footer><button type="button" :disabled="saving" @click="$emit('cancel')">收起 / 取消修改</button><button class="save" type="submit" :disabled="saving">{{ saving ? '保存中…' : '保存本项' }}</button></footer>
  </form>
</template>

<style scoped>
.supplier-editor{padding:14px;border:1px solid #e1e6e9;border-radius:8px;background:#fff}.supplier-editor fieldset{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px;margin:0 0 14px;padding:14px;border:1px solid #e5eaed;border-radius:9px}.supplier-editor legend{padding:0 8px;color:#23313b;font-size:12px;font-weight:900}.supplier-editor label,.field-block{display:grid;gap:6px;color:#596771;font-size:10px}.supplier-editor input,.supplier-editor select,.supplier-editor textarea{box-sizing:border-box;width:100%;min-height:37px;padding:8px 10px;border:1px solid #dce3e8;border-radius:7px;background:#fff;color:#26343d;font:inherit}.supplier-editor textarea{min-height:68px;resize:vertical}.supplier-editor .wide{grid-column:1/-1}.field-title{color:#46545e;font-weight:800}.invoice-block{padding:12px;border:1px solid #e3e8eb;border-radius:8px;background:#fbfcfd}.choice-grid{display:grid;gap:8px}.invoice-choices{grid-template-columns:repeat(3,minmax(0,1fr))}.choice-grid button{display:grid;gap:3px;min-height:58px;padding:10px 12px;border:1px solid #dce3e8;border-radius:8px;background:#fff;color:#485660;text-align:left;cursor:pointer}.choice-grid button b{font-size:13px}.choice-grid button small{color:#85919a;font-size:9px}.choice-grid button.selected{border-color:#ff930f;background:#fff5e8;box-shadow:0 0 0 1px #ff930f;color:#c86500}.tax-point-field{margin-top:2px}.tax-point-field small,.field-help{color:#7d8991;font-size:9px}.license-field{grid-row:span 2}.license-field img{display:block;width:150px;height:92px;object-fit:cover;border:1px solid #e1e6e9;border-radius:6px}.license-file{display:flex;align-items:center;min-height:42px;padding:8px;border:1px dashed #d8e0e5;border-radius:6px;color:#77838c}.license-file.removed{color:#b45309}.license-actions{display:flex;gap:8px}.license-actions button,.upload-button{display:inline-flex!important;align-items:center;justify-content:center;width:auto!important;min-height:32px!important;padding:0 11px!important;border:1px solid #ff9a24!important;border-radius:6px!important;background:#fff!important;color:#a65d00!important;font-weight:800;cursor:pointer}.upload-button input{display:none}.license-field small{color:#89949c;line-height:1.5}.history-note{padding:6px 8px;border-radius:5px;background:#f6f7f8;color:#7a6c59;font-size:9px;line-height:1.45}.score-card{padding:14px;border:1px solid #f1d1a7;border-radius:9px;background:#fffaf3}.score-card.complete{border-color:#a8dbc4;background:#f5fbf8}.score-card header{display:flex;align-items:center;justify-content:space-between;gap:16px}.score-card header div{display:grid;gap:3px}.score-card header small{color:#85919a;font-size:9px}.score-card header b{font-size:15px}.score-card header strong{color:#e87900;font-size:23px}.score-card header strong small{font-size:11px}.score-card.complete header strong{color:#14805e}.score-breakdown{display:grid;grid-template-columns:repeat(7,minmax(80px,1fr));gap:7px;margin-top:12px}.score-breakdown span{display:grid;gap:3px;padding:8px;border:1px solid rgba(200,156,101,.2);border-radius:6px;background:rgba(255,255,255,.72)}.score-breakdown b{color:#66737c;font-size:9px}.score-breakdown em{color:#27353e;font-size:11px;font-style:normal;font-weight:800}.score-card p{margin:9px 0 0;color:#776b5b;font-size:9px;line-height:1.5}.score-card .history-score{padding-top:8px;border-top:1px dashed #dfc8a9;color:#9a641f}.supplier-editor>footer{display:flex;justify-content:flex-end;gap:8px}.supplier-editor>footer button{height:36px;padding:0 14px;border:1px solid #dce3e8;border-radius:6px;background:#fff;font-weight:800}.supplier-editor>footer .save{border-color:#ff8a00;background:#ff8a00;color:#17212b}.supplier-form-error{padding:9px 11px;border-radius:6px;background:#fff0ee;color:#b33830;font-size:11px}@media(max-width:1100px){.score-breakdown{grid-template-columns:repeat(4,minmax(80px,1fr))}}@media(max-width:900px){.supplier-editor fieldset{grid-template-columns:1fr}.supplier-editor .wide{grid-column:auto}.invoice-choices,.score-breakdown{grid-template-columns:1fr}.license-field{grid-row:auto}}
</style>
