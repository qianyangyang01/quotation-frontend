package com.milano.quotation.purchase;

import com.milano.quotation.common.AppException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/** Sparse paste patches; preview and commit share the same merge and validation. */
@Service
public class PurchasePasteService {
    private static final Set<String> NUMBERS = Set.of("weightG", "lengthCm", "widthCm", "heightCm",
            "minOrderQty", "purchasePriceCny", "tier2MinQty", "tier2PriceCny", "tier3MinQty",
            "tier3PriceCny", "singleFreightCny", "freight10Cny", "freight100Cny", "taxIncludedPriceCny", "taxPoint");
    private static final Set<String> TEXT = Set.of("sku", "quotationDate", "quotationOwner", "notes",
            "size", "color", "material", "freeShipping", "invoiceType", "category", "stockStatus",
            "factoryInfo", "auditNotes", "sourceLink1", "sourceLink2", "sourceLink3", "similarSource");
    private final PurchaseProductRepository repository;
    private final PurchaseProductService products;

    public PurchasePasteService(PurchaseProductRepository repository, PurchaseProductService products) {
        this.repository = repository;
        this.products = products;
    }

    @Transactional(readOnly = true)
    public Preview preview(List<JsonNode> rows) {
        var batch = parse(rows);
        var existing = repository.findAllBySkuIn(batch.inputs.keySet()).stream()
                .collect(Collectors.toMap(p -> p.sku, p -> p));
        return prepare(batch, existing);
    }

    @Transactional
    public Result confirm(Confirmation input) {
        if (input == null || input.expected == null) throw AppException.unprocessable("请先预览采购数据");
        var batch = parse(input.rows);
        // Lock in SKU order, including unchanged rows. Missing rows are protected by the unique SKU constraint.
        var existing = repository.findAllLockedBySkuIn(batch.inputs.keySet()).stream()
                .collect(Collectors.toMap(p -> p.sku, p -> p));
        var expected = new HashMap<String, Expected>();
        for (var entry : input.expected) {
            if (entry == null || entry.sku == null || expected.put(entry.sku, entry) != null)
                throw AppException.unprocessable("预览版本格式错误");
        }
        if (!expected.keySet().equals(batch.inputs.keySet())) throw AppException.conflict("粘贴内容已变化，请重新预览");
        for (var sku : batch.inputs.keySet()) {
            var row = existing.get(sku);
            var e = expected.get(sku);
            if (row == null ? e.productId != null || e.version != null || e.updatedAt != null
                    : !row.id.equals(e.productId) || !Objects.equals(row.version, e.version) || !row.updatedAt.equals(e.updatedAt))
                throw AppException.conflict("商品 " + sku + " 已变化，请重新预览并确认；本批次未保存");
        }
        var preview = prepare(batch, existing); // Validate the whole batch before any write.
        var added = new ArrayList<JsonNode>();
        var updated = new ArrayList<JsonNode>();
        var unchanged = new ArrayList<String>();
        for (var row : preview.rows) {
            if ("unchanged".equals(row.action)) { unchanged.add(row.sku); continue; }
            var saved = products.savePasted(row.effective.deepCopy());
            ("create".equals(row.action) ? added : updated).add(saved);
        }
        return new Result(added, updated, unchanged, preview.skipped);
    }

    private Preview prepare(Batch batch, Map<String, PurchaseProduct> existing) {
        var rows = new ArrayList<PreviewRow>();
        for (var entry : batch.inputs.entrySet()) {
            var sku = entry.getKey();
            var patch = entry.getValue();
            var current = existing.get(sku);
            var merged = current == null ? JsonNodeFactory.instance.objectNode() : (ObjectNode) current.payload.deepCopy();
            for (var field : patch.properties()) if (!"sourceRow".equals(field.getKey())) merged.set(field.getKey(), field.getValue().deepCopy());
            int sourceRow = patch.path("sourceRow").asInt();
            if (current == null) {
                merged.put("dataSource", "standard").put("skuOrigin", "manual").put("sourceSheet", "采购粘贴新增").put("sourceRow", sourceRow);
            } else {
                merged.put("_version", current.version);
                merged.put("catalogState", current.catalogState);
            }
            PurchaseProductService.normalizeLegacyPrice(merged);
            PurchasePasteValidator.validateValues(merged, sourceRow, "legacy_2026".equals(merged.path("dataSource").asText()));
            var notices = new ArrayList<String>();
            if (patch.has("purchasePriceCny") && patch.get("purchasePriceCny").decimalValue().compareTo(merged.path("purchasePriceCny").decimalValue()) != 0)
                notices.add("旧数据按原有价格规则计算：本次填写采购单价 " + patch.get("purchasePriceCny").asText()
                        + "，最终生效采购单价 " + merged.path("purchasePriceCny").asText() + "；如需调整，请同时核对含票价。");
            var changes = PurchaseHistoryService.changes(current == null ? null : current.payload, merged);
            var expectation = new Expected(sku, current == null ? null : current.id,
                    current == null ? null : current.version, current == null ? null : current.updatedAt);
            rows.add(new PreviewRow(sourceRow, sku, current == null ? "create" : changes.isEmpty() ? "unchanged" : "update",
                    expectation, changes, notices, merged));
        }
        return new Preview(rows, batch.skipped);
    }

    private Batch parse(List<JsonNode> rows) {
        if (rows == null || rows.isEmpty() || rows.size() > 100) throw AppException.unprocessable("采购粘贴每次须为1至100条");
        var inputs = new LinkedHashMap<String, ObjectNode>();
        var skipped = new ArrayList<Skipped>();
        for (int i = 0; i < rows.size(); i++) {
            if (!(rows.get(i) instanceof ObjectNode node)) throw AppException.unprocessable("第" + (i + 1) + "行商品格式错误");
            int sourceRow = node.path("sourceRow").asInt(i + 1);
            if (sourceRow < 1 || sourceRow > 100) throw AppException.unprocessable("粘贴行号须为1至100");
            if (!node.path("sku").isTextual()) throw AppException.unprocessable("第" + sourceRow + "行：请填写正式SKU");
            var sku = node.path("sku").asText().toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
            if (!sku.matches("[A-Z0-9._/-]{1,96}") || sku.matches("(?i)^(TESTP|TEST|DEMO|MOCK|AUTO-).*$"))
                throw AppException.unprocessable("第" + sourceRow + "行：请填写有效的正式SKU");
            if (inputs.containsKey(sku)) { skipped.add(new Skipped(sourceRow, sku)); continue; }
            var patch = JsonNodeFactory.instance.objectNode().put("sku", sku).put("sourceRow", sourceRow);
            for (var field : node.properties()) {
                var name = field.getKey(); var value = field.getValue();
                if ("sku".equals(name) || "sourceRow".equals(name)) continue;
                if (!NUMBERS.contains(name) && !TEXT.contains(name)) throw AppException.unprocessable("不允许粘贴修改字段：" + name);
                if (value.isNull() || value.isTextual() && value.asText().isBlank()) continue;
                if (NUMBERS.contains(name) ? !value.isNumber() : !value.isTextual())
                    throw AppException.unprocessable("第" + sourceRow + "行：" + name + "格式错误");
                if (NUMBERS.contains(name) && (!Double.isFinite(value.asDouble()) || value.asDouble() < 0))
                    throw AppException.unprocessable("第" + sourceRow + "行：" + name + "须为有效非负数字");
                patch.set(name, value.isTextual() ? JsonNodeFactory.instance.textNode(value.asText().trim()) : value.deepCopy());
            }
            inputs.put(sku, patch);
        }
        return new Batch(inputs, skipped);
    }

    private record Batch(LinkedHashMap<String, ObjectNode> inputs, List<Skipped> skipped) {}
    public record Expected(String sku, UUID productId, Long version, Instant updatedAt) {}
    public record Skipped(int sourceRow, String sku) {}
    public record PreviewRow(int sourceRow, String sku, String action, Expected expected,
                             List<PurchaseHistoryService.Change> changes, List<String> notices, ObjectNode effective) {}
    public record Preview(List<PreviewRow> rows, List<Skipped> skipped) {}
    public record Confirmation(List<JsonNode> rows, List<Expected> expected) {}
    public record Result(List<JsonNode> added, List<JsonNode> updated, List<String> unchanged, List<Skipped> skipped) {}
}
