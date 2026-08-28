package com.milano.quotation.supplierrecord;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

interface SupplierRecordRepository extends JpaRepository<SupplierRecord, UUID> {
    @Query("""
            select record from SupplierRecord record
            where (:query = ''
                or lower(record.name) like lower(concat('%', :query, '%'))
                or lower(coalesce(record.contactRole, '')) like lower(concat('%', :query, '%'))
                or lower(coalesce(record.relationshipNotes, '')) like lower(concat('%', :query, '%')))
              and (:industryBelt = '' or record.industryBelt = :industryBelt)
              and (:rating = '' or record.rating = :rating)
            order by record.updatedAt desc
            """)
    Page<SupplierRecord> search(@Param("query") String query,
                                @Param("industryBelt") String industryBelt,
                                @Param("rating") String rating,
                                Pageable pageable);
}
