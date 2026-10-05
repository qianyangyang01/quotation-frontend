package com.milano.quotation.storage;

import com.milano.quotation.audit.AuditService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QuotationSheetPhotoControllerTest {
    @Test void storesAnImageAsAnAuditedAsset() throws Exception {
        var storage=mock(AssetStorageService.class); var audit=mock(AuditService.class);
        var asset=new AssetObject();asset.id=UUID.randomUUID();asset.originalName="photo.png";asset.sizeBytes=8;
        when(storage.storeImage(any(),eq("photo.png"))).thenReturn(asset);
        var controller=new QuotationSheetPhotoController(storage,audit);
        assertNotNull(controller.upload(new MockMultipartFile("file","photo.png","image/png",new byte[]{(byte)0x89,0x50,0x4e,0x47,13,10,26,10})));
        verify(storage).storeImage(any(),eq("photo.png"));
        verify(audit).record(eq("quotation.photo.upload"),eq("asset"),eq(asset.id.toString()),eq("success"),any());
    }
    @Test void rejectsUnsupportedAndOversizeFilesBeforeStorage() {
        var storage=mock(AssetStorageService.class);var controller=new QuotationSheetPhotoController(storage,mock(AuditService.class));
        assertThrows(RuntimeException.class,()->controller.upload(new MockMultipartFile("file","fake.png","image/png","<svg/>".getBytes())));
        assertThrows(RuntimeException.class,()->controller.upload(new MockMultipartFile("file","big.png","image/png",new byte[10*1024*1024+1])));
        assertThrows(RuntimeException.class,()->controller.upload(new MockMultipartFile("file","empty.png","image/png",new byte[0])));
        verifyNoInteractions(storage);
    }
}
