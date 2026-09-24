<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import QuotationMatrix from './QuotationMatrix.vue'
import QuotationTemplateDetails from './QuotationTemplateDetails.vue'
import type { QuotationCountrySummary, QuotationMatrixRow, QuotationPresetSelection } from './types'
import {
  QUOTATION_TEMPLATES_UPDATED_EVENT,
  copyQuotationTemplate,
  createQuotationTemplate,
  deleteQuotationTemplate,
  loadQuotationTemplates,
  updateQuotationTemplate,
  type QuotationPersonalTemplate,
  type QuotationTemplateOwner,
  type QuotationTemplateSelectionItem,
} from '@/data/quotationTemplates'

const props = withDefaults(defineProps<{
  unavailableReason?: (preset: QuotationPresetSelection, quantity?: number) => string
  active?: boolean
  ensureCountries?: (countries: string[]) => Promise<boolean>
  countries: QuotationCountrySummary[]
  quoteRowsForCountry: (country: string) => QuotationMatrixRow[]
  contextKey: string
  customQuantity: number
  adoptedCountry: string
  adoptedChannelKey?: string
  adoptedRule: string
  adoptedCarrier: string
  exchangeRate: number
  ownerName: string
  ownerAccount: string
  unitLabel?: string
  draftSelection?: QuotationPresetSelection[]
  draftTemplate?: { id: string; name: string } | null
  draftVersion?: number
}>(), { active: true })

const emit = defineEmits<{
  'update:customQuantity': [value: number]
  selectionChange: [rows: QuotationMatrixRow[]]
  templateChange: [template: { id: string; name: string } | null]
  adopt: [row: QuotationMatrixRow]
  copy: [rows: QuotationMatrixRow[]]
  quoteRegionChange: [payload: { country: string; region: string }]
}>()

const owner = computed<QuotationTemplateOwner>(() => ({
  name: props.ownerName,
  account: props.ownerAccount,
}))
const templates = ref<QuotationPersonalTemplate[]>([])
const selectedTemplateId = ref('')
const activeTemplateId = ref('')
const currentRows = ref<QuotationMatrixRow[]>([])
// This snapshot belongs to the editable rows. List refreshes must never advance it.
const editBase = ref<QuotationPersonalTemplate | null>(null)
const pendingUpdate = ref<{ base: QuotationPersonalTemplate; items: QuotationTemplateSelectionItem[] } | null>(null)
const selectionState = ref<'loading' | 'ready' | 'error'>('ready')
const savingTemplate = ref(false)
const creationSource = ref('模板报价清单')
const canSaveSelection = computed(() => selectionState.value === 'ready' && !savingTemplate.value && currentRows.value.length > 0)
const creationItems = computed(() => templateItems(currentRows.value))
const currentAvailableCount = computed(() => currentRows.value.filter(row => row.available !== false && [row.quote1, row.quote2, row.quote3, row.quoteCustom].some(value => typeof value === 'number' && Number.isFinite(value))).length)
const currentUnavailableCount = computed(() => currentRows.value.length - currentAvailableCount.value)
const presetSelection = ref<QuotationPresetSelection[]>([])
const presetVersion = ref(0)
const showManager = ref(false)
const expandedTemplateId = ref('')
const createName = ref('')
const createDescription = ref('')
const editingId = ref('')
const editingName = ref('')
const pendingDeleteId = ref('')
const pendingClear = ref(false)
const feedback = ref('')
let feedbackTimer = 0
let clearConfirmTimer = 0
let appliedDraftVersion = 0

const selectedTemplate = computed(() => templates.value.find(item => item.id === selectedTemplateId.value))
const activeTemplate = computed(() => templates.value.find(item => item.id === activeTemplateId.value))
const activeTemplateCountryCount = computed(() => new Set(activeTemplate.value?.items.map(item => item.country) || []).size)
const currentCountryCount = computed(() => new Set(currentRows.value.map(row => row.country)).size)
const updateBlockReason = computed(() => {
  if (!activeTemplate.value) return '请先应用需要更新的模板'
  if (selectedTemplateId.value !== activeTemplateId.value) return '选择的模板与当前应用不同，请先点击“一键应用”再编辑'
  if (!editBase.value || editBase.value.id !== activeTemplateId.value || editBase.value._version == null) return '请重新应用已保存模板后再编辑；恢复的临时清单不能直接覆盖模板'
  if (editBase.value._version !== activeTemplate.value._version) return '模板已被修改，当前临时清单可能过期。请先另存需要保留的清单，再重新应用最新模板'
  return ''
})
const canUpdateTemplate = computed(() => canSaveSelection.value && !updateBlockReason.value)
function itemSignature(item: QuotationTemplateSelectionItem) {
  return JSON.stringify([item.country, item.quoteRegion || '', item.countryCode, item.channelKey, item.ruleId, item.rule, item.carrier, item.transport, item.channelCode.toUpperCase()])
}
function itemsSignature(items: QuotationTemplateSelectionItem[]) { return items.map(itemSignature).sort().join('\n') }
function snapshot(template: QuotationPersonalTemplate): QuotationPersonalTemplate {
  return { ...template, items: template.items.map(item => ({ ...item })) }
}
const removedItems = computed(() => pendingUpdate.value?.base.items.filter(item => !pendingUpdate.value!.items.some(next => itemSignature(next) === itemSignature(item))) || [])
const addedItems = computed(() => pendingUpdate.value?.items.filter(item => !pendingUpdate.value!.base.items.some(old => itemSignature(old) === itemSignature(item))) || [])
const confirmationCurrent = computed(() => !!pendingUpdate.value && canUpdateTemplate.value
  && pendingUpdate.value.base.id === activeTemplateId.value
  && pendingUpdate.value.base._version === editBase.value?._version
  && itemsSignature(pendingUpdate.value.items) === itemsSignature(creationItems.value))

function notify(message: string) {
  feedback.value = message
  window.clearTimeout(feedbackTimer)
  feedbackTimer = window.setTimeout(() => { feedback.value = '' }, 3600)
}

function cancelClearConfirmation() {
  pendingClear.value = false
  window.clearTimeout(clearConfirmTimer)
}

async function refreshTemplates(preferredId = '') {
  templates.value = await loadQuotationTemplates(owner.value)
  const preferred = preferredId || selectedTemplateId.value
  selectedTemplateId.value = templates.value.some(item => item.id === preferred)
    ? preferred
    : templates.value[0]?.id || ''
  if (activeTemplateId.value && !templates.value.some(item => item.id === activeTemplateId.value)) {
    activeTemplateId.value = ''
    emit('templateChange', null)
  }
}

function templateItems(rows: QuotationMatrixRow[]): QuotationTemplateSelectionItem[] {
  return rows.map(row => ({
    country: row.country,
    quoteRegion: row.quoteRegion,
    countryCode: props.countries.find(country => country.name === row.country)?.code || '',
    channelKey: row.channelKey,
    ruleId: row.ruleId,
    rule: row.rule,
    carrier: row.carrier,
    transport: row.transport,
    channelCode: row.channelCode,
  }))
}

function applyTemplate(template = selectedTemplate.value) {
  if (!template) {
    notify('请先选择一个报价模板')
    return
  }
  selectedTemplateId.value = template.id
  activeTemplateId.value = template.id
  editBase.value = snapshot(template)
  pendingUpdate.value = null
  creationSource.value = '模板报价清单'
  selectionState.value = 'loading'
  cancelClearConfirmation()
  presetSelection.value = template.items.map(item => ({
    country: item.country,
    quoteRegion: item.quoteRegion,
    channelKey: item.channelKey,
    rule: item.rule,
    carrier: item.carrier,
    transport: item.transport,
  }))
  presetVersion.value += 1
  emit('templateChange', { id: template.id, name: template.name })
  notify(`已应用模板“${template.name}”，可在下方临时增删国家和渠道`)
  showManager.value = false
}

function handleSelectionChange(rows: QuotationMatrixRow[]) {
  if (pendingClear.value) cancelClearConfirmation()
  currentRows.value = rows
  emit('selectionChange', rows)
}


async function createFromCurrent() {
  if (selectionState.value !== 'ready' || savingTemplate.value) return
  const name = createName.value.trim()
  if (!currentRows.value.length) {
    notify('当前还没有选择渠道，请先在下方添加国家和渠道，再新建模板')
    return
  }
  if (!name) {
    createName.value = `${props.ownerName || '我的'}常用报价${templates.value.length + 1}`
    notify('已生成模板名称，可修改后再次点击“新建模板”')
    return
  }
  savingTemplate.value = true
  const savedPresetVersion = presetVersion.value
  try {
    const created = await createQuotationTemplate(owner.value, {
      name,
      description: createDescription.value.trim(),
      items: templateItems(currentRows.value),
    })
    createName.value = ''
    createDescription.value = ''
    templates.value = [created, ...templates.value.filter(template => template.id !== created.id)]
    // Saving must not replay an older snapshot over edits made while the request was in flight.
    selectedTemplateId.value = created.id
    if (presetVersion.value === savedPresetVersion) {
      activeTemplateId.value = created.id
      editBase.value = snapshot(created)
      emit('templateChange', { id: created.id, name: created.name })
    }
    showManager.value = true
    notify(`模板“${created.name}”已新建`)
  } catch (error) {
    notify(error instanceof Error ? error.message : '模板保存失败，请重试')
  } finally { savingTemplate.value = false }
}

function updateActiveFromCurrent() {
  if (!canUpdateTemplate.value || !editBase.value) return
  const items = templateItems(currentRows.value)
  if (itemsSignature(items) === itemsSignature(editBase.value.items)) { notify('渠道清单未变化，无需更新模板'); return }
  feedback.value = ''
  pendingUpdate.value = { base: snapshot(editBase.value), items }
}

async function confirmTemplateUpdate() {
  if (!confirmationCurrent.value || !pendingUpdate.value) return
  const { base: template, items } = pendingUpdate.value
  const ownerAtStart = owner.value.account
  savingTemplate.value = true
  try {
    const updated = await updateQuotationTemplate(owner.value, template.id, { items }, template._version, true)
    if (owner.value.account !== ownerAtStart) return
    if (!updated) {
      notify('模板已不存在，请刷新后重试')
      await refreshTemplates()
      return
    }
    templates.value = templates.value.map(item => item.id === updated.id ? updated : item)
    if (activeTemplateId.value === updated.id && editBase.value?._version === template._version) {
      editBase.value = snapshot(updated)
      emit('templateChange', { id: updated.id, name: updated.name })
    }
    pendingUpdate.value = null
    notify(`模板“${updated.name}”已按当前临时清单更新`)
  } catch (error) {
    notify(error instanceof Error ? error.message : '模板保存失败，请重试')
  } finally { savingTemplate.value = false }
}

function startFromSelection(rows: QuotationMatrixRow[], source: string) {
  if (!rows.length || savingTemplate.value) return
  cancelClearConfirmation()
  activeTemplateId.value = ''
  editBase.value = null
  pendingUpdate.value = null
  emit('templateChange', null)
  creationSource.value = source
  createName.value = ''
  createDescription.value = ''
  currentRows.value = rows.map(row => ({ ...row }))
  presetSelection.value = templateItems(currentRows.value)
  selectionState.value = 'loading'
  presetVersion.value += 1
  showManager.value = true
}
defineExpose({ startFromSelection })

function clearCurrentSelection() {
  if (!pendingClear.value) {
    pendingClear.value = true
    window.clearTimeout(clearConfirmTimer)
    clearConfirmTimer = window.setTimeout(() => { pendingClear.value = false }, 5000)
    notify('5秒内再次点击“确认清空”，将把本次报价的国家和渠道全部清为 0；已保存模板不会修改')
    return
  }

  cancelClearConfirmation()
  selectionState.value = 'loading'
  presetSelection.value = []
  presetVersion.value += 1
  notify(`已清空本次报价应用清单；模板“${activeTemplate.value?.name || ''}”未修改，可随时恢复`)
}

function startRename(template: QuotationPersonalTemplate) {
  editingId.value = template.id
  editingName.value = template.name
  pendingDeleteId.value = ''
}

async function saveRename(template: QuotationPersonalTemplate) {
  const name = editingName.value.trim()
  if (!name) {
    notify('模板名称不能为空')
    return
  }
  const updated = await updateQuotationTemplate(owner.value, template.id, { name }, template._version)
  if (updated && editBase.value?.id === template.id && editBase.value._version === template._version) editBase.value = snapshot(updated)
  editingId.value = ''
  editingName.value = ''
  await refreshTemplates(updated?.id || template.id)
  if (activeTemplateId.value === template.id && updated) emit('templateChange', { id: updated.id, name: updated.name })
  notify(`模板已重命名为“${name}”`)
}

async function copyTemplate(template: QuotationPersonalTemplate) {
  const copied = await copyQuotationTemplate(owner.value, template.id)
  if (!copied) {
    notify('模板复制失败，请刷新后重试')
    return
  }
  await refreshTemplates(copied.id)
  notify(`已复制为“${copied.name}”`)
}

async function removeTemplate(template: QuotationPersonalTemplate) {
  if (pendingDeleteId.value !== template.id) {
    pendingDeleteId.value = template.id
    notify(`再次点击“确认删除”将删除模板“${template.name}”`)
    return
  }
  const wasActive = activeTemplateId.value === template.id
  await deleteQuotationTemplate(owner.value, template.id)
  pendingDeleteId.value = ''
  await refreshTemplates()
  if (wasActive) {
    activeTemplateId.value = ''
    emit('templateChange', null)
  }
  notify(`模板“${template.name}”已删除；当前临时报价清单仍保留`)
}

function onTemplatesUpdated() {
  void refreshTemplates()
}

watch(() => [props.ownerName, props.ownerAccount], () => {
  editBase.value = null
  pendingUpdate.value = null
  expandedTemplateId.value = ''
  cancelClearConfirmation()
  activeTemplateId.value = ''
  presetSelection.value = []
  currentRows.value = []
  emit('templateChange', null)
  void refreshTemplates()
}, { immediate: true })

watch(() => props.draftVersion || 0, version => {
  if (!version || version === appliedDraftVersion) return
  appliedDraftVersion = version
  cancelClearConfirmation()
  const draftTemplate = props.draftTemplate
  selectedTemplateId.value = draftTemplate?.id || ''
  activeTemplateId.value = draftTemplate?.id || ''
  editBase.value = null
  pendingUpdate.value = null
  presetSelection.value = (props.draftSelection || []).map(item => ({ ...item }))
  selectionState.value = 'loading'
  presetVersion.value += 1
  emit('templateChange', draftTemplate ? { ...draftTemplate } : null)
}, { immediate: true })

onMounted(() => window.addEventListener(QUOTATION_TEMPLATES_UPDATED_EVENT, onTemplatesUpdated))
onBeforeUnmount(() => {
  window.removeEventListener(QUOTATION_TEMPLATES_UPDATED_EVENT, onTemplatesUpdated)
  window.clearTimeout(feedbackTimer)
  window.clearTimeout(clearConfirmTimer)
})

function formatTime(value: string) {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false })
}
</script>

<template>
  <section class="template-workbench">
    <header class="template-toolbar">
      <div class="template-intro">
        <p>MODE C · PERSONAL QUOTATION TEMPLATE</p>
        <h2>我的报价模板</h2>
        <span>按业务员账号独立保存常用国家与渠道；应用后可临时调整，不会自动改动原模板。</span>
      </div>
      <div class="template-actions">
        <label>
          <span>选择个人模板</span>
          <select v-model="selectedTemplateId" aria-label="选择个人报价模板">
            <option value="">{{ templates.length ? '请选择模板' : '暂未创建模板' }}</option>
            <option v-for="template in templates" :key="template.id" :value="template.id">
              {{ template.name }} · {{ new Set(template.items.map(item => item.country)).size }}国 / {{ template.items.length }}渠道
            </option>
          </select>
        </label>
        <button class="apply" :disabled="!selectedTemplate" @click="applyTemplate()">⚡ 一键应用</button>
        <button @click="showManager = true">⚙ 管理我的模板</button>
      </div>
    </header>

    <div class="template-status">
      <template v-if="activeTemplate">
        <div class="active-template">
          <i>✓</i>
          <span><small>当前已应用</small><b>{{ activeTemplate.name }}</b></span>
          <em>模板已保存：{{ activeTemplateCountryCount }} 个国家 · {{ activeTemplate.items.length }} 条渠道</em>
        </div>
        <p v-if="currentUnavailableCount" class="missing-warning">⚠ 当前商品或物流属性下有 {{ currentUnavailableCount }} 条模板渠道不可用，已保留并标注原因；其余 {{ currentAvailableCount }} 条已正常匹配。</p>
        <p v-else-if="!currentRows.length" class="cleared-note">本次应用清单为 0 个国家 · 0 条渠道；已保存模板未修改，可随时恢复。</p>
        <p v-else class="matched-note">✓ {{ currentAvailableCount }} 条模板渠道可用；下方增删仅对本次报价生效。</p>
        <div class="status-actions">
          <button @click="applyTemplate(activeTemplate)">恢复模板已保存清单</button>
          <button class="clear" :class="{ confirming: pendingClear }" @click="clearCurrentSelection">{{ pendingClear ? '确认清空清单' : '清空本次清单' }}</button>
          <button class="update" :disabled="!canUpdateTemplate" @click="updateActiveFromCurrent">更新模板“{{ activeTemplate.name }}”</button>
        </div>
      </template>
      <template v-else>
        <div class="empty-template"><i>☆</i><span><b>尚未应用个人模板</b><small>可先在下方选择国家与渠道，再从当前选择新建模板。</small></span></div>
        <button @click="showManager = true">＋ 从当前选择新建模板</button>
      </template>
    </div>

    <p v-if="activeTemplate && updateBlockReason" class="template-conflict" role="status">{{ updateBlockReason }}</p>

    <div v-if="pendingUpdate" class="manager-mask update-mask" @click.self="!savingTemplate && (pendingUpdate = null)">
      <section class="update-confirmation" role="dialog" aria-modal="true" aria-label="确认更新报价模板">
        <h2>确认覆盖模板“{{ pendingUpdate.base.name }}”</h2>
        <p>以下变更会保存到此模板，影响今后应用它的新报价。</p>
        <div class="update-diff">
          <section><h3>移除的渠道（{{ removedItems.length }}）</h3><QuotationTemplateDetails :items="removedItems" /></section>
          <section><h3>新增的渠道（{{ addedItems.length }}）</h3><QuotationTemplateDetails :items="addedItems" /></section>
        </div>
        <p v-if="!confirmationCurrent" class="template-conflict" role="alert">{{ updateBlockReason || '清单或应用目标已变化，请取消并重新核对' }}</p>
        <p v-if="feedback" role="status">{{ feedback }}</p>
        <footer><button :disabled="savingTemplate" @click="pendingUpdate = null">取消</button><button :disabled="!confirmationCurrent" @click="confirmTemplateUpdate">{{ savingTemplate ? '保存中…' : '确认保存模板变更' }}</button></footer>
      </section>
    </div>

    <QuotationMatrix
      variant="template"
      :active="active"
      :countries="countries" :ensure-countries="ensureCountries"
      :quote-rows-for-country="quoteRowsForCountry" :unavailable-reason="unavailableReason"
      :context-key="contextKey"
      :custom-quantity="customQuantity"
      :adopted-country="adoptedCountry"
      :adopted-rule="adoptedRule" :adopted-channel-key="adoptedChannelKey"
      :adopted-carrier="adoptedCarrier"
      :exchange-rate="exchangeRate"
      :unit-label="unitLabel"
      :preset-selection="presetSelection"
      :preset-version="presetVersion"
      @update:custom-quantity="$emit('update:customQuantity', $event)"
      @selection-change="handleSelectionChange"
      @preset-state-change="selectionState = $event"

      @quote-region-change="$emit('quoteRegionChange', $event)"
      @adopt="$emit('adopt', $event)"
      @copy="$emit('copy', $event)"
    />

    <div v-if="showManager" class="manager-mask" @click.self="showManager = false">
      <section class="template-manager" role="dialog" aria-modal="true" aria-label="管理我的报价模板">
        <header>
          <div><p>PERSONAL TEMPLATE MANAGER</p><h2>管理我的报价模板</h2><span>{{ ownerName }}（{{ ownerAccount }}）的模板仅本人可见</span></div>
          <button aria-label="关闭" @click="showManager = false">×</button>
        </header>

        <div class="create-template">
          <div><b>从{{ creationSource }}新建</b><span>当前 {{ currentCountryCount }} 个国家 · {{ currentRows.length }} 条渠道</span></div>
          <label>模板名称<input v-model="createName" maxlength="40" placeholder="例如：美英加澳常用报价"></label>
          <label>说明（选填）<input v-model="createDescription" maxlength="80" placeholder="例如：张三日常新品报价"></label>
          <button :disabled="!canSaveSelection" @click="createFromCurrent">{{ savingTemplate ? '保存中…' : '＋ 新建模板' }}</button>
        </div>

        <div class="manager-list">
          <div class="creation-preview">
            <b>即将保存的渠道 · {{ creationSource }}</b>
            <p v-if="selectionState === 'loading'">正在加载所选清单，完成后才能保存。</p>
            <p v-else-if="selectionState === 'error'">清单加载失败，请关闭此窗口后重新应用模板或重新选择清单。</p>
            <template v-else>
              <p>保存以下国家、区域和渠道。要保存常用或指定模式的选择，请使用该模式下的“将当前清单存为模板”。</p>
              <QuotationTemplateDetails :items="creationItems" />
            </template>
          </div>
          <article v-for="template in templates" :key="template.id" :class="{ active: template.id === activeTemplateId }">
            <div class="template-name">
              <template v-if="editingId === template.id">
                <input v-model="editingName" maxlength="40" @keyup.enter="saveRename(template)">
                <button @click="saveRename(template)">保存</button>
                <button @click="editingId = ''">取消</button>
              </template>
              <template v-else>
                <b>{{ template.name }}</b><em v-if="template.id === activeTemplateId">当前应用</em>
                <span>{{ new Set(template.items.map(item => item.country)).size }} 个国家 · {{ template.items.length }} 条渠道</span>
                <small>{{ template.description || '暂无说明' }} · 更新于 {{ formatTime(template.updatedAt) }}</small>
              </template>
            </div>
            <div class="template-country-tags">
              <span v-for="country in [...new Set(template.items.map(item => item.country))]" :key="country">
                {{ country }} {{ template.items.filter(item => item.country === country).length }}条
              </span>
            </div>
            <div class="manager-actions">
              <button
                class="details-toggle"
                :aria-expanded="expandedTemplateId === template.id"
                :aria-controls="`template-details-${template.id}`"
                @click="expandedTemplateId = expandedTemplateId === template.id ? '' : template.id"
              >{{ expandedTemplateId === template.id ? '收起明细' : '明细' }}</button>
              <button class="use" @click="applyTemplate(template)">一键应用</button>
              <button @click="startRename(template)">重命名</button>
              <button @click="copyTemplate(template)">复制</button>
              <button class="danger" @click="removeTemplate(template)">{{ pendingDeleteId === template.id ? '确认删除' : '删除' }}</button>
            </div>
            <QuotationTemplateDetails
              v-if="expandedTemplateId === template.id"
              :id="`template-details-${template.id}`"
              class="template-details"
              :items="template.items"
            />
          </article>
          <div v-if="!templates.length" class="manager-empty"><i>☆</i><b>还没有个人报价模板</b><span>先在下方选择需要长期复用的国家和渠道，再回到这里新建。</span></div>
        </div>

        <footer>
          <p v-if="feedback">{{ feedback }}</p>
          <span v-else>模板以渠道稳定标识保存，价格会按每次报价的商品、重量与客户等级重新计算。</span>
          <button @click="showManager = false">完成</button>
        </footer>
      </section>
    </div>

    <Transition name="feedback"><div v-if="feedback && !showManager" class="template-feedback">{{ feedback }}</div></Transition>
  </section>
</template>

<style scoped>
.template-conflict{margin:0;padding:10px 14px;border:1px solid #e6b467;border-radius:8px;background:#fff6e6;color:#8c5100;font-size:12px;line-height:1.6}.update-mask{z-index:145}.update-confirmation{width:min(960px,100%);max-height:90vh;overflow:auto;padding:22px;box-sizing:border-box;border-radius:12px;background:#fff}.update-confirmation h2{margin:0;font-size:18px}.update-confirmation p{font-size:12px;line-height:1.6}.update-diff{display:grid;grid-template-columns:1fr 1fr;gap:16px;margin:16px 0}.update-diff>section{min-width:0}.update-diff h3{font-size:13px}.update-confirmation footer{display:flex;justify-content:flex-end;gap:10px;margin-top:18px}.update-confirmation button{padding:9px 14px;border:1px solid #d8e1e6;border-radius:6px;background:#fff;cursor:pointer}.update-confirmation button:last-child{background:#ff9700;border-color:#ff9700}.update-confirmation button:disabled{opacity:.45;cursor:not-allowed}@media(max-width:680px){.update-diff{grid-template-columns:1fr}}
.template-workbench{position:relative;display:grid;gap:12px;color:#17232d}.template-toolbar{display:flex;align-items:center;justify-content:space-between;gap:20px;padding:18px 21px;border:1px solid #dfe6eb;border-radius:12px;background:#fff;box-shadow:0 10px 28px rgba(20,34,45,.05)}.template-intro p,.template-manager>header p{margin:0 0 4px;color:#d97800;font-size:9px;font-weight:900;letter-spacing:.15em}.template-intro h2,.template-manager>header h2{margin:0 0 4px;font-size:19px}.template-intro span,.template-manager>header span{color:#7d8992;font-size:10px}.template-actions{display:flex;align-items:flex-end;gap:8px}.template-actions label{display:grid;gap:5px;color:#69757f;font-size:9px}.template-actions select{width:270px;height:38px;padding:0 10px;border:1px solid #d8e1e6;border-radius:7px;background:#fff;color:#26343e;font-size:10px}.template-actions button,.template-status button{height:38px;padding:0 13px;border:1px solid #d9e1e6;border-radius:7px;background:#fff;color:#4f5e69;font-size:9px;font-weight:850;cursor:pointer}.template-actions .apply{border-color:#ff9700;background:#ff9700;color:#17232d}.template-actions button:disabled,.template-status button:disabled,.create-template button:disabled{opacity:.42;cursor:not-allowed}.template-status{display:flex;align-items:center;gap:13px;min-height:58px;padding:10px 16px;border:1px solid #dfe6eb;border-left:4px solid #ff9700;border-radius:10px;background:#fff}.active-template,.empty-template{display:flex;align-items:center;gap:9px}.active-template>i,.empty-template>i{width:30px;height:30px;display:grid;place-items:center;border-radius:50%;background:#fff0d6;color:#c76b00;font-style:normal;font-weight:900}.active-template>span,.empty-template>span{display:grid;gap:2px}.active-template small,.empty-template small{color:#86929b;font-size:8px}.active-template b,.empty-template b{font-size:11px}.active-template em{padding:4px 8px;border-radius:12px;background:#eff4f6;color:#65747e;font-size:8px;font-style:normal}.template-status>p{margin:0;font-size:9px}.matched-note{color:#27845a}.missing-warning{padding:7px 9px;border-radius:6px;background:#fff1dd;color:#a35b00}.status-actions{display:flex;gap:7px;margin-left:auto}.status-actions .update{border-color:#e7a13b;color:#ae6100}.template-status>.empty-template+button{margin-left:auto;border-color:#e7a13b;color:#ae6100}.manager-mask{position:fixed;z-index:135;inset:0;display:grid;place-items:center;padding:22px;background:rgba(17,27,36,.5);backdrop-filter:blur(3px)}.template-manager{display:grid;grid-template-rows:auto auto minmax(160px,1fr) auto;width:min(980px,95vw);max-height:min(760px,92vh);overflow:hidden;border-radius:13px;background:#f7f9fb;box-shadow:0 28px 80px rgba(8,18,27,.35)}.template-manager>header{display:flex;align-items:flex-start;justify-content:space-between;padding:20px 22px;border-bottom:1px solid #e3e9ed;background:#fff}.template-manager>header>button{border:0;background:none;color:#596873;font-size:24px;cursor:pointer}.create-template{display:grid;grid-template-columns:minmax(190px,1fr) 1fr 1fr auto;align-items:end;gap:10px;padding:15px 20px;border-bottom:1px solid #e2e8ec;background:#fffaf1}.create-template>div{display:grid;gap:3px}.create-template>div b{font-size:11px}.create-template>div span{color:#73818c;font-size:9px}.create-template label{display:grid;gap:5px;color:#69757f;font-size:8px}.create-template input{height:35px;box-sizing:border-box;padding:0 9px;border:1px solid #d8e0e5;border-radius:6px;outline:0}.create-template input:focus{border-color:#f1a239;box-shadow:0 0 0 3px rgba(255,151,0,.1)}.create-template button{height:35px;padding:0 13px;border:0;border-radius:6px;background:#ff9700;color:#17232d;font-size:9px;font-weight:850}.manager-list{display:grid;align-content:start;gap:9px;padding:14px 18px;overflow:auto}.manager-list article{display:grid;grid-template-columns:minmax(230px,1.1fr) minmax(260px,1.4fr) auto;align-items:center;gap:14px;padding:12px 14px;border:1px solid #dce4e9;border-radius:9px;background:#fff}.manager-list article.active{border-color:#f3a638;box-shadow:inset 3px 0 #ff9700}.template-name{display:grid;grid-template-columns:1fr auto;align-items:center;gap:4px 7px;min-width:0}.template-name>b{overflow:hidden;font-size:12px;text-overflow:ellipsis;white-space:nowrap}.template-name>em{padding:3px 6px;border-radius:9px;background:#e9f7ef;color:#238354;font-size:7px;font-style:normal}.template-name>span,.template-name>small{grid-column:1/-1;color:#65747e;font-size:8px}.template-name>small{color:#929ca3}.template-name>input{height:30px;padding:0 8px;border:1px solid #f0a33a;border-radius:5px}.template-name>button{height:29px;border:0;border-radius:5px;background:#17232d;color:#fff;font-size:8px}.template-country-tags{display:flex;flex-wrap:wrap;gap:5px}.template-country-tags span{padding:5px 7px;border-radius:12px;background:#eef3f6;color:#536672;font-size:8px}.manager-actions{display:flex;justify-content:flex-end;gap:5px}.manager-actions button{height:31px;padding:0 8px;border:1px solid #dce3e7;border-radius:5px;background:#fff;color:#586670;font-size:8px;font-weight:800}.manager-actions .use{border-color:#f2a237;background:#fff6e7;color:#ad6200}.manager-actions .danger{border-color:#f0d3cf;color:#b74b3c}.manager-empty{display:grid;place-items:center;gap:6px;padding:55px;color:#83909a}.manager-empty i{font-size:30px;font-style:normal}.manager-empty b{color:#3a4852}.manager-empty span{font-size:9px}.template-manager>footer{display:flex;align-items:center;gap:12px;min-height:53px;padding:0 20px;border-top:1px solid #e1e7eb;background:#fff;color:#6e7b85;font-size:9px}.template-manager>footer p{margin:0;color:#a25b00;font-weight:800}.template-manager>footer button{height:33px;margin-left:auto;padding:0 17px;border:0;border-radius:6px;background:#17232d;color:#fff;font-size:9px;font-weight:800}.template-feedback{position:fixed;right:24px;bottom:24px;z-index:150;max-width:380px;padding:11px 15px;border-radius:8px;background:#17232d;color:#fff;box-shadow:0 12px 30px rgba(0,0,0,.2);font-size:10px}.feedback-enter-active,.feedback-leave-active{transition:.2s}.feedback-enter-from,.feedback-leave-to{opacity:0;transform:translateY(7px)}@media(max-width:1050px){.template-toolbar{align-items:flex-start;flex-direction:column}.template-actions{width:100%;flex-wrap:wrap}.template-actions label{flex:1}.template-actions select{width:100%}.create-template{grid-template-columns:1fr 1fr}.create-template>div{grid-column:1/-1}.manager-list article{grid-template-columns:1fr}.manager-actions{justify-content:flex-start}}@media(max-width:680px){.template-actions{align-items:stretch;flex-direction:column}.template-status{align-items:flex-start;flex-wrap:wrap}.active-template{flex-wrap:wrap}.status-actions{width:100%;margin-left:0}.status-actions button{flex:1}.template-status>.empty-template+button{width:100%;margin-left:0}.create-template{grid-template-columns:1fr}.create-template>div{grid-column:auto}.template-manager{max-height:94vh}.manager-actions{flex-wrap:wrap}.manager-actions button{flex:1}}
.status-actions .clear{border-color:#eccdc8;color:#a5483a}
.status-actions .clear.confirming{border-color:#c95747;background:#fff2f0;color:#a23124}
.cleared-note{padding:7px 9px;border-radius:6px;background:#f3f6f8;color:#596a76}
.template-details{grid-column:1/-1;min-width:0}
.creation-preview{padding:12px 14px;border:1px solid #f3c27e;border-radius:9px;background:#fffaf1;font-size:12px}.creation-preview>p{color:#65747e;font-size:11px;line-height:1.6}.creation-preview .details-panel{max-height:240px;overflow:auto}
.manager-actions button{white-space:nowrap;cursor:pointer}
.manager-actions .details-toggle{border-color:#cad8e2;color:#365b73;background:#f3f7fa}
.manager-actions .details-toggle[aria-expanded="true"]{border-color:#64869c;background:#eaf2f7}
@media(max-width:680px){.status-actions{display:grid;grid-template-columns:repeat(2,minmax(0,1fr))}.status-actions button{height:auto;min-height:38px;padding:7px 9px}.status-actions .update{grid-column:1/-1}}
</style>
