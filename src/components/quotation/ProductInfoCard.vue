<script setup lang="ts">
import type { QuotationProduct } from './types'
defineProps<{ product: QuotationProduct }>()
</script>

<template>
  <section class="product-card">
    <div class="image"><img v-if="product.image" :src="product.image" :alt="product.name"><span v-else>{{ product.sku.slice(0, 1) || '品' }}</span></div>
    <div class="info"><small>当前报价商品</small><h2>{{ product.name }}</h2><p><code>{{ product.sku }}</code><span>采购：{{ product.supplier }}</span><span class="stock" :class="{ out:product.stockStatus==='无货', pending:product.stockStatus==='待确认' }">是否有货：{{ product.stockStatus }}</span></p></div>
    <div class="state"><i></i><span>{{ product.status }}</span><small>采购资料与物流数据已关联</small></div>
  </section>
</template>

<style scoped>
.product-card{display:grid;grid-template-columns:80px minmax(0,1fr) auto;align-items:center;gap:18px;padding:20px;border:1px solid #e3e8ed;border-radius:12px;background:#fff;box-shadow:0 7px 22px rgba(17,24,39,.04)}.image{width:80px;height:80px;display:grid;place-items:center;overflow:hidden;border-radius:12px;background:#edf2f4;color:#46777a;font-size:24px;font-weight:900}.image img{width:100%;height:100%;object-fit:cover}.info>small{color:#8c97a1;font-size:10px}.info h2{margin:5px 0 10px;font-size:18px}.info p{display:flex;flex-wrap:wrap;gap:7px;margin:0}.info code,.info p span{padding:4px 8px;border-radius:12px;background:#f2f5f7;color:#687580;font-size:10px}.info p .stock{background:#e8f6ee;color:#17794f;font-weight:800}.info p .stock.out{background:#fce9e7;color:#b84032}.info p .stock.pending{background:#fff3da;color:#9b6500}.state{display:grid;grid-template-columns:auto 1fr;align-items:center;gap:4px 7px;padding:12px 14px;border-radius:9px;background:#f1f9f4}.state i{width:8px;height:8px;border-radius:50%;background:#24ac6e}.state span{color:#167b50;font-size:12px;font-weight:800}.state small{grid-column:1/-1;color:#76a08b;font-size:9px}@media(max-width:700px){.product-card{grid-template-columns:64px 1fr}.image{width:64px;height:64px}.state{grid-column:1/-1}}
</style>
