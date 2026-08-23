import { api } from '@/services/http'

export interface QuotationReadiness {
  ready: boolean
  missing: string[]
  purchase: { ready: boolean; readyProducts: number }
  logistics: { ready: boolean; publishedChannels: number }
  finance: { ready: boolean; configured: number; missingKeys: string[] }
}

export function loadQuotationReadiness() {
  return api.get<QuotationReadiness>('/quotation-readiness')
}
