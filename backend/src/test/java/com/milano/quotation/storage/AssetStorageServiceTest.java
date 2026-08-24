package com.milano.quotation.storage;

import com.milano.quotation.common.AppException;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AssetStorageServiceTest {
    @Test void detectsSupportedImageSignatures() {
        assertEquals("image/png", AssetStorageService.detectImage(new byte[]{(byte)0x89,0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a}));
        assertEquals("image/jpeg", AssetStorageService.detectImage(new byte[]{(byte)0xff,(byte)0xd8,(byte)0xff}));
        assertEquals("image/gif", AssetStorageService.detectImage("GIF89a".getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        assertEquals("image/webp", AssetStorageService.detectImage("RIFF0000WEBP".getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
    }

    @Test void rejectsExtensionSpoofedContent() {
        var error = assertThrows(AppException.class, () -> AssetStorageService.detectImage("not-an-image".getBytes()));
        assertEquals("VALIDATION_ERROR", error.code());
    }

    @Test void sha256IsStableForDeduplication() {
        assertEquals(AssetStorageService.sha256("same".getBytes()), AssetStorageService.sha256("same".getBytes()));
        assertNotEquals(AssetStorageService.sha256("same".getBytes()), AssetStorageService.sha256("different".getBytes()));
    }

    @Test void initializesOnlyItsOwnBucketAndSurfacesFailures() throws Exception {
        var minio = mock(MinioClient.class); var assets = mock(AssetObjectRepository.class);
        new AssetStorageService(minio, assets, "quotation-assets", false).initialize();
        verifyNoInteractions(minio);
        when(minio.bucketExists(any())).thenReturn(true);
        new AssetStorageService(minio, assets, "quotation-assets", true).initialize();
        verify(minio, never()).makeBucket(any());
        reset(minio); when(minio.bucketExists(any())).thenReturn(false);
        new AssetStorageService(minio, assets, "quotation-assets", true).initialize();
        verify(minio).makeBucket(any());
        reset(minio); when(minio.bucketExists(any())).thenThrow(new RuntimeException("down"));
        assertThrows(IllegalStateException.class, () -> new AssetStorageService(minio, assets, "quotation-assets", true).initialize());
    }

    @Test void storesDeduplicatesStagesAndValidatesImages() throws Exception {
        var minio = mock(MinioClient.class); var assets = mock(AssetObjectRepository.class);
        var service = new AssetStorageService(minio, assets, "quotation-assets", false);
        var png = new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        when(assets.findBySha256(any())).thenReturn(Optional.empty());
        when(assets.save(any())).thenAnswer(call -> call.getArgument(0));
        var published = service.storeImage(png, "a/\r\n.png");
        assertEquals("published", published.storageState);
        assertEquals("a___.png", published.originalName);
        assertNull(published.expiresAt);
        var jobId = UUID.randomUUID();
        var temporary = service.storeTemporaryImage(png, null, jobId);
        assertEquals("temporary", temporary.storageState);
        assertEquals(jobId, temporary.stagingJobId);
        assertNotNull(temporary.expiresAt);
        var existing = new AssetObject(); existing.id = UUID.randomUUID();
        when(assets.findBySha256(any())).thenReturn(Optional.of(existing));
        assertSame(existing, service.storeTemporaryImageIndependent(png, "duplicate.png", jobId));
        assertThrows(AppException.class, () -> service.storeImage(new byte[0], "empty.png"));
        assertThrows(AppException.class, () -> service.storeImage(new byte[20 * 1024 * 1024 + 1], "large.png"));
        assertThrows(AppException.class, () -> service.storeImage("text".getBytes(), "fake.png"));
    }

    @Test void handlesStorageWriteReadAndCleanupFailures() throws Exception {
        var minio = mock(MinioClient.class); var assets = mock(AssetObjectRepository.class);
        var service = new AssetStorageService(minio, assets, "quotation-assets", false);
        var png = new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        when(assets.findBySha256(any())).thenReturn(Optional.empty());
        when(minio.putObject(any())).thenThrow(new RuntimeException("down"));
        assertEquals("STORAGE_UNAVAILABLE", assertThrows(AppException.class, () -> service.storeImage(png, "a.png")).code());
        assertThrows(AppException.class, () -> service.putRaw("migration/a", new java.io.ByteArrayInputStream(png), png.length, "image/png"));
        when(minio.getObject(any())).thenThrow(new RuntimeException("down"));
        var asset = new AssetObject(); asset.id = UUID.randomUUID(); asset.objectKey = "objects/x";
        when(assets.findById(asset.id)).thenReturn(Optional.of(asset));
        assertThrows(AppException.class, () -> service.open(asset.id));
        assertThrows(AppException.class, () -> service.openRaw("migration/a"));
        assertThrows(AppException.class, () -> service.open(UUID.randomUUID()));
        asset.expiresAt = Instant.now().minusSeconds(1);
        when(assets.findExpiredUnreferenced(any())).thenReturn(List.of(asset));
        doThrow(new RuntimeException("down")).when(minio).removeObject(any());
        assertFalse(service.removeRaw("purchase-import/failed.zip"));
        assertDoesNotThrow(service::cleanupExpired);
        verify(assets, never()).delete(asset);
    }

    @Test void publishesRetiresReadsAndDeletesExpiredObjects() throws Exception {
        var minio = mock(MinioClient.class); var assets = mock(AssetObjectRepository.class);
        var service = new AssetStorageService(minio, assets, "quotation-assets", false);
        var id = UUID.randomUUID(); var asset = new AssetObject(); asset.id = id; asset.objectKey = "objects/x";
        asset.storageState = "temporary"; asset.stagingJobId = UUID.randomUUID(); asset.expiresAt = Instant.now();
        when(assets.findById(id)).thenReturn(Optional.of(asset));
        service.publish(List.of(id));
        assertEquals("published", asset.storageState); assertNull(asset.stagingJobId); assertNull(asset.expiresAt);
        var job = UUID.randomUUID(); service.retire(java.util.Arrays.asList(null, id), job);
        assertEquals("temporary", asset.storageState); assertEquals(job, asset.stagingJobId);
        var stream = mock(GetObjectResponse.class); when(minio.getObject(any())).thenReturn(stream);
        assertSame(stream, service.open(id).stream());
        assertSame(stream, service.openRaw("migration/a"));
        assertDoesNotThrow(() -> service.putRaw("migration/a", new java.io.ByteArrayInputStream(new byte[]{1}), 1, "application/octet-stream"));
        assertTrue(service.removeRaw(""));
        assertTrue(service.removeRaw("purchase-import/completed.zip"));
        when(assets.findExpiredUnreferenced(any())).thenReturn(List.of(asset));
        service.cleanupExpired();
        verify(assets).delete(asset);
    }
}
