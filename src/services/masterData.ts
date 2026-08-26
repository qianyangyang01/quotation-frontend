import { api } from './http'

export type PageResult<T> = { items: T[]; page: number; size: number; total: number; totalPages: number }
export type Supplier = { id: string; code: string; name: string; contactName: string; phone: string; platform: string; category: string; settlementTerms: string; leadTimeDays: number | null; rating: number | null; enabled: boolean; version: number; createdAt: string; updatedAt: string }
export type SupplierProductLink = { id: string; supplierId: string; productId: string; productSku: string; productCategory: string; catalogState: string; supplierSku: string; enabled: boolean; createdAt: string; updatedAt: string }

export type SupplierInput = Omit<Supplier, 'id' | 'version' | 'createdAt' | 'updatedAt'>

export function loadSuppliers(query = '', page = 0, size = 20) {
  const params = new URLSearchParams({ query, page: String(page), size: String(size) })
  return api.get<PageResult<Supplier>>(`/suppliers?${params}`)
}
export function createSupplier(input: SupplierInput) { return api.post<Supplier>('/suppliers', input) }
export function updateSupplier(supplier: Supplier, input: SupplierInput) { return api.put<Supplier>(`/suppliers/${supplier.id}`, input, { 'If-Match': String(supplier.version) }) }
export function deleteSupplier(id: string) { return api.delete<void>(`/suppliers/${id}`) }
export function loadSupplierProducts(id: string) { return api.get<SupplierProductLink[]>(`/suppliers/${id}/products`) }
export function linkSupplierProduct(id: string, input: { sku: string; supplierSku: string }) { return api.post<SupplierProductLink>(`/suppliers/${id}/products`, input) }
export function updateSupplierProductLink(supplierId: string, linkId: string, input: { supplierSku: string; enabled: boolean }) { return api.patch<SupplierProductLink>(`/suppliers/${supplierId}/products/${linkId}`, input) }
export function unlinkSupplierProduct(supplierId: string, linkId: string) { return api.delete<void>(`/suppliers/${supplierId}/products/${linkId}`) }
