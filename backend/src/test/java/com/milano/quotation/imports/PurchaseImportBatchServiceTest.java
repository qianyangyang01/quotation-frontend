package com.milano.quotation.imports;

import com.milano.quotation.common.AppException;
import com.milano.quotation.purchase.PurchaseProduct;
import com.milano.quotation.purchase.PurchaseProductRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PurchaseImportBatchServiceTest {
    private ImportJobRepository jobs; private PurchaseImportRowRepository rows; private PurchaseProductRepository products;
    private PurchaseImportJdbcService jdbcImports; private EntityManager entityManager;
    private PurchaseImportBatchService service; private final varMapper mapperHolder=new varMapper();

    @BeforeEach void setup(){
        jobs=mock(ImportJobRepository.class);rows=mock(PurchaseImportRowRepository.class);products=mock(PurchaseProductRepository.class);
        jdbcImports=mock(PurchaseImportJdbcService.class);entityManager=mock(EntityManager.class);
        var tx=mock(PlatformTransactionManager.class);when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service=new PurchaseImportBatchService(jobs,rows,products,jdbcImports,entityManager,tx);
    }

    @Test void stagesValidInsertUpdateAndErrorsInOneBatch(){
        var job=job("parsing");when(jobs.findById(job.id)).thenReturn(Optional.of(job));
        var existing=mock(PurchaseProduct.class);existing.sku="SKU-2";existing.version=7;when(products.findAllBySkuIn(any())).thenReturn(List.of(existing));
        var valid1=mapped(2,"SKU-1",List.of());var valid2=mapped(3,"SKU-2",List.of());var invalid=mapped(4,"",List.of("SKU不能为空"));
        service.stage(job.id,List.of(valid1,valid2,invalid));
        assertEquals(3,job.totalRows);assertEquals(2,job.validRows);assertEquals(1,job.errorRows);assertEquals(1,job.addedRows);assertEquals(1,job.updatedRows);
        @SuppressWarnings("unchecked") var captured=(List<PurchaseImportRow>)mockingDetails(rows).getInvocations().stream().filter(i->i.getMethod().getName().equals("saveAll")).findFirst().orElseThrow().getArgument(0);
        assertEquals("insert",captured.get(0).importAction);assertEquals("update",captured.get(1).importAction);assertEquals(7,captured.get(1).expectedVersion);assertEquals("skip",captured.get(2).importAction);
        verify(entityManager).flush();verify(entityManager).clear();
    }

    @Test void marksReadyFailedCancelledAndCompleted(){
        var job=job("parsing");when(jobs.findById(job.id)).thenReturn(Optional.of(job));
        service.ready(job.id);assertEquals("ready",job.status);assertEquals(100,job.progressPercent);
        service.fail(job.id,new IllegalArgumentException());assertEquals("failed",job.status);assertEquals("IllegalArgumentException",job.errorMessage);
        service.cancelled(job.id);assertEquals("cancelled",job.status);
        job.errorRows=1;job.errorMessage="服务重启后任务已自动恢复排队";service.finishApply(job.id);assertEquals("completed-with-errors",job.status);assertNull(job.errorMessage);
        job.errorRows=0;job.conflictRows=0;job.errorMessage="服务重启后任务已自动恢复排队";service.finishApply(job.id);assertEquals("completed",job.status);assertNull(job.errorMessage);
        job.errorMessage="服务重启后任务已自动恢复排队";service.finishRollback(job.id);assertEquals("rolled-back",job.status);assertNotNull(job.rolledBackAt);assertNull(job.errorMessage);
    }

    @Test void appliesSuccessAndConflictRows(){
        var job=job("importing");job.validRows=2;job.totalRows=2;when(jobs.findById(job.id)).thenReturn(Optional.of(job));
        var good=row(job.id,"SKU-1");good.productAssetId=UUID.randomUUID();good.physicalAssetId=UUID.randomUUID();var conflict=row(job.id,"SKU-2");
        when(jdbcImports.apply(eq(job.id),anyList(),eq("hash"))).thenReturn(new PurchaseImportJdbcService.ApplyResult(1,1));when(rows.countByJobIdAndAppliedAtIsNotNull(job.id)).thenReturn(1L);
        service.applyBatch(job.id,List.of(good,conflict));
        assertEquals(1,job.conflictRows);assertEquals(1,job.validRows);assertEquals(1,job.processedRows);
    }

    @Test void honorsCancelBeforeDatabaseWrite(){
        var job=job("importing");job.validRows=1;when(jobs.findById(job.id)).thenReturn(Optional.of(job));var row=row(job.id,"SKU");
        job.cancelRequested=true;assertThrows(AppException.class,()->service.applyBatch(job.id,List.of(row)));
        verifyNoInteractions(jdbcImports);
    }

    @Test void appliesAllPagesThenFinishesAndRollsBackAllPages(){
        var job=job("import-queued");when(jobs.findById(job.id)).thenReturn(Optional.of(job));
        when(rows.findByJobIdAndValidationStatusAndAppliedAtIsNullOrderBySourceRow(eq(job.id),eq("valid"),any(Pageable.class))).thenReturn(List.of());
        service.apply(job.id);assertEquals("completed",job.status);verify(jobs).save(job);
        when(jdbcImports.lockAndCountRollbackConflicts(job.id)).thenReturn(0);when(jdbcImports.nextRollbackIds(job.id)).thenReturn(List.of());
        service.rollback(job.id);assertEquals("rolled-back",job.status);
        when(jdbcImports.lockAndCountRollbackConflicts(job.id)).thenReturn(1);assertThrows(AppException.class,()->service.rollback(job.id));
    }

    @Test void rollbackProcessesOwnedAssetsInSetBasedChunks(){
        var job=job("rolling-back");when(jobs.findById(job.id)).thenReturn(Optional.of(job));var rowId=UUID.randomUUID();var asset=UUID.randomUUID();
        when(jdbcImports.lockAndCountRollbackConflicts(job.id)).thenReturn(0);when(jdbcImports.nextRollbackIds(job.id)).thenReturn(List.of(rowId),List.of());when(jdbcImports.rollback(job.id,List.of(rowId))).thenReturn(1);
        service.rollback(job.id);verify(jdbcImports).rollback(job.id,List.of(rowId));assertEquals("rolled-back",job.status);
    }

    @Test void utilityMethodsBoundMessagesAndCreatePayload(){
        var longText="x".repeat(1200);assertEquals(1000,PurchaseImportBatchService.shortMessage(longText).length());assertEquals("处理失败",PurchaseImportBatchService.shortMessage(null));
        var job=new ImportJob();job.status="queued";PurchaseImportBatchService.syncPayload(job);assertEquals("queued",job.payload.path("phase").asText());
    }

    private PurchaseImportRowMapper.MappedRow mapped(int n,String sku,List<String> errors){return new PurchaseImportRowMapper.MappedRow(n,sku,mapperHolder.mapper.createObjectNode().put("sku",sku),errors,List.of());}
    private static PurchaseImportRow row(UUID jobId,String sku){var r=new PurchaseImportRow();r.id=UUID.randomUUID();r.jobId=jobId;r.sku=sku;r.importAction="insert";r.payload=JsonMapper.builder().build().createObjectNode();return r;}
    private static ImportJob job(String status){var j=new ImportJob();j.id=UUID.randomUUID();j.status=status;j.phase=status;j.jobType=AsyncPurchaseImportService.JOB_TYPE;j.sourceHash="hash";j.payload=JsonMapper.builder().build().createObjectNode();j.createdAt=Instant.now();j.updatedAt=j.createdAt;return j;}
    private static final class varMapper{final tools.jackson.databind.ObjectMapper mapper=JsonMapper.builder().build();}
}
