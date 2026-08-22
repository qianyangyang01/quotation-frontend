package com.milano.quotation.quote;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

interface QuotationRecordRepository extends JpaRepository<QuotationRecordEntity,UUID>{
    List<QuotationRecordEntity> findAllByOrderByCreatedAtDesc();
    List<QuotationRecordEntity> findByOwnerAccountOrderByCreatedAtDesc(String ownerAccount);
}
interface QuotationTemplateRepository extends JpaRepository<QuotationTemplateEntity,UUID>{List<QuotationTemplateEntity> findByOwnerAccountOrderByUpdatedAtDesc(String ownerAccount);}
interface QuotationDraftRepository extends JpaRepository<QuotationDraftEntity,String>{}
