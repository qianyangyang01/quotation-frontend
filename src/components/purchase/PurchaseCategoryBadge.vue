<script setup lang="ts">
import { computed } from 'vue'
import { purchaseCategoryIcon } from '@/data/purchaseCategoryIcons'

const props = withDefaults(defineProps<{ category?: string; expanded?: boolean }>(), { category: '', expanded: false })
const categoryHues: Record<string, number> = {
  文胸: 338, 袜子: 258, 内裤: 316, 服装: 218, 化妆品: 292, 保健品: 32,
  日用品: 38, 庭院工具: 100, 家用电器: 204, 健身器材: 8, 厨房用具: 24,
  家纺: 278, 配饰: 46, 鞋: 232, 文具: 192, 灯具: 56, 数码: 244,
  辅料: 176, 玩具: 350, 书籍: 124, 宠物用品: 162, 医疗: 184,
  汽车用品: 266, 清洁用品: 136, 箱包: 16, 护肤品: 304, 其他: 78,
}
const label = computed(() => props.category.trim() || '未分类')
const paths = computed(() => purchaseCategoryIcon(props.category))
const colors = computed(() => {
  if (!props.category.trim()) return { backgroundColor: '#eef2f6', color: '#475569', borderColor: '#d8e0e8' }
  const hue = categoryHues[label.value] ?? Array.from(label.value).reduce((hash, letter) => (hash * 31 + letter.codePointAt(0)!) % 360, 0)
  return { backgroundColor: `hsl(${hue} 68% 94%)`, color: `hsl(${hue} 60% 27%)`, borderColor: `hsl(${hue} 52% 84%)` }
})
</script>

<template>
  <div class="purchase-category-badge" :class="{ expanded }" :style="colors" :title="label" role="img" :aria-label="`${label}类别标识`">
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.65" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path v-for="(path, index) in paths" :key="index" :d="path" /></svg>
    <small v-if="expanded" aria-hidden="true">{{ label }}</small>
  </div>
</template>

<style scoped>
.purchase-category-badge{box-sizing:border-box;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:6px;width:48px;height:48px;flex:0 0 48px;border:1px solid;border-radius:11px;overflow:hidden}
.purchase-category-badge svg{width:27px;height:27px;flex-shrink:0}
.purchase-category-badge.expanded{width:100%;height:100%;border-radius:10px}
.purchase-category-badge.expanded svg{width:38px;height:38px}
.purchase-category-badge small{max-width:90%;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:10px;line-height:1.3}
</style>
