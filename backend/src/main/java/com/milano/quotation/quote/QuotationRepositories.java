package com.milano.quotation.quote;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

interface QuotationRecordRepository extends JpaRepository<QuotationRecordEntity,UUID>{
    @org.springframework.data.jpa.repository.Query(value="select * from quotation_record where payload->'quotationBatch'->>'id' = :batchId and owner_account = :owner order by (payload->'quotationBatch'->>'index')::integer", nativeQuery=true)
    List<QuotationRecordEntity> findBatch(@org.springframework.data.repository.query.Param("batchId") String batchId, @org.springframework.data.repository.query.Param("owner") String owner);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select r from QuotationRecordEntity r where r.id = :id")
    java.util.Optional<QuotationRecordEntity> lockById(@org.springframework.data.repository.query.Param("id") UUID id);
    org.springframework.data.domain.Page<QuotationRecordEntity> findByLifecycleStateIn(java.util.Collection<String> states, org.springframework.data.domain.Pageable pageable);
    org.springframework.data.domain.Page<QuotationRecordEntity> findByOwnerAccountAndLifecycleStateIn(String owner, java.util.Collection<String> states, org.springframework.data.domain.Pageable pageable);
    java.util.Optional<QuotationRecordEntity> findByQuoteNo(String quoteNo);
    List<QuotationRecordEntity> findAllByOrderByCreatedAtDesc();
    List<QuotationRecordEntity> findByOwnerAccountOrderByCreatedAtDesc(String ownerAccount);
    org.springframework.data.domain.Page<QuotationRecordEntity> findByOwnerAccount(String ownerAccount, org.springframework.data.domain.Pageable pageable);
}
interface QuotationTemplateRepository extends JpaRepository<QuotationTemplateEntity,UUID>{
    List<QuotationTemplateEntity> findByOwnerAccountOrderByUpdatedAtDesc(String ownerAccount);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_READ)
    @org.springframework.data.jpa.repository.Query("select t from QuotationTemplateEntity t where t.id in :ids order by t.id")
    List<QuotationTemplateEntity> lockSources(@org.springframework.data.repository.query.Param("ids") java.util.Collection<UUID> ids);
}
interface QuotationDraftRepository extends JpaRepository<QuotationDraftEntity,String>{}
