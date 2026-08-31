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
    private final LogisticsBillingAcceptanceService billing;
    public LogisticsRebuildController(LogisticsDatasetService datasets,LogisticsImportService imports,LogisticsExportService exports,
            LogisticsService logistics,LogisticsVersionRepository versions,AssetStorageService storage,IdempotencyService idempotency,AuditService audit,LogisticsDatasetGuard guard,LogisticsBillingAcceptanceService billing){
        this.billing=billing;
        this.guard=guard;
        this.datasets=datasets;this.imports=imports;this.exports=exports;this.logistics=logistics;this.versions=versions;this.storage=storage;this.idempotency=idempotency;this.audit=audit;
    }
    @GetMapping("/datasets") public ApiResponse<?> datasets(){return ApiResponse.ok(datasets.list());}
    @GetMapping("/downloads/prepare")
    public ApiResponse<?> prepareDownload(@RequestParam String kind,@RequestParam UUID id,@RequestParam(required=false)UUID versionId,
            @RequestParam(defaultValue="0")int index,@RequestParam(defaultValue="")String query,@RequestParam(defaultValue="")String country,@RequestParam(defaultValue="")String attribute){
        String path,name;var params=new LinkedHashMap<String,String>();
        switch(kind){
            case "prices" -> {datasets.dataset(id);path="/datasets/"+id+"/prices.xlsx";name="物流价格.xlsx";
                params.put("query",query);params.put("country",country);params.put("attribute",attribute);if(versionId!=null)params.put("versionId",versionId.toString());
                params.put("snapshot",exports.priceSnapshot(id,versionId,query,country,attribute));}
            case "version-diff" -> {if(!versions.existsById(id))throw AppException.notFound("物流版本不存在");path="/versions/"+id+"/changes.xlsx";name="版本变化.xlsx";}
            case "batch-diff" -> {imports.get(id);path="/imports/"+id+"/changes.xlsx";name="批次价格变化.xlsx";}
            case "source", "evidence" -> {var payload=imports.get(id).path("payload");var files=payload.path("files");if(index<0||index>=files.size())throw AppException.notFound("原文件不存在");
                path="/imports/"+id+"/files/"+index;name=files.get(index).path("name").asText();
                if(kind.equals("evidence")){if(payload.path("fileReports").path(index).path("sourceEvidence").path("objectKey").asText().isBlank())throw AppException.notFound("该批次没有独立解析证据");path+="/evidence";name="原表解析证据.json";}}
            default -> throw AppException.unprocessable("不支持的下载类型");
        }
        var uri=org.springframework.web.util.UriComponentsBuilder.fromPath("/api/v1/logistics/rebuild"+path);params.forEach(uri::queryParam);
        audit.record("logistics.download-prepare","logistics-download",id.toString(),"success",Map.of("kind",kind));
        return ApiResponse.ok(Map.of("url",uri.build().encode().toUriString(),"filename",name));
    }
    @GetMapping("/datasets/{id}/required-channels") public ApiResponse<?> required(@PathVariable UUID id){return ApiResponse.ok(datasets.requiredChannels(id));}
    @PutMapping("/datasets/{id}/required-channels") @Transactional
    public ApiResponse<?> required(@PathVariable UUID id,@RequestBody ObjectNode input,@RequestHeader("Idempotency-Key")String key,Authentication auth){
        var actor=actor(auth);input.put("datasetId",id.toString());guard.request(actor,"logistics-required",key);
        var prior=idempotency.existing(actor,"logistics-required",key,input);if(prior.isPresent())return ApiResponse.ok(prior.get());
        var result=datasets.saveRequiredChannels(id,input,actor);idempotency.save(actor,"logistics-required",key,input,result);
        audit.record("logistics.required-confirm","logistics-dataset",id.toString(),"success",Map.of("revision",result.path("revision").asLong()));return ApiResponse.ok(result);
    }
    @GetMapping("/versions/{id}/billing-acceptance") public ApiResponse<?> billing(@PathVariable UUID id){return ApiResponse.ok(billing.status(id));}
    @PostMapping("/versions/{id}/billing-acceptance") @Transactional
    public ApiResponse<?> billing(@PathVariable UUID id,@RequestBody ObjectNode input,@RequestHeader("Idempotency-Key")String key,Authentication auth){
        var actor=actor(auth);input.put("versionId",id.toString());guard.request(actor,"logistics-billing-accept",key);
        var prior=idempotency.existing(actor,"logistics-billing-accept",key,input);if(prior.isPresent())return ApiResponse.ok(prior.get());
        var result=billing.approve(id,input,actor);idempotency.save(actor,"logistics-billing-accept",key,input,result);
        audit.record("logistics.billing-accept","logistics-version",id.toString(),"success",Map.of("engineVersion",LogisticsBillingEngine.VERSION));return ApiResponse.ok(result);
    }
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
    @GetMapping("/imports/{id}/files/{index}/evidence")
    public ResponseEntity<InputStreamResource> evidence(@PathVariable UUID id,@PathVariable int index){
        var reports=imports.get(id).path("payload").path("fileReports");if(index<0||index>=reports.size())throw AppException.notFound("解析证据不存在");
        var source=reports.get(index).path("sourceEvidence");if(source.path("objectKey").asText().isBlank())throw AppException.notFound("该批次没有独立证据文件，请查阅原文件");
        audit.record("logistics.evidence-download","logistics-import",id.toString(),"success",Map.of("fileIndex",index));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.CONTENT_DISPOSITION,attachment("原表解析证据.json"))
                .body(new InputStreamResource(storage.openRaw(source.path("objectKey").asText())));
    }
    @GetMapping("/versions/{id}") public ApiResponse<?> version(@PathVariable UUID id){var v=versions.findById(id).orElseThrow(()->AppException.notFound("物流版本不存在"));return ApiResponse.ok(((ObjectNode)v.payload.deepCopy()).put("quoteReady",guard.quoteReady(id)));}
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
            @RequestParam(defaultValue="")String query,@RequestParam(defaultValue="")String country,@RequestParam(defaultValue="")String attribute,@RequestParam(required=false)String snapshot){
        var bytes=exports.prices(id,versionId,query,country,attribute,snapshot);audit.record("logistics.price-export","logistics-dataset",id.toString(),"success",Map.of("rowsScope","all-filtered"));return excel(bytes,"物流价格.xlsx");
    }
    @GetMapping("/imports/{id}/changes.xlsx") public ResponseEntity<byte[]> changes(@PathVariable UUID id){var bytes=exports.changes(id,null);audit.record("logistics.diff-export","logistics-import",id.toString(),"success",Map.of());return excel(bytes,"批次价格变化.xlsx");}
    @GetMapping("/versions/{id}/changes.xlsx") public ResponseEntity<byte[]> versionChanges(@PathVariable UUID id){var bytes=exports.changes(null,id);audit.record("logistics.diff-export","logistics-version",id.toString(),"success",Map.of());return excel(bytes,"版本变化.xlsx");}
    static ResponseEntity<byte[]> excel(byte[] bytes,String name){return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).header(HttpHeaders.CONTENT_DISPOSITION,attachment(name)).body(bytes);}
    static String attachment(String name){return ContentDisposition.attachment().filename(name,StandardCharsets.UTF_8).build().toString();}
    private static String actor(Authentication auth){return ((QuotationPrincipal)auth.getPrincipal()).account();}
    private static ArrayNode mappings(ObjectNode input){if(!input.has("mappings"))return JsonNodeFactory.instance.arrayNode();if(!input.path("mappings").isArray())throw AppException.unprocessable("渠道映射格式错误");return (ArrayNode)input.path("mappings");}
}
