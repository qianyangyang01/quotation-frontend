<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import Decimal from 'decimal.js'
import { averagePrices, averageScope, averageRegionLabel, australiaAverageZone, type AveragePlan } from '@/data/quoteChannelAverage'
import { quoteSheetRowKey, quoteSheetProviderName, type QuoteSheetSourceRow, type CustomerQuoteSheet } from '@/data/customerQuoteSheet'

const props = defineProps<{ rows: QuoteSheetSourceRow[]; system: CustomerQuoteSheet; quantities: number[]; plans: AveragePlan[]; disabled: boolean }>()
const emit = defineEmits<{ add: [plan: AveragePlan]; remove: [id: string] }>()
const scope = ref(''), mode = ref<'equal' | 'weighted'>('equal'), display = ref<'summary' | 'details'>('details')
const weights = ref<Record<string, string>>({}), chosen = ref<string[]>([])
const chosenZones = ref<number[]>([])
const editingId = ref('')
const provider = ref('Combined Shipping'), shippingTime = ref(''), error = ref('')
function taxLabel(row: QuoteSheetSourceRow) { return ({ 'no-tax': '无关税', exempt: '免税', 'fixed-order': '按票计税', 'per-item': '按件计税', 'weight-eur': '按重计税', 'weight-order': '按重及票计税', missing: '税费待配置' } as Record<string, string>)[row.taxFeeMode || ''] || '已保存税费口径' }
function sourceLabel(row: QuoteSheetSourceRow) { return [row.carrier, row.transport, row.quoteRegion === '全国统一' ? '' : row.quoteRegion].filter(Boolean).join(' · ') }
const groups = computed(() => [...new Map(props.rows.filter(row => row.available !== false).map(row => [averageScope(row), { key: averageScope(row), label: `${row.country} · ${averageRegionLabel(row)} · ${taxLabel(row)}` }])).values()])
watch(groups, values => { if (!values.some(g => g.key === scope.value)) { scope.value = values[0]?.key || ''; changeScope() } }, { immediate: true })
const activeScope = computed(() => scope.value || groups.value[0]?.key || '')
const candidates = computed(() => props.rows.filter(row => averageScope(row) === activeScope.value && row.available !== false))
const zoneChoices = computed(() => candidates.value.some(row => australiaAverageZone(row)) ? [1, 2, 3, 4].map(number => {
  const keys = candidates.value.filter(row => australiaAverageZone(row) === number).map(quoteSheetRowKey)
  return { number, keys, checked: chosenZones.value.includes(number) }
}) : [])
function toggleZone(number: number, checked: boolean) {
  chosenZones.value = checked ? [...new Set([...chosenZones.value, number])] : chosenZones.value.filter(zone => zone !== number)
  const keys = new Set(zoneChoices.value.find(zone => zone.number === number)?.keys || [])
  chosen.value = checked ? [...new Set([...chosen.value, ...keys])] : chosen.value.filter(key => !keys.has(key))
  error.value = ''
}
const visibleCandidates = computed(() => candidates.value.filter(row => !australiaAverageZone(row) || chosenZones.value.includes(australiaAverageZone(row)!)))
const selected = computed(() => visibleCandidates.value.filter(row => chosen.value.includes(quoteSheetRowKey(row))))
function changeScope() { chosen.value = []; chosenZones.value = []; weights.value = {}; error.value = '' }
function distribute() {
  if (!selected.value.length) return
  const count = selected.value.length, cents = Math.floor(10000 / count)
  selected.value.forEach((row, i) => { weights.value[quoteSheetRowKey(row)] = ((cents + (i < 10000 % count ? 1 : 0)) / 100).toString() })
}
const total = computed(() => selected.value.reduce((sum, row) => sum.plus(Number(weights.value[quoteSheetRowKey(row)]) || 0), new Decimal(0)).toNumber())
const draft = computed(() => {
  try {
    if (!props.quantities.every(q => Number.isSafeInteger(q) && q > 0)) throw new Error('请先填写有效的数量列')
    const members = selected.value.map(row => ({ optionId: quoteSheetRowKey(row), weight: mode.value === 'equal' ? 1 : Number(weights.value[quoteSheetRowKey(row)]), sourcePrices: [...(props.system.rows.find(r => r.key === quoteSheetRowKey(row))?.prices ?? [])] }))
    const prices = averagePrices(members, mode.value, props.quantities.length)
    if (prices.some(p => p == null)) throw new Error('所选渠道存在缺价档位，请补齐系统报价或调整渠道选择')
    return { members, prices, error: '' }
  } catch (e) { return { members: [], prices: [], error: (e as Error).message } }
})
function editPlan(plan: AveragePlan) {
  const first = props.rows.find(row => quoteSheetRowKey(row) === plan.members[0]?.optionId)
  if (!first) { error.value = '来源渠道已移除，请移除方案后重新生成'; return }
  editingId.value = plan.id
  scope.value = averageScope(first)
  mode.value = plan.mode; display.value = plan.display; provider.value = plan.provider; shippingTime.value = plan.shippingTime
  chosen.value = plan.members.map(m => m.optionId); weights.value = Object.fromEntries(plan.members.map(m => [m.optionId, String(m.weight)])); error.value = ''
  chosenZones.value = [...new Set(props.rows.filter(row => chosen.value.includes(quoteSheetRowKey(row))).map(australiaAverageZone).filter((zone): zone is number => zone !== undefined))]
}
function newPlan() { editingId.value = ''; chosen.value = []; chosenZones.value = []; weights.value = {}; error.value = ''; provider.value = 'Combined Shipping'; shippingTime.value = '' }
watch(() => props.plans.map(plan => plan.id).join(','), () => { if (editingId.value && !props.plans.some(plan => plan.id === editingId.value)) newPlan() })
function memberLabel(member: AveragePlan['members'][number], plan: AveragePlan) {
  const row = props.rows.find(row => quoteSheetRowKey(row) === member.optionId)
  return (row ? (quoteSheetProviderName(row.carrier) || row.carrier) + ' · ' + [row.transport, row.quoteRegion].filter(Boolean).join(' · ') : '来源渠道已移除') + (plan.mode === 'weighted' ? ' ' + member.weight + '%' : '')
}
function remove(id: string) { if (editingId.value === id) newPlan(); emit('remove', id) }
function generate() {
  error.value = draft.value.error
  if (error.value || props.disabled) return
  if (!editingId.value && props.plans.length >= 20) { error.value = '每张报价单最多 20 个综合方案'; return }
  if (!provider.value.trim() || /[^\x20-\x7e]/.test(provider.value) || /[^\x20-\x7e]/.test(shippingTime.value)) { error.value = '方案名称和运输时效请填写英文'; return }
  const id = editingId.value || crypto.randomUUID()
  const old = props.plans.find(plan => plan.id === id)
  const sameCalculation = old?.mode === mode.value && old.members.length === draft.value.members.length && draft.value.members.every(member => {
    const previous = old.members.find(m => m.optionId === member.optionId)
    return previous && (mode.value === 'equal' || previous.weight === member.weight) && props.quantities.every((q, i) => previous.sourcePrices[old.quantities.indexOf(q)] === member.sourcePrices[i])
  })
  const prices = sameCalculation ? props.quantities.map(q => old!.prices[old!.quantities.indexOf(q)] ?? null) : [...draft.value.prices]
  emit('add', { id, mode: mode.value, display: display.value, provider: provider.value.trim(), shippingTime: shippingTime.value.trim(), quantities: [...props.quantities], members: draft.value.members, systemPrices: draft.value.prices, prices })
  editingId.value = id
}
</script>

<template>
  <fieldset class="average-panel" :disabled="disabled" aria-label="渠道平均报价设置">
    <div class="average-head"><strong>渠道平均报价</strong><select v-model="scope" aria-label="平均报价国家及区域" @change="changeScope"><option value="" disabled>选择国家与区域</option><option v-for="group in groups" :key="group.key" :value="group.key">{{ group.label }}</option></select><span v-if="!scope && groups.length" class="average-hint">当前：{{ groups[0].label }}</span><div class="average-tabs"><button type="button" :aria-pressed="mode === 'equal'" @click="mode = 'equal'">普通平均</button><button type="button" :aria-pressed="mode === 'weighted'" @click="mode = 'weighted'">加权平均</button></div><span v-if="mode === 'weighted'" class="average-total" :class="{ valid: total === 100 }">权重合计 {{ total }}%</span><button v-if="mode === 'weighted'" type="button" @click="distribute">平均分配</button></div>
    <fieldset v-if="zoneChoices.length" class="average-regions" aria-label="澳大利亚平均报价区域选择">
      <legend>选择参与平均的区域 <span>可多选</span></legend>
      <div class="average-region-options">
        <label v-for="zone in zoneChoices" :key="zone.number" :class="{ selected: zone.checked, unavailable: !zone.keys.length }">
          <input type="checkbox" :aria-label="`参与平均区域：${zone.number}区`" :checked="zone.checked" :disabled="!zone.keys.length" @change="toggleZone(zone.number, ($event.target as HTMLInputElement).checked)">
          <span><strong>{{ zone.number }}区</strong><small>{{ zone.keys.length ? `${zone.keys.length}条已加入渠道` : '未加入报价单' }}</small></span>
        </label>
      </div>
    </fieldset>
    <p v-if="zoneChoices.length && !chosenZones.length" class="average-hint">请先勾选上方区域，再选择参与平均的渠道。</p>
    <div class="average-channels"><div v-for="row in visibleCandidates" :key="quoteSheetRowKey(row)" class="average-channel"><label><input v-model="chosen" type="checkbox" :value="quoteSheetRowKey(row)" :aria-label="`参与平均：${sourceLabel(row)}`"><span>{{ quoteSheetProviderName(row.carrier) || row.carrier }}<small>{{ sourceLabel(row) }}</small></span></label><label v-if="mode === 'weighted' && chosen.includes(quoteSheetRowKey(row))"><input v-model="weights[quoteSheetRowKey(row)]" class="average-weight" type="number" min="0.01" max="100" step="0.01" :aria-label="`${sourceLabel(row)} 权重`">%</label></div></div>
    <div class="average-options"><strong>客户展示</strong><label><input v-model="display" type="radio" value="summary">仅综合报价</label><label><input v-model="display" type="radio" value="details">综合报价＋明细</label><label>方案名称<input v-model="provider" maxlength="80" aria-label="综合报价方案名称"></label><label>运输时效<input v-model="shippingTime" maxlength="80" placeholder="手动确认，例如 7-12 workingdays" aria-label="综合报价运输时效"></label><button class="average-generate" type="button" :disabled="!!draft.error || !!system.priceIssues?.length" @click="generate">{{ editingId ? '应用修改' : '生成平均行' }}</button><button v-if="editingId" type="button" @click="newPlan">新增方案</button></div>
    <p v-if="draft.error" role="status" class="average-hint">{{ draft.error }}</p><p v-else class="average-result">计算结果：<span v-for="(price, i) in draft.prices" :key="i">{{ quantities[i] }} 数量：${{ price!.toFixed(2) }} </span></p>
    <p v-if="error" role="alert">{{ error }}</p>
    <div v-for="plan in plans" :key="plan.id" class="average-saved"><span>{{ plan.provider }} · {{ plan.mode === 'equal' ? '普通平均' : '加权平均' }} · {{ plan.members.length }} 条渠道 · {{ plan.display === 'summary' ? '仅综合报价' : '综合报价＋明细' }}<small class="average-members">{{ plan.members.map(member => memberLabel(member, plan)).join('；') }}</small></span><div><button type="button" :aria-label="`编辑综合方案 ${plan.provider}`" @click="editPlan(plan)">编辑方案</button><button type="button" :aria-label="`移除综合方案 ${plan.provider}`" @click="remove(plan.id)">移除方案</button></div></div>
  </fieldset>
</template>

<style scoped>
.average-panel{margin:6px 0;padding:6px 8px;border:1px solid #f58220;border-radius:8px;background:#fff8f1;color:#26313b;font-size:12px}.average-head,.average-options,.average-channels{display:flex;align-items:center;gap:12px;flex-wrap:wrap}.average-head{padding-bottom:4px;border-bottom:1px solid #efdfce}.average-head strong{font-size:15px}.average-panel button,.average-panel select,.average-panel input:not([type=checkbox]):not([type=radio]){height:28px;border:1px solid #d6dce2;border-radius:5px;background:white;padding:0 10px;color:inherit;font:inherit}.average-panel button{cursor:pointer}.average-panel select{max-width:340px}.average-tabs{display:flex}.average-tabs button[aria-pressed=true],.average-panel .average-generate{background:#f58220;border-color:#f58220;color:white;font-weight:700}.average-total{margin-left:auto}.average-total.valid{color:#16843a;font-weight:700}.average-channels{padding:5px 0;gap:10px}.average-channel,.average-channel label,.average-options label{display:flex;align-items:center;gap:7px}.average-channel small{display:block;max-width:250px;font-size:10px;color:#6d7984;margin-top:3px}.average-panel .average-weight{width:72px}.average-panel input[type=checkbox],.average-panel input[type=radio]{accent-color:#f58220}.average-options{row-gap:6px}.average-options input:not([type=radio]){width:170px}.average-generate{margin-left:auto}.average-hint{font-size:11px;color:#73808a;line-height:1.6}.average-result{margin:5px 0;color:#b95800;display:flex;gap:16px;flex-wrap:wrap}.average-members{display:block;margin-top:3px;color:#73808a}.average-saved>div{display:flex;gap:8px}.average-saved{display:flex;justify-content:space-between;align-items:center;padding-top:5px;border-top:1px solid #efdfce;margin-top:5px}.average-panel button:disabled{opacity:.5;cursor:not-allowed}
.average-regions{margin:10px 0 2px;padding:8px 10px;border:1px solid #efd0ac;border-radius:8px;background:#fff}.average-regions legend{padding:0 6px;font-size:14px;font-weight:700}.average-regions legend span{margin-left:8px;color:#8a612f;font-size:12px;font-weight:400}.average-region-options{display:flex;flex-wrap:wrap;gap:12px}.average-region-options label{display:flex;align-items:center;gap:10px;min-width:128px;padding:8px 12px;border:1px solid #d6dce2;border-radius:7px;cursor:pointer}.average-region-options label.selected{border-color:#f58220;background:#fff3e4}.average-region-options label.unavailable{opacity:.5;cursor:not-allowed}.average-region-options input{width:17px;height:17px}.average-region-options strong{display:block;font-size:18px}.average-region-options small{display:block;margin-top:4px;color:#65717c;font-size:11px}.average-regions p{font-size:12px;line-height:1.6;color:#65717c;margin:10px 0 0}
</style>
