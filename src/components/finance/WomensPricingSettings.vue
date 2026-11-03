<script setup lang="ts">
import { computed, ref } from 'vue'
import { writeFinanceSetting } from '@/services/financeSettings'
import { defaultWomensPricing, loadWomensPricing, womensPricingError, womensPolicyLabel, WOMENS_PRICING_UPDATED, type WomensPricingPolicy } from '@/data/womensPricing'
const saved = ref(loadWomensPricing())
const draft = ref<WomensPricingPolicy>(saved.value ? JSON.parse(JSON.stringify(saved.value)) : defaultWomensPricing())
const saving = ref(false), preview = ref(false), message = ref('')
const error = computed(() => womensPricingError(draft.value))
function syncBounds() { draft.value.tiers.forEach((t, i) => { t.fromUsd = i ? draft.value.tiers[i - 1]!.toUsd! : 0 }); preview.value = false }
function splitLast() { const last = draft.value.tiers.at(-1)!; last.toUsd = last.fromUsd + 20; draft.value.tiers.push({ fromUsd: last.toUsd, toUsd: null, coefficient: last.coefficient }); preview.value = false }
function removeTier(i: number) { draft.value.tiers.splice(i, 1); draft.value.tiers.at(-1)!.toUsd = null; syncBounds() }
async function publish() {
  if (error.value || !preview.value || saving.value) return
  saving.value = true; message.value = ''
  try {
    const policy: WomensPricingPolicy = { schemaVersion: 2, basis: 'reference-price-usd', referenceCoefficient: draft.value.referenceCoefficient, enabled: draft.value.enabled, tiers: draft.value.tiers.map(t => ({ ...t })) }
    await writeFinanceSetting('womens-pricing', policy)
    saved.value = loadWomensPricing(); preview.value = false
    window.dispatchEvent(new Event(WOMENS_PRICING_UPDATED))
    message.value = '女装系数已发布。新报价使用新版本；历史报价保持原系数和金额。'
  } catch (e) { message.value = e instanceof Error ? e.message : '发布失败，请重试' }
  finally { saving.value = false }
}
</script>
<template>
  <section class="womens-settings">
    <header><div><h3>女装报价系数</h3><p>统一用于女装单品、女装组合 SKU；先用综合成本乘选档基准系数得到参考价，再匹配最终报价系数。</p></div><span class="version">{{ saved ? `已发布 V${saved.version + 1}` : '尚未发布' }}</span></header>
    <fieldset :disabled="saving" @input="preview=false" @change="preview=false">
      <label><input v-model="draft.enabled" type="checkbox"> 启用女装报价</label>
      <div class="reference-setting"><label>选档基准系数 <input v-model.number="draft.referenceCoefficient" aria-label="女装选档基准系数" type="number" min="0.000001" max="10" step="0.01"></label><p>默认1.23，仅用于确定金额档位；最终报价用原综合成本乘命中系数，不叠乘。</p></div>
      <table><thead><tr><th>参考价下限（含，USD）</th><th>参考价上限（不含，USD）</th><th>系数</th><th>操作</th></tr></thead><tbody>
        <tr v-for="(tier,index) in draft.tiers" :key="index"><td>{{ tier.fromUsd }}</td><td><input v-if="index<draft.tiers.length-1" v-model.number="tier.toUsd" :aria-label="`第${index+1}档上限`" type="number" step="0.01" min="0" @input="syncBounds"><span v-else>无上限</span></td><td><input v-model.number="tier.coefficient" :aria-label="`第${index+1}档系数`" type="number" step="0.01" min="0.000001" max="10"></td><td><button :disabled="draft.tiers.length===1" @click="removeTier(index)">删除档位</button></td></tr>
      </tbody></table>
      <button :disabled="draft.tiers.length>=12" @click="splitLast">＋ 增加档位</button>
    </fieldset>
    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <details class="rule-details"><summary>查看计算口径与联动说明</summary><p>参考价＝（商品采购成本＋国内运费＋国际运费）÷ 当次美元汇率 × 选档基准系数。按未取整参考价选档一次，再用原综合成本乘命中系数。每个渠道、每个件数／套数分别选档，替代 S–E 客户等级系数；税费、附加费、客户操作费、佣金阈值及取整沿用现有流程。</p><p>按整笔金额乘命中系数；跨档时基础售价可能下降。请确认金额区间及系数后发布。</p></details>
    <p class="history-note">发布后用于新报价和再次发起；历史报价保留原系数、金额和状态。</p>
    <div v-if="preview" class="publish-preview"><p>发布后：{{ draft.enabled ? womensPolicyLabel(draft) : '停用女装新报价，历史记录仍可查看' }}</p><p>已打开的女装报价需更新计价；历史报价、审核状态和成交状态保留原样。再次发起将按当前版本生成新报价。</p></div>
    <footer><span role="status">{{ message }}</span><button v-if="!preview" :disabled="!!error||saving" @click="preview=true">预览发布变更</button><button v-else :disabled="!!error||saving" @click="publish">{{ saving ? '正在发布…' : '发布女装系数' }}</button></footer>
  </section>
</template>
<style scoped>
.reference-setting{margin-top:16px;padding:14px;background:#f6f8fa;border-radius:7px}.reference-setting label{display:flex;align-items:center;gap:14px}.version{flex-shrink:0;padding:6px 10px;border-radius:6px;background:#edf7ef;color:#32834a;font-size:12px}.rule-details{margin:16px 0;padding:12px 14px;background:#f6f8fa;border-radius:7px}.rule-details summary{cursor:pointer;font-size:12px;color:#536571}.womens-settings .history-note{margin:14px 0}.womens-settings footer{padding-top:14px;border-top:1px solid #e3e8ec}
.womens-settings{padding:22px;color:#26323d}.womens-settings header,.womens-settings footer{display:flex;justify-content:space-between;gap:16px;align-items:center}.womens-settings h3{margin:0;font-size:18px}.womens-settings p{font-size:12px;line-height:1.8;color:#677782}.womens-settings fieldset{border:0;padding:0;margin:18px 0}.womens-settings table{width:100%;border-collapse:collapse;margin:14px 0}.womens-settings th,.womens-settings td{text-align:left;padding:12px;border-bottom:1px solid #e3e8ec}.womens-settings input[type=number]{width:140px;max-width:100%;padding:9px;border:1px solid #dbe1e6;border-radius:6px}.womens-settings button{padding:9px 15px;background:#fff;border:1px solid #dbe1e6;border-radius:6px;color:#34434e}.womens-settings button:disabled{opacity:.5}.womens-settings footer button{background:#ff9700;border-color:#ff9700;color:#17232d;font-weight:700}.publish-preview{padding:12px 18px;background:#fff4e4;border-radius:8px}.womens-settings .error{color:#bd352a}@media(max-width:600px){.womens-settings{padding:12px}.womens-settings th,.womens-settings td{padding:8px 4px;font-size:12px}}
</style>
