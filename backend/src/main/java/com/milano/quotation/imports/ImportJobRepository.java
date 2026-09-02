package com.milano.quotation.imports;
import org.springframework.data.domain.Page;import org.springframework.data.domain.Pageable;import org.springframework.data.jpa.repository.JpaRepository;import org.springframework.data.jpa.repository.Modifying;import org.springframework.data.jpa.repository.Query;import org.springframework.data.repository.query.Param;import org.springframework.transaction.annotation.Transactional;import java.time.Instant;import java.util.*;
public interface ImportJobRepository extends JpaRepository<ImportJob,UUID>{
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from ImportJob j where j.id=:id")
    Optional<ImportJob> findLockedById(@Param("id")UUID id);
    @Query("select j from ImportJob j where j.jobType=:type and ((:archived=true and j.archivedAt is not null) or (:archived=false and j.archivedAt is null)) order by j.createdAt desc,j.id desc")
    Page<ImportJob> findVisibleJobs(@Param("type")String type,@Param("archived")boolean archived,Pageable pageable);
    Optional<ImportJob> findFirstByJobTypeAndStatusOrderByCreatedAt(String jobType,String status);
    boolean existsByJobTypeAndStatusIn(String jobType,Collection<String> statuses);
    Page<ImportJob> findByJobTypeOrderByCreatedAtDesc(String jobType,Pageable pageable);
    List<ImportJob> findByJobTypeAndStatusInAndHeartbeatAtBefore(String jobType,Collection<String> statuses,Instant before);
    List<ImportJob> findTop20ByJobTypeAndStatusInAndSourceObjectKeyIsNotNullOrderByUpdatedAt(String jobType,Collection<String> statuses);
    @Modifying @Transactional
    @Query(value="UPDATE import_job SET status=:activeStatus, phase=:activeStatus, heartbeat_at=:now, updated_at=:now WHERE id=:id AND job_type=:jobType AND status=:queuedStatus",nativeQuery=true)
    int claim(@Param("id")UUID id,@Param("jobType")String jobType,@Param("queuedStatus")String queuedStatus,@Param("activeStatus")String activeStatus,@Param("now")Instant now);
    @Modifying @Transactional(propagation=org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    @Query(value="UPDATE import_job SET heartbeat_at=:now, updated_at=:now WHERE id=:id AND job_type=:jobType AND status IN ('parsing','importing','rolling-back')",nativeQuery=true)
    int heartbeat(@Param("id")UUID id,@Param("jobType")String jobType,@Param("now")Instant now);
}
