<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { clampLogisticsPage, logisticsPageNumbers, logisticsPageRange } from './logisticsPagination'

const props = withDefaults(defineProps<{ page: number; size: number; total: number; totalPages: number; loading?: boolean; ariaLabel?: string; sizeOptions?: number[] }>(), {
  ariaLabel: '物流价格分页',
  sizeOptions: () => [20, 50, 100],
})
const emit = defineEmits<{ pageChange: [page: number]; sizeChange: [size: number] }>()
const jumpPage = ref(props.page + 1)
const lastPage = computed(() => Math.max(1, props.totalPages))
const pages = computed(() => logisticsPageNumbers(props.page, props.totalPages))
const range = computed(() => logisticsPageRange(props.page, props.size, props.total))
watch(() => props.page, value => { jumpPage.value = value + 1 })
watch(() => props.totalPages, () => { jumpPage.value = Math.min(lastPage.value, props.page + 1) })

function go(page: number) { emit('pageChange', clampLogisticsPage(page, props.totalPages)) }
function jump() { go((Number(jumpPage.value) || 1) - 1) }
function changeSize(event: Event) { emit('sizeChange', Number((event.target as HTMLSelectElement).value)) }
</script>

<template>
  <nav class="logistics-pager" :aria-label="ariaLabel">
    <span class="summary">第 {{ range.from }}–{{ range.to }} 条 / 共 {{ total }} 条</span>
    <label>每页
      <select :value="size" :disabled="loading" aria-label="物流价格每页条数" @change="changeSize">
        <option v-for="option in sizeOptions" :key="option" :value="option">{{ option }}</option>
      </select>
      条
    </label>
    <div class="buttons">
      <button :disabled="loading || page === 0" aria-label="首页" @click="go(0)">首页</button>
      <button :disabled="loading || page === 0" aria-label="上一页" @click="go(page - 1)">上一页</button>
      <button v-for="number in pages" :key="number" :class="{ current: number === page + 1 }" :aria-current="number === page + 1 ? 'page' : undefined" :disabled="loading" @click="go(number - 1)">{{ number }}</button>
      <button :disabled="loading || page + 1 >= totalPages" aria-label="下一页" @click="go(page + 1)">下一页</button>
      <button :disabled="loading || page + 1 >= totalPages" aria-label="末页" @click="go(lastPage - 1)">末页</button>
    </div>
    <form class="jump" @submit.prevent="jump"><label>跳至<input v-model.number="jumpPage" :disabled="loading" type="number" min="1" :max="lastPage" aria-label="跳转页码">页</label><button :disabled="loading">跳转</button></form>
  </nav>
</template>

<style scoped>
.logistics-pager{display:flex;align-items:center;gap:12px;flex-wrap:wrap;margin-top:16px;padding-top:14px;border-top:1px solid #edf1f4;color:#71838e;font-size:12px}.summary{margin-right:auto;font-weight:650;color:#526b79}.logistics-pager label{display:flex;flex-direction:row;align-items:center;gap:6px}.logistics-pager select,.logistics-pager input{border:1px solid #cdd8e0;border-radius:6px;background:#fff;color:#253d4c;padding:6px 8px}.logistics-pager input{width:54px}.buttons{display:flex;gap:5px;align-items:center}.logistics-pager button{border:1px solid #ccd7df;border-radius:6px;background:#fff;color:#435e70;padding:6px 9px;cursor:pointer}.logistics-pager button.current{background:#da853c;border-color:#da853c;color:#fff;font-weight:700}.logistics-pager button:disabled{opacity:.45;cursor:not-allowed}.jump{display:flex;align-items:center;gap:6px}.jump button{padding:6px 9px}@media(max-width:900px){.summary{width:100%}.buttons{order:3;width:100%;overflow:auto}.jump{margin-left:auto}}
</style>
