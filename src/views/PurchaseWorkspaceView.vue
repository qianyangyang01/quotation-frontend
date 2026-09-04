<script setup lang="ts">
import PurchaseDataWorkspace from '@/components/purchase/PurchaseDataWorkspace.vue'
</script>

<template>
  <main class="purchase-page">
    <PurchaseDataWorkspace />
  </main>
</template>

<style scoped>
:global(body){margin:0}.purchase-page{box-sizing:border-box;min-height:calc(100vh - 72px);width:100%;padding:36px max(3vw,calc((100vw - 1500px)/2)) 70px;background:#f4f6f8;color:#17232e;font-family:Inter,"PingFang SC","Microsoft YaHei",sans-serif}@media(max-width:640px){.purchase-page{padding:22px 3vw 50px}}
</style>
