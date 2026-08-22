package com.milano.quotation.imports;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.milano.quotation.common.AppException;
import com.milano.quotation.purchase.PurchaseProductService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.UUID;

@Service
public class ImageMigrationFinalizer {
    private final ImportJobRepository jobs;
    private final MigrationManifestEntryRepository entries;
    private final PurchaseProductService products;

    public ImageMigrationFinalizer(ImportJobRepository jobs, MigrationManifestEntryRepository entries, PurchaseProductService products) {
        this.jobs = jobs; this.entries = entries; this.products = products;
    }

    @Transactional
    public void publish(UUID jobId) {
        var job = jobs.findById(jobId).orElseThrow();
        var rows = entries.findByJobIdOrderByFileName(jobId);
        if (rows.isEmpty() || rows.stream().anyMatch(row -> !"validated".equals(row.status) || row.assetId == null))
            throw AppException.conflict("迁移文件尚未全部通过校验，未发布任何图片关联");
        for (var row : rows) {
            products.linkAsset(row.sku, row.assetId, row.imageType);
            row.status = "completed"; row.updatedAt = Instant.now();
        }
        job.status = "completed"; job.completedAt = Instant.now(); job.updatedAt = job.completedAt;
        var payload = (ObjectNode) job.payload; payload.put("completed", rows.size()).put("failed", 0).put("pending", 0);
    }
}
