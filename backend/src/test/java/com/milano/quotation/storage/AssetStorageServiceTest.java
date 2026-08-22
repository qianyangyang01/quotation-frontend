package com.milano.quotation.storage;

import com.milano.quotation.common.AppException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

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
}
