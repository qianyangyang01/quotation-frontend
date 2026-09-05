<script setup lang="ts">
defineProps<{ phase: string; detail: string; elapsed: number; completed: number; total: number; summary?: string }>()
</script>

<template>
  <div v-if="phase !== 'idle'" class="publish-feedback" :class="{ warning: phase === 'unconfirmed' }">
    <div role="status" aria-live="polite">
      <strong>{{ summary || (phase === 'publishing' ? '正在发布物流价格…' : phase === 'unconfirmed' ? '发布结果待确认' : '发布处理完成') }}</strong>
      <p>{{ detail }}</p>
    </div>
    <div v-if="total" class="publish-wait">
      <progress :value="completed" :max="total" aria-label="物流发布进度" />
      <span>{{ phase === 'publishing' || phase === 'unconfirmed' ? '已确认发布' : '已处理' }} {{ completed }} / {{ total }} 个渠道 · {{ Math.floor(completed / total * 100) }}%</span>
      <span v-if="phase === 'publishing' || phase === 'refreshing'">已等待 {{ elapsed }} 秒</span>
    </div>
  </div>
</template>

<style scoped>
.publish-feedback{grid-column:1/-1;padding:12px 15px;border:1px solid #b9d9c9;border-radius:8px;background:#f0f9f4;color:#245b40}
.publish-feedback.warning{border-color:#e6c894;background:#fff8ed;color:#805a22}
.publish-feedback p{margin:5px 0 0;line-height:1.5}
.publish-wait{display:flex;gap:12px;align-items:center;margin-top:9px;font-size:12px}
.publish-wait progress{width:min(320px,65%);height:8px;accent-color:#df8835}
</style>
