package com.milano.quotation.fob;

import com.milano.quotation.common.AppException;
import com.milano.quotation.storage.AssetObject;
import com.milano.quotation.storage.AssetStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FobImageControllerTest {
    @Test void stagesUploadsAndReturnsOnlyTheLocalAssetReference() throws Exception {
        var storage=mock(AssetStorageService.class); var controller=new FobImageController(storage);
        var asset=mock(AssetObject.class); asset.id=UUID.randomUUID();
        var bytes=new byte[]{(byte)0x89,0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a};
        when(storage.storeTemporaryImage(any(),anyString(),any(UUID.class))).thenReturn(asset);
        var result=controller.upload(new MockMultipartFile("file","product.png","image/png",bytes));
        assertEquals("/api/v1/assets/"+asset.id,result.data().url());
        verify(storage).storeTemporaryImage(eq(bytes),eq("product.png"),any(UUID.class));
        verify(storage,never()).storeImage(any(),anyString());
    }
    @Test void rejectsEmptyAndOversizedUploadsBeforeStorage() {
        var storage=mock(AssetStorageService.class); var controller=new FobImageController(storage);
        for(var bytes:new byte[][]{new byte[0],new byte[5*1024*1024+1]})
            assertThrows(AppException.class,()->controller.upload(new MockMultipartFile("file","image.png","image/png",bytes)));
        verifyNoInteractions(storage);
    }
}
