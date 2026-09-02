package com.milano.quotation.finance;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import com.milano.quotation.audit.AuditService;
import com.milano.quotation.common.ApiResponse;
import com.milano.quotation.common.AppException;
import com.milano.quotation.logistics.LogisticsDatasetService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController @RequestMapping("/api/v1/finance-settings")
public class FinanceSettingController {
    private static final List<String> KEYS=List.of("country-classification","channel-policies","customer-grades","exchange-rate","tax-settings");
    private final FinanceSettingRepository settings; private final AuditService audit; private final LogisticsDatasetService logisticsDatasets;
    public FinanceSettingController(FinanceSettingRepository settings, AuditService audit, LogisticsDatasetService logisticsDatasets){this.settings=settings;this.audit=audit;this.logisticsDatasets=logisticsDatasets;}
    @GetMapping @PreAuthorize("isAuthenticated()") @Transactional(readOnly=true) ApiResponse<Map<String,JsonNode>> all(){
        return ApiResponse.ok(settings.findAll().stream().collect(java.util.stream.Collectors.toMap(row->row.key,row->view(row))));
    }
    @GetMapping("/{key}") @PreAuthorize("isAuthenticated()") @Transactional(readOnly=true) ApiResponse<JsonNode> get(@PathVariable String key){ validateKey(key); return ApiResponse.ok(settings.findById(key).map(this::view).orElse(null)); }
    @GetMapping("/logistics-required-previews") @PreAuthorize("isAuthenticated()") ApiResponse<?> logisticsRequiredPreviews(){return ApiResponse.ok(logisticsDatasets.preparingRequiredPreviews());}
    @GetMapping("/logistics-required-previews/{datasetId}") @PreAuthorize("isAuthenticated()") ApiResponse<?> logisticsRequiredPreview(@PathVariable UUID datasetId){return ApiResponse.ok(logisticsDatasets.requiredChannelPreview(datasetId));}
    @PutMapping("/{key}") @PreAuthorize("hasAuthority('PERM_finance')") @Transactional ApiResponse<JsonNode> put(@PathVariable String key,@RequestBody JsonNode body,@RequestHeader("If-Match") long expectedVersion){
        validateKey(key); if(body.toString().length()>2_000_000) throw AppException.unprocessable("财务设置数据过大");
        var existing=settings.findById(key);if(existing.isPresent()&&existing.get().version!=expectedVersion)throw AppException.conflict("财务设置已被其他用户修改，请刷新后重试");if(existing.isEmpty()&&expectedVersion!=-1)throw AppException.conflict("财务设置版本不一致，请刷新后重试");
        var row=existing.orElseGet(()->FinanceSetting.create(key,body.deepCopy())); row.payload=body.deepCopy(); row.updatedAt=Instant.now(); settings.saveAndFlush(row);
        audit.record("finance.update","finance-setting",key,"success",Map.of()); return ApiResponse.ok(view(row));
    }
    private JsonNode view(FinanceSetting row){var wrapper=tools.jackson.databind.node.JsonNodeFactory.instance.objectNode();wrapper.set("value",row.payload.deepCopy());wrapper.put("_version",row.version);wrapper.put("_updatedAt",row.updatedAt.toString());return wrapper;}
    private static void validateKey(String key){if(!KEYS.contains(key)) throw AppException.notFound("财务设置不存在");}
}
