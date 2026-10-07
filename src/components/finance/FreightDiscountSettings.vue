<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { loadFreightDiscountChannels, loadFreightDiscountSettings, newFreightDiscountRule, saveFreightDiscountSettings, ZHENGZHOU_CHANNEL_CODE, ZHENGZHOU_DISCOUNT_COUNTRIES, type FreightDiscountChannel, type FreightDiscountRule } from '@/data/freightDiscountSettings'

const clone = <T,>(value: T): T => JSON.parse(JSON.stringify(value))
const saved = ref(loadFreightDiscountSettings())
const channels = ref<FreightDiscountChannel[]>([])
const drafts = ref<Record<string, FreightDiscountRule>>({})
const selected = ref(''), channelSearch = ref(''), provider = ref(''), countrySearch = ref('')
const saving = ref(false), loading = ref(true), loadError = ref(''), error = ref(''), message = ref('')
const checked = ref<string[]>([]), batchFactor = ref(0.97)
const catalogue = computed(() => [
  ...channels.value,
  ...saved.value.rules.filter(rule => !channels.value.some(c => c.channelId === rule.channelId))
    .map(rule => ({ ...rule, countries: Object.keys(rule.countries).map(code => ({ code, name: code })) })),
])
const providers = computed(() => [...new Set(catalogue.value.map(c => c.providerName))].sort())
const visibleChannels = computed(() => catalogue.value.filter(c => (!provider.value || c.providerName === provider.value)
  && `${c.providerName} ${c.channelName} ${c.channelCode}`.toLowerCase().includes(channelSearch.value.trim().toLowerCase())))
const current = computed(() => catalogue.value.find(c => c.channelId === selected.value))
const draft = computed(() => drafts.value[selected.value])
const active = computed(() => channels.value.some(c => c.channelId === selected.value))
const original = (id: string) => saved.value.rules.find(rule => rule.channelId === id)
const dirty = (id: string) => !!drafts.value[id] && JSON.stringify(drafts.value[id]) !== JSON.stringify(original(id) || newFreightDiscountRule(catalogue.value.find(c => c.channelId === id)!))
const unpublished = computed(() => !original(selected.value))
const rows = computed(() => {
  if (!current.value || !draft.value) return []
  const names = new Map<string, string>()
  for (const item of current.value.countries) if (/^[A-Z]{2}$/.test(item.code)) names.set(item.code, item.name || item.code)
  for (const code of Object.keys(draft.value.countries)) if (!names.has(code)) names.set(code, ZHENGZHOU_DISCOUNT_COUNTRIES.find(c => c[0] === code)?.[1] || code)
  return [...names].map(([code, name]) => ({ code, name })).sort((a, b) => a.code.localeCompare(b.code))
    .filter(row => `${row.code} ${row.name}`.toLowerCase().includes(countrySearch.value.trim().toLowerCase()))
})
const allChecked = computed(() => rows.value.length > 0 && rows.value.every(row => checked.value.includes(row.code)))
function select(id: string) {
  if (saving.value) return
  selected.value = id; checked.value = []; countrySearch.value = ''; error.value = ''; message.value = ''
  if (!drafts.value[id]) drafts.value[id] = clone(original(id) || newFreightDiscountRule(catalogue.value.find(c => c.channelId === id)!))
}
async function load() {
  loading.value = true; loadError.value = ''
  try {
    channels.value = await loadFreightDiscountChannels()
    const first = catalogue.value.find(c => original(c.channelId)) || catalogue.value.find(c => c.channelCode === ZHENGZHOU_CHANNEL_CODE) || catalogue.value[0]
    if (!selected.value && first) select(first.channelId)
  } catch (cause) { loadError.value = cause instanceof Error ? cause.message : '渠道目录读取失败' }
  finally { loading.value = false }
}
onMounted(load)
function setFactor(code: string, event: Event) {
  const value = (event.target as HTMLInputElement).value
  if (draft.value) draft.value.countries[code] = value === '' ? NaN : Number(value)
}
function batch(reset = false) {
  if (!draft.value) return
  const visible = new Set(rows.value.map(row => row.code))
  for (const code of checked.value.filter(code => visible.has(code))) {
    if (reset) delete draft.value.countries[code]
    else draft.value.countries[code] = batchFactor.value
  }
}
function toggleAll() {
  const visible = new Set(rows.value.map(row => row.code))
  checked.value = allChecked.value ? checked.value.filter(code => !visible.has(code)) : [...new Set([...checked.value, ...visible])]
}
async function save() {
  if (saving.value || !draft.value || loadError.value) return
  error.value = ''; message.value = ''; saving.value = true
  const id = selected.value
  try {
    const rule = clone(draft.value)
    saved.value = await saveFreightDiscountSettings({ ...clone(saved.value), rules: [...saved.value.rules.filter(r => r.channelId !== id).map(clone), rule] })
    drafts.value[id] = clone(saved.value.rules.find(r => r.channelId === id)!)
    message.value = '当前渠道已发布。新报价使用最新系数，已打开的报价需更新计价。'
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '保存失败，请重试' }
  finally { saving.value = false }
}
</script>

<template>
  <section class="freight-discounts" aria-label="渠道运费折扣">
    <header class="page-heading"><div><h2>渠道运费折扣</h2><p>按渠道维护默认系数和国家例外，全重量段适用。</p></div><span class="tag">{{ saved.rules.filter(r => r.enabled).length }} 个渠道已启用</span></header>
    <p v-if="loadError" role="alert" class="error">{{ loadError }} <button @click="load">重新读取渠道</button></p>
    <div class="workspace">
      <aside>
        <input v-model="channelSearch" aria-label="搜索渠道" placeholder="搜索渠道 / 承运商">
        <select v-model="provider" aria-label="筛选承运商"><option value="">全部承运商</option><option v-for="name in providers" :key="name">{{ name }}</option></select>
        <p class="hint">{{ loading ? '正在读取渠道…' : `共 ${visibleChannels.length} 个渠道` }}</p>
        <nav aria-label="渠道列表">
          <button v-for="channel in visibleChannels" :key="channel.channelId" :aria-pressed="selected === channel.channelId" :disabled="saving" @click="select(channel.channelId)">
            <strong>{{ channel.channelName }}</strong><span>{{ channel.providerName }} <small>{{ dirty(channel.channelId) ? '· 未保存' : original(channel.channelId)?.enabled ? '· 已启用' : original(channel.channelId) ? '· 已停用' : '· 未配置' }}</small></span>
          </button>
          <p v-if="!loading && !visibleChannels.length" class="hint">没有匹配的渠道</p>
        </nav>
      </aside>
      <main v-if="draft && current">
        <header class="editor-heading"><div><p class="eyebrow">{{ current.providerName }}</p><h3>{{ current.channelName }}</h3><p class="hint">{{ dirty(selected) ? '有未保存修改' : unpublished ? '尚未发布' : '已保存' }} · 切换渠道会保留本页修改</p></div><button class="primary" :disabled="saving || loading || !!loadError || (!active && draft.enabled)" @click="save">{{ saving ? '保存中…' : '保存当前渠道' }}</button></header>
        <p v-if="error" role="alert" class="error">{{ error }}</p><p v-if="message" role="status" class="success">{{ message }}</p>
        <p v-if="!active" class="notice">该渠道当前不在可报价目录中。可停用已存规则；重新启用前须恢复有效运价。</p>
        <p v-else-if="unpublished && current.channelCode === ZHENGZHOU_CHANNEL_CODE" class="notice">已预填确认的29个国家系数及其他国家系数，保存后生效。</p>
        <fieldset :disabled="saving">
          <div class="settings">
            <label class="toggle"><input v-model="draft.enabled" aria-label="启用当前渠道折扣" type="checkbox"> 启用当前渠道折扣</label>
            <label>折扣适用费用<select v-model="draft.basis" aria-label="折扣适用费用"><option value="base-excluding-linehaul">公斤费 + 挂号费（干线费保持原价）</option><option value="full-freight">全部基础运费（含运价中的干线费）</option></select></label>
            <label>默认系数<input v-model.number="draft.defaultFactor" aria-label="默认系数" type="number" min="0.0001" max="2" step="0.01"></label>
          </div>
          <div class="formula"><strong>{{ draft.basis === 'base-excluding-linehaul' ? '运费 =（重量 × 公斤费 + 挂号费）× 系数 + 重量 × 干线费' : '运费 = 原基础运费 × 系数' }}</strong><p>未单列国家使用默认系数。0.97 = 按97%计费，1.05 = 按105%计费；财务税费、附加费独立计算。</p></div>
          <div class="table-heading"><div><h4>国家系数</h4><span class="hint">{{ Object.keys(draft.countries).length }} 个国家单独设置 · 国家各分区使用相同系数</span></div><input v-model="countrySearch" aria-label="搜索折扣国家" placeholder="搜索国家 / 代码"></div>
          <div class="batch"><span>批量修改</span><input v-model.number="batchFactor" aria-label="批量系数" type="number" min="0.0001" max="2" step="0.01"><button :disabled="!checked.some(code => rows.some(r => r.code === code))" @click="batch()">应用到所选国家</button><button :disabled="!checked.some(code => rows.some(r => r.code === code))" @click="batch(true)">所选恢复默认</button></div>
          <div class="table-scroll"><table><thead><tr><th><input type="checkbox" aria-label="选择当前筛选国家" :checked="allChecked" @change="toggleAll"></th><th>国家 / 地区</th><th>计费系数</th><th>来源</th><th>操作</th></tr></thead><tbody>
            <tr v-for="row in rows" :key="row.code"><td><input v-model="checked" type="checkbox" :value="row.code" :aria-label="`选择${row.name}`"></td><td>{{ row.name }} <small>{{ row.code }}</small></td><td><input :value="draft.countries[row.code] ?? draft.defaultFactor" :aria-label="`${row.name}折扣系数`" type="number" min="0.0001" max="2" step="0.01" @input="setFactor(row.code, $event)"></td><td><span :class="row.code in draft.countries ? 'override' : 'hint'">{{ row.code in draft.countries ? '单独设置' : '默认系数' }}</span></td><td><button v-if="row.code in draft.countries" class="link" :aria-label="`${row.name}恢复默认`" @click="delete draft.countries[row.code]">恢复默认</button><span v-else class="hint">—</span></td></tr>
          </tbody></table><p v-if="!rows.length" class="hint">没有匹配的国家</p></div>
        </fieldset>
        <footer>可发国家、分区和重量范围沿用运价表；保存无需重新导入，历史报价保留原金额。</footer>
      </main>
      <main v-else class="empty">{{ loading ? '正在读取渠道目录…' : '请选择一个渠道维护运费折扣' }}</main>
    </div>
  </section>
</template>

<style scoped>
.freight-discounts{border:1px solid #dce5ed;border-radius:12px;background:#fff;color:#193047;overflow:hidden;font-size:14px}.page-heading,.editor-heading,.table-heading{display:flex;align-items:center;justify-content:space-between;gap:18px}.page-heading{padding:22px 26px;border-bottom:1px solid #e4ebf2}h2{margin:0;font-size:21px}h3{margin:0;font-size:20px}h4{margin:0 0 6px;font-size:15px}p{line-height:1.7}.page-heading p{color:#6c7f90;margin:7px 0 0}.tag{background:#edf5ff;color:#376db2;padding:8px 12px;border-radius:6px}.workspace{display:grid;grid-template-columns:260px minmax(0,1fr)}aside{background:#f7f9fc;padding:20px 16px;border-right:1px solid #e4ebf2}aside>input,aside>select{width:100%;margin-bottom:10px}nav{max-height:790px;overflow:auto}nav button{display:block;text-align:left;width:100%;margin-bottom:6px;padding:13px 12px;background:transparent;border:1px solid transparent}nav button[aria-pressed=true]{background:#eaf2ff;border-color:#bfd4f2;color:#1d5ead}nav strong,nav span{display:block}nav span{font-size:12px;margin-top:7px;color:#718397}main{padding:24px;min-width:0}.eyebrow{font-size:12px;color:#728399;margin:0 0 5px}.hint,small{color:#7a8b9a;font-size:12px}small{margin-left:8px}button,input,select{font:inherit;border:1px solid #cdd9e5;border-radius:6px;padding:9px 10px;color:inherit;background:#fff;box-sizing:border-box}button{cursor:pointer}button:disabled{opacity:.5;cursor:default}.primary{color:#fff;background:#2569cc;border-color:#2569cc;font-weight:600;white-space:nowrap}fieldset{border:0;padding:0;margin:0}.settings{display:flex;align-items:end;flex-wrap:wrap;gap:20px;margin-top:18px}.settings label:not(.toggle){display:flex;flex-direction:column;gap:8px;font-size:13px}.toggle{padding:10px 0}.settings input[type=number],td input[type=number],.batch input{width:100px}.formula{padding:16px;background:#f1f6fe;border-radius:8px;margin:20px 0;font-size:13px}.formula p{margin:8px 0 0;color:#6c7f90;font-size:12px}.table-heading{margin-bottom:14px}.table-heading>input{width:210px}.batch{display:flex;align-items:center;gap:10px;flex-wrap:wrap;background:#f8fafc;padding:10px 12px;border:1px solid #e5ebf1;border-bottom:0;font-size:12px}.batch input{padding:6px 8px}.batch button{padding:7px 10px}.table-scroll{max-height:440px;overflow:auto;border:1px solid #e5ebf1}table{width:100%;border-collapse:collapse;text-align:left}th{background:#f4f7fb;color:#6a7d90;font-size:12px;position:sticky;top:0;z-index:1}th,td{padding:10px 14px;border-bottom:1px solid #eaf0f5}td{font-size:13px}th:first-child,td:first-child{width:24px}td input{padding:6px 10px}.override{font-size:12px;color:#416da7}.link{border:0;background:none;color:#2467bd;padding:4px}footer{color:#7a8b9a;font-size:12px;padding-top:20px}.error{color:#b52b32}.success{color:#13724b}.notice{color:#8a610d;background:#fff8e9;padding:10px 14px;border-radius:6px;font-size:12px}.empty{padding:80px;text-align:center;color:#7a8b9a}@media(max-width:900px){.workspace{grid-template-columns:210px minmax(0,1fr)}main{padding:16px}.table-heading{flex-wrap:wrap}}@media(max-width:650px){.workspace{grid-template-columns:1fr}nav{max-height:200px}aside{border-right:0}.editor-heading{flex-wrap:wrap}}
</style>
