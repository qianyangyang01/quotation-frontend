<script setup lang="ts">
import '@/styles/logisticsPriceHistory.css'
withDefaults(defineProps<{error?:string;loading?:boolean}>(),{error:'',loading:true})
const emit=defineEmits<{workspace:[mode:'base'|'rules'];refresh:[]}>()
</script>
<template>
 <div class="price-history-view price-loading-shell"><div class="logistics-page charts-screen"><main>
  <nav class="workspace-switch" aria-label="物流工作区"><button @click="emit('workspace','base')">基础资料设置</button><button @click="emit('workspace','rules')">运费规则列表</button><button class="active" aria-current="page">渠道价格变动</button></nav>
  <section class="rule-workspace-card chart-workspace" :aria-busy="loading">
   <header class="chart-title"><div><h2>渠道价格变动</h2><p>默认展示表内重点渠道 · 搜索可查看其他渠道 · CNY / 票</p></div></header>
   <div class="chart-filters" aria-label="价格筛选条件正在准备"><label>国家/地区<select disabled><option>德国</option></select></label><label>物流属性<select disabled><option>普货/服装</option></select></label><label>重量<select disabled><option>1 kg</option></select></label><label>比较<select disabled><option>本版 vs 上一版</option></select></label><label>渠道搜索<input disabled placeholder="模糊搜索其他渠道"/></label></div>
   <div v-if="error" class="initial-price-error" role="alert"><h3>暂时无法读取价格</h3><p>{{error}}</p><button :disabled="loading" @click="emit('refresh')">刷新最新价格</button><button @click="emit('workspace','rules')">返回运费规则列表</button></div>
   <template v-else><p class="initial-price-status" role="status">正在读取已发布价格…</p><div class="chart-grid" aria-hidden="true"><div v-for="i in 6" :key="i" class="channel-card skeleton-card"><i/><i/><b/><span/><i/></div></div></template>
  </section>
 </main></div></div>
</template>
