package com.milano.quotation.imports;

import com.milano.quotation.common.AppException;
import com.milano.quotation.purchase.PurchaseProductRepository;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.*;

@Service
public class PurchaseImportBatchService {
    public static final int BATCH_SIZE=2_000;
    private final ImportJobRepository jobs;private final PurchaseImportRowRepository rows;private final PurchaseProductRepository products;private final PurchaseImportJdbcService jdbcImports;private final EntityManager entityManager;private final TransactionTemplate transactions;private final PurchaseImportContinuationService continuation;
    @org.springframework.beans.factory.annotation.Autowired public PurchaseImportBatchService(ImportJobRepository jobs,PurchaseImportRowRepository rows,PurchaseProductRepository products,PurchaseImportJdbcService jdbcImports,EntityManager entityManager,PlatformTransactionManager transactionManager,PurchaseImportContinuationService continuation){this.jobs=jobs;this.rows=rows;this.products=products;this.jdbcImports=jdbcImports;this.entityManager=entityManager;this.transactions=new TransactionTemplate(transactionManager);this.continuation=continuation;}
    PurchaseImportBatchService(ImportJobRepository jobs,PurchaseImportRowRepository rows,PurchaseProductRepository products,PurchaseImportJdbcService jdbcImports,EntityManager entityManager,PlatformTransactionManager transactionManager){this(jobs,rows,products,jdbcImports,entityManager,transactionManager,null);}

    @Transactional
    public void stage(UUID jobId,List<PurchaseImportRowMapper.MappedRow> mapped){
        var job=jobs.findById(jobId).orElseThrow();var legacy=PurchaseImportProfile.isLegacy(job);var skus=mapped.stream().filter(r->r.errors().isEmpty()).map(PurchaseImportRowMapper.MappedRow::sku).toList();record Existing(long version,boolean legacy){}var existing=new HashMap<String,Existing>();products.findAllBySkuIn(skus).forEach(p->existing.put(p.sku,new Existing(p.version,p.payload!=null&&PurchaseImportProfile.LEGACY_DATA_SOURCE.equals(p.payload.path("dataSource").asText()))));var now=Instant.now();var entities=new ArrayList<PurchaseImportRow>();int valid=0,errors=0,added=0,updated=0;
        for(var item:mapped){var row=new PurchaseImportRow();row.id=UUID.randomUUID();row.jobId=jobId;row.sourceSheet=item.sourceSheet();row.sourceRow=item.sourceRow();row.sku=stagedSku(item);row.payload=item.payload();row.sourceContentHash=item.sourceContentHash();row.sourceContentHashWithoutSku=item.sourceContentHashWithoutSku();row.createdAt=now;var current=existing.get(item.sku());if(item.errors().isEmpty()&&!(legacy&&current!=null&&!current.legacy())){row.validationStatus="valid";row.importAction=current!=null?"update":"insert";row.expectedVersion=current==null?null:current.version();valid++;if("update".equals(row.importAction))updated++;else added++;}else{row.validationStatus="error";row.importAction="skip";row.errorMessage=shortMessage(legacy&&current!=null&&!current.legacy()?"SKU已存在于当前标准采购数据，2026旧数据未覆盖":""+String.join("；",item.errors()));errors++;}entities.add(row);}
        rows.saveAll(entities);entityManager.flush();entityManager.clear();job.processedRows+=mapped.size();job.totalRows=job.processedRows;job.validRows+=valid;job.errorRows+=errors;job.addedRows+=added;job.updatedRows+=updated;job.heartbeatAt=now;job.updatedAt=now;job.progressPercent=Math.min(95,Math.max(1,job.processedRows/1000));syncPayload(job);jobs.save(job);
    }

    @Transactional public void ready(UUID jobId){var job=jobs.findById(jobId).orElseThrow();if(continuation!=null)continuation.refresh(job);if(PurchaseImportProfile.isLegacy(job))rows.keepLastFileDuplicate(jobId);else rows.markFileDuplicates(jobId);recount(job);job.status="ready";job.phase="ready";job.progressPercent=100;job.updatedAt=Instant.now();job.heartbeatAt=job.updatedAt;syncPayload(job);}
    @Transactional public void fail(UUID jobId,Exception error){jobs.findById(jobId).ifPresent(job->{job.status="failed";job.phase="failed";job.errorMessage=shortMessage(error.getMessage()==null?error.getClass().getSimpleName():error.getMessage());job.updatedAt=Instant.now();job.completedAt=job.updatedAt;syncPayload(job);});}
    @Transactional public void cancelled(UUID jobId){var job=jobs.findById(jobId).orElseThrow();job.status="cancelled";job.phase="cancelled";job.updatedAt=Instant.now();job.completedAt=job.updatedAt;syncPayload(job);}

    public void apply(UUID jobId){transactions.executeWithoutResult(status->{var job=jobs.findById(jobId).orElseThrow();if(continuation!=null){continuation.refresh(job);PurchaseImportContinuationService.requireUnblocked(job);if(PurchaseImportContinuationService.enabled(job)){if(PurchaseImportProfile.isLegacy(job))rows.keepLastFileDuplicate(jobId);else rows.markFileDuplicates(jobId);recount(job);if(job.conflictRows>0)throw AppException.conflict("来源历史发生变化，仍有重复SKU需要选择；请重新上传校验后确认");}syncPayload(job);}});while(true){var batch=rows.findByJobIdAndValidationStatusAndAppliedAtIsNullOrderBySourceRow(jobId,"valid",PageRequest.of(0,BATCH_SIZE));if(batch.isEmpty())break;transactions.executeWithoutResult(status->applyBatch(jobId,batch));}transactions.executeWithoutResult(status->finishApply(jobId));}
    @Transactional
    public void applyBatch(UUID jobId,List<PurchaseImportRow> batch){var job=jobs.findById(jobId).orElseThrow();if(job.cancelRequested)throw AppException.conflict("入库任务已请求取消");if(continuation!=null)continuation.guardBatch(job,batch.stream().map(r->r.id).toList());var outcome=jdbcImports.apply(jobId,batch.stream().map(r->r.id).toList(),job.sourceHash);if(continuation!=null)continuation.recordRevision(job);if(continuation!=null&&PurchaseImportContinuationService.enabled(job))recount(job);else{job.conflictRows+=outcome.conflicts();job.validRows-=outcome.conflicts();}job.processedRows=(int)rows.countByJobIdAndAppliedAtIsNotNull(jobId);job.progressPercent=job.totalRows==0?100:Math.min(99,(int)(job.processedRows*100L/Math.max(1,job.validRows+job.conflictRows)));job.heartbeatAt=Instant.now();job.updatedAt=job.heartbeatAt;syncPayload(job);}
    @Transactional public void finishApply(UUID jobId){var job=jobs.findById(jobId).orElseThrow();if(continuation!=null)continuation.guardBatch(job,List.of());job.status=job.conflictRows>0||job.errorRows>0||job.payload.path("embeddedImageErrors").asInt()>0?"completed-with-errors":"completed";job.phase="completed";job.progressPercent=100;job.errorMessage=null;job.completedAt=Instant.now();job.updatedAt=job.completedAt;syncPayload(job);jobs.save(job);}

    @Transactional public void rollback(UUID jobId){if(continuation!=null)continuation.lockSource(jobs.findById(jobId).orElseThrow());var conflicts=jdbcImports.lockAndCountRollbackConflicts(jobId);if(conflicts>0)throw AppException.conflict("有 "+conflicts+" 条商品已被修改或被报价、草稿、模板引用，已阻止整批回滚");while(true){var ids=jdbcImports.nextRollbackIds(jobId);if(ids.isEmpty())break;jdbcImports.rollback(jobId,ids);jobs.heartbeat(jobId,AsyncPurchaseImportService.JOB_TYPE,Instant.now());}finishRollback(jobId);}
    @Transactional public void finishRollback(UUID jobId){var job=jobs.findById(jobId).orElseThrow();job.status="rolled-back";job.phase="rolled-back";job.progressPercent=100;job.errorMessage=null;job.rolledBackAt=Instant.now();job.updatedAt=job.rolledBackAt;syncPayload(job);}
    private void recount(ImportJob job){job.totalRows=(int)rows.countByJobId(job.id);job.validRows=(int)rows.countByJobIdAndValidationStatus(job.id,"valid");job.errorRows=(int)rows.countByJobIdAndValidationStatus(job.id,"error");job.conflictRows=(int)rows.countByJobIdAndValidationStatus(job.id,"conflict");job.addedRows=(int)rows.countByJobIdAndValidationStatusAndImportAction(job.id,"valid","insert");job.updatedRows=(int)rows.countByJobIdAndValidationStatusAndImportAction(job.id,"valid","update");}
    static void syncPayload(ImportJob job){var payload=job.payload instanceof ObjectNode o?o:(ObjectNode)tools.jackson.databind.node.JsonNodeFactory.instance.objectNode();payload.put("phase",job.phase==null?job.status:job.phase).put("totalRows",job.totalRows).put("processedRows",job.processedRows).put("validRows",job.validRows).put("errorRows",job.errorRows).put("addedRows",job.addedRows).put("updatedRows",job.updatedRows).put("conflictRows",job.conflictRows).put("progressPercent",job.progressPercent);job.payload=payload;}
    // Invalid source values must remain visible in payload without overflowing
    // the bounded staging lookup key or becoming a truncated, importable SKU.
    static String stagedSku(PurchaseImportRowMapper.MappedRow row) {
        return row.sku().isBlank() || row.sku().length() > 96
                ? "INVALID-" + Integer.toUnsignedString(row.sourceSheet().hashCode()) + "-R" + row.sourceRow()
                : row.sku();
    }
    static String shortMessage(String value){var text=value==null?"处理失败":value;return text.substring(0,Math.min(1000,text.length()));}
}
