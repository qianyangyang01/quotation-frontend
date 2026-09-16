<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import type { FinanceCountryChannelRule } from '@/data/financeChannelPolicies'
const props = defineProps<{ rules: FinanceCountryChannelRule[]; codes: Map<string, { code: string }> }>()
const emit = defineEmits<{ pageChange: [] }>()
const query = ref(''), page = ref(0), size = ref(10)
const matches = computed(() => props.rules.map((rule,index) => ({ rule,index })).filter(({rule}) => `${rule.country} ${props.codes.get(rule.country)?.code || ''} ${rule.continent}`.toLowerCase().includes(query.value.trim().toLowerCase())))
const pages = computed(() => Math.max(1, Math.ceil(matches.value.length / size.value)))
const visible = computed(() => matches.value.slice(page.value * size.value, (page.value + 1) * size.value))
watch([query,size], () => { page.value = 0; emit('pageChange') })
watch(pages, total => { page.value = Math.min(page.value, total - 1) })
function go(value: number) { page.value = Math.max(0, Math.min(pages.value - 1, value)); emit('pageChange') }
async function reveal(index: number) { query.value = ''; await nextTick(); go(Math.floor(index / size.value)) }
defineExpose({ reveal })
</script>
<template>
  <div class="finance-country-rule-list">
    <div class="country-list-controls"><input v-model="query" aria-label="筛选已配置国家" placeholder="搜索已配置国家 / 代码 / 大洲"><span>共 {{ rules.length }} 个国家 · 匹配 {{ matches.length }} 个</span></div>
    <slot v-for="entry in visible" :key="entry.index" :rule="entry.rule" :index="entry.index" />
    <p v-if="!matches.length" class="country-list-empty">没有匹配的国家</p>
    <nav class="country-list-pagination" aria-label="国家配置分页">
      <label>每页 <select v-model.number="size" aria-label="每页国家数"><option v-for="n in [10,30,50]" :key="n" :value="n">{{ n }}</option></select> 个国家</label>
      <span>第 {{ page + 1 }} / {{ pages }} 页</span><button type="button" :disabled="page===0" @click="go(page-1)">上一页</button><button type="button" :disabled="page+1>=pages" @click="go(page+1)">下一页</button>
    </nav>
  </div>
</template>
<style scoped>
.country-list-controls{display:flex;align-items:center;gap:12px;margin:12px 0;flex-wrap:wrap}.country-list-controls input{flex:1;min-width:200px;height:38px;padding:8px 12px;border:1px solid #d5dfe7;border-radius:7px}.country-list-controls span,.country-list-empty{color:#73818e;font-size:12px}.country-list-pagination{display:flex;align-items:center;justify-content:flex-end;gap:10px;padding:14px 0;font-size:12px;flex-wrap:wrap}.country-list-pagination label{display:flex;flex-direction:row;align-items:center;gap:6px;margin-right:auto}.country-list-pagination select,.country-list-pagination button{border:1px solid #d5dfe7;border-radius:6px;background:white;padding:7px 10px;color:#465c70}.country-list-pagination button:disabled{opacity:.4}.finance-country-rule-list :slotted(.country-rule-card){margin-bottom:10px}
</style>
