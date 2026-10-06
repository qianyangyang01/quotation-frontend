import { api } from '@/services/http'
export interface PersonalQuotationCustomer { id: string; name: string; _version: number; lastUsedAt: string | null }
const endpoint='/personal-quotation-customers'
export const listPersonalCustomers=()=>api.get<PersonalQuotationCustomer[]>(endpoint,{cache:'no-store'})
export const addPersonalCustomer=(name:string,key:string)=>api.post<PersonalQuotationCustomer>(endpoint,{name},key)
export const renamePersonalCustomer=(row:PersonalQuotationCustomer,name:string)=>api.put<PersonalQuotationCustomer>(`${endpoint}/${encodeURIComponent(row.id)}`,{name,_version:row._version})
export const removePersonalCustomer=(row:PersonalQuotationCustomer)=>api.delete<void>(`${endpoint}/${encodeURIComponent(row.id)}`,{'If-Match':String(row._version)})
export const usePersonalCustomer=(row:PersonalQuotationCustomer)=>api.post<PersonalQuotationCustomer>(`${endpoint}/${encodeURIComponent(row.id)}/use`)
