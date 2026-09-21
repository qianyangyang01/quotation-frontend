<script setup lang="ts">
import { computed, ref, onMounted, onUnmounted } from 'vue'
import { useRoute } from 'vue-router'
import AppTopbar from '@/components/AppTopbar.vue'
import PageLoadNotice from '@/components/PageLoadNotice.vue'
import { authState, isAuthenticated } from '@/data/authStore'
import { hasRequestAccountChanged } from '@/services/http'

const accountChanged = ref(hasRequestAccountChanged())
const showAccountChange = () => { accountChanged.value = true }
const refreshAccount = () => window.location.reload()
onMounted(() => window.addEventListener('quotation:account-changed', showAccountChange))
onUnmounted(() => window.removeEventListener('quotation:account-changed', showAccountChange))

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
    <PageLoadNotice />
    <aside v-if="accountChanged" class="account-change-notice" role="alert">
      <span>登录账号已在其他页面切换，当前操作未提交。请复制需要保留的内容，再刷新确认账号。</span>
      <button type="button" @click="refreshAccount">刷新并确认账号</button>
    </aside>
    <RouterView />
  </template>
</template>

<style scoped>
.account-change-notice{position:sticky;top:0;z-index:100;display:flex;align-items:center;justify-content:space-between;gap:16px;padding:14px 24px;border-bottom:1px solid #f5bc73;background:#fff3df;color:#8c4300;font-size:14px}.account-change-notice button{flex-shrink:0;padding:8px 12px;border:1px solid #df7a00;border-radius:6px;background:#fff;color:#8c4300;cursor:pointer}
.app-bootstrap{min-height:100vh;background:#f3f6f8;color:#102536;font-family:Arial,"Microsoft YaHei",sans-serif}.app-bootstrap header{height:76px;display:flex;align-items:center;gap:10px;padding:0 max(28px,calc((100vw - 1500px)/2));border-bottom:1px solid #e1e7eb;background:#fff}.app-bootstrap header>i{width:42px;height:42px;display:grid;place-items:center;border-radius:12px;background:#ff9700;color:#102536;font-size:24px;font-style:normal;font-weight:900}.app-bootstrap header span{display:grid}.app-bootstrap header b{color:#53108d;font-size:18px}.app-bootstrap header small{color:#81909a;font-size:7px;letter-spacing:.18em}.app-bootstrap main{width:min(1480px,calc(100% - 48px));margin:34px auto}.skeleton-title{width:260px;height:32px;border-radius:7px;background:#e4e9ed}.skeleton-card{height:170px;margin-top:24px;border:1px solid #e0e6ea;border-radius:13px;background:linear-gradient(100deg,#fff 35%,#f3f6f8 50%,#fff 65%);background-size:220% 100%;animation:loading 1.2s infinite}.app-bootstrap p{display:flex;align-items:center;gap:8px;color:#697984;font-size:12px}.app-bootstrap p i{width:8px;height:8px;border-radius:50%;background:#ff9700;box-shadow:0 0 0 5px rgba(255,151,0,.15)}@keyframes loading{to{background-position:-220% 0}}
:global(#app>.module-app),:global(#app>.logistics-page),:global(#app>.permission-page),:global(#app>.overview-app),:global(#app>.app),:global(#app>.jerry-app),:global(#app>.erp){min-height:calc(100vh - 72px)}
</style>
