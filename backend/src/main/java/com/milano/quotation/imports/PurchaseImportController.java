package com.milano.quotation.imports;

import com.milano.quotation.audit.AuditService;
import com.milano.quotation.common.ApiResponse;
import com.milano.quotation.common.AppException;
import com.milano.quotation.idempotency.IdempotencyService;
import com.milano.quotation.purchase.PurchaseProductService;
import com.milano.quotation.security.QuotationPrincipal;
import com.milano.quotation.storage.AssetStorageService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/purchase-imports")
@PreAuthorize("hasAuthority('PERM_purchase')")
public class PurchaseImportController {
    private final PurchaseWorkbookService parser;
    private final ImportJobRepository jobs;
    private final PurchaseImportRowRepository rows;
    private final PurchaseProductService products;
    private final IdempotencyService idempotency;
    private final AuditService audit;
    private final AssetStorageService storage;
    private final AsyncPurchaseImportService asyncImports;
    private final PurchaseImportImageService asyncImages;

    public PurchaseImportController(PurchaseWorkbookService parser, ImportJobRepository jobs,
                                    PurchaseImportRowRepository rows, PurchaseProductService products,
                                    IdempotencyService idempotency, AuditService audit, AssetStorageService storage,
                                    AsyncPurchaseImportService asyncImports, PurchaseImportImageService asyncImages) {
        this.parser = parser;
        this.jobs = jobs;
        this.rows = rows;
        this.products = products;
        this.idempotency = idempotency;
        this.audit = audit;
        this.storage = storage;
        this.asyncImports = asyncImports;
        this.asyncImages = asyncImages;
    }

    @PostMapping(value="/jobs",consumes="multipart/form-data")
    ResponseEntity<ApiResponse<?>> createJob(@RequestPart("file")MultipartFile file,Authentication auth){var job=asyncImports.create(file,account(auth));audit.record("purchase.async-import-create","purchase-import",job.id.toString(),"success",Map.of("file",job.sourceName,"size",file.getSize()));return ResponseEntity.accepted().body(ApiResponse.ok(asyncImports.view(job.id)));}
    @PostMapping(value="/jobs/{id}/image-parts",consumes="multipart/form-data")
    ResponseEntity<ApiResponse<?>> imagePart(@PathVariable UUID id,@RequestParam int partNumber,@RequestPart("file")MultipartFile file){asyncImages.upload(id,partNumber,file);audit.record("purchase.async-import-image-part","purchase-import",id.toString(),"success",Map.of("part",partNumber,"size",file.getSize()));return ResponseEntity.accepted().body(ApiResponse.ok(asyncImports.view(id)));}
    @GetMapping("/jobs") ApiResponse<?> jobs(@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size){return ApiResponse.ok(asyncImports.list(PageRequest.of(Math.max(0,page),Math.min(100,Math.max(1,size)))));}
    @GetMapping("/jobs/{id}") ApiResponse<?> jobView(@PathVariable UUID id){return ApiResponse.ok(asyncImports.view(id));}
    @GetMapping("/jobs/{id}/rows") ApiResponse<?> jobRows(@PathVariable UUID id,@RequestParam(required=false)String status,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="50")int size){return ApiResponse.ok(asyncImports.rowPage(id,status,PageRequest.of(Math.max(0,page),Math.min(200,Math.max(1,size)))));}
    @GetMapping("/jobs/{id}/image-errors") ApiResponse<?> imageErrors(@PathVariable UUID id,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="50")int size){return ApiResponse.ok(asyncImports.imageErrorPage(id,PageRequest.of(Math.max(0,page),Math.min(200,Math.max(1,size)))));}
    @GetMapping("/jobs/{id}/errors.xlsx") ResponseEntity<StreamingResponseBody> errors(@PathVariable UUID id){asyncImports.view(id);StreamingResponseBody body=output->{try(var workbook=new SXSSFWorkbook(100)){var sheet=workbook.createSheet("数据错误");var header=sheet.createRow(0);String[] names={"Excel行号","SKU","状态","处理动作","错误原因"};for(int i=0;i<names.length;i++)header.createCell(i).setCellValue(names[i]);int index=1;for(var status:List.of("error","conflict")){int page=0;while(true){var result=asyncImports.rowPage(id,status,PageRequest.of(page++,1000));for(var item:result){var row=sheet.createRow(index++);row.createCell(0).setCellValue(item.sourceRow());row.createCell(1).setCellValue(item.sku());row.createCell(2).setCellValue(item.status());row.createCell(3).setCellValue(item.action()==null?"":item.action());row.createCell(4).setCellValue(item.error()==null?"":item.error());}if(result.isLast())break;}}var imageSheet=workbook.createSheet("图片错误");var imageHeader=imageSheet.createRow(0);String[] imageNames={"SKU","图片类型","文件名","错误原因"};for(int i=0;i<imageNames.length;i++)imageHeader.createCell(i).setCellValue(imageNames[i]);index=1;int page=0;while(true){var result=asyncImports.imageErrorPage(id,PageRequest.of(page++,1000));for(var item:result){var row=imageSheet.createRow(index++);row.createCell(0).setCellValue(item.sku());row.createCell(1).setCellValue(item.type());row.createCell(2).setCellValue(item.fileName());row.createCell(3).setCellValue(item.error()==null?"":item.error());}if(result.isLast())break;}workbook.write(output);workbook.dispose();}};return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=purchase-import-errors-"+id+".xlsx").body(body);}
    @PostMapping("/jobs/{id}/confirm") ResponseEntity<ApiResponse<?>> confirmJob(@PathVariable UUID id,@RequestHeader("Idempotency-Key")String key,Authentication auth){var request=JsonNodeFactory.instance.objectNode().put("jobId",id.toString());var existing=idempotency.existing(account(auth),"purchase-async-confirm",key,request);if(existing.isPresent())return ResponseEntity.accepted().body(ApiResponse.ok(existing.get()));var job=asyncImports.confirm(id);var response=JsonNodeFactory.instance.objectNode().put("jobId",id.toString()).put("status",job.status);idempotency.save(account(auth),"purchase-async-confirm",key,request,response);audit.record("purchase.async-import-confirm","purchase-import",id.toString(),"success",Map.of("validRows",job.validRows));return ResponseEntity.accepted().body(ApiResponse.ok(response));}
    @PostMapping("/jobs/{id}/retry") ResponseEntity<ApiResponse<?>> retry(@PathVariable UUID id){var job=asyncImports.retry(id);audit.record("purchase.async-import-retry","purchase-import",id.toString(),"success",Map.of());return ResponseEntity.accepted().body(ApiResponse.ok(asyncImports.view(job.id)));}
    @PostMapping("/jobs/{id}/rollback") ResponseEntity<ApiResponse<?>> rollback(@PathVariable UUID id){var job=asyncImports.requestRollback(id);audit.record("purchase.async-import-rollback","purchase-import",id.toString(),"success",Map.of());return ResponseEntity.accepted().body(ApiResponse.ok(asyncImports.view(job.id)));}
    @PostMapping("/jobs/{id}/cancel") ApiResponse<?> cancel(@PathVariable UUID id){var job=asyncImports.cancel(id);audit.record("purchase.async-import-cancel","purchase-import",id.toString(),"success",Map.of());return ApiResponse.ok(asyncImports.view(job.id));}

    @PostMapping(value = "/preview", consumes = "multipart/form-data")
    ApiResponse<?> preview(@RequestPart("file") MultipartFile file, Authentication auth) {
        var result = parser.preview(file, account(auth));
        audit.record("purchase.import-preview", "purchase-import", result.jobId().toString(), "success",
                Map.of("file", result.fileName(), "rows", result.records().size()));
        return ApiResponse.ok(result);
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    ApiResponse<?> job(@PathVariable UUID id) {
        var job = jobs.findById(id).orElseThrow(() -> AppException.notFound("导入任务不存在"));
        return ApiResponse.ok(Map.of(
                "id", job.id,
                "status", job.status,
                "sourceName", job.sourceName,
                "summary", job.payload,
                "records", rows.findByJobIdOrderBySourceRow(id).stream().map(row -> row.payload).toList()));
    }

    @PostMapping("/{id}/confirm")
    @Transactional
    ApiResponse<?> confirm(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                           @RequestBody(required = false) ConfirmInput input, Authentication auth) {
        var job = jobs.findById(id).orElseThrow(() -> AppException.notFound("导入任务不存在"));
        var importMode=input==null||input.importMode()==null||input.importMode().isBlank()?"formal":input.importMode();
        if(!java.util.Set.of("formal","pending_template").contains(importMode))throw AppException.unprocessable("采购导入模式不合法");
        var request = JsonNodeFactory.instance.objectNode().put("jobId", id.toString()).put("importMode",importMode);
        var existing = idempotency.existing(account(auth), "purchase-import-confirm", key, request);
        if (existing.isPresent()) return ApiResponse.ok(existing.get());
        if (!job.status.equals("preview")) throw AppException.conflict("该导入任务不能再次确认");
        if (!job.payload.path("canConfirm").asBoolean(false)) {
            audit.record("purchase.import-confirm", "purchase-import", id.toString(), "rejected",
                    Map.of("reason", "blocking-validation-errors"));
            throw AppException.unprocessable("导入预览存在阻断错误，请修正Excel后重新上传");
        }

        var imported = new ArrayList<JsonNode>();
        var stagedRows = rows.findByJobIdOrderBySourceRow(id);
        for (var row : stagedRows) {
            imported.add(products.upsertImported(row.payload, row.productAssetId, row.physicalAssetId,importMode,job.sourceHash));
        }
        storage.publish(stagedRows.stream()
                .flatMap(row -> java.util.stream.Stream.of(row.productAssetId, row.physicalAssetId))
                .filter(java.util.Objects::nonNull).toList());
        job.status = "completed";
        job.updatedAt = Instant.now();
        job.completedAt = job.updatedAt;
        var response = JsonNodeFactory.instance.objectNode()
                .put("jobId", id.toString())
                .put("imported", imported.size())
                .put("importMode",importMode)
                .put("status", "completed");
        idempotency.save(account(auth), "purchase-import-confirm", key, request, response);
        audit.record("purchase.import-confirm", "purchase-import", id.toString(), "success",
                Map.of("count", imported.size(),"importMode",importMode));
        return ApiResponse.ok(response);
    }

    record ConfirmInput(String importMode) {}

    private static String account(Authentication auth) {
        return ((QuotationPrincipal) auth.getPrincipal()).account();
    }
}
