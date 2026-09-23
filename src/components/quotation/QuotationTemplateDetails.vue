<script setup lang="ts">
import { computed } from 'vue'
import type { QuotationTemplateSelectionItem } from '@/data/quotationTemplates'

const props = defineProps<{ items: QuotationTemplateSelectionItem[] }>()

const countryGroups = computed(() => {
  const groups = new Map<string, QuotationTemplateSelectionItem[]>()
  for (const item of props.items) {
    const group = groups.get(item.country)
    if (group) group.push(item)
    else groups.set(item.country, [item])
  }
  return [...groups].map(([country, items]) => ({ country, items }))
})
</script>

<template>
  <section class="details-panel" aria-label="模板国家与渠道明细">
    <header class="details-heading">
      <b>国家与渠道明细</b>
      <span>{{ countryGroups.length }} 个国家 · {{ items.length }} 条渠道</span>
    </header>
    <div v-for="group in countryGroups" :key="group.country" class="country-group">
      <h3>{{ group.country }}<span>{{ group.items.length }} 条渠道</span></h3>
      <ul>
        <li v-for="(item, index) in group.items" :key="index">
          <div class="channel-name">
            <strong>{{ item.transport || item.rule || item.channelCode || '未命名渠道' }}</strong>
            <span v-if="item.channelCode" class="channel-code">{{ item.channelCode }}</span>
          </div>
          <div class="channel-meta">
            <span>物流商：{{ item.carrier || '未记录' }}</span>
            <span v-if="item.quoteRegion">报价区域：{{ item.quoteRegion }}</span>
            <span v-if="item.rule && item.rule !== item.transport">规则：{{ item.rule }}</span>
          </div>
        </li>
      </ul>
    </div>
    <p v-if="!items.length" class="empty-details">该模板暂无国家和渠道明细。</p>
  </section>
</template>

<style scoped>
.details-panel{border-top:1px solid #e2e9ee;padding-top:12px;color:#263945}
.details-heading{display:flex;align-items:center;flex-wrap:wrap;gap:8px;margin-bottom:10px}
.details-heading b{font-size:12px}
.details-heading>span{font-size:10px;color:#73818c}
.country-group{display:grid;grid-template-columns:120px minmax(0,1fr);overflow:hidden;border:1px solid #e1e8ed;border-radius:7px;background:#fafcfd}
.country-group+.country-group{margin-top:8px}
.country-group h3{display:flex;flex-direction:column;align-items:flex-start;gap:5px;margin:0;padding:12px;font-size:12px;overflow-wrap:anywhere}
.country-group h3 span{color:#758590;font-size:10px;font-weight:400}
.country-group ul{min-width:0;margin:0;padding:0 12px;list-style:none;border-left:1px solid #e1e8ed;background:#fff}
.country-group li{padding:10px 0;overflow-wrap:anywhere}
.country-group li+li{border-top:1px solid #edf1f4}
.channel-name{display:flex;align-items:baseline;flex-wrap:wrap;gap:6px 10px}
.channel-name strong{font-size:12px;font-weight:600}
.channel-code{color:#73818c;font-size:10px}
.channel-meta{display:flex;flex-wrap:wrap;gap:4px 16px;margin-top:5px;color:#657681;font-size:10px;line-height:1.6}
.empty-details{margin:8px 0;color:#73818c;font-size:11px}
@media(max-width:680px){.country-group{grid-template-columns:minmax(0,1fr)}.country-group h3{flex-direction:row;align-items:center;flex-wrap:wrap}.country-group ul{border-left:0;border-top:1px solid #e1e8ed}.channel-meta{flex-direction:column}}
</style>
