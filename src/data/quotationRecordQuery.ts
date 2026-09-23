import { api } from '@/services/http'
import { normalizeQuotationRecord, type QuotationRecord } from './quotationRecords'

export interface RecordFilters { lifecycle?: 'active' | 'archived' | 'trashed'; q?: string; status?: string; reviewStatus?: string; reviewMine?: boolean; country?: string; category?: string; startDate?: string; endDate?: string }
export interface RecordPage { items: QuotationRecord[]; page: number; size: number; total: number; totalPages: number; summary: { processed?: number; pending: number; won: number; lost: number; total: number }; countries: string[] }
export async function loadRecordPage(scope: 'mine' | 'company', filters: RecordFilters, page=0, size=10): Promise<RecordPage> {
  const query=new URLSearchParams({scope,page:String(page),size:String(size)})
  for(const [key,value] of Object.entries(filters)) if(value) query.set(key,String(value))
  const result=await api.get<RecordPage>(`/quotations/search?${query}`)
  return {...result,items:result.items.map(normalizeQuotationRecord).filter((row): row is QuotationRecord=>!!row)}
}
export async function loadFilteredRecords(scope: 'mine' | 'company', filters: RecordFilters) {
  const snapshot={...filters};const first=await loadRecordPage(scope,snapshot,0,100);const rows=[...first.items]
  for(let page=1;page<first.totalPages;page++) {
    const next=await loadRecordPage(scope,snapshot,page,100)
    if(next.total!==first.total || next.page!==page) throw new Error('导出期间记录数量发生变化，请重试')
    rows.push(...next.items)
  }
  if(new Set(rows.map(row=>row.id)).size!==first.total) throw new Error('导出期间记录发生变化，请重试')
  return rows
}
export async function loadRecord(id: string) { return normalizeQuotationRecord(await api.get<QuotationRecord>(`/quotations/${encodeURIComponent(id)}`)) }
export function recentRecordDates(days: number, now=new Date()) {
  const today=new Intl.DateTimeFormat('en-CA',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit'}).format(now)
  const start=new Date(`${today}T00:00:00+08:00`);start.setTime(start.getTime()-(days-1)*86400000)
  return {startDate:new Intl.DateTimeFormat('en-CA',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit'}).format(start),endDate:today}
}
