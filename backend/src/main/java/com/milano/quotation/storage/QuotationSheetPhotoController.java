package com.milano.quotation.storage;

import com.milano.quotation.audit.AuditService;
import com.milano.quotation.common.ApiResponse;
import com.milano.quotation.common.AppException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/quotation-sheet-photos")
public class QuotationSheetPhotoController {
    private final AssetStorageService storage;
    private final AuditService audit;
    public QuotationSheetPhotoController(AssetStorageService storage, AuditService audit) { this.storage=storage; this.audit=audit; }

    @PostMapping
    @PreAuthorize("hasAnyAuthority('PERM_quote','PERM_myRecords','PERM_allRecords')")
    ApiResponse<?> upload(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty() || file.getSize()>10L*1024*1024) throw AppException.unprocessable("每张报价单图片须大于0且不超过10MB");
        var bytes=file.getBytes();
        if (!Set.of("image/png","image/jpeg","image/webp").contains(AssetStorageService.detectImage(bytes))) throw AppException.unprocessable("报价单图片仅支持PNG、JPEG、WebP");
        var asset=storage.storeImage(bytes,file.getOriginalFilename());
        audit.record("quotation.photo.upload","asset",asset.id.toString(),"success",Map.of("bytes",asset.sizeBytes));
        return ApiResponse.ok(Map.of("assetId",asset.id.toString(),"name",asset.originalName));
    }
}
