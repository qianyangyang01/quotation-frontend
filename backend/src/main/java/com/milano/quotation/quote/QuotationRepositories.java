package com.milano.quotation.quote;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

interface QuotationRecordRepository extends JpaRepository<QuotationRecordEntity,UUID>{
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select q from QuotationRecordEntity q where q.id = :id")
    java.util.Optional<QuotationRecordEntity> lockById(@org.springframework.data.repository.query.Param("id") UUID id);
    java.util.Optional<QuotationRecordEntity> findByQuoteNo(String quoteNo);
    List<QuotationRecordEntity> findAllByOrderByCreatedAtDesc();
    List<QuotationRecordEntity> findByOwnerAccountOrderByCreatedAtDesc(String ownerAccount);
    org.springframework.data.domain.Page<QuotationRecordEntity> findByOwnerAccount(String ownerAccount, org.springframework.data.domain.Pageable pageable);
}
interface QuotationTemplateRepository extends JpaRepository<QuotationTemplateEntity,UUID>{List<QuotationTemplateEntity> findByOwnerAccountOrderByUpdatedAtDesc(String ownerAccount);}
interface QuotationDraftRepository extends JpaRepository<QuotationDraftEntity,String>{}
