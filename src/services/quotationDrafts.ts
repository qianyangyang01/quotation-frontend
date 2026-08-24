import { api } from './http'

export type DraftQuoteMode = 'single' | 'bundle'
export type DraftMatrixMode = 'common' | 'specified' | 'template'

export interface DraftChannelSelection {
  country: string
  quoteRegion?: string
  channelKey?: string
  rule?: string
  carrier?: string
  transport?: string
}

export interface QuotationDraftPayload {
  schemaVersion: 2
  customerName: string
  quoteMode: DraftQuoteMode
  skuSearch: string
  productCategory: string
  logisticsAttribute: string
  selectedCustomerGrade: string
  selectedTaxCustomerType: 'A' | 'B'
  monthlySalesEstimate: string
  customQuoteQuantity: number
  quoteMatrixMode: DraftMatrixMode
  selectedQuoteRegions: Record<string, string>
  product: {
    sku: string
    quantity: number
    weightSource: 'purchase' | 'manual'
    manualWeight: number
    volumetricEnabled: boolean
    packageLengthCm: number
    packageWidthCm: number
    packageHeightCm: number
    primaryCountry: string
    primaryChannelKey: string
    primaryRule: string
    primaryCarrier: string
  }
  bundleItems: Array<{ sku: string; quantityPerSet: number; customWeightKg: number | null }>
  commonSelections: DraftChannelSelection[]
  specifiedSelections: DraftChannelSelection[]
  templateSelections: DraftChannelSelection[]
  activeTemplate: { id: string; name: string } | null
}

export interface QuotationDraftState {
  exists: boolean
  payload: QuotationDraftPayload | null
  version: number
  updatedAt: string | null
}

export function normalizeDraftState(input: Partial<QuotationDraftState> | null | undefined): QuotationDraftState {
  const payload = input?.payload
  return {
    exists: input?.exists === true && payload?.schemaVersion === 2,
    payload: payload?.schemaVersion === 2 ? payload : null,
    version: Number.isInteger(input?.version) ? Number(input?.version) : -1,
    updatedAt: typeof input?.updatedAt === 'string' && input.updatedAt ? input.updatedAt : null,
  }
}

export async function loadQuotationDraft() {
  return normalizeDraftState(await api.get<QuotationDraftState>('/quotation-drafts/mine/state'))
}

export async function saveQuotationDraft(payload: QuotationDraftPayload, version: number) {
  return normalizeDraftState(await api.put<QuotationDraftState>('/quotation-drafts/mine/state', payload, { 'If-Match': String(version) }))
}

export async function deleteQuotationDraft(version: number) {
  await api.delete<void>('/quotation-drafts/mine/state', { 'If-Match': String(version) })
}

export function draftSelection(input: Array<Partial<DraftChannelSelection>>): DraftChannelSelection[] {
  const seen = new Set<string>()
  return input.flatMap(item => {
    const country = String(item.country || '').trim()
    const channelKey = String(item.channelKey || '').trim()
    const rule = String(item.rule || '').trim()
    const carrier = String(item.carrier || '').trim()
    const transport = String(item.transport || '').trim()
    if (!country || (!channelKey && !(rule && carrier && transport))) return []
    const normalized = { country, quoteRegion: String(item.quoteRegion || '').trim() || undefined, channelKey: channelKey || undefined, rule: rule || undefined, carrier: carrier || undefined, transport: transport || undefined }
    const key = `${country}|${normalized.quoteRegion || ''}|${channelKey || `${rule}|${carrier}|${transport}`}`
    if (seen.has(key)) return []
    seen.add(key)
    return [normalized]
  })
}
