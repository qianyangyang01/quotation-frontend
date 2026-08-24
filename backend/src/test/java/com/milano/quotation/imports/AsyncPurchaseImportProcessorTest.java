package com.milano.quotation.imports;

import com.milano.quotation.storage.AssetStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AsyncPurchaseImportProcessorTest {
    private ImportJobRepository jobs;private AsyncPurchaseImportService async;private StreamingPurchaseWorkbookReader reader;private PurchaseImportRowMapper mapper;private PurchaseImportBatchService batches;private PurchaseImportImageService images;private AssetStorageService storage;private AsyncPurchaseImportProcessor processor;
    @BeforeEach void setup(){jobs=mock(ImportJobRepository.class);async=mock(AsyncPurchaseImportService.class);reader=mock(StreamingPurchaseWorkbookReader.class);mapper=mock(PurchaseImportRowMapper.class);batches=mock(PurchaseImportBatchService.class);images=mock(PurchaseImportImageService.class);storage=mock(AssetStorageService.class);processor=new AsyncPurchaseImportProcessor(jobs,async,reader,mapper,batches,images,storage);}

    @Test void skipsPollingWhileAnotherLargeJobRuns(){when(jobs.existsByJobTypeAndStatusIn(anyString(),any())).thenReturn(true);processor.poll();verify(jobs,never()).findFirstByJobTypeAndStatusOrderByCreatedAt(anyString(),anyString());}

    @Test void ignoresJobAlreadyClaimedByAnotherWorker(){var job=job("queued");queue(job,"queued");when(jobs.claim(any(),anyString(),anyString(),anyString(),any())).thenReturn(0);processor.poll();verify(async,never()).prepareParsing(any());}

    @Test void parsesQueuedWorkbookDetectsDuplicateAndBecomesReady(){
        var job=job("queued");queue(job,"queued");when(storage.openRaw("source")).thenReturn(new ByteArrayInputStream(new byte[]{1}));
        doAnswer(call->{@SuppressWarnings("unchecked") Consumer<StreamingPurchaseWorkbookReader.RawRow> consumer=call.getArgument(1);consumer.accept(new StreamingPurchaseWorkbookReader.RawRow(2,new String[]{"a"}));consumer.accept(new StreamingPurchaseWorkbookReader.RawRow(3,new String[]{"b"}));return null;}).when(reader).read(any(),any());
        var payload=JsonMapper.builder().build().createObjectNode();when(mapper.map(anyInt(),any())).thenAnswer(i->new PurchaseImportRowMapper.MappedRow(i.getArgument(0),"SKU-1",payload,List.of(),List.of()));when(jobs.findById(job.id)).thenReturn(Optional.of(job));
        processor.poll();verify(async).prepareParsing(job.id);verify(batches).stage(eq(job.id),argThat(list->list.size()==2&&list.get(1).errors().contains("同一文件内SKU重复")));verify(batches).ready(job.id);
    }

    @Test void parsingRespectsCancelAndReportsFailures(){
        var cancelled=job("queued");cancelled.cancelRequested=true;queue(cancelled,"queued");when(storage.openRaw("source")).thenReturn(new ByteArrayInputStream(new byte[]{1}));doNothing().when(reader).read(any(),any());when(jobs.findById(cancelled.id)).thenReturn(Optional.of(cancelled));processor.poll();verify(batches).cancelled(cancelled.id);
        reset(jobs,reader);var failed=job("queued");queue(failed,"queued");when(storage.openRaw("source")).thenThrow(new IllegalStateException("down"));processor.poll();verify(async).markFailed(eq(failed.id),eq("parsing"),any());
    }

    @Test void importsQueuedJobAndReportsImportFailure(){
        var job=job("import-queued");queue(job,"import-queued");when(jobs.findById(job.id)).thenReturn(Optional.of(job));processor.poll();verify(async).markImporting(job.id);verify(images).processAll(job.id);verify(batches).apply(job.id);
        reset(jobs,async,images,batches);var failed=job("import-queued");queue(failed,"import-queued");doThrow(new IllegalStateException("bad zip")).when(images).processAll(failed.id);processor.poll();verify(async).markFailed(eq(failed.id),eq("importing"),any());
    }

    @Test void rollsBackWithPriorityAndReportsFailure(){
        var job=job("rollback-queued");queue(job,"rollback-queued");processor.poll();verify(async).markRollingBack(job.id);verify(batches).rollback(job.id);
        reset(jobs,async,batches);var failed=job("rollback-queued");queue(failed,"rollback-queued");doThrow(new IllegalStateException("conflict")).when(batches).rollback(failed.id);processor.poll();verify(async).markFailed(eq(failed.id),eq("rolling-back"),any());
    }

    private void queue(ImportJob job,String status){when(jobs.existsByJobTypeAndStatusIn(anyString(),any())).thenReturn(false);when(jobs.findFirstByJobTypeAndStatusOrderByCreatedAt(AsyncPurchaseImportService.JOB_TYPE,"rollback-queued")).thenReturn("rollback-queued".equals(status)?Optional.of(job):Optional.empty());when(jobs.findFirstByJobTypeAndStatusOrderByCreatedAt(AsyncPurchaseImportService.JOB_TYPE,"import-queued")).thenReturn("import-queued".equals(status)?Optional.of(job):Optional.empty());when(jobs.findFirstByJobTypeAndStatusOrderByCreatedAt(AsyncPurchaseImportService.JOB_TYPE,"queued")).thenReturn("queued".equals(status)?Optional.of(job):Optional.empty());when(jobs.claim(eq(job.id),eq(AsyncPurchaseImportService.JOB_TYPE),eq(status),anyString(),any())).thenReturn(1);}
    private static ImportJob job(String status){var j=new ImportJob();j.id=UUID.randomUUID();j.status=status;j.phase=status;j.jobType=AsyncPurchaseImportService.JOB_TYPE;j.sourceObjectKey="source";j.payload=JsonMapper.builder().build().createObjectNode();j.createdAt=Instant.now();j.updatedAt=j.createdAt;return j;}
}
