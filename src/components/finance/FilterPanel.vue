<script setup lang="ts">
import { ref } from 'vue'

defineProps<{
  search: string
  status: string
  country: string
  provider: string
  countryOptions: string[]
  providerOptions: string[]
  total: number
}>()

const emit = defineEmits<{
  'update:search': [value: string]
  'update:status': [value: string]
  'update:country': [value: string]
  'update:provider': [value: string]
  reset: []
}>()
const showMore = ref(false)
</script>

<template>
  <section class="filter-panel">
    <div class="filter-main">
      <label class="search-box"><span>⌕</span><input :value="search" placeholder="搜索物流属性、国家、服务商或渠道" @input="emit('update:search', ($event.target as HTMLInputElement).value)"></label>
      <select :value="status" @change="emit('update:status', ($event.target as HTMLSelectElement).value)"><option value="">全部状态</option><option value="启用">启用</option><option value="停用">停用</option></select>
      <button class="more-filter" type="button" :class="{ active:showMore }" @click="showMore=!showMore">更多筛选 <span>⌄</span></button>
      <button class="reset" type="button" @click="emit('reset')">重置</button>
      <div v-if="showMore" class="filter-popover">
        <label>国家<select :value="country" @change="emit('update:country', ($event.target as HTMLSelectElement).value)"><option value="">全部国家</option><option v-for="item in countryOptions" :key="item">{{ item }}</option></select></label>
        <label>服务商<select :value="provider" @change="emit('update:provider', ($event.target as HTMLSelectElement).value)"><option value="">全部服务商</option><option v-for="item in providerOptions" :key="item">{{ item }}</option></select></label>
      </div>
    </div>
    <div class="filter-meta">
      <div class="active-tags">
        <span v-if="country">国家：{{ country }} <button @click="emit('update:country','')">×</button></span>
        <span v-if="provider">服务商：{{ provider }} <button @click="emit('update:provider','')">×</button></span>
        <span v-if="status">状态：{{ status }} <button @click="emit('update:status','')">×</button></span>
        <small v-if="!country && !provider && !status">当前未设置额外筛选条件</small>
      </div>
      <strong>共 {{ total }} 条数据</strong>
    </div>
  </section>
</template>

<style scoped>
.filter-panel{height:112px;margin-bottom:14px;padding:14px 16px;box-sizing:border-box;border:1px solid #e1e6ea;border-radius:8px;background:#fff;box-shadow:0 5px 18px rgba(24,39,52,.045)}.filter-main{position:relative;display:flex;align-items:center;gap:9px}.search-box{width:min(420px,38vw);height:38px;display:flex;align-items:center;gap:9px;padding:0 12px;border:1px solid #d8dfe4;border-radius:7px;background:#fff}.search-box:focus-within{border-color:#ff9900;box-shadow:0 0 0 3px rgba(255,153,0,.12)}.search-box input{width:100%;border:0;outline:0;color:#29343d;font-size:12px}.filter-main>select,.filter-main>button{height:38px;padding:0 13px;border:1px solid #d8dfe4;border-radius:7px;background:#fff;color:#45525c}.filter-main>select{min-width:130px}.filter-main button{cursor:pointer}.more-filter.active{border-color:#ffb84f;background:#fff7e9;color:#9c5700}.reset{color:#7c8790!important}.filter-popover{position:absolute;left:420px;top:45px;z-index:20;display:grid;grid-template-columns:1fr 1fr;gap:10px;width:390px;padding:13px;border:1px solid #dbe2e7;border-radius:8px;background:#fff;box-shadow:0 14px 35px rgba(24,39,52,.16)}.filter-popover label{display:grid;gap:5px;color:#77828b;font-size:10px}.filter-popover select{height:34px;border:1px solid #d8dfe4;border-radius:6px;background:#fff;padding:0 8px}.filter-meta{display:flex;align-items:center;justify-content:space-between;margin-top:12px}.active-tags{display:flex;align-items:center;gap:7px}.active-tags span{padding:4px 8px;border-radius:12px;background:#fff2dc;color:#965300;font-size:10px}.active-tags button{border:0;background:none;color:inherit}.active-tags small{color:#9aa3aa}.filter-meta>strong{color:#65717a;font-size:11px}@media(max-width:900px){.search-box{width:46vw}.filter-popover{left:auto;right:0}}@media(max-width:700px){.filter-panel{height:auto}.filter-main{flex-wrap:wrap}.search-box{width:100%}.filter-popover{position:static;width:100%;grid-template-columns:1fr}.filter-meta{gap:12px}.active-tags{flex-wrap:wrap}}
</style>
