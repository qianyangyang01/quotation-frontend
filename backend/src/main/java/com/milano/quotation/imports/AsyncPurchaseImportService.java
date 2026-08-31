package com.milano.quotation.imports;

import com.milano.quotation.common.AppException;
import com.milano.quotation.storage.AssetStorageService;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.*;import java.security.MessageDigest;import java.time.Instant;import java.util.*;

@Service
public class AsyncPurchaseImportService {
    private static final long MAX_FILE=100L*1024*1024;public static final String JOB_TYPE="purchase-xlsx-async";
    private final ImportJobRepository jobs;private final PurchaseImportRowRepository rows;private final ImportPartRepository parts;private final MigrationManifestEntryRepository imageEntries;private final AssetStorageService storage;private final ObjectMapper mapper;private final TransactionTemplate transactions;private final PurchaseImportContinuationService continuation;
    @org.springframework.beans.factory.annotation.Autowired public AsyncPurchaseImportService(ImportJobRepository jobs,PurchaseImportRowRepository rows,ImportPartRepository parts,MigrationManifestEntryRepository imageEntries,AssetStorageService storage,ObjectMapper mapper,PlatformTransactionManager transactionManager,PurchaseImportContinuationService continuation){this(jobs,rows,parts,imageEntries,storage,mapper,new TransactionTemplate(transactionManager),continuation);}
    AsyncPurchaseImportService(ImportJobRepository jobs,PurchaseImportRowRepository rows,ImportPartRepository parts,MigrationManifestEntryRepository imageEntries,AssetStorageService storage,ObjectMapper mapper){this(jobs,rows,parts,imageEntries,storage,mapper,(TransactionTemplate)null,null);}
    private AsyncPurchaseImportService(ImportJobRepository jobs,PurchaseImportRowRepository rows,ImportPartRepository parts,MigrationManifestEntryRepository imageEntries,AssetStorageService storage,ObjectMapper mapper,TransactionTemplate transactions,PurchaseImportContinuationService continuation){this.jobs=jobs;this.rows=rows;this.parts=parts;this.imageEntries=imageEntries;this.storage=storage;this.mapper=mapper;this.transactions=transactions;this.continuation=continuation;}
    public ImportJob create(MultipartFile file,String actor){return create(file,actor,"text-only",file.getSize(),0);}
    // ZIP repacking can increase size even after image removal; validate each size independently.
    public ImportJob create(MultipartFile file,String actor,String importMode,Long originalSizeBytes,Integer removedMediaCount){if(file.isEmpty()||file.getOriginalFilename()==null||!file.getOriginalFilename().toLowerCase(Locale.ROOT).endsWith(".xlsx"))throw AppException.unprocessable("请选择.xlsx格式的采购模板");if(!"text-only".equals(importMode))throw AppException.unprocessable("采购 Excel 仅支持无图数据导入");long originalBytes=originalSizeBytes==null?file.getSize():originalSizeBytes;int removed=removedMediaCount==null?0:removedMediaCount;if(file.getSize()>MAX_FILE||originalBytes>MAX_FILE)throw AppException.unprocessable("采购数据文件不能超过100MB");if(originalBytes<=0||removed<0||removed>200_000)throw AppException.unprocessable("无图数据文件统计信息不合法");var id=UUID.randomUUID();var key="purchase-import/"+id+"/source.xlsx";try{var hash=storage.putRawWithSha256(key,file.getInputStream(),file.getSize(),"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");var build=(java.util.function.Supplier<ImportJob>)()->{var now=Instant.now();var job=new ImportJob();job.id=id;job.jobType=JOB_TYPE;job.status="queued";job.phase="queued";job.requestedBy=actor;job.sourceName=safe(file.getOriginalFilename());job.sourceHash=hash;job.sourceObjectKey=key;job.payload=mapper.createObjectNode().put("sourceBytes",file.getSize()).put("uploadedBytes",file.getSize()).put("originalSizeBytes",originalBytes).put("removedMediaCount",removed).put("importMode","text-only");job.createdAt=now;job.updatedAt=now;job.heartbeatAt=now;PurchaseImportContinuationService.initialize(job);PurchaseImportBatchService.syncPayload(job);return jobs.save(job);};try{return transactions==null?build.get():transactions.execute(status->build.get());}catch(Exception db){storage.removeRaw(key);throw db;}}catch(Exception e){if(e instanceof AppException a)throw a;throw AppException.unprocessable("采购文件上传失败");}}
    public ImportJob confirm(UUID id,Map<String,DuplicateSelection> selections){
        java.util.function.Supplier<Confirmation> work=()->{
            var job=editable(id);
            if(!"ready".equals(job.status))throw AppException.conflict("只有校验完成的任务可以确认入库");
            if(continuation!=null)continuation.refresh(job);
            if(job.payload.path("continuation").path("blocked").asBoolean())
                return new Confirmation(job,job.payload.path("continuation").path("reason").asText());
            // A concurrently completed import may have consumed every candidate.
            // Persist its refreshed summary even though confirmation is declined.
            if(PurchaseImportContinuationService.enabled(job)&&job.payload.path("continuation").path("pendingRows").asInt()==0){
                job.validRows=job.conflictRows=job.addedRows=job.updatedRows=0;
                PurchaseImportBatchService.syncPayload(job);
                return new Confirmation(job,"此表所有数据已处理，无须重复入库");
            }
            if(PurchaseImportContinuationService.enabled(job))rows.markFileDuplicates(id);
            resolveDuplicates(id,selections==null?Map.of():selections);
            job.validRows=(int)rows.countByJobIdAndValidationStatus(id,"valid");
            job.conflictRows=(int)rows.countByJobIdAndValidationStatus(id,"conflict");
            job.addedRows=(int)rows.countByJobIdAndValidationStatusAndImportAction(id,"valid","insert");
            job.updatedRows=(int)rows.countByJobIdAndValidationStatusAndImportAction(id,"valid","update");
            if(job.validRows==0)throw AppException.conflict("没有可入库的合格数据");
            job.status="import-queued";job.phase="import-queued";job.confirmedAt=Instant.now();
            job.updatedAt=job.confirmedAt;job.processedRows=0;job.progressPercent=0;job.errorMessage=null;
            PurchaseImportBatchService.syncPayload(job);
            return new Confirmation(job,null);
        };
        var result=transactions==null?work.get():transactions.execute(status->work.get());
        if(result.error()!=null)throw AppException.conflict(result.error());
        return result.job();
    }
    private record Confirmation(ImportJob job,String error){}
    @Transactional public ImportJob cancel(UUID id){var job=editable(id);if(!List.of("queued","parsing","ready","failed").contains(job.status))throw AppException.conflict("任务开始入库后不能取消，请等待完成后使用整批回滚");job.cancelRequested=true;if(List.of("queued","ready","failed").contains(job.status)){job.status="cancelled";job.phase="cancelled";job.completedAt=Instant.now();}job.updatedAt=Instant.now();PurchaseImportBatchService.syncPayload(job);return job;}
    @Transactional public ImportJob retry(UUID id){var job=editable(id);if(!"failed".equals(job.status))throw AppException.conflict("只有失败任务可以重试");var failedPhase=job.payload.path("failedPhase").asText("parsing");job.status=failedPhase.startsWith("import")?"import-queued":failedPhase.startsWith("roll")?"rollback-queued":"queued";job.phase=job.status;job.errorMessage=null;job.cancelRequested=false;job.completedAt=null;job.progressPercent=0;job.updatedAt=Instant.now();PurchaseImportBatchService.syncPayload(job);return job;}
    @Transactional public ImportJob requestRollback(UUID id){var job=editable(id);if(!List.of("completed","completed-with-errors").contains(job.status))throw AppException.conflict("只有已入库任务可以回滚");job.status="rollback-queued";job.phase="rollback-queued";job.progressPercent=0;job.errorMessage=null;job.updatedAt=Instant.now();PurchaseImportBatchService.syncPayload(job);return job;}
    @Transactional public void prepareParsing(UUID id){rows.deleteByJobId(id);var job=owned(id);job.totalRows=job.processedRows=job.validRows=job.errorRows=job.addedRows=job.updatedRows=job.conflictRows=job.progressPercent=0;job.status="parsing";job.phase="parsing";job.errorMessage=null;var payload=(ObjectNode)job.payload;for(var key:List.of("sheetSummaries","textParseMillis","generatedSkuRows","warningCount","embeddedImageErrors","embeddedImageError"))payload.remove(key);job.heartbeatAt=Instant.now();job.updatedAt=job.heartbeatAt;PurchaseImportBatchService.syncPayload(job);}
    @Transactional public void recordParseSummary(UUID id,StreamingPurchaseWorkbookReader.ReadResult result,long elapsedMs,int generatedSkuRows,int warningCount){var job=owned(id);var payload=(ObjectNode)job.payload;payload.put("textParseMillis",elapsedMs).put("generatedSkuRows",generatedSkuRows).put("warningCount",warningCount);var array=payload.putArray("sheetSummaries");for(var sheet:result.sheets()){var node=array.addObject();node.put("sheetName",sheet.sheetName()).put("recognized",sheet.recognized()).put("headerRow",sheet.headerRow()).put("dataRows",sheet.dataRows()).put("ignoredRows",sheet.ignoredRows());var recognized=node.putArray("recognizedColumns");sheet.recognizedColumns().forEach(recognized::add);var unknown=node.putArray("unknownColumns");sheet.unknownColumns().forEach(unknown::add);var missing=node.putArray("missingColumns");sheet.missingColumns().forEach(missing::add);}job.updatedAt=Instant.now();PurchaseImportBatchService.syncPayload(job);}
    @Transactional public void recordEmbeddedImageFailure(UUID id,Exception error){var job=owned(id);var payload=(ObjectNode)job.payload;payload.put("embeddedImageErrors",payload.path("embeddedImageErrors").asInt()+1);payload.put("embeddedImageError",PurchaseImportBatchService.shortMessage(error.getMessage()));PurchaseImportBatchService.syncPayload(job);}
    @Transactional public void recordEmbeddedImageFailures(UUID id,int count){if(count<=0)return;var job=owned(id);var payload=(ObjectNode)job.payload;payload.put("embeddedImageErrors",payload.path("embeddedImageErrors").asInt()+count);PurchaseImportBatchService.syncPayload(job);}
    @Transactional public void markImporting(UUID id){var job=owned(id);job.status="importing";job.phase="images";job.heartbeatAt=Instant.now();job.updatedAt=job.heartbeatAt;PurchaseImportBatchService.syncPayload(job);}
    @Transactional public void markRollingBack(UUID id){var job=owned(id);job.status="rolling-back";job.phase="rolling-back";job.heartbeatAt=Instant.now();job.updatedAt=job.heartbeatAt;PurchaseImportBatchService.syncPayload(job);}
    @Transactional public void markFailed(UUID id,String phase,Exception error){var job=owned(id);job.status="failed";job.phase="failed";job.errorMessage=PurchaseImportBatchService.shortMessage(error.getMessage()==null?error.getClass().getSimpleName():error.getMessage());job.completedAt=Instant.now();job.updatedAt=job.completedAt;((ObjectNode)job.payload).put("failedPhase",phase);PurchaseImportBatchService.syncPayload(job);}
    @Transactional public void recoverStaleJobs(){var before=Instant.now().minusSeconds(300);for(var job:jobs.findByJobTypeAndStatusInAndHeartbeatAtBefore(JOB_TYPE,List.of("parsing","importing","rolling-back"),before)){job.status="parsing".equals(job.status)?"queued":"importing".equals(job.status)?"import-queued":"rollback-queued";job.phase=job.status;job.errorMessage="服务重启后任务已自动恢复排队";job.updatedAt=Instant.now();PurchaseImportBatchService.syncPayload(job);}}
    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString="${app.purchase-import.cleanup-delay-ms:3600000}") public void cleanupFinishedRawFiles(){for(var job:jobs.findTop20ByJobTypeAndStatusInAndSourceObjectKeyIsNotNullOrderByUpdatedAt(JOB_TYPE,List.of("completed","completed-with-errors","cancelled","rolled-back")))cleanupRaw(job.id);}
    public void cleanupRaw(UUID id){if(transactions==null)cleanupRawLocked(id);else transactions.executeWithoutResult(ignored->cleanupRawLocked(id));}
    private void cleanupRawLocked(UUID id){var job=jobs.findLockedById(id).orElse(null);if(job==null||!List.of("completed","completed-with-errors","cancelled","rolled-back").contains(job.status))return;var partRows=parts.findByJobIdOrderByPartNumber(id);boolean removed=storage.removeRaw(job.sourceObjectKey);for(var part:partRows)removed&=storage.removeRaw(part.objectKey);if(removed){job.sourceObjectKey=null;partRows.forEach(part->part.objectKey="");parts.saveAll(partRows);jobs.save(job);}}
    public JobView view(UUID id){var job=owned(id);return toView(job);}
    public Page<JobView> list(Pageable pageable){return list(pageable,false);}
    public Page<JobView> list(Pageable pageable,boolean archived){return jobs.findVisibleJobs(JOB_TYPE,archived,pageable).map(this::toView);}
    public Page<RowView> rowPage(UUID id,String status,Pageable pageable){owned(id);var page=status==null||status.isBlank()?rows.findByJobIdOrderBySourceRow(id,pageable):rows.findByJobIdAndValidationStatusOrderBySourceRow(id,status,pageable);return page.map(r->new RowView(r.sourceSheet,r.sourceRow,r.sku,r.validationStatus,r.importAction,r.errorMessage,r.payload));}
    public List<DuplicateGroupView> duplicateGroups(UUID id){owned(id);return rows.findConflictSkus(id).stream().map(sku->new DuplicateGroupView(sku,rows.findByJobIdAndSkuAndValidationStatusOrderBySourceRow(id,sku,"conflict").stream().map(row->new DuplicateChoice(row.sourceSheet,row.sourceRow)).toList())).toList();}
    public List<ImageErrorView> imageErrors(UUID id){owned(id);return imageEntries.findByJobIdOrderByFileName(id).stream().filter(r->"failed".equals(r.status)).map(r->new ImageErrorView(r.sku,r.imageType,r.fileName,r.errorMessage)).toList();}
    public Page<ImageErrorView> imageErrorPage(UUID id,Pageable pageable){owned(id);return imageEntries.findByJobIdAndStatusOrderByFileName(id,"failed",pageable).map(r->new ImageErrorView(r.sku,r.imageType,r.fileName,r.errorMessage));}
    private ImportJob editable(UUID id){var job=jobs.findLockedById(id).orElseThrow(()->AppException.notFound("采购导入任务不存在"));if(!JOB_TYPE.equals(job.jobType))throw AppException.notFound("采购导入任务不存在");if(job.archivedAt!=null)throw AppException.conflict("任务已归档，请先恢复后再操作");return job;}
    private ImportJob owned(UUID id){var job=jobs.findById(id).orElseThrow(()->AppException.notFound("采购导入任务不存在"));if(!JOB_TYPE.equals(job.jobType))throw AppException.notFound("采购导入任务不存在");return job;}
    private void resolveDuplicates(UUID id,Map<String,DuplicateSelection> selections){var conflictSkus=rows.findConflictSkus(id);for(var sku:conflictSkus){var choice=selections.get(sku);if(choice==null)throw AppException.unprocessable("重复SKU "+sku+" 尚未选择保留记录");var group=rows.findByJobIdAndSkuAndValidationStatusOrderBySourceRow(id,sku,"conflict");var selected=group.stream().filter(row->row.sourceRow==choice.sourceRow()&&row.sourceSheet.equals(choice.sourceSheet())).findFirst().orElseThrow(()->AppException.unprocessable("重复SKU "+sku+" 的保留记录无效，请刷新任务后重试"));for(var row:group){if(row.id.equals(selected.id)){row.validationStatus="valid";row.errorMessage=null;}else{row.validationStatus="duplicate-skipped";row.importAction="skip";row.errorMessage="同一文件内SKU重复，已按用户选择忽略";}}rows.saveAll(group);}if(!rows.findConflictSkus(id).isEmpty())throw AppException.unprocessable("仍有重复SKU尚未处理");}
    private JobView toView(ImportJob j){var partRows=parts.findByJobIdOrderByPartNumber(j.id);var partViews=partRows.stream().map(p->new ImagePartView(p.partNumber,p.originalName,p.status,p.sizeBytes,p.processedBytes,p.errorMessage,p.processedAt)).toList();return new JobView(j.id,j.status,j.phase,j.sourceName,j.totalRows,j.processedRows,j.validRows,j.errorRows,j.addedRows,j.updatedRows,j.conflictRows,j.progressPercent,partRows.size(),partViews,imageEntries.countByJobIdAndStatus(j.id,"failed")+j.payload.path("embeddedImageErrors").asLong(0),j.errorMessage,j.payload,j.createdAt,j.updatedAt,j.completedAt,j.rolledBackAt,j.archivedAt);}
    private static String safe(String value){var v=value.replaceAll("[\\r\\n\\\\/]","_");return v.substring(0,Math.min(255,v.length()));}
    public record JobView(UUID id,String status,String phase,String sourceName,int totalRows,int processedRows,int validRows,int errorRows,int addedRows,int updatedRows,int conflictRows,int progressPercent,int imageParts,List<ImagePartView> imagePartDetails,long imageErrors,String error,tools.jackson.databind.JsonNode summary,Instant createdAt,Instant updatedAt,Instant completedAt,Instant rolledBackAt,Instant archivedAt){}
    public record ImagePartView(int partNumber,String fileName,String status,long sizeBytes,long processedBytes,String error,Instant processedAt){}
    public record RowView(String sourceSheet,int sourceRow,String sku,String status,String action,String error,tools.jackson.databind.JsonNode payload){}
    public record DuplicateSelection(String sourceSheet,int sourceRow){}
    public record DuplicateChoice(String sourceSheet,int sourceRow){}
    public record DuplicateGroupView(String sku,List<DuplicateChoice> choices){}
    public record ImageErrorView(String sku,String type,String fileName,String error){}
}
