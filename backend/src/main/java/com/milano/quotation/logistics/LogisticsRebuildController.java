package com.milano.quotation.logistics;

import com.milano.quotation.audit.AuditService;
import com.milano.quotation.common.ApiResponse;
import com.milano.quotation.common.AppException;
import com.milano.quotation.idempotency.IdempotencyService;
import com.milano.quotation.security.QuotationPrincipal;
import com.milano.quotation.storage.AssetStorageService;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
@RequestMapping("/api/v1/logistics/rebuild")
@PreAuthorize("hasAuthority('PERM_logistics')")
public class LogisticsRebuildController {
    private final LogisticsDatasetService datasets;
    private final LogisticsImportService imports;
    private final LogisticsExportService exports;
    private final LogisticsService logistics;
    private final LogisticsVersionRepository versions;
    private final AssetStorageService storage;
    private final IdempotencyService idempotency;
    private final AuditService audit;
    private final LogisticsDatasetGuard guard;
    public LogisticsRebuildController(LogisticsDatasetService datasets,LogisticsImportService imports,LogisticsExportService exports,
            LogisticsService logistics,LogisticsVersionRepository versions,AssetStorageService storage,IdempotencyService idempotency,AuditService audit,LogisticsDatasetGuard guard){
        this.guard=guard;
        this.datasets=datasets;this.imports=imports;this.exports=exports;this.logistics=logistics;this.versions=versions;this.storage=storage;this.idempotency=idempotency;this.audit=audit;
    }
    @GetMapping("/datasets") public ApiResponse<?> datasets(){return ApiResponse.ok(datasets.list());}
    @PostMapping("/datasets") @Transactional
    public ApiResponse<?> create(@RequestBody ObjectNode body,@RequestHeader("Idempotency-Key")String key,Authentication auth){
        var actor=actor(auth);guard.request(actor,"logistics-dataset-create",key);var existing=idempotency.existing(actor,"logistics-dataset-create",key,body);if(existing.isPresent())return ApiResponse.ok(existing.get());
        var result=datasets.create(body.path("name").asText(),actor);idempotency.save(actor,"logistics-dataset-create",key,body,result);
        audit.record("logistics.dataset-create","logistics-dataset",result.path("id").asText(),"success",Map.of("actor",actor));return ApiResponse.ok(result);
    }
    @GetMapping("/datasets/{id}/workspace") public ApiResponse<?> workspace(@PathVariable UUID id){return ApiResponse.ok(datasets.workspace(id));}
    @GetMapping("/datasets/{id}/prices") public ApiResponse<?> pricesPage(@PathVariable UUID id,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="50")int size,
            @RequestParam(defaultValue="")String query,@RequestParam(defaultValue="")String country,@RequestParam(defaultValue="")String attribute){return ApiResponse.ok(datasets.prices(id,page,size,query,country,attribute));}
    @PostMapping("/datasets/{id}/preview") public ApiResponse<?> preview(@PathVariable UUID id,@RequestBody ObjectNode input){return ApiResponse.ok(datasets.preview(id,mappings(input)));}
    @PostMapping("/datasets/{id}/backup") public ApiResponse<?> backup(@PathVariable UUID id,Authentication auth){
        var result=datasets.backup(id,actor(auth));audit.record("logistics.backup","logistics-dataset",id.toString(),"success",Map.of("sha256",result.path("sha256").asText()));return ApiResponse.ok(result);
    }
    @PostMapping("/datasets/{id}/activate") @Transactional
    public ApiResponse<?> activate(@PathVariable UUID id,@RequestBody ObjectNode input,@RequestHeader("Idempotency-Key")String key,Authentication auth){
        input.put("datasetId",id.toString());var actor=actor(auth);guard.request(actor,"logistics-dataset-activate",key);var existing=idempotency.existing(actor,"logistics-dataset-activate",key,input);if(existing.isPresent())return ApiResponse.ok(existing.get());
        var result=datasets.activate(id,input,actor);idempotency.save(actor,"logistics-dataset-activate",key,input,result);
        audit.record("logistics.dataset-activate","logistics-dataset",id.toString(),"success",Map.of("actor",actor,"sourceDatasetId",result.path("sourceDatasetId").asText()));return ApiResponse.ok(result);
    }
    @PostMapping("/datasets/{id}/imports")
    public ApiResponse<?> upload(@PathVariable UUID id,@RequestParam("files")List<MultipartFile> files,@RequestParam(defaultValue="false")boolean replaceDrafts,
                                @RequestHeader("Idempotency-Key")String key,Authentication auth){
        var result=imports.upload(id,files,actor(auth),key,replaceDrafts);audit.record("logistics.import-submit","logistics-import",result.path("id").asText(),"success",Map.of("fileCount",files.size()));return ApiResponse.ok(result);
    }
    @GetMapping("/datasets/{id}/imports") public ApiResponse<?> imports(@PathVariable UUID id){return ApiResponse.ok(imports.list(id));}
    @GetMapping("/imports/{id}") public ApiResponse<?> batch(@PathVariable UUID id){return ApiResponse.ok(imports.get(id));}
    @PostMapping("/imports/{id}/retry") public ApiResponse<?> retry(@PathVariable UUID id){imports.retry(id);audit.record("logistics.import-retry","logistics-import",id.toString(),"success",Map.of());return ApiResponse.ok(imports.get(id));}
    @GetMapping("/imports/{id}/files/{index}")
    public ResponseEntity<InputStreamResource> original(@PathVariable UUID id,@PathVariable int index){
        var files=imports.get(id).path("payload").path("files");if(index<0||index>=files.size())throw AppException.notFound("原文件不存在");var file=files.get(index);
        audit.record("logistics.source-download","logistics-import",id.toString(),"success",Map.of("fileIndex",index));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).header(HttpHeaders.CONTENT_DISPOSITION,attachment(file.path("name").asText()))
                .body(new InputStreamResource(storage.openRaw(file.path("objectKey").asText())));
    }
    @GetMapping("/versions/{id}") public ApiResponse<?> version(@PathVariable UUID id){var v=versions.findById(id).orElseThrow(()->AppException.notFound("物流版本不存在"));return ApiResponse.ok(v.payload);}
    @PostMapping("/channels/{channel}/versions/{version}/review") @Transactional
    public ApiResponse<?> review(@PathVariable UUID channel,@PathVariable UUID version,@RequestBody ObjectNode input,@RequestHeader("Idempotency-Key")String key,Authentication auth){
        input.put("channelId",channel.toString()).put("versionId",version.toString());var actor=actor(auth);
        guard.request(actor,"logistics-price-review",key);var existing=idempotency.existing(actor,"logistics-price-review",key,input);if(existing.isPresent())return ApiResponse.ok(existing.get());
        var v=versions.findById(version).orElseThrow(()->AppException.notFound("物流版本不存在"));
        if(v.payload.path("summary").path("highRisk").asInt()>0&&!input.path("reviewConfirmed").asBoolean())throw AppException.unprocessable("大幅涨跌需明确确认风险");
        var result=logistics.publishReviewed(channel,version,input.path("note").asText(),actor,input.path("removalConfirmed").asBoolean(),input.path("reviewConfirmed").asBoolean());
        idempotency.save(actor,"logistics-price-review",key,input,result);audit.record("logistics.publish","logistics-version",version.toString(),"success",Map.of("actor",actor));return ApiResponse.ok(result);
    }
    @PostMapping("/channels/{channel}/versions/{version}/recompare") public ApiResponse<?> recompare(@PathVariable UUID channel,@PathVariable UUID version){
        var result=logistics.recompare(channel,version);audit.record("logistics.recompare","logistics-version",version.toString(),"success",Map.of());return ApiResponse.ok(result);
    }
    @GetMapping("/datasets/{id}/prices.xlsx") public ResponseEntity<byte[]> prices(@PathVariable UUID id,@RequestParam(required=false)UUID versionId,
            @RequestParam(defaultValue="")String query,@RequestParam(defaultValue="")String country,@RequestParam(defaultValue="")String attribute){
        var bytes=exports.prices(id,versionId,query,country,attribute);audit.record("logistics.price-export","logistics-dataset",id.toString(),"success",Map.of("rowsScope","all-filtered"));return excel(bytes,"物流价格.xlsx");
    }
    @GetMapping("/imports/{id}/changes.xlsx") public ResponseEntity<byte[]> changes(@PathVariable UUID id){var bytes=exports.changes(id,null);audit.record("logistics.diff-export","logistics-import",id.toString(),"success",Map.of());return excel(bytes,"价格变化.xlsx");}
    @GetMapping("/versions/{id}/changes.xlsx") public ResponseEntity<byte[]> versionChanges(@PathVariable UUID id){var bytes=exports.changes(null,id);audit.record("logistics.diff-export","logistics-version",id.toString(),"success",Map.of());return excel(bytes,"版本变化.xlsx");}
    static ResponseEntity<byte[]> excel(byte[] bytes,String name){return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).header(HttpHeaders.CONTENT_DISPOSITION,attachment(name)).body(bytes);}
    static String attachment(String name){return ContentDisposition.attachment().filename(name,StandardCharsets.UTF_8).build().toString();}
    private static String actor(Authentication auth){return ((QuotationPrincipal)auth.getPrincipal()).account();}
    private static ArrayNode mappings(ObjectNode input){if(!input.has("mappings"))return JsonNodeFactory.instance.arrayNode();if(!input.path("mappings").isArray())throw AppException.unprocessable("渠道映射格式错误");return (ArrayNode)input.path("mappings");}
}
