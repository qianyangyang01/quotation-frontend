import { api } from './http'

export type PageResult<T> = { items: T[]; page: number; size: number; total: number; totalPages: number }
export type Customer = { id: string; code: string; name: string; contactName: string; phone: string; email: string; countryCode: string; grade: string; notes: string; enabled: boolean; version: number; createdAt: string; updatedAt: string }
export type Supplier = { id: string; code: string; name: string; contactName: string; phone: string; platform: string; category: string; settlementTerms: string; leadTimeDays: number | null; rating: number | null; enabled: boolean; version: number; createdAt: string; updatedAt: string }

export type CustomerInput = Omit<Customer, 'id' | 'version' | 'createdAt' | 'updatedAt'>
export type SupplierInput = Omit<Supplier, 'id' | 'version' | 'createdAt' | 'updatedAt'>

export function loadCustomers(query = '', page = 0, size = 20, enabled?: boolean) {
  const params = new URLSearchParams({ query, page: String(page), size: String(size) })
  if (enabled !== undefined) params.set('enabled', String(enabled))
  return api.get<PageResult<Customer>>(`/customers?${params}`)
}
export function createCustomer(input: CustomerInput) { return api.post<Customer>('/customers', input) }
export function updateCustomer(customer: Customer, input: CustomerInput) { return api.put<Customer>(`/customers/${customer.id}`, input, { 'If-Match': String(customer.version) }) }
export function setCustomerStatus(customer: Customer, enabled: boolean) { return api.patch<Customer>(`/customers/${customer.id}/status`, { enabled }, { 'If-Match': String(customer.version) }) }

export function loadSuppliers(query = '', page = 0, size = 20) {
  const params = new URLSearchParams({ query, page: String(page), size: String(size) })
  return api.get<PageResult<Supplier>>(`/suppliers?${params}`)
}
export function createSupplier(input: SupplierInput) { return api.post<Supplier>('/suppliers', input) }
export function updateSupplier(supplier: Supplier, input: SupplierInput) { return api.put<Supplier>(`/suppliers/${supplier.id}`, input, { 'If-Match': String(supplier.version) }) }
export function deleteSupplier(id: string) { return api.delete<void>(`/suppliers/${id}`) }
