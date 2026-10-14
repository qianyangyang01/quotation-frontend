import { api } from './http'
import type { QuotationPersonalTemplate } from '@/data/quotationTemplates'

export interface TemplateSyncAccount { account: string; name: string; templateCount: number }
export interface TemplateSyncRequest {
  sourceAccount: string
  prefix: string
  templates: { id: string; version: number }[]
  targetAccounts: string[]
}
export interface TemplateSyncResult {
  created: number
  skipped: number
  recipients: { account: string; name: string; created: number; skipped: number
    templates: { sourceId: string; name: string; status: 'created' | 'skipped'; reason?: string; id?: string }[] }[]
}
export const loadTemplateSyncAccounts = () => api.get<TemplateSyncAccount[]>('/quotation-template-sync/accounts')
export const loadTemplateSyncSource = (account: string) => api.get<QuotationPersonalTemplate[]>(`/quotation-template-sync/templates?account=${encodeURIComponent(account)}`)
export const syncQuotationTemplates = (body: TemplateSyncRequest, key: string) => api.post<TemplateSyncResult>('/quotation-template-sync', body, key)
