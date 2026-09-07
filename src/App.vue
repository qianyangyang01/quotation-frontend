<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import AppTopbar from '@/components/AppTopbar.vue'
import { authState, isAuthenticated } from '@/data/authStore'

const route = useRoute()
const showTopbar = computed(() => isAuthenticated.value && !['login', 'change-password'].includes(String(route.name || '')))
</script>

<template>
  <div v-if="!authState.initialized" class="app-bootstrap" aria-live="polite">
    <header><i>M</i><span><b>米莱诺报价</b><small>MILANO PRICING ERP</small></span></header>
    <main><div class="skeleton-title"></div><div class="skeleton-card"></div><p><i></i>正在加载报价工作区…</p></main>
  </div>
  <template v-else>
    <AppTopbar v-if="showTopbar" />
    <RouterView />
  </template>
</template>

<style scoped>
.app-bootstrap{min-height:100vh;background:#f3f6f8;color:#102536;font-family:Arial,"Microsoft YaHei",sans-serif}.app-bootstrap header{height:76px;display:flex;align-items:center;gap:10px;padding:0 max(28px,calc((100vw - 1500px)/2));border-bottom:1px solid #e1e7eb;background:#fff}.app-bootstrap header>i{width:42px;height:42px;display:grid;place-items:center;border-radius:12px;background:#ff9700;color:#102536;font-size:24px;font-style:normal;font-weight:900}.app-bootstrap header span{display:grid}.app-bootstrap header b{color:#53108d;font-size:18px}.app-bootstrap header small{color:#81909a;font-size:7px;letter-spacing:.18em}.app-bootstrap main{width:min(1480px,calc(100% - 48px));margin:34px auto}.skeleton-title{width:260px;height:32px;border-radius:7px;background:#e4e9ed}.skeleton-card{height:170px;margin-top:24px;border:1px solid #e0e6ea;border-radius:13px;background:linear-gradient(100deg,#fff 35%,#f3f6f8 50%,#fff 65%);background-size:220% 100%;animation:loading 1.2s infinite}.app-bootstrap p{display:flex;align-items:center;gap:8px;color:#697984;font-size:12px}.app-bootstrap p i{width:8px;height:8px;border-radius:50%;background:#ff9700;box-shadow:0 0 0 5px rgba(255,151,0,.15)}@keyframes loading{to{background-position:-220% 0}}
:global(#app>.module-app),:global(#app>.logistics-page),:global(#app>.permission-page),:global(#app>.overview-app),:global(#app>.app),:global(#app>.jerry-app),:global(#app>.erp){min-height:calc(100vh - 72px)}
</style>
