import { readFileSync } from 'node:fs'
import ts from 'typescript'
import { computed, effectScope, nextTick, reactive, ref, watchEffect } from 'vue'
import { expect, it, vi } from 'vitest'
import { isBundleQuotation } from '@/data/quotationModes'
import type { QuotationMatrixRow } from '@/components/quotation/types'

const source = readFileSync(new URL('./QuotationSystemView.vue', import.meta.url), 'utf8').split('<script setup lang="ts">')[1]!.split('</script>')[0]!
const ast = ts.createSourceFile('view.ts', source, ts.ScriptTarget.Latest, true)
const declaration = ast.statements.find(n => ts.isFunctionDeclaration(n) && n.name?.text === 'syncMissingPrimaryChannel')!
const js = ts.transpile(declaration.getText(ast), { target: ts.ScriptTarget.ES2022 })
const row = (key = '581::云速递::PURE', patch: Partial<QuotationMatrixRow> = {}) => ({
  country: '美国', rule: '全球专线敏感', carrier: '云速递', channelKey: key, quoteRegion: '全国统一',
  quote1: 26.1, taxConfigured: true, available: true, freight: 1, ...patch,
} as QuotationMatrixRow)

function harness() {
  const product = reactive({ sku: 'TC2602577', country: '中国香港', rule: '', channel: '', selectedChannelKey: '',
    quantity: 1, freight: 0, manualFreight: false, status: '', logisticsAttribute: '纯电' })
  const template = ref<QuotationMatrixRow[]>([]), common = ref<QuotationMatrixRow[]>([])
  const quoteMatrixMode = ref('template')
  const state = {
    products: ref([product]), draftReady: ref(true), draftNeedsQuery: ref(false),
    fobActive: ref(false), quotationHasInputs: () => true, productQueryBusy: ref(false), syncRefreshing: ref(false),
    syncPending: ref(''), syncError: ref(''), recoveryRestoring: ref(false), recoveryPending: ref<unknown>(null),
    savingQuotation: ref(false), logisticsLoadState: ref('ready'), logisticsRebuilding: ref(false), countryLoads: ref(0),
    countryLoadError: ref(''), financeSettingsAreHydrated: () => true, purchaseTaxBlockReason: ref(''),
    specialPackagingError: ref(''), commissionError: ref(''), quoteMatrixMode, templateSelectionState: ref('ready'),
    commonSelectionState: ref('ready'), quoteMode: ref('single'), isBundleQuotation,
    savedQuoteRows: computed(() => quoteMatrixMode.value === 'template' ? template.value : common.value),
    financePolicies: ref([]), financeAllowedChannelKeys: () => new Set(['581::云速递::PURE', 'other']),
    quantityCostBreakdown: vi.fn((_p: unknown, _r: string, quantity: number) => quantity <= 3
      ? { freight: 63.9 * quantity, quoteUsd: 26.1 * quantity, tax: { configured: true } } : null),
    selectedQuoteRegions: ref<Record<string, string>>({}),
  }
  const sync = new Function('state', `with(state){${js};return syncMissingPrimaryChannel}`)(state) as () => void
  const scope = effectScope()
  scope.run(() => watchEffect(sync, { flush: 'post' }))
  return { state, product, template, common, scope, sync }
}

it('repairs a missing primary after delayed template countries finish, using fresh current-quantity freight', async () => {
  const h = harness()
  try {
    h.state.countryLoads.value = 1; h.state.templateSelectionState.value = 'loading'
    h.template.value = [row()]
    await nextTick(); expect(h.product.rule).toBe('')
    h.state.countryLoads.value = 0
    await nextTick(); expect(h.product.rule).toBe('')
    h.state.templateSelectionState.value = 'ready'
    await nextTick()
    expect(h.product).toMatchObject({ country: '美国', rule: '全球专线敏感', selectedChannelKey: '581::云速递::PURE', freight: 63.9 })
    expect(h.state.selectedQuoteRegions.value).toEqual({ 美国: '全国统一' })
    expect(h.template.value).toEqual([row()])
    // After repair the effect becomes idle; unrelated quote changes never switch it.
    h.state.quantityCostBreakdown.mockClear(); h.template.value = [row('other')]
    await nextTick(); expect(h.product.selectedChannelKey).toBe('581::云速递::PURE')
    expect(h.state.quantityCostBreakdown).not.toHaveBeenCalled()
  } finally { h.scope.stop() }
})

it('uses only the active matrix and waits for its selection state, including quick mode', async () => {
  const h = harness()
  try {
    h.common.value = [row()]; await nextTick(); expect(h.product.rule).toBe('')
    h.state.commonSelectionState.value = 'loading'; h.state.quoteMatrixMode.value = 'common'
    await nextTick(); expect(h.product.rule).toBe('')
    h.state.commonSelectionState.value = 'ready'; await nextTick()
    expect(h.product.rule).toBe('全球专线敏感')
  } finally { h.scope.stop() }
})

it.each([
  ['draftReady', false], ['draftNeedsQuery', true], ['fobActive', true], ['productQueryBusy', true],
  ['syncRefreshing', true], ['syncPending', '资料更新'], ['syncError', '核验失败'], ['recoveryRestoring', true],
  ['recoveryPending', {}], ['savingQuotation', true], ['logisticsLoadState', 'stale'], ['logisticsLoadState', 'error'],
  ['logisticsLoadState', 'loading'], ['logisticsRebuilding', true], ['countryLoads', 1], ['countryLoadError', '失败'],
  ['purchaseTaxBlockReason', '票点待确认'], ['specialPackagingError', '包装输入错误'], ['commissionError', '佣金输入错误'],
  ['templateSelectionState', 'error'],
])('does not adopt unverified or incomplete input: %s=%j', async (name, value) => {
  const h = harness()
  try {
    ;(h.state[name as keyof typeof h.state] as { value: unknown }).value = value
    h.template.value = [row()]; await nextTick()
    expect(h.product.rule).toBe(''); expect(h.state.quantityCostBreakdown).not.toHaveBeenCalled()
  } finally { h.scope.stop() }
})

it('keeps an existing manual choice and replaces a cleared retired primary from the verified selection', async () => {
  const h = harness()
  try {
    Object.assign(h.product, { country: '澳大利亚', rule: '人工首选', selectedChannelKey: 'manual', freight: 88 })
    h.template.value = [row()]; await nextTick()
    expect(h.product.rule).toBe('人工首选'); expect(h.product.freight).toBe(88)
    h.product.rule = ''; h.product.selectedChannelKey = ''
    h.template.value = [row('retired', { available: false, quote1: null }), row()]
    await nextTick(); expect(h.product.selectedChannelKey).toBe('581::云速递::PURE')
    expect(h.template.value[0]).toMatchObject({ available: false, quote1: null })
  } finally { h.scope.stop() }
})

it('skips unavailable, untaxed and unauthorized identities, even when display prices exist', async () => {
  const h = harness()
  try {
    h.template.value = [row('retired'), row('other', { available: false }), row('other', { taxConfigured: false }),
      row('other', { quote1: null }), row('other', { quote1: NaN }), row()]
    await nextTick()
    expect(h.product.selectedChannelKey).toBe('581::云速递::PURE')
    expect(h.state.quantityCostBreakdown).toHaveBeenCalledTimes(1)
  } finally { h.scope.stop() }
})

it('does not confuse visible 1/2/3 prices with availability at the current main quantity', async () => {
  const h = harness()
  try {
    h.product.quantity = 9; h.template.value = [row()]; await nextTick(); expect(h.product.rule).toBe('')
    h.product.quantity = 2; await nextTick()
    expect(h.product.freight).toBe(127.8)
  } finally { h.scope.stop() }
})

it.each(['bundle', 'womens-bundle'])('uses one complete set as the primary quantity for %s', async mode => {
  const h = harness()
  try {
    h.product.quantity = 9; h.state.quoteMode.value = mode; h.template.value = [row()]; await nextTick()
    expect(h.product.freight).toBe(63.9)
  } finally { h.scope.stop() }
})

it('rejects a fresh calculation with incomplete tax or a non-finite final price', async () => {
  const h = harness()
  try {
    h.state.quantityCostBreakdown.mockReturnValue({ freight: 63.9, quoteUsd: 26.1, tax: { configured: false } })
    h.template.value = [row()]; await nextTick(); expect(h.product.rule).toBe('')
    h.state.quantityCostBreakdown.mockReturnValue({ freight: 63.9, quoteUsd: Infinity, tax: { configured: true } })
    h.sync(); expect(h.product.rule).toBe('')
  } finally { h.scope.stop() }
})
