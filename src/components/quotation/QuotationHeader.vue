<script setup lang="ts">
defineProps<{ salesperson: string; rate: number; status: string; modeLabel: string }>()
defineEmits<{ showRule: [] }>()
</script>

<template>
  <section class="quotation-header">
    <div class="heading">
      <p>MILANO PRICING CENTER</p>
      <h1>产品报价工作台</h1>
      <span>按 SKU、物流属性、国家和客户等级自动生成报价。</span>
    </div>
    <div class="header-meta">
      <dl><div><dt>报价模式</dt><dd>{{ modeLabel }}</dd></div><div><dt>当前币种</dt><dd>USD</dd></div><div><dt>报价状态</dt><dd class="ready"><i></i>{{ status }}</dd></div></dl>
      <div class="actions"><button class="rule" @click="$emit('showRule')">计算规则</button></div>
      <small>报价人：{{ salesperson }} · 财务汇率 {{ rate }}</small>
    </div>
  </section>
</template>

<style scoped>
.quotation-header{display:flex;align-items:center;justify-content:space-between;gap:28px;padding:24px 26px;border:1px solid #e4e9ee;border-radius:12px;background:#fff;box-shadow:0 8px 28px rgba(17,24,39,.05)}.heading p{margin:0 0 7px;color:#dc7b00;font-size:10px;font-weight:900;letter-spacing:.19em}.heading h1{margin:0 0 7px;color:#17212b;font-size:24px;line-height:1.2}.heading span{color:#74808b;font-size:13px}.header-meta{display:grid;justify-items:end;gap:10px}.header-meta dl{display:flex;gap:26px;margin:0}.header-meta dl div{display:grid;gap:4px}.header-meta dt{color:#98a2ad;font-size:11px}.header-meta dd{margin:0;color:#26313b;font-size:13px;font-weight:800}.header-meta .ready{color:#178757}.ready i{display:inline-block;width:7px;height:7px;margin-right:6px;border-radius:50%;background:#22ad6c}.actions{display:flex;gap:9px}.actions button{height:38px;padding:0 17px;border-radius:7px;font-size:12px;font-weight:800}.rule{border:1px solid #dce2e7;background:#fff;color:#52606c}.header-meta small{color:#929da7;font-size:10px}@media(max-width:900px){.quotation-header{align-items:flex-start;flex-direction:column}.header-meta{width:100%;justify-items:start}.header-meta dl{flex-wrap:wrap}.actions{position:absolute;right:7vw}.header-meta small{display:none}}@media(max-width:620px){.actions{position:static}.header-meta dl{gap:16px}}
</style>
