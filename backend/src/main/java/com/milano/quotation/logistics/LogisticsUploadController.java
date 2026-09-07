package com.milano.quotation.logistics;

import com.milano.quotation.common.ApiResponse;
import com.milano.quotation.audit.AuditService;
import com.milano.quotation.security.QuotationPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/logistics/rebuild")
@PreAuthorize("hasAuthority('PERM_logistics')")
public class LogisticsUploadController {
    private final LogisticsUploadService uploads;
    private final AuditService audit;
    public LogisticsUploadController(LogisticsUploadService uploads,AuditService audit){this.uploads=uploads;this.audit=audit;}
    @PostMapping("/datasets/{dataset}/uploads")
    public ApiResponse<?> start(@PathVariable UUID dataset,@RequestBody LogisticsUploadService.Manifest manifest,
                               @RequestHeader("Idempotency-Key") String key,Authentication auth){
        var result=uploads.start(dataset,actor(auth),key,manifest);
        audit.record("logistics.upload-start","logistics-upload",result.path("id").asText(),"success",java.util.Map.of("datasetId",dataset,"files",manifest.files().size()));
        return ApiResponse.ok(result);
    }
    @PostMapping("/uploads/{id}/files/{file}/chunks/{chunk}")
    public ApiResponse<?> chunk(@PathVariable UUID id,@PathVariable int file,@PathVariable int chunk,
                               @RequestParam("chunk") MultipartFile content,@RequestParam String sha256,Authentication auth){
        return ApiResponse.ok(uploads.chunk(id,actor(auth),file,chunk,content,sha256));
    }
    @PostMapping("/uploads/{id}/complete")
    public ApiResponse<?> complete(@PathVariable UUID id,Authentication auth){
        var result=uploads.complete(id,actor(auth));
        audit.record("logistics.upload-complete","logistics-upload",id.toString(),"success",java.util.Map.of("batchId",result.path("id").asText()));
        return ApiResponse.ok(result);
    }
    private static String actor(Authentication auth){return ((QuotationPrincipal)auth.getPrincipal()).account();}
}
