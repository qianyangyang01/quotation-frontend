<script setup lang="ts">
import type { BatchSingleLine } from '@/services/batchSingleQuotation'
import { computed } from 'vue'
const props = defineProps<{ lines: BatchSingleLine[]; currentSku: string; validating: boolean; stopping: boolean }>()
defineEmits<{ stop: [] }>()
const success = computed(() => props.lines.filter(line => line.item && !line.error))
const failed = computed(() => props.lines.filter(line => line.error))
const completed = computed(() => success.value.length + failed.value.length)
const percent = computed(() => props.lines.length ? Math.floor(completed.value / props.lines.length * 100) : 0)
const rowCount = computed(() => success.value.reduce((sum, line) => sum + line.item!.sheet.rows.length, 0))
</script>
<template>
  <section class="batch-progress" aria-labelledby="batch-progress-heading" aria-busy="true">
    <header><h2 id="batch-progress-heading">03 生成整批报价</h2><span>{{ stopping ? '正在停止' : validating ? '正在校验条件' : '正在计算' }}</span></header>
    <div class="content">
      <div class="heading"><div><h3>{{ stopping ? '正在结束当前 SKU' : validating ? '正在校验公共条件与渠道' : '正在生成整批报价' }}</h3><p>按各 SKU 的成本和重量独立计算，完成后统一展示报价单</p></div><strong>{{ completed }}<small> / {{ lines.length }}</small></strong></div>
      <div class="track" role="progressbar" aria-label="整批报价生成进度" :aria-valuenow="completed" :aria-valuemax="lines.length" :aria-valuemin="0"><i :style="{ width: `${percent}%` }" /></div>
      <div class="caption"><span>已完成 {{ percent }}%</span><span>无需重复点击，页面会自动展示计算结果</span></div>
      <div class="stats"><div><small>成功 SKU</small><b class="success">{{ success.length }} <em>个</em></b></div><div><small>异常 SKU</small><b>{{ failed.length }} <em>个</em></b></div><div><small>剩余 SKU</small><b>{{ lines.length - completed }} <em>个</em></b></div><div><small>已生成明细</small><b>{{ rowCount }} <em>行</em></b></div></div>
      <div class="current" role="status"><i aria-hidden="true" /><div><b>{{ validating ? '正在核对报价资料' : `正在处理 ${currentSku}` }}</b><small>{{ stopping ? '当前 SKU 结束后停止，不再处理下一项；已完成结果会保留' : '正在核对资料并计算所选渠道' }}</small></div></div>
      <details><summary>查看 SKU 处理明细 · 已完成 {{ completed }} 个</summary><div class="details-scroll"><p v-for="line in lines" :key="line.sku"><span>{{ line.sku }}</span><span :class="{ success: line.item, error: line.error }">{{ line.error || (line.item ? `计算完成 · ${line.item.sheet.rows.length} 行明细` : line.sku === currentSku ? '正在计算' : '等待计算') }}</span></p></div></details>
    </div>
    <footer><span>生成过程不会自动保存报价单</span><button :disabled="validating || stopping" @click="$emit('stop')">{{ stopping ? '正在停止…' : '停止计算' }}</button></footer>
  </section>
</template>
<style scoped>
.batch-progress{border:1px solid #d9d1f4;border-radius:9px;background:#fff;overflow:hidden;color:#18334c}header,footer,.heading,.caption{display:flex;justify-content:space-between;align-items:center;gap:16px}header{padding:16px 20px;background:#f3effc}h2{margin:0;font-size:17px}header>span{color:#b87712;font-size:12px}.content{padding:30px 36px 22px;min-height:390px}h3{margin:0;font-size:21px}.heading p{color:#798ba0;font-size:13px;margin:10px 0 0}.heading>strong{font-size:34px;color:#e58b00}.heading small{font-size:18px;font-weight:400;color:#8999aa}.track{height:12px;border-radius:8px;background:#eef1f5;margin-top:24px;overflow:hidden}.track i{height:100%;display:block;background:#f79400;transition:width .2s}.caption{font-size:12px;color:#8999aa;margin-top:10px}.caption>span:first-child{color:#c67b08}.stats{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));border:1px solid #e4eaf0;border-radius:8px;padding:16px 0;margin-top:24px}.stats>div{padding:0 24px;border-right:1px solid #e4eaf0}.stats>div:last-child{border:0}.stats small{display:block;font-size:12px;color:#7b8ca0;margin-bottom:7px}.stats b{font-size:26px}.stats em{font-style:normal;font-size:12px;font-weight:400;color:#8597aa}.success{color:#1b986a}.error{color:#b33}.current{display:flex;gap:14px;align-items:center;background:#f8fafc;border-radius:6px;padding:14px;margin-top:18px}.current>i{width:18px;height:18px;border:2px solid #ffe8c6;border-top-color:#f79400;border-radius:50%;animation:spin 1s linear infinite}.current b{font-size:13px;font-weight:500}.current small{display:block;color:#7e90a4;margin-top:5px;font-size:12px}details{margin-top:18px;font-size:12px;color:#70849a}summary{cursor:pointer}.details-scroll{max-height:230px;overflow:auto}.details-scroll p{display:flex;justify-content:space-between;gap:14px;padding:5px 0}footer{padding:15px 20px;background:#fcfcfe;border-top:1px solid #e6eaf0;font-size:12px;color:#8394a8}button{border:1px solid #d6e1ec;border-radius:6px;padding:10px 16px;background:white;color:#29445f;cursor:pointer}button:disabled{opacity:.5;cursor:default}@keyframes spin{to{transform:rotate(360deg)}}@media(prefers-reduced-motion:reduce){.current>i{animation:none}.track i{transition:none}}@media(max-width:700px){.content{padding:20px 16px}.heading{align-items:flex-start}.heading h3{font-size:17px}.heading>strong{font-size:26px;white-space:nowrap}.stats{grid-template-columns:1fr 1fr;row-gap:18px}.stats>div:nth-child(2){border:0}.stats>div{padding:0 16px}.caption,footer{align-items:flex-start;flex-wrap:wrap}}
</style>
