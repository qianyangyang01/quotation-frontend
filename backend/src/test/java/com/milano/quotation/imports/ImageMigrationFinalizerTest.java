package com.milano.quotation.imports;

import com.milano.quotation.common.AppException;
import com.milano.quotation.purchase.PurchaseProductService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImageMigrationFinalizerTest {
    private ImportJobRepository jobs;private MigrationManifestEntryRepository entries;private PurchaseProductService products;private ImageMigrationFinalizer service;private ImportJob job;
    @BeforeEach void setup(){jobs=mock(ImportJobRepository.class);entries=mock(MigrationManifestEntryRepository.class);products=mock(PurchaseProductService.class);service=new ImageMigrationFinalizer(jobs,entries,products);job=new ImportJob();job.id=UUID.randomUUID();job.status="processing";job.payload=JsonNodeFactory.instance.objectNode();job.createdAt=Instant.now();job.updatedAt=job.createdAt;when(jobs.findById(job.id)).thenReturn(Optional.of(job));}
    @Test void rejectsEmptyIncompleteAndMissingAssets(){when(entries.findByJobIdOrderByFileName(job.id)).thenReturn(List.of());assertThrows(AppException.class,()->service.publish(job.id));var row=entry("pending",UUID.randomUUID());when(entries.findByJobIdOrderByFileName(job.id)).thenReturn(List.of(row));assertThrows(AppException.class,()->service.publish(job.id));row.status="validated";row.assetId=null;assertThrows(AppException.class,()->service.publish(job.id));}
    @Test void publishesEveryValidatedLink(){var first=entry("validated",UUID.randomUUID());var second=entry("validated",UUID.randomUUID());when(entries.findByJobIdOrderByFileName(job.id)).thenReturn(List.of(first,second));service.publish(job.id);verify(products).linkAsset("SKU-1",first.assetId,"product");verify(products).linkAsset("SKU-1",second.assetId,"product");assertEquals("completed",first.status);assertEquals("completed",job.status);assertEquals(2,job.payload.path("completed").asInt());}
    private MigrationManifestEntry entry(String status,UUID asset){var row=new MigrationManifestEntry();row.id=UUID.randomUUID();row.jobId=job.id;row.sku="SKU-1";row.imageType="product";row.fileName=UUID.randomUUID()+".png";row.status=status;row.assetId=asset;row.updatedAt=Instant.now();return row;}
}
