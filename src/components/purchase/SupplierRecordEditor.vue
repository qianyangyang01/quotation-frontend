<script setup lang="ts">
import type { SupplierRecordDraft } from '@/services/supplierRecords'

defineProps<{ saving: boolean; error: string }>()
const model = defineModel<SupplierRecordDraft>({ required: true })
defineEmits<{ save: []; cancel: [] }>()
</script>

<template>
  <form class="supplier-editor" @submit.prevent="$emit('save')">
    <fieldset><legend>基础联系</legend>
      <label>供应商名称*<input v-model="model.name" maxlength="160" placeholder="请输入供应商名称"></label>
      <label>产业带<input v-model="model.industryBelt" maxlength="160" placeholder="如：广州·十三行"></label>
      <label>对接人身份<input v-model="model.contactRole" maxlength="80" placeholder="如：业务经理"></label>
      <label>人际关系<input v-model="model.relationshipNotes" maxlength="160" placeholder="如：长期合作"></label>
    </fieldset>
    <fieldset><legend>交易条件</legend>
      <label>开票<select v-model="model.invoiceType"><option value="">未填写</option><option>普票</option><option>专票</option><option>不开票</option></select></label>
      <label>票点（%）<input v-model.number="model.taxPointPercent" type="number" min="0" max="100" step="0.01" placeholder="如：3"></label>
      <label>备选供应商询价<input v-model="model.alternativeInquiry" maxlength="500" placeholder="文字、日期或链接"></label>
      <label>成本表<input v-model="model.costSheet" maxlength="500" placeholder="文字、日期或链接"></label>
      <label>本月采购额（元）<input v-model.number="model.monthlyPurchaseAmount" type="number" min="0" step="0.01" placeholder="0.00"></label>
    </fieldset>
    <fieldset><legend>履约能力</legend>
      <label>质量<input v-model="model.qualityGrade" maxlength="40" placeholder="如：A（优）"></label>
      <label>交期<input v-model="model.deliveryTerms" maxlength="80" placeholder="如：3-7天"></label>
      <label>产能/货单<input v-model="model.capacityOrder" maxlength="120" placeholder="如：5000件/天"></label>
      <label>备货策略<input v-model="model.stockingStrategy" maxlength="160" placeholder="如：安全库存备货"></label>
      <label>免费样品<select v-model="model.freeSample"><option :value="null">未填写</option><option :value="true">是</option><option :value="false">否</option></select></label>
      <label>售后（退换）<input v-model="model.afterSales" maxlength="160" placeholder="如：支持7天内退换"></label>
    </fieldset>
    <fieldset><legend>合作评价</legend>
      <label>爆品推荐<select v-model="model.hotProductRecommendation"><option :value="null">未填写</option><option :value="true">是</option><option :value="false">否</option></select></label>
      <label>总分（配合度）<input v-model.number="model.cooperationScore" type="number" min="0" max="100" step="1" placeholder="0-100"></label>
      <label>评级<select v-model="model.rating"><option>待评价</option><option>A级</option><option>B级</option><option>C级</option></select></label>
      <label class="wide">备注<textarea v-model="model.notes" maxlength="2000" placeholder="记录合作情况"></textarea></label>
      <label class="wide">建议<textarea v-model="model.suggestion" maxlength="2000" placeholder="记录后续建议"></textarea></label>
    </fieldset>
    <p v-if="error" class="supplier-form-error">{{ error }}</p>
    <footer><button type="button" :disabled="saving" @click="$emit('cancel')">收起 / 取消修改</button><button class="save" type="submit" :disabled="saving">{{ saving ? '保存中…' : '保存本项' }}</button></footer>
  </form>
</template>

<style scoped>
.supplier-editor{padding:14px;border:1px solid #e1e6e9;border-radius:8px;background:#fff}.supplier-editor fieldset{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px;margin:0 0 12px;padding:12px;border:1px solid #e5eaed;border-radius:7px}.supplier-editor legend{padding:0 7px;color:#23313b;font-size:12px;font-weight:900}.supplier-editor label{display:grid;gap:5px;color:#596771;font-size:10px}.supplier-editor input,.supplier-editor select,.supplier-editor textarea{box-sizing:border-box;width:100%;min-height:35px;padding:7px 9px;border:1px solid #dce3e8;border-radius:6px;background:#fff;color:#26343d;font:inherit}.supplier-editor textarea{min-height:64px;resize:vertical}.supplier-editor .wide{grid-column:1/-1}.supplier-editor>footer{display:flex;justify-content:flex-end;gap:8px}.supplier-editor>footer button{height:36px;padding:0 14px;border:1px solid #dce3e8;border-radius:6px;background:#fff;font-weight:800}.supplier-editor>footer .save{border-color:#ff8a00;background:#ff8a00;color:#17212b}.supplier-form-error{padding:9px 11px;border-radius:6px;background:#fff0ee;color:#b33830;font-size:11px}@media(max-width:900px){.supplier-editor fieldset{grid-template-columns:1fr}.supplier-editor .wide{grid-column:auto}}
</style>
