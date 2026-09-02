package com.milano.quotation.supplierrecord;

import com.milano.quotation.storage.AssetObject;
import com.milano.quotation.storage.AssetStorageService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SupplierRecordServiceTest {
    private final SupplierRecordRepository records = mock(SupplierRecordRepository.class);
    private final AssetStorageService storage = mock(AssetStorageService.class);
    private final SupplierRecordService service = new SupplierRecordService(records, storage);

    @Test
    void replacesAndRetiresThePreviousBusinessLicense() {
        var row = record();
        var previous = UUID.randomUUID();
        var replacement = mock(AssetObject.class);
        replacement.id = UUID.randomUUID();
        row.businessLicenseAssetId = previous;
        when(records.findById(row.id)).thenReturn(Optional.of(row));
        when(storage.storeImage(new byte[]{1, 2, 3}, "license.png")).thenReturn(replacement);
        when(records.saveAndFlush(row)).thenReturn(row);

        var result = service.uploadBusinessLicense(row.id, row.version, new byte[]{1, 2, 3}, "license.png", "buyer");

        assertEquals(replacement.id, result.businessLicenseAssetId());
        verify(storage).retireUnreferenced(List.of(previous));
    }

    @Test
    void removesAndRetiresTheCurrentBusinessLicense() {
        var row = record();
        var previous = UUID.randomUUID();
        row.businessLicenseAssetId = previous;
        when(records.findById(row.id)).thenReturn(Optional.of(row));
        when(records.saveAndFlush(row)).thenReturn(row);

        var result = service.removeBusinessLicense(row.id, row.version, "buyer");

        assertNull(result.businessLicenseAssetId());
        verify(storage).retireUnreferenced(List.of(previous));
    }

    @Test
    void deletingARecordWithoutALicenseDoesNotTouchObjectStorage() {
        var row = record();
        when(records.findById(row.id)).thenReturn(Optional.of(row));

        service.delete(row.id, row.version);

        verify(storage, never()).retireUnreferenced(org.mockito.ArgumentMatchers.any());
    }

    private static SupplierRecord record() {
        var row = new SupplierRecord();
        row.id = UUID.randomUUID();
        row.name = "测试供应商";
        row.createdAt = Instant.now();
        row.updatedAt = row.createdAt;
        row.createdBy = "BUYER";
        row.updatedBy = "BUYER";
        row.version = 4;
        return row;
    }
}
