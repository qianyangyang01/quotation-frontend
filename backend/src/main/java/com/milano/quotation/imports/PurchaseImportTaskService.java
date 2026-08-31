package com.milano.quotation.imports;

import com.milano.quotation.audit.AuditService;
import com.milano.quotation.common.AppException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Task-list maintenance never rolls back purchase data or removes stored image bytes. */
@Service
public class PurchaseImportTaskService {
    private final ImportJobRepository jobs;
    private final PurchaseImportRowRepository rows;
    private final AuditService audit;
    public PurchaseImportTaskService(ImportJobRepository jobs, PurchaseImportRowRepository rows, AuditService audit) {
        this.jobs=jobs;this.rows=rows;this.audit=audit;
    }
    @Transactional(readOnly=true)
    public RemovalCheck inspect(UUID id) { return check(require(jobs.findById(id).orElse(null))); }

    @Transactional
    public RemovalCheck delete(UUID id) {
        var job=locked(id);var check=check(job);
        if(!"delete".equals(check.action()))throw AppException.conflict(check.reason());
        // Foreign keys cascade only task staging, parts and manifest rows. Import
        // audit events are independent; products/assets are not cascade targets.
        jobs.delete(job);jobs.flush();
        audit.record("purchase.import-task-delete","purchase-import",id.toString(),"success",
                Map.of("source",job.sourceName,"status",job.status,"appliedRows",check.appliedRows()));
        return check;
    }
    @Transactional
    public RemovalCheck archive(UUID id) {
        var job=locked(id);var check=check(job);
        if(!"archive".equals(check.action()))throw AppException.conflict(check.reason());
        job.archivedAt=Instant.now();job.updatedAt=job.archivedAt;
        audit.record("purchase.import-task-archive","purchase-import",id.toString(),"success",
                Map.of("source",job.sourceName,"status",job.status,"appliedRows",check.appliedRows()));
        return check;
    }
    @Transactional
    public void restore(UUID id) {
        var job=locked(id);
        if(job.archivedAt==null)throw AppException.conflict("任务未归档，请刷新列表");
        job.archivedAt=null;job.updatedAt=Instant.now();
        audit.record("purchase.import-task-restore","purchase-import",id.toString(),"success",Map.of("source",job.sourceName));
    }
    private ImportJob locked(UUID id){return require(jobs.findLockedById(id).orElse(null));}
    private ImportJob require(ImportJob job){
        if(job==null||!AsyncPurchaseImportService.JOB_TYPE.equals(job.jobType))throw AppException.notFound("采购导入任务不存在");
        return job;
    }
    private RemovalCheck check(ImportJob job) {
        long applied=rows.countByJobIdAndAppliedAtIsNotNull(job.id);
        if(job.archivedAt!=null)return new RemovalCheck("blocked",applied,"任务已归档，请先恢复");
        if(!List.of("ready","failed","cancelled","completed","completed-with-errors","rolled-back").contains(job.status))
            return new RemovalCheck("blocked",applied,"任务正在执行或排队，请等待结束后再整理");
        // A completed/rolled-back job with incomplete legacy counters is retained
        // conservatively as well. applied_at remains set after a rollback.
        if(applied>0||job.rolledBackAt!=null
                ||List.of("completed","completed-with-errors","rolled-back").contains(job.status))
            return new RemovalCheck("archive",applied,"任务包含入库历史，仅移入已归档；商品、续传行号和回滚依据全部保留");
        return new RemovalCheck("delete",0,"将彻底删除未入库任务及暂存行、分包和清单记录，无法恢复；采购商品与已有图片不变");
    }
    public record RemovalCheck(String action,long appliedRows,String reason){}
}
