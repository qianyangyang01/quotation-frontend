import { api, idempotencyKey, uploadForm, type UploadProgress } from './http'

export type PurchaseImportJobStatus = 'queued'|'parsing'|'ready'|'import-queued'|'importing'|'completed'|'completed-with-errors'|'failed'|'cancelled'|'rollback-queued'|'rolling-back'|'rolled-back'
export interface PurchaseImportImagePart { partNumber:number;fileName:string;status:string;sizeBytes:number;processedBytes:number;error?:string;processedAt?:string }
export interface PurchaseImportJob {
  archivedAt?:string|null
  id:string;status:PurchaseImportJobStatus;phase:string;sourceName:string;totalRows:number;processedRows:number;validRows:number;errorRows:number;addedRows:number;updatedRows:number;conflictRows:number;progressPercent:number;imageParts:number;imagePartDetails:PurchaseImportImagePart[];imageErrors:number;error?:string;summary?:{sourceBytes?:number;uploadedBytes?:number;originalSizeBytes?:number;removedMediaCount?:number;importMode?:'text-only';textParseMillis?:number;generatedSkuRows?:number;warningCount?:number;sheetSummaries?:PurchaseImportSheetSummary[];continuation?:PurchaseImportContinuation};createdAt:string;updatedAt:string;completedAt?:string;rolledBackAt?:string
}
export interface PurchaseImportContinuation {
  mode:'append';sourceName:string;baselineFound:boolean;skippedRows:number;pendingRows:number;skuBackfillRows?:number;blocked:boolean;reason?:string; sheets:PurchaseImportContinuationSheet[]
}
export interface PurchaseImportContinuationSheet {sheetName:string;lastImportedRow:number;nextRow:number;skippedRows:number;newRows:number;retryRows:number;skuBackfillRows?:number}
export interface PurchaseImportSheetSummary {sheetName:string;recognized:boolean;headerRow:number;recognizedColumns:string[];unknownColumns:string[];missingColumns:string[];dataRows:number;ignoredRows:number}
export interface PurchaseImportRowView { sourceSheet:string;sourceRow:number;sku:string;status:string;action:string;error?:string;payload:Record<string,unknown> }
export interface PurchaseImportDuplicateGroup { sku:string;choices:{sourceSheet:string;sourceRow:number}[] }
export interface PageResult<T>{content:T[];number:number;size:number;totalElements:number;totalPages:number;first:boolean;last:boolean}

export function createPurchaseImportJob(file:File,onProgress?:(progress:UploadProgress)=>void){
  if(!file.name.toLowerCase().endsWith('.xlsx'))throw new Error('请选择.xlsx格式的采购模板')
  if(file.size<=0||file.size>100*1024*1024)throw new Error('采购 Excel 文件不能为空且不能超过100MB')
  const form=new FormData();form.append('file',file);form.append('importMode','text-only')
  return uploadForm<PurchaseImportJob>('/purchase-imports/jobs',form,onProgress)
}
export async function uploadPurchaseImagePart(jobId:string,partNumber:number,file:File){const form=new FormData();form.append('file',file);return api.post<PurchaseImportJob>(`/purchase-imports/jobs/${jobId}/image-parts?partNumber=${partNumber}`,form)}
export const loadPurchaseImportJobs=(page=0,size=20,archived=false)=>api.get<PageResult<PurchaseImportJob>>(`/purchase-imports/jobs?page=${page}&size=${size}&archived=${archived}`)
export interface PurchaseImportRemovalCheck { action:'delete'|'archive'|'blocked';appliedRows:number;reason:string }
export const checkPurchaseImportRemoval=(id:string)=>api.get<PurchaseImportRemovalCheck>(`/purchase-imports/jobs/${id}/deletion-check`)
export const deletePurchaseImportTask=(id:string)=>api.delete<PurchaseImportRemovalCheck>(`/purchase-imports/jobs/${id}`)
export const archivePurchaseImportTask=(id:string)=>api.post<PurchaseImportRemovalCheck>(`/purchase-imports/jobs/${id}/archive`)
export const restorePurchaseImportTask=(id:string)=>api.post<PurchaseImportJob>(`/purchase-imports/jobs/${id}/restore`)
export const loadPurchaseImportJob=(id:string)=>api.get<PurchaseImportJob>(`/purchase-imports/jobs/${id}`)
export const loadPurchaseImportRows=(id:string,status='',page=0,size=50)=>api.get<PageResult<PurchaseImportRowView>>(`/purchase-imports/jobs/${id}/rows?status=${encodeURIComponent(status)}&page=${page}&size=${size}`)
export const loadPurchaseImportDuplicateGroups=(id:string)=>api.get<PurchaseImportDuplicateGroup[]>(`/purchase-imports/jobs/${id}/duplicate-groups`)
export const confirmPurchaseImportJob=(id:string,duplicateSelections:Record<string,{sourceSheet:string;sourceRow:number}>={})=>api.post<{jobId:string;status:string}>(`/purchase-imports/jobs/${id}/confirm`,{duplicateSelections},idempotencyKey('purchase-async-confirm'))
export const retryPurchaseImportJob=(id:string)=>api.post<PurchaseImportJob>(`/purchase-imports/jobs/${id}/retry`)
export const rollbackPurchaseImportJob=(id:string)=>api.post<PurchaseImportJob>(`/purchase-imports/jobs/${id}/rollback`)
export const cancelPurchaseImportJob=(id:string)=>api.post<PurchaseImportJob>(`/purchase-imports/jobs/${id}/cancel`)
export const purchaseImportErrorsUrl=(id:string)=>`/api/v1/purchase-imports/jobs/${id}/errors.xlsx`
