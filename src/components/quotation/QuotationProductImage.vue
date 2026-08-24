<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { nextQuotationImageCandidate, quotationImageCandidates } from '@/data/quotationImages'

const props = withDefaults(defineProps<{
  snapshotImage?: string
  physicalImage?: string
  productImage?: string
  alt: string
  fallbackText?: string
  showLabel?: boolean
}>(), {
  snapshotImage: '', physicalImage: '', productImage: '', fallbackText: '暂无图片', showLabel: false,
})

const failedUrls = ref<Set<string>>(new Set())
const candidates = computed(() => quotationImageCandidates(props))
const current = computed(() => nextQuotationImageCandidate(candidates.value, failedUrls.value))
const candidateKey = computed(() => candidates.value.map(item => `${item.kind}:${item.url}`).join('|'))

watch(candidateKey, () => { failedUrls.value = new Set() })

function handleError() {
  if (!current.value) return
  failedUrls.value = new Set([...failedUrls.value, current.value.url])
}
</script>

<template>
  <div class="quotation-product-image" :class="{ empty:!current }">
    <img v-if="current" :src="current.url" :alt="alt" @error="handleError">
    <small v-if="current && showLabel">{{ current.label }}</small>
    <span v-if="!current">{{ fallbackText }}</span>
  </div>
</template>

<style scoped>
.quotation-product-image{position:relative;display:grid;place-items:center;overflow:hidden;background:#edf2f4}.quotation-product-image img{width:100%;height:100%;object-fit:cover}.quotation-product-image small{position:absolute;right:5px;bottom:5px;padding:3px 6px;border-radius:8px;background:rgba(17,24,39,.72);color:#fff;font-size:8px}.quotation-product-image span{max-width:86%;color:#85919a;font-size:10px;line-height:1.45;text-align:center}
</style>
