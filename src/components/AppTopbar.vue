<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { currentAuthUser, currentRole, defaultHomeForRole, hasAnyPermission, hasPermission, logout, type PermissionKey } from '@/data/authStore'

const route = useRoute()
const router = useRouter()
const accountMenuOpen = ref(false)
const navItems = computed(() => [
  { label: '报价预览', path: '/quotation/overview', permissions: ['allRecords'] },
  { label: '我的报价', path: '/quotation', permissions: ['quote'] },
  { label: '采购', path: '/quotation/products', permissions: ['purchase'] },
  { label: '物流', path: '/quotation/logistics', permissions: ['logistics'] },
  { label: '财务', path: '/quotation/members', permissions: ['finance'] },
  { label: '我的报价记录', path: '/quotation/my-records', permissions: ['myRecords', 'allRecords'] },
  { label: '报价记录', path: '/quotation/records', permissions: ['allRecords'] },
  { label: '权限管理', path: '/quotation/permissions', permissions: ['permissions'] },
].filter(item => hasAnyPermission(...item.permissions as PermissionKey[])))
const home = computed(() => defaultHomeForRole())
const initials = computed(() => currentAuthUser.value.name.slice(0, 2).toUpperCase())

async function signOut() {
  accountMenuOpen.value = false
  await logout()
  await router.replace('/login')
}
</script>

<template>
  <header class="app-topbar">
    <RouterLink class="app-brand" :to="home"><i>M</i><span><b>米莱诺报价</b><small>MILANO PRICING ERP</small></span></RouterLink>
    <nav><RouterLink v-for="item in navItems" :key="item.path" :class="{ active:route.path===item.path }" :to="item.path">{{ item.label }}</RouterLink></nav>
    <div class="app-user">
      <button type="button" @click="accountMenuOpen=!accountMenuOpen"><i>{{ initials }}</i><span><b>{{ currentAuthUser.name }}</b><small>{{ currentRole.name }} · {{ currentAuthUser.account }}</small></span><em>⌄</em></button>
      <div v-if="accountMenuOpen" class="account-menu">
        <header><b>{{ currentAuthUser.name }}</b><small>{{ currentRole.name }} · {{ currentAuthUser.account }}</small></header>
        <RouterLink v-if="hasPermission('permissions')" to="/quotation/permissions" @click="accountMenuOpen=false">账号与权限管理</RouterLink>
        <button class="logout" @click="signOut">退出登录</button>
      </div>
    </div>
  </header>
</template>

<style scoped>
.app-topbar{position:relative;z-index:30;display:flex;align-items:center;min-height:72px;padding:0 max(28px,calc((100vw - 1500px)/2));border-bottom:1px solid #e3e8ec;background:#fff;color:#172431;font-family:"Microsoft YaHei",sans-serif}.app-brand{display:flex;align-items:center;gap:10px;color:#172431;text-decoration:none}.app-brand>i{display:grid;width:42px;height:42px;place-items:center;border-radius:11px;background:#ff9810;color:#14212c;font-size:22px;font-style:normal;font-weight:950}.app-brand>span{display:grid}.app-brand b{color:#4e168a;font-size:16px}.app-brand small{color:#74818a;font-size:7px;letter-spacing:1.6px}.app-topbar nav{display:flex;align-self:stretch;align-items:center;gap:28px;margin-left:46px}.app-topbar nav a{position:relative;display:flex;height:100%;align-items:center;color:#53616c;font-size:13px;text-decoration:none;white-space:nowrap}.app-topbar nav a.active,.app-topbar nav a.router-link-active{color:#101c26;font-weight:900}.app-topbar nav a.active:after,.app-topbar nav a.router-link-active:after{position:absolute;right:0;bottom:-1px;left:0;height:3px;background:#ff9810;content:""}.app-user{position:relative;margin-left:auto}.app-user>button{display:flex;align-items:center;gap:9px;padding:6px 8px;border:0;background:transparent;cursor:pointer}.app-user i{display:grid;width:36px;height:36px;place-items:center;border-radius:50%;background:#142431;color:#fff;font-size:10px;font-style:normal;font-weight:900}.app-user span{display:grid;min-width:92px;text-align:left}.app-user b{color:#172431;font-size:12px}.app-user small{color:#7d8992;font-size:8px}.app-user em{color:#8d979f;font-size:10px;font-style:normal}.account-menu{position:absolute;top:52px;right:0;width:230px;padding:8px;border:1px solid #dfe5e9;border-radius:12px;background:#fff;box-shadow:0 18px 48px rgba(20,34,46,.17)}.account-menu header{display:grid;gap:2px;padding:8px 10px 10px;border-bottom:1px solid #edf0f2}.account-menu a,.account-menu>button{box-sizing:border-box;display:flex;width:100%;align-items:center;margin-top:4px;padding:10px;border:0;border-radius:8px;background:transparent;color:#364550;font-size:11px;text-decoration:none;cursor:pointer}.account-menu a:hover,.account-menu>button:hover{background:#fff3df}.account-menu>button.logout{color:#c64239}@media(max-width:1100px){.app-topbar{align-items:flex-start;flex-wrap:wrap;padding:12px 18px}.app-topbar nav{order:3;width:100%;height:42px;margin-left:0;gap:22px;overflow-x:auto}.app-user{margin-left:auto}}@media(max-width:640px){.app-brand span{display:none}.app-user span{display:none}.app-topbar nav{gap:18px}.app-topbar nav a{font-size:12px}}
</style>
