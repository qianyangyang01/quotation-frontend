import { api } from './http'

export type PageResult<T> = { items: T[]; page: number; size: number; total: number; totalPages: number }

export type SupplierRecord = {
  id: string
  name: string
  industryBelt: string
  contactRole: string
  relationshipNotes: string
  invoiceType: string
  taxPoint: number | null
  qualityGrade: string
  deliveryTerms: string
  capacityOrder: string
  stockingStrategy: string
  alternativeInquiry: string
  costSheet: string
  hotProductRecommendation: boolean | null
  freeSample: boolean | null
  afterSales: string
  cooperationScore: number | null
  rating: string
  monthlyPurchaseAmount: number | null
  notes: string
  suggestion: string
  createdBy: string
  updatedBy: string
  version: number
  createdAt: string
  updatedAt: string
}

export type SupplierRecordInput = Omit<SupplierRecord, 'id' | 'createdBy' | 'updatedBy' | 'version' | 'createdAt' | 'updatedAt'>

export type NumericDraft = number | '' | null
export type SupplierRecordDraft = Omit<SupplierRecordInput, 'taxPoint' | 'cooperationScore' | 'monthlyPurchaseAmount'> & {
  taxPointPercent: NumericDraft
  cooperationScore: NumericDraft
  monthlyPurchaseAmount: NumericDraft
}

export type SupplierRecordFilters = {
  query?: string
  industryBelt?: string
  rating?: string
  page?: number
  size?: number
}

export function loadSupplierRecords(filters: SupplierRecordFilters = {}) {
  const params = new URLSearchParams({
    query: filters.query?.trim() || '',
    industryBelt: filters.industryBelt?.trim() || '',
    rating: filters.rating?.trim() || '',
    page: String(filters.page ?? 0),
    size: String(filters.size ?? 10),
  })
  return api.get<PageResult<SupplierRecord>>(`/supplier-records?${params}`)
}

export function createSupplierRecord(input: SupplierRecordInput) {
  return api.post<SupplierRecord>('/supplier-records', input)
}

export function updateSupplierRecord(record: Pick<SupplierRecord, 'id' | 'version'>, input: SupplierRecordInput) {
  return api.put<SupplierRecord>(`/supplier-records/${record.id}`, input, { 'If-Match': String(record.version) })
}

export function deleteSupplierRecord(record: Pick<SupplierRecord, 'id' | 'version'>) {
  return api.delete<void>(`/supplier-records/${record.id}`, { 'If-Match': String(record.version) })
}
