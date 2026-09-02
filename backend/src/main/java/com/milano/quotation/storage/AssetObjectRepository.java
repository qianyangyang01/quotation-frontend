package com.milano.quotation.storage;
import org.springframework.data.jpa.repository.JpaRepository;import org.springframework.data.jpa.repository.Modifying;import org.springframework.data.jpa.repository.Query;import org.springframework.data.repository.query.Param;import java.time.Instant;import java.util.Collection;import java.util.List;import java.util.Optional;import java.util.UUID;
public interface AssetObjectRepository extends JpaRepository<AssetObject,UUID>{
    Optional<AssetObject>findBySha256(String sha256);
    @Modifying @Query(value="""
        UPDATE asset_object asset SET storage_state='temporary',staging_job_id=NULL,expires_at=:now
         WHERE asset.id IN (:ids)
           AND NOT EXISTS (SELECT 1 FROM purchase_product_image image WHERE image.asset_id=asset.id)
           AND NOT EXISTS (SELECT 1 FROM migration_manifest_entry entry WHERE entry.asset_id=asset.id)
           AND NOT EXISTS (SELECT 1 FROM purchase_import_row pir WHERE asset.id IN (pir.product_asset_id,pir.physical_asset_id,pir.before_product_asset_id,pir.before_physical_asset_id))
           AND NOT EXISTS (SELECT 1 FROM supplier_record supplier WHERE supplier.business_license_asset_id=asset.id)
        """,nativeQuery=true)int retireUnreferenced(@Param("ids")Collection<UUID>ids,@Param("now")Instant now);
    @Query(value="""
        SELECT asset.* FROM asset_object asset
         WHERE asset.storage_state='temporary' AND asset.expires_at<:now
           AND NOT EXISTS (SELECT 1 FROM purchase_product_image image WHERE image.asset_id=asset.id)
           AND NOT EXISTS (SELECT 1 FROM migration_manifest_entry entry WHERE entry.asset_id=asset.id)
           AND NOT EXISTS (SELECT 1 FROM purchase_import_row pir WHERE asset.id IN (pir.product_asset_id,pir.physical_asset_id,pir.before_product_asset_id,pir.before_physical_asset_id))
           AND NOT EXISTS (SELECT 1 FROM supplier_record supplier WHERE supplier.business_license_asset_id=asset.id)
        """,nativeQuery=true)List<AssetObject>findExpiredUnreferenced(@Param("now")Instant now);
}
