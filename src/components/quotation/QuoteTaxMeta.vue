<script setup lang="ts">
import { computed } from 'vue'
import type { QuotationMatrixRow } from './types'

const props = withDefaults(defineProps<{
  row: QuotationMatrixRow
  mode?: 'badge' | 'price'
  tier?: '1' | '2' | '3' | 'custom'
}>(), { mode: 'badge', tier: '1' })

const taxAmount = computed(() => {
  const key = props.tier === 'custom' ? 'taxCustomUsd' : `tax${props.tier}Usd` as 'tax1Usd' | 'tax2Usd' | 'tax3Usd'
  const value = props.row[key]
  return value == null || !Number.isFinite(value) ? null : value
})
const finalPrice = computed(() => {
  const key = props.tier === 'custom' ? 'quoteCustom' : `quote${props.tier}` as 'quote1' | 'quote2' | 'quote3'
  const value = props.row[key]
  return value == null || !Number.isFinite(value) ? null : value
})
const taxable = computed(() => !props.row.taxIncluded && props.row.taxConfigured)
const missing = computed(() => !props.row.taxIncluded && !props.row.taxConfigured)
const badgeText = computed(() => {
  if (props.row.taxIncluded) return '免税'
  if (missing.value) return '税费待设置'
  if (props.row.taxFeeMode === 'fixed-order') return `A类 $${Number(props.row.countryFixedTaxUsd || 0).toFixed(2)}/单`
  if (props.row.taxFeeMode === 'per-item') return `B类 $${Number(props.row.taxPerItemFeeUsd || 0).toFixed(2)}/件`
  return '含税'
})
const untaxedPrice = computed(() => finalPrice.value == null || taxAmount.value == null ? null : Math.max(0, finalPrice.value - taxAmount.value))
</script>

<template>
  <mark v-if="mode==='badge'" class="tax-channel-badge" :class="{ exempt:row.taxIncluded, missing }">{{ badgeText }}</mark>
  <span v-else-if="taxable && taxAmount != null && taxAmount > 0" class="tax-price-meta" tabindex="0">
    <em>已含税 ${{ taxAmount.toFixed(2) }}</em>
    <span class="tax-tooltip">
      <i>未税报价<b>{{ untaxedPrice == null ? '—' : `$${untaxedPrice.toFixed(2)}` }}</b></i>
      <strong>＋</strong><i>税费<b>${{ taxAmount.toFixed(2) }}</b></i>
      <strong>＝</strong><i>含税报价<b>{{ finalPrice == null ? '—' : `$${finalPrice.toFixed(2)}` }}</b></i>
    </span>
  </span>
  <span v-else-if="mode==='price' && row.taxIncluded" class="tax-price-meta exempt"><em>免税</em></span>
  <span v-else-if="mode==='price' && missing" class="tax-price-meta missing" tabindex="0"><em>暂未计税</em><span class="tax-tooltip warning">请先在财务设置中维护该国家客户税费或物流商属性</span></span>
</template>

<style scoped>
.tax-channel-badge{display:inline-flex;align-items:center;box-sizing:border-box;padding:3px 7px;border:1px solid #f2a257;border-radius:4px;background:#fffaf4;color:#e56a0a;font-size:10px;font-weight:700;line-height:1.2;white-space:nowrap}.tax-channel-badge.exempt{border-color:#efb274;background:#fffaf4;color:#e56a0a}.tax-channel-badge.missing{border-color:#df6b5d;background:#fff4f1;color:#b52d21}
.tax-price-meta{position:relative;justify-self:start;display:inline-flex;outline:0}.tax-price-meta>em{color:#e56a0a;font-size:10px;font-style:normal;font-weight:700;line-height:1.25;white-space:nowrap}.tax-price-meta.exempt>em{color:#e56a0a}.tax-price-meta.missing>em{color:#b52d21}.tax-tooltip{position:absolute;z-index:30;left:50%;bottom:calc(100% + 9px);display:none;align-items:center;gap:10px;width:max-content;padding:10px 13px;border:1px solid #e2e7ea;border-radius:8px;background:#fff;color:#53616b;box-shadow:0 8px 24px rgba(20,34,45,.18);font-size:10px}.tax-tooltip:after{position:absolute;left:50%;bottom:-5px;width:9px;height:9px;background:#fff;border-right:1px solid #e2e7ea;border-bottom:1px solid #e2e7ea;content:'';transform:translateX(-50%) rotate(45deg)}.tax-tooltip i{display:grid;gap:3px;font-style:normal}.tax-tooltip b{color:#17232d;font-size:12px}.tax-tooltip strong{color:#a4adb4}.tax-tooltip.warning{color:#a92a1d;font-weight:800}.tax-price-meta:hover .tax-tooltip,.tax-price-meta:focus .tax-tooltip,.tax-price-meta:focus-within .tax-tooltip{display:flex}
</style>
