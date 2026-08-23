<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()
const quotation = ref<Record<string, unknown> | null>(null)
const error = ref('')
onMounted(async () => {
  try {
    const response = await fetch(`/api/public/v1/quotation-shares/${encodeURIComponent(String(route.params.token || ''))}`, { headers: { Accept: 'application/json' } })
    const body = await response.json() as { message?: string; data?: Record<string, unknown> }
    if (!response.ok || !body.data) throw new Error(body.message || '分享不存在或已失效')
    quotation.value = body.data
  } catch (reason) { error.value = reason instanceof Error ? reason.message : '分享加载失败' }
})
function display(value: unknown) { return value == null || value === '' ? '—' : typeof value === 'object' ? JSON.stringify(value) : String(value) }
</script>

<template>
  <main class="share-page"><header><i>M</i><div><b>米莱诺客户报价单</b><small>MILANO CUSTOMER QUOTATION</small></div></header><section v-if="error" class="error"><h1>报价链接不可用</h1><p>{{ error }}</p></section><section v-else-if="!quotation" class="loading">正在安全读取报价…</section><section v-else class="sheet"><small>QUOTATION</small><h1>{{ quotation.no }}</h1><p>此页面仅包含客户可见报价内容，不包含采购成本、内部利润或审计记录。</p><div class="grid"><article v-for="(value,key) in quotation" :key="key" v-show="!['id','no','status','createdAt','updatedAt'].includes(String(key))"><small>{{ key }}</small><p>{{ display(value) }}</p></article></div><footer><span>状态：{{ quotation.status }}</span><span>创建时间：{{ quotation.createdAt }}</span></footer></section></main>
</template>

<style scoped>
.share-page{box-sizing:border-box;min-height:100vh;padding:28px max(22px,calc((100vw - 1050px)/2));background:#071d2f;color:#102537;font-family:"Microsoft YaHei",sans-serif}.share-page>header{display:flex;align-items:center;gap:11px;margin-bottom:26px;color:#fff}.share-page>header i{display:grid;width:42px;height:42px;place-items:center;border-radius:10px;background:#ff9810;color:#102537;font-size:22px;font-style:normal;font-weight:950}.share-page>header div{display:grid}.share-page>header small{color:#9bb0c1;letter-spacing:.16em}.sheet,.error,.loading{padding:34px;border-radius:14px;background:#fff}.sheet>small{color:#d87600;font-weight:900;letter-spacing:.18em}.sheet h1{margin:8px 0;font-size:30px}.sheet>p{color:#6f7e89}.grid{display:grid;grid-template-columns:1fr 1fr;gap:1px;margin-top:25px;border:1px solid #e1e7eb;background:#e1e7eb}.grid article{min-width:0;padding:13px;background:#fff}.grid small{color:#7f8c96}.grid p{overflow-wrap:anywhere;margin:7px 0;white-space:pre-wrap}.sheet footer{display:flex;justify-content:space-between;margin-top:22px;padding-top:15px;border-top:1px solid #e5eaed;color:#6f7d87}.error{text-align:center}.loading{text-align:center;color:#71808a}@media(max-width:650px){.grid{grid-template-columns:1fr}.sheet footer{flex-direction:column;gap:8px}}
</style>
