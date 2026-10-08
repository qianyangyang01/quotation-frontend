package com.milano.quotation.fob;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface FobPurchaseRepository extends JpaRepository<FobPurchaseProduct, String> {
    List<FobPurchaseProduct> findBySkuIn(Collection<String> skus);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from FobPurchaseProduct p where p.sku in :skus order by p.sku")
    List<FobPurchaseProduct> findLocked(@Param("skus") Collection<String> skus);
}
