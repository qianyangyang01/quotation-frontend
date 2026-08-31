package com.milano.quotation.imports;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class PurchaseImportJdbcService {
    private final NamedParameterJdbcTemplate named;
    private final JdbcTemplate jdbc;

    public PurchaseImportJdbcService(NamedParameterJdbcTemplate named, JdbcTemplate jdbc) {
        this.named = named;
        this.jdbc = jdbc;
    }

    @Transactional
    public ApplyResult apply(UUID jobId, Collection<UUID> rowIds, String sourceHash) {
        if (rowIds.isEmpty()) return new ApplyResult(0, 0);
        var now = Instant.now();
        var parameters = new MapSqlParameterSource()
                .addValue("ids", rowIds)
                .addValue("jobId", jobId)
                .addValue("sourceHash", sourceHash)
                .addValue("now", OffsetDateTime.ofInstant(now,ZoneOffset.UTC));

        int conflicts = named.update("""
                UPDATE purchase_import_row r
                   SET validation_status = 'conflict',
                       error_message = '商品已在预览后被修改'
                 WHERE r.id IN (:ids)
                   AND r.job_id = :jobId
                   AND r.validation_status = 'valid'
                   AND r.applied_at IS NULL
                   AND ((r.import_action = 'update' AND NOT EXISTS (
                          SELECT 1 FROM purchase_product p
                           WHERE p.sku = r.sku AND p.version = r.expected_version
                        )) OR (r.import_action = 'insert' AND EXISTS (
                          SELECT 1 FROM purchase_product p WHERE p.sku = r.sku
                        )) OR (r.import_action = 'sku-backfill' AND (
                          NOT EXISTS (SELECT 1 FROM purchase_product p WHERE p.id=r.target_product_id
                                      AND p.version=r.expected_version AND p.sku LIKE 'AUTO-%')
                          OR EXISTS (SELECT 1 FROM purchase_product p WHERE p.sku=r.sku AND p.id<>r.target_product_id)
                        )))
                """, parameters);

        named.update("""
                UPDATE purchase_import_row r
                   SET before_payload = p.payload,
                       before_sku = p.sku,
                       before_catalog_state = p.catalog_state,
                       before_quote_ready = p.quote_ready,
                       before_source_hash = p.source_hash,
                       before_version = p.version,
                       before_product_asset_id = (
                           SELECT i.asset_id FROM purchase_product_image i
                            WHERE i.product_id = p.id AND i.image_type = 'product'
                            ORDER BY i.sort_order LIMIT 1
                       ),
                       before_physical_asset_id = (
                           SELECT i.asset_id FROM purchase_product_image i
                            WHERE i.product_id = p.id AND i.image_type = 'physical'
                            ORDER BY i.sort_order LIMIT 1
                       )
                  FROM purchase_product p
                 WHERE r.id IN (:ids)
                   AND r.job_id = :jobId
                   AND r.validation_status = 'valid'
                   AND r.applied_at IS NULL
                   AND ((r.import_action = 'update' AND p.sku = r.sku)
                     OR (r.import_action = 'sku-backfill' AND p.id = r.target_product_id))
                   AND p.version = r.expected_version
                """, parameters);

        named.update("""
                INSERT INTO purchase_product
                    (id, sku, payload, catalog_state, quote_ready, source_hash, version, created_at, updated_at)
                SELECT r.id, r.sku, r.payload - '_version' - '_updatedAt',
                       CASE WHEN COALESCE((r.payload ->> 'quoteReady')::boolean, false) THEN 'ready' ELSE 'pending_template' END,
                       COALESCE((r.payload ->> 'quoteReady')::boolean, false), :sourceHash, 0, :now, :now
                  FROM purchase_import_row r
                 WHERE r.id IN (:ids)
                   AND r.job_id = :jobId
                   AND r.validation_status = 'valid'
                   AND r.applied_at IS NULL
                   AND r.import_action = 'insert'
                """, parameters);

        named.update("""
                UPDATE purchase_product p
                   SET payload = r.payload - '_version' - '_updatedAt',
                       catalog_state = CASE WHEN COALESCE((r.payload ->> 'quoteReady')::boolean, false) THEN 'ready' ELSE 'pending_template' END,
                       quote_ready = COALESCE((r.payload ->> 'quoteReady')::boolean, false),
                       source_hash = :sourceHash,
                       version = p.version + 1,
                       updated_at = :now
                  FROM purchase_import_row r
                 WHERE r.id IN (:ids)
                   AND r.job_id = :jobId
                   AND r.validation_status = 'valid'
                   AND r.applied_at IS NULL
                   AND r.import_action = 'update'
                   AND p.sku = r.sku
                   AND p.version = r.expected_version
                """, parameters);

        // Backfill uses the live product payload, not the newly uploaded row: it
        // must preserve manual edits, image URLs, image links and disabled state.
        named.update("""
                WITH candidates AS (
                    SELECT r.id AS row_id, r.sku, r.expected_version, p.id AS product_id, p.payload,
                           CASE WHEN p.catalog_state='disabled' THEN 'disabled'
                                WHEN jsonb_path_exists(p.payload,'$.weightG ? (@ > 0)')
                                 AND jsonb_path_exists(p.payload,'$.minOrderQty ? (@ > 0)')
                                 AND (jsonb_path_exists(p.payload,'$.purchasePriceCny ? (@ >= 0)')
                                   OR jsonb_path_exists(p.payload,'$.tier2PriceCny ? (@ >= 0)')
                                   OR jsonb_path_exists(p.payload,'$.tier3PriceCny ? (@ >= 0)')
                                   OR jsonb_path_exists(p.payload,'$.taxIncludedPriceCny ? (@ >= 0)'))
                                  THEN 'ready' ELSE 'pending_template' END AS next_state
                      FROM purchase_import_row r JOIN purchase_product p ON p.id=r.target_product_id
                     WHERE r.id IN (:ids) AND r.job_id=:jobId AND r.validation_status='valid'
                       AND r.import_action='sku-backfill' AND r.applied_at IS NULL
                       AND p.version=r.expected_version AND p.sku LIKE 'AUTO-%'
                )
                UPDATE purchase_product p SET
                       sku=c.sku,
                       payload=c.payload || jsonb_build_object(
                           'sku',c.sku,'skuOrigin','imported','catalogState',c.next_state,
                           'quoteReady',c.next_state='ready',
                           'status',CASE WHEN c.next_state='disabled' THEN '已停用'
                                         WHEN c.next_state='ready' THEN '资料完整' ELSE '模板待补全（不可报价）' END,
                           'importWarnings',(SELECT COALESCE(jsonb_agg(w.value),'[]'::jsonb)
                              FROM jsonb_array_elements(CASE WHEN jsonb_typeof(c.payload->'importWarnings')='array'
                                  THEN c.payload->'importWarnings' ELSE '[]'::jsonb END) w
                             WHERE (w.value #>> '{}') NOT LIKE 'SKU为空%临时SKU%')),
                       catalog_state=c.next_state,quote_ready=c.next_state='ready',
                       version=p.version+1,updated_at=:now
                  FROM candidates c WHERE p.id=c.product_id AND p.version=c.expected_version AND p.sku LIKE 'AUTO-%'
                """,parameters);

        named.update("""
                UPDATE purchase_import_row r
                   SET applied_product_id = p.id,
                       applied_version = p.version,
                       applied_at = :now
                  FROM purchase_product p
                 WHERE r.id IN (:ids)
                   AND r.job_id = :jobId
                   AND r.validation_status = 'valid'
                   AND r.applied_at IS NULL
                   AND p.sku = r.sku
                   AND ((r.import_action = 'insert' AND p.id = r.id)
                     OR (r.import_action = 'update' AND p.updated_at = :now AND p.source_hash = :sourceHash)
                     OR (r.import_action = 'sku-backfill' AND p.id = r.target_product_id
                         AND p.version = r.expected_version + 1 AND p.updated_at = :now))
                """, parameters);

        conflicts += named.update("""
                UPDATE purchase_import_row
                   SET validation_status='conflict', error_message='商品已在预览后被修改'
                 WHERE id IN (:ids) AND job_id=:jobId AND validation_status='valid'
                   AND applied_at IS NULL
                """,parameters);

        replaceImages(parameters, "product");
        replaceImages(parameters, "physical");

        named.update("""
                UPDATE asset_object a
                   SET storage_state='published', staging_job_id=NULL, expires_at=NULL
                 WHERE a.staging_job_id=:jobId AND a.id IN (
                    SELECT product_asset_id FROM purchase_import_row
                     WHERE id IN (:ids) AND applied_at IS NOT NULL AND product_asset_id IS NOT NULL AND import_action<>'sku-backfill'
                    UNION
                    SELECT physical_asset_id FROM purchase_import_row
                     WHERE id IN (:ids) AND applied_at IS NOT NULL AND physical_asset_id IS NOT NULL AND import_action<>'sku-backfill'
                 )
                """,parameters);
        Integer applied = named.queryForObject("SELECT count(*) FROM purchase_import_row WHERE id IN (:ids) AND applied_at IS NOT NULL", parameters, Integer.class);
        return new ApplyResult(applied == null ? 0 : applied, conflicts);
    }

    private void replaceImages(MapSqlParameterSource parameters, String imageType) {
        parameters.addValue("imageType", imageType);
        named.update("""
                DELETE FROM purchase_product_image i
                 USING purchase_import_row r
                 WHERE r.id IN (:ids)
                   AND r.applied_at IS NOT NULL
                   AND r.import_action <> 'sku-backfill'
                   AND i.product_id = r.applied_product_id
                   AND i.image_type = :imageType
                   AND CASE WHEN :imageType = 'product' THEN r.product_asset_id IS NOT NULL ELSE r.physical_asset_id IS NOT NULL END
                """, parameters);
        named.update("""
                INSERT INTO purchase_product_image (id, product_id, asset_id, image_type, sort_order)
                SELECT md5(r.id::text || ':' || :imageType)::uuid,
                       r.applied_product_id,
                       CASE WHEN :imageType = 'product' THEN r.product_asset_id ELSE r.physical_asset_id END,
                       :imageType, 0
                  FROM purchase_import_row r
                 WHERE r.id IN (:ids)
                   AND r.applied_at IS NOT NULL
                   AND r.import_action <> 'sku-backfill'
                   AND CASE WHEN :imageType = 'product' THEN r.product_asset_id IS NOT NULL ELSE r.physical_asset_id IS NOT NULL END
                ON CONFLICT (product_id, asset_id, image_type) DO NOTHING
                """, parameters);
    }

    @Transactional
    public int lockAndCountRollbackConflicts(UUID jobId) {
        Integer missing = jdbc.queryForObject("""
                SELECT count(*) FROM purchase_import_row r
                 WHERE r.job_id = ? AND r.applied_at IS NOT NULL AND r.rolled_back_at IS NULL
                   AND NOT EXISTS (SELECT 1 FROM purchase_product p WHERE p.id = r.applied_product_id)
                """, Integer.class, jobId);
        var conflicts = new AtomicInteger(missing == null ? 0 : missing);
        Integer occupied = jdbc.queryForObject("""
                SELECT count(*) FROM purchase_import_row r
                 WHERE r.job_id=? AND r.applied_at IS NOT NULL AND r.rolled_back_at IS NULL
                   AND r.import_action='sku-backfill' AND EXISTS (
                       SELECT 1 FROM purchase_product p WHERE p.sku=r.before_sku AND p.id<>r.applied_product_id)
                """,Integer.class,jobId);
        conflicts.addAndGet(occupied==null?0:occupied);
        jdbc.query(connection -> {
            var statement = connection.prepareStatement("""
                    SELECT r.sku, r.applied_version, p.version
                      FROM purchase_import_row r
                      JOIN purchase_product p ON p.id = r.applied_product_id
                     WHERE r.job_id = ? AND r.applied_at IS NOT NULL AND r.rolled_back_at IS NULL
                     ORDER BY r.source_row
                     FOR UPDATE OF p
                    """, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
            statement.setObject(1, jobId);
            statement.setFetchSize(PurchaseImportBatchService.BATCH_SIZE);
            return statement;
        }, rs -> {
            if (rs.getLong(3) != rs.getLong(2)) conflicts.incrementAndGet();
        });
        return conflicts.get();
    }

    public List<UUID> nextRollbackIds(UUID jobId) {
        return jdbc.query("""
                SELECT id FROM purchase_import_row
                 WHERE job_id = ? AND applied_at IS NOT NULL AND rolled_back_at IS NULL
                 ORDER BY source_row LIMIT ?
                """, (rs, row) -> rs.getObject(1, UUID.class), jobId, PurchaseImportBatchService.BATCH_SIZE);
    }

    @Transactional
    public int rollback(UUID jobId, Collection<UUID> rowIds) {
        if (rowIds.isEmpty()) return 0;
        var parameters = new MapSqlParameterSource().addValue("ids", rowIds).addValue("jobId", jobId).addValue("now", OffsetDateTime.now(ZoneOffset.UTC));
        // SKU backfill never changes image relations and must not use the full
        // product-update rollback's delete/recreate image path.
        named.update("""
                UPDATE purchase_product p SET sku=r.before_sku,payload=r.before_payload,
                       catalog_state=r.before_catalog_state,quote_ready=r.before_quote_ready,
                       source_hash=r.before_source_hash,version=p.version+1,updated_at=:now
                  FROM purchase_import_row r
                 WHERE r.id IN (:ids) AND r.job_id=:jobId AND r.import_action='sku-backfill'
                   AND p.id=r.applied_product_id AND p.version=r.applied_version
                """,parameters);
        named.update("""
                DELETE FROM purchase_product_image i
                 USING purchase_import_row r
                 WHERE r.id IN (:ids) AND r.job_id = :jobId
                   AND r.import_action = 'update' AND i.product_id = r.applied_product_id
                   AND i.image_type IN ('product','physical')
                """, parameters);
        named.update("""
                UPDATE purchase_product p
                   SET payload = r.before_payload,
                       catalog_state = r.before_catalog_state,
                       quote_ready = r.before_quote_ready,
                       source_hash = r.before_source_hash,
                       version = p.version + 1,
                       updated_at = :now
                  FROM purchase_import_row r
                 WHERE r.id IN (:ids) AND r.job_id = :jobId
                   AND r.import_action = 'update' AND p.id = r.applied_product_id
                   AND p.version = r.applied_version
                """, parameters);
        restoreImages(parameters, "product", "before_product_asset_id");
        restoreImages(parameters, "physical", "before_physical_asset_id");
        named.update("""
                DELETE FROM purchase_product p
                 USING purchase_import_row r
                 WHERE r.id IN (:ids) AND r.job_id = :jobId
                   AND r.import_action = 'insert' AND p.id = r.applied_product_id
                   AND p.version = r.applied_version
                """, parameters);
        int retired=named.update("""
                UPDATE asset_object a
                   SET storage_state='temporary', staging_job_id=:jobId, expires_at=:now
                 WHERE a.id IN (
                    SELECT DISTINCT e.asset_id
                      FROM migration_manifest_entry e
                      JOIN purchase_import_row r ON r.job_id=e.job_id
                           AND (r.product_asset_id=e.asset_id OR r.physical_asset_id=e.asset_id)
                     WHERE r.id IN (:ids) AND e.job_id=:jobId
                       AND e.asset_owned=true AND e.asset_id IS NOT NULL
                 )
                   AND NOT EXISTS (SELECT 1 FROM purchase_product_image i WHERE i.asset_id=a.id)
                """,parameters);
        named.update("UPDATE purchase_import_row SET rolled_back_at = :now WHERE id IN (:ids) AND job_id = :jobId", parameters);
        return retired;
    }

    private void restoreImages(MapSqlParameterSource parameters, String imageType, String assetColumn) {
        parameters.addValue("rollbackImageType", imageType);
        String column = switch (assetColumn) {
            case "before_product_asset_id" -> "r.before_product_asset_id";
            case "before_physical_asset_id" -> "r.before_physical_asset_id";
            default -> throw new IllegalArgumentException("Unsupported snapshot column");
        };
        named.update("""
                INSERT INTO purchase_product_image (id, product_id, asset_id, image_type, sort_order)
                SELECT md5(r.id::text || ':rollback:' || :rollbackImageType)::uuid,
                       r.applied_product_id, %s, :rollbackImageType, 0
                  FROM purchase_import_row r
                 WHERE r.id IN (:ids) AND r.job_id = :jobId
                   AND r.import_action = 'update' AND %s IS NOT NULL
                ON CONFLICT (product_id, asset_id, image_type) DO NOTHING
                """.formatted(column, column), parameters);
    }

    public record ApplyResult(int applied, int conflicts) {}
}
