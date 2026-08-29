import { api, request } from './http'

export type PageResult<T> = { items: T[]; page: number; size: number; total: number; totalPages: number }

export type SupplierScoreStatus = 'COMPLETE' | 'PENDING'
export type SupplierScoreBreakdown = {
  quality: number | null
  delivery: number | null
  afterSales: number | null
  hotProduct: number | null
  freeSample: number | null
  priceLevel: number | null
  invoice: number | null
}

export type SupplierRecord = {
  id: string
  name: string
  industryBelt: string
  bossName: string
  contactDetails: string
  invoiceType: string
  taxPoint: number | null
  qualityGrade: string
  deliveryTerms: string
  capacityOrder: string
  stockingStrategy: string
  alternativeInquiry: string
  corporateAccount: string
  corporateBank: string
  businessLicenseAssetId: string | null
  businessLicenseUrl: string
  hotProductRecommendation: boolean | null
  freeSample: boolean | null
  afterSales: string
  afterSalesAvailable: boolean | null
  priceLevel: string
  cooperationScore: number | null
  calculatedScore: number | null
  scoreStatus: SupplierScoreStatus
  missingScoreItems: string[]
  scoreBreakdown: SupplierScoreBreakdown
  scorePolicyVersion: string | null
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

export type SupplierRecordInput = Omit<SupplierRecord,
  'id' | 'businessLicenseAssetId' | 'businessLicenseUrl' | 'calculatedScore' | 'scoreStatus' | 'missingScoreItems' |
  'scoreBreakdown' | 'scorePolicyVersion' | 'createdBy' | 'updatedBy' | 'version' | 'createdAt' | 'updatedAt'>

export type NumericDraft = number | '' | null
export type SupplierRecordDraft = Omit<SupplierRecordInput, 'taxPoint' | 'cooperationScore' | 'monthlyPurchaseAmount'> & {
  taxPointPercent: NumericDraft
  cooperationScore: NumericDraft
  monthlyPurchaseAmount: NumericDraft
  legacyDeliveryTerms: string
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

export function uploadSupplierBusinessLicense(record: Pick<SupplierRecord, 'id' | 'version'>, file: File) {
  const form = new FormData()
  form.append('file', file)
  return request<SupplierRecord>(`/supplier-records/${record.id}/business-license`, {
    method: 'POST', body: form, headers: { 'If-Match': String(record.version) },
  })
}

export function removeSupplierBusinessLicense(record: Pick<SupplierRecord, 'id' | 'version'>) {
  return request<SupplierRecord>(`/supplier-records/${record.id}/business-license`, {
    method: 'DELETE', headers: { 'If-Match': String(record.version) },
  })
}
