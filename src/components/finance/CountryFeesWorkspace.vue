<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { channelsAvailableForCountry, type FinanceCountrySetting, type FinanceLogisticsChannelOption } from '@/data/financeChannelPolicies'
import { calculateFinanceQuoteTax, normalizeFinanceTaxSettings, saveFinanceTaxSettings, type FinanceTaxSettings, type LogisticsTaxMode } from '@/data/financeTaxSettings'

const props = defineProps<{ settings: FinanceTaxSettings; countries: FinanceCountrySetting[]; exchangeRate: number }>()
const emit = defineEmits<{ saved: [settings: FinanceTaxSettings] }>()
const clone = (value: FinanceTaxSettings) => normalizeFinanceTaxSettings(JSON.parse(JSON.stringify(value)))
const draft = ref(clone(props.settings))
const selectedCountry = ref(draft.value.countries.find(item => item.selected)?.country || '')
const countrySearch = ref('')
const channelSearch = ref('')
const providerFilter = ref('')
const addOpen = ref(false)
const addCountry = ref('')
const page = ref(1)
const pageSize = ref(10)
const saving = ref(false)
const message = ref('')
const failed = ref(false)
const previewKey = ref('')
const previewBase = ref(20)
const countryMeta = computed(() => new Map(props.countries.map(item => [item.country, item])))
const matchesCountry = (country: string) => `${country} ${countryMeta.value.get(country)?.code || ''}`.toLowerCase().includes(countrySearch.value.trim().toLowerCase())
const visibleCountries = computed(() => draft.value.countries.filter(item => item.selected && matchesCountry(item.country)))
const availableCountries = computed(() => draft.value.countries.filter(item => !item.selected && matchesCountry(item.country)))
const current = computed(() => draft.value.countries.find(item => item.selected && item.country === selectedCountry.value))
const channels = computed(() => current.value ? channelsAvailableForCountry(current.value.country) : [])
const providers = computed(() => [...new Set(channels.value.map(item => item.carrier))])
const filteredChannels = computed(() => channels.value.filter(item =>
  (!providerFilter.value || item.carrier === providerFilter.value)
  && `${item.carrier} ${item.channel} ${item.channelCode}`.toLowerCase().includes(channelSearch.value.trim().toLowerCase())))
const pageCount = computed(() => Math.max(1, Math.ceil(filteredChannels.value.length / pageSize.value)))
const pageRows = computed(() => filteredChannels.value.slice((page.value - 1) * pageSize.value, page.value * pageSize.value))
const previewChannel = computed(() => channels.value.find(item => item.key === previewKey.value) || filteredChannels.value[0])
const preview = computed(() => current.value && previewChannel.value
  ? calculateFinanceQuoteTax(draft.value, current.value.country, previewChannel.value.carrier, previewBase.value, previewChannel.value.key) : null)
const obsoleteCount = computed(() => {
  const keys = new Set(channels.value.map(item => item.key))
  return draft.value.channelFees?.filter(item => item.country === current.value?.country && !keys.has(item.channelKey)).length || 0
})
watch(() => props.settings, value => { draft.value = clone(value) })
watch([selectedCountry, channelSearch, providerFilter, pageSize], () => { page.value = 1 })
watch(selectedCountry, () => { providerFilter.value = ''; channelSearch.value = ''; previewKey.value = '' })
watch(pageCount, count => { page.value = Math.min(page.value, count) })
function add() {
  const item = draft.value.countries.find(row => row.country === addCountry.value)
  if (!item) return
  item.selected = true
  item.taxConfigured = true
  item.surchargeFeeUsd ??= 0
  item.surchargeEnabled ??= false
  selectedCountry.value = item.country
  countrySearch.value = ''
  addOpen.value = false
  addCountry.value = ''
}
function changeEnabled(kind: 'tax' | 'surcharge', event: Event) {
  if (!current.value) return
  const enabled = (event.target as HTMLInputElement).checked
  if (kind === 'tax') { current.value.taxConfigured = true; current.value.enabled = enabled }
  else current.value.surchargeEnabled = enabled
}
function editTaxAmount(event: Event) {
  if (!current.value) return
  const input = event.target as HTMLInputElement
  current.value.fixedFeeUsd = input.value === '' ? Number.NaN : Number(input.value)
  current.value.taxConfigured = true
}
function override(channel: FinanceLogisticsChannelOption) {
  return draft.value.channelFees?.find(item => item.country === selectedCountry.value && item.channelKey === channel.key)
}
function effectiveMode(channel: FinanceLogisticsChannelOption, kind: 'taxMode' | 'surchargeMode') {
  const explicit = override(channel)?.[kind]
  if (explicit) return explicit
  if (kind === 'surchargeMode') return 'taxable'
  return draft.value.providers.find(item => item.selected && item.provider === channel.carrier)?.mode
}
function setMode(channel: FinanceLogisticsChannelOption, kind: 'taxMode' | 'surchargeMode', mode: LogisticsTaxMode) {
  draft.value.channelFees ??= []
  let row = override(channel)
  if (!row) { row = { country: selectedCountry.value, channelKey: channel.key }; draft.value.channelFees.push(row) }
  row[kind] = mode
}
function toggle(channel: FinanceLogisticsChannelOption, kind: 'taxMode' | 'surchargeMode') {
  setMode(channel, kind, effectiveMode(channel, kind) === 'exempt' ? 'taxable' : 'exempt')
}
function resetChannel(channel: FinanceLogisticsChannelOption) {
  draft.value.channelFees = draft.value.channelFees?.filter(item => !(item.country === selectedCountry.value && item.channelKey === channel.key))
}
function reset() {
  draft.value = clone(props.settings)
  if (!draft.value.countries.some(item => item.selected && item.country === selectedCountry.value))
    selectedCountry.value = draft.value.countries.find(item => item.selected)?.country || ''
  message.value = '已恢复为上次保存的设置'
  failed.value = false
}
async function save() {
  saving.value = true
  message.value = ''
  try {
    const saved = await saveFinanceTaxSettings(draft.value)
    draft.value = clone(saved)
    emit('saved', saved)
    failed.value = false
    message.value = '税费与附加费已保存，后续报价按新设置计算'
  } catch (error) {
    failed.value = true
    message.value = error instanceof Error ? error.message : '保存失败，请重试'
  } finally { saving.value = false }
}
const money = (value: number | undefined) => Number.isFinite(value) ? Number(value).toFixed(2) : '—'
</script>

<template>
  <section class="country-fees">
    <header class="workspace-heading">
      <div><h2>国家税费与附加费</h2><p>按国家设置金额，按物流渠道独立设置豁免</p></div>
      <div class="actions"><small>最近保存：{{ settings.updatedAt }}</small><button :disabled="saving" @click="reset">取消修改</button><button class="primary" :disabled="saving" @click="save">{{ saving ? '正在保存…' : '保存设置' }}</button></div>
    </header>
    <p v-if="message" class="message" :class="{ error: failed }" role="status">{{ message }}</p>
    <fieldset :disabled="saving">
      <div class="workspace-grid">
        <aside class="country-panel">
          <div class="panel-heading"><h3>国家</h3><button @click="addOpen = !addOpen">＋ 添加国家</button></div>
          <input v-model="countrySearch" aria-label="搜索国家" placeholder="搜索国家或代码">
          <div v-if="addOpen" class="add-country">
            <select v-model="addCountry" aria-label="选择新增国家"><option value="" disabled>选择国家</option><option v-for="item in availableCountries" :key="item.country" :value="item.country">{{ item.country }} {{ countryMeta.get(item.country)?.code }}</option></select>
            <button class="primary" :disabled="!addCountry" @click="add">添加</button>
          </div>
          <div class="country-list">
            <button v-for="item in visibleCountries" :key="item.country" :class="{ active: selectedCountry === item.country }" :aria-pressed="selectedCountry === item.country" @click="selectedCountry = item.country"><b>{{ item.country }}</b><span>{{ countryMeta.get(item.country)?.code }}</span></button>
            <p v-if="!visibleCountries.length" class="empty">暂无匹配国家，可搜索并添加国家</p>
          </div>
        </aside>
        <main v-if="current" class="detail-panel">
          <h3 class="country-title">{{ current.country }} <small>{{ countryMeta.get(current.country)?.code }}</small></h3>
          <div class="fee-cards">
            <section class="fee-card">
              <header><h3>国家关税</h3><label class="enable"><input type="checkbox" :checked="current.enabled" @change="changeEnabled('tax', $event)">启用</label></header>
              <label class="amount"><span>$</span><input :value="current.fixedFeeUsd" aria-label="国家关税金额" type="number" min="0" max="1000000" step="0.01" @input="editTaxAmount"><span>USD / 单</span></label>
              <small>≈ ¥{{ money(current.fixedFeeUsd * exchangeRate) }} · {{ current.enabled ? '按整单收取一次' : '未启用，不收取关税' }}</small>
            </section>
            <section class="fee-card">
              <header><h3>国家附加费</h3><label class="enable"><input type="checkbox" :checked="current.surchargeEnabled" @change="changeEnabled('surcharge', $event)">启用</label></header>
              <label class="amount"><span>$</span><input v-model.number="current.surchargeFeeUsd" aria-label="国家附加费金额" type="number" min="0" max="1000000" step="0.01" placeholder="0.00"><span>USD / 单</span></label>
              <small>≈ ¥{{ money((current.surchargeFeeUsd || 0) * exchangeRate) }} · {{ current.surchargeEnabled ? '按整单收取一次' : '已关闭，不收取附加费' }}</small>
            </section>
          </div>
          <p class="hint">每项费用按整单计入一次，分别判断豁免。国家附加费与物流价格表中的附加费分别核算。</p>
          <section class="channel-panel">
            <header class="panel-heading"><div><h3>渠道豁免设置</h3><small>当前国家：{{ current.country }}</small></div><div class="filters"><input v-model="channelSearch" aria-label="搜索物流商或渠道" placeholder="搜索物流商或渠道"><select v-model="providerFilter" aria-label="筛选物流商"><option value="">全部物流商</option><option v-for="provider in providers" :key="provider">{{ provider }}</option></select></div></header>
            <div class="table-scroll"><table>
              <thead><tr><th>物流商</th><th>物流渠道</th><th>免关税</th><th>免附加费</th><th>操作</th></tr></thead>
              <tbody><tr v-for="channel in pageRows" :key="channel.key">
                <td>{{ channel.carrier }}</td><td><b>{{ channel.channel }}</b><small>{{ channel.channelCode }}</small></td>
                <td><button class="fee-switch" role="switch" :aria-label="channel.channel + '免关税'" :aria-checked="effectiveMode(channel, 'taxMode') === 'exempt'" :class="{ on: effectiveMode(channel, 'taxMode') === 'exempt' }" @click="toggle(channel, 'taxMode')"><i></i>{{ effectiveMode(channel, 'taxMode') === 'exempt' ? '免收' : effectiveMode(channel, 'taxMode') ? '收取' : '待设置' }}</button><small>{{ override(channel)?.taxMode ? '本国渠道设置' : '继承物流商默认' }}</small><button v-if="!effectiveMode(channel, 'taxMode')" class="text-button" @click="setMode(channel, 'taxMode', 'taxable')">设为收取</button></td>
                <td><button class="fee-switch" role="switch" :aria-label="channel.channel + '免附加费'" :aria-checked="effectiveMode(channel, 'surchargeMode') === 'exempt'" :class="{ on: effectiveMode(channel, 'surchargeMode') === 'exempt' }" @click="toggle(channel, 'surchargeMode')"><i></i>{{ effectiveMode(channel, 'surchargeMode') === 'exempt' ? '免收' : '收取' }}</button><small v-if="!current.surchargeEnabled">国家附加费未启用</small></td>
                <td><button class="text-button" @click="previewKey = channel.key">试算</button><button v-if="override(channel)" class="text-button" @click="resetChannel(channel)">恢复默认</button></td>
              </tr></tbody>
            </table></div>
            <p v-if="!pageRows.length" class="empty">没有匹配的正式物流渠道</p>
            <footer><span>共 {{ filteredChannels.length }} 条 · 开启表示免收，仅对当前国家和渠道生效</span><div class="actions"><select v-model.number="pageSize" aria-label="每页条数"><option :value="10">每页 10 条</option><option :value="30">每页 30 条</option><option :value="50">每页 50 条</option></select><button :disabled="page <= 1" @click="page--">上一页</button><span>{{ page }} / {{ pageCount }}</span><button :disabled="page >= pageCount" @click="page++">下一页</button></div></footer>
          </section>
          <p v-if="obsoleteCount" class="hint">{{ obsoleteCount }} 条旧渠道豁免设置暂不可用，已保留；不会自动套用到同名新渠道。</p>
          <details class="defaults"><summary>物流商默认关税规则（全局）</summary><p class="hint">未单独设置的国家和渠道继承这里的规则，修改会影响该物流商的所有继承渠道。</p><div v-for="provider in draft.providers" :key="provider.provider" class="default-row"><b>{{ provider.provider }}</b><select :value="provider.selected ? provider.mode : ''" :aria-label="provider.provider + '默认关税规则'" @change="provider.selected = !!($event.target as HTMLSelectElement).value; provider.mode = (($event.target as HTMLSelectElement).value || 'taxable') as LogisticsTaxMode"><option value="">待设置</option><option value="exempt">免关税</option><option value="taxable">收取关税</option></select></div></details>
        </main>
        <p v-else class="empty">添加或选择一个国家，开始设置税费和渠道豁免</p>
      </div>
      <section v-if="preview && previewChannel" class="preview">
        <div><h3>报价试算</h3><small>{{ current?.country }} · {{ previewChannel.carrier }} · {{ previewChannel.channel }}</small></div>
        <label>基础报价（USD）<input v-model.number="previewBase" type="number" min="0" step="0.01" aria-label="试算基础报价"></label><span>＋</span>
        <div><small>国家关税</small><b>${{ money(preview.taxUsd) }}</b><small>{{ preview.label }}</small></div><span>＋</span>
        <div><small>国家附加费</small><b>${{ money(preview.surchargeUsd) }}</b><small>{{ !preview.surchargeEnabled ? '未启用' : preview.surchargeExempt ? '已豁免' : '按单收取' }}</small></div><span>＝</span>
        <div><small>{{ preview.configured ? '最终报价' : '试算未完成' }}</small><strong>{{ preview.configured ? '$' + money(preview.totalUsd) : '请补齐关税设置' }}</strong></div>
      </section>
    </fieldset>
  </section>
</template>

<style scoped>
.country-fees{color:#20344c;background:#f7f9fc;border:1px solid #dce4ec;border-radius:12px;padding:24px}.workspace-heading,.actions,.panel-heading,.fee-card header,.filters,.channel-panel footer{display:flex;align-items:center;justify-content:space-between;gap:12px}.workspace-heading{margin-bottom:22px;flex-wrap:wrap}h2,h3,p{margin:0}h2{font-size:23px}h3{font-size:16px}.workspace-heading p,.hint,small{color:#6c7c90;font-size:12px}.workspace-heading p{margin-top:7px}.country-fees button,.country-fees input,.country-fees select{font:inherit;border:1px solid #d8e1e9;border-radius:6px;padding:9px 12px;background:white;color:inherit;box-sizing:border-box}.country-fees button{cursor:pointer}.country-fees button:disabled{opacity:.5;cursor:default}.country-fees button.primary{background:#00939f;color:white;border-color:#00939f}.country-fees input:focus,.country-fees select:focus,.country-fees button:focus-visible{outline:2px solid #00939f;outline-offset:2px}.country-fees fieldset{border:0;padding:0;margin:0;min-width:0}.workspace-grid{display:grid;grid-template-columns:220px minmax(0,1fr);gap:20px}.country-panel,.detail-panel{background:white;border:1px solid #dce4ec;border-radius:9px;padding:16px}.country-panel>.panel-heading{margin-bottom:14px}.country-panel>input{width:100%}.country-list{margin-top:12px;max-height:670px;overflow:auto}.country-list button{display:flex;justify-content:space-between;width:100%;padding:18px 12px;border-color:transparent;border-bottom-color:#edf0f5;border-radius:0}.country-list button.active{background:#edfafb;border-color:#00939f;border-radius:6px;color:#007d89}.country-list span{color:#7b8b9c}.add-country{display:flex;gap:6px;margin-top:8px}.add-country select{min-width:0;flex:1}.country-title{font-size:20px;margin:4px 0 20px}.country-title small{margin-left:10px}.fee-cards{display:grid;grid-template-columns:1fr 1fr;gap:18px}.fee-card{padding:18px;border:1px solid #dce4ec;border-radius:8px}.fee-card header{margin-bottom:20px}.enable{display:flex;align-items:center;gap:5px;font-size:12px}.country-fees input[type=checkbox]{accent-color:#00939f}.amount{display:flex;align-items:center;border:1px solid #d8e1e9;border-radius:6px;padding:0 10px;gap:7px;margin-bottom:9px;white-space:nowrap}.amount span:last-child{font-size:12px;color:#6c7c90}.amount input{width:100%;min-width:0;border:0;padding:12px 0;font-weight:600}.hint{margin:14px 0;line-height:1.6}.channel-panel{border:1px solid #dce4ec;border-radius:8px;padding:14px}.channel-panel .panel-heading{margin-bottom:14px;flex-wrap:wrap}.panel-heading h3{margin-bottom:4px}.filters input{width:200px}.table-scroll{overflow:auto}table{width:100%;border-collapse:collapse;text-align:left;font-size:12px}th{padding:12px 10px;background:#f5f7fb;font-weight:500}td{padding:12px 10px;border-bottom:1px solid #e7edf2}td small{display:block;margin-top:5px;font-size:10px}.country-fees .fee-switch{display:inline-flex;align-items:center;gap:7px;padding:0;border:0;background:transparent;font-size:12px;white-space:nowrap}.fee-switch i{display:block;width:34px;height:19px;border-radius:20px;background:#b6bfcb;position:relative}.fee-switch i:after{content:'';position:absolute;left:2px;top:2px;background:white;border-radius:50%;width:15px;height:15px}.fee-switch.on i{background:#00939f}.fee-switch.on i:after{left:17px}.country-fees .text-button{border:0;color:#008a96;padding:4px;font-size:11px;background:transparent}.channel-panel footer{margin-top:14px;flex-wrap:wrap;font-size:11px;color:#6c7c90}.channel-panel footer button,.channel-panel footer select{padding:6px}.empty{padding:28px 12px;color:#718298;font-size:13px;line-height:1.7}.preview{display:flex;align-items:center;justify-content:space-between;gap:14px;background:#eefafb;border:1px solid #a4d9df;border-radius:9px;padding:20px;margin-top:18px;flex-wrap:wrap}.preview small,.preview b,.preview strong{display:block}.preview h3{color:#00818d;margin-bottom:8px}.preview b,.preview strong{font-size:22px;color:#00818d;margin:6px 0}.preview label{font-size:12px;display:flex;flex-direction:column;gap:7px}.preview input{width:110px}.message{padding:12px;margin-bottom:15px;background:#eaf8f5;color:#007e6a;border-radius:6px}.message.error{color:#b83832;background:#fff0ee}.defaults{margin-top:18px}.defaults summary{cursor:pointer;font-size:12px;color:#5d7189}.default-row{display:flex;justify-content:space-between;gap:15px;padding:8px 0;font-size:12px;border-bottom:1px solid #edf0f4}
@media(max-width:1100px){.workspace-grid{grid-template-columns:170px minmax(0,1fr)}.country-fees{padding:14px}.filters{flex-wrap:wrap}.fee-card{padding:12px}.fee-cards{gap:10px}.actions{flex-wrap:wrap}}@media(max-width:760px){.workspace-grid,.fee-cards{grid-template-columns:1fr}.country-list{max-height:180px}.preview{align-items:flex-start}}
</style>
