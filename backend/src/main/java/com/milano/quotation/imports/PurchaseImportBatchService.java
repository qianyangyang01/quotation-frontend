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
    private final ImportJobRepository jobs;private final PurchaseImportRowRepository rows;private final PurchaseProductRepository products;private final PurchaseImportJdbcService jdbcImports;private final EntityManager entityManager;private final TransactionTemplate transactions;
    public PurchaseImportBatchService(ImportJobRepository jobs,PurchaseImportRowRepository rows,PurchaseProductRepository products,PurchaseImportJdbcService jdbcImports,EntityManager entityManager,PlatformTransactionManager transactionManager){this.jobs=jobs;this.rows=rows;this.products=products;this.jdbcImports=jdbcImports;this.entityManager=entityManager;this.transactions=new TransactionTemplate(transactionManager);}

    @Transactional
    public void stage(UUID jobId,List<PurchaseImportRowMapper.MappedRow> mapped){
        var job=jobs.findById(jobId).orElseThrow();var skus=mapped.stream().filter(r->r.errors().isEmpty()).map(PurchaseImportRowMapper.MappedRow::sku).toList();var existing=new HashMap<String,Long>();products.findAllBySkuIn(skus).forEach(p->existing.put(p.sku,p.version));var now=Instant.now();var entities=new ArrayList<PurchaseImportRow>();int valid=0,errors=0,added=0,updated=0;
        for(var item:mapped){var row=new PurchaseImportRow();row.id=UUID.randomUUID();row.jobId=jobId;row.sourceSheet=item.sourceSheet();row.sourceRow=item.sourceRow();row.sku=item.sku().isBlank()?"INVALID-"+Math.abs(item.sourceSheet().hashCode())+"-R"+item.sourceRow():item.sku();row.payload=item.payload();row.createdAt=now;if(item.errors().isEmpty()){row.validationStatus="valid";row.importAction=existing.containsKey(item.sku())?"update":"insert";row.expectedVersion=existing.get(item.sku());valid++;if("update".equals(row.importAction))updated++;else added++;}else{row.validationStatus="error";row.importAction="skip";row.errorMessage=shortMessage(String.join("；",item.errors()));errors++;}entities.add(row);}
        rows.saveAll(entities);entityManager.flush();entityManager.clear();job.processedRows+=mapped.size();job.totalRows=job.processedRows;job.validRows+=valid;job.errorRows+=errors;job.addedRows+=added;job.updatedRows+=updated;job.heartbeatAt=now;job.updatedAt=now;job.progressPercent=Math.min(95,Math.max(1,job.processedRows/1000));syncPayload(job);jobs.save(job);
    }

    @Transactional public void ready(UUID jobId){rows.markFileDuplicates(jobId);var job=jobs.findById(jobId).orElseThrow();job.totalRows=(int)rows.countByJobId(jobId);job.validRows=(int)rows.countByJobIdAndValidationStatus(jobId,"valid");job.errorRows=(int)rows.countByJobIdAndValidationStatus(jobId,"error");job.conflictRows=(int)rows.countByJobIdAndValidationStatus(jobId,"conflict");job.addedRows=(int)rows.countByJobIdAndValidationStatusAndImportAction(jobId,"valid","insert");job.updatedRows=(int)rows.countByJobIdAndValidationStatusAndImportAction(jobId,"valid","update");job.status="ready";job.phase="ready";job.progressPercent=100;job.updatedAt=Instant.now();job.heartbeatAt=job.updatedAt;syncPayload(job);}
    @Transactional public void fail(UUID jobId,Exception error){jobs.findById(jobId).ifPresent(job->{job.status="failed";job.phase="failed";job.errorMessage=shortMessage(error.getMessage()==null?error.getClass().getSimpleName():error.getMessage());job.updatedAt=Instant.now();job.completedAt=job.updatedAt;syncPayload(job);});}
    @Transactional public void cancelled(UUID jobId){var job=jobs.findById(jobId).orElseThrow();job.status="cancelled";job.phase="cancelled";job.updatedAt=Instant.now();job.completedAt=job.updatedAt;syncPayload(job);}

    public void apply(UUID jobId){while(true){var batch=rows.findByJobIdAndValidationStatusAndAppliedAtIsNullOrderBySourceRow(jobId,"valid",PageRequest.of(0,BATCH_SIZE));if(batch.isEmpty())break;transactions.executeWithoutResult(status->applyBatch(jobId,batch));}finishApply(jobId);}
    @Transactional
    public void applyBatch(UUID jobId,List<PurchaseImportRow> batch){var job=jobs.findById(jobId).orElseThrow();if(job.cancelRequested)throw AppException.conflict("入库任务已请求取消");var outcome=jdbcImports.apply(jobId,batch.stream().map(r->r.id).toList(),job.sourceHash);job.conflictRows+=outcome.conflicts();job.validRows-=outcome.conflicts();job.processedRows=(int)rows.countByJobIdAndAppliedAtIsNotNull(jobId);job.progressPercent=job.totalRows==0?100:Math.min(99,(int)(job.processedRows*100L/Math.max(1,job.validRows+job.conflictRows)));job.heartbeatAt=Instant.now();job.updatedAt=job.heartbeatAt;syncPayload(job);}
    @Transactional public void finishApply(UUID jobId){var job=jobs.findById(jobId).orElseThrow();job.status=job.conflictRows>0||job.errorRows>0||job.payload.path("embeddedImageErrors").asInt()>0?"completed-with-errors":"completed";job.phase="completed";job.progressPercent=100;job.errorMessage=null;job.completedAt=Instant.now();job.updatedAt=job.completedAt;syncPayload(job);jobs.save(job);}

    @Transactional public void rollback(UUID jobId){var conflicts=jdbcImports.lockAndCountRollbackConflicts(jobId);if(conflicts>0)throw AppException.conflict("有 "+conflicts+" 条商品在导入后被修改，已阻止整批回滚");while(true){var ids=jdbcImports.nextRollbackIds(jobId);if(ids.isEmpty())break;jdbcImports.rollback(jobId,ids);jobs.heartbeat(jobId,AsyncPurchaseImportService.JOB_TYPE,Instant.now());}finishRollback(jobId);}
    @Transactional public void finishRollback(UUID jobId){var job=jobs.findById(jobId).orElseThrow();job.status="rolled-back";job.phase="rolled-back";job.progressPercent=100;job.errorMessage=null;job.rolledBackAt=Instant.now();job.updatedAt=job.rolledBackAt;syncPayload(job);}
    static void syncPayload(ImportJob job){var payload=job.payload instanceof ObjectNode o?o:(ObjectNode)tools.jackson.databind.node.JsonNodeFactory.instance.objectNode();payload.put("phase",job.phase==null?job.status:job.phase).put("totalRows",job.totalRows).put("processedRows",job.processedRows).put("validRows",job.validRows).put("errorRows",job.errorRows).put("addedRows",job.addedRows).put("updatedRows",job.updatedRows).put("conflictRows",job.conflictRows).put("progressPercent",job.progressPercent);job.payload=payload;}
    static String shortMessage(String value){var text=value==null?"处理失败":value;return text.substring(0,Math.min(1000,text.length()));}
}
