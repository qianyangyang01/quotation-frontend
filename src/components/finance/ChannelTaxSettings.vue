<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { channelsAvailableForCountry } from '@/data/financeChannelPolicies'
import { EU_MEMBER_STATES, EU_TAX_GROUP, isEuCountry } from '@/data/europeanUnion'
import { financeCountryIdentity, matchesFinanceCountry } from '@/data/financeCountrySearch'
import { calculateFinanceQuoteTax, type FinanceTaxSettings } from '@/data/financeTaxSettings'
import { channelTaxAmount, taxCountryKey, validateChannelTaxRule, type ChannelTaxRule } from '@/data/channelTaxRules'
import { matchesEuYunExpressTax } from '@/data/euYunExpressTax'
import { importChannelTaxWorkbook } from '@/data/channelTaxWorkbook'
import { decimal } from '@/services/quotationDecimal'
import { api } from '@/services/http'
import type { logisticsChannels } from '@/data/logistics'
const props = defineProps<{ modelValue: FinanceTaxSettings; exchange: { usdCny: number; eurUsd?: number }; saving: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [FinanceTaxSettings]; save: [] }>()
const country = ref(''), query = ref(''), countrySearch = ref(''), provider = ref(''), size = ref(10)
const expandedProviders = ref<string[]>([]), providerPages = ref<Record<string, number>>({})
const selected = ref<string[]>([]), editing = ref(false), message = ref(''), error = ref(''), importing = ref(false)
const draft = ref<Omit<ChannelTaxRule, 'key'>>({ mode: 'fixed-order', currency: 'USD', amount: 0.3, perKg: 1.5 })
const amountInput = ref('0.30'), amountEdited = ref(false)
function inputAmount(event: Event) {
  amountInput.value = (event.target as HTMLInputElement).value
  draft.value.amount = amountInput.value.trim() ? Number(amountInput.value) : NaN
  amountEdited.value = true
}
function finishAmount() {
  if (amountEdited.value && Number.isFinite(draft.value.amount) && draft.value.amount >= 0 && draft.value.amount <= 1_000_000) {
    draft.value.amount = decimal(draft.value.amount).toDecimalPlaces(2).toNumber()
    amountInput.value = draft.value.amount.toFixed(2)
  }
}
const importInput = ref<HTMLInputElement>(), countrySearchInput = ref<HTMLInputElement>()
const browsingCountries = ref(false), countryResultLimit = ref(10)
const label = (value: string) => value === EU_TAX_GROUP ? '欧盟（27国）' : value
const countries = computed(() => props.modelValue.countries.filter(row => row.selected).filter(row => matchesFinanceCountry(row.country, countrySearch.value)))
const activeCountry = computed(() => props.modelValue.countries.find(row => row.country === country.value))
const addingHandling = computed(() => isEuCountry(country.value) && activeCountry.value?.euTaxMode === 'add-handling')
function changeEuMode(event: Event) {
  const euTaxMode = (event.target as HTMLSelectElement).value as 'override' | 'add-handling'
  update({ ...props.modelValue, countries: props.modelValue.countries.map(row => row.country === country.value ? { ...row, euTaxMode } : row) })
  selected.value = []; editing.value = false; error.value = ''
  message.value = '计费方式已更新，点击“保存并发布”生效。'
}
const available = computed(() => {
  const selectedKeys = new Set(props.modelValue.countries.filter(row => row.selected).map(row => financeCountryIdentity(row.country)))
  const seen = new Set<string>()
  return props.modelValue.countries.filter(row => {
    const key = financeCountryIdentity(row.country)
    if (row.selected || selectedKeys.has(key) || seen.has(key)) return false
    seen.add(key)
    return true
  })
})
const availableMatches = computed(() => available.value.filter(row => matchesFinanceCountry(row.country, countrySearch.value)))
const showAvailable = computed(() => browsingCountries.value || !!countrySearch.value.trim())
const euConfigured = computed(() => props.modelValue.countries.some(row => row.country === EU_TAX_GROUP && row.selected))
watch(countrySearch, () => { countryResultLimit.value = 10 })
function browseCountries() {
  browsingCountries.value = !browsingCountries.value
  countryResultLimit.value = 10
  if (browsingCountries.value) countrySearchInput.value?.focus()
}
watch(() => props.modelValue.countries, rows => { if (!rows.some(row => row.country === country.value && row.selected)) country.value = rows.find(row => row.selected)?.country || '' }, { immediate: true })
const allRows = computed(() => {
  const names = country.value === EU_TAX_GROUP ? EU_MEMBER_STATES.flatMap(row => [row[0], row[1]]) : [...new Set([country.value, taxCountryKey(country.value)])]
  return [...new Map(names.flatMap(name => channelsAvailableForCountry(name)).map(row => [row.key, row])).values()]
})
const filtered = computed(() => allRows.value.filter(row => (!provider.value || row.carrier === provider.value) && `${row.carrier} ${row.channel}`.toLowerCase().includes(query.value.trim().toLowerCase())))
const providers = computed(() => [...new Set(allRows.value.map(row => row.carrier))])
const groups = computed(() => providers.value.flatMap(name => {
  const rows = filtered.value.filter(row => row.carrier === name)
  return rows.length ? [{ name, rows, selectedCount: rows.filter(row => selected.value.includes(row.key)).length }] : []
}))
type ProviderGroup = typeof groups.value[number]
function groupPages(group: ProviderGroup) { return Math.max(1, Math.ceil(group.rows.length / size.value)) }
function groupPage(group: ProviderGroup) { return Math.min(providerPages.value[group.name] || 1, groupPages(group)) }
function visibleRows(group: ProviderGroup) { return group.rows.slice((groupPage(group) - 1) * size.value, groupPage(group) * size.value) }
function toggleProvider(name: string) { expandedProviders.value = expandedProviders.value.includes(name) ? expandedProviders.value.filter(value => value !== name) : [...expandedProviders.value, name] }
function toggleGroup(group: ProviderGroup) {
  const keys = new Set(group.rows.map(row => row.key))
  selected.value = group.selectedCount === group.rows.length ? selected.value.filter(key => !keys.has(key)) : [...new Set([...selected.value, ...keys])]
}
watch(country, () => { selected.value = []; editing.value = false; query.value = ''; provider.value = ''; error.value = ''; message.value = '' })
watch([country, query, provider, () => providers.value.join('\0')], () => {
  providerPages.value = {}
  expandedProviders.value = (query.value.trim() || provider.value ? groups.value : groups.value.slice(0, 1)).map(group => group.name)
}, { immediate: true })
watch(size, () => { providerPages.value = {} })
function update(value: FinanceTaxSettings) { emit('update:modelValue', value) }
function add(name: string) {
  if (props.saving || !available.value.some(row => row.country === name)) return
  update({ ...props.modelValue, countries: props.modelValue.countries.map(row => row.country === name ? { ...row, selected: true } : row) })
  country.value = name; countrySearch.value = ''; browsingCountries.value = false
}
function ruleFor(key: string) { return (addingHandling.value ? activeCountry.value?.handlingRules : activeCountry.value?.channelRules)?.find(row => row.key === key) }
function isChc(key: string, carrier: string) { return matchesEuYunExpressTax(country.value === EU_TAX_GROUP ? 'DE' : country.value, carrier, key) }
function view(row: typeof allRows.value[number]) {
  const rule = ruleFor(row.key)
  const displaySettings = addingHandling.value ? props.modelValue : { ...props.modelValue, countries: props.modelValue.countries.filter(item => item.country === country.value) }
  const result = calculateFinanceQuoteTax(displaySettings, country.value === EU_TAX_GROUP ? 'DE' : country.value, row.carrier, 0, { channelKey: row.key, ...props.exchange, weightKg: 1 })
  if (addingHandling.value) {
    const snapshot = result.calculation?.rule === 'eu-handling-v1' ? result.calculation : undefined
    return { mode: result.configured ? 'handling' : 'missing', name: '欧盟关税＋处理费',
      amount: rule?.mode === 'weight' ? `处理费 ${rule.perKg} × 整单 kg + ${rule.amount} ${rule.currency}` : rule?.mode === 'fixed-order' ? `处理费 ${rule.amount.toFixed(2)} ${rule.currency} / 单` : rule?.mode === 'unavailable' ? '该目的地不支持' : '不加处理费',
      usd: snapshot ? `欧盟 $${snapshot.euTaxUsd.toFixed(2)} + 处理费 $${snapshot.handlingFeeUsd.toFixed(2)} = $${snapshot.taxUsd.toFixed(2)}${result.feeMode === 'weight-order' ? '（1kg 示例）' : '/单'}` : result.label }
  }
  const mode = rule?.mode || (isChc(row.key, row.carrier) ? 'weight' : result.feeMode)
  const labels: Record<string, string> = { 'fixed-order': '固定金额', weight: '按重量', 'weight-eur': '按重量', exempt: '已含税', 'no-tax': '无', unavailable: '不发', missing: '待设置' }
  return { mode, name: labels[mode] || '待设置', amount: rule ? rule.mode === 'weight' ? `${rule.perKg} × 计费重 kg + ${rule.amount} ${rule.currency}` : rule.mode === 'fixed-order' ? `${rule.amount.toFixed(2)} ${rule.currency} / 单` : rule.mode === 'unavailable' ? '该目的地不支持' : '不额外加收' : isChc(row.key,row.carrier) ? '1.5 × 计费重 kg + 0.6 EUR' : result.configured ? `${result.fixedFeeUsd} USD / 单` : '待财务设置', usd: mode === 'weight' ? '随重量计算' : mode === 'unavailable' ? '—' : result.configured ? `$${result.taxUsd.toFixed(2)}` : '—' }
}
function toggle(key: string) { selected.value = selected.value.includes(key) ? selected.value.filter(value => value !== key) : [...selected.value, key] }
function edit(key?: string) {
  if (addingHandling.value) draft.value = { mode: 'fixed-order', amount: NaN, currency: 'USD', perKg: 0 }
  if (key) {
    selected.value = [key]
    const rule = ruleFor(key), row = allRows.value.find(item => item.key === key)!
    draft.value = rule ? { ...rule } : addingHandling.value ? { mode: 'fixed-order', amount: NaN, currency: 'USD', perKg: 0 } : isChc(key, row.carrier) ? { mode: 'weight', amount: 0.6, currency: 'EUR', perKg: 1.5 } : { mode: 'fixed-order', amount: 0.3, currency: 'USD', perKg: 0 }
  }
  if (selected.value.length) {
    amountInput.value = Number.isFinite(draft.value.amount) ? draft.value.amount.toFixed(2) : ''
    amountEdited.value = false
    editing.value = true; error.value = ''
  }
}
const preview = computed(() => { try { return `$${channelTaxAmount({ ...draft.value, key: '1::预览::渠道' }, { ...props.exchange, weightKg: 1 }).toFixed(2)} / 单` } catch (e) { return e instanceof Error ? e.message : '请填写费用' } })
function apply() {
  try {
    finishAmount()
    if (addingHandling.value && ['no-tax', 'exempt', 'unavailable'].includes(draft.value.mode)) { draft.value.amount = 0; draft.value.perKg = 0 }
    const keys = new Set(selected.value); let kept = 0
    const rules = new Map(((addingHandling.value ? activeCountry.value?.handlingRules : activeCountry.value?.channelRules) || []).map(row => [row.key, row]))
    for (const row of allRows.value.filter(row => keys.has(row.key))) {
      const keepChc = !addingHandling.value && isChc(row.key, row.carrier)
      const rule = keepChc ? { key: row.key, mode: 'weight' as const, currency: 'EUR' as const, amount: 0.6, perKg: 1.5 } : { ...draft.value, key: row.key }
      if (keepChc) kept++
      validateChannelTaxRule(rule); rules.set(row.key, rule)
    }
    update({ ...props.modelValue, countries: props.modelValue.countries.map(row => row.country === country.value ? { ...row, enabled: true, selected: true, ...(addingHandling.value ? { handlingRules: [...rules.values()] } : { channelRules: [...rules.values()] }) } : row) })
    message.value = `已应用 ${keys.size} 个渠道，点击“保存并发布”生效${kept ? `；其中 ${kept} 条 CHC 保留欧元重量规则` : ''}`
    editing.value = false; error.value = ''
  } catch (e) { error.value = e instanceof Error ? e.message : '设置失败' }
}
async function importFile(event: Event) {
  const input = event.target as HTMLInputElement, file = input.files?.[0]
  if (!file) return
  importing.value = true; error.value = ''
  try {
    const catalog = await api.get<typeof logisticsChannels>('/finance-settings/tax-channels')
    const result = await importChannelTaxWorkbook(file, props.modelValue, catalog)
    update(result.settings); message.value = result.message
  } catch (e) { error.value = e instanceof Error ? e.message : '导入失败' }
  finally { importing.value = false; input.value = '' }
}
</script>

<template>
  <section class="channel-tax-workspace" aria-label="渠道税费设置">
    <header class="workspace-title"><div><h2>渠道税费设置</h2><p>按国家、物流商分组维护，支持整组勾选与单独调整</p></div><div class="actions"><input ref="importInput" hidden type="file" accept=".xlsx" @change="importFile"><button :disabled="saving || importing" @click="importInput?.click()">{{ importing ? '正在读取…' : '导入表格' }}</button><button class="primary" :disabled="saving || importing" @click="emit('save')">{{ saving ? '保存中…' : '保存并发布' }}</button></div></header>
    <p v-if="error" role="alert" class="error">{{ error }}</p><p v-if="message" role="status" class="notice">{{ message }}</p>
    <div class="layout" :class="{ editing }">
      <aside class="countries">
        <h3>国家 / 地区</h3>
        <div class="country-search"><input ref="countrySearchInput" v-model="countrySearch" placeholder="中文 / 英文 / 国家代码" aria-label="搜索税费国家" aria-describedby="country-search-hint"><button v-if="countrySearch" aria-label="清空国家搜索" @click="countrySearch = ''; countrySearchInput?.focus()">×</button></div>
        <p id="country-search-hint" class="country-hint">搜索全部国家，支持直接添加</p>
        <p v-if="countries.length || !countrySearch.trim()" class="country-section-label">已添加<span>{{ countries.length }}</span></p>
        <nav v-if="countries.length" aria-label="已添加税费国家"><button v-for="row in countries" :key="row.country" :class="{ active: country === row.country }" :aria-current="country === row.country ? 'true' : undefined" @click="country = row.country">{{ label(row.country) }}</button></nav>
        <p v-if="!countries.length && !countrySearch.trim()" class="country-hint">尚未添加国家</p>
        <button v-if="!countrySearch.trim()" class="browse-countries" :aria-expanded="showAvailable" aria-controls="available-tax-countries" @click="browseCountries">{{ browsingCountries ? '收起可添加国家' : '＋ 添加国家' }}</button>
        <section v-if="showAvailable" id="available-tax-countries" aria-label="可添加税费国家">
          <p class="country-section-label">可添加<span>{{ availableMatches.length }}</span></p>
          <ul class="country-results">
            <li v-for="row in availableMatches.slice(0, countryResultLimit)" :key="row.country">
              <div><strong>{{ label(row.country) }}</strong><small v-if="financeCountryIdentity(row.country) !== row.country">{{ financeCountryIdentity(row.country) }}</small></div>
              <button :disabled="saving" :aria-label="`添加${label(row.country)}税费设置`" @click="add(row.country)">＋ 添加</button>
              <p v-if="euConfigured && isEuCountry(row.country)" class="eu-country-hint">当前沿用欧盟；单独添加后需核对该国全部渠道。</p>
            </li>
          </ul>
          <p v-if="!availableMatches.length" class="country-hint" role="status">{{ countries.length ? '匹配国家已添加，可在上方选择' : '未找到匹配国家，请尝试中文、英文或国家代码' }}</p>
          <button v-if="availableMatches.length > countryResultLimit" class="more-countries" @click="countryResultLimit += 10">再显示10个（剩余{{ availableMatches.length - countryResultLimit }}个）</button>
        </section>
        <p v-if="euConfigured && isEuCountry(country)" class="eu-country-hint">{{ addingHandling ? '当前沿用欧盟关税，并叠加本国处理费。' : '当前为独立国家设置，优先于欧盟配置。发布前请核对该国全部渠道。' }}</p>
      </aside>
      <main class="matrix"><div v-if="isEuCountry(country)" class="eu-mode"><label>本国计费方式<select :value="activeCountry?.euTaxMode || 'override'" :disabled="saving" aria-label="本国计费方式" @change="changeEuMode"><option value="override">单独国家关税（覆盖欧盟）</option><option value="add-handling">欧盟关税＋本国处理费</option></select></label><p v-if="addingHandling">沿用欧盟对应渠道的关税，本页只设置额外处理费；未设置的渠道不加处理费。处理费每单收取一次，不会因购买多件而重复收取。</p><p v-else>本国设置优先于欧盟。需要在欧盟关税之外加收费用，请切换为“欧盟关税＋本国处理费”。</p><p v-if="addingHandling" class="notice">原单独国家关税已保留，但不参与此模式。若已在“附加费设置”填写同一处理费，请移除重复项后再发布。</p></div><header><h3>{{ label(country) || '请选择国家' }}</h3><p>同一项关税只计入一次；历史报价保持原金额</p></header><div v-if="country" class="filters"><input v-model="query" placeholder="搜索物流商或渠道" aria-label="搜索税费渠道"><select v-model="provider" aria-label="筛选税费物流商"><option value="">全部物流商</option><option v-for="name in providers" :key="name">{{ name }}</option></select><button :disabled="!selected.length || saving" @click="edit()">批量设置</button></div>
        <div v-if="country" class="selection"><span>共 {{ groups.length }} 家物流商 · 已选 {{ selected.length }} 个渠道</span><button v-if="selected.length" @click="selected = []; editing = false">取消选择</button><button class="select-all" :disabled="!allRows.length || saving" @click="selected = allRows.map(row => row.key)">全选全部渠道（{{ allRows.length }}）</button></div>
        <div class="provider-groups">
          <section v-for="(group, index) in groups" :key="group.name" class="provider-group" :class="{ 'has-selection': group.selectedCount > 0 }" :aria-label="`${group.name}渠道分组`">
            <div class="provider-heading">
              <input type="checkbox" :aria-label="`全选${group.name}${query.trim() ? '筛选结果' : '全部渠道'}`" :checked="group.selectedCount === group.rows.length" :indeterminate="group.selectedCount > 0 && group.selectedCount < group.rows.length" :disabled="saving" @change="toggleGroup(group)">
              <button class="provider-toggle" :aria-expanded="expandedProviders.includes(group.name)" :aria-controls="`tax-provider-${index}`" @click="toggleProvider(group.name)"><strong>{{ group.name }}</strong><span>{{ group.rows.length }} 条{{ query.trim() ? '匹配' : '' }}渠道</span><span v-if="group.selectedCount" class="selected-count">已选 {{ group.selectedCount }} 条</span><span class="expand-label">{{ expandedProviders.includes(group.name) ? '收起' : '展开渠道' }} <span aria-hidden="true">{{ expandedProviders.includes(group.name) ? '⌃' : '⌄' }}</span></span></button>
            </div>
            <div v-if="expandedProviders.includes(group.name)" :id="`tax-provider-${index}`">
              <p class="group-hint">勾选物流商可选择本组{{ query.trim() ? '全部匹配' : '全部' }}渠道，跨本组分页生效</p>
              <div class="table-scroll"><table><thead><tr><th><span class="sr-only">选择渠道</span></th><th>渠道名称</th><th>收费方式</th><th>原币金额</th><th>折合美元</th><th>操作</th></tr></thead><tbody><tr v-for="row in visibleRows(group)" :key="row.key"><td><input type="checkbox" :aria-label="`选择${row.carrier}${row.channel}`" :checked="selected.includes(row.key)" :disabled="saving" @change="toggle(row.key)"></td><td>{{ row.channel }}</td><td><span class="badge" :class="view(row).mode">{{ view(row).name }}</span></td><td>{{ view(row).amount }}</td><td>{{ view(row).usd }}</td><td><button class="text-button" :disabled="saving" :aria-label="`编辑${row.carrier}${row.channel}税费`" @click="edit(row.key)">编辑</button></td></tr></tbody></table></div>
              <nav v-if="groupPages(group) > 1" class="group-pagination" :aria-label="`${group.name}渠道分页`"><span>共 {{ group.rows.length }} 条渠道</span><button :disabled="groupPage(group) <= 1" :aria-label="`${group.name}上一页`" @click="providerPages[group.name] = groupPage(group) - 1">‹</button><span>{{ groupPage(group) }} / {{ groupPages(group) }}</span><button :disabled="groupPage(group) >= groupPages(group)" :aria-label="`${group.name}下一页`" @click="providerPages[group.name] = groupPage(group) + 1">›</button></nav>
            </div>
          </section>
          <p v-if="!groups.length" class="empty">{{ country ? '当前没有匹配的启用渠道' : '添加国家后开始设置渠道税费' }}</p>
        </div>
        <footer><small>1 USD = {{ exchange.usdCny.toFixed(4) }} CNY · 最近保存：{{ modelValue.updatedAt }}</small><div><span>每组每页</span><select v-model.number="size" aria-label="每页渠道数"><option :value="10">10 条</option><option :value="30">30 条</option><option :value="50">50 条</option></select></div></footer>
      </main>
      <aside v-if="editing" class="editor"><header><h3>{{ addingHandling ? '设置本国处理费' : selected.length === 1 ? '编辑渠道税费' : '批量设置关税' }}</h3><button aria-label="关闭税费编辑" @click="editing = false">×</button></header><p>{{ label(country) }} · 已选 {{ selected.length }} 个渠道</p><label>收费方式<select v-model="draft.mode" aria-label="收费方式"><option value="fixed-order">固定金额 / 单</option><option value="weight">按整单重量</option><option value="exempt">{{ addingHandling ? '已含处理费' : '已含税（含）' }}</option><option value="no-tax">{{ addingHandling ? '不加处理费' : '不加收（无）' }}</option><option value="unavailable">该目的地不支持（不发）</option></select></label><template v-if="draft.mode === 'fixed-order' || draft.mode === 'weight'"><label>{{ draft.mode === 'weight' ? '每单固定费用' : '原币金额' }}<div class="amount"><input :value="amountInput" @input="inputAmount" @blur="finishAmount" aria-label="原币金额" type="number" min="0" max="1000000" step="0.01"><select v-model="draft.currency" aria-label="费用币种"><option>USD</option><option>CNY</option><option>EUR</option></select></div></label><label v-if="draft.mode === 'weight'">每公斤费用<input v-model.number="draft.perKg" aria-label="每公斤费用" type="number" min="0" step="0.01"></label><div class="calculation"><small>{{ draft.mode === 'weight' ? '含包材计费重 1kg 示例' : '折合美元' }}</small><strong>{{ preview }}</strong><small>按财务汇率自动换算</small></div></template><p class="notice">{{ addingHandling ? '填写的是本国额外处理费，报价会另加欧盟对应渠道关税；欧盟已含税不免除本国处理费。' : '更新仅影响当前国家所选渠道的新报价。欧盟 CHC 三条渠道保留专用欧元重量规则。' }}</p><div class="actions"><button @click="editing = false">取消</button><button class="primary" :disabled="saving || !selected.length" @click="apply">应用到所选渠道</button></div></aside>
    </div>
  </section>
</template>

<style scoped>
.eu-mode{margin:20px 20px 0;padding:16px;background:#fff8ed;border:1px solid #f3d7ae;border-radius:8px}.eu-mode label{display:flex;align-items:center;gap:16px;flex-wrap:wrap;font-weight:600}.eu-mode p{font-size:12px;margin-bottom:0}.badge.handling{background:#fff0dc;color:#995400}

.country-search{position:relative}.country-search input{padding-right:32px}.country-search button{position:absolute;right:4px;top:3px;border:0;padding:5px 7px;font-size:18px}.countries .country-hint{font-size:12px;line-height:1.6;margin:8px 0 12px}.countries .country-section-label{display:flex;justify-content:space-between;font-size:12px;color:#617285;margin:16px 0 8px}.country-section-label span{font-variant-numeric:tabular-nums}.countries nav[aria-label]{margin:0 -12px 12px}.browse-countries,.more-countries{width:100%;white-space:normal}.browse-countries{color:#c46a00;border-color:#f1c187}.country-results{list-style:none;padding:0;margin:0}.country-results li{display:flex;align-items:center;flex-wrap:wrap;gap:8px;padding:12px 0;border-bottom:1px solid #edf0f4}.country-results li>div{flex:1;min-width:0}.country-results strong{font-size:13px;font-weight:500;overflow-wrap:anywhere}.country-results small{display:block;color:#7c8b9c;font-size:11px;margin-top:3px}.country-results button{padding:6px;font-size:12px;color:#b66300;flex-shrink:0}.countries .eu-country-hint{flex-basis:100%;font-size:11px;color:#886127;background:#fff7e9;border-radius:5px;padding:8px;margin:4px 0;line-height:1.6}.more-countries{font-size:12px;margin-top:10px}
.provider-groups{display:grid;gap:14px;padding:0 20px 20px}.provider-group{border:1px solid #dfe5eb;border-radius:9px;overflow:hidden}.provider-group.has-selection{border-color:#f0b65c}.provider-heading{display:flex;align-items:center;gap:14px;padding:0 16px;background:#f7f9fb}.provider-group.has-selection .provider-heading{background:#fff6e9}.provider-toggle{display:flex;align-items:center;gap:12px;flex:1;min-width:0;padding:16px 0;border:0;border-radius:0;background:transparent;text-align:left}.provider-toggle strong{font-size:15px}.provider-toggle>span{font-size:12px;color:#7a8998}.provider-toggle .selected-count{color:#c87912}.provider-toggle .expand-label{margin-left:auto;white-space:nowrap}.provider-heading input{flex-shrink:0}.group-hint{margin:0;padding:12px 20px 10px;font-size:12px;color:#7d8b99}.group-pagination{display:flex;align-items:center;justify-content:flex-end;gap:12px;padding:12px 20px;font-size:12px;color:#7a8998}.group-pagination button{padding:5px 10px}.group-pagination>span:first-child{margin-right:auto}.selection .select-all{margin-left:auto;border-color:#d9e1e9;color:#647485;background:#fff}.sr-only{position:absolute;width:1px;height:1px;margin:-1px;padding:0;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;border:0}@media(max-width:750px){.provider-groups{padding:0 12px 12px}.provider-toggle{gap:8px;flex-wrap:wrap}.provider-toggle strong{font-size:14px}.provider-toggle .expand-label{margin-left:auto}.selection .select-all{margin-left:0}}
.channel-tax-workspace{color:#172632;font-size:14px}.workspace-title,.matrix header,.editor header{display:flex;align-items:center;justify-content:space-between}.workspace-title{background:#fff;border:1px solid #dfe5eb;border-radius:12px;padding:22px;margin-bottom:16px}.workspace-title h2{font-size:23px;margin:0}h3{margin:0;font-size:18px}p{color:#7c8b9c;line-height:1.65;margin:8px 0}.actions{display:flex;gap:10px}button,input,select{font:inherit;border:1px solid #d9e1e9;border-radius:6px;background:white;padding:10px 12px;color:inherit}button{cursor:pointer;white-space:nowrap}button:hover{border-color:#ff9200;color:#e57b00}button:disabled{opacity:.45;cursor:not-allowed}.primary{background:#ff9200;border-color:#ff9200;color:#fff;font-weight:700}.primary:hover{background:#f18700;color:#fff}.layout{display:grid;grid-template-columns:210px minmax(0,1fr);gap:14px;align-items:start}.layout.editing{grid-template-columns:200px minmax(520px,1fr) 300px}.countries,.matrix,.editor{background:#fff;border:1px solid #dfe5eb;border-radius:10px;overflow:hidden}.countries{padding:20px 12px}.countries h3{margin-bottom:16px}.countries input,.countries select{width:100%;box-sizing:border-box}.countries nav{display:flex;flex-direction:column;margin:16px -12px;max-height:600px;overflow:auto}.countries nav button{border:0;border-left:3px solid transparent;border-radius:0;text-align:left;padding:15px 18px}.countries nav button.active{background:#fff3e4;color:#ea8200;border-left-color:#ff9200;font-weight:700}.add-country{display:grid;gap:10px}.matrix header{display:block;padding:22px}.filters,.selection{display:flex;gap:10px;align-items:center;padding:0 20px 14px;flex-wrap:wrap}.filters input{min-width:180px;flex:1}.filters button,.selection button{color:#e77c00;border-color:#ffb350}.selection{font-size:12px;color:#8290a1}.selection button{padding:7px 9px}.table-scroll{overflow:auto;padding:0 20px}table{border-collapse:collapse;width:100%;min-width:650px;text-align:left}th{background:#f5f7f9;font-weight:600;font-size:12px;color:#5f6e7d}td,th{border-bottom:1px solid #e5eaf0;padding:16px 10px}td:first-child,th:first-child{width:25px}td:nth-child(2){width:29%}td:nth-child(4){min-width:125px;line-height:1.5}td small{display:block;color:#6e7e8f;margin-top:5px;font-size:12px;line-height:1.5}input[type=checkbox]{accent-color:#ff9200;width:17px;height:17px}.badge{display:inline-block;padding:6px 8px;background:#f0f4f8;border-radius:5px;font-size:12px;white-space:nowrap}.badge.weight{background:#fff0dc;color:#e48100}.badge.exempt{background:#e8f8ed;color:#19844a}.badge.unavailable{background:#fff0ee;color:#c54d38}.text-button{border:0;color:#f18a00;padding:2px}.matrix footer{padding:20px;display:flex;gap:12px;justify-content:space-between;align-items:center;border-top:1px solid #edf0f4}.matrix footer small{font-size:11px;color:#8a98a9}.matrix footer div{display:flex;gap:10px;align-items:center}.matrix footer button{padding:5px 10px}.matrix footer select{padding:6px}.empty{text-align:center;color:#8491a1;padding:50px}.editor{padding:20px;position:sticky;top:20px}.editor header button{border:0;font-size:23px;padding:0}.editor label{display:grid;gap:10px;margin-top:22px;font-weight:600}.amount{display:flex;gap:8px}.amount input{width:0;flex:1}.amount select{width:83px}.calculation{background:#fff2e1;border-radius:9px;padding:20px;margin:20px 0;display:grid;gap:10px}.calculation strong{font-size:25px;color:#f58200}.calculation small{color:#7a8b9b}.notice{padding:12px;background:#edf5fb;border-radius:6px;font-size:12px}.error{padding:12px;background:#fff0ee;color:#bd3d2c;border-radius:6px}.editor .actions{margin-top:25px}.editor .actions button{flex:1;padding:12px 6px}@media(max-width:1350px){.layout.editing{grid-template-columns:190px minmax(0,1fr)}.editor{position:fixed;top:0;right:0;bottom:0;width:340px;max-width:90vw;overflow:auto;z-index:100;box-shadow:-12px 0 36px #17263226;border-radius:10px 0 0 10px;background:#fff}.matrix footer{flex-wrap:wrap}}@media(max-width:750px){.layout,.layout.editing{grid-template-columns:1fr}.editor{grid-column:1}.workspace-title{align-items:flex-start;gap:15px;flex-direction:column}.countries nav{max-height:200px}.matrix footer{flex-direction:column;align-items:flex-start}}
</style>
