<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { logisticsRules as sourceRules, type LogisticsPriceRow, type LogisticsRule } from '@/data/logistics'

const rules = ref<LogisticsRule[]>(structuredClone(sourceRules))
const selectedIds = ref<number[]>([])
const typeFilter = ref('')
const publishFilter = ref('')
const statusFilter = ref('')
const searchMode = ref<'name' | 'english'>('name')
const keyword = ref('')
const page = ref(1)
const pageSize = ref(10)
const toast = ref('')
const view = ref<'list' | 'areas'>('list')
const activeRule = ref<LogisticsRule | null>(null)
const showRuleEditor = ref(false)
const showConditionEditor = ref(false)
const showAreaEditor = ref(false)
const form = reactive({ id: 0, name: '', englishName: '', type: '专线', published: '未发布', status: '启用', carrier: '', channel: '', channelCode: '' })
const editingAreaIndex = ref<number | null>(null)
const areaForm = reactive({ areaName: '', countryCode: '', etaMinDays: 0, etaMaxDays: 0, weightFromG: 0, weightToG: 0, pricePer1000G: 0, registrationFee: 0 })
const areaKeyword = ref('')
type WorkspaceMode = 'rules' | 'base'
interface ImportedTemplate { id: string; name: string; updatedAt: string }
interface LogisticsProviderSetting { id: string; name: string; code: string; templates: ImportedTemplate[] }

const SETTINGS_KEY = 'milano.logistics.base-settings.v1'
const SETTINGS_VERSION = 2
const workspaceMode = ref<WorkspaceMode>('base')
const templateInput = ref<HTMLInputElement | null>(null)
const uploadProviderId = ref('')
const providerSearch = ref('')
const draggedProviderId = ref('')
const dragOverProviderId = ref('')
const showProviderEditor = ref(false)
const providerForm = reactive({ name: '', code: '' })

const defaultProviders: LogisticsProviderSetting[] = [
  { id: 'baizhou', name: '百洲物流', code: 'BAIZHOU', templates: [] },
  { id: 'huahai', name: '花海供应链', code: 'HUAHAI', templates: [] },
  { id: 'jieyitongda', name: '捷易通达', code: 'JIEYITONGDA', templates: [] },
  { id: 'lianyoutong', name: '联邮通', code: 'LIANYOUTONG', templates: [] },
  { id: 'rongding', name: '容鼎供应链', code: 'RONGDING', templates: [] },
  { id: 'shandianhou', name: '闪电猴', code: 'SHANDIANHOU', templates: [] },
  { id: 'sf', name: '顺丰国际', code: 'SF', templates: [] },
  { id: 'shunyou', name: '顺友', code: 'SHUNYOU', templates: [] },
  { id: 'wanbang', name: '万邦速达', code: 'WANBANG', templates: [] },
  { id: 'yuntu', name: '云途物流', code: 'YUNTU', templates: [
    { id: 'yt-1', name: '云途挂号标快普货.xlsx', updatedAt: '2026-08-14 10:20' },
    { id: 'yt-2', name: '云途专线带电.xlsx', updatedAt: '2026-08-13 16:45' },
  ] },
  { id: 'yanwen', name: '燕文物流', code: 'YANWEN', templates: [
    { id: 'yw-1', name: '燕文化妆品.xlsx', updatedAt: '2026-08-14 14:30' },
    { id: 'yw-2', name: '燕文普货专线.xlsx', updatedAt: '2026-08-12 09:55' },
  ] },
  { id: 'yunsudi', name: '云速递', code: 'YUNSUDI', templates: [] },
]
function loadBaseSettings() {
  try {
    const saved = JSON.parse(localStorage.getItem(SETTINGS_KEY) || '{}')
    const savedProviders = Array.isArray(saved.providers) ? saved.providers as LogisticsProviderSetting[] : []
    const mergedProviders = defaultProviders.map(defaultProvider => {
      const existing = savedProviders.find(item => item.id === defaultProvider.id || item.code?.toUpperCase() === defaultProvider.code)
      return existing ? { ...existing, templates: Array.isArray(existing.templates) ? existing.templates : [] } : structuredClone(defaultProvider)
    })
    const additionalProviders = savedProviders.filter(item => !defaultProviders.some(defaultProvider => defaultProvider.id === item.id || defaultProvider.code === item.code?.toUpperCase()))
    return { providers: saved.providerCatalogVersion === SETTINGS_VERSION ? savedProviders : [...mergedProviders, ...additionalProviders] }
  } catch {
    return { providers: structuredClone(defaultProviders) }
  }
}
const initialSettings = loadBaseSettings()
const providerSettings = ref<LogisticsProviderSetting[]>(initialSettings.providers)
localStorage.setItem(SETTINGS_KEY, JSON.stringify({ providerCatalogVersion: SETTINGS_VERSION, providers: providerSettings.value }))
const selectedProviderId = ref(providerSettings.value.some(item => item.id === 'yanwen') ? 'yanwen' : providerSettings.value[0]?.id || '')
const filteredProviderSettings = computed(() => {
  const query = providerSearch.value.trim().toLowerCase()
  if (!query) return providerSettings.value
  return providerSettings.value.filter(item => `${item.name} ${item.code}`.toLowerCase().includes(query))
})
const selectedProvider = computed(() => providerSettings.value.find(item => item.id === selectedProviderId.value) ?? providerSettings.value[0])

function saveBaseSettings(message = '基础资料设置已保存') {
  localStorage.setItem(SETTINGS_KEY, JSON.stringify({ providerCatalogVersion: SETTINGS_VERSION, providers: providerSettings.value }))
  notify(message)
}
function templateChannelName(fileName: string) { return fileName.replace(/\.xlsx?$/i, '').trim() }
function triggerTemplateUpload(providerId: string) {
  uploadProviderId.value = providerId
  templateInput.value?.click()
}
function addTemplateFiles(providerId: string, files: File[]) {
  const provider = providerSettings.value.find(item => item.id === providerId)
  if (!provider || !files.length) return
  const timestamp = new Date().toLocaleString('zh-CN', { hour12: false }).replace(/\//g, '-')
  files.forEach((file, index) => provider.templates.unshift({ id: `${Date.now()}-${index}-${file.name}`, name: file.name, updatedAt: timestamp }))
  saveBaseSettings(`已上传 ${provider.name} 的 ${files.length} 个渠道模板`)
}
function handleTemplateUpload(event: Event) {
  const input = event.target as HTMLInputElement
  if (!input.files?.length) return
  addTemplateFiles(uploadProviderId.value, Array.from(input.files))
  input.value = ''
}
function handleTemplateDrop(event: DragEvent) {
  if (!selectedProvider.value) return
  const files = Array.from(event.dataTransfer?.files ?? []).filter(file => /\.xlsx?$/i.test(file.name))
  if (!files.length) return notify('请拖入 Excel 模板文件')
  addTemplateFiles(selectedProvider.value.id, files)
}
function saveProvider() {
  const name = providerForm.name.trim()
  if (!name) return notify('请填写物流商名称')
  const code = providerForm.code.trim().toUpperCase() || `P${providerSettings.value.length + 1}`
  const id = `${Date.now()}`
  providerSettings.value.push({ id, name, code, templates: [] })
  selectedProviderId.value = id
  Object.assign(providerForm, { name: '', code: '' })
  showProviderEditor.value = false
  saveBaseSettings('物流商已添加')
}
function removeProvider(provider: LogisticsProviderSetting) {
  if (providerSettings.value.length <= 1) return notify('至少需要保留一家物流商')
  const detail = provider.templates.length ? `${provider.templates.length} 个渠道模板` : ''
  if (!window.confirm(`确认删除“${provider.name}”吗？${detail ? `\n同时会删除关联的${detail}。` : ''}`)) return
  providerSettings.value = providerSettings.value.filter(item => item.id !== provider.id)
  selectedProviderId.value = providerSettings.value[0]?.id || ''
  saveBaseSettings('物流商已删除')
}
function removeTemplate(provider: LogisticsProviderSetting, template: ImportedTemplate) {
  if (!window.confirm(`确认删除模板“${template.name}”吗？`)) return
  provider.templates = provider.templates.filter(item => item.id !== template.id)
  saveBaseSettings('渠道模板已删除')
}
function startProviderDrag(providerId: string, event: DragEvent) {
  draggedProviderId.value = providerId
  event.dataTransfer?.setData('text/plain', providerId)
  if (event.dataTransfer) event.dataTransfer.effectAllowed = 'move'
}
function dropProvider(targetProviderId: string) {
  const sourceProviderId = draggedProviderId.value
  if (!sourceProviderId || sourceProviderId === targetProviderId) return endProviderDrag()
  const sourceIndex = providerSettings.value.findIndex(item => item.id === sourceProviderId)
  if (sourceIndex < 0) return endProviderDrag()
  const [movedProvider] = providerSettings.value.splice(sourceIndex, 1)
  if (!movedProvider) return endProviderDrag()
  const targetIndex = providerSettings.value.findIndex(item => item.id === targetProviderId)
  providerSettings.value.splice(targetIndex < 0 ? providerSettings.value.length : targetIndex, 0, movedProvider)
  endProviderDrag()
  saveBaseSettings('物流商顺序已保存')
}
function endProviderDrag() {
  draggedProviderId.value = ''
  dragOverProviderId.value = ''
}
function gramsFromKg(value: number) { return Math.ceil(Math.max(0, Number(value) || 0) * 1000) }
function kilogramsFromGrams(value: number) { return Math.ceil(Math.max(0, Number(value) || 0)) / 1000 }

const filtered = computed(() => rules.value.filter(rule => {
  const searchValue = searchMode.value === 'name' ? rule.name : rule.englishName
  return (!typeFilter.value || rule.type.includes(typeFilter.value)) && (!publishFilter.value || rule.published === publishFilter.value) && (!statusFilter.value || rule.status === statusFilter.value) && (!keyword.value.trim() || searchValue.toLowerCase().includes(keyword.value.trim().toLowerCase()))
}))
const pages = computed(() => Math.max(1, Math.ceil(filtered.value.length / pageSize.value)))
const visibleRules = computed(() => filtered.value.slice((page.value - 1) * pageSize.value, page.value * pageSize.value))
const allVisibleSelected = computed(() => visibleRules.value.length > 0 && visibleRules.value.every(rule => selectedIds.value.includes(rule.id)))
const areaRows = computed(() => activeRule.value?.prices ?? [])
const visibleAreaRows = computed(() => areaRows.value.map((area, index) => ({ area, index })).filter(({ area }) => !areaKeyword.value.trim() || `${area.areaName} ${area.countryCode}`.toLowerCase().includes(areaKeyword.value.trim().toLowerCase())))

function notify(message: string) { toast.value = message; window.setTimeout(() => toast.value === message && (toast.value = ''), 2200) }
function toggleVisible() { const ids = visibleRules.value.map(rule => rule.id); selectedIds.value = allVisibleSelected.value ? selectedIds.value.filter(id => !ids.includes(id)) : [...new Set([...selectedIds.value, ...ids])] }
function openRuleEditor(rule?: LogisticsRule) {
  const relation = rule?.relations[0]
  Object.assign(form, { id: rule?.id ?? 0, name: rule?.name ?? '', englishName: rule?.englishName ?? '', type: rule?.type ?? '专线', published: rule?.published ?? '未发布', status: rule?.status ?? '启用', carrier: relation?.carrier ?? '', channel: relation?.channel ?? '', channelCode: relation?.channelCode ?? '' })
  showRuleEditor.value = true
}
function saveRule() {
  const existing = rules.value.find(rule => rule.id === form.id)
  if (existing) Object.assign(existing, { name: form.name, englishName: form.englishName, type: form.type, published: form.published, status: form.status, relations: form.carrier ? [{ carrier: form.carrier, channel: form.channel, channelCode: form.channelCode, discounts: '-\n-' }] : [] })
  else rules.value.unshift({ id: Math.max(...rules.value.map(rule => rule.id)) + 1, name: form.name || '新运费规则', englishName: form.englishName, type: form.type, currency: 'USD', published: form.published, status: form.status, dates: new Date().toLocaleString(), users: 'admin', relations: form.carrier ? [{ carrier: form.carrier, channel: form.channel, channelCode: form.channelCode, discounts: '-\n-' }] : [], phoneRequired: false, areaCount: 0, priceRowCount: 0, prices: [] })
  showRuleEditor.value = false; notify('保存成功')
}
function cloneSelected() { const rule = rules.value.find(item => selectedIds.value.includes(item.id)); if (!rule) return notify('请选择一条规则'); rules.value.unshift({ ...structuredClone(rule), id: Math.max(...rules.value.map(item => item.id)) + 1, name: `${rule.name}-副本` }); notify('克隆成功') }
function toggleRule(rule: LogisticsRule) { rule.status = rule.status === '启用' ? '禁用' : '启用'; notify('状态设置成功') }
function removeRule(rule: LogisticsRule) { rules.value = rules.value.filter(item => item.id !== rule.id); selectedIds.value = selectedIds.value.filter(id => id !== rule.id); notify('已从当前本地数据中删除') }
function openAreas(rule: LogisticsRule) { activeRule.value = rule; view.value = 'areas' }
function openCondition(rule: LogisticsRule) { activeRule.value = rule; showConditionEditor.value = true }
function exportRules() { const data = rules.value.filter(rule => !selectedIds.value.length || selectedIds.value.includes(rule.id)); const link = document.createElement('a'); link.href = URL.createObjectURL(new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' })); link.download = '运费规则.json'; link.click(); URL.revokeObjectURL(link.href); notify(`已导出 ${data.length} 条规则`) }
function openAreaEditor(index?: number) {
  const row = index === undefined ? null : areaRows.value[index]
  editingAreaIndex.value = index ?? null
  Object.assign(areaForm, {
    areaName: row?.areaName ?? '', countryCode: row?.countryCode ?? '', etaMinDays: row?.etaMinDays ?? 0, etaMaxDays: row?.etaMaxDays ?? 0,
    weightFromG: gramsFromKg(row?.weightFromKg ?? 0), weightToG: gramsFromKg(row?.weightToKg ?? 0),
    pricePer1000G: row?.pricePerKg ?? 0, registrationFee: row?.registrationFee ?? 0,
  })
  showAreaEditor.value = true
}
function saveArea() {
  if (!activeRule.value) return
  if (Number(areaForm.weightToG) < Number(areaForm.weightFromG)) {
    notify('截止重量不能小于起始重量')
    return
  }
  const editable = {
    areaName: areaForm.areaName.trim(), countryCode: areaForm.countryCode.trim().toUpperCase(),
    etaMinDays: Math.max(0, Number(areaForm.etaMinDays) || 0), etaMaxDays: Math.max(0, Number(areaForm.etaMaxDays) || 0),
    weightFromKg: kilogramsFromGrams(areaForm.weightFromG), weightToKg: kilogramsFromGrams(areaForm.weightToG),
    pricePerKg: Math.max(0, Number(areaForm.pricePer1000G) || 0), registrationFee: Math.max(0, Number(areaForm.registrationFee) || 0),
  }
  if (editingAreaIndex.value == null) {
    const newRow: LogisticsPriceRow = {
      ...editable, prohibitedMarks: '', allowedMarks: '', maxPerimeterCm: 0, maxSideCm: 0, volumeDivisor: 0,
      startWeightKg: 0, minChargeWeightKg: 0, firstWeightKg: 0, firstWeightPrice: 0,
      nextWeightKg: 0, nextWeightPrice: 0, intervalPrice: 0, surcharge: 0, fuelSurchargeRate: 0,
      prohibitGeneralCargo: false, volumetric: false, phoneRequired: false, zoneName: '', zoneExclude: false,
    }
    activeRule.value.prices.push(newRow)
  } else {
    Object.assign(activeRule.value.prices[editingAreaIndex.value], editable)
  }
  activeRule.value.areaCount = new Set(activeRule.value.prices.map(row => row.countryCode || row.areaName)).size
  activeRule.value.priceRowCount = activeRule.value.prices.length
  showAreaEditor.value = false
  notify('区域计费重量已按 g 保存，报价计算会自动换算')
}
function areaAction(message: string) { notify(`${message}已在当前页面状态执行`) }
</script>

<template>
  <div class="erp">
    <header class="milano-topbar">
      <RouterLink class="milano-brand" to="/quotation"><span>J</span><div><strong>米莱诺报价</strong><small>MILANO PRICING ERP</small></div></RouterLink>
      <nav><RouterLink to="/quotation">业务报价</RouterLink><RouterLink to="/quotation/products">采购资料</RouterLink><RouterLink class="active" to="/quotation/logistics">物流规则</RouterLink><RouterLink to="/quotation/members">财务设置</RouterLink><RouterLink to="/quotation/my-records">我的报价记录</RouterLink><RouterLink to="/quotation/records">公司报价记录</RouterLink></nav>
      <div class="milano-user"><span>AD</span><div><b>管理员</b><small>报价中心</small></div></div>
    </header>
    <section class="workspace">
      <template v-if="view === 'list'">
        <div class="milano-heading"><div><p>LOGISTICS CONFIGURATION</p><h1>物流规则</h1><span>维护物流渠道、国家区域、重量限制与分段运费，供米莱诺报价计算直接调用。</span></div></div>
        <div class="tip"><b>PS：</b> 此用于设置物流渠道的运费计算规则</div>
        <section class="workspace-switch"><button :class="{ active: workspaceMode === 'base' }" @click="workspaceMode='base'">基础资料设置</button><button :class="{ active: workspaceMode === 'rules' }" @click="workspaceMode='rules'">运费规则列表</button></section>
        <template v-if="workspaceMode === 'rules'">
          <section class="filters"><div><label>类型</label><select v-model="typeFilter"><option value="">--请选择--</option><option>专线</option><option>挂号</option><option>free</option></select><label>是否发布</label><select v-model="publishFilter"><option value="">--请选择--</option><option>发布</option><option>未发布</option></select><label>状态</label><select v-model="statusFilter"><option value="">--请选择--</option><option>启用</option><option>禁用</option></select></div><div><label>搜索类型</label><button :class="{ chosen:searchMode==='name' }" @click="searchMode='name'">规则名称</button><button :class="{ chosen:searchMode==='english' }" @click="searchMode='english'">英文名称</button></div><div><label>搜索内容</label><input v-model="keyword" :placeholder="searchMode==='name'?'输入规则名称':'输入英文名称'" @keyup.enter="page=1"><button class="search" @click="page=1">搜索(S)</button></div></section>
          <section class="data-card"><div class="actions"><button class="green" @click="openRuleEditor()">✚ 添加</button><button class="blue" @click="exportRules">↪ 导出</button><button class="yellow" @click="cloneSelected">✹ 克隆</button><button class="teal" @click="notify('请选择规则后更新会员报价')">查看/更新会员报价⌄</button><div>排序条件　<span>默认排序▼</span>　模块名称</div></div>
            <div class="scroll"><table><thead><tr><th><input type="checkbox" :checked="allVisibleSelected" @change="toggleVisible"></th><th>规则名称</th><th>英文名称</th><th>类型</th><th>关联</th><th>是否发布</th><th>创建时间<br>修改时间</th><th>创建者<br>修改人</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-for="rule in visibleRules" :key="rule.id"><td><input v-model="selectedIds" type="checkbox" :value="rule.id"></td><td>{{ rule.name }}</td><td>{{ rule.englishName }}</td><td><span class="type">{{ rule.type }}</span></td><td><a>{{ rule.relations.length || '-' }}</a></td><td><span class="unpublished">{{ rule.published }}</span></td><td>{{ rule.dates.split('|')[0] }}<br>{{ rule.dates.split('|')[1] }}</td><td>{{ rule.users.split('|')[0] }}<br>{{ rule.users.split('|')[1] }}</td><td>{{ rule.status }}</td><td class="ops"><button @click="toggleRule(rule)">{{ rule.status==='启用'?'禁用':'启用' }}</button><button @click="openRuleEditor(rule)">编辑</button><button @click="openAreas(rule)">区域设置</button><button @click="openCondition(rule)">条件限制</button><button @click="removeRule(rule)">删除</button></td></tr></tbody></table></div>
            <footer><button @click="page=1">首页</button><button @click="page=Math.max(1,page-1)">上一页</button><button v-for="n in pages" :key="n" :class="{ current:page===n }" @click="page=n">{{ n }}</button><button @click="page=Math.min(pages,page+1)">下一页</button><button @click="page=pages">尾页</button><span>每页 <select v-model.number="pageSize" @change="page=1"><option>10</option><option>20</option><option>50</option><option>100</option></select> 条　共 {{ filtered.length }} 条数据</span></footer></section>
        </template>
        <section v-else class="base-settings">
            <div class="base-toolbar"><div><h2>物流商与渠道模板</h2><p>同一物流商可以一次上传多个渠道模板；国家及澳大利亚1—4报价区域直接以Excel内容为准。</p></div><div class="provider-toolbar"><label>⌕<input v-model="providerSearch" placeholder="搜索物流商或编码"></label><button class="outline-orange" @click="showProviderEditor=true">＋ 新增物流商</button></div></div>
            <input ref="templateInput" class="hidden-file" type="file" multiple accept=".xlsx,.xls" @change="handleTemplateUpload">
            <div class="provider-manager">
              <aside class="provider-list"><div class="provider-sort-hint"><span>物流商列表</span><small>按住卡片拖动排序</small></div><div class="provider-list-scroll"><button v-for="provider in filteredProviderSettings" :key="provider.id" draggable="true" :class="{ active:selectedProvider?.id===provider.id, dragging:draggedProviderId===provider.id, 'drag-over':dragOverProviderId===provider.id && draggedProviderId!==provider.id }" @click="selectedProviderId=provider.id" @dragstart="startProviderDrag(provider.id,$event)" @dragover.prevent="dragOverProviderId=provider.id" @drop.prevent="dropProvider(provider.id)" @dragend="endProviderDrag"><u title="拖动排序">⋮⋮</u><i>{{ provider.name.slice(0,1) }}</i><span><b>{{ provider.name }}</b><small>{{ provider.code }}</small></span><em>{{ provider.templates.length }}个渠道</em><strong>›</strong></button><div v-if="!filteredProviderSettings.length" class="provider-empty">没有匹配的物流商</div></div><footer>共 {{ providerSettings.length }} 家物流商 · 拖动可排序</footer></aside>
              <section v-if="selectedProvider" class="provider-detail"><header><div class="provider-icon">{{ selectedProvider.name.slice(0,1) }}</div><div><h3>{{ selectedProvider.name }} <span>{{ selectedProvider.templates.length }}个渠道</span></h3><small>物流商编码 {{ selectedProvider.code }}</small></div><div class="provider-detail-actions"><button class="danger-outline" @click="removeProvider(selectedProvider)">删除物流商</button><button class="upload" @click="triggerTemplateUpload(selectedProvider.id)">批量上传模板</button></div></header><div class="provider-detail-body"><h4>渠道模板</h4><div class="provider-template-table"><div class="template-table-head"><span>渠道名称</span><span>Excel 模板</span><span>更新时间</span><span>操作</span></div><div v-for="template in selectedProvider.templates" :key="template.id" class="template-table-row"><b>{{ templateChannelName(template.name) }}</b><div class="template-file">{{ template.name }}</div><time>{{ template.updatedAt }}</time><div class="template-actions"><button @click="notify(`${template.name} 仅作为基础资料保存，暂未关联运费规则`)">查看</button><button class="delete-template" @click="removeTemplate(selectedProvider,template)">删除</button></div></div><div v-if="!selectedProvider.templates.length" class="template-table-empty">当前物流商尚未上传渠道模板</div></div><button class="template-dropzone" @dragover.prevent @drop.prevent="handleTemplateDrop" @click="triggerTemplateUpload(selectedProvider.id)"><i>⇧</i><span>拖拽 Excel 文件到这里，或<strong>点击继续上传</strong></span></button></div></section>
            </div>
        </section>
      </template>
      <template v-else-if="activeRule"><div class="area-head"><button @click="view='list'">‹ 返回运费规则</button><h2>{{ activeRule.name }}</h2></div><div class="area-actions"><input v-model="areaKeyword" placeholder="输入区域名称"><button class="search">搜索(S)</button><button class="green" @click="openAreaEditor()">添加</button><button class="blue" @click="notify('导入功能将在接数据库时启用')">导入区域规则</button><button class="blue" @click="areaAction('导出区域规则')">导出区域规则</button><button @click="areaAction('批量删除')">批量删除</button><button @click="areaAction('批量启用')">批量启用</button><button @click="areaAction('批量禁用')">批量禁用</button><button @click="areaAction('操作日志')">操作日志</button></div><div class="data-card scroll"><table><thead><tr><th></th><th>区域名称</th><th>时效天数</th><th>禁运商品</th><th>允许商品标记</th><th>状态</th><th>是否禁止普货</th><th>计费重量</th><th>计费价格</th><th>操作</th></tr></thead><tbody><tr v-for="entry in visibleAreaRows" :key="entry.index"><td><input type="checkbox"></td><td>{{ entry.area.areaName }}<small>{{ entry.area.countryCode }}</small></td><td>{{ entry.area.etaMinDays }} - {{ entry.area.etaMaxDays }}</td><td class="marks">{{ entry.area.prohibitedMarks || '-' }}</td><td>{{ entry.area.allowedMarks || '-' }}</td><td>启用</td><td>{{ entry.area.prohibitGeneralCargo?'是':'否' }}</td><td>{{ gramsFromKg(entry.area.weightFromKg) }} - {{ gramsFromKg(entry.area.weightToKg) }} g</td><td>¥{{ entry.area.pricePerKg }}/1000g + ¥{{ entry.area.registrationFee }}</td><td class="ops"><button @click="openAreaEditor(entry.index)">编辑</button><button @click="areaAction('导出')">导出</button><button @click="areaAction('设为禁用')">设为禁用</button><button @click="areaAction('复制')">复制</button><button @click="areaAction('删除')">删除</button></td></tr></tbody></table></div></template>
    </section>

    <div v-if="showRuleEditor" class="mask"><div class="modal rule-modal"><header>编辑运费规则<button @click="showRuleEditor=false">×</button></header><div class="form"><label>规则名称<input v-model="form.name"></label><label>英文名称<input v-model="form.englishName"></label><label>模板类型<select v-model="form.type"><option>专线</option><option>挂号</option><option>free</option></select></label><label>会员运费报价系数<input value="0.00"></label><label>是否发布<select v-model="form.published"><option>未发布</option><option>发布</option></select></label><label>状态<select v-model="form.status"><option>启用</option><option>禁用</option></select></label><label>物流商<input v-model="form.carrier"></label><label>渠道<input v-model="form.channel"></label><label>渠道编码<input v-model="form.channelCode"></label></div><footer><button class="blue" @click="saveRule">保存</button><button @click="showRuleEditor=false">取消</button></footer></div></div>
    <div v-if="showConditionEditor" class="mask"><div class="modal small"><header>编辑运费规则<button @click="showConditionEditor=false">×</button></header><label class="check"><input v-model="activeRule!.phoneRequired" type="checkbox"> 匹配是否需要电话</label><footer><button class="blue" @click="showConditionEditor=false;notify('保存成功')">保存</button><button @click="showConditionEditor=false">取消</button></footer></div></div>
    <div v-if="showAreaEditor" class="mask"><div class="modal area-modal"><header>编辑运费区域<button @click="showAreaEditor=false">×</button></header><div class="form"><label>区域名称<input v-model="areaForm.areaName"></label><label>国家简码<input v-model="areaForm.countryCode"></label><label>时效最早天数<input v-model.number="areaForm.etaMinDays" type="number"></label><label>时效最晚天数<input v-model.number="areaForm.etaMaxDays" type="number"></label><label>起始重量（g）<input v-model.number="areaForm.weightFromG" type="number" min="0" step="1"></label><label>截止重量（g）<input v-model.number="areaForm.weightToG" type="number" min="0" step="1"></label><label>每 1000g 运费（CNY）<input v-model.number="areaForm.pricePer1000G" type="number" min="0" step="0.01"></label><label>挂号费<input v-model.number="areaForm.registrationFee" type="number"></label></div><footer><button class="blue" @click="saveArea">保存</button><button @click="showAreaEditor=false">取消</button></footer></div></div>
    <div v-if="showProviderEditor" class="mask"><div class="modal small base-modal"><header>新增物流商<button @click="showProviderEditor=false">×</button></header><div class="simple-form"><label>物流商名称<input v-model="providerForm.name" placeholder="例如：燕文物流"></label><label>物流商编码<input v-model="providerForm.code" placeholder="例如：YANWEN" @input="providerForm.code=providerForm.code.toUpperCase()"></label></div><footer><button class="primary-orange" @click="saveProvider">保存物流商</button><button @click="showProviderEditor=false">取消</button></footer></div></div>
    <div v-if="toast" class="toast">{{ toast }}</div>
  </div>
</template>

<style scoped>
:global(body){margin:0}.erp{--cyan:#0699bd;--blue:#2e91c5;--line:#d7d7d7;min-height:100vh;background:#fff;color:#555;font:13px Arial,"Microsoft YaHei",sans-serif}.top{height:42px;display:flex;align-items:stretch;background:#0495b8;color:#fff;position:sticky;top:0;z-index:30}.logo{width:175px;display:grid;place-items:center;background:#078eae;font-weight:bold}.top nav{display:flex}.top nav a{min-width:58px;padding:0 10px;display:grid;place-items:center;border-left:1px solid #0788aa;font-weight:bold}.top nav a.active{background:#087f9d;border-bottom:3px solid #ffe31b}.tools{margin-left:auto;display:flex;align-items:center;padding:0 15px;white-space:nowrap}.sidebar{position:fixed;left:0;top:42px;bottom:0;width:177px;border-right:1px solid #ccc;background:#fafafa;overflow:auto}.sidebar h3{margin:0;padding:11px 8px;background:#eee;border-bottom:1px solid #d3d3d3;font-size:13px}.sidebar button{width:100%;height:34px;border:0;border-bottom:1px solid #e6e6e6;background:#fff;text-align:left;padding-left:15px;color:#666}.sidebar button i{font-size:8px;color:#bbb;margin-right:7px}.sidebar button.active{background:#079abe;color:#fff;font-weight:bold}.sidebar button.active i{color:#fff}.workspace{margin-left:177px;padding:0 21px 50px;min-width:900px}.crumb{height:31px;display:flex;align-items:center;background:#f1f1f1;margin:0 -21px 10px;padding:0 12px}.tab-title{display:inline-block;background:#069bc0;color:#fff;padding:9px 18px;border-radius:4px 4px 0 0;font-weight:bold}.cyan-line{height:3px;background:#079cbe}.tip{margin:6px 0 20px;padding:14px 8px;background:#d9f1f9;border:1px solid #c5e6f1}.filters{border:1px solid var(--line);padding:8px 20px 5px}.filters>div{display:flex;align-items:center;min-height:34px}.filters label{min-width:82px;font-weight:bold}.filters select{width:145px;height:30px;margin-right:36px;border:1px solid #ccc;border-radius:4px;background:linear-gradient(#fff,#eee);padding:0 10px}.filters input{width:295px;height:28px;border:1px solid #ccc;padding:0 10px}.filters button{height:27px;border:0;background:#fff}.filters button.chosen{background:#3b9bc6;color:#fff}.search{background:#338fc0!important;color:#fff!important;border:0!important;padding:0 15px}.data-card{margin-top:14px;border:1px solid var(--line)}.actions{height:49px;display:flex;align-items:center;padding:0 10px;border-bottom:1px solid var(--line)}button{cursor:pointer}.actions button,.area-actions button{height:30px;border:0;border-radius:3px;color:#fff;margin-right:4px;padding:0 11px;font-weight:bold}.green{background:#4fbd65!important}.blue{background:#43acd1!important;color:#fff;border:0}.yellow{background:#e6b84e!important}.teal{background:#42b8c5!important}.actions>div{margin-left:auto}.actions span{background:#3f93c5;color:#fff;padding:7px}.scroll{overflow:auto;max-height:610px}table{width:100%;border-collapse:collapse;table-layout:fixed}th{background:#eee;padding:11px 6px;border:1px solid #d5d5d5;color:#555}td{padding:8px 7px;border:1px solid #ddd;text-align:center;line-height:20px;word-break:break-word}th:first-child,td:first-child{width:20px}.type{display:inline-block;padding:2px 8px;background:#50b969;color:#fff;border-radius:3px}.unpublished{color:#b66060}.ops a{color:#4287aa;margin:0 2px;cursor:pointer}.ops{width:170px}.marks{font-size:11px}td small{display:block;color:#999}footer{display:flex;justify-content:flex-end;align-items:center;padding:8px;background:#fafafa}footer button{height:31px;border:1px solid #ddd;background:#fff;color:#4589aa}footer button.current{background:#2695be;color:#fff}footer span{margin-left:10px}footer select{height:27px}.area-head{display:flex;align-items:center;gap:15px;padding:15px 0}.area-head button{height:30px;border:1px solid #ccc;background:#fff}.area-actions{display:flex;align-items:center;padding:10px;border:1px solid #ddd}.area-actions input{height:28px;width:220px;border:1px solid #ccc;padding:0 8px;margin-right:4px}.area-actions button:not(.green):not(.blue):not(.search){background:#888}.mask{position:fixed;inset:0;z-index:80;display:grid;place-items:center;background:#0007}.modal{width:780px;background:#fff;border-radius:4px;box-shadow:0 10px 40px #0005}.modal.small{width:520px}.modal.area-modal{width:760px}.modal header{height:42px;display:flex;align-items:center;padding:0 15px;background:#f1f1f1;border-bottom:1px solid #ccc;font-weight:bold}.modal header button{margin-left:auto;border:0;background:none;font-size:22px}.form{display:grid;grid-template-columns:1fr 1fr;gap:14px;padding:22px}.form label{display:grid;grid-template-columns:120px 1fr;align-items:center}.form input,.form select{height:32px;border:1px solid #ccc;padding:0 8px}.modal footer{gap:6px;padding:12px 20px;border-top:1px solid #ddd}.modal footer button{padding:0 18px}.check{display:block;padding:35px}.toast{position:fixed;right:25px;bottom:25px;z-index:100;background:#333;color:#fff;padding:12px 20px;border-radius:4px}@media(max-width:1150px){.top nav a:nth-child(n+10){display:none}}
.ops button{border:0;background:transparent;color:#4287aa;margin:0 2px;padding:0;cursor:pointer;font:inherit}
.erp{min-height:100vh;background:#f4f6f8;color:#17232e;font-family:Inter,"PingFang SC","Microsoft YaHei",sans-serif}
.milano-topbar{height:68px;display:flex;align-items:center;padding:0 4vw;background:#fff;border-bottom:1px solid #e3e8ec;position:sticky;top:0;z-index:30}
.milano-brand{display:flex;align-items:center;gap:11px;margin-right:56px;color:#17232e;text-decoration:none}
.milano-brand>span{width:39px;height:39px;display:grid;place-items:center;border-radius:10px;background:#ff9910;font-size:21px;font-weight:950}
.milano-brand strong,.milano-brand small,.milano-user small{display:block}.milano-brand small{color:#9199a2;font-size:8px;letter-spacing:.18em}
.milano-topbar nav{display:flex;align-items:center;gap:31px;height:100%}.milano-topbar nav a{height:100%;display:flex;align-items:center;position:relative;color:#66717c;text-decoration:none;font-size:13px}.milano-topbar nav a.active{color:#17232e;font-weight:850}.milano-topbar nav a.active:after{content:"";position:absolute;inset:auto 0 0;height:3px;background:#ff9910}
.milano-user{display:flex;align-items:center;gap:10px;margin-left:auto;font-size:11px}.milano-user>span{width:35px;height:35px;display:grid;place-items:center;border-radius:50%;background:#1b2731;color:#fff}.milano-user small{color:#929ba4}
.workspace{width:min(1500px,94vw);min-width:0;margin:0 auto;padding:36px 0 70px}.milano-heading{display:flex;align-items:flex-end;justify-content:space-between;margin-bottom:24px}.milano-heading p{margin:0 0 8px;color:#dd7c00;font-size:10px;font-weight:900;letter-spacing:.2em}.milano-heading h1{margin:0 0 7px;font-size:30px}.milano-heading span{color:#75808a;font-size:12px}.tip{background:#fff7e9;border-color:#f2d7ad}.data-card,.filters{background:#fff}.scroll{max-height:none}
.workspace-switch{display:flex;gap:8px;margin:0 0 16px;padding:5px;background:#e9edf0;border-radius:10px}.workspace-switch button{min-width:180px;height:40px;padding:0 24px;border:0;border-radius:7px;background:transparent;color:#68747e;font-weight:750}.workspace-switch button.active{background:#fff;color:#17232e;box-shadow:0 3px 12px #24313d12}.base-settings{overflow:hidden;background:#fff;border:1px solid #e0e6ea;border-radius:12px;box-shadow:0 8px 28px #1e2c3810}.base-tabs{display:flex;height:54px;border-bottom:1px solid #e5eaed}.base-tabs button{min-width:220px;border:0;background:#fff;color:#586671;font-weight:800;position:relative}.base-tabs button.active{color:#e47d00}.base-tabs button.active:after{content:"";position:absolute;left:20px;right:20px;bottom:0;height:3px;background:#ff930f}.base-toolbar,.section-title{display:flex;align-items:center;justify-content:space-between;gap:20px}.base-toolbar{padding:22px 24px 12px}.base-toolbar h2,.section-title h2{margin:0 0 5px;font-size:18px;color:#17232e}.base-toolbar p,.section-title p{margin:0;color:#7a858f;font-size:11px}.outline-orange{height:38px;padding:0 16px;border:1px solid #ff9414;border-radius:7px;background:#fff;color:#d97900;font-weight:800}.hidden-file{display:none}.provider-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:16px;padding:12px 24px 26px}.provider-card{border:1px solid #dfe5e9;border-radius:10px;overflow:hidden}.provider-card>header{display:flex;align-items:center;gap:12px;padding:16px;border-bottom:1px solid #edf0f2}.provider-icon{width:42px;height:42px;display:grid;place-items:center;border-radius:50%;background:#fff0d9;color:#d97600;font-weight:900;font-size:16px}.provider-card h3{margin:0;color:#17232e;font-size:16px}.provider-card h3 span{display:inline-block;margin-left:6px;padding:3px 7px;border-radius:10px;background:#fff0d8;color:#d47600;font-size:9px}.provider-card header small{color:#8a949c}.provider-card .upload{height:34px;margin-left:auto;padding:0 13px;border:0;border-radius:6px;background:#ff9511;color:#fff;font-weight:800}.provider-card h4{margin:14px 16px 8px;font-size:11px}.template-list{padding:0 16px}.template-list>div{display:grid;grid-template-columns:minmax(140px,1.4fr) auto auto auto;gap:8px;align-items:center;padding:10px 0;border-top:1px solid #eef1f3;font-size:10px}.template-list b{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.template-list span{color:#199260}.template-list span.pending{color:#d38200}.template-list time{color:#89939b}.template-list button,.provider-card footer button{border:0;background:none;color:#247cb0}.provider-card>footer{justify-content:space-between;padding:11px 16px;border-top:1px solid #edf0f2;color:#8a949c}.empty-template{margin:0 16px 16px;padding:28px;text-align:center;background:#f7f9fa;color:#8b959d}.codes-layout{display:grid;grid-template-columns:.8fr 1.2fr;gap:16px;padding:22px}.codes-card{border:1px solid #dfe5e9;border-radius:10px;overflow:hidden}.section-title{padding:16px}.settings-table{table-layout:auto}.settings-table th{position:static;background:#f5f7f8;border:0;border-top:1px solid #e5e9ec}.settings-table td{height:38px;border:0;border-top:1px solid #edf0f2;text-align:left;padding:6px 14px}.settings-table input{box-sizing:border-box;width:100%;height:30px;border:1px solid #d8e0e5;border-radius:5px;padding:0 8px;background:#fff}.zone-filters{display:grid;grid-template-columns:repeat(3,1fr);gap:8px;padding:12px 16px;background:#f5f9fd;border-top:1px solid #e3e9ee}.zone-filters select,.simple-form input,.simple-form select{height:34px;border:1px solid #d7e0e6;border-radius:6px;background:#fff;padding:0 9px}.zone-note{margin:12px 16px 16px;padding:10px 12px;border:1px solid #f5d6ad;border-radius:6px;background:#fff8ef;color:#b46a09;font-size:10px}.danger-text{border:0;background:none;color:#d45445}.empty-row{text-align:center!important;color:#8b959d}.save-settings{display:flex;align-items:center;justify-content:flex-end;gap:18px;padding:15px 22px;border-top:1px solid #e3e8eb;background:#fafbfc;color:#7e8992;font-size:11px}.save-settings button,.primary-orange{height:38px;padding:0 24px;border:0;border-radius:6px;background:#ff9310;color:#fff;font-weight:850}.simple-form{display:grid;gap:14px;padding:22px}.simple-form label{display:grid;grid-template-columns:105px 1fr;align-items:center;color:#5f6d78}.base-modal footer{gap:7px}.base-modal footer .primary-orange{border:0;color:#fff}.provider-card footer{margin:0}
.provider-toolbar{display:flex;align-items:center;gap:12px}.provider-toolbar label{display:flex;align-items:center;gap:7px;width:260px;height:38px;box-sizing:border-box;padding:0 11px;border:1px solid #dbe2e7;border-radius:7px;color:#72808b}.provider-toolbar input{width:100%;border:0;outline:0;background:transparent;color:#26333d}.provider-manager{display:grid;grid-template-columns:320px minmax(0,1fr);gap:14px;padding:12px 24px 26px}.provider-list,.provider-detail{min-width:0;border:1px solid #dfe5e9;border-radius:10px;background:#fff;overflow:hidden}.provider-sort-hint{height:44px;display:flex;align-items:center;justify-content:space-between;padding:0 12px;border-bottom:1px solid #e5eaed;background:#fff}.provider-sort-hint span{font-weight:850}.provider-sort-hint small{color:#929da5}.provider-list-scroll{height:458px;padding:8px;overflow:auto;background:#f6f8fa}.provider-list-scroll>button{width:100%;min-height:54px;display:grid;grid-template-columns:14px 36px minmax(0,1fr) auto 12px;align-items:center;gap:8px;margin-bottom:7px;padding:7px 9px;border:1px solid #e0e6ea;border-radius:8px;background:#fff;box-shadow:0 2px 7px #2637440a;text-align:left;color:#26333d;transition:border-color .15s,box-shadow .15s,transform .15s,opacity .15s}.provider-list-scroll>button:hover{border-color:#f1b967;background:#fffaf2}.provider-list-scroll>button.active{border-color:#ffae42;box-shadow:inset 4px 0 #ff930f,0 4px 12px #bd690014;background:#fff4e4}.provider-list-scroll>button.dragging{opacity:.35}.provider-list-scroll>button.drag-over{border-color:#ff930f;box-shadow:0 -3px 0 #ff930f,0 5px 14px #bd690018;transform:translateY(2px)}.provider-list-scroll>button u{color:#aeb7be;font-size:13px;line-height:1;text-decoration:none;cursor:grab}.provider-list-scroll>button:active u{cursor:grabbing}.provider-list-scroll>button i{width:32px;height:32px;display:grid;place-items:center;border-radius:50%;background:#fff0d9;color:#d97600;font-style:normal;font-weight:900}.provider-list-scroll>button span{min-width:0;display:grid;gap:2px}.provider-list-scroll>button b{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.provider-list-scroll>button small{color:#9aa3aa;font-size:8px}.provider-list-scroll>button em{color:#84909a;font-size:10px;font-style:normal;white-space:nowrap}.provider-list-scroll>button.active em,.provider-list-scroll>button.active strong{color:#e17a00}.provider-list-scroll>button strong{color:#89959e;font-size:20px}.provider-list>footer{justify-content:center;height:38px;box-sizing:border-box;border-top:1px solid #e8ecef;color:#7f8a93;font-size:11px}.provider-empty{display:grid;place-items:center;height:160px;color:#929ca4}.provider-detail>header{display:flex;align-items:center;gap:12px;min-height:74px;padding:0 18px;border-bottom:1px solid #e8ecef}.provider-detail h3{margin:0 0 4px;font-size:17px}.provider-detail h3 span{display:inline-block;margin-left:7px;padding:3px 7px;border-radius:10px;background:#fff0d8;color:#d47600;font-size:9px}.provider-detail header small{color:#8a949c}.provider-detail-actions{display:flex;align-items:center;gap:8px;margin-left:auto}.provider-detail .upload{height:36px;padding:0 15px;border:0;border-radius:6px;background:#ff9511;color:#fff;font-weight:800}.danger-outline{height:36px;padding:0 13px;border:1px solid #e7a59d;border-radius:6px;background:#fff;color:#c94f40;font-weight:750}.danger-outline:hover{background:#fff3f1}.provider-detail-body{padding:18px}.provider-detail-body h4{margin:0 0 12px;font-size:14px}.provider-template-table{border:1px solid #dfe5e9;border-radius:8px;overflow-x:auto}.template-table-head,.template-table-row{min-width:900px;display:grid;grid-template-columns:minmax(165px,1.35fr) minmax(150px,1.15fr) 90px 78px 126px 170px;align-items:center;gap:12px;min-height:48px;padding:0 14px}.template-table-head{min-height:38px;background:#f5f7f8;color:#66737e;font-size:10px;font-weight:800}.template-table-row{border-top:1px solid #edf0f2;font-size:11px}.template-table-row>b{min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.template-table-row>b small,.matched-channel small{display:block;color:#939da5;font-size:8px;font-weight:500}.matched-channel{min-width:0}.matched-channel strong{display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.template-table-row code{color:#4a5862;font-weight:800}.template-table-row>span{width:max-content;padding:3px 8px;border-radius:10px;background:#e6f6eb;color:#198951;font-size:9px;font-weight:800}.template-table-row>span.pending{background:#fff0d8;color:#d57900}.template-table-row time{color:#65727c}.template-actions{display:flex;align-items:center;flex-wrap:wrap;gap:5px 9px}.template-actions button{padding:0;border:0;background:none;color:#247cb0;font-size:10px}.template-actions .move-template{color:#d87900;font-weight:800}.template-actions .delete-template{color:#cf5142}.template-table-empty{display:grid;place-items:center;height:104px;color:#929ca4}.template-dropzone{width:100%;height:130px;display:grid;place-items:center;align-content:center;gap:7px;margin-top:18px;border:1px dashed #ccd7df;border-radius:8px;background:#fbfcfd;color:#6f7d87}.template-dropzone:hover{border-color:#ffae45;background:#fffaf2}.template-dropzone i{font-size:28px;color:#9aa7b0;font-style:normal}.template-dropzone strong{margin-left:3px;color:#1f80b7}
.settings-table th:first-child,.settings-table td:first-child{width:auto}.codes-card:first-child .settings-table th:first-child,.codes-card:first-child .settings-table td:first-child{width:78px}.zone-card .settings-table th:first-child,.zone-card .settings-table td:first-child{width:42%}.workspace-switch button:focus,.base-tabs button:focus{outline:none}.workspace-switch button:focus-visible,.base-tabs button:focus-visible{box-shadow:0 0 0 3px #ff991033}
.country-region-card{margin:22px}.country-region-table th:nth-child(1){width:18%}.country-region-table th:nth-child(2){width:24%}.country-region-table th:nth-child(3){width:16%}.country-region-table th:nth-child(4){width:24%}.country-region-table th:nth-child(5){width:12%}.australia-country-row{background:#fff8ee}.zone-total{color:#d87900}.zone-toggle{border:0;background:none;color:#247cb0;font-weight:750}.australia-zone-row{background:#fffdf9}.australia-zone-row td:first-child{display:flex;align-items:center;gap:8px;padding-left:30px}.zone-branch{color:#e18a1b;font-size:16px}.fixed-zone{display:inline-block;padding:4px 8px;border-radius:10px;background:#fff0d8;color:#c76c00;font-size:9px;font-weight:750}.country-zone-note{margin:14px 16px 16px;padding:10px 12px;border:1px solid #f5d6ad;border-radius:6px;background:#fff8ef;color:#a9620a;font-size:10px}
.template-table-head,.template-table-row{min-width:680px;grid-template-columns:minmax(150px,1.2fr) minmax(220px,1.6fr) 130px 90px}.template-table-row>b,.template-file{min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.template-file{color:#687681}
@media(max-width:900px){.milano-topbar nav{display:none}.milano-brand{margin-right:0}.workspace{padding-top:22px}}
.country-region-card .country-region-table th:first-child,.country-region-card .country-region-table td:first-child{width:18%}
.country-region-panel{margin:22px;padding:20px;border:1px solid #dfe5e9;border-radius:10px;background:#fff}.country-region-panel>.section-title{padding:0 0 16px}.standard-country-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px}.country-area-card{display:grid;grid-template-columns:minmax(0,1fr) 110px auto;align-items:center;gap:14px;padding:16px;border:1px solid #e1e7eb;border-radius:9px;background:#fbfcfd}.country-area-card div,.australia-area-card header>div{display:grid;gap:3px}.country-area-card small,.australia-area-card small{color:#8a959d;font-size:9px}.country-area-card label,.australia-area-card header label{display:grid;gap:4px;color:#78858f;font-size:9px}.country-area-card input,.australia-area-card input{box-sizing:border-box;width:100%;height:32px;border:1px solid #d8e0e5;border-radius:6px;padding:0 9px;background:#fff}.country-area-card>span{padding:5px 9px;border-radius:12px;background:#eef3f6;color:#65747f;font-size:9px;white-space:nowrap}.australia-area-card{margin-top:14px;border:1px solid #f0b65f;border-radius:10px;background:#fffaf2;overflow:hidden}.australia-area-card>header{display:grid;grid-template-columns:minmax(160px,1fr) 110px auto auto;align-items:center;gap:16px;padding:16px 18px}.australia-area-card>header>span{padding:5px 10px;border-radius:12px;background:#fff0d8;color:#c96d00;font-size:9px;font-weight:800}.australia-zone-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:10px;padding:14px 18px 18px;border-top:1px solid #f4d8ad}.australia-zone-grid label{display:grid;grid-template-columns:1fr;gap:6px;padding:13px;border:1px solid #eadfcf;border-radius:8px;background:#fff}.australia-zone-grid label span{color:#8a959d;font-size:9px}.australia-area-card>p{margin:0;padding:11px 18px;border-top:1px solid #f4d8ad;color:#a86613;font-size:10px}.country-region-panel .zone-toggle{height:32px;padding:0 10px;border:1px solid #e8c48e;border-radius:6px;background:#fff;color:#b66a0b}.country-region-panel+.save-settings{margin-top:0}
.country-directory-toolbar{display:flex;align-items:center;justify-content:space-between;gap:18px}.country-directory-toolbar h2{margin:0 0 5px;color:#17232e}.country-directory-toolbar p{margin:0;color:#7b8790;font-size:11px}.country-directory-toolbar>label{display:flex;align-items:center;gap:7px;width:300px;height:38px;box-sizing:border-box;padding:0 11px;border:1px solid #dbe2e7;border-radius:7px;color:#73808a}.country-directory-toolbar>label input{width:100%;border:0;outline:0;background:transparent}.country-directory-table{margin-top:14px;border:1px solid #e0e6ea;border-radius:9px;overflow:hidden}.country-directory-table table{table-layout:fixed}.country-directory-table th{border:0;border-bottom:1px solid #e0e6ea;background:#f5f7f8}.country-directory-table th:nth-child(1){width:22%}.country-directory-table th:nth-child(2){width:28%}.country-directory-table th:nth-child(3){width:16%}.country-directory-table th:nth-child(4){width:20%}.country-directory-table th:nth-child(5){width:14%}.country-directory-table td,.country-directory-table td:first-child{width:auto;border:0;border-top:1px solid #edf0f2;padding:9px 14px;text-align:left}.country-directory-table input{box-sizing:border-box;width:100%;height:30px;border:1px solid #d8e0e5;border-radius:5px;padding:0 8px}.country-directory-table code{font-weight:800;color:#34434e}.country-unified{display:inline-block;padding:4px 8px;border-radius:10px;background:#eef3f6;color:#65747f;font-size:9px}.country-edit,.country-save{border:0;background:none;color:#247cb0;font-weight:750}.country-save{color:#e07800}.country-directory-pagination{display:flex;align-items:center;justify-content:flex-end;gap:10px;padding:13px 2px 0;color:#75818a}.country-directory-pagination>span{margin-right:auto}.country-directory-pagination select{height:30px;margin:0 5px;border:1px solid #d8e0e5;border-radius:5px;background:#fff}.country-directory-pagination button{height:30px;padding:0 11px;border:1px solid #d8e0e5;border-radius:5px;background:#fff;color:#53636f}.country-directory-pagination button:disabled{opacity:.45;cursor:not-allowed}
.country-directory-table th:nth-child(1){width:38%}.country-directory-table th:nth-child(2){width:18%}.country-directory-table th:nth-child(3){width:28%}.country-directory-table th:nth-child(4){width:16%}
.australia-area-card>header{grid-template-columns:minmax(160px,1fr) auto auto}
.australia-zone-item{display:grid;gap:6px;padding:13px;border:1px solid #eadfcf;border-radius:8px;background:#fff}.australia-zone-item span{color:#8a959d;font-size:9px}
.country-directory-table th:nth-child(1){width:55%}.country-directory-table th:nth-child(2){width:30%}.country-directory-table th:nth-child(3){width:15%}
@media(max-width:1100px){.provider-grid,.codes-layout{grid-template-columns:1fr}.template-list>div{grid-template-columns:1fr auto}.template-list time{display:none}.zone-filters{grid-template-columns:1fr}.provider-manager{grid-template-columns:250px minmax(0,1fr)}}
@media(max-width:1100px){.standard-country-grid{grid-template-columns:1fr}.australia-zone-grid{grid-template-columns:repeat(2,minmax(0,1fr))}}
@media(max-width:760px){.base-toolbar{align-items:flex-start;flex-direction:column}.provider-toolbar{width:100%}.provider-toolbar label{width:auto;flex:1}.provider-manager{grid-template-columns:1fr}.provider-list-scroll{height:250px}.provider-detail>header{flex-wrap:wrap;padding-block:12px}.provider-detail-actions{width:100%;margin-left:0}.provider-detail-actions .upload{margin-left:auto}}
</style>
