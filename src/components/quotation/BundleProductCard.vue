<script setup lang="ts">
import type { BundleQuoteItem } from './types'
import QuotationProductImage from './QuotationProductImage.vue'

const grams = (weightKg: number) => Math.ceil((Number.isFinite(Number(weightKg)) ? Number(weightKg) : 0) * 1000)
const effectiveWeightKg = (item: BundleQuoteItem) => item.customWeightKg == null ? item.weightKg : item.customWeightKg
const rowDomesticFreight = (item: BundleQuoteItem) => item.purchaseFreightPerUnit * Math.max(1, Math.floor(Number(item.quantityPerSet) || 1))

defineProps<{
  items: BundleQuoteItem[]
  purchaseCost: number
  totalWeight: number
  domesticFreight: number
}>()
defineEmits<{
  add: []
  remove: [id: number]
  query: [item: BundleQuoteItem]
  quantityChange: [item: BundleQuoteItem]
  weightChange: [item: BundleQuoteItem]
}>()
</script>

<template>
  <section class="bundle-card">
    <header>
      <div><p>02</p><section><h2>组合商品明细</h2><span>每行数量表示一套组合中包含的商品件数</span></section></div>
      <button type="button" @click="$emit('add')">＋ 添加 SKU</button>
    </header>

    <div class="table-head"><span>商品信息 / SKU</span><span>单套数量</span><span>采购单价</span><span>单件重量（可自定义）</span><span>单套国内运费</span><span>操作</span></div>
    <div class="bundle-rows">
      <article v-for="(item,index) in items" :key="item.id">
        <div class="product-cell">
          <QuotationProductImage class="thumb" :physical-image="item.physicalImage" :product-image="item.image" :alt="`${item.name || item.sku}商品图`" :fallback-text="String(index + 1)" />
          <div><label><input v-model.trim="item.sku" placeholder="输入 SKU" @keyup.enter="$emit('query',item)"><button type="button" @click="$emit('query',item)">查询</button></label><b>{{ item.name || '等待查询采购资料' }}</b><small>{{ item.supplier || '—' }} · {{ item.status || '待查询' }} · 库存：<em :class="{ out:item.stockStatus==='无货', pending:item.stockStatus==='待确认' }">{{ item.stockStatus }}</em></small></div>
        </div>
        <label class="qty"><input v-model.number="item.quantityPerSet" type="number" min="1" step="1" @change="$emit('quantityChange',item)"><span>件/套</span></label>
        <div class="purchase-price"><b>¥{{ item.purchaseUnitPrice.toFixed(2) }}</b><small>采购资料匹配</small></div>
        <label class="custom-weight"><input :value="grams(effectiveWeightKg(item))" type="number" min="0" step="1" @input="item.customWeightKg=Math.max(0,Number(($event.target as HTMLInputElement).value)||0)/1000;$emit('weightChange',item)"><span>g</span><small>{{ item.customWeightKg == null ? `采购克重 ${grams(item.weightKg)}g` : '自定义克重' }}</small><button v-if="item.customWeightKg != null" type="button" @click="item.customWeightKg=null;$emit('weightChange',item)">恢复</button></label>
        <div class="row-domestic-freight"><b>¥{{ rowDomesticFreight(item).toFixed(2) }}</b><small>¥{{ item.purchaseFreightPerUnit.toFixed(2) }}/件 × {{ Math.max(1, Math.floor(Number(item.quantityPerSet) || 1)) }}</small></div>
        <button class="remove" type="button" :disabled="items.length <= 1" @click="$emit('remove',item.id)">删除</button>
      </article>
    </div>

    <div class="summary-grid">
      <div><span>单套采购成本</span><b>¥{{ purchaseCost.toFixed(2) }}</b><small>各 SKU 按单套数量匹配阶梯价</small></div>
      <div><span>单套商品重量（g）</span><b>{{ grams(totalWeight) }} g</b><small>全部 SKU 商品重量合计</small></div>
      <div><span>单套国内运费</span><b>¥{{ domesticFreight.toFixed(2) }}</b><small>各 SKU 采用10件运费平摊</small></div>
    </div>
  </section>
</template>

<style scoped>
.bundle-card{overflow:hidden;border:1px solid #e3e8ed;border-radius:12px;background:#fff;box-shadow:0 7px 22px rgba(17,24,39,.04)}header{display:flex;align-items:center;justify-content:space-between;padding:19px 21px;border-bottom:1px solid #edf0f3}header>div{display:flex;align-items:center;gap:10px}header p{width:28px;height:28px;display:grid;place-items:center;margin:0;border-radius:8px;background:#ff9900;color:#17212b;font-size:11px;font-weight:900}header section{display:grid;gap:3px}h2{margin:0;font-size:17px}header span{color:#87939d;font-size:10px}header button{height:38px;padding:0 15px;border:1px solid #ff9900;border-radius:7px;background:#fff;color:#bb6800;font-weight:800}.table-head,.bundle-rows article{display:grid;grid-template-columns:minmax(330px,2.2fr) 110px 155px 100px 55px;align-items:center;gap:12px}.table-head{margin:14px 20px 0;padding:10px 12px;border-radius:7px 7px 0 0;background:#f6f8fa;color:#77838d;font-size:10px}.bundle-rows{margin:0 20px}.bundle-rows article{min-height:82px;padding:11px 12px;border:1px solid #e8edf1;border-top:0}.product-cell{display:flex;align-items:center;gap:12px;min-width:0}.thumb{width:54px;height:54px;display:grid;flex:0 0 auto;place-items:center;overflow:hidden;border-radius:9px;background:#edf2f4;color:#63727d;font-weight:900}.thumb img{width:100%;height:100%;object-fit:cover}.product-cell>div:last-child{min-width:0}.product-cell label{display:flex;margin-bottom:5px}.product-cell label input{width:145px;height:27px;box-sizing:border-box;border:1px solid #d9e0e5;border-radius:5px 0 0 5px;padding:0 8px;font-size:10px;text-transform:uppercase}.product-cell label button{height:27px;border:0;border-radius:0 5px 5px 0;background:#17232d;color:#fff;font-size:9px}.product-cell b,.product-cell small{display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.product-cell b{font-size:12px}.product-cell small{margin-top:3px;color:#8a959f;font-size:9px}.product-cell small em{color:#178055;font-style:normal;font-weight:800}.product-cell small em.out{color:#bd493b}.product-cell small em.pending{color:#a66a00}.qty{display:flex}.qty input{width:62px;height:34px;box-sizing:border-box;border:1px solid #d9e0e5;border-radius:6px 0 0 6px;padding:0 7px}.qty span{height:34px;display:grid;place-items:center;padding:0 7px;border:1px solid #d9e0e5;border-left:0;border-radius:0 6px 6px 0;background:#f6f8fa;color:#74808a;font-size:9px}.custom-purchase-price{position:relative;display:grid;grid-template-columns:22px 74px 38px;align-items:center;min-height:50px}.custom-purchase-price>span{height:32px;display:grid;place-items:center;border:1px solid #d9e0e5;border-right:0;border-radius:6px 0 0 6px;background:#f5f7f9;color:#596671;font-size:11px;font-weight:850}.custom-purchase-price input{box-sizing:border-box;width:74px;height:32px;border:1px solid #d9e0e5;border-radius:0 6px 6px 0;padding:0 7px;color:#17232d;font-size:11px;font-weight:800;outline:0}.custom-purchase-price input:focus{border-color:#ff9900;box-shadow:0 0 0 2px rgba(255,153,0,.12)}.custom-purchase-price small{position:absolute;left:0;bottom:0;color:#8a959e;font-size:8px}.custom-purchase-price>button{height:25px;margin-left:5px;border:0;background:none;color:#ba6800;font-size:8px;font-weight:800;cursor:pointer}.remove{border:0;background:none;color:#c74e3e;font-size:10px}.remove:disabled{opacity:.35}.summary-grid{display:grid;grid-template-columns:repeat(3,1fr);gap:10px;padding:15px 20px 20px}.summary-grid>div{display:grid;gap:5px;min-width:0;padding:12px;border-radius:9px;background:#f6f8fa}.summary-grid span{color:#74808b;font-size:10px}.summary-grid b{font-size:17px}.summary-grid small{color:#96a0a9;font-size:8px}@media(max-width:1100px){.bundle-card{overflow-x:auto}.table-head,.bundle-rows article{min-width:800px}.summary-grid{min-width:620px}}
.table-head,.bundle-rows article{grid-template-columns:minmax(330px,2.2fr) 110px 120px 155px 55px}.purchase-price{display:grid;gap:4px}.purchase-price b{font-size:12px}.purchase-price small{color:#8a959e;font-size:8px}.custom-weight{position:relative;display:grid;grid-template-columns:80px 28px 38px;align-items:center;min-height:50px}.custom-weight input{box-sizing:border-box;width:80px;height:32px;border:1px solid #d9e0e5;border-radius:6px 0 0 6px;padding:0 8px;color:#17232d;font-size:11px;font-weight:800;outline:0}.custom-weight input:focus{border-color:#ff9900;box-shadow:0 0 0 2px rgba(255,153,0,.12)}.custom-weight>span{height:32px;display:grid;place-items:center;border:1px solid #d9e0e5;border-left:0;border-radius:0 6px 6px 0;background:#f5f7f9;color:#596671;font-size:9px;font-weight:850}.custom-weight small{position:absolute;left:0;bottom:0;color:#8a959e;font-size:8px}.custom-weight>button{height:25px;margin-left:5px;border:0;background:none;color:#ba6800;font-size:8px;font-weight:800;cursor:pointer}
.table-head,.bundle-rows article{grid-template-columns:minmax(300px,2.2fr) 105px 105px 150px 125px 50px}.row-domestic-freight{display:grid;gap:4px}.row-domestic-freight b{font-size:12px}.row-domestic-freight small{color:#8a959e;font-size:8px}
</style>
