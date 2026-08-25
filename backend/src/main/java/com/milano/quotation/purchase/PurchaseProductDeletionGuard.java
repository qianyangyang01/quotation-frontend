package com.milano.quotation.purchase;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class PurchaseProductDeletionGuard {
    private static final String STRUCTURED_REFERENCE = """
        upper(regexp_replace(coalesce(%1$s #>> '{product,sku}', ''), '[[:space:]]+', '', 'g')) = :sku
        OR upper(regexp_replace(coalesce(%1$s ->> 'skuSearch', ''), '[[:space:]]+', '', 'g')) = :sku
        OR EXISTS (
            SELECT 1
              FROM jsonb_array_elements(
                   CASE WHEN jsonb_typeof(%1$s -> 'bundleItems') = 'array'
                        THEN %1$s -> 'bundleItems' ELSE '[]'::jsonb END
              ) item
             WHERE upper(regexp_replace(coalesce(item ->> 'sku', ''), '[[:space:]]+', '', 'g')) = :sku
        )
        """;

    private final NamedParameterJdbcTemplate jdbc;

    public PurchaseProductDeletionGuard(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public DeletionCheck inspect(UUID productId, String sku, long version) {
        var parameters = new MapSqlParameterSource().addValue("productId", productId).addValue("sku", sku);
        var sql = """
            SELECT
              (SELECT count(*) FROM supplier_product WHERE product_id = :productId) supplier_links,
              (SELECT count(*) FROM quotation_record q
                WHERE EXISTS (
                  SELECT 1 FROM regexp_split_to_table(
                    upper(coalesce(q.payload ->> 'primarySku', '')), '[、,+，[:space:]]+'
                  ) token WHERE token = :sku
                )) quotation_records,
              (SELECT count(*) FROM quotation_draft d WHERE %s) drafts,
              (SELECT count(*) FROM quotation_template t WHERE %s) templates,
              (SELECT count(DISTINCT job_id) FROM purchase_import_row
                WHERE applied_product_id = :productId AND applied_at IS NOT NULL AND rolled_back_at IS NULL) import_batches,
              (SELECT count(*) FROM purchase_product_image WHERE product_id = :productId) image_count
            """.formatted(STRUCTURED_REFERENCE.formatted("d.payload"), STRUCTURED_REFERENCE.formatted("t.payload"));
        return jdbc.queryForObject(sql, parameters, (row, index) -> {
            int supplierLinks = row.getInt("supplier_links");
            int quotationRecords = row.getInt("quotation_records");
            int drafts = row.getInt("drafts");
            int templates = row.getInt("templates");
            int importBatches = row.getInt("import_batches");
            int imageCount = row.getInt("image_count");
            boolean canDelete = supplierLinks + quotationRecords + drafts + templates + importBatches == 0;
            return new DeletionCheck(canDelete, version, imageCount, supplierLinks, quotationRecords, drafts, templates, importBatches);
        });
    }

    public record DeletionCheck(boolean canDelete, long version, int imageCount, int supplierLinks,
                                int quotationRecords, int drafts, int templates, int importBatches) {
        public Map<String, Object> auditDetail() {
            var detail = new LinkedHashMap<String, Object>();
            detail.put("version", version); detail.put("imageCount", imageCount);
            detail.put("supplierLinks", supplierLinks); detail.put("quotationRecords", quotationRecords);
            detail.put("drafts", drafts); detail.put("templates", templates); detail.put("importBatches", importBatches);
            return detail;
        }

        public String blockingMessage() {
            var reasons = new java.util.ArrayList<String>();
            if (supplierLinks > 0) reasons.add("供应商关联 " + supplierLinks + " 条");
            if (quotationRecords > 0) reasons.add("报价记录 " + quotationRecords + " 条");
            if (drafts > 0) reasons.add("报价草稿 " + drafts + " 条");
            if (templates > 0) reasons.add("报价模板 " + templates + " 条");
            if (importBatches > 0) reasons.add("未回滚导入批次 " + importBatches + " 个，请从任务中心整批回滚");
            return String.join("；", reasons);
        }
    }
}
