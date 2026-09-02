import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from './http'
import { deleteQuotationDraft, draftSelection, loadQuotationDraft, normalizeDraftState, saveQuotationDraft, type QuotationDraftPayload } from './quotationDrafts'

vi.mock('./http', () => ({ api: { get: vi.fn(), put: vi.fn(), delete: vi.fn() } }))

const mockedApi = vi.mocked(api)
const payload: QuotationDraftPayload = {
  schemaVersion: 2, customerName: '客户A', quoteMode: 'single', skuSearch: 'SKU-1', productCategory: '服装', logisticsAttribute: '普货',
  selectedCustomerGrade: 'S', selectedTaxCustomerType: 'A', monthlySalesEstimate: '10', customQuoteQuantity: 5, quoteMatrixMode: 'common',
  selectedQuoteRegions: { 澳大利亚: '1区' }, product: { sku: 'SKU-1', purchaseInvoiceTaxApplied: true, quantity: 1, weightSource: 'purchase', manualWeight: 0, volumetricEnabled: false, packageLengthCm: 0, packageWidthCm: 0, packageHeightCm: 0, primaryCountry: '美国', primaryChannelKey: 'channel-1', primaryRule: '规则A', primaryCarrier: '物流商A' },
  bundleItems: [], commonSelections: [], specifiedSelections: [], templateSelections: [], activeTemplate: null,
}

describe('quotation draft repository', () => {
  beforeEach(() => vi.clearAllMocks())

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
    const legacy = { ...payload, product: { ...payload.product, purchaseInvoiceTaxApplied: undefined } }
    expect(normalizeDraftState({ exists: true, payload: legacy, version: 2 }).payload?.product.purchaseInvoiceTaxApplied).toBeUndefined()
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
