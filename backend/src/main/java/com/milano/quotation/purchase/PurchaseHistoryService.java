package com.milano.quotation.purchase;

import com.milano.quotation.audit.AuditLogRepository;
import com.milano.quotation.audit.AuditService;
import com.milano.quotation.security.QuotationPrincipal;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import java.time.Instant;
import java.util.*;

@Service
public class PurchaseHistoryService {
    private static final String RESOURCE = "purchase-product-history";
    private static final Map<String, String> FIELDS = new LinkedHashMap<>();
    static {
        String[][] fields = {
            {"sku","SKU"},{"category","类别"},{"productImage","产品图片"},{"physicalImage","实物图"},
            {"quotationOwner","报价人"},{"quotationDate","报价日期"},{"size","尺码"},{"color","颜色"},{"material","材质"},
            {"weightG","克重(g)"},{"lengthCm","长(cm)"},{"widthCm","宽(cm)"},{"heightCm","高(cm)"},
            {"minOrderQty","起订量(件)"},{"purchasePriceCny","基准采购单价(CNY/件)"},
            {"tier2MinQty","阶梯价2起订量"},{"tier2PriceCny","阶梯价2(CNY/件)"},{"tier3MinQty","阶梯价3起订量"},{"tier3PriceCny","阶梯价3(CNY/件)"},
            {"singleFreightCny","1件总运费(CNY)"},{"freight10Cny","10件总运费(CNY)"},{"freight100Cny","100件总运费(CNY)"},
            {"freeShipping","是否包邮"},{"taxIncludedPriceCny","含票价(CNY/件)"},{"taxPoint","票点"},{"invoiceType","票类型"},
            {"stockStatus","是否有货"},{"notes","备注"},{"factoryInfo","工厂信息"},{"sourceLink1","货源链接1"},
            {"sourceLink2","货源链接2"},{"sourceLink3","货源链接3"},{"similarSource","相似货源"},{"auditNotes","审核备注"},
            {"catalogState","目录状态"}
        };
        for (var field : fields) FIELDS.put(field[0], field[1]);
    }
    private final AuditService audit;
    private final AuditLogRepository logs;
    public PurchaseHistoryService(AuditService audit, AuditLogRepository logs) { this.audit = audit; this.logs = logs; }

    // Joins the product transaction: failed saves must never leave a successful history entry.
    @Transactional
    public void record(UUID productId, JsonNode before, JsonNode after, String operation) {
        var changes = new ArrayList<Change>();
        FIELDS.forEach((field, label) -> {
            var oldValue = value(before, field); var newValue = value(after, field);
            boolean equal = oldValue.isNumber() && newValue.isNumber()
                    ? oldValue.decimalValue().compareTo(newValue.decimalValue()) == 0 : oldValue.equals(newValue);
            if (!equal) changes.add(new Change(field, label, oldValue, newValue));
        });
        if (changes.isEmpty()) return;
        var auth = SecurityContextHolder.getContext().getAuthentication();
        var name = auth != null && auth.getPrincipal() instanceof QuotationPrincipal principal ? principal.displayName() : "";
        var detail = new LinkedHashMap<String, Object>();
        detail.put("actorName", name == null ? "" : name);
        detail.put("sku", after.path("sku").asText());
        detail.put("operation", operation);
        detail.put("changes", changes);
        audit.record("purchase.maintenance", RESOURCE, productId.toString(), "success", detail);
    }
    private static JsonNode value(JsonNode snapshot, String field) {
        var value = snapshot == null ? null : snapshot.get(field);
        if (value == null || value.isNull() || (value.isTextual() && value.asText().isEmpty())) return JsonNodeFactory.instance.nullNode();
        return value.deepCopy();
    }
    @Transactional(readOnly = true)
    public Page<Entry> page(UUID productId, Pageable pageable) {
        return logs.findByResourceTypeAndResourceIdOrderByCreatedAtDescIdDesc(RESOURCE, productId.toString(), pageable)
                .map(log -> new Entry(log.id, log.createdAt, log.actorAccount, log.detail.path("actorName").asText(""),
                        log.detail.path("sku").asText(), log.detail.path("operation").asText(), log.detail.path("changes")));
    }
    public record Change(String field, String label, JsonNode before, JsonNode after) {}
    public record Entry(UUID id, Instant createdAt, String actorAccount, String actorName, String sku, String operation, JsonNode changes) {}
}
