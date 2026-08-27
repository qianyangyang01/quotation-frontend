package com.milano.quotation.imports;
import org.springframework.data.domain.Page;import org.springframework.data.domain.Pageable;import org.springframework.data.jpa.repository.JpaRepository;import org.springframework.data.jpa.repository.Modifying;import org.springframework.data.jpa.repository.Query;import java.util.*;
public interface PurchaseImportRowRepository extends JpaRepository<PurchaseImportRow,UUID>{
    interface SkuId {String getSku();UUID getId();}
    List<PurchaseImportRow> findByJobIdOrderBySourceRow(UUID jobId);
    Optional<PurchaseImportRow> findFirstByJobIdAndSourceSheetAndSourceRow(UUID jobId,String sourceSheet,int sourceRow);
    Page<PurchaseImportRow> findByJobIdOrderBySourceRow(UUID jobId,Pageable pageable);
    Page<PurchaseImportRow> findByJobIdAndValidationStatusOrderBySourceRow(UUID jobId,String status,Pageable pageable);
    List<PurchaseImportRow> findByJobIdAndSkuAndValidationStatusOrderBySourceRow(UUID jobId,String sku,String status);
    List<PurchaseImportRow> findByJobIdAndValidationStatusAndAppliedAtIsNullOrderBySourceRow(UUID jobId,String status,Pageable pageable);
    @Query("select r.sku as sku,r.id as id from PurchaseImportRow r where r.jobId=:jobId and r.validationStatus='valid'") List<SkuId> findValidSkuIds(UUID jobId);
    long countByJobId(UUID jobId);long countByJobIdAndValidationStatus(UUID jobId,String status);
    long countByJobIdAndValidationStatusAndImportAction(UUID jobId,String status,String action);
    long countByJobIdAndAppliedAtIsNotNull(UUID jobId);
    @Query("select distinct r.sku from PurchaseImportRow r where r.jobId=:jobId and r.validationStatus='conflict' order by r.sku") List<String> findConflictSkus(UUID jobId);
    @Modifying @Query(value="""
            UPDATE purchase_import_row r
               SET validation_status='conflict',
                   error_message=concat_ws('；',nullif(r.error_message,''),'同一文件内SKU重复')
             WHERE r.job_id=:jobId AND r.validation_status='valid' AND r.sku IN (
                 SELECT sku FROM purchase_import_row WHERE job_id=:jobId AND validation_status='valid' GROUP BY sku HAVING count(*)>1
             )
            """,nativeQuery=true)
    int markFileDuplicates(UUID jobId);
    void deleteByJobId(UUID jobId);
}
