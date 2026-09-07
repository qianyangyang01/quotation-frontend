<script setup lang="ts">
import { computed } from 'vue'
import type { Price, SourceIssue } from '@/data/logisticsRebuild'
import { issueLocations, priceIssueLocationLabel, rowsForPriceIssue } from '@/data/logisticsPriceIssueScope'
const props = defineProps<{ rows: Price[]; issues: SourceIssue[]; busy?: boolean; canSuggest?: boolean }>()
defineEmits<{ locate: [issue: SourceIssue]; suggest: [issue: SourceIssue] }>()
const entries = computed(() => props.issues.map(issue => ({ issue, label: priceIssueLocationLabel(props.rows, issue),
  matched: rowsForPriceIssue(props.rows, issue).length, locations: issueLocations(props.rows, issue),
  evidence: issue.sourceEvidence?.length ? issue.sourceEvidence : issue.rawValues ? [{ row: issue.row, rawValues: issue.rawValues }] : [] })))
function rawText(values: Record<string, unknown>) {
  return Object.entries(values).filter(([, value]) => value != null && value !== '').map(([key, value]) => `${key}：${String(value)}`).join('；')
}
</script>

<template>
  <section v-if="entries.length" class="issue-summary" aria-label="问题原因与原表位置">
    <h3>问题原因与原表位置</h3>
    <article v-for="(entry, index) in entries" :key="index" :class="{ warning: entry.issue.level !== 'error' }">
      <strong>{{ entry.issue.level === 'error' ? '阻断' : '提醒' }} · {{ entry.issue.field || '渠道规则' }}</strong>
      <p>{{ entry.issue.message }}</p>
      <p class="location">{{ entry.label }}</p>
      <button v-if="entry.matched" type="button" :disabled="busy" @click="$emit('locate', entry.issue)">定位 {{ entry.matched }} 条相关价格</button>
      <p v-else-if="entry.locations.length">该原表位置尚未对应到价格列表，无法在下方标红。请按上述 Sheet 和行号查看原表；不要通过修改其他价格行消除问题。</p>
      <p v-else>此问题没有可靠的价格行定位，请核对渠道规则或原表结构。</p>
      <details v-if="entry.evidence.length"><summary>查看原始内容</summary><p v-for="(evidence, i) in entry.evidence" :key="i">第 {{ evidence.row }} 行：{{ rawText(evidence.rawValues) }}</p></details>
      <button v-if="canSuggest && entry.issue.suggestedFields" type="button" :disabled="busy" @click="$emit('suggest', entry.issue)">采用边界建议</button>
    </article>
  </section>
</template>

<style scoped>
.issue-summary{margin:12px 0;padding:14px;border:1px solid #edc3be;border-radius:8px;background:#fffafa}.issue-summary h3{margin:0 0 10px;font-size:15px}.issue-summary article{margin-top:8px;padding:12px;border-left:3px solid #c84238;background:#fff3f1;overflow-wrap:anywhere}.issue-summary article.warning{border-color:#c78b26;background:#fff8e9}.issue-summary p{margin:6px 0;line-height:1.6}.location{font-weight:650;color:#963b31}.issue-summary button{padding:6px 10px;border:1px solid #bf7169;border-radius:5px;background:white;color:#963b31;cursor:pointer}.issue-summary details{margin-top:8px}
</style>
