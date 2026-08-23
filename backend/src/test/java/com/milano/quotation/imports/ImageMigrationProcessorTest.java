package com.milano.quotation.imports;

import com.milano.quotation.purchase.PurchaseProductService;
import com.milano.quotation.storage.AssetObject;
import com.milano.quotation.storage.AssetStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ImageMigrationProcessorTest {
    private ImportJobRepository jobs; private ImportPartRepository parts; private MigrationManifestEntryRepository entries;
    private AssetStorageService storage; private ImageMigrationFinalizer finalizer; private PurchaseProductService products;
    private ImageMigrationProcessor processor; private ImportJob job; private ImportPart part;

    @BeforeEach void setup() {
        jobs=mock(ImportJobRepository.class); parts=mock(ImportPartRepository.class); entries=mock(MigrationManifestEntryRepository.class);
        storage=mock(AssetStorageService.class); finalizer=mock(ImageMigrationFinalizer.class); products=mock(PurchaseProductService.class);
        processor=new ImageMigrationProcessor(jobs,parts,entries,storage,finalizer,products);
        job=new ImportJob();job.id=UUID.randomUUID();job.status="processing";job.payload=JsonNodeFactory.instance.objectNode();job.createdAt=Instant.now();job.updatedAt=job.createdAt;
        part=new ImportPart();part.id=UUID.randomUUID();part.jobId=job.id;part.partNumber=1;part.objectKey="migration/part.zip";
        when(jobs.findById(job.id)).thenReturn(Optional.of(job));when(parts.findByJobIdOrderByPartNumber(job.id)).thenReturn(List.of(part));
        when(entries.countByJobIdAndStatus(any(),anyString())).thenReturn(0L);when(jobs.save(any())).thenAnswer(c->c.getArgument(0));
    }

    @Test void validatesKnownImageAndAtomicallyFinalizes() throws Exception {
        var bytes=new byte[]{(byte)0x89,0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a};
        var manifest=entry("images/a.png",AssetStorageService.sha256(bytes));
        when(storage.openRaw(part.objectKey)).thenReturn(new ByteArrayInputStream(zip("images/a.png",bytes,false)));
        when(entries.findByJobIdAndFileName(job.id,"images/a.png")).thenReturn(Optional.of(manifest));
        when(products.exists("SKU-1")).thenReturn(true);
        var asset=mock(AssetObject.class);asset.id=UUID.randomUUID();when(storage.storeImage(bytes,"images/a.png")).thenReturn(asset);
        processor.process(job.id);
        assertEquals("validated",manifest.status);assertEquals(asset.id,manifest.assetId);
        verify(finalizer).publish(job.id);verify(jobs,never()).save(job);
    }

    @Test void recordsHashAndOrphanFailuresWithoutPublishing() throws Exception {
        var bytes="not-the-expected-file".getBytes();var manifest=entry("a.png","f".repeat(64));
        when(storage.openRaw(part.objectKey)).thenReturn(new ByteArrayInputStream(zip("a.png",bytes,false)));
        when(entries.findByJobIdAndFileName(job.id,"a.png")).thenReturn(Optional.of(manifest));
        when(entries.countByJobIdAndStatus(job.id,"failed")).thenReturn(1L);
        processor.process(job.id);
        assertEquals("failed",manifest.status);assertTrue(manifest.errorMessage.contains("SHA256"));assertEquals("completed-with-errors",job.status);
        verify(finalizer,never()).publish(any());verify(jobs).save(job);

        var orphan=entry("b.png",null);job.status="processing";job.completedAt=null;
        when(storage.openRaw(part.objectKey)).thenReturn(new ByteArrayInputStream(zip("b.png",bytes,false)));
        when(entries.findByJobIdAndFileName(job.id,"b.png")).thenReturn(Optional.of(orphan));when(products.exists("SKU-1")).thenReturn(false);
        processor.process(job.id);assertTrue(orphan.errorMessage.contains("SKU"));
    }

    @Test void skipsDirectoriesAndUnknownEntriesAndRejectsUnsafePaths() throws Exception {
        when(storage.openRaw(part.objectKey)).thenReturn(new ByteArrayInputStream(zip("folder/",new byte[0],true)));
        processor.process(job.id);verify(finalizer).publish(job.id);
        reset(finalizer);job.status="processing";
        when(storage.openRaw(part.objectKey)).thenReturn(new ByteArrayInputStream(zip("unknown.png",new byte[]{1},false)));
        when(entries.findByJobIdAndFileName(job.id,"unknown.png")).thenReturn(Optional.empty());
        processor.process(job.id);verify(finalizer).publish(job.id);
        reset(finalizer);job.status="processing";
        when(storage.openRaw(part.objectKey)).thenReturn(new ByteArrayInputStream(zip("../escape.png",new byte[]{1},false)));
        processor.process(job.id);assertEquals("failed",job.status);assertTrue(job.errorMessage.contains("不安全路径"));verify(jobs,atLeastOnce()).save(job);
    }

    @Test void capturesImageStorageFailureWithSafeMessage() throws Exception {
        var bytes=new byte[]{(byte)0x89,0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a};var manifest=entry("a.png",null);
        when(storage.openRaw(part.objectKey)).thenReturn(new ByteArrayInputStream(zip("a.png",bytes,false)));
        when(entries.findByJobIdAndFileName(job.id,"a.png")).thenReturn(Optional.of(manifest));when(products.exists("SKU-1")).thenReturn(true);
        when(storage.storeImage(any(),anyString())).thenThrow(new RuntimeException());when(entries.countByJobIdAndStatus(job.id,"failed")).thenReturn(1L);
        processor.process(job.id);assertEquals("RuntimeException",manifest.errorMessage);
    }

    private MigrationManifestEntry entry(String name,String sha){var row=new MigrationManifestEntry();row.id=UUID.randomUUID();row.jobId=job.id;row.sku="SKU-1";row.imageType="product";row.fileName=name;row.expectedSha256=sha;row.status="pending";row.updatedAt=Instant.now();return row;}
    private byte[] zip(String name,byte[] content,boolean directory)throws Exception{try(var out=new ByteArrayOutputStream();var zip=new ZipOutputStream(out)){var entry=new ZipEntry(name);zip.putNextEntry(entry);if(!directory)zip.write(content);zip.closeEntry();zip.finish();return out.toByteArray();}}
}
