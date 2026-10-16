<script setup lang="ts">
import type { FobPreview } from '@/services/fobPurchase'
defineProps<{ preview: FobPreview; locations?: { sheet: string; row: number }[] }>()
</script>
<template>
  <section class="fob-preview" aria-label="FOB识别和变更预览">
    <h3>新增 {{ preview.rows.filter(r => r.action === 'create').length }} 条 · 更新 {{ preview.rows.filter(r => r.action === 'update').length }} 条 · 无变化 {{ preview.rows.filter(r => r.action === 'unchanged').length }} 条</h3>
    <p v-if="preview.skipped.length" class="warning">同批内容完全相同的重复行已跳过：{{ preview.skipped.map(r => `第${r.sourceRow}行 ${r.sku}`).join('、') }}</p>
    <article v-for="row in preview.rows" :key="row.sku" :class="{ invalid: row.issues.length }">
      <h4>第{{ row.sourceRow }}条 · {{ row.sku }} · {{ row.action === 'create' ? '新增' : row.action === 'update' ? '更新' : '无变化' }} <span class="source">FOB数据</span></h4>
      <p v-if="locations?.[row.sourceRow - 1]" class="location">{{ locations[row.sourceRow - 1]!.sheet }} · Excel 第{{ locations[row.sourceRow - 1]!.row }}行</p>
      <p v-for="issue in row.issues" :key="issue" class="error">{{ issue }}</p>
      <p v-for="notice in row.notices" :key="notice" class="warning">{{ notice }}</p>
      <div class="preview-images"><img v-if="row.effective.productImage" :src="row.effective.productImage" alt="保存后的产品图片"><img v-if="row.effective.physicalImage" :src="row.effective.physicalImage" alt="保存后的实物图"></div>
      <template v-if="row.effective.parsed">
        <p>起订量 {{ row.effective.parsed.minOrderQty }} · 下单倍数 {{ row.effective.parsed.orderMultiple }} · 运费：{{ row.effective.parsed.freight.basis }}＝¥{{ row.effective.parsed.freight.unitFreightCny }}/单位</p>
        <table><thead><tr><th>采购阶梯</th><th>数量区间</th><th>未税采购价</th><th>均摊运费</th></tr></thead><tbody><tr v-for="(tier,i) in row.effective.parsed.priceTiers" :key="i"><td>阶梯{{ i + 1 }}</td><td>{{ tier.minQty }}{{ tier.maxQty == null ? '起' : `—${tier.maxQty}` }}{{ tier.unit }}</td><td>¥{{ tier.unitPriceCny }}</td><td>¥{{ row.effective.parsed.freight.unitFreightCny }}</td></tr></tbody></table>
      </template>
      <details v-if="row.changes.length" :open="row.action === 'update'"><summary>查看 {{ row.changes.length }} 项修改</summary><table><thead><tr><th>字段</th><th>原值</th><th>保存后</th></tr></thead><tbody><tr v-for="change in row.changes" :key="change.field"><th>{{ change.label }}</th><td>{{ change.before || '（空）' }}</td><td>{{ change.after || '（空）' }}</td></tr></tbody></table></details>
      <p v-else>原文无变化，相同内容不重复生成修改记录。</p>
    </article>
  </section>
</template>
<style scoped>
.fob-preview{font-size:12px;color:#344958}.fob-preview h3{font-size:15px}.fob-preview h4{margin:0;font-size:13px}.fob-preview article{margin:12px 0;padding:12px;border:1px solid #dce5ec;border-radius:8px}.fob-preview .invalid{border-color:#d76452;background:#fff9f7}.fob-preview p{white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.6}.error{color:#b92c20}.warning{color:#95601c}.location{color:#72838f}.source{margin-left:8px;padding:3px 7px;background:#edf7f1;border-radius:4px;color:#287b59;font-size:11px}.fob-preview table{width:100%;border-collapse:collapse;table-layout:fixed;font-size:12px}.fob-preview th,.fob-preview td{padding:7px;border:1px solid #dce5ec;text-align:left;vertical-align:top;white-space:pre-wrap;overflow-wrap:anywhere}.fob-preview th{background:#f5f7f9}.fob-preview summary{cursor:pointer;margin:9px 0;color:#8a6227}
.preview-images{display:flex;gap:12px;flex-wrap:wrap}.preview-images img{max-width:120px;max-height:100px;object-fit:contain}
</style>
