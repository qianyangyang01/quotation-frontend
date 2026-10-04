import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from './http'
import { deleteQuotationDraft, draftSelection, migrateDraftMatrix, loadQuotationDraft, normalizeDraftState, saveQuotationDraft, type QuotationDraftPayload } from './quotationDrafts'

vi.mock('./http', () => ({ api: { get: vi.fn(), put: vi.fn(), delete: vi.fn() } }))

const mockedApi = vi.mocked(api)
const payload: QuotationDraftPayload = {
  schemaVersion: 2, customerName: '客户A', quoteMode: 'single', skuSearch: 'SKU-1', productCategory: '服装', logisticsAttribute: '普货',
  selectedCustomerGrade: 'S', monthlySalesEstimate: '10', customQuoteQuantity: 5, quoteMatrixMode: 'common',
  selectedQuoteRegions: { 澳大利亚: '1区' }, product: { sku: 'SKU-1', purchaseInvoiceTaxApplied: true, quantity: 1, weightSource: 'purchase', manualWeight: 0, volumetricEnabled: false, packageLengthCm: 0, packageWidthCm: 0, packageHeightCm: 0, primaryCountry: '美国', primaryChannelKey: 'channel-1', primaryRule: '规则A', primaryCarrier: '物流商A' },
  bundleItems: [], commonSelections: [], specifiedSelections: [], templateSelections: [], activeTemplate: null,
}

describe('quotation draft repository', () => {
  beforeEach(() => vi.clearAllMocks())

  it.each(['single', 'bundle'] as const)('preserves NEW grade in a %s draft round trip', async quoteMode => {
    const draft = { ...payload, quoteMode, selectedCustomerGrade: 'NEW' }
    mockedApi.put.mockResolvedValue({ exists: true, payload: draft, version: 1 })
    const saved = await saveQuotationDraft(draft, -1)
    mockedApi.get.mockResolvedValue(JSON.parse(JSON.stringify(saved)))
    expect((await loadQuotationDraft()).payload?.selectedCustomerGrade).toBe('NEW')
  })

  it.each(['化妆品', '保健品', '非液体化妆品', '以色列自提', '以色列到门'])('preserves %s independently of product category and selected regions', async logisticsAttribute => {
    const draft = { ...payload, productCategory: '服装', logisticsAttribute,
      templateSelections: [{ country: '澳大利亚', channelKey: 'channel-1', quoteRegion: '澳大利亚3区' }, { country: '澳大利亚', channelKey: 'channel-1', quoteRegion: '澳大利亚4区' }] }
    mockedApi.put.mockResolvedValue({ exists: true, payload: draft, version: 1 })
    const saved = await saveQuotationDraft(draft, -1)
    mockedApi.get.mockResolvedValue(JSON.parse(JSON.stringify(saved)))
    const loaded = await loadQuotationDraft()
    expect(loaded.payload?.logisticsAttribute).toBe(logisticsAttribute)
    expect(loaded.payload?.productCategory).toBe('服装')
    expect(loaded.payload?.templateSelections.map(row => row.quoteRegion)).toEqual(['澳大利亚3区', '澳大利亚4区'])
  })

  it('normalizes absent and unsupported draft states', () => {
    expect(normalizeDraftState(null)).toEqual({ exists: false, payload: null, version: -1, updatedAt: null })
    expect(normalizeDraftState({ exists: true, payload: { ...payload, schemaVersion: 1 } as never, version: 2 })).toMatchObject({ exists: false, payload: null, version: 2 })
  })

  it('loads and saves one versioned server draft', async () => {
    mockedApi.get.mockResolvedValue({ exists: true, payload, version: 3, updatedAt: '2026-08-24T00:00:00Z' })
    expect((await loadQuotationDraft()).payload?.customerName).toBe('客户A')
    mockedApi.put.mockResolvedValue({ exists: true, payload, version: 4, updatedAt: '2026-08-24T00:01:00Z' })
    expect((await saveQuotationDraft(payload, 3)).version).toBe(4)
    expect(mockedApi.put).toHaveBeenCalledWith('/quotation-drafts/mine/state', payload, { 'If-Match': '3' })
    await deleteQuotationDraft(4)
    expect(mockedApi.delete).toHaveBeenCalledWith('/quotation-drafts/mine/state', { 'If-Match': '4' })
  })

  it('keeps the invoice-pricing marker optional for legacy schema-version-2 drafts', () => {
    const legacy = { ...payload, selectedTaxCustomerType: 'B', product: { ...payload.product, purchaseInvoiceTaxApplied: undefined } }
    expect(normalizeDraftState({ exists: true, payload: legacy, version: 2 }).payload?.product.purchaseInvoiceTaxApplied).toBeUndefined()
    expect(normalizeDraftState({ exists: true, payload: legacy, version: 2 }).payload).toHaveProperty('selectedTaxCustomerType', 'B')
  })

  it('keeps only stable unique channel selections', () => {
    expect(draftSelection([
      { country: '美国', channelKey: 'channel-1' },
      { country: '美国', channelKey: 'channel-1' },
      { country: '', channelKey: 'channel-2' },
      { country: '英国', rule: '规则', carrier: '物流商', transport: '渠道' },
    ])).toEqual([
      { country: '美国', channelKey: 'channel-1', quoteRegion: undefined, rule: undefined, carrier: undefined, transport: undefined },
      { country: '英国', channelKey: undefined, quoteRegion: undefined, rule: '规则', carrier: '物流商', transport: '渠道' },
    ])
  })
})


describe('retired specified mode compatibility', () => {
  const specifiedSelections = [
    { country: '澳大利亚', quoteRegion: '澳大利亚1区', channelKey: '1::物流::AU' },
    { country: '澳大利亚', quoteRegion: '澳大利亚2区', channelKey: '1::物流::AU' },
    { country: '英国', rule: '旧规则', carrier: '物流', transport: '旧渠道' },
  ]
  const commonSelections = [{ country: '美国', channelKey: 'unrelated' }]
  it.each(['single', 'bundle'] as const)('migrates only the active specified list for %s and keeps source inputs immutable', quoteMode => {
    const original = { ...payload, quoteMode, quoteMatrixMode: 'specified' as const, specifiedSelections, commonSelections,
      templateSelections: [{ country: '德国', channelKey: 'template-only' }], activeTemplate: { id: 'T1', name: '模板' },
      customQuoteQuantity: 8, selectedQuoteRegions: { 澳大利亚: '澳大利亚2区' },
      product: { ...payload.product, primaryCountry: '澳大利亚', primaryChannelKey: '1::物流::AU' },
    }
    const before = JSON.stringify(original)
    const migrated = migrateDraftMatrix(original)
    expect(migrated.quoteMatrixMode).toBe('common')
    expect(migrated.commonSelections).toEqual(specifiedSelections)
    expect(migrated.specifiedSelections).toEqual([])
    for (const field of ['product', 'templateSelections', 'activeTemplate', 'selectedQuoteRegions', 'customQuoteQuantity'] as const)
      expect(migrated[field]).toEqual(original[field])
    expect(JSON.stringify(original)).toBe(before)
    expect(migrateDraftMatrix(migrated)).toEqual(migrated)
  })
  it.each(['common', 'template'] as const)('does not add inactive specified selections to %s quotes', quoteMatrixMode => {
    const migrated = migrateDraftMatrix({ ...payload, quoteMatrixMode, commonSelections, specifiedSelections })
    expect(migrated.quoteMatrixMode).toBe(quoteMatrixMode)
    expect(migrated.commonSelections).toEqual(commonSelections)
    expect(migrated.specifiedSelections).toEqual([])
  })
  it('keeps an empty specified list empty instead of restoring an unrelated common list', () => {
    expect(migrateDraftMatrix({ ...payload, quoteMatrixMode: 'specified', commonSelections }).commonSelections).toEqual([])
  })
})
