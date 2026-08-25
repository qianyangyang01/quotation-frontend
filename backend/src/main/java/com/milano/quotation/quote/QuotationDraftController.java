package com.milano.quotation.quote;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.NullNode;
import tools.jackson.databind.node.ObjectNode;
import com.milano.quotation.common.ApiResponse;
import com.milano.quotation.common.AppException;
import com.milano.quotation.security.QuotationPrincipal;
import com.milano.quotation.purchase.PurchaseProductService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;

@RestController @RequestMapping("/api/v1/quotation-drafts") @PreAuthorize("hasAuthority('PERM_quote')")
public class QuotationDraftController {
    private static final int MAX_DRAFT_BYTES = 1_000_000;
    private static final Set<String> DRAFT_FIELDS = Set.of(
            "schemaVersion", "customerName", "quoteMode", "skuSearch", "productCategory",
            "logisticsAttribute", "selectedCustomerGrade", "selectedTaxCustomerType",
            "monthlySalesEstimate", "customQuoteQuantity", "quoteMatrixMode",
            "selectedQuoteRegions", "product", "bundleItems", "commonSelections",
            "specifiedSelections", "templateSelections", "activeTemplate");
    private final QuotationDraftRepository drafts;private final PurchaseProductService products; public QuotationDraftController(QuotationDraftRepository drafts,PurchaseProductService products){this.drafts=drafts;this.products=products;}
    @GetMapping("/mine") @Transactional(readOnly=true) ApiResponse<JsonNode> get(Authentication auth){return ApiResponse.ok(drafts.findById(account(auth)).map(row->row.payload).orElse(null));}
    @PutMapping("/mine") @Transactional ApiResponse<JsonNode> put(@RequestBody JsonNode body,Authentication auth){products.lockStructuredReferences(body);var account=account(auth);var row=drafts.findById(account).orElseGet(()->{var draft=new QuotationDraftEntity();draft.ownerAccount=account;return draft;});row.payload=body.deepCopy();if(row.payload instanceof ObjectNode payload)payload.remove("customerId");row.updatedAt=Instant.now();drafts.save(row);return ApiResponse.ok(row.payload);}
    @DeleteMapping("/mine") @Transactional ApiResponse<Void> delete(Authentication auth){drafts.deleteById(account(auth));return ApiResponse.ok(null);}

    @GetMapping("/mine/state") @Transactional(readOnly=true)
    ApiResponse<JsonNode> state(Authentication auth){
        return ApiResponse.ok(drafts.findById(account(auth)).map(this::view).orElseGet(this::emptyView));
    }

    @PutMapping("/mine/state") @Transactional
    ApiResponse<JsonNode> saveState(@RequestBody JsonNode body,@RequestHeader("If-Match") long expectedVersion,Authentication auth){
        var payload=validated(body);products.lockStructuredReferences(payload);var owner=account(auth);var existing=drafts.findById(owner);
        if(existing.isEmpty()){
            if(expectedVersion!=-1)throw AppException.conflict("草稿已经变化，请重新加载后继续");
            var row=new QuotationDraftEntity();row.ownerAccount=owner;row.payload=payload;row.updatedAt=Instant.now();
            drafts.saveAndFlush(row);return ApiResponse.ok(view(row));
        }
        var row=existing.get();
        if(row.version!=expectedVersion)throw AppException.conflict("草稿已在另一个页面更新，请选择加载服务器草稿或明确覆盖");
        if(row.payload.equals(payload))return ApiResponse.ok(view(row));
        row.payload=payload;row.updatedAt=Instant.now();drafts.saveAndFlush(row);return ApiResponse.ok(view(row));
    }

    @DeleteMapping("/mine/state") @Transactional
    ApiResponse<Void> deleteState(@RequestHeader("If-Match") long expectedVersion,Authentication auth){
        var existing=drafts.findById(account(auth));
        if(existing.isEmpty())return ApiResponse.ok(null);
        if(existing.get().version!=expectedVersion)throw AppException.conflict("草稿已在另一个页面更新，未清除较新的草稿");
        drafts.delete(existing.get());return ApiResponse.ok(null);
    }

    private ObjectNode validated(JsonNode body){
        if(!(body instanceof ObjectNode input)||body.toString().length()>MAX_DRAFT_BYTES)throw AppException.unprocessable("草稿格式错误或内容过大");
        if(input.path("schemaVersion").asInt()!=2)throw AppException.unprocessable("草稿版本不受支持");
        input.propertyNames().forEach(key->{if(!DRAFT_FIELDS.contains(key))throw AppException.unprocessable("草稿包含不支持的字段："+key);});
        rejectSensitive(input);
        return input.deepCopy();
    }
    private void rejectSensitive(JsonNode node){
        if(node.isTextual()&&node.asText("").stripLeading().toLowerCase(Locale.ROOT).startsWith("data:"))throw AppException.unprocessable("草稿不能包含Base64或Data URL");
        if(node.isArray()){node.forEach(this::rejectSensitive);return;}
        if(!node.isObject())return;
        node.properties().forEach(entry->{
            var key=entry.getKey().toLowerCase(Locale.ROOT).replaceAll("[-_]","");
            if(key.contains("password")||key.contains("session")||key.contains("cookie")||key.contains("customerid")||key.equals("token")||key.equals("logisticsrules")||key.equals("workspace"))throw AppException.unprocessable("草稿包含禁止保存的字段");
            rejectSensitive(entry.getValue());
        });
    }
    private ObjectNode emptyView(){var result=JsonNodeFactory.instance.objectNode();result.put("exists",false);result.set("payload",NullNode.instance);result.put("version",-1);result.set("updatedAt",NullNode.instance);return result;}
    private ObjectNode view(QuotationDraftEntity row){var result=JsonNodeFactory.instance.objectNode();result.put("exists",true);result.set("payload",row.payload.deepCopy());result.put("version",row.version);result.put("updatedAt",row.updatedAt.toString());return result;}
    private static String account(Authentication auth){return ((QuotationPrincipal)auth.getPrincipal()).account();}
}
