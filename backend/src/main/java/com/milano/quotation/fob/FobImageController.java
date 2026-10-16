package com.milano.quotation.fob;

import com.milano.quotation.common.ApiResponse;
import com.milano.quotation.common.AppException;
import com.milano.quotation.storage.AssetStorageService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/fob-purchase-products")
public class FobImageController {
    private final AssetStorageService storage;
    public FobImageController(AssetStorageService storage) { this.storage = storage; }
    public record Uploaded(String url) {}
    @PostMapping(value="/images", consumes="multipart/form-data")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PURCHASE')")
    public ApiResponse<Uploaded> upload(@RequestPart("file") MultipartFile file) throws IOException {
        if (file.isEmpty() || file.getSize() > 5L * 1024 * 1024) throw AppException.unprocessable("图片须非空且不超过5MB");
        // Unconfirmed uploads expire; saved FOB payloads and audit history retain referenced assets.
        var asset = storage.storeTemporaryImage(file.getBytes(), file.getOriginalFilename(), UUID.randomUUID());
        return ApiResponse.ok(new Uploaded("/api/v1/assets/" + asset.id));
    }
}
