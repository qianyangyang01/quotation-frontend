package com.milano.quotation.imports;

import com.milano.quotation.storage.AssetStorageService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class AsyncPurchaseImportProcessor {
    private final ImportJobRepository jobs;private final AsyncPurchaseImportService service;private final StreamingPurchaseWorkbookReader reader;private final PurchaseImportRowMapper mapper;private final PurchaseImportBatchService batches;private final PurchaseImportImageService images;private final AssetStorageService storage;
    public AsyncPurchaseImportProcessor(ImportJobRepository jobs,AsyncPurchaseImportService service,StreamingPurchaseWorkbookReader reader,PurchaseImportRowMapper mapper,PurchaseImportBatchService batches,PurchaseImportImageService images,AssetStorageService storage){this.jobs=jobs;this.service=service;this.reader=reader;this.mapper=mapper;this.batches=batches;this.images=images;this.storage=storage;}
    @Scheduled(fixedDelayString="${app.purchase-import.poll-delay-ms:1000}")
    public void poll(){service.recoverStaleJobs();if(jobs.existsByJobTypeAndStatusIn(AsyncPurchaseImportService.JOB_TYPE,List.of("parsing","importing","rolling-back")))return;var queued=jobs.findFirstByJobTypeAndStatusOrderByCreatedAt(AsyncPurchaseImportService.JOB_TYPE,"rollback-queued").or(()->jobs.findFirstByJobTypeAndStatusOrderByCreatedAt(AsyncPurchaseImportService.JOB_TYPE,"import-queued")).or(()->jobs.findFirstByJobTypeAndStatusOrderByCreatedAt(AsyncPurchaseImportService.JOB_TYPE,"queued"));queued.ifPresent(this::process);}
    private void process(ImportJob job){var queuedStatus=job.status;var activeStatus="queued".equals(queuedStatus)?"parsing":"import-queued".equals(queuedStatus)?"importing":"rollback-queued".equals(queuedStatus)?"rolling-back":null;if(activeStatus==null||jobs.claim(job.id,AsyncPurchaseImportService.JOB_TYPE,queuedStatus,activeStatus,java.time.Instant.now())!=1)return;if("queued".equals(queuedStatus))parse(job);else if("import-queued".equals(queuedStatus))apply(job);else rollback(job);}
    private void parse(ImportJob job){
        try{
            service.prepareParsing(job.id);var seen=new HashSet<String>();var chunk=new ArrayList<PurchaseImportRowMapper.MappedRow>(PurchaseImportBatchService.BATCH_SIZE);
            try(var input=storage.openRaw(job.sourceObjectKey)){
                reader.read(input,raw->{var mapped=mapper.map(raw.sourceRow(),raw.values());if(!mapped.sku().isBlank()&&!seen.add(mapped.sku())){var errors=new ArrayList<>(mapped.errors());errors.add("同一文件内SKU重复");mapped=new PurchaseImportRowMapper.MappedRow(mapped.sourceRow(),mapped.sku(),mapped.payload(),errors,mapped.warnings());}chunk.add(mapped);if(chunk.size()>=PurchaseImportBatchService.BATCH_SIZE){batches.stage(job.id,List.copyOf(chunk));chunk.clear();var state=jobs.findById(job.id).orElseThrow();if(state.cancelRequested)throw new Cancelled();}});
                if(!chunk.isEmpty())batches.stage(job.id,List.copyOf(chunk));
            }
            if(jobs.findById(job.id).orElseThrow().cancelRequested)batches.cancelled(job.id);else batches.ready(job.id);
        }catch(Cancelled e){batches.cancelled(job.id);cleanup(job.id);}catch(Exception e){service.markFailed(job.id,"parsing",e);}
    }
    private void apply(ImportJob job){try{service.markImporting(job.id);images.processAll(job.id);var current=jobs.findById(job.id).orElseThrow();current.phase="importing";jobs.save(current);batches.apply(job.id);cleanup(job.id);}catch(Exception e){service.markFailed(job.id,"importing",e);}}
    private void rollback(ImportJob job){try{service.markRollingBack(job.id);batches.rollback(job.id);}catch(Exception e){service.markFailed(job.id,"rolling-back",e);}}
    private void cleanup(UUID jobId){try{service.cleanupRaw(jobId);}catch(Exception ignored){/* 定时清理任务会继续重试。 */}}
    private static final class Cancelled extends RuntimeException{}
}
