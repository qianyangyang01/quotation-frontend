import { api } from './http'

export type PageResult<T> = { items: T[]; page: number; size: number; total: number; totalPages: number }
export type Supplier = { id: string; code: string; name: string; contactName: string; phone: string; platform: string; category: string; settlementTerms: string; leadTimeDays: number | null; rating: number | null; enabled: boolean; version: number; createdAt: string; updatedAt: string }

export type SupplierInput = Omit<Supplier, 'id' | 'version' | 'createdAt' | 'updatedAt'>

export function loadSuppliers(query = '', page = 0, size = 20) {
  const params = new URLSearchParams({ query, page: String(page), size: String(size) })
  return api.get<PageResult<Supplier>>(`/suppliers?${params}`)
}
export function createSupplier(input: SupplierInput) { return api.post<Supplier>('/suppliers', input) }
export function updateSupplier(supplier: Supplier, input: SupplierInput) { return api.put<Supplier>(`/suppliers/${supplier.id}`, input, { 'If-Match': String(supplier.version) }) }
export function deleteSupplier(id: string) { return api.delete<void>(`/suppliers/${id}`) }
