// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, reactive, type App, type Component } from 'vue'
import CommonMatrix from './QuotationCommonMatrix.vue'
import Matrix from './QuotationMatrix.vue'
import TemplateMatrix from './QuotationTemplateMatrix.vue'
import Preview from './QuotationPreviewSave.vue'
import { quoteSheetRowKey } from '@/data/customerQuoteSheet'
import type { QuotationCountrySummary, QuotationMatrixRow } from './types'

vi.mock('@/data/quotationTemplates', async original => ({
  ...await original<typeof import('@/data/quotationTemplates')>(),
  loadQuotationTemplates: vi.fn().mockResolvedValue([]),
}))
vi.mock('@/services/customerQuoteSheetRenderer', async original => ({
  ...await original<typeof import('@/services/customerQuoteSheetRenderer')>(),
  preloadQuoteSheetAssets: vi.fn().mockResolvedValue(undefined),
}))
let app: App
const settle = async () => { for (let i = 0; i < 12; i++) await nextTick() }
afterEach(() => { app?.unmount(); document.body.innerHTML = ''; localStorage.clear() })
const row = (region: string, country = '美国') => ({ country, quoteRegion: region, channelKey: 'shared', rule: '规则', carrier: '燕文', transport: '专线', channelCode: 'YW', ruleId: 1, quote1: 10, quote2: 20, quote3: 30, quoteCustom: 50, taxConfigured: true, eta: '5天' }) as QuotationMatrixRow

it.each(['common', 'specified', 'template'])('%s: preview removal updates real selection, preserves sibling routes and survives repricing', async mode => {
  const all = [row('一区'), row('二区'), row('一区', '英国')]
  const state = reactive({ rows: [] as QuotationMatrixRow[], contextKey: 'initial', saving: false })
  let matrix: { removeSelection: (row: QuotationMatrixRow) => void } | null = null
  const component: Component = mode === 'common' ? CommonMatrix : mode === 'specified' ? Matrix : TemplateMatrix
  const host = document.createElement('div'); document.body.append(host)
  app = createApp({ render: () => h('div', [
    h(component, {
      ref: (instance: unknown) => { matrix = instance as typeof matrix },
      countries: ['美国', '英国'].map(name => ({ name, code: name === '美国' ? 'US' : 'GB', stage: 'common', channelCount: 2, sortOrder: 0 })) as QuotationCountrySummary[],
      quoteRowsForCountry: (country: string) => all.filter(row => row.country === country),
      contextKey: state.contextKey, customQuantity: 5, adoptedCountry: '', adoptedRule: '', adoptedCarrier: '', exchangeRate: 7,
      presetSelection: all, presetVersion: 1, draftSelection: all, draftVersion: 1, ownerName: 'test', ownerAccount: 'test',
      onSelectionChange: (rows: QuotationMatrixRow[]) => { state.rows = rows },
    }),
    h(Preview, {
      rows: state.rows, countries: [], salesperson: 'test', contextKey: 'sheet', sourcePending: false,
      matrixModeLabel: mode, customerName: 'test', productName: 'test', sku: 'SKU', customerGrade: 'S', coefficient: 1,
      customQuantity: 5, unitLabel: '件', exchangeRate: 7, primaryCountry: '', primaryCarrier: '', primaryRule: '', primaryCnyPrice: 0, primaryUsdPrice: 0, saving: state.saving,
      onRemoveRow: (key: string) => { const selected = state.rows.find(row => quoteSheetRowKey(row) === key); if (selected) matrix?.removeSelection(selected) },
    }),
  ]) }); app.mount(host); await settle()
  expect(state.rows).toHaveLength(3)
  state.saving = true; await settle()
  expect(document.querySelector<HTMLButtonElement>('[aria-label="移除第 1 行渠道"]')!.disabled).toBe(true)
  state.saving = false; await settle()
  document.querySelector<HTMLButtonElement>('[aria-label="移除第 1 行渠道"]')!.click(); await settle()
  expect(state.rows.map(quoteSheetRowKey)).toEqual(all.slice(1).map(quoteSheetRowKey))
  state.contextKey = 'recalculated'; await settle()
  expect(state.rows.map(quoteSheetRowKey)).toEqual(all.slice(1).map(quoteSheetRowKey))
  document.querySelector<HTMLButtonElement>('[aria-label="移除第 1 行渠道"]')!.click(); await settle()
  document.querySelector<HTMLButtonElement>('[aria-label="移除第 1 行渠道"]')!.click(); await settle()
  expect(state.rows).toEqual([])
  expect(document.querySelector<HTMLButtonElement>('.quote-preview .save')!.disabled).toBe(true)
})
